(ns ai.obney.ontology.core.state
  "Tenant-scoped graph projection. Application vocabularies are data."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]))

(def events
  #{:ontology/ontology-created :ontology/concept-created
    :ontology/concept-updated :ontology/relationship-created
    :ontology/predicate-registered :ontology/contribution-imported})

(def default-predicates
  {"skos:broader" {:hierarchical? true :inverse "skos:narrower"}
   "skos:narrower" {:inverse "skos:broader"}
   "skos:related" {:symmetric? true}
   "owl:sameAs" {:symmetric? true}
   "rdfs:subClassOf" {:hierarchical? true}})

(defn normalize-id [id]
  (if (string? id)
    (try (java.util.UUID/fromString id)
         (catch IllegalArgumentException _
           (java.util.UUID/nameUUIDFromBytes (.getBytes id "UTF-8"))))
    id))

(defn edge-key [edge]
  ((juxt :source-ontology-id :source-uri :predicate :target-ontology-id :target-uri) edge))

(defn edge [oid subject predicate object]
  {:source-ontology-id oid :target-ontology-id oid
   :source-uri subject :target-uri object :predicate predicate})

(defn canonical-edge [e]
  (if (= "skos:narrower" (:predicate e))
    (-> e
        (assoc :source-ontology-id (:target-ontology-id e)
               :target-ontology-id (:source-ontology-id e)
               :source-uri (:target-uri e) :target-uri (:source-uri e)
               :predicate "skos:broader"))
    e))

(defn put-edge [state e]
  (let [e (canonical-edge e)]
    (assoc-in state [:edges (edge-key e)] e)))

(defn replace-parents [state oid uri parents timestamp]
  (let [previous (:edges state)
        state (update state :edges
                      #(into {} (remove (fn [[_ e]]
                                          (and (= oid (:source-ontology-id e))
                                               (= uri (:source-uri e))
                                               (= "skos:broader" (:predicate e)))) %)))]
    (reduce (fn [s parent]
              (let [e (edge oid uri "skos:broader" parent)]
                (put-edge s (or (get previous (edge-key e)) (assoc e :created-at timestamp))))) state parents)))

(defn put-concept [state oid c]
  (let [old (get-in state [:concepts [oid (:uri c)]])
        c (-> (merge old c)
              (assoc :ontology-id oid)
              (update :id #(or (:id old) % (:concept-id c)
                              (java.util.UUID/nameUUIDFromBytes
                               (.getBytes (str oid "|" (:uri c)) "UTF-8")))))]
    (cond-> (assoc-in state [:concepts [oid (:uri c)]] (dissoc c :broader :narrower :related))
      (contains? c :broader) (replace-parents oid (:uri c) (:broader c) (or (:updated-at c) (:created-at c))))))

