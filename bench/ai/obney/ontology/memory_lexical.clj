(ns ai.obney.ontology.memory-lexical
  (:require [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.memory-seeds :as seeds]
            [ai.obney.ontology.memory-time :as temporal]))

(defn supplement
  "Add strong lexical matches from the user's own statements after session expansion."
  ([ctx graph turns question candidates]
   (supplement ctx graph turns question candidates nil))
  ([ctx graph turns question candidates anchor]
   (let [hits (ontology/search ctx graph {:query-text question :lexical-method :bm25
                                         :limit (max 1 (count turns))})
        _ (when (:cognitect.anomalies/category hits) (throw (ex-info "Lexical search failed" hits)))
        ranked (mapv #(get-in % [:attributes :id]) hits)
        rank (zipmap ranked (range))
        role (into {} (map (juxt :id :role)) turns)
        base (vec (take seeds/window candidates))
        existing (set base)
        additions (->> ranked (filter #(= "user" (role %))) (take 16) (remove existing) (take 8))
        ;; Start at the bottom so tied, unmatched replies drop in reverse baseline order.
        replies (->> (drop (quot (count base) 2) base) reverse
                     (filter #(= "assistant" (role %)))
                     (sort-by #(get rank % (count ranked)) >))
        pairs (filter (fn [[add drop]] (< (rank add) (get rank drop (count ranked))))
                      (map vector additions replies))
        displaced (set (map second pairs))]
     (temporal/supplement anchor turns rank
       (vec (distinct (concat (remove displaced base) (map first pairs) candidates)))))))
