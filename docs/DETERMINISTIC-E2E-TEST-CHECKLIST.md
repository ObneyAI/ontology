# Extraction boundary verification

These obligations come from the agreed extraction boundary and the split ontology specification.

- [x] Standalone classpath loads without ORC and exposes no agent-specific commands or processors.
- [x] Public graph lifecycle persists and replays tenant-scoped concepts and typed relationships.
- [x] Same URI in distinct ontologies stays distinct during lookup, import and search.
- [x] Manual and imported hierarchy updates maintain both directions and reject cycles.
- [x] Contributions preserve source/model attribution, schema and instances across incremental imports.
- [x] Contribution retries append no duplicate events; changed content under one idempotency identity conflicts.
- [x] Expected-revision checks and append-time checks reject competing graph changes.
- [x] Entity resolution and snapshots preserve existing manual identities and current updates.
- [x] Search works without a model provider, and optional vector ranking uses qualified identities.
- [x] ORC source extraction commits through the standalone contribution API and retains learning behavior.

## Standalone boundary test evidence

`clojure -M:test` passes 20 tests / 123 assertions; output is in
[`evidence/standalone-test.log`](../evidence/standalone-test.log).

- `current-graph-events-replay-in-fresh-store` reconstructs snapshots, revisions,
  relationships and contributions from graph events in a new Grain store.
- `retry-identity-is-independent-of-source-content` measures graph events explicitly;
  Grain command/transaction records are outside the no-duplicate graph-event obligation.
- `competing-contributions-check-revision-at-append` synchronizes two state reads at
  the same revision, then uses real Grain append/CAS. Exactly one writer succeeds.
- `rdf-roundtrip-retains-literals-and-historical-revisions` imports and reimports
  exported N-Triples, preserving language and datatype literals; a past revision's
  snapshot stays unchanged after a later concept update.

Additional passing boundary tests cover versioned source attribution, generic inverse
predicates, matching/mismatching/missing embedding model identities, canonical schema,
instance and RDF references, invalid schema atomic rejection, imported cycles, and
qualified model-backed search. URI-only lookup is rejected as a removed API arity.

The dependency/source audit is recorded in `evidence/extraction-dependency-audit.json`
and `evidence/standalone-dependencies.txt`. ORC workflow/learning integration passes
156 tests / 836 assertions in `evidence/orc-focused-tests.log`. All 76 ontology test
namespaces load; live provider calls were not executed.

The final suite also checks original submitted input retention and rejects
ambiguous resolution that would collapse existing identities.
