(ns ai.obney.ontology.core.streams
  (:require [clojure.set :as set]))

(defn identity-id [parts]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str parts) "UTF-8")))
(defn concept-tag [oid uri] [:concept (identity-id [oid uri])])
(defn request-ontology-id [event]
  (when-not (= :ontology/ontology-created (:event/type event))
    (or (:ontology-id event) (:source-ontology-id event))))
(defn request-tag [oid request-id] [:request (identity-id [oid request-id])])
(defn- changed-keys [a b]
  (filter #(not= (get a %) (get b %)) (set/union (set (keys a)) (set (keys b)))))
(defn event-tags [before after event]
  (let [oid (:ontology-id event)
        changes (:changes event)
        contribution? (= :ontology/contribution-imported (:event/type event))
        edge-keys (if contribution?
                    (concat (keys (get-in changes [:relationships :upserts])) (get-in changes [:relationships :removals]))
                    (changed-keys (:edges before) (:edges after)))
        edges (mapcat #(keep (fn [s] (get-in s [:edges %])) [before after]) edge-keys)
        endpoints (set (mapcat (fn [e] [[(:source-ontology-id e) (:source-uri e)]
                                       [(:target-ontology-id e) (:target-uri e)]]) edges))
        concepts (set/union (set (if contribution?
                                   (concat (keys (get-in changes [:concepts :upserts])) (get-in changes [:concepts :removals]))
                                   (changed-keys (:concepts before) (:concepts after)))) endpoints)
        affected (set (remove nil? (concat [oid (:source-ontology-id event) (:target-ontology-id event)] (map first endpoints))))]
    (into (cond-> (set (map #(vector :ontology %) affected))
            (:request-id event) (conj (request-tag (request-ontology-id event) (:request-id event)))
            (:contribution-id event) (conj [:contribution (:contribution-id event)])
            (get-in event [:source :source-id]) (conj [:source (get-in event [:source :source-id])]
                                                    [:source-version (identity-id [oid (get-in event [:source :source-id])
                                                                                  (get-in event [:source :content-hash])])]))
          (concat (map (fn [[id uri]] (concept-tag id uri)) concepts)
                  (keep (fn [key] (when-let [id (or (get-in after [:concepts key :concept-id]) (get-in before [:concepts key :concept-id]))]
                                   [:concept-id id])) concepts)
                  (map #(vector :ontology-relationships %) (set (map first endpoints)))))))
