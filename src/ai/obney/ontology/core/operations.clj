(ns ai.obney.ontology.core.operations
  "Ontology operations: command dispatch, graph reads, imports, export, and search."
  (:require [ai.obney.ontology.core.errors :as errors]
            [ai.obney.ontology.core.commands :as commands]
            [ai.obney.ontology.core.schemas]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.ontology.core.graph :as graph]
            [ai.obney.ontology.core.graph-evolver :as graph-evolver]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn- dispatch! [ctx command-name request]
  (let [import? (= command-name :ontology/import-contribution)]
    (cond
      (and import? (contains? request :request-id))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Imports use contribution-id, not request-id"}

      (and (contains? request :request-id) (not (uuid? (:request-id request))))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Request ID must be a UUID"}

      (and (contains? request :expected-revision) (not (int? (:expected-revision request))))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Expected revision must be an integer"}

      :else
      (let [command (assoc (dissoc request :request-id)
                           :command/id (or (:request-id request) (random-uuid))
                           :command/timestamp (time/now)
                           :command/name command-name)
            result (cp/process-command (assoc ctx :command command))]
        (if-let [exception (::commands/exception result)]
          (throw exception)
          (if (:cognitect.anomalies/category result) result (:command-result/data result)))))))

(defn create-ontology! [ctx params] (dispatch! ctx :ontology/create-ontology params))
(defn create-concept! [ctx ontology-id concept]
  (dispatch! ctx :ontology/create-concept (assoc concept :ontology-id (state/normalize-id ontology-id))))
(defn update-concept! [ctx ontology-id concept-id request]
  (dispatch! ctx :ontology/update-concept
             (assoc request :ontology-id (state/normalize-id ontology-id) :concept-id concept-id)))
(defn create-relationship! [ctx edge]
  (dispatch! ctx :ontology/create-relationship
             (-> edge (update :source-ontology-id state/normalize-id) (update :target-ontology-id state/normalize-id))))
(defn register-predicate! [ctx ontology-id request]
  (dispatch! ctx :ontology/register-predicate
             (assoc request :ontology-id (state/normalize-id ontology-id))))
(defn import-contribution! [ctx ontology-id request]
  (dispatch! ctx :ontology/import-contribution
             (assoc request :ontology-id (state/normalize-id ontology-id))))

(defn import-rdf!
  "Import the supported N-Triples subset without any model or workflow."
  [ctx ontology-id {:keys [content source] :as request}]
  (let [parsed (rdf/extract-concepts-from-rdf (:source-id source) content {})
        concepts (mapv #(-> % (assoc :description (:definition %) :scope (:entity-type %))
                            (dissoc :definition)) (:concepts parsed))]
    (import-contribution! ctx ontology-id (merge (dissoc request :content) parsed {:concepts concepts}))))

(def graph-event-types state/events)

(defn get-ontology [ctx oid]
  (let [oid (state/normalize-id oid) s (state/metadata-state ctx oid)]
    (when-let [o (get-in s [:ontologies oid])] (assoc o :revision (state/revision s oid)))))
(defn list-ontologies
  ([ctx] (let [s (state/metadata-state ctx nil)]
           (mapv #(assoc % :revision (state/revision s (:ontology-id %)))
                 (sort-by (comp str :ontology-id) (vals (:ontologies s)))))))
(defn ontology-exists? [ctx oid] (boolean (get-ontology ctx oid)))
(defn get-revision [ctx oid]
  (:revision (get-ontology ctx oid)))
