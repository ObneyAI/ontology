# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 100 | lookup-uri | complete | 51.52 | 708.44 | 700.00–711.60 | 942.05 |
| in-memory | 1000 | lookup-uri | timeout | 417.48 | — | —–— | — |
