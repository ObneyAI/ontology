(ns ai.obney.ontology.core.rdf
  "Deterministic import of the supported N-Triples subset."
  (:require [clojure.string :as str]
            [ai.obney.ontology.core.errors :as errors]))

(def ^:private rdf-type-uri
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(def ^:private rdf-label-uris
  #{"http://www.w3.org/2000/01/rdf-schema#label"
    "http://www.w3.org/2004/02/skos/core#prefLabel"})

(def ^:private rdf-description-uris
  #{"http://www.w3.org/2000/01/rdf-schema#comment"
    "http://www.w3.org/2004/02/skos/core#definition"})

(def ^:private rdf-predicate-aliases
  {"http://www.w3.org/2004/02/skos/core#broader" "skos:broader"
   "http://www.w3.org/2004/02/skos/core#narrower" "skos:narrower"
   "http://www.w3.org/2004/02/skos/core#related" "skos:related"
   "http://www.w3.org/2000/01/rdf-schema#subClassOf" "rdfs:subClassOf"
   "http://www.w3.org/2002/07/owl#sameAs" "owl:sameAs"})

(def ^:private schema-predicates
  #{rdf-type-uri "http://www.w3.org/2000/01/rdf-schema#domain"
    "http://www.w3.org/2000/01/rdf-schema#range"})

(defn- rdf-uri-label [uri]
  (let [tail (last (str/split uri #"[/#:]"))]
    (if (str/blank? tail) uri tail)))

(defn- unescape-literal [s]
  (str/replace s #"\\(?:u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|.)"
               (fn [escape]
                 (case (subs escape 1 2)
                   "t" "\t" "b" "\b" "n" "\n" "r" "\r" "f" "\f"
                   "\"" "\"" "'" "'" "\\" "\\"
                   ("u" "U") (let [_ (errors/require! (re-matches #"(?:[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8})" (subs escape 2))
                                                   "Invalid Unicode escape")
                                     code-point (Long/parseLong (subs escape 2) 16)]
                               (errors/require! (<= 0 code-point Character/MAX_CODE_POINT)
                                                "Invalid Unicode code point")
                               (String. (Character/toChars (int code-point))))
                   (errors/fail! :cognitect.anomalies/incorrect "Unsupported RDF literal escape")))))

(defn- parse-ntriples-line [line-number line]
  (when-not (or (str/blank? line) (str/starts-with? (str/trim line) "#"))
    (if-let [[_ subject predicate object-uri literal language datatype]
             (re-matches
              #"\s*<([^>]+)>\s+<([^>]+)>\s+(?:<([^>]+)>|\"((?:\\.|[^\"])*)\"(?:@([A-Za-z0-9-]+)|\^\^<([^>]+)>)?)\s*\.\s*"
              line)]
      {:subject subject
       :predicate predicate
       :object-uri object-uri
       :language language
       :datatype datatype
       :literal (some-> literal unescape-literal)}
      (throw (ex-info "Malformed N-Triples statement"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :cognitect.anomalies/message "Malformed N-Triples statement"
                       :line line-number :statement line})))))

(defn- valid-unicode? [s]
  (loop [i 0]
    (if (= i (count s)) true
        (let [c (.charAt ^String s i)]
          (cond
            (Character/isHighSurrogate c)
            (and (< (inc i) (count s)) (Character/isLowSurrogate (.charAt ^String s (inc i))) (recur (+ i 2)))
            (Character/isLowSurrogate c) false
            :else (recur (inc i)))))))

(defn- valid-uri? [s]
  (and (string? s) (not (str/blank? s)) (valid-unicode? s)
       (not (re-find #"[\x00-\x20<>\"{}|^`\\]" s))
       (try (.isAbsolute (java.net.URI. s)) (catch Exception _ false))))

(defn validate-triples! [triples]
  (doseq [{:keys [subject predicate object-uri literal language datatype] :as triple} triples]
    (when-not (and (map? triple) (valid-uri? subject) (valid-uri? predicate)
                   (if (some? object-uri)
                     (and (valid-uri? object-uri) (nil? literal) (nil? language) (nil? datatype))
                     (and (string? literal) (valid-unicode? literal)
                          (not (and language datatype))
                          (or (nil? language) (and (string? language) (re-matches #"[a-zA-Z]+(?:-[a-zA-Z0-9]+)*" language)))
                          (or (nil? datatype) (valid-uri? datatype)))))
      (throw (ex-info "Invalid RDF triple"
                       {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                        :cognitect.anomalies/message "Invalid RDF triple" :triple triple}))))
  triples)

(defn parse-ntriples
  "Parse the supported URI-subject N-Triples subset; reject unsupported statements."
  [content]
  (when-not (string? content)
    (errors/fail! :cognitect.anomalies/incorrect "N-Triples content must be a string"))
  (->> (str/split-lines content)
       (map-indexed #(parse-ntriples-line (inc %1) %2))
       (remove nil?) vec validate-triples!))

(defn extract-concepts-from-rdf
  "Deterministically import an RDF graph encoded as N-Triples.

   URI subjects become concepts. URI objects also become concepts so every
   imported edge has a resolvable endpoint. rdfs:label/skos:prefLabel and
   rdfs:comment/skos:definition populate descriptive fields; SKOS graph
   predicates are normalized to the relationship vocabulary used by the ontology graph."
  [source-id content _config]
  (when-not (string? content)
    (errors/fail! :cognitect.anomalies/incorrect "RDF import requires N-Triples string content"))
  (let [triples (parse-ntriples content)
        uri-objects (keep :object-uri triples)
        uris (into #{} (concat (map :subject triples) uri-objects))
        labels (into {} (keep (fn [{:keys [subject predicate literal]}]
                                (when (and literal (contains? rdf-label-uris predicate))
                                  [subject literal])))
                     triples)
        descriptions (into {} (keep (fn [{:keys [subject predicate literal]}]
                                      (when (and literal
                                                 (contains? rdf-description-uris predicate))
                                        [subject literal])))
                           triples)
        types (into {} (keep (fn [{:keys [subject predicate object-uri]}]
                               (when (and (= rdf-type-uri predicate) object-uri)
                                 [subject (rdf-uri-label object-uri)])))
                    triples)
        concepts (mapv (fn [uri]
                         {:uri uri
                          :label (get labels uri (rdf-uri-label uri))
                          :definition (get descriptions uri "Imported RDF concept")
                          :entity-type (get types uri "Concept")
                          :source-id source-id
                          :confidence 1.0})
                       (sort uris))
        relationships (->> triples
                           (keep (fn [{:keys [subject predicate object-uri]}]
                                   (when (and object-uri (not (schema-predicates predicate)))
                                     {:subject subject
                                      :predicate (get rdf-predicate-aliases predicate predicate)
                                      :object object-uri
                                      :confidence 1.0})))
                           vec)]
    {:rdf-triples triples
     :concepts concepts
     :relationships relationships
     :tbox {:classes [] :object-properties [] :datatype-properties []}
     :abox []}))
