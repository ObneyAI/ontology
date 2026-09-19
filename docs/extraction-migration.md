# Clean extraction from ORC

This extraction creates a standalone graph project and makes ORC its consumer.
Backward compatibility is not required. Update callers to the public library
rather than adding aliases for old mixed graph-and-agent APIs or replay adapters
for old event shapes.

| Standalone ontology | ORC |
| --- | --- |
| Ontology/concept identity and lifecycle | Agent vocabulary and behavior bodies |
| Predicate policy, hierarchy and graph validation | Tasks, campaigns, classification and execution |
| Source versions and atomic graph contributions | Source loading, extractor workflows and retries |
| Canonical resolution, schema and instances | Claims, descriptions and learning policy |
| Scoped queries, search and snapshots | Fitness ranking, prompt assembly and harvesting |

## Consumer changes

1. Add `ai.obney/ontology` as a dependency on this project. The intended local
   layout is sibling `orc` and `ontology-exp` directories; ORC's spec imports the
   standalone specification from that sibling.
2. Require `ai.obney.ontology.interface` for graph operations. Use tenant context
   and an ontology ID for every concept lookup or search. Store qualified graph
   references with ORC-owned records.
3. Keep source-type dispatch, workflow execution, model selection, retries,
   cancellation and build tracking inside ORC. Extractors produce concepts,
   relationships, schema, instances and opaque producer provenance.
4. Submit that data through `(import-contribution! ctx ontology-id request)`,
   with a source content hash,
   stable contribution ID and expected graph revision. Do not append graph events
   from ORC or mutate the library's projections. Retry an unchanged import using
   the same request; retry extraction under a new contribution identity.
5. Complete ORC's build only after successful import and snapshot export. A graph
   contribution failure is a build-coordination concern for ORC to record.
6. Query the library for graph data. Join it with descriptions, claims and
   execution evidence in ORC when assembling agent context.

Grain remains the persistence dependency. Applications can supply one physical
store while using distinct graph and ORC event domains; physical co-location
does not move orchestration into the graph project. A dedicated ontology store
can instead be supplied through the same context boundary.

## Existing data

No old mixed-event replay contract is promised. Existing installations should
choose an explicit data reset or a separately reviewed one-time data migration.
If data is retained, convert only graph-owned concepts, edges, schema, instances
and provenance into new contributions. Claims, traces, campaigns and description
history stay with ORC. Do not copy events solely because their name contains
`ontology`.

A reset or migration is an application deployment decision; the source extraction
itself does not erase or rewrite a running installation's data.

## Completion evidence

The standalone suite must exercise its actual public commands and queries on a
classpath without ORC. Required proofs cover tenant isolation, equal URIs in
separate graphs, retry identity versus source identity, stale revisions, atomic
validation, hierarchy inverses, canonical updates, schema/instance export,
replay and operation without optional search providers.

ORC separately needs an extraction integration test that executes its workflow,
submits the resulting contribution, and reads the graph through the library.
Retained ORC learning and execution tests must still pass. The two specifications
are checked together:

```sh
# From ORC:
allium check specs ../ontology-exp/specs
allium analyse specs ../ontology-exp/specs
```

Historical design reports explain the proposed cut; current source, specifications
and executed tests establish whether that cut is complete. No claim of completion
should rely only on a namespace rename or a successful standalone unit test.

## Positional graph identity

`import-contribution!`, `import-rdf!` and `search` now take
`[ctx ontology-id request]`. Move `:ontology-id` from the request map into the
second argument. The former two-argument signatures are no longer supported.
The positional identity controls graph selection, even if a request still
contains `:ontology-id`. Stored contribution input continues to include the
ontology ID, preserving its history format.

Relationship creation retains both qualified endpoints in its edge map.
`get-concepts` retains its filter map for tenant-wide and multi-ontology reads.

## Concept identity in read results

Concept records now expose their UUID as `:concept-id`, matching creation and
update results. Replace `(:id concept)` with `(:concept-id concept)` for lookup,
listing, concept maps, search and snapshot exports, including historical snapshots.
The public records no longer contain the `:id` alias. URI lookup and UUID-based
updates retain their existing signatures. Internal records, contribution deltas
and indexes also use `:concept-id`. There is no compatibility translation for
stored concept records using the former `:id` field.

## Entity identity and optional revision preconditions

Ontology, concept and relationship creation generate entity UUIDs independently
of `:command/id`. Creation retries recover the original entity ID. Ontology
creation retry keys are tenant-scoped; mutations of existing graphs retain
ontology-scoped retry keys.

`:expected-revision` is optional for structured and RDF imports. Omit it to
validate against current graph state, or supply it to reject work based on a
stale caller revision. The append CAS remains active in both cases. Concurrent
attempts to create an ontology with the same retry key can commit at most once;
independent creation keys do not conflict merely because they share a tenant.

## Manual command request options

Manual commands accept `:request-id` (an optional UUID) and `:expected-revision`
(an optional integer) in their request maps. A supplied request ID maps to the
internal command ID; it does not determine the created entity ID. Omitted request
IDs generate a new command identity per call. Imports retain `:contribution-id`
as their retry key and reject `:request-id`.

Updates now take `(update-concept! ctx ontology-id concept-id request)`, with
mutable fields under `:changes`. The separate `command-meta` arity is removed.
Predicate registration now takes `(register-predicate! ctx ontology-id request)`,
with `:predicate` and `:policy` in the request. Positional identities take
precedence over request-map identity fields. Command names, IDs and timestamps
are assembled internally; supplied `:command/*` name, ID and timestamp fields
cannot override them.

## Expected errors and missing resources

All public operations return Cognitect anomaly maps for expected failures.
Handle `:cognitect.anomalies/category` before consuming a result, including
boolean and collection operations. Invalid inputs return `incorrect`; missing
tenant context returns `forbidden`. Missing single-resource lookups and revision
lookups return `nil`. Explicitly scoped collection reads, search, mutations and
exports return `not-found` for missing resources. Existing empty graphs still
return empty collections. Multi-graph queries fail if any selected graph is
missing. Unexpected implementation exceptions propagate; RDF parsing does not
reclassify storage failures or implementation faults as invalid input.

## Concept-map filters

`get-concept-map` now accepts `[ctx filters]` as well as `[ctx]`. Its filters
and errors match `get-concepts`: `:ontology-id` or `:ontology-ids`, plus optional
`:scope` and `:broader-uri`. Keys remain `[ontology-id uri]`. An empty selection
returns `{}`; a selection containing a missing ontology returns `not-found`.
