(ns ai.obney.ontology.test-runner
  (:require [clojure.test :as test]
            [clojure.java.io :as io]))

(defn suites []
  (->> (file-seq (io/file "test/ai/obney/ontology"))
       (filter #(and (.isFile %) (.endsWith (.getName %) "_test.clj")))
       (map (fn [file] (with-open [r (java.io.PushbackReader. (io/reader file))] (second (read r)))))
       sort vec))
(defn -main [& names]
  (let [selected (if (seq names) (mapv symbol names) (suites))]
    (when (empty? selected) (throw (ex-info "No test suites found" {})))
    (doseq [suite selected] (require suite))
    (let [{:keys [fail error]} (apply test/run-tests selected)]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
