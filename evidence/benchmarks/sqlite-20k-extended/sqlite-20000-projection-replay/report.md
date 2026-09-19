# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| sqlite | 20000 | 0 | projection-replay | 2126.219 | 2122.925 | 2185.512 | 3478.527 | 82 |
