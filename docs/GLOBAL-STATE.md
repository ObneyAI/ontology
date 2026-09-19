# Global in-process state inventory

Inspected the current worktree on 2026-09-19, including uncommitted source files. “Global” means shared through a namespace/module root, a captured singleton, a registration side effect, or a process-wide runtime facility. Thread-local bindings and instance-owned state are distinguished below. No application behavior was changed.

## Ontology-owned globals

| Location | State and lifetime | Sharing / cleanup |
|---|---|---|
| `src/ai/obney/ontology/core/projection.clj:15`, `stores` | `defonce` Java `WeakHashMap`: event-store object → generated UUID. Written by `store-key` at line 17. | Shared across contexts; synchronized on the map. Keys use Java equality/hash semantics, not a dedicated identity map. Weak keys permit GC cleanup; there is no explicit reset API. Supplying `:ontology/store-id` bypasses the map. |
| `src/ai/obney/ontology/core/projection.clj:16`, `locks` | `defonce` vector of 128 shared Java monitor objects. | `project` chooses a stripe by hash of projection name (lines 66–73). Same-key projection calls serialize; unrelated keys can share a stripe. Lives for the namespace/classloader lifetime. |
| `src/ai/obney/ontology/core/trace.clj:6`, `*allocations?*` | Dynamic Var with root value `false`. | Profiling binds it per thread; the profiling writer wrapper explicitly propagates the binding. It is ambient configuration, not a shared mutable graph. |
| `src/ai/obney/ontology/core/projection.clj:11`, `no-l2` | One globally retained `KVStore` implementation. | Stateless singleton: reads return nil, writes do nothing, start/stop return self. Included as a singleton, not mutable storage. |

There is no ontology-owned top-level atom containing the graph. Graph state becomes globally retained through Grain's L1 cache described next. `core/source_registry.clj` is a collection of utility functions, not a global source registry.

## Global state accessed or populated through Grain

Dependency paths below are relative to the pinned Grain checkout at SHA `dbf5b5229aad09785e40f71e96adf65c90f971d5` (`deps.edn`). Each `components/<component>/src/` prefix is followed by the named namespace's normal Clojure source path.

| Owner and definition | Ontology entry point / contents | Lifetime and isolation |
|---|---|---|
| `read-model-processor-v2.l1-cache/cache*`, `l1_cache.clj:14` | `projection/project` → `rmp/p`. Atom of cache entries containing `:state`, `:watermark`, `:accessed-at`, `:validated-at`. | Shared by all Grain read models in the JVM/classloader. Stores graph records, indexes, metadata, requests, histories and point projections as they are read. Default capacity is 10,000 entries (not bytes); reads update LRU timestamps. `l1/clear!` / `rmp/l1-clear!` clear all models; `l1/invalidate!` removes one key. Store shutdown does not clear this cache. |
| `read-model-processor-v2.interface/read-model-registry*`, `interface.clj:139` | Seven `defreadmodel` declarations in `core/state.clj:143,160,162,164,168,195,197`: `:ontology.graph/state`, `metadata`, `policies`, `history`, `requests`, `concept`, `relationships`. | Global map of reducers/options/versions populated when namespace forms execute. `projection/project` explicitly reads the global registry at line 63. |
| `command-processor-v2.interface/command-registry*`, `interface.clj:4` | Six `defcommand` declarations in `core/commands.clj:101,112,125,141,156,268`: create ontology/concept/relationship, update concept, register predicate, import contribution. | Global handler/authorization registry. `process-command` uses it unless the caller provides `:command-registry` in context. |
| `schema-util.interface/registry*`, `interface.cljc:7` | `defschemas` in `core/schemas.clj:27,57` merges six command and six event schemas into this atom. | Process-wide schema names. Namespace initialization also calls `malli.registry/set-default-registry!` (line 19), installing a mutable registry backed by this atom as Malli's default. This affects other users of that default in the same process. |
| `event-store-v3.interface.event-definition/registry*`, `event_definition.clj:10` | Loaded through event-store interface; event-definition registration machinery. | Global event declarations, with `reset-event-definitions!` at line 125. Ontology's `defschemas events` does not itself populate this registry. |
| `event-model.interface/event-model-registry*`, `interface.clj:199` | Loaded transitively by Grain. | Global event-model declarations. Ontology declares no `defeventmodel`. |
| `event-store-v3.interface.protocol/start-event-store`, `protocol.clj:4` | Backend `defmulti`; methods registered on loading in-memory / SQLite implementations. | Mutable global dispatch table and method cache. |
| `pubsub.core/start-pubsub`, `core.cljc:5` | Loaded transitively by the event-store interface. | Mutable global backend dispatch table. |

