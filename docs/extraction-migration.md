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
4. Submit that data through `import-contribution!`, with a source content hash,
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
