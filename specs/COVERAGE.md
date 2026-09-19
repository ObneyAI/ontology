# Ontology behavioral coverage

`ontology.allium` governs the standalone `ai.obney.ontology` graph library.
It owns tenant-qualified ontology and concept identity, lifecycle mutation,
registered predicate policy, hierarchy consistency, source versions, retry-safe
contribution imports, canonical resolution and merge, deterministic RDF,
schema/instance preservation, scoped queries/search, and revision snapshots.

ORC owns source extraction workflows, build coordination, classification,
Living Descriptions, claims, campaign evidence, consolidation and harvesting.
Its `specs/ontology.allium` imports this specification and demands the graph
mutation, lookup and contribution contracts. Agent vocabulary is caller data.
There is no backward-compatibility requirement or URI-only lookup API.

The contribution contract uses additive upserts. New keys may re-interpret the
same source bytes; identical retries return the original result; changed-payload
key reuse and stale revisions fail without a graph mutation. Existing manual
concept identity remains canonical; ambiguous resolution cannot collapse two
existing identities. Original submitted input and opaque producer evidence
remain available independently of the merged graph. Each contribution is one
atomic transaction; an application workflow can commit several contributions.

## Historical extraction verification, 18 September 2026

`allium check specs` reports 17 diagnostics: 16 information and 1 warning,
with no errors. `allium analyse specs` reports no process findings.
The information diagnostics are unused retained descriptive/provenance fields;
the warning says the external caller has no governing specification import.
The caller deliberately has no governing application specification: this is a
standalone library. These are characterized diagnostics, not a clean check.
Raw results are in `evidence/extraction-spec-check.json` and
`evidence/extraction-spec-analyse.json`.

These structural results do not prove implementation conformance. Required
integration gates are tenant isolation, equal URI in separate ontologies,
manual/import integrity equivalence, retry/conflict handling, hierarchy inverse
consistency, canonical merge updates, schema/instance round-trip, snapshot replay,
operation without optional search, and a classpath containing no ORC components.
ORC must additionally execute extraction and commit/query its contribution
through the standalone public API. Implementation/test status belongs in the
project README and ORC deterministic E2E checklist, not this structural baseline.

Final per-source workflow alignment retains the same measured structural
baseline: standalone 16 information, 1 warning, 0 errors; combined 13 modules
198 information, 34 warnings, 0 errors, with no analysis findings. ORC records
build start before extraction, imports each accepted source immediately, and
exports after all sources are committed or skipped. A later workflow failure
does not erase earlier accepted contributions. These orchestration obligations
belong to the importing ORC specification, not this library.

## Standalone rigorous suite completion

Current verification reports 72 tests and 539 assertions passing, with six
generated properties also passing 500 trials each at seed 7. The reviewed ledger
covers 96 generated obligations and 11 prose invariants/guarantees. Optional
fields and supported attribute/embedding updates are now explicit in the spec;
predicate redefinition rejects, and cross-graph edges advance each affected
graph revision once. Allium now reports 15 information diagnostics, one warning,
zero errors and zero process findings.

See [the obligation matrix](../docs/TEST-COVERAGE.md),
[the assessment and limitations](../docs/TEST-ASSESSMENT.md), and
[the spec/code alignment review](../docs/TEST-ALIGNMENT.md).
`bash scripts/verify-tests.sh` reproduces the current verification. The preceding
ORC results are historical and are not counted toward standalone suite coverage.
