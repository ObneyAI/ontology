(ns ai.obney.ontology.core.state
  "Tenant-scoped graph projection. Application vocabularies are data."
  (:require [ai.obney.ontology.core.trace :as trace] [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]
            [ai.obney.ontology.core.projection :as projection]
            [ai.obney.ontology.core.streams :as streams]
            [ai.obney.ontology.core.delta :as delta]
            [ai.obney.ontology.core.indexes :as indexes]
            [clojure.set :as set]))

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
  (let [e (canonical-edge e) key (edge-key e) old (get-in state [:edges key])]
    (delta/touch (indexes/edge-change (assoc-in state [:edges key] e) key old e) :edges key)))

(defn remove-edge [state key]
  (let [old (get-in state [:edges key])]
    (delta/touch (indexes/edge-change (update state :edges dissoc key) key old nil) :edges key)))

(defn parent-keys [state oid uri]
  (if (:indexes state)
    (get-in state [:indexes :parents [oid uri]] #{})
    (into #{} (keep (fn [[key e]] (when (and (= oid (:source-ontology-id e))
                                              (= uri (:source-uri e)) (= "skos:broader" (:predicate e))) key)))
          (:edges state))))
(defn add-parents [state oid uri parents timestamp previous]
  (reduce (fn [s parent]
            (let [e (edge oid uri "skos:broader" parent)]
              (put-edge s (or (get previous (edge-key e)) (assoc e :created-at timestamp))))) state parents))
(defn replace-parents [state oid uri parents timestamp]
  (let [previous (:edges state)
        state (reduce remove-edge state (parent-keys state oid uri))]
    (add-parents state oid uri parents timestamp previous)))

(defn put-concept [state oid c]
  (let [old (get-in state [:concepts [oid (:uri c)]])
        c (-> (merge old c)
              (assoc :ontology-id oid)
              (update :concept-id #(or (:concept-id old) %
                              (java.util.UUID/nameUUIDFromBytes
                               (.getBytes (str oid "|" (:uri c)) "UTF-8")))))]
    (let [record (dissoc c :broader :narrower :related)
          state (delta/touch
                 (indexes/concept-change (assoc-in state [:concepts [oid (:uri c)]] record) [oid (:uri c)] old record)
                 :concepts [oid (:uri c)])]
      (if (contains? c :broader)
        (if (and (:indexes state) (nil? old))
          (add-parents state oid (:uri c) (:broader c) (or (:updated-at c) (:created-at c)) {})
          (replace-parents state oid (:uri c) (:broader c) (or (:updated-at c) (:created-at c)))) state))))

(defn apply-event [state event]
  (trace/span :ontology.trace/apply-event
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
            (indexes/reindex-policy
              (assoc-in state [:ontologies oid :predicate-policies (:predicate e)] (:policy e))
              oid (:predicate e) (get-in state [:ontologies oid :predicate-policies (:predicate e)]))

            :ontology/concept-created
            (put-concept state oid
                         (-> (select-keys e [:uri :label :description :scope :broader :indicators
                                             :provenance :attributes :embedding :embedding-model :created-at :entity-type :alt-labels])
                             (assoc :concept-id (:concept-id e))))

            :ontology/concept-updated
            (if-let [uri (or (:uri e)
                            (some (fn [[[_ uri] c]] (when (and (= oid (:ontology-id c)) (= (:concept-id e) (:concept-id c))) uri))
                                  (:concepts state)))]
              (put-concept state oid (assoc (:changes e) :uri uri :updated-at (:updated-at e)))
              state)

            :ontology/relationship-created
            (put-edge state (select-keys e [:source-ontology-id :target-ontology-id :source-uri
                                           :target-uri :predicate :metadata :relationship-id :created-at]))

            :ontology/contribution-imported
            (let [state (delta/apply-changes state oid (:changes e))]
              (-> state
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
          (assoc-in [:requests [(streams/request-ontology-id e) (:request-id e)]]
                    {:request-hash (:request-hash e) :result (:result e)}))
        result))))

