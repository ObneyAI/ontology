# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| in-memory | 10000 | 0 | projection-replay | 99.122 | 98.903 | 101.356 | 305.240 | 5 |
