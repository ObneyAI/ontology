# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | lookup-uri | complete | 15365.70 | 0.77 | 0.62–1.23 | 0.31 |
| in-memory | 10000 | import-delta | complete | 15323.60 | 254.34 | 253.63–260.09 | 339.15 |
| in-memory | 20000 | lookup-uri | complete | 69708.00 | 0.57 | 0.55–0.71 | 0.32 |
| in-memory | 20000 | import-delta | complete | 63258.31 | 560.14 | 554.30–588.23 | 733.88 |
| sqlite | 10000 | lookup-uri | complete | 16755.47 | 0.77 | 0.76–0.82 | 0.33 |
| sqlite | 10000 | import-delta | complete | 18298.23 | 289.46 | 278.84–293.66 | 335.64 |
| sqlite | 20000 | lookup-uri | complete | 68772.32 | 0.72 | 0.71–0.89 | 0.33 |
| sqlite | 20000 | import-delta | complete | 68467.33 | 695.98 | 692.20–716.25 | 724.91 |

[Consolidated raw results and run metadata](results.edn). Per-case generated files are retained locally under ignored `target/evidence-archive/`.
