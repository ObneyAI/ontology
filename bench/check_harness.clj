(require '[ai.obney.ontology.benchmark :as b]
         '[clojure.test :refer [deftest is run-tests]])

(deftest summary-statistics-and-sample-boundaries
  (is (= 3 (b/median [9 1 3])))
  (is (= 4.0 (b/median [9 1 3 5])))
  (let [calls (atom 0) observed (atom [])
        result (b/measure 2 3 #(swap! calls inc) #(swap! observed conj %))]
    (is (= 5 @calls))
    (is (= [1 2 3 4 5] @observed))
    (is (= 3 (count (:samples result))))
    (is (<= (:min-ms result) (:median-ms result) (:max-ms result)))
    (is (every? #(not (neg? (:elapsed-ms %))) (:samples result)))))

(deftest invalid-options-cannot-produce-empty-success
  (doseq [args [["--samples" "0"] ["--sizes" "0"] ["--operations" "typo"]
                ["--backends" "imaginary"] ["--warmup" "-1"] ["--sizes"]]]
    (is (thrown? Exception (b/parse-args args))))
  (is (= #{:lookup-uri} (:operations (b/parse-args ["--operations" "lookup-uri"]))))
  (is (= [10000 20000] (:sizes (b/parse-args [])))))

(deftest failed-results-do-not-become-timing-data
  (is (thrown? clojure.lang.ExceptionInfo
               (b/measure 0 1 (constantly {:cognitect.anomalies/category :failure}) b/ok!))))

(let [{:keys [fail error]} (run-tests)]
  (shutdown-agents)
  (System/exit (if (zero? (+ fail error)) 0 1)))
