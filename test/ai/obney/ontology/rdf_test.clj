(ns ai.obney.ontology.rdf-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.algorithms :as a]
            [ai.obney.ontology.support :as h]))

(defn import! [ctx oid content]
  (o/import-rdf! ctx (assoc (dissoc (h/request ctx oid []) :concepts) :content content)))

(deftest malformed-or-unsupported-rdf-is-rejected-atomically
  (h/with-context [ctx]
    (let [oid (h/graph ctx) before (h/capture ctx oid)]
      (doseq [line ["_:blank <urn:p> <urn:o> ." "<urn:s> <urn:p> _:blank ."
                    "@prefix a: <urn:a:> ." "<urn:s> <urn:p> \"unterminated ."
                    "<urn:s> <urn:p> \"bad\\q\" ." "<urn:s> <urn:p> <urn:o>"
                    "<urn:s> <urn:p> \"bad\"@en^^<urn:t> ."
                    "<urn:s> <urn:p> \"bad\\uD800\" ."
                    "<urn:bad space> <urn:p> \"bad\" ."
                    "<urn:s> <urn:p> \"bad\"@123 ."]]
        (is (h/anomaly? (import! ctx oid line)) line)
        (is (= before (h/capture ctx oid)) line)))))

(deftest literals-survive-unicode-escapes-and-control-characters
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          lines ["<urn:s> <urn:p> \"line\\nquote\\\"slash\\\\tab\\t\" ."
                 "<urn:s> <urn:q> \"\\u03BB \\U0001F333\"@el ."
                 "<urn:s> <urn:r> \"42\"^^<http://www.w3.org/2001/XMLSchema#integer> ."]
          content (str/join "\n" lines)]
      (h/ok! (import! ctx oid content))
      (let [snapshot (o/export-snapshot ctx oid) parsed (a/parse-ntriples (:ttl-output snapshot))
            raw (set (a/parse-ntriples content))]
        (is (every? (set parsed) raw))
        (is (= (:triple-count snapshot) (count parsed)))
        (is (= (:checksum snapshot) (a/sha256 (:ttl-output snapshot))))
        (is (= #{"line\nquote\"slash\\tab\t" "λ 🌳" "42"}
               (set (keep :literal raw))))))))

(deftest complete-schema-and-instance-statements-survive-export
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          properties (into {} (map #(vector (str "urn:field:" %) (str "v" %)) (range 16)))
          input (assoc (h/request ctx oid [])
                       :tbox {:classes [{:uri "urn:C" :label "Class"}]
                              :object-properties [{:uri "urn:parent" :domain "urn:C" :range "urn:C"}]
                              :datatype-properties [{:uri "urn:age" :domain "urn:C" :datatype "xsd:integer"}]}
                       :abox [{:uri "urn:i" :type "urn:C" :properties properties}])]
      (h/ok! (o/import-contribution! ctx input))
      (let [snapshot (o/export-snapshot ctx oid) triples (a/parse-ntriples (:ttl-output snapshot))]
        (doseq [[p v] properties]
          (is (some #(and (= "urn:i" (:subject %)) (= p (:predicate %)) (= v (:literal %))) triples)))
        (is (some #(and (= "urn:parent" (:subject %)) (= "urn:C" (:object-uri %))) triples))
        (is (= (:triple-count snapshot) (count triples)))))))

(deftest raw-triple-contributions-are-validated-before-commit
  (h/with-context [ctx]
    (let [oid (h/graph ctx) before (h/capture ctx oid)]
      (doseq [triple [{:subject "urn:s" :predicate "urn:p"}
                      {:predicate "urn:p" :literal "x"}
                      {:subject "urn:s" :predicate "urn:p" :object-uri "urn:o" :literal "both"}
                      {:subject "urn:s" :predicate "urn:p" :literal 42}
                      {:subject "urn:s" :predicate "urn:p" :literal "x" :language "en" :datatype "xsd:string"}]]
        (is (h/anomaly? (o/import-contribution! ctx (assoc (h/request ctx oid []) :rdf-triples [triple]))))
        (is (= before (h/capture ctx oid)))))))

(deftest export-rejects-nonexistent-revisions
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [revision [-1 0 2 0.5]] (is (h/rejected? #(o/export-snapshot ctx oid revision))))
      (is (h/rejected? #(o/export-snapshot ctx (random-uuid))))
      (is (= 1 (:revision (o/export-snapshot ctx oid 1)))))))

(deftest exported-schema-and-individuals-can-be-imported-again
  (h/with-context [ctx]
    (let [oid (h/graph ctx) copy (h/graph ctx)
          request (assoc (h/request ctx oid [])
                         :tbox {:classes [{:uri "urn:C" :label "Class"}]
                                :object-properties [{:uri "urn:p" :domain "urn:C" :range "urn:C"}]
                                :datatype-properties [{:uri "urn:age" :domain "urn:C" :datatype "xsd:integer"}]}
                         :abox [{:uri "urn:i" :type "urn:C" :properties {"urn:age" "42"}}])]
      (h/ok! (o/import-contribution! ctx request))
      (let [exported (:ttl-output (o/export-snapshot ctx oid))
            result (import! ctx copy exported)]
        (is (not (h/anomaly? result)) (pr-str result))
        (when-not (h/anomaly? result)
          (is (every? (set (a/parse-ntriples (:ttl-output (o/export-snapshot ctx copy))))
                      (a/parse-ntriples exported))))))))
