(ns ai.obney.ontology.interface
  "Public ontology graph API. Requires Grain and tenant context, no execution engine."
  (:require [ai.obney.ontology.core.commands :as commands]
            [ai.obney.ontology.core.schemas]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.algorithms :as algorithms]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn- dispatch! [ctx command-name params]
  (let [command (assoc (merge {:command/id (random-uuid) :command/timestamp (time/now)} params)
                       :command/name command-name)
        result (cp/process-command (assoc ctx :command command))]
    (if (:cognitect.anomalies/category result) result (:command-result/data result))))

(defn create-ontology! [ctx params] (dispatch! ctx :ontology/create-ontology params))
(defn create-concept! [ctx ontology-id concept]
  (dispatch! ctx :ontology/create-concept (assoc concept :ontology-id (state/normalize-id ontology-id))))
(defn update-concept!
  ([ctx oid cid changes] (update-concept! ctx oid cid changes {}))
  ([ctx oid cid changes command-meta]
   (dispatch! ctx :ontology/update-concept
              (merge {:ontology-id (state/normalize-id oid) :concept-id cid :changes changes} command-meta))))
(defn create-relationship! [ctx edge]
  (dispatch! ctx :ontology/create-relationship
             (-> edge (update :source-ontology-id state/normalize-id) (update :target-ontology-id state/normalize-id))))
(defn register-predicate! [ctx oid predicate policy]
  (dispatch! ctx :ontology/register-predicate {:ontology-id (state/normalize-id oid) :predicate predicate :policy policy}))
(defn import-contribution! [ctx contribution]
  (dispatch! ctx :ontology/import-contribution (update contribution :ontology-id state/normalize-id)))

(defn import-rdf!
  "Import the supported N-Triples subset without any model or workflow."
  [ctx {:keys [content source] :as request}]
  (try
    (let [parsed (algorithms/extract-concepts-from-rdf (:source-id source) content {})
          concepts (mapv #(-> % (assoc :description (:definition %) :scope (:entity-type %))
                              (dissoc :definition)) (:concepts parsed))]
      (import-contribution! ctx (merge (dissoc request :content) parsed {:concepts concepts})))
    (catch Exception e
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (.getMessage e)})))

(def graph-event-types state/events)

(defn get-ontology [ctx oid]
  (let [oid (state/normalize-id oid) s (state/read-state ctx)]
    (when-let [o (get-in s [:ontologies oid])] (assoc o :revision (state/revision s oid)))))
(defn list-ontologies
  ([ctx] (let [s (state/read-state ctx)]
           (mapv #(assoc % :revision (state/revision s (:ontology-id %)))
                 (sort-by (comp str :ontology-id) (vals (:ontologies s)))))))
