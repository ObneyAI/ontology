# Ontology port test assessment

Measured on 18 September 2026, scoped to this standalone library.

The suite provides substantial regression protection for the ontology port.
All 96 generated Allium obligations and 11 prose invariants/guarantees now have
reviewed executable witnesses or a documented language-level representation in
[the coverage ledger](TEST-COVERAGE.md). The checker compares a fresh Allium plan,
verifies test references and rejects a changed spec hash. This establishes
reviewed traceability, not a proof of correctness for every possible input.

| Verification | Result | Evidence |
| --- | --- | --- |
| Default suite, eight namespaces | 72 tests, 539 assertions, zero failures/errors | [Full run](../evidence/rigorous-current.log) |
| Six generated properties, default seed 20260918 | 60 trials each | [Full run](../evidence/rigorous-current.log) |
| Additional property campaign, seed 7 | 500 trials each; 3,000 total, all passing | [Campaign](../evidence/property-campaign.log) |
| Six deliberate regressions | All six detected by assertion failures; unmodified controls pass | [Sensitivity audit](../evidence/mutation-audit.log) |
| Obligation mapping | 96 generated + 11 prose, all mapped | [Ledger check](../evidence/obligation-review.json) |
| Coverage-checker sensitivity | Five invalid ledgers/specs rejected; control passes | [Checker audit](../evidence/coverage-checker-audit.json) |
| Allium check/analyse | Zero errors or process findings; 15 information diagnostics and one warning | [Check](../evidence/rigorous-spec-check.json), [analysis](../evidence/rigorous-spec-analyse.json) |

Coverage includes tenant and graph identity isolation, mutation validation and
atomicity, idempotency after later writes, stale revisions, predicate policies,
cross-graph relationships, inverse/symmetric uniqueness, hierarchy cycles,
resolution and canonical merge, source provenance, query filters, search ranking,
invalid vectors, Unicode RDF parsing/export, schema and instance round trips,
historical snapshots and replay. Generated mutation sequences check a separate
graph model after each operation and compare history with a fresh-store replay.

Durable tests use a real SQLite database, reopen it, reconstruct it in a fresh
JVM, reject an actual database write via a trigger, retry after that failure, and
race two contributions for one revision. This is evidence for the exercised
in-memory and SQLite paths; it does not establish behavior for every Grain
backend, crash point, or production graph size.

The sensitivity audit removes graph validation, relationship views, payload
identity checks, RDF validation, vector validity behavior and append CAS in turn.
Six detected examples are not an exhaustive mutation-testing score.

The suite exposed production defects, including missing timestamps, type-unsafe
resolution, order-dependent merge results, duplicate logical relationships,
invalid RDF acceptance, vector overflow and schema round-trip failures. Their
regression tests now pass. Earlier failing runs remain in `evidence/`.

All completion gates are recorded in [TEST-PLAN.md](TEST-PLAN.md), with the
spec/code review in [TEST-ALIGNMENT.md](TEST-ALIGNMENT.md). Reproduce the verification
with `bash scripts/verify-tests.sh`; run `python3 scripts/audit_coverage_checker.py`
for the checker sensitivity audit. No ORC or agent-orchestration verification is
included in this assessment.
