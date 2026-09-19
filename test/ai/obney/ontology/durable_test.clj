(ns ai.obney.ontology.durable-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [next.jdbc :as jdbc]))

(defn temp-db [] (.toFile (java.nio.file.Files/createTempFile "ontology-durable-" ".sqlite" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn open-store [file] (es/start {:conn {:type :sqlite :database-file (.getAbsolutePath file)} :event-pubsub nil :logger nil}))
(defn cleanup [file]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]] (.delete (java.io.File. (str (.getAbsolutePath file) suffix)))))

(deftest sqlite-close-reopen-replays-revisions-and-retries
  (let [file (temp-db) tenant (random-uuid) store (open-store file)
        ctx {:event-store store :tenant-id tenant}
        saved (try
                (let [oid (h/graph ctx) _ (h/add! ctx oid "urn:parent")
                      input (h/request ctx oid [(assoc (h/concept "urn:child") :broader ["urn:parent"])])
                      result (h/ok! (o/import-contribution! ctx oid input))]
                  {:oid oid :input input :result result :snapshot (o/export-snapshot ctx oid)
                   :contributions (o/get-contributions ctx oid) :sources (o/get-sources ctx oid)})
                (finally (es/stop store)))]
    (try
      (let [reopened (open-store file) ctx2 {:event-store reopened :tenant-id tenant}]
        (try
          (is (= (:snapshot saved) (o/export-snapshot ctx2 (:oid saved))))
          (is (= (:contributions saved) (o/get-contributions ctx2 (:oid saved))))
          (is (= (:sources saved) (o/get-sources ctx2 (:oid saved))))
          (h/add! ctx2 (:oid saved) "urn:later")
          (let [before (h/graph-events ctx2)]
            (is (= (:result saved) (o/import-contribution! ctx2 (:oid saved) (:input saved))))
            (is (= before (h/graph-events ctx2))))
          (is (= (:snapshot saved) (o/export-snapshot ctx2 (:oid saved) (get-in saved [:snapshot :revision]))))
          (is (nil? (o/get-ontology (assoc ctx2 :tenant-id (random-uuid)) (:oid saved))))
          (finally (es/stop reopened))))
      (finally (cleanup file)))))

(deftest actual-sqlite-rejection-leaves-no-graph-or-retry-record
  (let [file (temp-db) store (open-store file) ctx {:event-store store :tenant-id (random-uuid)}
        url (str "jdbc:sqlite:" (.getAbsolutePath file))]
    (try
      (let [oid (h/graph ctx) input (h/request ctx oid [(h/concept "urn:write")]) before (h/capture ctx oid)]
        ;; This is a real database constraint failure, not a substituted append result.
        (jdbc/execute! {:jdbcUrl url} ["CREATE TRIGGER reject_contribution BEFORE INSERT ON events WHEN NEW.type = ':ontology/contribution-imported' BEGIN SELECT RAISE(ABORT, 'deliberate-write-failure'); END"])
        (is (try (h/anomaly? (o/import-contribution! ctx oid input))
                 (catch java.sql.SQLException e
                   (.contains (.getMessage e) "deliberate-write-failure"))))
        (is (= before (h/capture ctx oid)))
        (jdbc/execute! {:jdbcUrl url} ["DROP TRIGGER reject_contribution"])
        (h/ok! (o/import-contribution! ctx oid input))
        (is (= "urn:write" (:uri (o/get-concept-by-uri ctx oid "urn:write"))))
        (is (= 1 (count (o/get-contributions ctx oid)))))
      (finally (es/stop store) (cleanup file)))))

(deftest a-fresh-jvm-reconstructs-the-durable-graph
  (let [file (temp-db) store (open-store file) tenant (random-uuid)
        ctx {:event-store store :tenant-id tenant}
        oid (h/graph ctx)
        _ (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [(h/concept "urn:durable")])))
        expected {:snapshot (o/export-snapshot ctx oid) :sources (o/get-sources ctx oid)
                  :contributions (o/get-contributions ctx oid)}
        errors (java.io.File/createTempFile "ontology-child-stderr-" ".log")]
    (es/stop store)
    (try
      (let [java (str (System/getProperty "java.home") "/bin/java")
            process (-> (ProcessBuilder. ^java.util.List
                          [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m"
                           "ai.obney.ontology.durable-probe" (.getAbsolutePath file) (str tenant) (str oid)])
                        (.redirectError errors) (.start))]
        (when-not (.waitFor process 45 java.util.concurrent.TimeUnit/SECONDS)
          (.destroyForcibly process)
          (throw (ex-info "Fresh JVM replay timed out" {})))
        (is (zero? (.exitValue process)) (slurp errors))
        (is (= expected (clojure.edn/read-string (slurp (.getInputStream process))))))
      (finally (cleanup file) (.delete errors)))))

