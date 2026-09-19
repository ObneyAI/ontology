# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| in-memory | 10000 | 10 | projection-replay | 261.121 | 258.806 | 261.566 | 871.521 | 12 |
