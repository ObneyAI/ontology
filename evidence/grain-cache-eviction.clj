(require '[ai.obney.grain.read-model-processor-v2.l1-cache :as l1])
;; Standalone dependency experiment; no ontology state or events involved.
(doseq [n [10002 20000]]
  (l1/clear!)
  (let [start (System/nanoTime)]
    (dotimes [i n] (l1/put-entry! (str i) {} nil 10000))
    (prn {:insertions n :capacity 10000 :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
          :cache (l1/stats)})))
