# Ontology benchmark campaign

Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.

**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.

| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| in-memory | 10000 | projection-replay | complete | 28886.57 | 261.12 | 258.81–261.57 | 871.52 |
