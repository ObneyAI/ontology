# Developing Ontology

Run commands from the repository root. The test suite uses the Clojure CLI. Repository linting uses Babashka (`bb`).

## Everyday checks

Install the pre-commit hook once per checkout with `./scripts/install-hooks.sh`. The hook lints the staged files. Run these commands to check the working tree and execute the test suite.

```sh
./scripts/lint.sh
clojure -M:test
```

To run one namespace, pass its name, for example `clojure -M:test ai.obney.ontology.rdf-test`. The suite covers the public API, graph invariants, SQLite persistence, replay, and concurrent writes.

## Verify a behavior change

Update the behavioral specification and its test mappings when a public contract changes. Run `python3 scripts/check_obligations.py` to check that the ledger covers the current obligations and names existing tests. [Behavioral specification](../specs/ontology.allium) · [Test ledger](../specs/test-coverage.json)

For full verification, run `bash scripts/verify-tests.sh` with Python 3, the Allium CLI, and the Clojure CLI on your path. It checks the specification and coverage checker, runs the suite and extended property tests, and audits mutations. It writes its results under `evidence/`.

## Measure performance

The storage benchmark runs each case in a fresh JVM against the in-memory and SQLite stores. Choose a new output directory for each run.

```sh
python3 scripts/benchmark.py --output target/benchmarks/my-baseline
```

To profile contribution latency for one store and graph size, run the profiler directly.

```sh
clojure -M:bench:profile --sizes 10000 --backends sqlite --samples 5 --warmup 2 --output target/profile/sqlite-10000
```

For retrieval changes, the LongMemEval-S runbook explains how to measure annotated-turn coverage before reranking and how to compare reader accuracy and inference cost. [Memory benchmark](../bench/memory/README.md)
