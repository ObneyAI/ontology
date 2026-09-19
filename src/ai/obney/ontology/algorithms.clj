(ns ai.obney.ontology.algorithms
  "Public graph, resolution, source, serialization, and RDF algorithms."
  (:require
   [ai.obney.ontology.core.entity-resolver :as entity-resolver]
   [ai.obney.ontology.core.graph-evolver :as graph-evolver]
   [ai.obney.ontology.core.source-registry :as source-registry]
   [ai.obney.ontology.core.graph :as graph]
   [ai.obney.ontology.core.rdf :as rdf]))

(def normalize-label entity-resolver/normalize-label)
(def group-by-entity-type entity-resolver/group-by-entity-type)
(def concepts-from-different-sources? entity-resolver/concepts-from-different-sources?)
(def find-exact-matches-in-block entity-resolver/find-exact-matches-in-block)
(def cosine-similarity entity-resolver/cosine-similarity)
(def find-semantic-matches-in-block entity-resolver/find-semantic-matches-in-block)
(def make-union-find entity-resolver/make-union-find)
(def find-root entity-resolver/find-root)
(def union! entity-resolver/union!)
(def group-matched-uris entity-resolver/group-matched-uris)
(def select-canonical-uri entity-resolver/select-canonical-uri)
(def compute-canonical-uris entity-resolver/compute-canonical-uris)
(def generate-owl-sameAs-triples entity-resolver/generate-owl-sameAs-triples)
(def resolve-within-block entity-resolver/resolve-within-block)
(def resolve-within-batch entity-resolver/resolve-within-batch)
(def resolve-incremental entity-resolver/resolve-incremental)

(def apply-canonical-uri graph-evolver/apply-canonical-uri)
(def apply-canonical-to-concept graph-evolver/apply-canonical-to-concept)
(def apply-canonical-to-relationship graph-evolver/apply-canonical-to-relationship)
(def apply-canonical-mappings graph-evolver/apply-canonical-mappings)
(def deduplicate-concepts graph-evolver/deduplicate-concepts)
(def deduplicate-relationships graph-evolver/deduplicate-relationships)
(def extract-classes-from-concepts graph-evolver/extract-classes-from-concepts)
(def extract-properties-from-relationships graph-evolver/extract-properties-from-relationships)
(def detect-schema-extensions graph-evolver/detect-schema-extensions)
(def merge-into-graph graph-evolver/merge-into-graph)
(def concept->ttl-triples graph-evolver/concept->ttl-triples)
(def relationship->ttl-triple graph-evolver/relationship->ttl-triple)
(def generate-ttl-snapshot graph-evolver/generate-ttl-snapshot)
(def tbox->ttl-lines graph-evolver/tbox->ttl-lines)
(def abox->ttl-lines graph-evolver/abox->ttl-lines)
(def generate-owl-ttl-snapshot graph-evolver/generate-owl-ttl-snapshot)

(def sha256 source-registry/sha256)
(def compute-content-hash source-registry/compute-content-hash)
(def get-file-size source-registry/get-file-size)
(def generate-source-id source-registry/generate-source-id)
(def detect-source-type source-registry/detect-source-type)

(def default-edge-weights graph/default-edge-weights)
(def default-traversal-config graph/default-traversal-config)
(def concepts->graph graph/concepts->graph)
(def parse-turtle-multiline graph/parse-turtle-multiline)
(def parse-turtle-graph graph/parse-turtle-graph)
(def bfs-spreading-activation graph/bfs-spreading-activation)
(def link-expansion graph/link-expansion)
(def compute-rrf-scores graph/compute-rrf-scores)
(def merge-batches graph/merge-batches)
(def compute-temporal-relevance graph/compute-temporal-relevance)
(def apply-temporal-scoring graph/apply-temporal-scoring)
(def quick-search graph/quick-search)
(def expand-from-concept graph/expand-from-concept)

(def extract-concepts-from-rdf rdf/extract-concepts-from-rdf)

(def parse-ntriples rdf/parse-ntriples)
