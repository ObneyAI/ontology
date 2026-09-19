(ns ai.obney.ontology.core
  "Validated public operations and expected-error handling."
  (:require [ai.obney.ontology.core.errors :as errors]
            [ai.obney.ontology.core.operations :as operations]
            [clojure.string :as str]))

(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))
(defn- ontology-id! [id]
  (errors/require! (or (uuid? id) (nonblank? id)) "Ontology ID must be a UUID or nonblank string"))
(defn- request! [request]
  (errors/require! (map? request) "Request must be a map"))
(defn- scoped [ctx ontology-id f]
  (errors/call ctx #(do (ontology-id! ontology-id) (f))))
(defn- scoped-request [ctx ontology-id request f]
  (scoped ctx ontology-id #(do (request! request) (f))))

(defn create-ontology! [ctx request]
  (errors/call ctx #(do (request! request) (operations/create-ontology! ctx request))))
(defn create-concept! [ctx ontology-id request]
  (scoped-request ctx ontology-id request #(operations/create-concept! ctx ontology-id request)))
(defn update-concept! [ctx ontology-id concept-id request]
  (scoped-request ctx ontology-id request
    #(do (errors/require! (uuid? concept-id) "Concept ID must be a UUID")
         (operations/update-concept! ctx ontology-id concept-id request))))
(defn create-relationship! [ctx request]
  (errors/call ctx
    #(do (request! request)
         (ontology-id! (:source-ontology-id request))
         (ontology-id! (:target-ontology-id request))
         (operations/create-relationship! ctx request))))
(defn register-predicate! [ctx ontology-id request]
  (scoped-request ctx ontology-id request #(operations/register-predicate! ctx ontology-id request)))
(defn import-contribution! [ctx ontology-id request]
  (scoped-request ctx ontology-id request #(operations/import-contribution! ctx ontology-id request)))
(defn import-rdf! [ctx ontology-id request]
  (scoped-request ctx ontology-id request #(operations/import-rdf! ctx ontology-id request)))

(def graph-event-types operations/graph-event-types)

(defn get-ontology [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-ontology ctx ontology-id)))
(defn list-ontologies [ctx]
  (errors/call ctx #(operations/list-ontologies ctx)))
(defn ontology-exists? [ctx ontology-id]
  (scoped ctx ontology-id #(operations/ontology-exists? ctx ontology-id)))
(defn get-revision [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-revision ctx ontology-id)))
(defn- with-concept-filters [ctx filters f]
  (errors/call ctx
    #(do (request! filters)
         (when (contains? filters :ontology-id) (ontology-id! (:ontology-id filters)))
         (when (contains? filters :ontology-ids)
           (errors/require! (coll? (:ontology-ids filters)) "Ontology IDs must be a collection")
           (doseq [id (:ontology-ids filters)] (ontology-id! id)))
         (errors/require! (not (and (contains? filters :ontology-id) (contains? filters :ontology-ids)))
                          "Supply ontology-id or ontology-ids, not both")
         (when (some? (:scope filters))
           (errors/require! (or (keyword? (:scope filters)) (string? (:scope filters)))
                            "Scope must be a keyword or string"))
         (when (some? (:broader-uri filters))
           (errors/require! (nonblank? (:broader-uri filters)) "Broader URI must be a nonblank string"))
         (f))))
(defn get-concepts
  ([ctx] (get-concepts ctx {}))
  ([ctx filters]
   (with-concept-filters ctx filters #(operations/get-concepts ctx filters))))
(defn get-concept [ctx ontology-id concept-id]
  (scoped ctx ontology-id
    #(do (errors/require! (uuid? concept-id) "Concept ID must be a UUID")
         (operations/get-concept ctx ontology-id concept-id))))
(defn get-concept-by-uri [ctx ontology-id uri]
  (scoped ctx ontology-id
    #(do (errors/require! (nonblank? uri) "Concept URI must be a nonblank string")
         (operations/get-concept-by-uri ctx ontology-id uri))))
(defn get-concept-map
  ([ctx] (get-concept-map ctx {}))
  ([ctx filters]
   (with-concept-filters ctx filters #(operations/get-concept-map ctx filters))))
(defn get-relationships [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-relationships ctx ontology-id)))
(defn get-contributions [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-contributions ctx ontology-id)))
(defn get-sources [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-sources ctx ontology-id)))
(defn get-canonical-mappings [ctx ontology-id]
  (scoped ctx ontology-id #(operations/get-canonical-mappings ctx ontology-id)))
(defn export-snapshot
  ([ctx ontology-id] (export-snapshot ctx ontology-id nil))
  ([ctx ontology-id revision]
   (scoped ctx ontology-id
     #(do (when (some? revision)
            (errors/require! (pos-int? revision) "Revision must be a positive integer"))
          (operations/export-snapshot ctx ontology-id revision)))))
(defn search [ctx ontology-id request]
  (scoped-request ctx ontology-id request
    #(do (when (some? (:query-text request))
           (errors/require! (string? (:query-text request)) "Query text must be a string"))
         (when (some? (:seed-uris request))
           (errors/require! (and (coll? (:seed-uris request)) (every? nonblank? (:seed-uris request)))
                            "Seed URIs must be a collection of nonblank strings"))
         (when (some? (:embed-query request))
           (errors/require! (ifn? (:embed-query request)) "Embed query must be callable"))
         (operations/search ctx ontology-id request))))
