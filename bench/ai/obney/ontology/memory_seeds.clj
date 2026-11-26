(ns ai.obney.ontology.memory-seeds
  (:require [ai.obney.ontology.interface :as ontology]))

(def window 80)
(def depth 4)

(defn seeds
  "First flat hit in each of the eight highest-ranked distinct sessions."
  [turns flat]
  (let [session-of (into {} (map (juxt :id :session)) turns)]
    (->> flat
         (reduce (fn [[seen result] hit]
                   (let [session (session-of hit)]
                     (if (or (nil? session) (seen session))
                       [seen result]
                       [(conj seen session) (conj result hit)])))
                 [#{} []])
         second (take 8) vec)))

(defn expand
  "Supplement the original hybrid result using the conversation graph. Use source
   positions to order equally distant turns across sessions in seed-rank order."
  [ctx graph turns seed-ids original]
  (let [sessions (vals (group-by :session turns))
        position (into {} (mapcat (fn [session]
                                    (map-indexed (fn [i t] [(:id t) [session i]]) session))
                                  sessions))
        graph-hits (ontology/search ctx graph
                     {:seed-uris (mapv #(str "urn:longmem:" %) seed-ids)
                      :max-depth depth
                      :limit (max 1 (* (count seed-ids) (inc (* 2 depth))))})
        _ (when (:cognitect.anomalies/category graph-hits)
            (throw (ex-info "Session expansion failed" graph-hits)))
        reachable (into #{} (map #(get-in % [:attributes :id])) graph-hits)
        base (vec (take window original))
        existing (set base)
        additions (for [hops (range 1 (inc depth))
                        seed seed-ids
                        :let [[session pos] (position seed)]
                        i [(- pos hops) (+ pos hops)]
                        :let [turn (get session i) id (:id turn)]
                        :when (and (= "user" (:role turn)) (reachable id) (not (existing id)))]
                    id)
        replaceable (for [id (reverse (drop (quot (count base) 2) base))
                          :let [[session pos] (position id)
                                previous (get session (dec pos))]
                          :when (and (= "assistant" (:role (get session pos)))
                                     (= "user" (:role previous))
                                     (existing (:id previous)))]
                      id)
        n (min 16 (count additions) (count replaceable))
        displaced (set (take n replaceable))]
    (vec (distinct (concat (remove displaced base) (take n additions) original)))))