For all Grain namespace names in this table, prepend `ai.obney.grain.`. Registry atoms defined with `def` can be recreated on explicit namespace reload; normal `require` does not reset them. The event-definition registry uses `defonce`.

Projection cache keys include a SHA-256 digest of store ID, tenant ID, queries and options in the model name (`projection.clj:66`), plus Grain's model version and scoped key. This separates cached values but not memory, eviction, or monitor contention. Reusing an explicit store ID across distinct stores can defeat the intended store separation. `:l1-ttl-ms 0` means revalidate on each read; it does **not** disable L1. `no-l2` disables storage in L2 only. Collecting a weak store-map key does not evict its L1 entries.

## Logging, clocks and dependency runtime state

These are dependency-owned process facilities reached by the application, not tenant data stores. Jar paths and source locations are recorded by the runtime inventory where visible as Var roots.

| Dependency | Shared state |
|---|---|
| μ/log 0.9.0, `com/brunobonacci/mulog/core.clj:30,46,55,109,142` | `*default-logger*` ring-buffer atom (capacity 1,000), `global-context` atom, `local-context` ThreadLocal, `publishers` atom and delayed `dispatch-publishers` recurring task. All ontology `trace/span` calls use this infrastructure. Thread-local context contents are per thread. |
| μ/log `buffer.clj:111`, `publisher.clj:196` | Delayed global scheduler `timer-pool` and mutable `publisher-factory` multimethod. `profile/collector` registers its collector with μ/log and returns a stop function. Per-collector buffers/events are retained through this global registration until stopped. |
| clj-uuid 0.2.0, `clj_uuid/clock.clj:57,101` | Atoms captured by globally defined monotonic clock functions; these are hidden from a direct Var-root atom scan. Used by time-ordered UUID generation in Grain. |
| clj-uuid `random.clj:21`, `node.clj:22,144,152,163` | Delayed `SecureRandom`, sampled clock sequence, delayed node ID, memoized v1/v6 least-significant-bit functions. Some are only exercised by particular UUID versions. Java `UUID/randomUUID` used by ontology also relies on Java's shared randomness infrastructure. |
| Malli 0.17.0, `registry.cljc:40,73`, `core.cljc:2739` | Default registry atom, dynamically bindable registry, function-schema registry atom. `core.cljc:2581` also captures a memoized evaluator in its global evaluation function. |
| Tick, `tick/core.cljc:101,53` | Dynamic `*clock*` and `*time-literals-printing*`; Grain `time/now` uses Tick's ambient clock. |
| Clojure / core.async | Global agent/future executors, async executor/timer facilities, namespace and protocol registries, multimethod caches and dynamic I/O/compiler/reader settings. Application entry points call `shutdown-agents`, affecting the JVM's shared agent executors. |

The machine-readable [Var-root inventory](../evidence/global-state-vars.edn) also identifies loaded spec/analyzer/reader/printing infrastructure and `primitive-math/hijacked?`. Ordinary function/schema/constant Vars and protocol implementation tables are global roots too; they do not represent evolving ontology data. JVM/library internals (for example logging ID generation, JIT caches, random generators and native driver internals) are runtime infrastructure, not an exhaustive heap/static-field census in this report.

## Profiling, tests and scripts

