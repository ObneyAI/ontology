# Extraction verification

The standalone library passes **20 tests with 123 assertions** using a real
Grain event store. It covers qualified identities, tenant isolation, hierarchy,
source versions, idempotency, concurrent commits, canonical resolution,
embedding model identity, schema validation, original input retention, RDF
roundtrips and historical replay.

The ORC integration passes **156 tests with 836 assertions** across 16 suites.
These cover public extraction and embedding workflows, source queries, graph
lifecycle, static vocabulary, classification, Living Descriptions and claims,
behavior composition, hierarchy, and harvesting. A source failure after an
earlier accepted contribution retains that contribution and records a durable
build failure. The start event exists before extraction runs.

All **76 ontology test namespaces load**. Live model test assertions were
migrated to current contributions and compiled; live provider calls were not
executed. The focused regression uses model substitutes at extraction boundaries
and real graph commands, persistence and queries.

The dependency tree and source audit show no ORC dependency, agent command,
workflow processor or scheduler in the standalone library. It registers six
graph mutation commands and uses the host-provided tenant/event-store context.

Allium diagnostics are tracked in each coverage ledger. Structural checks have
no errors and analysis has no findings; existing informational diagnostics and
external-consumer warnings remain documented.

Evidence lives in `evidence/standalone-test.log`, `orc-focused-tests.log`,
`orc-test-namespace-load.log`, `extraction-dependency-audit.json`,
`standalone-dependencies.txt`, and `extraction-*-spec-*.json`. The exact focused
ORC runner is `evidence/orc-focused-tests.clj` (run from ORC with
`clojure -M:dev:test ../ontology-exp/evidence/orc-focused-tests.clj`).

This extraction makes a clean API and event-format break. It does not migrate
a running installation, publish a library release, or deploy ORC. The sibling
local dependency makes the two local projects immediately usable together.