(defn get-concepts
  ([ctx] (get-concepts ctx {}))
  ([ctx {:keys [ontology-id ontology-ids scope broader-uri]}]
   (let [ids (cond ontology-ids (set (map state/normalize-id ontology-ids))
                   ontology-id #{(state/normalize-id ontology-id)})]
     (let [s (state/read-state (assoc ctx :ontology/ids ids))]
       (doseq [id ids] (commands/require-ontology! s id))
       (state/concepts s {:ids ids :scope scope :broader-uri broader-uri})))))
(defn get-concept [ctx oid concept-id]
  (let [oid (state/normalize-id oid)
        s (state/read-state (assoc ctx :ontology/ids #{oid}))]
    (when-let [key (first (get-in s [:indexes :concept-ids [oid concept-id]]))]
      (state/concept-view s key))))
(defn get-concept-by-uri [ctx oid uri]
  (when-not oid (errors/fail! :cognitect.anomalies/incorrect "Concept lookup requires ontology-id"))
  (let [oid (state/normalize-id oid)]
    (state/concept-view (state/point-state ctx oid uri) [oid uri])))
(defn get-concept-map
  ([ctx] (get-concept-map ctx {}))
  ([ctx filters]
   (into {} (map (fn [c] [[(:ontology-id c) (:uri c)] c])) (get-concepts ctx filters))))
(defn get-relationships [ctx oid]
  (let [oid (state/normalize-id oid) s (state/relationship-state ctx oid)]
    (commands/require-ontology! s oid)
    (state/relationships s oid)))
(defn- require-history [ctx oid]
  (commands/require-ontology! (state/metadata-state ctx oid) oid)
  (state/history-state ctx oid))
(defn get-contributions [ctx oid]
  (let [oid (state/normalize-id oid)]
    (->> (:contributions (require-history ctx oid))
         (keep (fn [[[id _] c]] (when (= id oid) c)))
         (sort-by (juxt :recorded-at (comp str :contribution-id)))
         (mapv #(select-keys % [:contribution-id :ontology-id :source :input
                               :canonical-map :resolution :result :recorded-at])))))
(defn get-sources [ctx oid]
  (let [oid (state/normalize-id oid)]
    (->> (:sources (require-history ctx oid))
         (keep (fn [[[id _ _] c]] (when (= id oid) c)))
         (sort-by (juxt (comp str :source-id) :content-hash)) vec)))
(defn get-canonical-mappings [ctx oid]
  (let [oid (state/normalize-id oid)] (get-in (require-history ctx oid) [:mappings oid] {})))

(defn export-snapshot
  ([ctx oid] (export-snapshot ctx oid nil))
  ([ctx oid revision]
   (let [oid (state/normalize-id oid) s (state/state-at ctx oid revision)
         o (get-in s [:ontologies oid])]
     (when-not o (errors/fail! :cognitect.anomalies/not-found "Ontology not found"))
     (when (and revision (not= revision (state/revision s oid)))
       (errors/fail! :cognitect.anomalies/not-found "Graph revision not found"))
     (let [concepts (filterv #(= oid (:ontology-id %)) (state/concepts s))
           relationships (state/relationships s oid)
           tbox (get-in s [:tbox oid] {}) abox (get-in s [:abox oid] [])
           result (graph-evolver/generate-owl-ttl-snapshot
                    {:concepts (into {} (map (juxt :uri #(assoc % :definition (:description %)))) concepts)
                     :relationships (mapv #(assoc % :subject (:source-uri %) :object (:target-uri %)) relationships)
                     :tbox tbox :abox abox :rdf-triples (get-in s [:rdf-triples oid])}
                    {:base-uri (or (:base-uri o) (str "urn:ontology:" oid)) :include-metadata? true})]
       {:ontology-id oid :revision (state/revision s oid)
        :concepts concepts :relationships relationships :tbox tbox :abox abox
        :rdf-triples (get-in s [:rdf-triples oid] [])
        :ttl-output (:ttl-string result) :triple-count (:triple-count result) :checksum (:checksum result)}))))

(defn- tokens [s] (set (re-seq #"[\p{L}\p{N}]+" (str/lower-case (or s "")))))
(defn search
  "Scoped concept search. Lexical and graph signals work without a model.
   Optional :embed-query returns a vector; :embedding-model must match stored vectors."
  [ctx ontology-id {:keys [query-text seed-uris limit max-depth weights embed-query embedding-model]
        :or {limit 10 max-depth 2 weights {:lexical 1.0 :graph 1.0 :embedding 1.0}}}]
  (when-not ontology-id (errors/fail! :cognitect.anomalies/incorrect "Search requires ontology-id"))
  (when-not (pos-int? limit) (errors/fail! :cognitect.anomalies/incorrect "Search limit must be positive"))
  (when-not (nat-int? max-depth) (errors/fail! :cognitect.anomalies/incorrect "Search depth must be nonnegative"))
  (when-not (and (map? weights)
                (every? #(and (number? %) (Double/isFinite (double %)) (not (neg? %))) (vals weights)))
    (errors/fail! :cognitect.anomalies/incorrect "Search weights must be finite and nonnegative"))
  (let [cs (get-concepts ctx {:ontology-id ontology-id})
        by-uri (into {} (map (juxt :uri identity)) cs)
        q (tokens query-text)
        lexical (->> cs (keep (fn [c]
                               (let [n (count (set/intersection q (tokens (str (:label c) " " (:description c)))))]
                                 (when (pos? n) {:uri (:uri c) :score n}))))
                     (sort-by (juxt (comp - :score) :uri)))
        graph (when (seq seed-uris)
                (graph/bfs-spreading-activation (graph/concepts->graph by-uri)
                                                     (filter by-uri seed-uris) {:max-depth max-depth}))
        query-vector (when (and embed-query query-text) (embed-query query-text))
        embeddings (when (and query-vector (some? embedding-model))
                     (->> cs
                          (keep (fn [c]
                                  (when (= embedding-model (:embedding-model c))
                                    (when-let [score (entity-resolver/cosine-similarity query-vector (:embedding c))]
                                      {:uri (:uri c) :score score}))))
                          (sort-by (juxt (comp - :score) :uri))))
        ranked (reduce (fn [scores [signal candidates]]
                         (reduce (fn [scores [i {:keys [uri]}]]
                                   (update scores uri (fnil + 0.0) (/ (get weights signal 1.0) (+ 60.0 (inc i)))))
                                 scores (map-indexed vector candidates)))
                       {} [[:lexical lexical] [:graph graph] [:embedding embeddings]])]
    (->> ranked (sort-by (juxt (comp - val) key)) (take limit)
         (keep (fn [[uri score]] (when-let [c (by-uri uri)] (assoc c :score score)))) vec)))