(defn apply-event [state event]
  (let [e (merge event (:body event))
        oid (:ontology-id e)
        state (or state {})
        result
        (case (:event/type event)
          :ontology/ontology-created
          (assoc-in state [:ontologies oid]
                    (-> (select-keys e [:ontology-id :name :scope :description :base-uri :created-at])
                        (assoc :predicate-policies (merge default-predicates (:predicate-policies e)))))

          :ontology/predicate-registered
          (assoc-in state [:ontologies oid :predicate-policies (:predicate e)] (:policy e))

          :ontology/concept-created
          (put-concept state oid
                       (-> (select-keys e [:uri :label :description :scope :broader :indicators
                                           :provenance :attributes :embedding :embedding-model :created-at :entity-type :alt-labels])
                           (assoc :id (:concept-id e))))

          :ontology/concept-updated
          (if-let [[[_ uri] _] (some #(when (and (= oid (:ontology-id (val %)))
                                               (= (:concept-id e) (:id (val %)))) %) (:concepts state))]
            (put-concept state oid (assoc (:changes e) :uri uri :updated-at (:updated-at e)))
            state)

          :ontology/relationship-created
          (put-edge state (select-keys e [:source-ontology-id :target-ontology-id :source-uri
                                         :target-uri :predicate :metadata :relationship-id :created-at]))

          :ontology/contribution-imported
          (let [state (-> state
                          (update :concepts #(into {} (remove (fn [[[id _] _]] (= id oid)) %)))
                          (update :edges #(into {} (remove (fn [[_ e]] (= oid (:source-ontology-id e))) %))))
                state (reduce #(put-concept %1 oid %2) state (:concepts e))
                state (reduce put-edge state (:relationships e))]
            (-> state
                (assoc-in [:tbox oid] (:tbox e))
                (assoc-in [:abox oid] (:abox e))
                (assoc-in [:rdf-triples oid] (:rdf-triples e))
                (update-in [:mappings oid] merge (:canonical-map e))
                (update-in [:sources [oid (get-in e [:source :source-id])
                                     (get-in e [:source :content-hash])]]
                           (fn [prior] (assoc (:source e) :registered-at
                                             (or (:registered-at prior) (:recorded-at e)))))
                (assoc-in [:contributions [oid (:contribution-id e)]]
                          (select-keys e [:contribution-id :ontology-id :source :input :canonical-map
                                          :resolution :result :request-hash :recorded-at]))))
          state)
        affected (set (remove nil? [oid (:source-ontology-id e) (:target-ontology-id e)]))]
    (if (contains? events (:event/type event))
      (cond-> (reduce #(update-in %1 [:revisions %2] (fnil inc 0))
                      (update result :event-count (fnil inc 0)) affected)
        (:request-id e)
        (assoc-in [:requests [(or oid (:source-ontology-id e)) (:request-id e)]]
                  {:request-hash (:request-hash e) :result (:result e)}))
      result)))

(defreadmodel :ontology.graph state
  {:events events :version 1}
  [state event] (apply-event state event))

(defn read-events [ctx]
  (when-not (:tenant-id ctx)
    (throw (ex-info "A tenant-id is required" {:cognitect.anomalies/category :cognitect.anomalies/forbidden})))
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types events})))

(defn read-state [ctx]
  ;; Read once for each transaction; the append predicate uses this exact prefix.
  (reduce apply-event {} (read-events ctx)))

(defn revision [state oid] (get-in state [:revisions oid] 0))

(defn relationships [state oid]
  (->> (:edges state) vals
       (filter #(or (= oid (:source-ontology-id %)) (= oid (:target-ontology-id %))))
       (mapcat (fn [e]
                 (let [policy (get-in state [:ontologies (:source-ontology-id e) :predicate-policies (:predicate e)])]
                   (cond-> [e]
                     (or (:symmetric? policy) (:inverse policy))
                     (conj (assoc e :source-ontology-id (:target-ontology-id e)
                                   :target-ontology-id (:source-ontology-id e)
                                   :source-uri (:target-uri e) :target-uri (:source-uri e)
                                   :predicate (or (:inverse policy) (:predicate e))))))))
       (reduce (fn [m e] (assoc m (edge-key e) e)) {}) vals
       (sort-by (comp pr-str edge-key)) vec))

(defn concepts [state]
  (mapv (fn [[[oid uri] c]]
          (reduce (fn [c e]
                    (let [out? (and (= oid (:source-ontology-id e)) (= uri (:source-uri e)))
                          in? (and (= oid (:target-ontology-id e)) (= uri (:target-uri e)))]
                      (cond-> c
                        (and out? (= "skos:broader" (:predicate e))) (update :broader conj (:target-uri e))
                        (and in? (= "skos:broader" (:predicate e))) (update :narrower conj (:source-uri e))
                        (and out? (= "skos:related" (:predicate e))) (update :related conj (:target-uri e))
                        out? (update-in [:relations (:predicate e)] (fnil conj #{})
                                        [(:target-ontology-id e) (:target-uri e)]))))
                  (assoc c :broader #{} :narrower #{} :related #{} :relations {})
                  (relationships state oid)))
        (sort-by (comp pr-str key) (:concepts state))))
