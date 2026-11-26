(ns ai.obney.ontology.memory-full
  (:require [ai.obney.ontology.memory-pilot :as pilot]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files StandardCopyOption)))

(defn- rank! [root id]
  (let [input (io/file root id "input.json")
        output (io/file root id "ontology.json")]
    (when (and (.exists input) (not (.exists output)))
      (let [case (json/parse-string (slurp input) true)
            turns (filterv #(not (str/blank? (:text %))) (:turns case))
            result (assoc (pilot/rankings (assoc case :turns turns) 80)
                          :blank-turns-skipped (- (count (:turns case)) (count turns)))
            tmp (io/file root id "ontology.tmp")]
        (spit tmp (json/generate-string result))
        (Files/move (.toPath tmp) (.toPath output)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))))
    (.exists output)))

(defn -main [root]
  (try
    (loop [pending (:ids (json/parse-string (slurp (io/file root "manifest.json")) true))]
      (let [remaining (filterv #(not (rank! root %)) pending)]
        (when (seq remaining)
          (Thread/sleep 1000)
          (recur remaining))))
    (finally (shutdown-agents))))
