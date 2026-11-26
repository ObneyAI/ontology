# Products to benchmark against

Research date: 2026-10-03. This is a candidate selection and experiment design,
not an executed comparison. Product capabilities below come from linked primary
documentation; priorities and proposed experiments are our assessment. Pin exact
versions, editions, configurations and license terms when executing a campaign.

For the storage/graph track, start with **RDF4J NativeStore, Datomic Local, and Neo4j**, then add **TerminusDB**
for versioned graph behavior and **GraphDB** for an RDF product comparison.
For product feature coverage, evaluate **PoolParty and TopBraid EDG** separately.

For the agent-memory track, see [agent-memory products and benchmarks](AGENT-MEMORY-BENCHMARKS.md):
Mem0, Zep/Graphiti, Hindsight, Supermemory, Letta, Cognee and LangMem, with
LongMemEval, LoCoMo and action-based evaluations.

Our comparison target is the standalone library described in [README](../README.md):
tenant-scoped concept graphs, qualified identities, typed relationship policies,
cycle rejection, atomic contributions, canonicalization, hybrid search, retries,
optimistic concurrency, provenance and revision snapshots. Extraction and
embedding generation belong to the application. The older architecture report
also covers ORC functionality outside this library and should not define its
benchmark scope.

## Candidates for executable comparisons

