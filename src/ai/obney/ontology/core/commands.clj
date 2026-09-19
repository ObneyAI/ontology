(ns ai.obney.ontology.core.commands
  (:require [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.schemas]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.algorithms :as algorithms]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.anomalies :as anom]))

(defn tenant-scoped? [ctx] (some? (:tenant-id ctx)))
(defn fail! [category message]
  (throw (ex-info message {::anom/category category ::anom/message message})))
(defn require! [pred message]
  (when-not pred (fail! ::anom/incorrect message)))
(defn nonblank? [s] (and (string? s) (not (str/blank? s))))
(defn digest [value]
  (let [canonical (walk/postwalk (fn [v]
                                   (cond (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                                         (set? v) (vec (sort-by pr-str v))
                                         :else v)) value)]
    (algorithms/sha256 (pr-str canonical))))

(defn require-ontology! [s oid]
  (when-not (get-in s [:ontologies oid]) (fail! ::anom/not-found "Ontology not found")))

(defn validate-graph! [s oid]
  (let [cs (:concepts s)
        edges (vals (:edges s))]
    (doseq [[[id _] c] cs :when (= id oid)]
      (require! (every? nonblank? ((juxt :uri :label :description) c))
                "Concept URI, label and description must be nonblank"))
    (doseq [e edges :when (or (= oid (:source-ontology-id e)) (= oid (:target-ontology-id e)))]
      (let [source [(:source-ontology-id e) (:source-uri e)]
            target [(:target-ontology-id e) (:target-uri e)]
            policy (get-in s [:ontologies (:source-ontology-id e) :predicate-policies (:predicate e)])]
        (require! (and (get cs source) (get cs target)) "Relationship endpoints must exist")
        (require! policy (str "Undeclared predicate: " (:predicate e)))
        (require! (or (= (first source) (first target)) (:cross-ontology? policy))
                  "Predicate does not permit cross-ontology relationships")
        (require! (not= source target) "Self relationships are not supported")))
    (let [hierarchy (filter #(get-in s [:ontologies (:source-ontology-id %) :predicate-policies
                                      (:predicate %) :hierarchical?]) edges)
          adjacency (group-by (juxt :source-ontology-id :source-uri) hierarchy)]
      (doseq [start (keys adjacency)]
        (loop [pending (mapv (juxt :target-ontology-id :target-uri) (get adjacency start)) seen #{}]
          (when-let [node (peek pending)]
            (require! (not= start node) "Hierarchy cycle")
            (recur (if (seen node) (pop pending)
                       (into (pop pending) (map (juxt :target-ontology-id :target-uri) (get adjacency node))))
                   (conj seen node)))))))
  s)

