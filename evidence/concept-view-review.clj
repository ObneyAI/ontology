(require '[ai.obney.ontology.interface :as o]
         '[ai.obney.ontology.core.state :as state])

(defn fixture [n other-n]
  (let [oid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
        other (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
        initial {:ontologies {oid {:predicate-policies state/default-predicates}
                              other {:predicate-policies state/default-predicates}}}
        add (fn [s [graph i]]
              (state/put-concept s graph {:uri (str "urn:" i) :label (str i) :description "D"
                                          :broader (if (zero? i) [] [(str "urn:" (quot (dec i) 2))])}))]
    [oid (reduce add initial (concat (map #(vector oid %) (range n))
                                    (map #(vector other %) (range other-n))))]))

(defn inspect-case [n other-n query]
  (let [[oid s] (fixture n other-n)
        relationships state/relationships concepts state/concepts printer pr-str
        counts (atom {:relationship-view-builds 0 :concepts-materialized 0 :pr-str-calls 0})
        result (with-redefs [state/read-state (constantly s)
                            state/relationships (fn [s oid] (swap! counts update :relationship-view-builds inc) (relationships s oid))
                            state/concepts (fn [s] (let [cs (concepts s)] (swap! counts update :concepts-materialized + (count cs)) cs))
                            clojure.core/pr-str (fn [& args] (swap! counts update :pr-str-calls inc) (apply printer args))]
                 (o/get-concept-by-uri {:tenant-id "review"} oid query))]
    (prn (merge {:requested-graph-concepts n :other-graph-concepts other-n :query query :returned-uri (:uri result)} @counts))))

(inspect-case 100 0 "urn:99")
(inspect-case 100 0 "urn:missing")
(inspect-case 1 100 "urn:0")
(shutdown-agents)
