(ns ai.obney.ontology.property-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.core.source-registry :as source-registry]
            [ai.obney.ontology.support :as h]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(def seed (Long/parseLong (or (System/getenv "ONTOLOGY_TEST_SEED") "20260918")))
(def trials (Long/parseLong (or (System/getenv "ONTOLOGY_TEST_TRIALS") "60")))
(defn check! [label property]
  ;; Each generated case owns fresh stores. Release their shared L1 entries
  ;; after the whole case (including replay), preserving warm reads within it.
  (rmp/l1-clear!)
  (let [isolated (gen/fmap (fn [result] (rmp/l1-clear!) result) property)
        result (tc/quick-check trials isolated :seed seed :max-size 35)]
    (println label (select-keys result [:seed :num-tests :pass? :time-elapsed-ms :shrunk]))
    (is (:pass? result) (pr-str result))))
(defn uri [n] (str "urn:n:" n))
(def operation-gen
  (gen/tuple (gen/elements [:add :rename :parents :import :retry :bad-import])
             (gen/choose 0 8) (gen/choose 0 8) (gen/elements ["Alpha" "Beta" "Gamma" "Δ"])))
(defn reaches? [model from target]
  (loop [todo [from] seen #{}]
    (if-let [x (peek todo)]
      (cond (= x target) true
            (seen x) (recur (pop todo) seen)
            :else (recur (into (pop todo) (get-in model [x :broader])) (conj seen x)))
      false)))
(defn public-model [ctx oid]
  (into {} (map (fn [c] [(:uri c) (select-keys c [:label :broader])]))
        (o/get-concepts ctx {:ontology-id oid})))
(defn inverse-correct? [ctx oid model]
  (let [edges (o/get-relationships ctx oid)
        keys (map (juxt :source-ontology-id :source-uri :predicate :target-ontology-id :target-uri) edges)]
    (and (= (count keys) (count (set keys)))
         (every? #(= oid (:source-ontology-id %) (:target-ontology-id %)) edges)
         (every? (fn [c]
            (= (:narrower c)
               (set (keep (fn [[u record]] (when (contains? (:broader record) (:uri c)) u)) model))))
          (o/get-concepts ctx {:ontology-id oid})))))

(defn exercise-sequence [operations]
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (loop [ops operations model {} revision 1 prior nil snapshots {1 (o/export-snapshot ctx oid)}]
        (if-let [[op n p label] (first ops)]
          (let [u (uri n) parent (uri p) existing (get model u)
                c (o/get-concept-by-uri ctx oid u)
                input (when (#{:import :bad-import} op)
                        (h/request ctx oid [(cond-> (assoc (h/concept u) :label label)
                                              (= op :bad-import) (assoc :broader ["urn:absent"]))]))
                allowed (case op :add (nil? existing) :rename (some? existing)
                              :parents (and existing (model parent) (not (reaches? model parent u)))
                              :bad-import false true)
                before-events (h/graph-events ctx)
                result (case op
                         :add (o/create-concept! ctx oid (assoc (h/concept u) :label label))
                         :rename (o/update-concept! ctx oid (or (:concept-id c) (random-uuid)) {:changes {:label label}})
                         :parents (o/update-concept! ctx oid (or (:concept-id c) (random-uuid)) {:changes {:broader [parent]}})
                         (:import :bad-import) (o/import-contribution! ctx oid input)
                         :retry (when prior (o/import-contribution! ctx oid (first prior))))
                changes? (and allowed (not= op :retry))
                expected (if changes?
                           (case op
                             (:add :import) (assoc model u {:label label :broader (or (:broader existing) #{})})
                             :rename (assoc-in model [u :label] label)
                             :parents (assoc-in model [u :broader] #{parent})) model)
                next-revision (if changes? (inc revision) revision)
                snapshot (o/export-snapshot ctx oid)
                good (and (= (boolean allowed) (not (h/anomaly? result)))
                          (= expected (public-model ctx oid))
                          (= next-revision (:revision snapshot))
                          (inverse-correct? ctx oid expected)
                          (if changes? true (= before-events (h/graph-events ctx)))
                          (if (and (= op :retry) prior) (= (second prior) result) true))]
            (if good
              (recur (rest ops) expected next-revision
                     (if (= op :import) [input result] prior) (assoc snapshots next-revision snapshot))
              false))
          (and (every? (fn [[r snapshot]] (= snapshot (o/export-snapshot ctx oid r))) snapshots)
               (h/with-context [fresh]
                 (h/replay! ctx fresh)
                 (and (= (o/export-snapshot ctx oid) (o/export-snapshot fresh oid))
                      (= (o/get-contributions ctx oid) (o/get-contributions fresh oid))
                      (= model (public-model fresh oid))))))))))

(deftest generated-mutation-sequences-match-independent-model-and-replay
  (check! "mutation-sequences"
          (prop/for-all [operations (gen/vector operation-gen 1 35)] (exercise-sequence operations))))

(deftest canonical-choice-is-independent-of-input-order
  (check! "canonical-choice"
          (prop/for-all [names (gen/vector-distinct (gen/fmap #(str "urn:" %) gen/string-alphanumeric) {:min-elements 1 :max-elements 15})
                        prefer gen/boolean]
            (let [existing (if prefer #{(first names)} #{})
                  expected (if prefer (first names) (first (sort-by (juxt count identity) names)))]
              (= expected (entity-resolver/select-canonical-uri names existing)
                 (entity-resolver/select-canonical-uri (reverse names) existing))))))

(deftest literal-export-roundtrip-has-exact-statements-and-checksum
  (check! "literal-roundtrip"
          (prop/for-all [pieces (gen/vector (gen/elements ["a" "λ" "🌳" "\n" "\r" "\t" "\b" "\f" "\\" "\"" " " "<>"]) 0 25)]
            (h/with-context [ctx]
              (let [oid (h/graph ctx) literal (apply str pieces)
                    input (assoc (h/request ctx oid []) :rdf-triples [{:subject "urn:s" :predicate "urn:p" :literal literal}])]
                (h/ok! (o/import-contribution! ctx oid input))
                (let [snapshot (o/export-snapshot ctx oid)
                      triples (rdf/parse-ntriples (:ttl-output snapshot))]
                  (and (some #(= literal (:literal %)) triples)
                       (= (:triple-count snapshot) (count triples))
                       (= (:checksum snapshot) (source-registry/sha256 (:ttl-output snapshot))))))))))

(deftest disjoint-contributions-have-order-independent-rdf
  (check! "disjoint-import-order"
          (prop/for-all [labels (gen/vector-distinct gen/string-alphanumeric {:min-elements 1 :max-elements 8})]
            (let [records (mapv (fn [i label] (assoc (h/concept (uri i)) :label (str "Label " label))) (range) labels)
                  snapshots (mapv (fn [cs]
                                    (h/with-context [ctx]
                                      (let [oid (h/graph ctx)]
                                        (doseq [c cs] (h/ok! (o/import-contribution! ctx oid (h/request ctx oid [c]))))
                                        (select-keys (o/export-snapshot ctx oid) [:ttl-output :checksum :triple-count]))))
                                  [records (reverse records)])]
              (apply = snapshots)))))

(deftest generated-source-versions-stay-unique-and-tenant-scoped
  (check! "source-versions"
    (prop/for-all [versions (gen/vector (gen/tuple (gen/choose 0 3) (gen/choose 0 3)) 1 15)]
      (h/with-context [ctx]
        (let [oid (h/graph ctx) foreign (assoc ctx :tenant-id (random-uuid))]
          (loop [remaining versions expected #{}]
            (if-let [[source version] (first remaining)]
              (let [sid (java.util.UUID/nameUUIDFromBytes (.getBytes (str source) "UTF-8"))
                    hash (str "version-" version)
                    _ (h/ok! (o/import-contribution! ctx oid (assoc (h/request ctx oid []) :source {:source-id sid :content-hash hash})))
                    expected (conj expected [sid hash])
                    actual (mapv (juxt :source-id :content-hash) (o/get-sources ctx oid))]
                (and (= expected (set actual)) (= (count actual) (count expected))
                     (= :cognitect.anomalies/not-found (:cognitect.anomalies/category (o/get-sources foreign oid)))
                     (recur (rest remaining) expected)))
              true)))))))

(deftest generated-resolution-scores-are-bounded
  (check! "resolution-scores"
    (prop/for-all [angle (gen/choose 0 1000) scale (gen/elements [1.0e-200 1.0 1.0e200])]
      (let [theta (* Math/PI (/ angle 2000.0))
            original {:uri "urn:original" :label "Original" :source-id "a" :entity-type "T" :embedding-model "m" :embedding [scale 0.0]}
            incoming {:uri "urn:new" :label "New" :source-id "b" :entity-type "T" :embedding-model "m"
                      :embedding [(* scale (Math/cos theta)) (* scale (Math/sin theta))]}
            result (entity-resolver/resolve-incremental [incoming] [original] #{"urn:original"} {:similarity-threshold 0.0})]
        (and (seq (:matches result))
             (every? #(and (Double/isFinite (double (:similarity-score %))) (<= 0 (:similarity-score %) 1)) (:matches result))
             (= "urn:original" (get (:canonical-map result) "urn:new")))))))

;; Independent public-boundary models deliberately avoid projection/index helpers.
(defn reachable-within [edges seeds depth]
  (loop [seen (set seeds) frontier (set seeds) remaining depth]
    (if (or (zero? remaining) (empty? frontier)) seen
        (let [neighbors (into #{} (mapcat (fn [[a b]]
                                          (cond-> [] (frontier a) (conj b) (frontier b) (conj a)))) edges)
              next-frontier (reduce disj neighbors seen)]
          (recur (into seen next-frontier) next-frontier (dec remaining))))))

(def search-graph-gen
  (gen/let [size (gen/choose 2 16)
            pairs (gen/vector (gen/tuple (gen/choose 0 (dec size))
                                        (gen/choose 0 (dec size)) gen/boolean) 0 30)
            seeds (gen/vector (gen/choose 0 (dec size)) 0 8)
            depth (gen/choose 0 18)]
    {:size size :seeds seeds :depth depth
     :edges (vec (distinct (keep (fn [[a b hierarchical?]]
                                  (when (not= a b)
                                    [(min a b) (max a b) (if hierarchical? "skos:broader" "skos:related")])) pairs)))}))

(deftest generated-search-agrees-with-independent-reachability
  (check! "search-reachability"
    (prop/for-all [{:keys [size edges seeds depth]} search-graph-gen]
      (h/with-context [ctx]
        (let [oid (h/graph ctx)
              _ (h/ok! (o/import-contribution! ctx oid
                         (assoc (h/request ctx oid (mapv (comp h/concept uri) (range size)))
                                :relationships (mapv (fn [[a b p]] {:subject (uri a) :object (uri b) :predicate p}) edges))))
              request {:seed-uris (conj (mapv uri seeds) "urn:absent") :max-depth depth :limit size}
              results (o/search ctx oid request)
              expected (set (map uri (reachable-within (map #(subvec % 0 2) edges) seeds depth)))]
          (and (vector? results)
               (= expected (set (map :uri results)))
               (= (count results) (count expected))
               (every? #(= oid (:ontology-id %)) results)
               (= results (o/search ctx oid (update request :seed-uris #(vec (reverse %)))))
               (= (vec (take 1 results)) (o/search ctx oid (assoc request :limit 1)))))))))

(def metadata-fields [:label :description :scope :alt-labels :provenance :entity-type :attributes])
(def metadata-change-gen
  (gen/let [label (gen/elements ["Alpha" "β" "Tree"])
            scope (gen/elements [:domain :other "shared"])
            aliases (gen/vector (gen/elements ["Alias" "別名" "old" "new"]) 0 3)
            value (gen/choose -10 10)
            fields (gen/not-empty (gen/set (gen/elements metadata-fields)))]
    (select-keys {:label label :description (str "Description " label) :scope scope
                  :alt-labels aliases :provenance {:version value} :entity-type label
                  :attributes {:replacement value}} fields)))

(defn metadata-view [records]
  (into {} (map (fn [c] [[(:ontology-id c) (:uri c)]
                         (select-keys c (into metadata-fields [:ontology-id :concept-id :uri]))])) records))

(deftest generated-metadata-updates-preserve-qualified-views-and-replay
  (check! "metadata-scopes"
    (prop/for-all [ops (gen/vector (gen/tuple (gen/choose 0 1) (gen/choose 0 2) metadata-change-gen) 1 15)]
      (h/with-context [ctx]
        (let [ids [(h/graph ctx) (h/graph ctx)]
              initial (into {}
                        (for [[i oid] (map-indexed vector ids) n (range 3)]
                          (let [c (assoc (h/concept (uri n)) :attributes {:old true} :provenance {:old true})
                                input (h/request ctx oid [c])
                                result (if (zero? i) (h/add! ctx oid (uri n))
                                           (h/ok! (o/import-contribution! ctx oid input)))
                                ;; Import assigns identity; subsequent expectations use that receipt's graph view.
                                cid (if (zero? i) (:concept-id result)
                                        (:concept-id (o/get-concept-by-uri ctx oid (uri n))))
                                record (if (zero? i) (h/concept (uri n))
                                           (assoc c :entity-type :domain
                                                  :provenance {:kind :source-extracted
                                                               :source-reference (str (get-in input [:source :source-id]))
                                                               :producer nil}))]
                            [[oid (uri n)] (assoc (select-keys record metadata-fields)
                                                 :ontology-id oid :concept-id cid :uri (uri n))])))]
          (loop [remaining ops model initial]
            (if-let [[i n changes] (first remaining)]
              (let [oid (ids i) key [oid (uri n)] cid (:concept-id (model key))
                    result (o/update-concept! ctx oid cid {:changes changes})
                    expected (update model key merge changes)
                    point (o/get-concept ctx oid cid)
                    selected (into {} (filter (fn [[[id _] _]] (= id oid))) expected)
                    scoped (into {} (filter (fn [[_ c]] (= :domain (:scope c)))) selected)]
                (and (not (h/anomaly? result))
                     (= expected (metadata-view (o/get-concepts ctx)))
                     (= point (o/get-concept-by-uri ctx oid (uri n)))
                     (= selected (metadata-view (o/get-concepts ctx {:ontology-id oid})))
                     (= scoped (metadata-view (o/get-concepts ctx {:ontology-id oid :scope :domain})))
                     (= (set (keys expected)) (set (keys (o/get-concept-map ctx {:ontology-ids (reverse ids)}))))
                     (nil? (o/get-concept ctx (ids (- 1 i)) cid))
                     (recur (rest remaining) expected)))
              (h/with-context [fresh]
                (h/replay! ctx fresh)
                (and (= model (metadata-view (o/get-concepts fresh)))
                     (every? (fn [[[oid u] c]]
                               (= (o/get-concept-by-uri ctx oid u)
                                  (o/get-concept fresh oid (:concept-id c)))) model)
                     (= [] (o/get-concepts (assoc ctx :tenant-id (random-uuid)))))))))))))

(def retry-operation-gen
  (gen/tuple (gen/choose 0 1) (gen/choose 0 3) (gen/elements ["Alpha" "Beta" "Gamma"])
             (gen/elements [:absent :fresh :stale]) gen/boolean))

(deftest generated-retries-are-graph-scoped-and-reject-conflicting-payloads
  (check! "retry-scopes"
    (prop/for-all [ops (gen/vector retry-operation-gen 1 25)]
      (h/with-context [ctx]
        (let [ids [(h/graph ctx) (h/graph ctx)]
              concepts (mapv #(h/add! ctx % "urn:shared") ids)
              keys (vec (repeatedly 4 random-uuid))]
          (loop [remaining (concat [[0 0 "Alpha" :fresh false] [1 0 "Beta" :fresh false]] ops)
                 receipts {} revisions [2 2] labels ["urn:shared" "urn:shared"]]
            (if-let [[i slot label mode replay?] (first remaining)]
              (let [oid (ids i) prior (receipts [i slot])
                    request (if (and replay? prior) (:request prior)
                                (cond-> {:changes {:label label} :request-id (keys slot)}
                                  (= mode :fresh) (assoc :expected-revision (revisions i))
                                  (= mode :stale) (assoc :expected-revision 0)))
                    retry? (and prior (= request (:request prior)))
                    accepted? (and (nil? prior) (not= 0 (:expected-revision request)))
                    before (h/graph-events ctx)
                    result (o/update-concept! ctx oid (:concept-id (concepts i)) request)
                    next-revisions (if accepted? (update revisions i inc) revisions)
                    next-labels (if accepted? (assoc labels i (get-in request [:changes :label])) labels)
                    correct? (cond retry? (= (:result prior) result)
                                   accepted? (and (not (h/anomaly? result)) (= (next-revisions i) (:revision result)))
                                   :else (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))]
                (and correct?
                     (= next-revisions (mapv #(o/get-revision ctx %) ids))
                     (= next-labels (mapv #(-> (o/get-concept ctx (ids %) (:concept-id (concepts %))) :label) [0 1]))
                     (= (if accepted? (inc (count before)) (count before)) (count (h/graph-events ctx)))
                     (or accepted? (= before (h/graph-events ctx)))
                     (recur (rest remaining)
                            (if accepted? (assoc receipts [i slot] {:request request :result result}) receipts)
                            next-revisions next-labels)))
              true)))))))

(defn logical-edge-keys [edges policies]
  (into #{} (mapcat (fn [[so su p to tu :as edge]]
                     (let [policy (policies so)]
                       (cond-> [edge]
                         (or (:symmetric? policy) (:inverse policy))
                         (conj [to tu (or (:inverse policy) p) so su]))))) edges))

(deftest generated-cross-ontology-links-match-logical-view-model
  (check! "cross-ontology-views"
    (prop/for-all [flags (gen/vector (gen/elements [{} {:symmetric? true} {:inverse "urn:inverse"}]) 2)
                  ops (gen/vector (gen/tuple (gen/choose 0 5) (gen/choose 1 5)) 1 18)]
      (h/with-context [ctx]
        (let [ids [(h/graph ctx) (h/graph ctx)]
              policies (zipmap ids flags)
              nodes (vec (for [oid ids n (range 3)] [oid (uri n)]))
              concepts (into {} (for [[oid u :as key] nodes] [key (h/add! ctx oid u)]))]
          (doseq [oid ids]
            (h/ok! (o/register-predicate! ctx oid {:predicate "urn:link" :policy (assoc (policies oid) :cross-ontology? true)})))
          (loop [remaining ops edges #{}]
            (if-let [[from offset] (first remaining)]
              (let [[so su] (nodes from) [to tu] (nodes (mod (+ from offset) 6))
                    edge [so su "urn:link" to tu]
                    duplicate? (contains? (logical-edge-keys edges policies) edge)
                    before (h/graph-events ctx)
                    result (o/create-relationship! ctx {:source-ontology-id so :source-uri su
                                                        :target-ontology-id to :target-uri tu :predicate "urn:link"})
                    next-edges (if duplicate? edges (conj edges edge))
                    logical (logical-edge-keys next-edges policies)]
                (and (if duplicate? (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
                                        (= before (h/graph-events ctx)))
                         (not (h/anomaly? result)))
                     (every? (fn [oid]
                               (let [expected (set (filter (fn [[a _ _ b _]] (or (= a oid) (= b oid))) logical))
                                     actual (mapv (juxt :source-ontology-id :source-uri :predicate :target-ontology-id :target-uri)
                                                  (o/get-relationships ctx oid))]
                                 (and (= expected (set actual)) (= (count expected) (count actual))))) ids)
                     (every? (fn [[[oid u] c]]
                               (let [expected (reduce (fn [m [so su p to tu]]
                                                        (if (= [oid u] [so su]) (update m p (fnil conj #{}) [to tu]) m)) {} logical)
                                     point (o/get-concept ctx oid (:concept-id c))]
                                 (and (= expected (:relations point))
                                      (= point (o/get-concept-by-uri ctx oid u))))) concepts)
                     (recur (rest remaining) next-edges)))
              true)))))))
