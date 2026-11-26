#!/usr/bin/env bb

(ns ontology-lint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(load-file "scripts/lint.bb")

(def graph-file "src/ai/obney/ontology/core/graph.clj")

(defn findings [file source]
  (mapcat #(% file source) (ontology-lint/checks file)))

(deftest rejects-workshop-violations-in-the-ontology-layout
  (doseq [[file source message]
          [[graph-file "; explanation" "Code comments"]
           ["test/sample.clj" "(clojure.core/declare a)" "declare is banned"]
           ["bench/sample.clj" "(requiring-resolve 'a/b)" "requiring-resolve is banned"]
           ["scripts/tool.py" (str/join " " ["DELETE" "FROM" "events"]) "SQL DELETE"]
           [graph-file "(defreadmodel :graph next {:version 1} [s e] s)" "must be declared"]
           ["src/duplicate/commands.clj" "(ns duplicate.commands)" "canonical file"]
           [graph-file "(ns x (:require [ai.obney.grain.event-store-v3.interface :as es])) (es/read ctx q)" "Grain runtime"]
           ["src/ai/obney/ontology/core/state.clj" "(defreadmodel :graph next {:version 2} [s e] s)" "version 1"]
           [graph-file "{:grain/read-models {:graph/state 2}}" "version 1"]
           [graph-file "(defschema example [:map [:attributes {:optional true} :any]])" "concrete Malli"]]]
    (is (some #(str/includes? (:message %) message) (findings file source)) (str file " " source))))

(deftest accepts-data-and-java-imports
  (is (empty? (findings graph-file
                "(ns graph (:import [java.io File] [java.security DigestInputStream MessageDigest]))")))
  (is (empty? (findings graph-file ";; --- Query helpers ---\n(defn lookup \"Read a concept.\" [] 'declare)")))
  (is (empty? (findings graph-file "(def schema [:map [:any-checked? :boolean]])"))))

(let [{:keys [fail error]} (run-tests 'ontology-lint-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
