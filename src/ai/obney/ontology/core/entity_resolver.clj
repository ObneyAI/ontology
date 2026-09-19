(ns ai.obney.ontology.core.entity-resolver
  "Entity Resolver - Deduplicates concepts across sources using semantic matching.

   Deterministic identity resolution across source contributions:

   1. Type-based blocking - Group concepts by entity-type to reduce O(N²) → O((N/k)²)
   2. Exact matching - Normalized label comparison within blocks
   3. Semantic matching - Comparable-model embedding similarity above threshold
   4. Union-Find - Group matches into equivalence classes
   5. Canonical URI selection - existing > shortest > alphabetical

   Key differences from simple dedup:
   - Only matches concepts from DIFFERENT sources (same source = different entities)
   - Uses type-blocking for performance (occupations only match occupations)
   - Produces owl:sameAs alignment triples for RDF compatibility"
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; =============================================================================
;; Label Normalization (matches Python normalize_label)
;; =============================================================================

(defn normalize-label
  "Normalize a label for exact matching.
   Matches Python: label.lower().strip().replace('-', ' ').replace('_', ' ')"
  [label]
  (when label
    (-> label
        str/lower-case
        str/trim
        (str/replace #"[-_]" " ")
        (str/replace #"\s+" " "))))

;; =============================================================================
;; Type-Based Blocking
;; =============================================================================

(defn group-by-entity-type
  "Group concepts by entity-type for blocking optimization.
   Returns: {entity-type -> [concepts...]}"
  [concepts]
  (group-by :entity-type concepts))

(defn concepts-from-different-sources?
  "Check if two concepts come from different sources.
   Only match across sources - same source means different entities."
  [concept1 concept2]
  (not= (:source-id concept1) (:source-id concept2)))

;; =============================================================================
;; Exact Matching
;; =============================================================================

(defn find-exact-matches-in-block
  "Find exact matches within a block of same-type concepts.
   Only matches concepts from different sources.

   Returns: [{:source1-uri :source2-uri :similarity-score 1.0 :match-type 'exact'}]"
  [concepts]
  (let [normalized-labels
        (fn [concept]
          (->> (cons (:label concept) (:alt-labels concept))
               (keep normalize-label)
               (remove str/blank?)
               set))]
    (->> (for [c1 concepts
               c2 concepts
               :when (and (concepts-from-different-sources? c1 c2)
                          (neg? (compare (:uri c1) (:uri c2)))
                          (seq (set/intersection (normalized-labels c1)
                                                 (normalized-labels c2))))]
           {:source1-uri (:uri c1)
            :source2-uri (:uri c2)
            :similarity-score 1.0
            :match-type "exact"})
         vec)))

;; =============================================================================
;; Semantic Matching
;; =============================================================================

(defn cosine-similarity
  "Compute cosine similarity between two embedding vectors."
  [v1 v2]
  (when (and (sequential? v1) (sequential? v2)
             (= (count v1) (count v2)) (pos? (count v1))
             (every? #(and (number? %) (Double/isFinite (double %))) (concat v1 v2)))
    (let [scale1 (reduce max (map #(Math/abs (double %)) v1))
          scale2 (reduce max (map #(Math/abs (double %)) v2))]
      (when (and (pos? scale1) (pos? scale2))
        (let [a (map #(/ (double %) scale1) v1)
              b (map #(/ (double %) scale2) v2)
              dot (reduce + (map * a b))
              norms (* (Math/sqrt (reduce + (map #(* % %) a)))
                       (Math/sqrt (reduce + (map #(* % %) b))))]
          (max -1.0 (min 1.0 (/ dot norms))))))))

(defn find-semantic-matches-in-block
  "Find semantic matches within a block using embeddings.
   Only matches concepts from different sources and above threshold.

   Args:
     concepts - Vector of concepts with :embedding field
     threshold - Minimum similarity score (default 0.85)
     already-matched - Set of URI pairs already matched (exact matches)

   Returns: [{:source1-uri :source2-uri :similarity-score :match-type 'semantic'}]"
  [concepts threshold already-matched]
  (let [concepts-with-embeddings (filter :embedding concepts)]
    (->> (for [c1 concepts-with-embeddings
               c2 concepts-with-embeddings
               :when (and (concepts-from-different-sources? c1 c2)
                          (some? (:embedding-model c1))
                          (= (:embedding-model c1) (:embedding-model c2))
                          (neg? (compare (:uri c1) (:uri c2))))]
           (let [pair-key #{(:uri c1) (:uri c2)}]
             (when-not (contains? already-matched pair-key)
               (let [sim (cosine-similarity (:embedding c1) (:embedding c2))]
                 (when (and (number? sim) (>= sim threshold))
                   {:source1-uri (:uri c1)
                    :source2-uri (:uri c2)
                    :similarity-score sim
                    :match-type "semantic"})))))
         (remove nil?)
         vec)))

;; =============================================================================
;; Union-Find for Equivalence Classes
;; =============================================================================

(defn make-union-find
  "Create a Union-Find data structure.
   Returns atom with {:parent {x -> parent} :rank {x -> rank}}"
  []
  (atom {:parent {} :rank {}}))

(defn find-root
  "Find root of element with path compression."
  [uf x]
  (let [{:keys [parent]} @uf]
    (if-let [p (get parent x)]
      (if (= p x)
        x
        (let [root (find-root uf p)]
          ;; Path compression
          (swap! uf assoc-in [:parent x] root)
          root))
      (do
        ;; Initialize element as its own parent
        (swap! uf assoc-in [:parent x] x)
        (swap! uf assoc-in [:rank x] 0)
        x))))

(defn union!
  "Union two elements by rank."
  [uf x y]
  (let [root-x (find-root uf x)
        root-y (find-root uf y)]
    (when (not= root-x root-y)
      (let [{:keys [rank]} @uf
            rank-x (get rank root-x 0)
            rank-y (get rank root-y 0)]
        (cond
          (< rank-x rank-y)
          (swap! uf assoc-in [:parent root-x] root-y)

          (> rank-x rank-y)
          (swap! uf assoc-in [:parent root-y] root-x)

          :else
          (do
            (swap! uf assoc-in [:parent root-y] root-x)
            (swap! uf update-in [:rank root-x] inc)))))))

(defn group-matched-uris
  "Group matched URIs into equivalence classes using Union-Find.
   Returns: [[uri1 uri2 uri3] [uri4 uri5] ...]"
  [matches]
  (let [uf (make-union-find)]
    ;; Union all matched pairs
    (doseq [{:keys [source1-uri source2-uri]} matches]
      (find-root uf source1-uri)  ;; Initialize if needed
      (find-root uf source2-uri)
      (union! uf source1-uri source2-uri))

    ;; Group by root
    (let [all-uris (set (mapcat (juxt :source1-uri :source2-uri) matches))
          by-root (group-by #(find-root uf %) all-uris)]
      (vec (vals by-root)))))

;; =============================================================================
;; Canonical URI Selection
;; =============================================================================

(defn select-canonical-uri
  "Select canonical URI for an equivalence class.
   Priority: existing ontology URI > shortest URI > alphabetically first

   Args:
     uris - Collection of equivalent URIs
     existing-uris - Set of URIs from existing ontology (for incremental mode)"
  [uris existing-uris]
  (let [uris-vec (vec uris)
        ;; Check for existing ontology URIs
        existing-in-group (filter #(contains? existing-uris %) uris-vec)]
    (if (seq existing-in-group)
      ;; Prefer existing - take shortest existing
      (first (sort-by (juxt count identity) existing-in-group))
      ;; No existing - take shortest, then alphabetical
      (first (sort-by (juxt count identity) uris-vec)))))

(defn compute-canonical-uris
  "Compute canonical URI mapping for all equivalence classes.
   Returns: {original-uri -> canonical-uri}

   Args:
     uri-groups - [[uri1 uri2] [uri3 uri4 uri5] ...]
     existing-uris - Set of existing ontology URIs (optional)"
  [uri-groups & {:keys [existing-uris] :or {existing-uris #{}}}]
  (->> uri-groups
       (mapcat (fn [group]
                 (let [canonical (select-canonical-uri group existing-uris)]
                   (map (fn [uri] [uri canonical]) group))))
       (into {})))

;; =============================================================================
;; OWL Alignment Triple Generation
;; =============================================================================

(defn generate-owl-sameAs-triples
  "Generate owl:sameAs triples from canonical mapping.
   Returns: [[original-uri 'owl:sameAs' canonical-uri] ...]"
  [canonical-map]
  (->> canonical-map
       (filter (fn [[orig canonical]] (not= orig canonical)))
       (map (fn [[orig canonical]]
              [orig "owl:sameAs" canonical]))
       vec))

;; =============================================================================
;; Main Resolution Algorithm
;; =============================================================================

(defn resolve-within-block
  "Resolve entities within a single type block.
   Combines exact and semantic matching."
  [concepts similarity-threshold]
  (let [exact-matches (find-exact-matches-in-block concepts)
        exact-pairs (set (map (fn [{:keys [source1-uri source2-uri]}]
                                #{source1-uri source2-uri})
                              exact-matches))
        semantic-matches (find-semantic-matches-in-block
                           concepts
                           similarity-threshold
                           exact-pairs)]
    (concat exact-matches semantic-matches)))

(defn resolve-within-batch
  "Match entities across sources using type-based blocking.

   Algorithm (matches Python exactly):
   1. Group concepts by entity-type (blocking optimization)
   2. For each block:
      a. Find exact matches (normalized label comparison)
      b. Find semantic matches (embedding similarity > threshold)
      c. Only match concepts from DIFFERENT sources
   3. Build canonical URI mappings using Union-Find
      - Canonical = existing > shortest > alphabetical
   4. Generate owl:sameAs alignment triples

   Args:
     concepts - Vector of concept maps with :uri :label :entity-type :source-id :embedding
     config - {:similarity-threshold 0.85, :emit-owl-sameAs? true, :existing-uris #{}}

   Returns:
     {:matches [{:source1-uri :source2-uri :similarity-score :match-type}]
      :canonical-map {uri -> canonical-uri}
      :alignment-triples [[s p o] ...]
      :unmatched [concepts...]
      :exact-matches count
      :semantic-matches count}"
  [concepts {:keys [similarity-threshold emit-owl-sameAs? existing-uris]
             :or {similarity-threshold 0.85
                  emit-owl-sameAs? true
                  existing-uris #{}}}]
  (let [by-type (group-by-entity-type concepts)
        exact-matches (vec (mapcat find-exact-matches-in-block (vals by-type)))
        exact-pairs (set (map (fn [{:keys [source1-uri source2-uri]}]
                                #{source1-uri source2-uri})
                              exact-matches))
        semantic-matches (->> (vals by-type)
                              (mapcat #(find-semantic-matches-in-block
                                        % similarity-threshold exact-pairs))
                              vec)
        all-matches (into exact-matches semantic-matches)

        ;; Step 3: Canonical URI mapping (Union-Find)
        uri-groups (group-matched-uris all-matches)
        canonical-map (compute-canonical-uris uri-groups :existing-uris existing-uris)

        ;; Step 4: Alignment triples
        alignment-triples (when emit-owl-sameAs?
                            (generate-owl-sameAs-triples canonical-map))

        ;; Stats
        matched-uris (set (keys canonical-map))
        unmatched (->> concepts
                       (remove #(contains? matched-uris (:uri %)))
                       vec)
        exact-count (count (filter #(= "exact" (:match-type %)) all-matches))
        semantic-count (count (filter #(= "semantic" (:match-type %)) all-matches))]

    {:matches all-matches
     :canonical-map canonical-map
     :alignment-triples (or alignment-triples [])
     :unmatched unmatched
     :exact-matches exact-count
     :semantic-matches semantic-count}))

(defn resolve-incremental
  "Resolve new concepts against existing ontology.
   Prefers existing URIs as canonical.

   Args:
     new-concepts - New concepts to resolve
     existing-labels - Labels from existing ontology (for matching)
     existing-uris - URIs from existing ontology
     config - Same as resolve-within-batch

   Returns: Same as resolve-within-batch"
  [new-concepts existing-concepts existing-uris config]
  (let [existing-concepts (mapv #(assoc % :source-id ::existing) existing-concepts)
        all-concepts (concat new-concepts existing-concepts)]
    (resolve-within-batch all-concepts
                          (assoc config :existing-uris existing-uris))))