| Priority | Product | Documented capability and reason to include | Proposed comparison and integration boundary |
| --- | --- | --- | --- |
| First | **Eclipse RDF4J NativeStore / MemoryStore** | Embedded Java RDF stores behind a common repository API, with transactions; Lucene and inference are separate SAIL components. This is a close runtime comparison for our Clojure library. [Repository API](https://rdf4j.org/documentation/programming/repository/), [SAIL architecture](https://rdf4j.org/documentation/reference/sail/) | Qualified lookup, adjacency, transactional delta import and RDF export. Use NativeStore for persistent comparisons and MemoryStore for volatile comparisons. Add an adapter for our concept records, inverse views and mutation policies; a triple insert alone does not implement our import contract. |
| First | **Datomic Local** | In-process durable and memory databases, with historical database views in the Datomic API. Particularly relevant to immutable state and Clojure integration. [Local deployment](https://docs.datomic.com/datomic-local.html), [history and as-of](https://docs.datomic.com/reference/filters.html) | Current versus historical reads, ten-concept transactions, retained-history growth, reopen and recovery. Map ontology revisions to transaction boundaries. Model qualified identity, edges, retry keys and contribution provenance explicitly. Local has documented transaction, string and memory limits; record these and do not generalize its results to Datomic Cloud or Pro. |
| First | **Neo4j** | A graph comparison with full-text and vector indexes that can be combined for hybrid search. [Semantic indexes](https://neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/) | Node lookup, bounded traversal, writes and retrieval quality/latency. Represent tenant, ontology and URI explicitly, return the same concept fields, and implement our hierarchy and inverse policies. Use a locally hosted server; measure transport separately from embedded calls. Treat historical snapshots as adapter work unless the selected configuration demonstrates the required behavior. |
| Second | **TerminusDB** | Document graphs with immutable commits, branching, diffs and historical queries. This is a strong match for evolving knowledge with retained provenance. [Architecture](https://terminusdb.org/docs/terminusdb-explanation/), [document/version API](https://docs.terminusdb.org/docs/document-insertion/) | Small commits, historical export, storage growth and concurrent version checks. Map ontology revisions to commit identifiers. Preserve the same graph content at each boundary; native version history does not by itself establish our contribution retry or canonicalization semantics. Local Docker deployment is documented in its [getting-started guide](https://terminusdb.org/docs/). |
| Second | **GraphDB** | RDF storage with configurable inference. The documented `empty` ruleset disables reasoning, allowing a storage baseline before a separate reasoning experiment. [Reasoning configuration](https://graphdb.ontotext.com/documentation/11.0/reasoning.html) | Imports, traversal through SPARQL, export and optional entailment workloads. Reuse the RDF fixture developed for RDF4J where possible. The 11.0 licensing documentation describes a two-concurrent-query cap for Free; verify the selected edition before throughput tests. [Edition constraints](https://graphdb.ontotext.com/documentation/11.0/licensing.html) |
| Alternative RDF baseline | **Apache Jena TDB2** | Java RDF storage with transactional access; Jena documents serializable transactions and a multiple-reader/single-writer model for TDB2. [TDB2](https://jena.apache.org/documentation/tdb2/), [transactions](https://jena.apache.org/documentation/txn/) | An independent RDF implementation to confirm findings from RDF4J. Use embedded TDB2 for local library measurements; introduce a server boundary only in a separate campaign. Same concept/policy adapter requirements as RDF4J. |
| Targeted follow-up | **TypeDB Community Edition** | Typed entities, relations and attributes, with inheritance and constraints enforced on writes. [Community Edition](https://typedb.com/community-edition), [schema model](https://typedb.com/docs/home/get-started/schema/) | Typed relationship validation, invalid imports, multi-hop queries and schema evolution. Useful if richer schema enforcement becomes a priority. Define an explicit data mapping; its native model is different from our RDF/SKOS-shaped records. |
| Application-level follow-up | **Graphiti** | Temporal graph construction and hybrid retrieval; search combines semantic and BM25 results with reciprocal-rank fusion and optional distance reranking. [Overview](https://help.getzep.com/graphiti/getting-started/overview), [search](https://help.getzep.com/graphiti/working-with-data/searching) | Entity-resolution precision/recall, temporal retrieval and useful search results on labeled tasks. Its ingestion includes model work and requires a graph backend: report those costs separately. This is an application-stack comparison, not a direct storage-speed comparison. [Requirements and implementation](https://github.com/getzep/graphiti) |

RDF4J and Jena cover much of the same initial question. Implementing both before
the temporal and property-graph comparisons would have lower information value.
The recommended first three test different hypotheses: RDF overhead, history
cost, and indexed traversal/search.

## Product feature comparisons

These are useful references for what a complete ontology product offers. Access
to an evaluation environment and the selected edition's APIs must be established
before planning executable measurements; this research did not obtain accounts.

| Product | What its documentation establishes | What to assess against our library |
| --- | --- | --- |
| **PoolParty** | Concept, scheme and SKOS-XL data, project history and concept-management APIs. [Data organization](https://help.poolparty.biz/en/poolparty-overview/repository-and-graph-structure-of-data-stored-in-poolparty.html), [concept API](https://help.poolparty.biz/8.1/en/developer-guide/basic---advanced-server-apis/thesaurus---ontology-manager-api/thesaurus-services/concept-management-services/web-service-method--request-a-concept-s-details.html) | Concept/label modeling, hierarchy editing, imports, audit completeness and API ergonomics. A material distinction: its raw SPARQL update endpoint explicitly bypasses project history. Use history-aware mutation APIs when comparing audit guarantees. [Update semantics](https://help.poolparty.biz/en/poolparty-developer-guide/thesaurus---ontology-manager-api/project-services/web-service-method--update-project-data-using-sparql.html) |
| **TopBraid EDG** | SKOS taxonomies, ontology and crosswalk collections, review workflows and change history. [Introduction](https://docs.topquadrant.com/8.3/introduction/index.html), [vocabulary workflow guide](https://docs.topquadrant.com/7.4/quick_start_guides/edg_vocabulary_management/index.html) | Approval and publication workflows, custom metadata, provenance visibility and vocabulary governance. These provide a feature-gap assessment; our current library has no comparable authoring UI. Confirm behavior against the evaluation version because these guides are versioned. |
| **Stardog** | An inference engine and schema-version tracking; its documented schema hash detects schema changes. [Inference and schema versioning](https://docs.stardog.com/inference-engine/) | Reasoning coverage and schema-management behavior if those become requirements. Do not equate schema-change detection with our full historical graph export. Lower initial priority because full logical entailment is outside our current contract. |
| **Palantir Foundry Ontology** | An operational model combining objects, properties and links with actions, functions and security. [Ontology overview](https://www.palantir.com/docs/foundry/ontology/overview) | Application integration, action semantics and operational governance as a future product reference. It has a much broader platform scope than this library; classify it as a feature comparison rather than a first local latency baseline. |

## How to make the results comparable

Keep two reported experiment tracks:

1. **Common operations:** the same records, lookup result shape, traversal depth,
   write batch and export content. Report which guarantees each configuration
   actually implements. This measures the shared operations with minimal adapters.
2. **Library contract:** require tenant isolation, ontology-qualified identity,
   endpoint validation, inverse/symmetric views, cycle rejection, atomic imports,
   revision conflicts, retry behavior, contribution evidence and historical state.
   Include adapter work in timings. Classify each obligation as native,
   adapter-implemented, unsupported or unverified. Unsupported is not a zero-cost
   successful operation.

Before performance measurements, check successful and rejected writes, exact
retry after subsequent mutations, changed payload under the same retry key,
competing revisions, cross-ontology edges, tenant leakage and restart recovery.
Ontology-specific URI qualification needs particular care: encoding two
graph-qualified identities as one RDF IRI must not accidentally unify them when
queries span graphs. A named graph alone is also not an authorization boundary.

| Workload | Fixture and observation |
| --- | --- |
| Current reads | Existing 10k/20k binary tree; random and hot URI lookups, enumeration, depth-two traversal and export. Fully consume results and verify content outside the timer. |
| Incremental writes | Ten new concepts; one concept update; ten matching aliases. Measure durable commit latency and time until all relevant read/search paths expose the change. Preserve validation and provenance in the contract track. |
| History | Hold concept count fixed while increasing updates, then export the same selected revisions. Measure current reads, historical reads, disk growth and reopen time independently. Map revision identifiers across implementations. |
| Graph shape and isolation | Add a DAG with multiple parents, a hub-heavy graph, equal URIs across ontologies, cross-ontology links and multiple tenants. Record edge degree and stored versus derived relationships. |
| Retrieval and resolution quality | Keep current synthetic vectors for plumbing checks. Add labeled query relevance and labeled alias pairs; use recall@10/nDCG@10 and merge precision/recall. Hold embeddings and model identifiers fixed. Compare approximate retrieval at stated recall, not latency alone. |
| Contention | Run separate single-client and concurrent-client campaigns after correctness checks. Track accepted throughput, conflicts, retries and latency. Honor edition concurrency limits. |

Use 10k and 20k concepts initially, followed by 100k only after the adapters are
correct and the smaller cases complete. Record actual concepts, stored edges,
triples/datoms and historical records: equal concept counts do not imply equal
storage work. Keep a fixed batch size and distinguish bulk loading from ongoing
transactional writes.

Match durability, total memory allowance and index readiness. The existing 2 GiB
JVM heap setting is useful for JVM runs, but is not a complete memory budget for
servers or native/page caches. Report process RSS and disk usage as well as JVM
allocations. Run embedded and server measurements as distinct groups; add
equivalent client/server boundaries before making deployment-level latency claims.
Measure inference-enabled configurations separately from explicit-data baselines.

Retain raw samples, failures, fixture generation time, index-build time, database
configuration and exact dependency/image versions. Use repeated campaigns and
enough requests for any tail-latency claims. Our existing three/five-sample smoke
campaigns support preliminary medians, not p95/p99 comparisons. See the existing
[benchmark methodology](BENCHMARKS.md).

## Existing evidence and proposed next implementation

The original [baseline](BENCHMARK-BASELINE.md) predates scoped reads and mutation
indexes. The later [mutation-index campaign](../evidence/benchmarks/mutation-indexes/report.md)
records sub-millisecond warm lookup medians at 10k/20k concepts and roughly
94–286 ms ten-concept import medians across the two stores. Its accompanying
[implementation notes](MUTATION-INDEXES.md) identify concurrent test activity and
only three samples, and describe subsequent validation changes. Rerun the current
checkout under controlled conditions before comparing it with another product.

The first implementation should add a shared fixture/result contract and an
RDF4J adapter for lookup, depth-two traversal, ten-concept import and current
export. Follow with Datomic Local for current/historical state and Neo4j for
traversal/retrieval. Add the richer contract obligations incrementally, with an
explicit coverage table, before publishing any claim of equivalent behavior.

No external product has been installed or benchmarked as part of this research.
