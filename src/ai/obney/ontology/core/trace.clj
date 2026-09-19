(ns ai.obney.ontology.core.trace
  "Ontology spans share Grain's μ/trace tree. Allocation sampling is opt-in."
  (:require [com.brunobonacci.mulog :as u])
  (:import [java.lang.management ManagementFactory]))

(def ^:dynamic *allocations?* false)
(defn allocated-bytes []
  (when *allocations?*
    (let [bean (ManagementFactory/getThreadMXBean)]
      (when (and (instance? com.sun.management.ThreadMXBean bean)
                 (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean)
                 (.isThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean))
        (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.getId (Thread/currentThread)))))))
(defmacro span [name & body]
  `(let [before# (allocated-bytes)]
     (u/trace ~name
       {:pairs []
        :capture (fn [_#] (if-let [after# (when before# (allocated-bytes))]
                           {:ontology.trace/allocated-bytes (- after# before#)} {}))}
       ~@body)))
