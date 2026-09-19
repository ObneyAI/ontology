# Ontology benchmark results

Single client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.

| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| in-memory | 20 | 0 | projection-replay | 0.414 | 0.285 | 0.544 | 0.214 | 0 |
| in-memory | 20 | 0 | lookup-uri | 27.204 | 24.812 | 29.596 | 23.420 | 1 |
| in-memory | 20 | 0 | list-concepts | 22.906 | 22.768 | 23.044 | 23.419 | 0 |
| in-memory | 20 | 0 | search-lexical | 25.087 | 23.521 | 26.652 | 23.512 | 1 |
| in-memory | 20 | 0 | search-graph | 24.521 | 23.815 | 25.228 | 23.541 | 0 |
| in-memory | 20 | 0 | search-vector | 23.427 | 23.416 | 23.438 | 23.943 | 0 |
| in-memory | 20 | 0 | export-current | 26.849 | 25.477 | 28.221 | 25.243 | 1 |
| in-memory | 20 | 0 | export-first-import | 5.259 | 5.200 | 5.317 | 5.129 | 0 |
| in-memory | 20 | 0 | retry-contribution | 1.047 | 0.997 | 1.097 | 0.797 | 0 |
| in-memory | 20 | 0 | update-concept | 0.893 | 0.874 | 0.913 | 0.510 | 0 |
| in-memory | 20 | 0 | import-resolved | 2.454 | 2.371 | 2.536 | 1.686 | 0 |
| in-memory | 20 | 0 | import-delta | 2.481 | 2.477 | 2.486 | 2.251 | 0 |
| sqlite | 20 | 0 | projection-replay | 1.643 | 1.630 | 1.655 | 0.714 | 0 |
| sqlite | 20 | 0 | lookup-uri | 25.773 | 25.675 | 25.870 | 24.280 | 0 |
| sqlite | 20 | 0 | list-concepts | 23.484 | 23.477 | 23.492 | 24.279 | 0 |
| sqlite | 20 | 0 | search-lexical | 24.964 | 23.541 | 26.387 | 24.372 | 1 |
| sqlite | 20 | 0 | search-graph | 25.407 | 24.624 | 26.190 | 24.401 | 1 |
| sqlite | 20 | 0 | search-vector | 24.813 | 24.260 | 25.365 | 24.800 | 0 |
| sqlite | 20 | 0 | export-current | 26.126 | 25.715 | 26.536 | 26.119 | 0 |
| sqlite | 20 | 0 | export-first-import | 5.451 | 5.419 | 5.482 | 5.635 | 0 |
| sqlite | 20 | 0 | retry-contribution | 1.923 | 1.870 | 1.977 | 1.259 | 0 |
| sqlite | 20 | 0 | update-concept | 2.500 | 2.470 | 2.529 | 0.996 | 0 |
| sqlite | 20 | 0 | import-resolved | 6.909 | 6.787 | 7.032 | 2.558 | 0 |
| sqlite | 20 | 0 | import-delta | 8.138 | 7.274 | 9.001 | 3.953 | 1 |
