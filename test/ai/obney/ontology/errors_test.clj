(ns ai.obney.ontology.errors-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.core.commands :as commands]
            [ai.obney.ontology.core.operations :as operations]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.support :as h]))

(defn category [result] (:cognitect.anomalies/category result))

(deftest missing-resources-and-empty-results-are-distinct
  (h/with-context [ctx]
    (let [oid (h/graph ctx) missing (random-uuid)]
      (is (nil? (o/get-ontology ctx missing)))
      (is (nil? (o/get-revision ctx missing)))
      (is (false? (o/ontology-exists? ctx missing)))
      (is (nil? (o/get-concept-by-uri ctx missing "urn:absent")))
      (is (nil? (o/get-concept-by-uri ctx oid "urn:absent")))
      (doseq [read [#(o/get-concepts ctx {:ontology-id %})
                    #(o/get-concepts ctx {:ontology-ids [%]})
                    #(o/get-relationships ctx %) #(o/get-sources ctx %)
                    #(o/get-contributions ctx %) #(o/search ctx % {:query-text "nothing"})]]
        (is (= [] (read oid)))
        (is (= :cognitect.anomalies/not-found (category (read missing)))))
      (is (= {} (o/get-canonical-mappings ctx oid)))
      (is (= :cognitect.anomalies/not-found (category (o/get-canonical-mappings ctx missing))))
      (is (= :cognitect.anomalies/not-found (category (o/get-concepts ctx {:ontology-ids [oid missing]}))))
      (is (= [] (o/get-concepts ctx {:ontology-ids []})))
      (doseq [result [(o/export-snapshot ctx missing) (o/export-snapshot ctx missing 1)
                      (o/export-snapshot ctx oid 2)
                      (o/create-concept! ctx missing (h/concept "urn:new"))
                      (o/update-concept! ctx oid (random-uuid) {:changes {:label "Absent"}})
                      (o/register-predicate! ctx missing {:predicate "app:p" :policy {}})
                      (o/import-contribution! ctx missing (h/request ctx oid []))]]
        (is (= :cognitect.anomalies/not-found (category result)))))))

(deftest all-public-operations-return-forbidden-without-tenant
  (h/with-context [ctx]
    (let [oid (h/graph ctx) missing (dissoc ctx :tenant-id)
          cid (:concept-id (h/add! ctx oid "urn:a"))]
      (doseq [f [#(o/create-ontology! missing {:name "Forbidden"})
                 #(o/create-concept! missing oid (h/concept "urn:new"))
                 #(o/update-concept! missing oid cid {:changes {:label "Forbidden"}})
                 #(o/create-relationship! missing {})
                 #(o/register-predicate! missing oid {:predicate "app:p" :policy {}})
                 #(o/import-contribution! missing oid (h/request ctx oid []))
                 #(o/import-rdf! missing oid {:content "malformed"})
                 #(o/get-ontology missing oid) #(o/list-ontologies missing)
                 #(o/ontology-exists? missing oid) #(o/get-revision missing oid)
                 #(o/get-concept missing oid cid) #(o/get-concepts missing) #(o/get-concept-by-uri missing oid "urn:a")
                 #(o/get-concept-map missing) #(o/get-relationships missing oid)
                 #(o/get-contributions missing oid) #(o/get-sources missing oid)
                 #(o/get-canonical-mappings missing oid)
                 #(o/export-snapshot missing oid) #(o/search missing oid {})]]
        (is (= :cognitect.anomalies/forbidden (category (f))))))))

(deftest invalid-inputs-return-incorrect-instead-of-throwing
  (h/with-context [ctx]
    (let [oid (h/graph ctx)]
      (doseq [f [#(o/create-ontology! ctx []) #(o/create-concept! ctx oid [])
                 #(o/update-concept! ctx oid "bad" {}) #(o/create-relationship! ctx [])
                 #(o/register-predicate! ctx oid []) #(o/import-contribution! ctx oid [])
                 #(o/import-rdf! ctx oid []) #(o/get-concepts ctx [])
                 #(o/get-concepts ctx {:ontology-id nil})
                 #(o/get-concepts ctx {:ontology-ids "bad"})
                 #(o/get-concepts ctx {:ontology-id oid :ontology-ids [oid]})
                 #(o/get-concepts ctx {:scope []}) #(o/get-concepts ctx {:broader-uri 1})
                 #(o/get-ontology ctx nil) #(o/ontology-exists? ctx nil) #(o/get-revision ctx nil)
                 #(o/get-concept ctx oid "bad") #(o/get-concept ctx oid nil)
                 #(o/get-concept ctx oid (str (random-uuid)))
                 #(o/get-concept ctx nil (random-uuid))
                 #(o/get-concept-by-uri ctx oid 1) #(o/get-relationships ctx nil)
                 #(o/get-sources ctx nil) #(o/get-contributions ctx nil)
                 #(o/get-canonical-mappings ctx nil)
                 #(o/export-snapshot ctx oid 0) #(o/export-snapshot ctx oid 1.5)
                 #(o/search ctx oid []) #(o/search ctx oid {:query-text 42})
                 #(o/search ctx oid {:limit 0}) #(o/search ctx oid {:max-depth -1})
                 #(o/search ctx oid {:seed-uris "bad"}) #(o/search ctx oid {:weights {:graph -1}})
                 #(o/search ctx oid {:embed-query 42})]]
        (is (= :cognitect.anomalies/incorrect (category (f))))))))

(deftest rdf-input-errors-are-classified-and-unexpected-errors-propagate
  (h/with-context [ctx]
    (let [oid (h/graph ctx)
          request (assoc (dissoc (h/request ctx oid []) :concepts) :content "")
          failure (ex-info "Unexpected implementation failure" {:diagnostic :original})]
      (doseq [content [nil 42 "bad" "_:blank <urn:p> <urn:o> ."
                       "<urn:s> <urn:p> \"\\q\" ."
                       "<urn:s> <urn:p> \"\\uQQQQ\" ."
                       "<urn:s> <urn:p> \"\\UFFFFFFFF\" ."]]
        (is (= :cognitect.anomalies/incorrect
               (category (o/import-rdf! ctx oid (assoc request :content content))))))
      (doseq [target [#'rdf/extract-concepts-from-rdf #'operations/import-contribution!]]
        (with-redefs-fn {target (fn [& _] (throw failure))}
          #(is (identical? failure (try (o/import-rdf! ctx oid request) (catch Exception e e))))))
      (with-redefs [state/metadata-state (fn [& _] (throw failure))]
        (is (identical? failure (try (o/get-revision ctx oid) (catch Exception e e)))))
      (with-redefs [commands/validate-concept! (fn [& _] (throw failure))]
        (is (identical? failure (try (o/create-concept! ctx oid (h/concept "urn:new")) (catch Exception e e)))))
      (is (nil? (o/get-concept-by-uri ctx oid "urn:new")))
      (is (identical? failure
                     (try (o/search ctx oid {:query-text "x" :embed-query (fn [_] (throw failure))})
                          (catch Exception e e))))
      (with-redefs [operations/import-contribution! (fn [& _] {:cognitect.anomalies/category :cognitect.anomalies/fault
                                                             :cognitect.anomalies/message "Storage failure"})]
        (is (= :cognitect.anomalies/fault (category (o/import-rdf! ctx oid request))))))))
