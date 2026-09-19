(ns ai.obney.ontology.core.commands
  (:require [ai.obney.ontology.core.trace :as trace] [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.streams :as streams]
            [ai.obney.ontology.core.delta :as delta]
            [ai.obney.ontology.core.indexes :as indexes]
            [ai.obney.ontology.core.schemas]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.ontology.core.source-registry :as source-registry]
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
  (trace/span :ontology.trace/request-or-record-hash
    (let [canonical (walk/postwalk (fn [v]
                                     (cond (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                                           (set? v) (vec (sort-by pr-str v))
                                           :else v)) value)]
      (source-registry/sha256 (pr-str canonical)))))

(defn require-ontology! [s oid]
  (when-not (get-in s [:ontologies oid]) (fail! ::anom/not-found "Ontology not found")))

(defn- affected-relationship-keys [s event]
  (let [e (state/event-data event) oid (:ontology-id e)]
    (case (:event/type e)
      :ontology/contribution-imported (keys (get-in e [:changes :relationships :upserts]))
      :ontology/relationship-created [(state/edge-key e)]
      :ontology/concept-created (state/parent-keys s oid (:uri e))
      :ontology/concept-updated (when (contains? (:changes e) :broader)
                                  (state/parent-keys s oid (:uri e)))
      :ontology/predicate-registered (get-in s [:indexes :predicate-edges [oid (:predicate e)]])
      :ontology/ontology-created (mapcat #(get-in s [:indexes :predicate-edges [oid %]])
                                         (keys (get-in s [:ontologies oid :predicate-policies])))
      [])))

(defn validate-hierarchy-edge! [adjacency start target]
  (loop [pending [target] seen #{}]
    (when-let [node (peek pending)]
      (require! (not= start node) "Hierarchy cycle")
      (recur (if (seen node) (pop pending)
                 (into (pop pending) (keys (get adjacency node))))
             (conj seen node)))))

(defn validate-hierarchy! [before s event]
  (trace/span :ontology.trace/validate-hierarchy
    ;; The committed graph is acyclic. Every new cycle must contain a newly
    ;; reachable direct connection. Removals and parallel edges cannot add one.
    (let [adjacency (get-in s [:indexes :hierarchy])
          additions (into #{}
                          (keep (fn [[so su _ to tu]]
                                  (let [source [so su] target [to tu]]
                                    (when (and (get-in adjacency [source target])
                                               (not (get-in before [:indexes :hierarchy source target])))
                                      [source target]))))
                          (affected-relationship-keys s event))]
        (doseq [[start target] additions]
          ;; Use the final candidate, so additions are checked together and
          ;; paths removed by the same command do not cause false rejection.
          (validate-hierarchy-edge! adjacency start target)))))

(defn validate-concept! [c]
  (require! (and (nonblank? (:uri c)) (nonblank? (:label c)) (nonblank? (:description c)))
            "Concept URI, label and description must be nonblank"))

(defn validate-relationship! [s e]
  (let [source [(:source-ontology-id e) (:source-uri e)]
        target [(:target-ontology-id e) (:target-uri e)]
        policy (get-in s [:ontologies (:source-ontology-id e) :predicate-policies (:predicate e)])]
    (when-not (and (get-in s [:concepts source]) (get-in s [:concepts target]))
      (fail! ::anom/not-found "Relationship endpoints must exist"))
    (require! policy (str "Undeclared predicate: " (:predicate e)))
    (require! (or (= (first source) (first target)) (:cross-ontology? policy))
              "Predicate does not permit cross-ontology relationships")
    (require! (not= source target) "Self relationships are not supported")))

(defn validate-graph! [s oid before event]
  (trace/span :ontology.trace/validate-graph
    (let [s (indexes/ensure-indexed s)
          e (state/event-data event)
          imported? (= :ontology/contribution-imported (:event/type e))
          concepts (case (:event/type e)
                     :ontology/contribution-imported (keys (get-in e [:changes :concepts :upserts]))
                     :ontology/concept-created [[oid (:uri e)]]
                     :ontology/concept-updated [[oid (:uri e)]]
                     [])
          removed (when imported? (get-in e [:changes :concepts :removals]))
          ;; Endpoint removal can invalidate unchanged incoming or outgoing edges.
          ;; Select them from the prior index, then check only surviving edges.
          dependencies (mapcat #(get-in before [:indexes :concept-edges %]) removed)
          edges (into (set (affected-relationship-keys s event)) dependencies)]
      (doseq [key concepts] (validate-concept! (get-in s [:concepts key])))
      (doseq [key edges :let [edge (get-in s [:edges key])] :when edge]
        (validate-relationship! s edge))
      (validate-hierarchy! before s event))
    s))

(defn command-result [ctx oid request-id make-change]
  (trace/span :ontology.trace/command-handler
    (try
      (when-not (tenant-scoped? ctx) (fail! ::anom/forbidden "A tenant-id is required"))
      (let [command (:command ctx)
            request-hash (digest (dissoc command :command/id :command/timestamp))
            cached (state/read-request ctx oid request-id)
            s (when-not cached (state/read-state ctx))
            prior (or cached (get-in s [:requests [oid request-id]]))]
        (if prior
          (if (= request-hash (:request-hash prior))
            {:command-result/events [] :command-result/data (:result prior)}
            {::anom/category ::anom/conflict ::anom/message "Idempotency key reused with different payload"})
          (do
            (when oid (require-ontology! s oid))
            (when (and (contains? command :expected-revision)
                       (not= (:expected-revision command) (state/revision s oid)))
              (fail! ::anom/conflict "Stale ontology revision"))
            (let [{:keys [type body result]} (make-change s)
                  request-ontology-id oid
                  oid (or oid (:ontology-id result))
                  result (assoc result :ontology-id oid :revision (inc (state/revision s oid)))
                  body (assoc body :ontology-id oid :request-id request-id :request-hash request-hash :result result)
                  event (es/->event {:type type :tags #{} :body body})
                  candidate (state/apply-event s event)
                  event (assoc event :event/tags (streams/event-tags s candidate (state/event-data event)))
                  ids (:read-ontology-ids s)]
              (validate-graph! candidate oid s event)
              {:command-result/events [event]
               :command-result/data result
               ;; A connected graph may span several ontology streams. Grain CAS
               ;; accepts one query, so use a tenant suffix for that case.
               :command-result/cas (if (nil? request-ontology-id)
                                     ;; Creation has no graph identity yet. Atomically claim
                                     ;; its tenant-scoped retry key, even across concurrent creates.
                                     {:types state/events :limit 1
                                      :tags #{(streams/request-tag nil request-id)}
                                      :predicate-fn #(empty? (into [] (take 1) %))}
                                     (cond-> {:types state/events :limit 1
                                              :predicate-fn #(empty? (into [] (take 1) %))}
                                       (:read-head s) (assoc :after (:read-head s))
                                       (= 1 (count ids)) (assoc :tags #{[:ontology (first ids)]})))}))))
      (catch Exception e
        (if (::anom/category (ex-data e)) (ex-data e)
            ;; Grain catches handler exceptions. Preserve the cause for the public
            ;; dispatcher to rethrow after the engine declines to append events.
            {::anom/category ::anom/fault ::anom/message (.getMessage e) ::exception e})))))

(defcommand :ontology create-ontology {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [name] :as p} (:command ctx) id (random-uuid)]
    (command-result ctx nil (:command/id p)
      (fn [_]
        (require! (nonblank? name) "Ontology name must be nonblank")
        {:type :ontology/ontology-created
         :body (assoc (select-keys p [:name :scope :description :base-uri :predicate-policies])
                      :ontology-id id :created-at (str (java.time.Instant/now)))
         :result {:ontology-id id}}))))

(defcommand :ontology create-concept {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [ontology-id uri] :as p} (:command ctx) id (random-uuid)]
    (command-result ctx ontology-id (:command/id p)
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
        (let [key (first (get-in s [:indexes :concept-ids [ontology-id concept-id]]))
              c (get-in s [:concepts key])
              changes (select-keys changes [:label :description :broader :indicators :attributes :embedding :embedding-model
                                            :scope :alt-labels :provenance :entity-type])]
          (when-not c (fail! ::anom/not-found "Concept not found"))
          (require! (seq changes) "No mutable fields supplied")
          {:type :ontology/concept-updated
           :body {:ontology-id ontology-id :concept-id concept-id :uri (:uri c) :changes changes
                  :updated-at (str (java.time.Instant/now))}
           :result {:concept-id concept-id :uri (:uri c)}})))))

(defcommand :ontology create-relationship {:authorized? tenant-scoped?}
  [ctx]
  (let [{:keys [source-ontology-id target-ontology-id] :as p} (:command ctx)
        relationship-id (random-uuid)]
    (command-result ctx source-ontology-id (:command/id p)
      (fn [s]
        (require-ontology! s source-ontology-id)
        (require-ontology! s target-ontology-id)
        (let [e (select-keys p [:source-ontology-id :target-ontology-id :source-uri :target-uri :predicate :metadata])]
          (when (some #(= (state/edge-key e) (state/edge-key %))
                      (state/relationships s source-ontology-id))
            (fail! ::anom/conflict "Relationship already exists"))
          {:type :ontology/relationship-created
           :body (assoc e :relationship-id relationship-id :created-at (str (java.time.Instant/now)))
           :result (assoc e :relationship-id relationship-id)})))))

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

(defn- validate-schema! [concept-uris tbox abox]
  (require! (every? #{:classes :object-properties :datatype-properties} (keys tbox))
            "Unsupported TBox section")
  (doseq [[_ records] tbox]
    (require! (and (vector? records) (every? #(and (map? %) (nonblank? (:uri %))) records))
              "TBox records require a URI"))
  (let [known (into (or concept-uris #{}) (map :uri (:classes tbox)))
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

(defn prepare-schema [s candidate ontology-id rewrite tbox abox rdf-triples]
  (trace/span :ontology.trace/schema-rdf
    (let [schema (into {} (map (fn [[k records]]
                                [k (mapv #(rewrite-fields % [:uri :domain :range :datatype] rewrite) records)]))
                       (merge-with merge-records (get-in s [:tbox ontology-id] {}) (or tbox {})))
          instances (mapv #(cond-> (rewrite-fields % [:uri :type] rewrite)
                             (:properties %) (update :properties
                                                    (fn [properties] (into {} (map (fn [[k v]] [(rewrite k) v])) properties))))
                          (merge-records (get-in s [:abox ontology-id]) abox))
          triples (mapv #(rewrite-fields % [:subject :predicate :object-uri :datatype] rewrite)
                        (distinct (concat (get-in s [:rdf-triples ontology-id]) rdf-triples)))
          _ (validate-schema! (get-in candidate [:indexes :concept-uris ontology-id]) schema instances)
          result (-> candidate
                        (assoc-in [:tbox ontology-id] (update-vals schema #(delta/ordered (delta/index %))))
                        (assoc-in [:abox ontology-id] (delta/ordered (delta/index instances)))
                        (assoc-in [:rdf-triples ontology-id] (vec (sort-by pr-str (set triples)))))]
      result)))

(defn prepare-contribution [s {:keys [ontology-id contribution-id source concepts relationships
                                    tbox abox resolution-policy rdf-triples] :as p}]
  (trace/span :ontology.trace/prepare-contribution
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
          incoming (mapv #(-> % (assoc :source-id (:source-id source))
                               (update :entity-type (fn [t] (or t (:scope %) "Concept")))) concepts)
          existing-uris (get-in s [:indexes :concept-uris ontology-id] #{})
          resolution (if (:match-labels? resolution-policy)
                       (entity-resolver/resolve-incremental incoming (indexes/resolution-candidates s ontology-id incoming) existing-uris
                                                       {:similarity-threshold (or (:similarity-threshold resolution-policy) 0.85)})
                       {:canonical-map {} :exact-matches 0 :semantic-matches 0})
          canonical (:canonical-map resolution)
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
          candidate (reduce #(state/put-concept %1 ontology-id %2) (delta/track s) incoming)
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
          candidate (prepare-schema s candidate ontology-id rewrite tbox abox rdf-triples)]
      {:type :ontology/contribution-imported
       :body {:ontology-id ontology-id :contribution-id contribution-id :source source
              :input (dissoc p :command/name :command/id :command/timestamp)
              :changes (delta/changes s candidate ontology-id)
              :canonical-map canonical :resolution (select-keys resolution [:exact-matches :semantic-matches :matches])
              :recorded-at recorded-at}
       :result {:contribution-id contribution-id :concept-count (count (get-in candidate [:indexes :concept-uris ontology-id]))
                :relationship-count (count (get-in candidate [:indexes :outgoing ontology-id])) :canonical-map canonical}})))

(defcommand :ontology import-contribution {:authorized? tenant-scoped?}
  [ctx]
  (let [p (:command ctx)]
    (command-result ctx (:ontology-id p) (:contribution-id p)
                    #(prepare-contribution % p))))