(def graph-keys [:indexes :ontologies :concepts :edges :revisions :requests :event-count :tbox :abox :rdf-triples])
(def policy-events #{:ontology/ontology-created :ontology/predicate-registered})
(def contribution-events #{:ontology/contribution-imported})
(defn event-data [e] (dissoc (merge e (:body e)) :body))

(defreadmodel :ontology.graph state
  {:events events :version 5}
  [s e] (select-keys (apply-event (indexes/ensure-indexed (or s {})) e) graph-keys))

(defn- metadata-event [s event]
  (let [e (event-data event) oid (:ontology-id e)
        affected (set (remove nil? [oid (:source-ontology-id e) (:target-ontology-id e)]))
        s (case (:event/type e)
            :ontology/ontology-created (assoc-in s [:ontologies oid]
                                         (assoc (select-keys e [:ontology-id :name :scope :description :base-uri :created-at])
                                                :predicate-policies (merge default-predicates (:predicate-policies e))))
            :ontology/predicate-registered (assoc-in s [:ontologies oid :predicate-policies (:predicate e)] (:policy e))
            s)]
    (reduce (fn [s id]
              (let [revision (inc (get-in s [:revisions id] 0))]
                (-> s (assoc-in [:revisions id] revision)
                    (assoc-in [:revision-events id revision] (:event/id e))))) s affected)))
(defreadmodel :ontology.graph metadata {:events events :version 1}
  [s e] (metadata-event s e))
(defreadmodel :ontology.graph policies {:events policy-events :version 1}
  [s e] (select-keys (metadata-event s e) [:ontologies]))
(defreadmodel :ontology.graph history {:events contribution-events :version 2}
  [s e]
  (select-keys (apply-event s (assoc (event-data e) :changes {}))
               [:sources :contributions :mappings]))
(defreadmodel :ontology.graph requests {:events events :version 1}
  [s e]
  (let [e (event-data e)]
    (assoc s [(streams/request-ontology-id e) (:request-id e)] (select-keys e [:request-hash :result]))))

(defn- incident? [[oid uri] e]
  (or (= [oid uri] [(:source-ontology-id e) (:source-uri e)])
      (= [oid uri] [(:target-ontology-id e) (:target-uri e)])))
(defn- view-event [s event key]
  (let [e (event-data event)
        e (if (= :ontology/contribution-imported (:event/type e))
            (assoc e :changes
                   {:concepts (if key
                                {:upserts (select-keys (get-in e [:changes :concepts :upserts]) [key])
                                 :removals (filterv #{key} (get-in e [:changes :concepts :removals]))} {})
                    :relationships
                    (if key
                      {:upserts (into {} (filter (fn [[_ edge]] (incident? key edge)))
                                      (get-in e [:changes :relationships :upserts]))
                       :removals (filterv (fn [[so su _ to tu]] (or (= key [so su]) (= key [to tu])))
                                          (get-in e [:changes :relationships :removals]))}
                      (get-in e [:changes :relationships]))}) e)
        s (apply-event s e)]
    (if key
      {:concepts (select-keys (:concepts s) [key])
       :edges (into {} (filter (fn [[_ edge]] (incident? key edge))) (:edges s))}
      (select-keys s [:edges]))))
(defreadmodel :ontology.graph concept {:events events :version 2}
  [s e] (view-event s e (:projection/concept-key e)))
(defreadmodel :ontology.graph concept-by-id {:events events :version 1}
  [s event]
  (let [{:keys [ontology-id concept-id]} (:projection/options event)
        e (event-data event)
        key (or (:concept-key s)
                (when (and (= :ontology/concept-created (:event/type e))
                           (= ontology-id (:ontology-id e)) (= concept-id (:concept-id e)))
                  [ontology-id (:uri e)])
                (when (= :ontology/contribution-imported (:event/type e))
                  (some (fn [[[oid _ :as key] c]]
                          (when (and (= oid ontology-id) (= concept-id (:concept-id c))) key))
                        (get-in e [:changes :concepts :upserts]))))]
    (if key (assoc (view-event s event key) :concept-key key) s)))
(defreadmodel :ontology.graph relationships {:events events :version 2}
  [s e] (view-event s e nil))

(defn revision [state oid] (get-in state [:revisions oid] 0))
(defn- queries [types ids tag as-of]
  (mapv #(cond-> {:types types}
           % (assoc :tags #{[tag %]})
           as-of (assoc :as-of as-of))
        (if (nil? ids) [nil] (sort ids))))
(defn metadata-state [ctx oid]
  (projection/project ctx :ontology.graph/metadata (queries events (when oid #{oid}) :ontology nil)))
(defn history-state [ctx oid]
  (projection/project ctx :ontology.graph/history (queries contribution-events #{oid} :ontology nil)))
(defn read-request [ctx oid request-id]
  (trace/span :ontology.trace/retry-read
    (get (projection/project ctx :ontology.graph/requests
                             ;; The request tag already includes ontology identity;
                             ;; Grain adds tenant scope to the query.
                             [{:types events :tags #{(streams/request-tag oid request-id)}}]) [oid request-id])))
(defn- with-policies [ctx s ids as-of]
  (let [origins (set/union (set ids) (set (map :source-ontology-id (vals (:edges s)))))
        policies (projection/project ctx :ontology.graph/policies (queries policy-events origins :ontology as-of))]
    (let [joined (update s :ontologies #(merge-with merge (:ontologies policies) %))]
      (reduce (fn [result oid]
                (reduce (fn [result predicate]
                          (indexes/reindex-policy result oid predicate
                            (get-in s [:ontologies oid :predicate-policies predicate])))
                        result (keys (get-in joined [:ontologies oid :predicate-policies]))))
              joined origins))))
(defn point-state [ctx oid uri]
  (let [s (projection/project ctx :ontology.graph/concept
                              [{:types events :tags #{(streams/concept-tag oid uri)}}]
                              {:concept-key [oid uri]})]
    (if (get-in s [:concepts [oid uri]]) (with-policies ctx s #{oid} nil) s)))
(defn point-id-state [ctx oid concept-id]
  (let [s (projection/project ctx :ontology.graph/concept-by-id
                              [{:types events :tags #{[:ontology oid] [:concept-id concept-id]}}]
                              {:ontology-id oid :concept-id concept-id})]
    (if (get-in s [:concepts (:concept-key s)]) (with-policies ctx s #{oid} nil) s)))
(defn relationship-state [ctx oid]
  (with-policies ctx
    (projection/project ctx :ontology.graph/relationships
                        [{:types events :tags #{[:ontology-relationships oid]}}]) #{oid} nil))
(defn- graph-state [ctx ids as-of]
  (projection/project ctx :ontology.graph/state (queries events ids :ontology as-of)))
(defn- connected-state [ctx initial]
  (loop [ids initial]
    (let [s (graph-state ctx ids nil)
          dependencies (set (mapcat #(keys (get-in s [:indexes :connections %])) ids))
          expanded (set/union ids dependencies)]
      (if (= ids expanded) (assoc s :read-ontology-ids ids)
          (recur expanded)))))
(defn read-events [ctx]
  (projection/authorize! ctx)
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types events})))
(defn read-state [ctx]
  (trace/span :ontology.trace/load-state
    (projection/authorize! ctx)
    (if-let [command (:command ctx)]
      (let [head (:event/id (first (into [] (es/read (:event-store ctx)
                                                    {:tenant-id (:tenant-id ctx) :types events :reverse? true :limit 1}))))
            ids (set (remove nil? [(or (:ontology-id command) (:source-ontology-id command))
                                  (:target-ontology-id command)]))]
        (assoc (connected-state ctx ids) :read-head head))
      (let [ids (:ontology/ids ctx)]
        (with-policies ctx (graph-state ctx ids nil) ids nil)))))
(defn state-at [ctx oid requested-revision]
  (if (nil? requested-revision)
    (read-state (assoc ctx :ontology/ids #{oid}))
    (let [metadata (metadata-state ctx oid)
          event-id (when (pos-int? requested-revision) (get-in metadata [:revision-events oid requested-revision]))]
      (when-not event-id (throw (ex-info "Graph revision not found"
                                      {:cognitect.anomalies/category :cognitect.anomalies/not-found
                                       :cognitect.anomalies/message "Graph revision not found"
                                       :revision requested-revision})))
      (with-policies ctx (graph-state ctx #{oid} event-id) #{oid} event-id))))

(defn- logical-edges [state]
  (->> (:edges state) vals
       (mapcat (fn [e]
                 (let [policy (get-in state [:ontologies (:source-ontology-id e) :predicate-policies (:predicate e)])]
                   (cond-> [e]
                     (or (:symmetric? policy) (:inverse policy))
                     (conj (assoc e :source-ontology-id (:target-ontology-id e)
                                   :target-ontology-id (:source-ontology-id e)
                                   :source-uri (:target-uri e) :target-uri (:source-uri e)
                                   :predicate (or (:inverse policy) (:predicate e))))))))
       (reduce (fn [m e] (assoc m (edge-key e) e)) {}) vals))
(defn relationships [state oid]
  (->> (logical-edges state)
       (filter #(or (= oid (:source-ontology-id %)) (= oid (:target-ontology-id %))))
       (sort-by edge-key) vec))
(defn- adjacency [state]
  (reduce (fn [index e]
            (let [source [(:source-ontology-id e) (:source-uri e)]
                  target [(:target-ontology-id e) (:target-uri e)] predicate (:predicate e)]
              (cond-> (update-in index [source :relations predicate] (fnil conj #{}) target)
                (= predicate "skos:broader") (update-in [source :broader] (fnil conj #{}) (:target-uri e))
                (= predicate "skos:broader") (update-in [target :narrower] (fnil conj #{}) (:source-uri e))
                (= predicate "skos:related") (update-in [source :related] (fnil conj #{}) (:target-uri e)))))
          {} (logical-edges state)))
(defn- concept-record-view [c relations]
  (merge c
         {:broader #{} :narrower #{} :related #{} :relations {}}
         relations))
(defn concept-view [state key]
  (when-let [c (get-in state [:concepts key])]
    (concept-record-view c (get (adjacency state) key))))
(defn concepts
  ([state] (concepts state {}))
  ([state {:keys [ids scope broader-uri]}]
   (let [index (adjacency state)]
     (->> (:concepts state)
          (filter (fn [[[oid _] c]] (and (or (nil? ids) (contains? ids oid)) (or (nil? scope) (= scope (:scope c))))))
          (sort-by key)
          (map (fn [[key c]] (concept-record-view c (get index key))))
          (filter #(or (nil? broader-uri) (contains? (:broader %) broader-uri))) vec))))
