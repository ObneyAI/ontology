(ns ai.obney.ontology.memory-seed-replay
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.memory-pilot :as pilot]
            [ai.obney.ontology.memory-seeds :as seeds]
            [ai.obney.ontology.memory-aggregation :as aggregation]
            [ai.obney.ontology.memory-time :as temporal]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- read-json [f] (json/parse-string (slurp f) true))

(defn- replay [root id policy]
  (let [folder (io/file root id)
        input (read-json (io/file folder "input.json"))
        turns (filterv #(not (str/blank? (:text %))) (:turns input))
        original (read-json (io/file folder "ontology.json"))
        _ (when (:seed-policy original) (throw (ex-info "Expected original baseline" {:id id})))
        seed-ids (seeds/seeds turns (:ontology-flat original))
        sessions (set (map :session (filter (comp (set seed-ids) :id) turns)))
        store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}]
    (try
      (let [graph (:ontology-id (ontology/create-ontology! ctx {:name "Session traversal replay"}))]
        ;; BM25 uses statistics from the whole graph. Traversal alone needs only seeded sessions.
        (doseq [session (partition-by :session turns)
                :when (or (not= policy :session-only) (sessions (:session (first session))))]
          (pilot/import-session! ctx graph session))
        (let [base (:candidates original)
              session-order (seeds/expand ctx graph turns seed-ids base)
              order (pilot/refine ctx graph (assoc input :turns turns) session-order policy)
              candidates (vec (take (count base) order))]
          (when-not (= (count base) (count candidates) (count (set candidates)))
            (throw (ex-info "Invalid candidate count" {:id id})))
          {:id id :policy (name policy) :session-seeds seed-ids :candidates candidates}))
      (finally (es/stop store)))))

(defn- verify-retained [root output policy]
  (with-open [reader (io/reader output)]
    (reduce
     (fn [verified line]
       (let [{:keys [id candidates session-seeds]} (json/parse-string line true)
             folder (io/file root id)
             input (read-json (io/file folder "input.json"))]
         (if (seq (:vector input))
           (let [turns (filterv #(not (str/blank? (:text %))) (:turns input))
                 case-input (assoc input :turns turns)
                 result (if (= policy :session-aggregate)
                          (pilot/rankings case-input 80)
                          (pilot/rankings case-input 80 policy))
                 baseline (read-json (io/file folder "ontology.json"))]
             (when-not (and (= candidates (:candidates result))
                            (= session-seeds (:session-seeds result))
                            (= (:ontology-flat baseline) (:ontology-flat result)))
               (throw (ex-info "Fresh search differs from replay" {:id id})))
             (inc verified))
           verified)))
     0 (line-seq reader))))

(defn- replay-from-checkpoint [root output baseline policy]
  (when (= (.getCanonicalPath (io/file baseline)) (.getCanonicalPath (io/file output)))
    (throw (ex-info "Choose a separate output for the derived ranking" {})))
  (let [ids (:ids (read-json (io/file root "manifest.json")))
        expected (case policy :session-date "session-bm25" :session-aggregate "session-date")]
    (with-open [reader (io/reader baseline) writer (io/writer output)]
      (doseq [id ids]
        (let [line (.readLine reader)
              row (when line (json/parse-string line true))
              _ (when-not (and (= id (:id row)) (= expected (:policy row)))
                  (throw (ex-info "Missing, incompatible or out-of-order baseline" {:id id})))
              input (read-json (io/file root id "input.json"))
              result (case policy
                       :session-date
                       (let [anchor (temporal/target (:question input) (:date input))]
                         (assoc (if anchor (replay root id policy) row)
                                :date-routed (boolean anchor)))
                       :session-aggregate
                       (let [before (:candidates row)
                             order (aggregation/supplement (:turns input) (:question input) before)]
                         (assoc row :aggregate-routed (aggregation/requested? (:question input))
                                :candidates (vec (take (count before) order)))))]
          (.write writer (str (json/generate-string (assoc result :policy (name policy))) "\n"))))
      (when (.readLine reader) (throw (ex-info "Extra baseline rows" {}))))))

(defn -main [root output & [mode baseline]]
  (try
    (let [policy (case mode
                   (nil "verify-retained") :session-only
                   ("bm25" "bm25-verify-retained") :session-bm25
                   ("date" "date-verify-retained") :session-date
                   ("aggregate" "aggregate-verify-retained") :session-aggregate)]
      (cond
        (and (#{"date" "aggregate"} mode) baseline) (replay-from-checkpoint root output baseline policy)
        (#{"verify-retained" "bm25-verify-retained" "date-verify-retained" "aggregate-verify-retained"} mode)
        (println "Fresh graph, flat search, seed and candidate order verified:"
                 (verify-retained root output policy))
        :else
        (let [ids (:ids (read-json (io/file root "manifest.json")))]
          (with-open [writer (io/writer output)]
            (doseq [id ids]
              (.write writer (str (json/generate-string (replay root id policy)) "\n")))))))
    (finally (shutdown-agents))))