(deftest sqlite-competing-writes-have-one-revision-winner
  (let [file (temp-db) store (open-store file) ctx {:event-store store :tenant-id (random-uuid)}]
    (try
      (let [oid (h/graph ctx)
            a (dissoc (h/request ctx oid [(h/concept "urn:a")]) :expected-revision)
            b (dissoc (h/request ctx oid [(h/concept "urn:b")]) :expected-revision)
            barrier (java.util.concurrent.CyclicBarrier. 2)
            read-state (requiring-resolve 'ai.obney.ontology.core.state/read-state)
            original @read-state
            n (atom 0)
            results (with-redefs-fn {read-state (fn [c]
                                                (let [s (original c)]
                                                  (when (<= (swap! n inc) 2) (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS))
                                                  s))}
                      #(let [left (future (o/import-contribution! ctx oid a))
                             right (future (o/import-contribution! ctx oid b))]
                         [(deref left 15000 ::timeout) (deref right 15000 ::timeout)]))]
        (is (not-any? #{::timeout} results))
        (is (= 1 (count (filter (complement h/anomaly?) results))))
        (is (= 1 (count (filter #(= :cognitect.anomalies/conflict (:cognitect.anomalies/category %)) results))))
        (is (= 2 (o/get-revision ctx oid)))
        (is (= 1 (count (o/get-contributions ctx oid)))))
      (finally (es/stop store) (cleanup file)))))

(deftest ontology-creation-retry-keys-are-atomic-and-tenant-scoped
  (doseq [backend [:memory :sqlite] same-key? [true false]]
    (let [file (when (= backend :sqlite) (temp-db))
          ctx (if file {:event-store (open-store file) :tenant-id (random-uuid)} (h/context))
          left-id (random-uuid) right-id (if same-key? left-id (random-uuid))
          send (fn [context command-id]
                 (o/create-ontology! context {:name "Independent identity" :request-id command-id}))
          read-state (requiring-resolve 'ai.obney.ontology.core.state/read-state)
          original @read-state
          barrier (java.util.concurrent.CyclicBarrier. 2)]
      (try
        (let [results (with-redefs-fn {read-state (fn [context]
                                                  (let [s (original context)]
                                                    (.await barrier 10 java.util.concurrent.TimeUnit/SECONDS)
                                                    s))}
                        #(let [left (future (send ctx left-id)) right (future (send ctx right-id))]
                           [(deref left 15000 ::timeout) (deref right 15000 ::timeout)]))
              accepted (filter map? (remove h/anomaly? results))
              expected-count (if same-key? 1 2)]
          (is (not-any? #{::timeout} results))
          (is (= expected-count (count accepted)))
          (is (= (if same-key? 1 0)
                 (count (filter #(= :cognitect.anomalies/conflict (:cognitect.anomalies/category %)) results))))
          (is (= expected-count (count (o/list-ontologies ctx))))
          (is (every? #(not (contains? (set [left-id right-id]) (:ontology-id %))) accepted))
          (let [before (h/graph-events ctx)]
            (is (some #{(send ctx left-id)} accepted))
            (is (= before (h/graph-events ctx))))
          (let [foreign (assoc ctx :tenant-id (random-uuid))
                result (h/ok! (send foreign left-id))]
            (is (not (some #{(:ontology-id result)} (map :ontology-id accepted)))))
          (when file
            (es/stop (:event-store ctx))
            (let [reopened (open-store file)]
              (try
                (is (some #{(send (assoc ctx :event-store reopened) left-id)} accepted))
                (finally (es/stop reopened))))))
        (finally (es/stop (:event-store ctx)) (when file (cleanup file)))))))
