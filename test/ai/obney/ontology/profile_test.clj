(ns ai.obney.ontology.profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.ontology.profile :as profile]
            [ai.obney.ontology.benchmark :as bench]
            [ai.obney.ontology.core.trace :as trace]))

(defn event [id parent name duration bytes]
  (cond-> {:mulog/trace-id id :mulog/parent-trace parent :mulog/event-name name
           :mulog/duration duration :profile/sample 0}
    (some? bytes) (assoc profile/allocation-key bytes)))

(deftest nested-accounting
  (let [events [(event "root" nil profile/root-name 100 1000)
                (event "native" "root" :grain/append 60 nil)
                (event "child" "native" :ontology/encode 20 200)]
        rows (profile/account events)]
    (is (= [40 40 20] (mapv :self-ns rows)))
    (is (= [800 nil 200] (mapv :self-allocated-bytes rows)))
    (is (= 100 (reduce + (map :self-ns rows))))
    (is (= 1000 (reduce + (keep :self-allocated-bytes rows))))
    (is (thrown? clojure.lang.ExceptionInfo (profile/account (subvec events 0 0))))
    (is (thrown? clojure.lang.ExceptionInfo (profile/account [(first events) (last events)])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (profile/account [(first events) (assoc (second events) :mulog/duration 101)]))))
  (let [sanitized (profile/sanitize (assoc (event "a" nil profile/root-name 1 2)
                                         :command {:secret "payload"} :exception (Exception. "private")))]
    (is (not (contains? sanitized :command)))
    (is (not (contains? sanitized :exception)))))

(deftest allocation-is-accounted-per-thread
  (let [rows (profile/account [(assoc (event "root" nil profile/root-name 100 1000) :profile/thread-id 1)
                               (assoc (event "writer" "root" :ontology/writer 60 500) :profile/thread-id 2)
                               (assoc (event "child" "writer" :ontology/encode 20 200) :profile/thread-id 2)])]
    (is (= [1000 300 200] (mapv :self-allocated-bytes rows)))))

(deftest trace-preserves-behavior
  (let [value (Object.) error (Exception. "expected")]
    (is (identical? value (trace/span :ontology.test/value value)))
    (is (identical? error (try (trace/span :ontology.test/error (throw error))
                              (catch Exception e e))))))

(deftest grain-traces-join-ontology-tree
  (doseq [backend [:in-memory :sqlite]]
    (testing (name backend)
      (let [result (binding [*out* (java.io.StringWriter.)]
                     (profile/run! (assoc bench/defaults :sizes [20] :backends [backend]
                                          :samples 1 :warmup 1
                                          :output (str "target/profile/test-" (name backend)))))
            names (set (map :event (:summary result)))]
        (is (contains? names :ontology.trace/validate-hierarchy))
        (is (contains? names :ontology.trace/diff))
        (is (contains? names :ontology.trace/grain-projection))
        (is (contains? names :ontology.trace/grain-append))
        (is (contains? names :ai.obney.grain.command-processor-v2.core/process-command))
        (when (= backend :sqlite)
          (is (contains? names :ontology.trace/sqlite-writer))
          (is (contains? names :ai.obney.grain.event-store-sqlite-v3.core/append))
          (is (contains? names :ai.obney.grain.event-store-sqlite-v3.core/write-transaction)))
        (is (= 1 (count (:measurements result))))))))
