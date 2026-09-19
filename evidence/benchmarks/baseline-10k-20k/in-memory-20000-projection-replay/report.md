# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| in-memory | 20000 | 0 | projection-replay | 378.572 | 374.214 | 384.079 | 1190.584 | 14 |
