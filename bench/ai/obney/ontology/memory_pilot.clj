(ns ai.obney.ontology.memory-pilot
  (:require [cheshire.core :as json]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.memory-seeds :as seeds]
            [ai.obney.ontology.memory-lexical :as lexical]
            [ai.obney.ontology.memory-time :as temporal]
            [ai.obney.ontology.memory-aggregation :as aggregation])
  (:import (java.util UUID)
           (java.security MessageDigest)
           (java.util HexFormat)))

(defn- checked [x]
  (when (:cognitect.anomalies/category x) (throw (ex-info "Ontology operation failed" x)))
  x)

(defn- bytes-of [s] (.getBytes ^String s "UTF-8"))
(defn- stable-id [s] (UUID/nameUUIDFromBytes (bytes-of s)))
(defn- uri [turn] (str "urn:longmem:" (:id turn)))

(defn import-session! [ctx graph turns]
  (let [session (:session (first turns))
        hash (.formatHex (HexFormat/of)
                         (.digest (MessageDigest/getInstance "SHA-256")
                                  (bytes-of (json/generate-string (mapv #(dissoc % :vector) turns)))))]
    (checked
     (ontology/import-contribution!
      ctx graph
      {:contribution-id (stable-id (str session ":" hash ":minilm-v1"))
       :source {:source-id (stable-id session) :content-hash hash}
       :concepts (mapv (fn [t] {:uri (uri t) :label (:role t) :description (:text t)
                                :attributes (select-keys t [:session :date :id :role])
                                :embedding (:vector t) :embedding-model "all-minilm"}) turns)
       :relationships (mapv (fn [[a b]] {:source-uri (uri a) :target-uri (uri b)
                                          :predicate "skos:related"}) (partition 2 1 turns))}))))

(defn refine [ctx graph {:keys [turns question date]} candidates policy]
  (case policy
    :session-only candidates
    :session-bm25 (lexical/supplement ctx graph turns question candidates)
    (:session-date :session-aggregate)
    (let [dated (lexical/supplement ctx graph turns question candidates (temporal/target question date))]
      (if (= policy :session-aggregate)
        (aggregation/supplement turns question dated)
        dated))))

(defn rankings
  ([case limit] (rankings case limit :session-aggregate))
  ([{:keys [question turns vector] :as input} limit policy]
   (when-not (pos-int? limit)
     (throw (ex-info "Ranking limit must be positive" {:limit limit})))
   (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
         ctx {:event-store store :tenant-id (random-uuid)}]
     (try
       (let [graph (:ontology-id (checked (ontology/create-ontology! ctx {:name "Conversation history"})))
             start (System/nanoTime)
             _ (doseq [session (partition-by :session turns)] (import-session! ctx graph session))
             ready (System/nanoTime)
             query {:query-text question :embed-query (constantly vector) :embedding-model "all-minilm" :limit 30}
             flat (checked (ontology/search ctx graph query))
             seeded (System/nanoTime)
             expanded (checked (ontology/search ctx graph
                                                (assoc query :limit (max limit seeds/window) :seed-uris (mapv :uri (take 3 flat)) :max-depth 1)))
             ids (fn [hits] (mapv #(get-in % [:attributes :id]) hits))
             flat-ids (ids flat)
             seed-ids (seeds/seeds turns flat-ids)
             session-order (seeds/expand ctx graph turns seed-ids (ids expanded))
             order (refine ctx graph input session-order policy)
             candidates (vec (take limit order))]
         {:candidates candidates :ontology (vec (take 30 candidates)) :ontology-flat flat-ids
          :retrieval-policy (name policy) :seed-policy "distinct-sessions-v1" :session-seeds seed-ids
          :seconds {:ingest (/ (- ready start) 1e9)
                    :ontology-flat (/ (- seeded ready) 1e9)
                    :ontology (/ (- (System/nanoTime) ready) 1e9)}})
       (finally (es/stop store))))))

(defn -main [input output & [limit]]
  (spit output (json/generate-string (rankings (json/parse-string (slurp input) true)
                                               (if limit (parse-long limit) 30))))
  (shutdown-agents))
