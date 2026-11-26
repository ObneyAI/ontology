(ns ai.obney.ontology.memory-seeds-test
  (:require [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.memory-seeds :as seeds]
            [ai.obney.ontology.memory-lexical :as lexical]
            [ai.obney.ontology.memory-time :as temporal]
            [ai.obney.ontology.memory-aggregation :as aggregation]
            [clojure.test :refer [deftest is]]))

(deftest distinct-seeds-and-bounded-expansion
  (let [turns (vec (for [s (range 10) n (range 12)]
                     {:id (format "s%02d-%02d" s n) :session s
                      :role (if (even? n) "user" "assistant")}))
        ids (mapv :id turns)
        omitted (set (map #(format "s%02d-02" %) (range 8)))
        original (filterv (complement omitted) ids)
        flat (mapv :id (filter #(re-find #"-0[01]$" (:id %)) turns))
        starts (seeds/seeds turns flat)
        search (fn [_ _ opts]
                 (is (= (mapv #(str "urn:longmem:" %) starts) (:seed-uris opts)))
                 (is (= 4 (:max-depth opts)))
                 (mapv #(hash-map :attributes {:id %}) omitted))]
    (is (= (mapv #(format "s%02d-00" %) (range 8)) starts))
    (with-redefs [ontology/search search]
      (let [result (seeds/expand nil nil turns starts original)
            top (take 80 result)]
        (is (= (take 40 original) (take 40 result)))
        (is (= omitted (set (filter omitted top))))
        (is (= (count result) (count (set result))))
        (is (every? (set result) original))
        (is (every? #(= "assistant" (:role (first (filter (fn [t] (= % (:id t))) turns))))
                    (remove (set top) (take 80 original))))))
    (with-redefs [ontology/search (constantly [])]
      (is (= original (seeds/expand nil nil turns starts original)))
      (is (empty? (seeds/expand nil nil [] [] []))))))

(deftest lexical-supplement-requires-a-stronger-statement
  (let [turns [{:id "a" :role "assistant"} {:id "b" :role "user"}
               {:id "c" :role "assistant"} {:id "d" :role "user"}
               {:id "e" :role "user"}]
        original ["a" "b" "c" "d"]
        hits (fn [ids] (mapv #(hash-map :attributes {:id %}) ids))]
    (with-redefs [ontology/search (constantly (hits ["e" "c"]))]
      (is (= ["a" "b" "d" "e"] (take 4 (lexical/supplement nil nil turns "query" original)))))
    (with-redefs [ontology/search (constantly (hits ["c" "e"]))]
      (is (= original (lexical/supplement nil nil turns "query" original))))))

(deftest relative-date-retrieval-uses-the-source-date
  (let [question "What did I plant a week ago?"
        anchor (temporal/target question "2024/03/07 (Thu) 09:00")
        turns [{:id "a" :role "user" :date "2024/01/01" :text "First"}
               {:id "b" :role "user" :date "2024/01/01" :text "Second"}
               {:id "c" :role "assistant" :date "2024/01/01" :text "Other"}
               {:id "d" :role "assistant" :date "2024/01/01" :text "Other"}
               {:id "e" :role "user" :date "2024/03/01" :text "I planted tomatoes yesterday."}
               {:id "f" :role "user" :date "2024/03/08" :text "I planted them a week ago."}]
        before ["a" "b" "c" "d"]]
    (is (= (.toEpochDay (java.time.LocalDate/of 2024 2 29)) (:day anchor)))
    (is (nil? (temporal/target "How many weeks ago did we meet?" "2024-03-07")))
    (is (nil? (temporal/target "Two days ago or three weeks ago?" "2024-03-07")))
    (doseq [q ["1.5 days ago?" "Two to three weeks ago?" "twenty-one days ago?" nil]]
      (is (nil? (temporal/target q "2024-03-07"))))
    (is (nil? (temporal/target question "2024-02-30")))
    (is (= before (temporal/supplement nil turns {} before)))
    (is (= ["a" "b" "c" "e"] (take 4 (temporal/supplement anchor turns {"f" 0 "e" 1} before))))
    (let [expanded (conj turns {:id "g" :role "user" :date "2024-02-29" :text "Any recipe ideas?"})]
      (is (= ["a" "e" "g"] (take 3 (temporal/supplement anchor expanded {"g" 0 "e" 1} ["a" "c" "d"])))))))

(deftest aggregate-retrieval-restores-the-initiating-request-of-a-ranked-session
  (let [turn (fn [id session role] {:id id :session session :role role :text id})
        turns [(turn "z-opening" :trip "user")
               (turn "reply" :trip "assistant")
               (turn "follow-up" :trip "user")
               (turn "last-reply" :trip "assistant")
               (turn "only-assistant" :other "assistant")
               (turn "unrelated" :unranked "user")]
        baseline ["reply" "follow-up" "last-reply" "only-assistant"]
        question "How much did I spend on my trips?"
        result (aggregation/supplement turns question baseline)]
    (is (= ["reply" "follow-up" "only-assistant" "z-opening"] (take 4 result)))
    (is (= 1 (count (filter #{"z-opening"} result))))
    (is (= baseline (aggregation/supplement turns "Which trip was first?" baseline)))
    (is (empty? (aggregation/supplement turns question [])))))
