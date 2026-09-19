(ns ai.obney.ontology.search-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.support :as h]))

(deftest graph-search-obeys-depth-and-scoped-seeds
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)]
      (doseq [uri ["urn:a" "urn:b" "urn:c" "urn:d"]] (h/add! ctx oid uri))
      (h/add! ctx other "urn:foreign")
      (doseq [[a b] [["urn:a" "urn:b"] ["urn:b" "urn:c"] ["urn:c" "urn:d"]]]
        (h/ok! (o/create-relationship! ctx {:source-ontology-id oid :target-ontology-id oid :source-uri a :target-uri b :predicate "skos:broader"})))
      (doseq [[depth expected] [[0 #{"urn:a"}] [1 #{"urn:a" "urn:b"}] [2 #{"urn:a" "urn:b" "urn:c"}]]]
        (let [result (o/search ctx oid {:seed-uris ["urn:a" "urn:foreign" "urn:absent"] :max-depth depth :limit 20})]
          (is (= expected (set (map :uri result))))
          (is (every? #(= oid (:ontology-id %)) result))))
      (is (empty? (o/search ctx oid {:seed-uris ["urn:foreign"]}))))))

(deftest lexical-search-is-case-insensitive-and-stable-on-ties
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [uri ["urn:z" "urn:a" "urn:m"]]
        (h/ok! (o/create-concept! ctx oid (assoc (h/concept uri) :label "Café TREE" :description "Green"))))
      (is (= ["urn:a" "urn:m" "urn:z"] (mapv :uri (o/search ctx oid {:query-text "CAFÉ, tree!"}))))
      (is (= ["urn:a"] (mapv :uri (o/search ctx oid {:query-text "green" :limit 1}))))
      (is (empty? (o/search ctx oid {:query-text "  "}))))))

(deftest weighted-fusion-controls-ranking
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [[uri label vector] [["urn:lexical" "needle" [0.0 1.0]] ["urn:vector" "haystack" [1.0 0.0]]]]
        (h/ok! (o/create-concept! ctx oid (assoc (h/concept uri) :label label :embedding vector :embedding-model "fixture"))))
      (let [opts {:query-text "needle" :embedding-model "fixture" :embed-query (constantly [1.0 0.0])}]
        (is (= "urn:lexical" (:uri (first (o/search ctx oid (assoc opts :weights {:lexical 2.0 :embedding 0.1}))))))
        (is (= "urn:vector" (:uri (first (o/search ctx oid (assoc opts :weights {:lexical 0.0 :embedding 1.0}))))))
        (is (every? #(Double/isFinite (:score %)) (o/search ctx oid opts)))))))

(deftest invalid-search-arguments-are-rejected
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (is (h/rejected? #(o/search ctx nil {:query-text "x"})))
      (doseq [bad [{:limit 0} {:limit -1} {:limit 1.5} {:max-depth -1}
                  {:max-depth 1.5} {:weights {:lexical -1}} {:weights {:embedding ##NaN}}]]
        (is (h/rejected? #(o/search ctx oid (merge {:query-text "x"} bad))) (pr-str bad))))))

(deftest unusable-vectors-do-not-participate-in-ranking
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [[i vector] (map-indexed vector [[] [0.0 0.0] [1.0] [##NaN 0.0] [##Inf 0.0] ["bad" 0]])]
        (h/ok! (o/create-concept! ctx oid (assoc (h/concept (str "urn:bad:" i)) :embedding vector :embedding-model "fixture"))))
      (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:good") :embedding [1.0 0.0] :embedding-model "fixture")))
      (let [opts {:query-text "unmatched" :embedding-model "fixture" :embed-query (constantly [1.0 0.0])}]
        (is (= ["urn:good"] (mapv :uri (o/search ctx oid opts))))
        (doseq [vector [[] [0.0 0.0] [##NaN 1.0] [##Inf 1.0]]]
          (is (empty? (o/search ctx oid (assoc opts :embed-query (constantly vector)))) (pr-str vector)))
        (is (= ["urn:bad:2"] (mapv :uri (o/search ctx oid (assoc opts :embed-query (constantly [1.0]))))))))))

(deftest finite-large-vectors-cannot-produce-nan-scores
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (h/ok! (o/create-concept! ctx oid (assoc (h/concept "urn:large") :embedding [1.0e300 1.0e300] :embedding-model "fixture")))
      (let [result (o/search ctx oid {:query-text "unmatched" :embedding-model "fixture" :embed-query (constantly [1.0e300 1.0e300])})]
        (is (= ["urn:large"] (mapv :uri result)))
        (is (every? #(Double/isFinite (:score %)) result))))))
