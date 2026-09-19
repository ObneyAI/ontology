# Standalone ontology test plan

Scope: the standalone library only. No ORC runtime, fixtures, or model service.
Generated obligations are in `evidence/test-obligations.json` (`allium plan`),
with entity shapes in `evidence/domain-model.json` (`allium model`).

Completion gates:

- [x] Map every generated obligation and prose contract invariant to executable tests or a justified language-level mapping.
- [x] Public mutation success, validation, idempotency, conflicts and tenant authorization.
- [x] Declared predicates, accepted/rejected cross-graph edges, inverse/symmetric uniqueness and hierarchy invariants.
- [x] Query filters, required identities, historical snapshots and isolated tenants.
- [x] Lexical/vector/graph retrieval, ranking weights, depth, limits, ties, and invalid vectors.
- [x] RDF valid/invalid syntax, escaping, Unicode, complete schema/instance serialization and canonical roundtrip.
- [x] Resolution aliases, type blocks, thresholds, ambiguous identities, merge preservation and permutation properties.
- [x] Generated operation sequences checked against an independent model, invariants after each step, replay and all revision snapshots.
- [x] Durable SQLite restart, actual rejected storage write, retry after reopen, and competing writes.
- [x] Representative deliberate regressions are detected by tests (sensitivity audit).
- [x] One repeatable default command executes all required suites; failures and random seeds are reported; no silent skips.
- [x] Final Allium check/analyse and an obligation-by-obligation completion review.

Tests that are already green are checked for observable state assertions; they
may cover existing behavior. New failures are retained as regressions and fixed
in the implementation. Prior extraction results are historical, not evidence for
this expanded suite.

## Completion evidence

| Gate | Current authoritative evidence |
| --- | --- |
| Generated and prose obligation mapping | `specs/test-coverage.json`, `docs/TEST-COVERAGE.md`, `evidence/obligation-review.json`: 96 generated + 11 prose, no missing/obsolete obligations or test references |
| Mutation validity, retries and tenants | `mutation_test.clj`, `boundary_test.clj`: invalid inputs preserve snapshots, events and provenance; original results survive later writes |
| Predicates, qualified edges and hierarchy | Mutation tables plus generated independent graph model; cross-graph revisions and inverse uniqueness checked through replay |
| Queries and history | Lifecycle/filter and missing-identity tests; every generated revision compared with historical export |
| Search | Six search tests plus model-identity boundary tests; signals, weights, dimensions, depth, limits and numerical extremes |
| RDF/schema/instances | Six RDF tests plus generated escaped literal round trips; malformed input and non-existing revisions reject |
| Resolution and merge | Nine resolution tests, boundary reference-rewrite tests and generated score/canonical properties; duplicate-URI permutation tested |
| Generated model/replay | Six properties: mutation sequences, canonical choice, literal round trips, disjoint import order, source versions and bounded resolution scores |
| Durable failure/restart/race | Four SQLite tests including a fresh JVM, real rejected SQL write and competing appends |
| Regression sensitivity | `evidence/mutation-audit.log`: six detected regressions, passing controls; coverage checker also rejects five malformed/stale inputs |
| Repeatable command | `clojure -M:test` discovers all suites; `bash scripts/verify-tests.sh` executes the complete verification pipeline and fails on unsuccessful commands/assertions |
| Final review | `docs/TEST-ALIGNMENT.md`, `evidence/final-verification.log`, current Allium JSON; zero test failures/errors and no spec errors/process findings |

The completed scope is a rigorous ontology-port regression suite with reviewed
spec traceability. It does not claim exhaustive input coverage, performance
capacity, arbitrary crash recovery, or verification of unexercised Grain
backends. No required test is a skipped placeholder.
