(ns ai.obney.ontology.core.indexes
  "Incremental indexes retained with Grain's graph projection."
  (:require [ai.obney.ontology.core.trace :as trace] [ai.obney.ontology.core.entity-resolver :as resolver]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn- dissoc-path [m [k & ks]]
  (if ks
    (let [child (dissoc-path (get m k) ks)]
      (if (seq child) (assoc m k child) (dissoc m k)))
    (dissoc m k)))
(defn- membership [m path value add?]
  (let [members ((if add? conj disj) (get-in m path #{}) value)]
    (if (seq members) (assoc-in m path members) (dissoc-path m path))))
(defn- reference [m path amount]
  (let [n (+ (get-in m path 0) amount)]
    (if (pos? n) (assoc-in m path n) (dissoc-path m path))))
(defn entity-type [c] (or (:entity-type c) (:scope c) "Concept"))
(defn labels [c]
  (into #{} (comp (keep resolver/normalize-label) (remove str/blank?))
        (cons (:label c) (:alt-labels c))))
(defn embedding-key [oid c]
  (when (and (some? (:embedding-model c)) (sequential? (:embedding c)) (seq (:embedding c)))
    [oid (entity-type c) (:embedding-model c) (count (:embedding c))]))
(defn- concept-entry [idx [oid uri :as key] c add?]
  (let [idx (-> idx
                (membership [:concept-uris oid] uri add?)
                (membership [:concept-ids [oid (:concept-id c)]] key add?))
        idx (reduce #(membership %1 [:labels [oid (entity-type c) %2]] key add?) idx (labels c))]
    (if-let [bucket (embedding-key oid c)]
      (membership idx [:embeddings bucket] key add?) idx)))
(defn concept-change [s key before after]
  (if (and (contains? s :indexes) (not= before after))
    (update s :indexes
            (fn [idx] (cond-> idx before (concept-entry key before false) after (concept-entry key after true)))) s))
(defn- edge-entry [idx policies [so su predicate to tu :as key] add?]
  (let [amount (if add? 1 -1)
        idx (-> idx
                (membership [:concept-edges [so su]] key add?)
                (membership [:concept-edges [to tu]] key add?)
                (membership [:outgoing so] key add?)
                (membership [:incident so] key add?)
                (membership [:predicate-edges [so predicate]] key add?))
        idx (if (= so to) idx (-> idx
                                         (membership [:incident to] key add?)
                                         (reference [:connections so to] amount)
                                         (reference [:connections to so] amount)))]
    (cond-> idx
      (= predicate "skos:broader") (membership [:parents [so su]] key add?)
      (get-in policies [so :predicate-policies predicate :hierarchical?])
      (reference [:hierarchy [so su] [to tu]] amount))))
(defn edge-change [s key before after]
  ;; Metadata-only changes do not alter any edge index.
  (if (and (contains? s :indexes) (not= (some? before) (some? after)))
    (update s :indexes edge-entry (:ontologies s) key (some? after)) s))
(defn reindex-policy [s oid predicate previous-policy]
  (if (and (:indexes s) (not= (boolean (:hierarchical? previous-policy))
                             (boolean (get-in s [:ontologies oid :predicate-policies predicate :hierarchical?]))))
    (reduce (fn [s [so su _ to tu]]
              (update s :indexes reference [:hierarchy [so su] [to tu]]
                      (if (get-in s [:ontologies oid :predicate-policies predicate :hierarchical?]) 1 -1)))
            s (get-in s [:indexes :predicate-edges [oid predicate]])) s))
(defn rebuild [s]
  (let [s (assoc s :indexes {})
        s (reduce-kv (fn [s key c] (concept-change s key nil c)) s (:concepts s {}))]
    (reduce-kv (fn [s key e] (edge-change s key nil e)) s (:edges s {}))))
(defn ensure-indexed [s] (if (contains? s :indexes) s (rebuild s)))
(defn apply-delta [before after changes]
  (trace/span :ontology.trace/update-indexes
    (if (contains? before :indexes)
      (let [update-records (fn [s field change-fn change]
                             (reduce (fn [s key] (change-fn s key (get-in before [field key]) (get-in after [field key])))
                                     s (set/union (set (keys (:upserts change))) (set (:removals change))))) ]
        (-> after
            (update-records :concepts concept-change (:concepts changes))
            (update-records :edges edge-change (:relationships changes)))) after)))
(defn resolution-candidates [s oid incoming]
  (trace/span :ontology.trace/resolution-candidates
    (let [keys (reduce (fn [found c]
                         (let [found (reduce #(into %1 (get-in s [:indexes :labels [oid (entity-type c) %2]])) found (labels c))]
                           (if-let [bucket (embedding-key oid c)]
                             (into found (get-in s [:indexes :embeddings bucket])) found))) #{} incoming)]
      (mapv (fn [key] (let [c (get-in s [:concepts key])] (assoc c :entity-type (entity-type c)))) (sort keys)))))
