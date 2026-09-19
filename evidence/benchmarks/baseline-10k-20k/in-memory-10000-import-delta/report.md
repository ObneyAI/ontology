# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| in-memory | 10000 | 0 | import-delta | 1192.576 | 1181.076 | 1219.267 | 1670.355 | 28 |
