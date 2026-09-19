# Final ontology spec/code alignment review

This review covers the standalone graph library and every construct in
`specs/ontology.allium`. The executable witnesses and representation notes are
listed individually in [TEST-COVERAGE.md](TEST-COVERAGE.md), generated from the
reviewed `specs/test-coverage.json` ledger. Source/runtime evidence takes
precedence over historical extraction reports.

| Construct | Finding and resolution | Evidence |
| --- | --- | --- |
| ConceptDefinition, ConceptChanges, Concept | The spec omitted supported attributes/embedding inputs and forced a null embedding at creation. The spec now accounts for supplied embeddings, independent attribute/model updates, and optional domain/provenance fields. These are existing documented API capabilities, not new orchestration behavior. | `optional-fields-and-independent-attribute-embedding-updates`, lifecycle and typed validation tests |
| RegisterPredicate | The spec omitted duplicate-policy rejection and the revision increment. Both are now specified and tested. | `predicates-register-and-redefinition-is-atomic` |
| LinkConcepts | The spec advanced only the source revision. A cross-graph edge affects both endpoint graphs; each now explicitly advances once. | `cross-ontology-links-and-replay-retain-qualified-identities` |
| DeterministicResolutionAndMerge | Invalid resolution thresholds were accepted. They now reject before mutation; the spec states the valid finite [0,1] interval. | `obligation-gaps-red.log`, `invalid-resolution-thresholds-are-atomic`, generated bounded-score property |
| DeterministicResolutionAndMerge | Duplicate-URI candidates with conflicting values depended on input order. A stable content tie-break now makes the canonical graph independent of candidate order. | `obligation-properties-red.log`, `duplicate-uri-candidate-order-is-deterministic` |
| Entity representation | Spec identifiers and entity relationships are represented by UUIDs, qualified maps and query context; parent lists are exposed as sets. Optional values may be omitted keys. Contribution revision is nested in its result, and canonical mapping evidence joins URI mappings with match records. These are intentional representation differences, documented per obligation. | Field/relationship rows in the ledger; lifecycle, contribution-record, canonical-reference and source tests |
| Value equality | Clojure maps have structural equality. Request retention, digest-based retries and metadata replay exercise equality at observable boundaries; there is no separate value-class implementation to test. | Four value-equality rows in the ledger |
| Surface availability | The surface is a library API. Invalid operations reject via guards rather than hiding UI controls. Tenant context is provided by the authenticated host; missing/foreign context cannot access graph data. | Surface and tenant rows in the ledger |
| Remaining rules/invariants/contracts | Mutation, identity, hierarchy, contribution, RDF, retrieval, provenance and replay contracts have executable witnesses. No temporal state machine, deferred orchestration module or provider integration is required by this standalone spec. | All 107 ledger entries; full suite and sensitivity audit |

Earlier test-driven production fixes are retained by the boundary, mutation,
search, RDF, resolution and algorithm suites. They include inverse-edge
uniqueness, missing timestamps, schema reimport, vector overflow, type-compatible
resolution, immutable operation dispatch and invalid raw-RDF rejection. Red runs
are retained in `evidence/`; final green results are recorded separately.

Allium's current diagnostic baseline is 15 unused-field information diagnostics
and one warning that the external consumer has no governing spec import. A
standalone library deliberately does not import an application's governing
specification. There are zero errors and zero analysis findings. These checks
validate spec structure; runtime conformance evidence comes from the tests and
review, not from the absence of structural diagnostics.

No unresolved divergence remains within the reviewed ontology-port contracts.
This is a reviewed regression suite, not a formal proof or a performance/crash
qualification for every possible storage backend. ORC has not been changed or
retested as part of this completion audit.
