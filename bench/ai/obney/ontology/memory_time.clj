(ns ai.obney.ontology.memory-time
  (:require [ai.obney.ontology.memory-seeds :as seeds]
            [clojure.string :as str])
  (:import (java.time LocalDate DateTimeException)))

(def ^:private offsets
  #"(?i)\b(a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d{1,3})\s+(day|week)s?\s+ago\b")
(def ^:private numbers
  (zipmap ["a" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"]
          [1 1 2 3 4 5 6 7 8 9 10 11 12]))

(defn- epoch-day [stamp]
  (when-let [date (and (string? stamp) (re-find #"^\d{4}[-/]\d{2}[-/]\d{2}(?=\s|T|$)" stamp))]
    (try (.toEpochDay (LocalDate/parse (str/replace date "/" "-")))
         (catch DateTimeException _ nil))))

(defn- days-back [[_ amount unit]]
  (* (if (= "week" (str/lower-case unit)) 7 1)
     (or (get numbers (str/lower-case amount)) (parse-long amount))))

(defn target [question stamp]
  (when-let [day (epoch-day stamp)]
    (let [matcher (re-matcher offsets (or question ""))]
      (when (.find matcher)
        (let [anchor (re-groups matcher)
              prefix (subs question 0 (.start matcher))
              compound? (re-find #"(?i)(?:[\d][.,/–—-]|\b(?:between|and|or|to|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)[ -]+)$" prefix)]
          (when-not (or compound? (.find matcher))
            {:as-of day :day (- day (days-back anchor))
             :tolerance (if (= "week" (str/lower-case (last anchor))) 3 1)}))))))

(defn- date-match [{:keys [day as-of tolerance]} {:keys [date text]}]
  (when-let [reported (epoch-day date)]
    (when (<= reported as-of)
      (let [relative (cond-> (map days-back (re-seq offsets text))
                       (re-find #"(?i)\btoday\b" text) (conj 0)
                       (re-find #"(?i)\byesterday\b" text) (conj 1))
            event-distance (when (seq relative) (reduce min (map #(abs (- reported % day)) relative)))
            report-distance (abs (- reported day))]
        (cond
          (and event-distance (<= event-distance tolerance)) [0 event-distance]
          (<= report-distance tolerance) [1 report-distance])))))

(defn supplement [anchor turns lexical-rank candidates]
  (if-not anchor
    candidates
    (let [base (vec (take seeds/window candidates))
          existing (set base)
          by-id (into {} (map (juxt :id identity)) turns)
          nearby (keep (fn [turn]
                         (when (= "user" (:role turn))
                           (when-let [match (date-match anchor turn)]
                             [match (get lexical-rank (:id turn) Long/MAX_VALUE) (:id turn)])))
                       turns)
          additions (->> nearby sort (map last) (remove existing) (take 8))
          replies (->> (drop (quot (count base) 2) base) reverse
                       (filter #(= "assistant" (:role (by-id %))))
                       (sort-by #(get lexical-rank % Long/MAX_VALUE) >))
          pairs (map vector additions replies)]
      (vec (distinct (concat (remove (set (map second pairs)) base)
                             (map first pairs) candidates))))))
