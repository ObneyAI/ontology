(ns ai.obney.ontology.property-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.algorithms :as a]
            [ai.obney.ontology.support :as h]))

(def seed (Long/parseLong (or (System/getenv "ONTOLOGY_TEST_SEED") "20260918")))
(def trials (Long/parseLong (or (System/getenv "ONTOLOGY_TEST_TRIALS") "60")))
(defn check! [label property]
  (let [result (tc/quick-check trials property :seed seed :max-size 35)]
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
                         :rename (o/update-concept! ctx oid (or (:id c) (random-uuid)) {:label label})
                         :parents (o/update-concept! ctx oid (or (:id c) (random-uuid)) {:broader [parent]})
                         (:import :bad-import) (o/import-contribution! ctx input)
                         :retry (when prior (o/import-contribution! ctx (first prior))))
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
              (= expected (a/select-canonical-uri names existing)
                 (a/select-canonical-uri (reverse names) existing))))))

(deftest literal-export-roundtrip-has-exact-statements-and-checksum
  (check! "literal-roundtrip"
          (prop/for-all [pieces (gen/vector (gen/elements ["a" "λ" "🌳" "\n" "\r" "\t" "\b" "\f" "\\" "\"" " " "<>"]) 0 25)]
            (h/with-context [ctx]
              (let [oid (h/graph ctx) literal (apply str pieces)
                    input (assoc (h/request ctx oid []) :rdf-triples [{:subject "urn:s" :predicate "urn:p" :literal literal}])]
                (h/ok! (o/import-contribution! ctx input))
                (let [snapshot (o/export-snapshot ctx oid)
                      triples (a/parse-ntriples (:ttl-output snapshot))]
                  (and (some #(= literal (:literal %)) triples)
                       (= (:triple-count snapshot) (count triples))
                       (= (:checksum snapshot) (a/sha256 (:ttl-output snapshot))))))))))

(deftest disjoint-contributions-have-order-independent-rdf
  (check! "disjoint-import-order"
          (prop/for-all [labels (gen/vector-distinct gen/string-alphanumeric {:min-elements 1 :max-elements 8})]
            (let [records (mapv (fn [i label] (assoc (h/concept (uri i)) :label (str "Label " label))) (range) labels)
                  snapshots (mapv (fn [cs]
                                    (h/with-context [ctx]
                                      (let [oid (h/graph ctx)]
                                        (doseq [c cs] (h/ok! (o/import-contribution! ctx (h/request ctx oid [c]))))
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
                    _ (h/ok! (o/import-contribution! ctx (assoc (h/request ctx oid []) :source {:source-id sid :content-hash hash})))
                    expected (conj expected [sid hash])
                    actual (mapv (juxt :source-id :content-hash) (o/get-sources ctx oid))]
                (and (= expected (set actual)) (= (count actual) (count expected))
                     (empty? (o/get-sources foreign oid))
                     (recur (rest remaining) expected)))
              true)))))))

(deftest generated-resolution-scores-are-bounded
  (check! "resolution-scores"
    (prop/for-all [angle (gen/choose 0 1000) scale (gen/elements [1.0e-200 1.0 1.0e200])]
      (let [theta (* Math/PI (/ angle 2000.0))
            original {:uri "urn:original" :label "Original" :source-id "a" :entity-type "T" :embedding-model "m" :embedding [scale 0.0]}
            incoming {:uri "urn:new" :label "New" :source-id "b" :entity-type "T" :embedding-model "m"
                      :embedding [(* scale (Math/cos theta)) (* scale (Math/sin theta))]}
            result (a/resolve-incremental [incoming] [original] #{"urn:original"} {:similarity-threshold 0.0})]
        (and (seq (:matches result))
             (every? #(and (Double/isFinite (double (:similarity-score %))) (<= 0 (:similarity-score %) 1)) (:matches result))
             (= "urn:original" (get (:canonical-map result) "urn:new")))))))