(defn ontology-exists? [ctx oid] (boolean (get-ontology ctx oid)))
(defn get-revision [ctx oid] (state/revision (state/read-state ctx) (state/normalize-id oid)))
(defn get-concepts
  ([ctx] (get-concepts ctx {}))
  ([ctx {:keys [ontology-id ontology-ids scope broader-uri]}]
   (let [ids (cond ontology-ids (set (map state/normalize-id ontology-ids))
                   ontology-id #{(state/normalize-id ontology-id)})]
     (cond->> (state/concepts (state/read-state ctx))
       ids (filterv #(ids (:ontology-id %)))
       scope (filterv #(= scope (:scope %)))
       broader-uri (filterv #(contains? (:broader %) broader-uri))))))
(defn get-concept-by-uri [ctx oid uri]
  (when-not oid (throw (ex-info "Concept lookup requires ontology-id" {})))
  (first (filter #(= uri (:uri %)) (get-concepts ctx {:ontology-id oid}))))
(defn get-concept-map [ctx]
  (into {} (map (fn [c] [[(:ontology-id c) (:uri c)] c])) (get-concepts ctx)))
(defn get-relationships [ctx oid]
  (state/relationships (state/read-state ctx) (state/normalize-id oid)))
(defn get-contributions [ctx oid]
  (let [oid (state/normalize-id oid)]
    (->> (:contributions (state/read-state ctx))
         (keep (fn [[[id _] c]] (when (= id oid) c)))
         (sort-by (juxt :recorded-at (comp str :contribution-id))) vec)))
(defn get-sources [ctx oid]
  (let [oid (state/normalize-id oid)]
    (->> (:sources (state/read-state ctx))
         (keep (fn [[[id _ _] c]] (when (= id oid) c)))
         (sort-by (juxt (comp str :source-id) :content-hash)) vec)))
(defn get-canonical-mappings [ctx oid]
  (get-in (state/read-state ctx) [:mappings (state/normalize-id oid)] {}))

(defn- state-at [ctx oid revision]
  (if (nil? revision) (state/read-state ctx)
      (reduce (fn [s e]
                (if (= revision (state/revision s oid)) (reduced s) (state/apply-event s e)))
              {} (state/read-events ctx))))

(defn export-snapshot
  ([ctx oid] (export-snapshot ctx oid nil))
  ([ctx oid revision]
   (let [oid (state/normalize-id oid) s (state-at ctx oid revision)
         o (get-in s [:ontologies oid])]
     (when-not o (throw (ex-info "Ontology not found" {:ontology-id oid})))
     (when (and revision (not= revision (state/revision s oid)))
       (throw (ex-info "Graph revision not found" {:revision revision})))
     (let [concepts (filterv #(= oid (:ontology-id %)) (state/concepts s))
           relationships (state/relationships s oid)
           tbox (get-in s [:tbox oid] {}) abox (get-in s [:abox oid] [])
           result (algorithms/generate-owl-ttl-snapshot
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
  [ctx {:keys [ontology-id query-text seed-uris limit max-depth weights embed-query embedding-model]
        :or {limit 10 max-depth 2 weights {:lexical 1.0 :graph 1.0 :embedding 1.0}}}]
  (when-not ontology-id (throw (ex-info "Search requires ontology-id" {})))
  (when-not (pos-int? limit) (throw (ex-info "Search limit must be positive" {})))
  (when-not (nat-int? max-depth) (throw (ex-info "Search depth must be nonnegative" {})))
  (when-not (and (map? weights)
                (every? #(and (number? %) (Double/isFinite (double %)) (not (neg? %))) (vals weights)))
    (throw (ex-info "Search weights must be finite and nonnegative" {})))
  (let [cs (get-concepts ctx {:ontology-id ontology-id})
        by-uri (into {} (map (juxt :uri identity)) cs)
        q (tokens query-text)
        lexical (->> cs (keep (fn [c]
                               (let [n (count (set/intersection q (tokens (str (:label c) " " (:description c)))))]
                                 (when (pos? n) {:uri (:uri c) :score n}))))
                     (sort-by (juxt (comp - :score) :uri)))
        graph (when (seq seed-uris)
                (algorithms/bfs-spreading-activation (algorithms/concepts->graph by-uri)
                                                     (filter by-uri seed-uris) {:max-depth max-depth}))
        query-vector (when (and embed-query query-text) (embed-query query-text))
        embeddings (when (and query-vector (some? embedding-model))
                     (->> cs
                          (keep (fn [c]
                                  (when (= embedding-model (:embedding-model c))
                                    (when-let [score (algorithms/cosine-similarity query-vector (:embedding c))]
                                      {:uri (:uri c) :score score}))))
                          (sort-by (juxt (comp - :score) :uri))))
        ranked (reduce (fn [scores [signal candidates]]
                         (reduce (fn [scores [i {:keys [uri]}]]
                                   (update scores uri (fnil + 0.0) (/ (get weights signal 1.0) (+ 60.0 (inc i)))))
                                 scores (map-indexed vector candidates)))
                       {} [[:lexical lexical] [:graph graph] [:embedding embeddings]])]
    (->> ranked (sort-by (juxt (comp - val) key)) (take limit)
         (keep (fn [[uri score]] (when-let [c (by-uri uri)] (assoc c :score score)))) vec)))
