(ns ai.obney.ontology.core.source-registry
  "Source identity and content utilities; persistence belongs to graph commands."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.io File]))

(defn sha256
  "Compute SHA-256 hash of a string.
   Returns hex string, matches Python: hashlib.sha256(content.encode()).hexdigest()"
  [^String content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes content "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn compute-content-hash
  "Compute content hash for a source.
   For file paths, reads the file content.
   For direct content, hashes the content string."
  [source-uri content]
  (cond
    ;; Direct content provided
    (and content (string? content))
    (sha256 content)

    ;; File path - read and hash
    (and source-uri (.exists (File. ^String source-uri)))
    (sha256 (slurp source-uri))

    ;; URL or non-file path - hash the URI itself
    :else
    (sha256 (or source-uri ""))))

(defn get-file-size
  "Get file size in bytes, or content length for strings."
  [source-uri content]
  (cond
    (and content (string? content))
    (count (.getBytes ^String content "UTF-8"))

    (and source-uri (.exists (File. ^String source-uri)))
    (.length (File. ^String source-uri))

    :else
    0))

;; =============================================================================
;; Source ID Generation
;; =============================================================================

(defn generate-source-id
  "Generate a UUID source ID.
   Note: We use UUIDs because grain event tags require UUID values."
  [_content-hash]
  (java.util.UUID/randomUUID))

;; =============================================================================
;; Source Type Detection
;; =============================================================================

(defn detect-source-type
  "Auto-detect source type from URI/path.
   Matches Python's _detect_source_type method."
  [source-uri]
  (when source-uri
    (let [lower-uri (str/lower-case source-uri)]
      (cond
        (str/ends-with? lower-uri ".csv") "csv"
        (str/ends-with? lower-uri ".json") "json"
        (str/ends-with? lower-uri ".ttl") "rdf"
        (str/ends-with? lower-uri ".rdf") "rdf"
        (str/ends-with? lower-uri ".owl") "rdf"
        (str/ends-with? lower-uri ".txt") "text"
        (str/ends-with? lower-uri ".md") "text"
        (str/starts-with? lower-uri "jdbc:") "sql"
        (str/starts-with? lower-uri "postgresql:") "sql"
        (str/starts-with? lower-uri "mysql:") "sql"
        :else "text"))))