(defn command-result [ctx oid request-id make-change]
  (try
    (when-not (tenant-scoped? ctx) (fail! ::anom/forbidden "A tenant-id is required"))
    (let [s (state/read-state ctx)
          command (:command ctx)
          request-hash (digest (dissoc command :command/id :command/timestamp))
          prior (get-in s [:requests [oid request-id]])]
      (if prior
        (if (= request-hash (:request-hash prior))
          {:command-result/events [] :command-result/data (:result prior)}
          {::anom/category ::anom/conflict ::anom/message "Idempotency key reused with different payload"})
        (do
          (when (and (contains? command :expected-revision)
                     (not= (:expected-revision command) (state/revision s oid)))
            (fail! ::anom/conflict "Stale ontology revision"))
          (let [{:keys [type body result]} (make-change s)
                result (assoc result :ontology-id oid :revision (inc (state/revision s oid)))
                body (assoc body :ontology-id oid :request-id request-id :request-hash request-hash :result result)
                tags (set (map #(vector :ontology %) (remove nil? [oid (:target-ontology-id body)])))
                event (es/->event {:type type :tags tags :body body})
                candidate (state/apply-event s event)]
            (validate-graph! candidate oid)
            {:command-result/events [event]
             :command-result/data result
             ;; Serialize graph mutations within this tenant. This protects
             ;; endpoint/cycle/uniqueness checks as well as contribution revisions.
             :command-result/cas {:types state/events
                                  :predicate-fn #(= (:event-count s 0) (count (into [] %)))}}))))
    (catch clojure.lang.ExceptionInfo e
      (if (::anom/category (ex-data e)) (ex-data e)
          {::anom/category ::anom/fault ::anom/message (.getMessage e)}))))

(defcommand :ontology create-ontology {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [name] :as p} (:command ctx) id (:command/id p)]
    (command-result ctx id id
      (fn [_]
        (require! (nonblank? name) "Ontology name must be nonblank")
        {:type :ontology/ontology-created
         :body (assoc (select-keys p [:name :scope :description :base-uri :predicate-policies])
                      :ontology-id id :created-at (str (java.time.Instant/now)))
         :result {:ontology-id id}}))))

(defcommand :ontology create-concept {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [ontology-id uri] :as p} (:command ctx) id (:command/id p)]
    (command-result ctx ontology-id id
      (fn [s]
        (require-ontology! s ontology-id)
        (when (get-in s [:concepts [ontology-id uri]]) (fail! ::anom/conflict "Concept URI already exists"))
        {:type :ontology/concept-created
         :body (assoc (select-keys p [:ontology-id :uri :label :description :scope :broader
                                     :indicators :attributes :embedding :embedding-model :provenance :entity-type :alt-labels])
                      :concept-id id :created-at (str (java.time.Instant/now)))
         :result {:concept-id id :uri uri}}))))

(defcommand :ontology update-concept {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [ontology-id concept-id changes] :as p} (:command ctx)]
    (command-result ctx ontology-id (:command/id p)
      (fn [s]
        (require-ontology! s ontology-id)
        (let [c (some #(when (and (= ontology-id (:ontology-id %)) (= concept-id (:id %))) %)
                      (vals (:concepts s)))
              changes (select-keys changes [:label :description :broader :indicators :attributes :embedding :embedding-model])]
          (when-not c (fail! ::anom/not-found "Concept not found"))
          (require! (seq changes) "No mutable fields supplied")
          {:type :ontology/concept-updated
           :body {:ontology-id ontology-id :concept-id concept-id :changes changes
                  :updated-at (str (java.time.Instant/now))}
           :result {:concept-id concept-id :uri (:uri c)}})))))

(defcommand :ontology create-relationship {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [source-ontology-id target-ontology-id] :as p} (:command ctx)]
    (command-result ctx source-ontology-id (:command/id p)
      (fn [s]
        (require-ontology! s source-ontology-id)
        (require-ontology! s target-ontology-id)
        (let [e (select-keys p [:source-ontology-id :target-ontology-id :source-uri :target-uri :predicate :metadata])]
          (when (some #(= (state/edge-key e) (state/edge-key %))
                      (state/relationships s source-ontology-id))
            (fail! ::anom/conflict "Relationship already exists"))
          {:type :ontology/relationship-created
           :body (assoc e :relationship-id (:command/id p) :created-at (str (java.time.Instant/now)))
           :result (assoc e :relationship-id (:command/id p))})))))

(defcommand :ontology register-predicate {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [ontology-id predicate policy] :as p} (:command ctx)]
    (command-result ctx ontology-id (:command/id p)
      (fn [s]
        (require-ontology! s ontology-id)
        (require! (nonblank? predicate) "Predicate must be nonblank")
        (when (get-in s [:ontologies ontology-id :predicate-policies predicate])
          (fail! ::anom/conflict "Predicate policy already exists"))
        {:type :ontology/predicate-registered
         :body {:ontology-id ontology-id :predicate predicate :policy policy}
         :result {:predicate predicate :policy policy}}))))

(defn merge-records [old new]
  (->> (concat old new)
       (reduce (fn [m x] (assoc m (or (:uri x) (:id x) (pr-str x)) x)) {})
       (sort-by (comp str key)) (mapv val)))

(defn- rewrite-fields [record fields rewrite]
  (reduce (fn [r k] (if (contains? r k) (update r k rewrite) r)) record fields))

