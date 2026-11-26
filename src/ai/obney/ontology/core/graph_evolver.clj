(ns ai.obney.ontology.core.graph-evolver
  "Graph Evolver - Merges extracted concepts and tracks schema extensions.

   Pure graph transformations and deterministic snapshot serialization:

   1. Apply canonical URI mappings to all concepts
   2. Detect schema extensions (new classes, properties)
   3. Merge triples into unified graph
   4. Track evolution history

   Key operations:
   - merge-sources: Apply canonical mapping, combine triples
   - detect-schema-extensions: Find new TBox elements
   - apply-canonical-mapping: Rewrite URIs in concepts/relationships"
  (:require [clojure.string :as str]
            [ai.obney.ontology.core.source-registry :as source-registry]
            [clojure.set :as set]))

;; =============================================================================
;; Canonical URI Application
;; =============================================================================

(defn apply-canonical-uri
  "Apply canonical mapping to a single URI.
   Returns the canonical URI or the original if not mapped."
  [canonical-map uri]
  (get canonical-map uri uri))

(defn apply-canonical-to-concept
  "Apply canonical URI mapping to a concept.
   Updates the concept's URI and any relationship targets."
  [canonical-map concept]
  (let [canonical-uri (apply-canonical-uri canonical-map (:uri concept))]
    (-> concept
        (assoc :uri canonical-uri
               :original-uri (:uri concept))
        (update :relationships
                (fn [rels]
                  (mapv (fn [rel]
                          (update rel :object #(apply-canonical-uri canonical-map %)))
                        (or rels [])))))))

(defn apply-canonical-to-relationship
  "Apply canonical URI mapping to a relationship triple."
  [canonical-map {:keys [subject predicate object] :as rel}]
  (assoc rel
         :subject (apply-canonical-uri canonical-map subject)
         :object (apply-canonical-uri canonical-map object)))

(defn apply-canonical-mappings
  "Apply canonical URI mappings to all concepts and relationships.

   Args:
     concepts - Vector of concept maps
     relationships - Vector of relationship maps
     canonical-map - {original-uri -> canonical-uri}

   Returns:
     {:concepts [...] :relationships [...]}"
  [concepts relationships canonical-map]
  {:concepts (mapv #(apply-canonical-to-concept canonical-map %) concepts)
   :relationships (mapv #(apply-canonical-to-relationship canonical-map %) relationships)})

;; =============================================================================
;; Deduplication
;; =============================================================================

(defn deduplicate-concepts
  "Deduplicate concepts by URI, merging properties from duplicates.
   When concepts share a URI (after canonical mapping), merge:
   - alt-labels: Union of all
   - definition: Prefer longest (most detailed)
   - confidence: Take highest
   - relationships: Union of all"
  [concepts]
  (let [by-uri (group-by :uri concepts)]
    (->> by-uri
         (map (fn [[_uri cs]]
                (if (= 1 (count cs))
                  (first cs)
                  (reduce (fn [merged c]
                            (-> merged
                                (update :alt-labels #(vec (distinct (concat % (:alt-labels c)))))
                                (update :definition
                                        (fn [d1]
                                          (let [d2 (:definition c)]
                                            (if (> (count (or d2 "")) (count (or d1 "")))
                                              d2 d1))))
                                (update :confidence
                                        (fn [c1]
                                          (max (or c1 0) (or (:confidence c) 0))))
                                (update :relationships
                                        (fn [r1]
                                          (vec (distinct (concat r1 (:relationships c))))))))
                          (first cs)
                          (rest cs)))))
         vec)))

(defn deduplicate-relationships
  "Deduplicate relationships by [subject predicate object] triple."
  [relationships]
  (->> relationships
       (group-by (juxt :subject :predicate :object))
       vals
       (map (fn [rels]
              (apply max-key #(or (:confidence %) 0) rels)))
       vec))

;; =============================================================================
;; Schema Extension Detection
;; =============================================================================

(defn extract-classes-from-concepts
  "Extract unique entity types as potential classes."
  [concepts]
  (->> concepts
       (map :entity-type)
       (remove nil?)
       distinct
       (map (fn [etype]
              {:uri (str "class:" (str/replace etype #"\s+" ""))
               :label etype}))
       vec))

(defn extract-properties-from-relationships
  "Extract unique predicates as potential properties."
  [relationships]
  (->> relationships
       (map :predicate)
       distinct
       (map (fn [pred]
              {:uri pred
               :label (last (str/split pred #"[:#/]"))}))
       vec))

(defn detect-schema-extensions
  "Detect new schema elements (classes, properties) from extracted data.

   Args:
     concepts - Extracted concepts
     relationships - Extracted relationships
     existing-schema - {:classes #{uri} :properties #{uri}}

   Returns:
     {:new-classes [{:uri :label}]
      :new-object-properties [{:uri :label}]
      :new-datatype-properties [{:uri :label}]}"
  [concepts relationships existing-schema]
  (let [{:keys [classes properties]
         :or {classes #{} properties #{}}} existing-schema

        extracted-classes (extract-classes-from-concepts concepts)
        extracted-properties (extract-properties-from-relationships relationships)

        new-classes (remove #(contains? classes (:uri %)) extracted-classes)
        new-properties (remove #(contains? properties (:uri %)) extracted-properties)]

    {:new-classes (vec new-classes)
     :new-object-properties (vec new-properties)
     :new-datatype-properties []}))

;; =============================================================================
;; Graph Merging
;; =============================================================================

(defn merge-into-graph
  "Merge new concepts and relationships into existing graph.

   Args:
     existing-graph - {:concepts {uri -> concept} :relationships [triple...]}
     new-concepts - Vector of concepts (after canonical mapping)
     new-relationships - Vector of relationships (after canonical mapping)

   Returns:
     {:concepts {uri -> concept}
      :relationships [triple...]
      :concepts-added count
      :concepts-merged count}"
  [{:keys [concepts relationships] :as existing-graph
    :or {concepts {} relationships []}}
   new-concepts
   new-relationships]
  (let [existing-uris (set (keys concepts))
        new-uris (set (map :uri new-concepts))

        added-uris (set/difference new-uris existing-uris)
        merged-uris (set/intersection new-uris existing-uris)

        updated-concepts (reduce
                           (fn [m c]
                             (if (contains? m (:uri c))
                               (update m (:uri c)
                                       (fn [existing]
                                         (-> existing
                                             (update :alt-labels into (:alt-labels c))
                                             (update :relationships into (:relationships c)))))
                               (assoc m (:uri c) c)))
                           concepts
                           new-concepts)

        all-relationships (deduplicate-relationships
                            (concat relationships new-relationships))]

    {:concepts updated-concepts
     :relationships all-relationships
     :concepts-added (count added-uris)
     :concepts-merged (count merged-uris)}))

;; =============================================================================
;; Main Evolution Operation
;; =============================================================================

;; =============================================================================
;; TTL Snapshot Generation
;; =============================================================================

(def ^:private rdf-prefixes
  {"rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
   "owl" "http://www.w3.org/2002/07/owl#"
   "skos" "http://www.w3.org/2004/02/skos/core#"
   "xsd" "http://www.w3.org/2001/XMLSchema#"})

(defn- escape-ttl-string [s]
  (-> (str (or s ""))
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")
      (str/replace "\b" "\\b")
      (str/replace "\f" "\\f")))

(defn- uri-term [base-uri value]
  (let [value (str value)
        [prefix local] (str/split value #":" 2)
        uri (cond
              (= value "a") (str (rdf-prefixes "rdf") "type")
              (rdf-prefixes prefix) (str (rdf-prefixes prefix) local)
              (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" value) value
              :else (str base-uri value))]
    (when (re-find #"[<>\s\x00-\x20]" uri)
      (throw (ex-info "Invalid RDF IRI" {:uri value})))
    (str "<" uri ">")))

(defn- literal-term [value]
  (str "\"" (escape-ttl-string value) "\""))

(defn- triple-line [base-uri subject predicate object-term]
  (str (uri-term base-uri subject) " " (uri-term base-uri predicate) " " object-term " ."))

(defn concept->ttl-triples
  "Serialize a concept as one RDF statement per line."
  [concept base-uri]
  (when (and (:uri concept) (:label concept))
    (let [uri (:uri concept)
          literal #(triple-line base-uri uri %1 (str (literal-term %2) "@en"))]
      (concat [(triple-line base-uri uri "rdf:type" (uri-term base-uri "skos:Concept"))
               (literal "skos:prefLabel" (:label concept))]
              (when (:definition concept) [(literal "skos:definition" (:definition concept))])
              (when (:entity-type concept)
                [(triple-line base-uri uri "entityType" (literal-term (:entity-type concept)))])
              (map #(literal "skos:altLabel" %) (sort (:alt-labels concept)))))))

(defn relationship->ttl-triple [{:keys [subject predicate object]} base-uri]
  (triple-line base-uri subject predicate (uri-term base-uri object)))

(defn tbox->ttl-lines
  "Serialize OWL classes and properties without requiring optional fields."
  [{:keys [classes object-properties datatype-properties]} base-uri]
  (mapcat
   (fn [[kind entities]]
     (mapcat
      (fn [{:keys [uri label description domain range datatype]}]
        (concat [(triple-line base-uri uri "rdf:type" (uri-term base-uri kind))]
                (when label [(triple-line base-uri uri "rdfs:label" (str (literal-term label) "@en"))])
                (when description [(triple-line base-uri uri "rdfs:comment" (str (literal-term description) "@en"))])
                (when domain [(triple-line base-uri uri "rdfs:domain" (uri-term base-uri domain))])
                (when-let [r (if (= kind "owl:DatatypeProperty") (or datatype "xsd:string") range)]
                  [(triple-line base-uri uri "rdfs:range" (uri-term base-uri r))])))
      entities))
   [["owl:Class" classes] ["owl:ObjectProperty" object-properties] ["owl:DatatypeProperty" datatype-properties]]))

(defn abox->ttl-lines
  "Serialize OWL individuals and all supplied literal properties."
  [individuals base-uri]
  (mapcat (fn [{:keys [uri type label properties]}]
            (concat [(triple-line base-uri uri "rdf:type" (uri-term base-uri "owl:NamedIndividual"))]
                    (when type [(triple-line base-uri uri "rdf:type" (uri-term base-uri type))])
                    (when label [(triple-line base-uri uri "rdfs:label" (str (literal-term label) "@en"))])
                    (map (fn [[k v]] (triple-line base-uri uri (if (keyword? k) (name k) k) (literal-term v))) properties)))
          individuals))

(defn- rdf-triple->line [{:keys [subject predicate object-uri literal language datatype]} base-uri]
  (triple-line base-uri subject predicate
               (if object-uri (uri-term base-uri object-uri)
                   (str (literal-term literal)
                        (cond language (str "@" language)
                              datatype (str "^^" (uri-term base-uri datatype)))))))

(defn generate-owl-ttl-snapshot
  "Create deterministic Turtle (N-Triples subset), exact statement count and SHA-256.
   Preserves supplied raw :rdf-triples, including typed and language literals."
  [{:keys [tbox abox concepts relationships rdf-triples]}
   {:keys [base-uri include-metadata?]
    :or {base-uri "http://ontology.local/" include-metadata? true}}]
  (let [lines (->> (concat
                   (when include-metadata?
                     [(triple-line base-uri base-uri "rdf:type" (uri-term base-uri "owl:Ontology"))])
                   (tbox->ttl-lines tbox base-uri)
                   (abox->ttl-lines abox base-uri)
                   (mapcat #(concept->ttl-triples % base-uri) (vals concepts))
                   (map #(relationship->ttl-triple % base-uri) relationships)
                   (map #(rdf-triple->line % base-uri) rdf-triples))
                  distinct sort vec)
        ttl-string (str/join "\n" lines)]
    {:ttl-string ttl-string
     :triple-count (count lines)
     :checksum (source-registry/sha256 ttl-string)}))

(defn generate-ttl-snapshot [graph config]
  (generate-owl-ttl-snapshot graph config))
