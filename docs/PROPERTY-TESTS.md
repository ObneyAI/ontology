# Property-based tests

The public API properties live in
[`property_test.clj`](../test/ai/obney/ontology/property_test.clj). They use
`clojure.test.check` generators and shrinking against real in-memory Grain
stores. Each trial creates an isolated tenant and closes its store. The harness
clears Grain's shared L1 cache between complete trials, retaining warm-cache
behavior within each trial and its replay checks.

## Coverage

| Property | Generated inputs | Independent expectation |
| --- | --- | --- |
| Search reachability | Acyclic hierarchy edges, related edges, multiple seeds, and traversal depths | A set-based breadth-first traversal determines reachable concepts. Reordering seeds preserves ranking, and a smaller result limit returns a prefix. |
| Metadata and collection scope | Updates to labels, descriptions, scope, aliases, provenance, entity type, and attributes across graphs sharing URIs | A map keyed by ontology and URI models whole-field replacement and unchanged fields. UUID/URI reads, filtered collections, qualified map keys, and fresh-store replay must agree. |
| Retry isolation | Interleaved updates across two graphs, repeated request keys, changed payloads, and revision preconditions | A receipt table keyed by graph and request key models acceptance, replay, and conflicts. Revisions, labels, and event counts must match after every operation. |
| Relationship views | Directed, symmetric, and inverse policies with local and cross-ontology links | A set of qualified edges computes logical reversals from the originating graph's policy. Public relationship and concept views must agree; duplicate links must leave history unchanged. |
| Mutation sequences | Creation, renaming, parent replacement, imports, retries, and rejected imports | A separate concept/hierarchy model checks graph state, revisions, historical snapshots, and replay. |
| Canonical selection | URI candidates and existing-identity preference | Selection follows existing identity, URI length, and lexical order independently of candidate ordering. |
| RDF literals | Unicode, escapes, control characters, and empty strings | Parsed exports preserve literals, statement counts, and checksums. |
| Disjoint imports | Independent concepts in different import orders | Exported RDF and checksums are order-independent. |
| Source versions | Repeated source identities and content versions | A set of source/version pairs models uniqueness and tenant isolation. |
| Resolution scores | Vector angles and very small or large finite scales | Match scores remain finite and bounded, and existing identities remain canonical. |

The newer boundary properties target combinations that example tests cover only
individually: warm UUID projections after updates, overlapping URIs, policy
ownership across graphs, and retries after unrelated writes. Their models do not
call production traversal, projection, index, or retry helpers to compute the
expected result.

Additional generated tests in `delta_test.clj` and `indexes_test.clj` compare
incremental changes, indexes, hierarchy validation, and record validation with
whole-state reference computations. SQLite persistence and concurrency are
covered separately by the durable and structural suites.

## Running and reproducing failures

```sh
clojure -M:test ai.obney.ontology.property-test
ONTOLOGY_TEST_SEED=7 ONTOLOGY_TEST_TRIALS=500 clojure -M:test ai.obney.ontology.property-test
```

The default is 60 trials per property with seed `20260918` and maximum generator
size 35. Each property prints its seed, trial count, outcome, and shrinking
information. A failed assertion includes the generated counterexample; rerun
with the reported seed to reproduce the input sequence.

Fixtures allocate fresh UUIDs and timestamps. Assertions compare identity
relationships, revisions, content, and replay rather than requiring particular
random identifiers or wall-clock values. Trial sizes bound runtime; passing
these properties does not establish correctness for every graph size or storage
backend.

[The coverage ledger](TEST-COVERAGE.md) links these tests to the Allium obligations.
