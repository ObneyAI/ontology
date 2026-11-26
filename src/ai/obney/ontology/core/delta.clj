(ns ai.obney.ontology.core.delta
  "Resolved record changes. Upserts replace records; relationships are independent."
  (:require [ai.obney.ontology.core.trace :as trace] [clojure.set :as set]
            [ai.obney.ontology.core.indexes :as indexes]))

(defn records [before after]
  {:upserts (into {} (filter (fn [[k v]] (or (not (contains? before k)) (not= v (get before k))))) after)
   :removals (vec (sort-by pr-str (set/difference (set (keys before)) (set (keys after)))))})
(defn apply-records [before {:keys [upserts removals]}]
  (into (apply dissoc (or before {}) removals) upserts))
(defn changed? [{:keys [upserts removals additions]}]
  (boolean (or (seq upserts) (seq removals) (seq additions))))
(defn index [xs] (into {} (map (juxt :uri identity)) xs))
(defn ordered [m] (mapv val (sort-by key m)))
(defn track [s]
  (assoc s ::touched {:concepts #{} :edges #{}}))
(defn touch [s field key]
  (if (contains? s ::touched) (update-in s [::touched field] conj key) s))
(defn- graph-records [before after field]
  (if (contains? after ::touched)
    (let [keys (get-in after [::touched field])]
      (records (select-keys (get before field) keys) (select-keys (get after field) keys)))
    (records (get before field) (get after field))))
(defn changes [before after oid]
  (trace/span :ontology.trace/diff
    (let [old-schema (get-in before [:tbox oid] {}) new-schema (get-in after [:tbox oid] {})
          old-triples (set (get-in before [:rdf-triples oid])) new-triples (set (get-in after [:rdf-triples oid]))]
      {:concepts (graph-records before after :concepts)
       :relationships (graph-records before after :edges)
       :tbox (into {} (keep (fn [[section xs]]
                             (when (not= (get old-schema section) xs)
                               [section (records (index (get old-schema section)) (index xs))]))) new-schema)
       :tbox-removals (vec (sort (set/difference (set (keys old-schema)) (set (keys new-schema)))))
       :abox (records (index (get-in before [:abox oid])) (index (get-in after [:abox oid])))
       :rdf-triples {:additions (vec (sort-by pr-str (set/difference new-triples old-triples)))
                    :removals (vec (sort-by pr-str (set/difference old-triples new-triples)))}})))
(defn apply-changes [s oid changes]
  (trace/span :ontology.trace/apply-delta
    (let [schema (apply dissoc (get-in s [:tbox oid] {}) (:tbox-removals changes))]
      (indexes/apply-delta s (-> s
          (update :concepts apply-records (:concepts changes))
          (update :edges apply-records (:relationships changes))
          (assoc-in [:tbox oid]
                    (reduce-kv (fn [schema section change]
                                 (assoc schema section (ordered (apply-records (index (get schema section)) change))))
                               schema (:tbox changes {})))
          (update-in [:abox oid] #(if (changed? (:abox changes))
                                    (ordered (apply-records (index %) (:abox changes))) (or % [])))
          (update-in [:rdf-triples oid]
                     #(if (changed? (:rdf-triples changes))
                        (vec (sort-by pr-str (set/union (set/difference (set %) (set (get-in changes [:rdf-triples :removals])))
                                                    (set (get-in changes [:rdf-triples :additions])))))
                        (or % [])))) changes))))
