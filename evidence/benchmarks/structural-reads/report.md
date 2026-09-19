# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | lookup-uri | complete | 22943.41 | 0.73 | 0.71–1.28 | 0.32 |
| in-memory | 20000 | lookup-uri | complete | 89370.77 | 0.54 | 0.53–0.65 | 0.32 |
| sqlite | 10000 | lookup-uri | complete | 25226.00 | 0.91 | 0.78–0.99 | 0.33 |
| sqlite | 20000 | lookup-uri | complete | 99456.41 | 0.90 | 0.71–1.45 | 0.33 |

[Consolidated raw results and run metadata](results.edn). Per-case generated files are retained locally under ignored `target/evidence-archive/`.
