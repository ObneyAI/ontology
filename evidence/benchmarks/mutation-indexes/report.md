# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | lookup-uri | complete | 2468.53 | 0.63 | 0.61–0.69 | 0.32 |
| in-memory | 10000 | import-delta | complete | 2480.65 | 94.49 | 91.41–99.64 | 117.35 |
| in-memory | 10000 | import-resolved | complete | 2456.87 | 94.39 | 92.56–97.60 | 115.93 |
| in-memory | 20000 | lookup-uri | complete | 5686.08 | 0.58 | 0.54–0.66 | 0.32 |
| in-memory | 20000 | import-delta | complete | 5625.40 | 197.24 | 196.08–216.49 | 249.99 |
| in-memory | 20000 | import-resolved | complete | 6064.83 | 209.06 | 203.49–228.81 | 250.05 |
| sqlite | 10000 | lookup-uri | complete | 3624.19 | 0.88 | 0.86–0.92 | 0.34 |
| sqlite | 10000 | import-delta | complete | 3645.11 | 139.66 | 138.63–160.75 | 117.28 |
| sqlite | 10000 | import-resolved | complete | 3583.05 | 126.93 | 122.94–128.13 | 116.30 |
| sqlite | 20000 | lookup-uri | complete | 8536.86 | 0.70 | 0.66–0.78 | 0.33 |
| sqlite | 20000 | import-delta | complete | 8737.94 | 286.46 | 282.94–311.77 | 250.18 |
| sqlite | 20000 | import-resolved | complete | 8561.65 | 311.04 | 306.44–320.02 | 249.34 |

[Consolidated raw results and run metadata](results.edn). Per-case generated files are retained locally under ignored `target/evidence-archive/`.