(defn- validate-schema! [concepts tbox abox]
  (require! (every? #{:classes :object-properties :datatype-properties} (keys tbox))
            "Unsupported TBox section")
  (doseq [[_ records] tbox]
    (require! (and (vector? records) (every? #(and (map? %) (nonblank? (:uri %))) records))
              "TBox records require a URI"))
  (let [known (set (concat (map :uri concepts) (map :uri (:classes tbox))))
        known? #(or (known %) (and (string? %) (re-find #"^(?:xsd:|rdf:|rdfs:|owl:|http://www.w3.org/)" %)))]
    (doseq [property (concat (:object-properties tbox) (:datatype-properties tbox))
            reference (keep property [:domain :range :datatype])]
      (require! (known? reference) (str "Unknown schema reference: " reference)))
    (doseq [individual abox]
      (require! (nonblank? (:uri individual)) "Individual URI must be nonblank")
      (when-let [type (:type individual)]
        (require! (known? type) (str "Unknown individual type: " type)))
      (when (contains? individual :properties)
        (require! (map? (:properties individual)) "Individual properties must be a map")))))

(defn prepare-contribution [s {:keys [ontology-id contribution-id source concepts relationships
                                    tbox abox resolution-policy rdf-triples] :as p}]
  (require-ontology! s ontology-id)
  (require! (nonblank? (:content-hash source)) "Source content hash must be nonblank")
  (when (contains? resolution-policy :similarity-threshold)
    (let [threshold (:similarity-threshold resolution-policy)]
      (require! (and (number? threshold) (Double/isFinite (double threshold)) (<= 0 threshold 1))
                "Resolution threshold must be finite and between zero and one")))
  (rdf/validate-triples! (or rdf-triples []))
  (doseq [individual abox :when (contains? individual :properties)]
    (require! (map? (:properties individual)) "Individual properties must be a map"))
  (let [recorded-at (str (java.time.Instant/now))
        old (filterv #(= ontology-id (:ontology-id %)) (vals (:concepts s)))
        incoming (mapv #(-> % (assoc :source-id (:source-id source))
                             (update :entity-type (fn [t] (or t (:scope %) "Concept")))) concepts)
        old-for-resolution (mapv #(assoc % :entity-type (or (:entity-type %) (:scope %) "Concept")) old)
        resolution (if (:match-labels? resolution-policy)
                     (algorithms/resolve-incremental incoming old-for-resolution (set (map :uri old))
                                                     {:similarity-threshold (or (:similarity-threshold resolution-policy) 0.85)})
                     {:canonical-map {} :exact-matches 0 :semantic-matches 0})
        canonical (:canonical-map resolution)
        existing-uris (set (map :uri old))
        _ (when (some (fn [[uri target]] (and (existing-uris uri) (not= uri target))) canonical)
            (fail! ::anom/conflict "Resolution would collapse distinct existing identities"))
        rewrite #(get canonical % %)
        incoming (mapv #(cond-> (assoc % :uri (rewrite (:uri %))
                                        :provenance {:kind :source-extracted
                                                     :source-reference (str (:source-id source))
                                                     :producer (:producer source)})
                          (contains? % :broader) (update :broader (fn [xs] (mapv rewrite xs))))
                       (sort-by (juxt :uri #(digest (dissoc % :source-id))) incoming))
        incoming (mapv (fn [c]
                         (let [existing (get-in s [:concepts [ontology-id (:uri c)]])]
                           (cond-> (assoc c :created-at (or (:created-at existing) recorded-at))
                             existing (assoc :updated-at recorded-at)))) incoming)
        candidate (reduce #(state/put-concept %1 ontology-id %2) s incoming)
        candidate (reduce (fn [s e]
                            (doseq [k [:source-ontology-id :target-ontology-id]
                                    :when (contains? e k)]
                              (require! (= ontology-id (get e k))
                                        "Contributions contain local edges; use create-relationship! for cross-ontology edges"))
                            (state/put-edge s
                              (-> (state/edge ontology-id (rewrite (or (:subject e) (:source-uri e)))
                                               (:predicate e) (rewrite (or (:object e) (:target-uri e))))
                                  (merge (select-keys e [:metadata]))
                                  (assoc :created-at recorded-at)))) candidate relationships)
        _ (validate-graph! candidate ontology-id)
        all-concepts (filterv #(= ontology-id (:ontology-id %)) (vals (:concepts candidate)))
        all-edges (filterv #(= ontology-id (:source-ontology-id %)) (vals (:edges candidate)))
        schema (into {} (map (fn [[k records]]
                              [k (mapv #(rewrite-fields % [:uri :domain :range :datatype] rewrite) records)]))
                     (merge-with merge-records (get-in s [:tbox ontology-id] {}) (or tbox {})))
        instances (mapv #(cond-> (rewrite-fields % [:uri :type] rewrite)
                           (:properties %) (update :properties
                                                  (fn [properties] (into {} (map (fn [[k v]] [(rewrite k) v])) properties))))
                        (merge-records (get-in s [:abox ontology-id]) abox))
        triples (mapv #(rewrite-fields % [:subject :predicate :object-uri :datatype] rewrite)
                      (distinct (concat (get-in s [:rdf-triples ontology-id]) rdf-triples)))
        _ (validate-schema! all-concepts schema instances)]
    {:type :ontology/contribution-imported
     :body {:ontology-id ontology-id :contribution-id contribution-id :source source
            :input (dissoc p :command/name :command/id :command/timestamp)
            :concepts (vec (sort-by :uri all-concepts)) :relationships (vec (sort-by (comp pr-str state/edge-key) all-edges))
            :tbox schema :abox instances
            :rdf-triples triples
            :canonical-map canonical :resolution (select-keys resolution [:exact-matches :semantic-matches :matches])
            :recorded-at recorded-at}
     :result {:contribution-id contribution-id :concept-count (count all-concepts)
              :relationship-count (count all-edges) :canonical-map canonical}}))

(defcommand :ontology import-contribution {:authorized? tenant-scoped?}
  [ctx]
  (let [p (:command ctx)]
    (command-result ctx (:ontology-id p) (:contribution-id p)
                    #(prepare-contribution % p))))
