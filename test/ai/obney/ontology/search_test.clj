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
        (is (= (o/search ctx oid opts)
               (o/search ctx oid (assoc opts :weights {:lexical 1.0 :graph 1.0 :embedding 1.0}))))
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

(deftest graph-search-honors-large-limits-and-deep-traversal
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          seeds (mapv #(format "urn:seed:%03d" %) (range 105))
          chain (mapv #(str "urn:chain:" %) (range 70))]
      (h/ok! (o/import-contribution! ctx oid
               (assoc (h/request ctx oid (mapv h/concept (concat seeds chain)))
                      :relationships (mapv (fn [[a b]] {:subject a :object b :predicate "skos:broader"})
                                           (partition 2 1 chain)))))
      (is (= (subvec seeds 0 10) (mapv :uri (o/search ctx oid {:seed-uris seeds}))))
      (is (= (set (take 3 chain))
             (set (map :uri (o/search ctx oid {:seed-uris [(first chain)]})))))
      (is (= seeds (mapv :uri (o/search ctx oid {:seed-uris (reverse seeds) :max-depth 0 :limit 105}))))
      (is (= (subvec seeds 0 3) (mapv :uri (o/search ctx oid {:seed-uris seeds :max-depth 0 :limit 3}))))
      (doseq [depth [0 4 6 69]]
        (let [results (o/search ctx oid {:seed-uris [(first chain)] :max-depth depth :limit 100})]
          (is (= (set (take (inc depth) chain)) (set (map :uri results)))))))))

(deftest bm25-scopes-term-statistics-and-preserves-the-overlap-default
  (h/with-context [ctx]
    (let [oid (h/graph ctx) other (h/graph ctx)
          rare {:query-text "COMMON saffron" :lexical-method :bm25}
          uris #(mapv :uri (o/search ctx oid %))]
      (is (= [] (uris rare)))
      (doseq [[uri text] [["urn:a" "common"] ["urn:b" "common"]
                          ["urn:c" "common"] ["urn:d" "common"] ["urn:e" "common"]
                          ["urn:y" "saffron"] ["urn:z" "saffron"]]]
        (h/ok! (o/create-concept! ctx oid (assoc (h/concept uri) :label text :description "entry"))))
      (let [expected ["urn:y" "urn:z" "urn:a" "urn:b" "urn:c" "urn:d" "urn:e"]]
        (is (= expected (uris rare)))
        (doseq [i (range 8)]
          (h/ok! (o/create-concept! ctx other (assoc (h/concept (str "urn:other:" i)) :label "saffron"))))
        (is (= expected (uris rare)))
        (is (= ["urn:y"] (uris (assoc rare :limit 1)))))
      (let [default (uris (dissoc rare :lexical-method))]
        (is (= (sort default) default))
        (is (= default (uris (assoc rare :lexical-method :overlap)))))
      (is (= [] (uris (assoc rare :query-text "unmatched"))))
      (is (h/rejected? #(o/search ctx oid (assoc rare :lexical-method :unknown)))))))

(deftest bm25-uses-term-frequency-and-document-length
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [[uri description] [["urn:a" "red blue blue blue"]
                                 ["urn:b" "red red blue blue"]
                                 ["urn:c" "red blue blue blue blue blue blue blue"]]]
        (h/ok! (o/create-concept! ctx oid (assoc (h/concept uri) :label "entry" :description description))))
      (is (= ["urn:b" "urn:a" "urn:c"]
             (mapv :uri (o/search ctx oid {:query-text "Red" :lexical-method :bm25})))))))
