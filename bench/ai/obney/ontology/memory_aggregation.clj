(ns ai.obney.ontology.memory-aggregation
  (:require [ai.obney.ontology.memory-seeds :as seeds]
            [clojure.string :as str]))

(defn requested? [question]
  (boolean (re-find #"(?i)\b(?:how (?:many|much|old)|total|altogether|in all|on average)\b"
                    (or question ""))))

(defn supplement
  "Recover the initiating user statement from sessions represented in the protected prefix."
  [turns question candidates]
  (if-not (requested? question)
    candidates
    (let [base (vec (take seeds/window candidates))
          middle (quot (count base) 2)
          by-id (into {} (map (juxt :id identity)) turns)
          session-of #(get-in by-id [% :session])
          role-of #(get-in by-id [% :role])
          openers (reduce (fn [acc {:keys [session role id text]}]
                            (if (and session (= "user" role) (not (str/blank? text))
                                     (not (contains? acc session)))
                              (assoc acc session id)
                              acc))
                          {} turns)
          additions (->> (take middle base) (map session-of) distinct
                         (keep openers) (remove (set base)) (take 8))
          represented (into #{} (comp (filter #(= "user" (role-of %))) (keep session-of)) base)
          replies (->> (drop middle base) reverse
                       (filter #(and (= "assistant" (role-of %)) (represented (session-of %)))))
          pairs (map vector additions replies)]
      (vec (distinct (concat (remove (set (map second pairs)) base)
                             (map first pairs) candidates))))))
