(ns ai.obney.ontology.algorithms-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.ontology.core.graph :as graph]
            [ai.obney.ontology.core.graph-evolver :as graph-evolver]
            [ai.obney.ontology.core.source-registry :as source-registry]))

(deftest union-find-builds-transitive-equivalence-classes
  (let [uf (entity-resolver/make-union-find)]
    (entity-resolver/union! uf "a" "b") (entity-resolver/union! uf "b" "c")
    (is (= (entity-resolver/find-root uf "a") (entity-resolver/find-root uf "c")))
    (is (not= (entity-resolver/find-root uf "a") (entity-resolver/find-root uf "d"))))
  (let [groups (entity-resolver/group-matched-uris [{:source1-uri "a" :source2-uri "b"}
                                     {:source1-uri "b" :source2-uri "c"}
                                     {:source1-uri "d" :source2-uri "e"}])
        mappings (entity-resolver/compute-canonical-uris groups :existing-uris #{"c"})]
    (is (= #{#{"a" "b" "c"} #{"d" "e"}} (set (map set groups))))
    (is (= {"a" "c" "b" "c" "c" "c" "d" "d" "e" "d"} mappings))
    (is (= #{["a" "owl:sameAs" "c"] ["b" "owl:sameAs" "c"] ["e" "owl:sameAs" "d"]}
           (set (entity-resolver/generate-owl-sameAs-triples mappings))))))

(deftest canonical-rewriting-preserves-relationship-predicates
  (let [mapping {"old:a" "new:a" "old:b" "new:b"}
        c {:uri "old:a" :label "A" :relationships [{:predicate "app:p" :object "old:b"}]}
        r {:subject "old:a" :predicate "app:p" :object "old:b"}
        result (graph-evolver/apply-canonical-mappings [c] [r] mapping)]
    (is (= "new:a" (graph-evolver/apply-canonical-uri mapping "old:a")))
    (is (= "absent" (graph-evolver/apply-canonical-uri mapping "absent")))
    (is (= "old:a" (get-in result [:concepts 0 :original-uri])))
    (is (= "new:b" (get-in result [:concepts 0 :relationships 0 :object])))
    (is (= [{:subject "new:a" :predicate "app:p" :object "new:b"}] (:relationships result)))))

(deftest concept-and-relationship-deduplication-preserve-information
  (let [r {:subject "a" :predicate "app:p" :object "b"}
        cs [{:uri "a" :definition "short" :confidence 0.2 :alt-labels ["A"] :relationships [r]}
            {:uri "a" :definition "a longer definition" :confidence 0.9 :alt-labels ["A" "Alias"] :relationships [r]}]
        merged (first (graph-evolver/deduplicate-concepts cs))]
    (is (= "a longer definition" (:definition merged)))
    (is (= 0.9 (:confidence merged)))
    (is (= #{"A" "Alias"} (set (:alt-labels merged))))
    (is (= 2 (count (:alt-labels merged))))
    (is (= [r] (:relationships merged)))
    (is (= [(assoc r :confidence 0.9)]
           (graph-evolver/deduplicate-relationships [(assoc r :confidence 0.1) (assoc r :confidence 0.9)])))
    (let [result (graph-evolver/merge-into-graph {:concepts {"a" (first cs)} :relationships [r]}
                                   [{:uri "b" :label "B"} (second cs)] [r])]
      (is (= 1 (:concepts-added result)))
      (is (= 1 (:concepts-merged result)))
      (is (= #{"a" "b"} (set (keys (:concepts result)))))
      (is (= 1 (count (:relationships result)))))))

(deftest schema-extension-detection-keeps-existing-elements
  (let [cs [{:entity-type "Living Thing"} {:entity-type "Living Thing"} {:entity-type "Place"}]
        rs [{:predicate "app:located"} {:predicate "app:known"}]
        result (graph-evolver/detect-schema-extensions cs rs {:classes #{"class:Place"} :properties #{"app:known"}})]
    (is (= [{:uri "class:LivingThing" :label "Living Thing"}] (:new-classes result)))
    (is (= [{:uri "app:located" :label "located"}] (:new-object-properties result)))
    (is (empty? (:new-datatype-properties result)))))

(deftest turtle-parsers-and-neighborhood-algorithms
  (let [g (graph/parse-turtle-multiline "@prefix app: <urn:app:> .\napp:a app:edge app:b ;\n app:other app:c .\napp:a app:label \"Alpha\"@en ." {"app:edge" 0.8})
        simple (graph/parse-turtle-graph "<urn:a> <urn:p> <urn:b> .")]
    (is (= "urn:app:" (get-in g [:prefixes "app"])))
    (is (= #{"app:b" "app:c"} (set (map :to (get-in g [:edges "app:a"])))))
    (is (= 3 (:triple-count g)))
    (is (= #{"urn:a" "urn:b"} (:nodes simple))))
  (let [g (graph/concepts->graph {"a" {:broader #{"b"}} "b" {:broader #{"c"}} "c" {}})]
    (is (= #{"a" "b" "c"} (:nodes g)))
    (doseq [results [(graph/link-expansion g ["a"] :hops 1)
                     (graph/quick-search g ["a"] :max-depth 1)
                     (graph/expand-from-concept g "a" :max-depth 1)]]
      (is (= #{"a" "b"} (set (map :uri results)))))))

(deftest rank-fusion-and-temporal-scoring
  (let [batches [[{:uri "a" :score 3} {:uri "b" :score 1}]
                 [{:uri "b" :score 3} {:uri "c" :score 1}]]
        fused (graph/compute-rrf-scores batches)
        merged (graph/merge-batches batches)
        weighted (graph/merge-batches batches :method "weighted-sum" :weights [1 2])]
    (is (= "b" (ffirst fused)))
    (is (< (Math/abs (- (+ (/ 1.0 61) (/ 1.0 62)) (second (first fused)))) 1.0e-12))
    (is (= 2 (:sources (first merged))))
    (is (= {:uri "b" :score 7.0 :sources 1} (first weighted))))
  (is (= [0.5 1.0 1.1 0.1] (mapv #(graph/compute-temporal-relevance % 2026) [nil 2026 2027 1900])))
  (is (= 1.0 (:score (first (graph/apply-temporal-scoring [{:uri "a" :score 2.0}] {} 2026))))))

(deftest source-content-and-identity-utilities
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (source-registry/sha256 "abc")))
  (is (= 2 (source-registry/get-file-size nil "λ")))
  (is (= "sql" (source-registry/detect-source-type "jdbc:sqlite:test")))
  (is (= "rdf" (source-registry/detect-source-type "ONTOLOGY.OWL")))
  (is (nil? (source-registry/detect-source-type nil)))
  (is (uuid? (source-registry/generate-source-id "hash")))
  (let [file (java.io.File/createTempFile "ontology-hash-" ".txt")]
    (try (spit file "λ" :encoding "UTF-8")
         (is (= (source-registry/sha256 "λ") (source-registry/compute-content-hash (.getAbsolutePath file) nil)))
         (is (= 2 (source-registry/get-file-size (.getAbsolutePath file) nil)))
         (java.nio.file.Files/write (.toPath file) (byte-array [(byte -1) 0])
                                    (make-array java.nio.file.OpenOption 0))
         (is (= "ea5dbf9596d187e9500f23e9a680109475341cf4e81f7e043f7d97152c10772f" (source-registry/compute-content-hash (.getAbsolutePath file) nil)))
         (finally (.delete file)))))