| Location | Process-wide effect |
|---|---|
| `bench/ai/obney/ontology/profile.clj:108` | `with-grain-boundaries` temporarily redefines `rmp/p`, `es/append`, `es/->event` via `with-redefs`. All threads observe these root changes while active; restoration happens on exit. |
| `test/.../structural_test.clj:32,137`; `boundary_test.clj:212`; `durable_test.clj:87`; `indexes_test.clj:61,71,145` | Temporary root replacements for projection, graph validation, parent lookup/replacement, resolution selection and read-state calls. Test-local atoms are counters, not additional singleton stores. |
| `scripts/mutation_audit.clj:14`; `evidence/concept-view-review.clj:19` | Temporary function-root replacements. |
| `evidence/grain-cache-eviction.clj:3` | Directly exercises and clears Grain's shared L1 cache. Run as an isolated probe. |
| `test/.../property_test.clj:10,11` | `seed` and `trials` snapshot environment variables at namespace load; immutable values but process-lifetime configuration. |
| `scripts/audit_coverage_checker.py:12` onward | Module-scope audit execution and mutable module bindings, including `checker.ROOT` / `checker.SPEC` reassignment to a temporary checkout, `baseline`, `ledger`, `outcomes`. `contextlib.redirect_stdout` temporarily replaces process-global Python `sys.stdout`. |
| `scripts/benchmark.py:15` | Module-global `OPERATIONS` list, used as configuration without mutation in this file. Other Python root path constants are immutable configuration. |

`bench/benchmark.clj` captures counters in each workload closure (`revision`, `last-input`, `next-id`, `command-index`, `write-revision`) and a run-local `rows` atom. `profile/collector` creates an events atom, promise and agent buffer per collector. These are instance/run-owned, with the publisher registration bridge noted above.

## Mutable state that is not global

* `core/entity_resolver.clj:131`: `make-union-find` returns a fresh atom to its caller. `find-root` and `union!` mutate that instance; no singleton union-find exists.
* `core/graph.clj:64,230,370,417,490,581`: atoms for graph building, parsing, traversal and score merging are allocated inside function calls. They are not retained in a namespace root.
* Event-store contexts own their stores. Grain's in-memory backend creates a fresh `ref` in `core/in_memory.clj:12`; stopping it resets that instance. SQLite's connection/pool/write coordinator and thread counter belong to its store instance (`event_store_sqlite_v3/core.clj`, coordinator construction around line 130), not an ontology singleton.
* Optional `ctx :cache` is supplied by the host. The host controls whether it shares that instance; ontology supplies only the stateless `no-l2` fallback.
* Reducer results and indexes are persistent values passed between functions. Their long-term in-process retention occurs in L1 (or host resources), not a separate index singleton.
* MessageDigest instances are constructed per call. Constant maps/sets/vectors, regex patterns, function aliases and schema forms are not evolving application state.

## Verification and reproduction

Reviewed declarations, mutation/registration sites and scope in all 15 production Clojure files, benchmark/test/support code, executable evidence probes and repository scripts. Followed the registration macros into the actual pinned dependency source, including Malli's default-registry side effect, and inspected the cache implementation rather than inferring behavior from its name. No AGENTS.md was present in the repository scan.

Ran [scripts/inspect_global_state.clj](../scripts/inspect_global_state.clj) in a fresh JVM with the current test classpath, loading the public ontology API and profiling namespace. It recorded 118 mutable/dynamic/delayed/dispatch Var roots, including transitive runtime infrastructure:

```sh
clojure -Sdeps '{:aliases {:inspect {:main-opts ["scripts/inspect_global_state.clj"]}}}' -M:test:inspect
```

This probe records names, classes and source locations, not user data; it does not force delays. It is supporting evidence, not a complete heap walker: the lock vector, atoms captured in functions, Java statics, protocol dispatch caches, and state in namespaces loaded only later require source inspection. Source review supplies the repository-level inventory and the directly relevant dependency state above. Tests were not rerun because this is an inventory-only change.
