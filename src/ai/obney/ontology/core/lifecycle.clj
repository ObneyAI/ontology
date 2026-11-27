(ns ai.obney.ontology.core.lifecycle
  "Prepare explicit concept identity changes for atomic commit and replay."
  (:require [ai.obney.ontology.core.delta :as delta]
            [ai.obney.ontology.core.errors :as errors]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.core.state :as state]
            [cognitect.anomalies :as anom]))

(defn- concept! [s oid id]
  (errors/require! (uuid? id) "Concept ID must be a UUID")
  (or (get-in s [:concepts (first (get-in s [:indexes :concept-ids [oid id]]))])
      (errors/fail! ::anom/not-found "Concept not found in this ontology")))

(defn- survivor [s oid source {:keys [new-uri target-concept-id]} event-type]
  (case event-type
    :ontology/concept-deleted nil
    :ontology/concept-renamed
    (do
      (errors/require! (rdf/uri-reference? new-uri) "Invalid new concept URI")
      (when (get-in s [:concepts [oid new-uri]])
        (errors/fail! ::anom/conflict "Concept URI already exists"))
      (assoc source :uri new-uri))
    :ontology/concepts-merged
    (let [target (concept! s oid target-concept-id)]
      (errors/require! (not= (:concept-id source) target-concept-id) "Choose two distinct concepts")
      (assoc target :alt-labels
             (into [] (comp (remove #{(:label target)}) (distinct))
                   (concat (:alt-labels target) [(:label source)] (:alt-labels source)))))))

(defn- redirected-edge [e [oid uri] new-uri]
  (cond-> e
    (= [oid uri] ((juxt :source-ontology-id :source-uri) e)) (assoc :source-uri new-uri)
    (= [oid uri] ((juxt :target-ontology-id :target-uri) e)) (assoc :target-uri new-uri)))

(defn- edge-delta [s key destination merging?]
  (let [removed (sort (get-in s [:indexes :concept-edges key]))
        retained (remove (set removed) (get-in s [:indexes :concept-edges destination]))
        view-keys (fn [edge] (map state/edge-key (state/edge-views s edge)))
        occupied (into #{} (mapcat #(view-keys (get-in s [:edges %]))) retained)
        redirected (when destination
                     (map #(redirected-edge (get-in s [:edges %]) key (second destination)) removed))]
    (loop [[edge & more] redirected seen occupied upserts {}]
      (if edge
        (let [[so su _ to tu :as k] (state/edge-key edge)
              views (view-keys edge)]
          (if (or (= [so su] [to tu]) (and merging? (some seen views)))
            (recur more seen upserts)
            (recur more (into seen views) (assoc upserts k edge))))
        {:upserts upserts :removals (vec removed)}))))

(defn- rewrite-schema-types [s oid uri new-uri]
  (let [schema (get-in s [:tbox oid] {})
        update-field (fn [record field]
                       (if (= uri (get record field))
                         (if new-uri (assoc record field new-uri)
                             (errors/fail! ::anom/conflict "A schema or individual type requires this concept"))
                         record))]
    (if (or (rdf/built-in-schema-uri? uri) (some #(= uri (:uri %)) (:classes schema)))
      s
      (-> s
          (assoc-in [:tbox oid]
                    (update-vals schema
                      (fn [records] (mapv #(reduce update-field % [:domain :range :datatype]) records))))
          (update-in [:abox oid] #(mapv (fn [record] (update-field record :type)) %))))))

(defn- rewrite-triples [triples uri new-uri merging?]
  (let [rewrite (fn [{:keys [subject object-uri] :as triple}]
                  (if (or (= uri subject) (= uri object-uri))
                    (when new-uri
                      (let [result (cond-> triple
                                     (= uri subject) (assoc :subject new-uri)
                                     (= uri object-uri) (assoc :object-uri new-uri))]
                        (when-not (and merging? (not= subject object-uri) (= (:subject result) (:object-uri result)))
                          (rdf/validate-triples! [result])
                          result)))
                    triple))]
    (into [] (keep rewrite) triples)))

(defn prepare [s {:keys [ontology-id concept-id] :as request} event-type]
  (let [source (concept! s ontology-id concept-id)
        target (survivor s ontology-id source request event-type)
        uri (:uri source) new-uri (:uri target)
        source-key [ontology-id uri] target-key (when target [ontology-id new-uri])
        merging? (= event-type :ontology/concepts-merged)
        edges (edge-delta s source-key target-key merging?)
        affected (into #{ontology-id} (mapcat (juxt first #(nth % 3))) (:removals edges))
        schema-state (-> (rewrite-schema-types s ontology-id uri new-uri)
                         (update-in [:rdf-triples ontology-id] rewrite-triples uri new-uri merging?))
        changes (assoc (delta/changes s (delta/track schema-state) ontology-id)
                       :concepts {:removals [source-key]
                                  :upserts (if target
                                             {target-key (assoc target :updated-at (str (java.time.Instant/now)))} {})}
                       :relationships edges)]
    {:type event-type
     :body {:old-uri uri :new-uri new-uri :changes changes :affected-ontology-ids (vec (sort affected))}
     :result (select-keys (or target source) [:concept-id :uri])}))
