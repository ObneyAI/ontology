# Ontology

Ontology is a Clojure library for building and searching tenant-scoped concept graphs.
It stores changes in Grain’s event store so applications can recover the graph at a given revision or export it as RDF.

## Getting started

Add Ontology to your application’s `deps.edn`.

```clojure
{:deps {ai.obney/ontology
        {:git/url "https://github.com/ObneyAI/ontology.git"
         :git/sha "9670715a0ff798d9fe051925f547262a73563ad1"}}}
```

[Create and search your first graph](docs/usage.md) · [Public API](src/ai/obney/ontology/interface.clj) · [Development and testing](docs/development.md) · [Memory benchmark](bench/memory/README.md)
