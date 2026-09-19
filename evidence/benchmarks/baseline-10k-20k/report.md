# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | projection-replay | complete | 16265.35 | 99.12 | 98.90–101.36 | 305.24 |
| in-memory | 10000 | lookup-uri | timeout | 16587.82 | — | —–— | — |
| in-memory | 10000 | import-delta | complete | 15692.83 | 1192.58 | 1181.08–1219.27 | 1670.35 |
| in-memory | 20000 | projection-replay | complete | 66230.24 | 378.57 | 374.21–384.08 | 1190.58 |
| in-memory | 20000 | lookup-uri | timeout | 63740.87 | — | —–— | — |
| in-memory | 20000 | import-delta | complete | 63331.72 | 2905.61 | 2715.04–3029.89 | 4095.06 |
| sqlite | 10000 | projection-replay | complete | 19178.98 | 562.88 | 558.68–580.43 | 925.40 |
| sqlite | 10000 | lookup-uri | timeout | 18449.53 | — | —–— | — |
| sqlite | 10000 | import-delta | complete | 18982.33 | 2415.80 | 2252.30–2565.79 | 2457.38 |
| sqlite | 20000 | projection-replay | timeout | — | — | —–— | — |
| sqlite | 20000 | lookup-uri | timeout | — | — | —–— | — |
| sqlite | 20000 | import-delta | timeout | — | — | —–— | — |
