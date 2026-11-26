(ns ai.obney.ontology.profile
  "Single-client stage profiling using the same μ/trace tree as Grain."
  (:refer-clojure :exclude [run!])
  (:require [ai.obney.ontology.benchmark :as bench]
            [ai.obney.ontology.core.source-registry :as source-registry]
            [ai.obney.ontology.core.trace :as trace]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [com.brunobonacci.mulog :as u]
            [com.brunobonacci.mulog.buffer :as buffer]
            [com.brunobonacci.mulog.publisher :as publisher]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.util.concurrent ThreadPoolExecutor TimeUnit]))

(def root-name :ontology.profile/import)
(def allocation-key :ontology.trace/allocated-bytes)
(def exported-keys
  [:mulog/event-name :mulog/trace-id :mulog/parent-trace :mulog/root-trace
   :mulog/timestamp :mulog/duration :mulog/outcome :mulog/capture
   :profile/run-id :profile/sample :profile/thread-id :queue-wait-ns :profile/rows allocation-key])
(defn sanitize [event]
  (reduce (fn [m k] (if (get m k) (update m k str) m))
          (select-keys event exported-keys)
          [:mulog/trace-id :mulog/parent-trace :mulog/root-trace]))

(defn collector [run-id samples]
  (let [events (atom []) complete (promise) inbox (buffer/agent-buffer 10000)
        p (reify publisher/PPublisher
            (agent-buffer [_] inbox)
            (publish-delay [_] 50)
            (publish [_ buf]
              (let [batch (->> (buffer/items buf) (map second)
                               (filter #(and (= run-id (:profile/run-id %)) (:mulog/duration %)))
                               (mapv sanitize))
                    all (swap! events into batch)]
                (when (= samples (count (filter #(= root-name (:mulog/event-name %)) all)))
                  (deliver complete true)))
              (buffer/clear buf)))
        stop (u/start-publisher! {:type :inline :publisher p})]
    {:events events :complete complete
     :stop #(do (stop) (await inbox))}))

(defn account
  "Self time subtracts direct children. Allocation subtracts nearest measured descendants.
  Requires a complete synchronous tree; native Grain spans have no allocation counter."
  [events]
  (let [by-id (into {} (map (juxt :mulog/trace-id identity) events))
        roots (filter #(= root-name (:mulog/event-name %)) events)
        children (group-by :mulog/parent-trace events)
        measured-parent (fn [e]
                          (loop [id (:mulog/parent-trace e)]
                            (when-let [parent (get by-id id)]
                              (if (and (contains? parent allocation-key)
                                       (= (:profile/thread-id e) (:profile/thread-id parent))) id
                                  (recur (:mulog/parent-trace parent))))))
        allocated-children (group-by measured-parent (filter #(contains? % allocation-key) events))]
    (bench/verify! (= 1 (count roots)) {:reason :expected-one-root})
    (bench/verify! (= (count events) (count by-id)) {:reason :duplicate-trace-id})
    (doseq [e events :when (not= root-name (:mulog/event-name e))]
      (bench/verify! (contains? by-id (:mulog/parent-trace e)) {:reason :missing-parent :event e}))
    (mapv (fn [e]
            (let [id (:mulog/trace-id e)
                  self (- (:mulog/duration e) (reduce + 0 (map :mulog/duration (get children id))))
                  alloc (when (contains? e allocation-key)
                          (- (get e allocation-key)
                             (reduce + 0 (map allocation-key (get allocated-children id)))))]
              (bench/verify! (and (not (neg? self)) (or (nil? alloc) (not (neg? alloc))))
                             {:reason :overlapping-or-invalid-spans :event e :self self :allocation alloc})
              (assoc e :self-ns self :self-allocated-bytes alloc))) events)))

(defn summarize [events]
  (let [samples (mapv account (vals (group-by :profile/sample events)))
        names (sort (set (map :mulog/event-name events)))]
    (->> names
         (mapv (fn [event-name]
                 (let [groups (mapv #(filter (fn [e] (= event-name (:mulog/event-name e))) %) samples)
                       sums (fn [k] (mapv #(reduce + 0 (keep k %)) groups))]
                   {:event event-name :calls (bench/median (map count groups))
                    :inclusive-ms (/ (bench/median (sums :mulog/duration)) 1e6)
                    :self-ms (/ (bench/median (sums :self-ns)) 1e6)
                    :self-mib (when (some #(contains? % allocation-key) (apply concat groups))
                                (/ (bench/median (sums :self-allocated-bytes)) 1048576.0))})))
         (sort-by :self-ms >) vec)))

(defn propagate-writer-context [store complete submitted?]
  (let [path [:state :ai.obney.grain.event-store-sqlite-v3.core/write-coordinator]]
    (if-let [^ThreadPoolExecutor executor (get-in store path)]
      (let [context (u/local-context) allocations? trace/*allocations?*
            forwarding (proxy [ThreadPoolExecutor]
                             [1 1 0 TimeUnit/MILLISECONDS (.getQueue executor)]
                         (execute [task]
                           (.execute executor
                                     ^Runnable
                                     (fn []
                                       (try
                                         (u/with-context (assoc context :profile/thread-id (.getId (Thread/currentThread)))
                                           (binding [trace/*allocations?* allocations?]
                                             (trace/span :ontology.trace/sqlite-writer
                                               (.run ^Runnable task))))
                                         (finally (deliver complete true)))))
                           (reset! submitted? true)))]
        (assoc-in store path forwarding))
      store)))

(defn traced-reduction [source f init]
  (let [rows (volatile! 0) before (trace/allocated-bytes)]
    (u/trace :ontology.trace/sqlite-read
      {:pairs []
       :capture (fn [_] (cond-> {:profile/rows @rows}
                          before (assoc allocation-key (- (trace/allocated-bytes) before))))}
      (reduce (fn [acc row] (vswap! rows inc) (f acc row)) init source))))

(defn sqlite-read-hooks []
  (when-let [ns (find-ns 'ai.obney.grain.event-store-sqlite-v3.core)]
    (into {}
          (for [name '[make-reducible conn-reducible]
                :let [v (ns-resolve ns name) original @v]]
            [v (fn [& args]
                 (let [source (apply original args)]
                   (reify
                     clojure.lang.IReduceInit
                     (reduce [_ f init] (traced-reduction source f init))
                     clojure.lang.IReduce
                     (reduce [_ f]
                       (let [sentinel (Object.)
                             result (traced-reduction source
                                                      #(if (identical? %1 sentinel) %2 (f %1 %2)) sentinel)]
                         (if (identical? result sentinel) (f) result))))))]))))

(defn with-grain-boundaries
  "Profiling only: enclose complete public calls, including validation outside native spans.
  with-redefs is process-wide: use only in this isolated, single-client profiling JVM."
  [f]
  (let [project rmp/p append es/append event es/->event]
    (with-redefs [rmp/p (fn [& args] (trace/span :ontology.trace/grain-projection (apply project args)))
                  es/append (fn [store args]
                              (trace/span :ontology.trace/grain-append
                                (let [complete (promise) submitted? (atom false)]
                                  (try (append (propagate-writer-context store complete submitted?) args)
                                       (finally
                                         (when @submitted?
                                           (bench/verify! (deref complete 30000 false)
                                                          {:reason :writer-trace-timeout})))))))
                  es/->event (fn [& args] (trace/span :ontology.trace/event-construction (apply event args)))]
      (with-redefs-fn (or (sqlite-read-hooks) {}) f))))

(defn run! [options]
  (bench/verify! (and (= 1 (count (:sizes options))) (= 1 (count (:backends options))))
                 {:reason :one-size-and-backend-per-jvm})
  (let [n (first (:sizes options)) backend (first (:backends options))
        operation (or (first (:operations options)) :import-delta)
        _ (bench/verify! (and (#{:import-delta :import-resolved} operation)
                             (<= (count (:operations options)) 1)) {:reason :one-import-operation})
        fixture (bench/open-fixture backend)]
    (try
      (println "Populating" backend n) (flush)
      (let [graph (bench/populate! (:ctx fixture) n options)
            workload (first (filter #(= operation (:name %)) (bench/workloads (:ctx fixture) graph options)))
            run (:run workload) check (:check workload)
            control (bench/measure (:warmup options) (:samples options) run check)
            run-id (str (random-uuid)) {:keys [events complete stop]} (collector run-id (:samples options))
            measurements
            (try
              (with-grain-boundaries
                #(binding [trace/*allocations?* true]
                   (mapv (fn [sample]
                           (let [[result observation]
                                 (bench/timed
                                  (fn [] (u/with-context {:profile/run-id run-id :profile/sample sample
                                                         :profile/thread-id (.getId (Thread/currentThread))}
                                           (trace/span :ontology.profile/import (bench/ok! (run))))))]
                             (check result) observation))
                         (range (:samples options)))))
              (finally
                (try (bench/verify! (deref complete 30000 false) {:reason :trace-collection-timeout})
                     (finally (stop)))))
            rows (summarize @events)
            _ (bench/verify! (some #(str/starts-with? (str (:event %)) ":ai.obney.grain.") rows)
                             {:reason :missing-native-grain-spans})
            result {:options options :operation operation :run-id run-id
                    :started-at (str (java.time.Instant/now))
                    :java-version (System/getProperty "java.version")
                    :os (System/getProperty "os.name") :arch (System/getProperty "os.arch")
                    :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                    :jvm-arguments (vec (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))
                    :deps (edn/read-string (slurp "deps.edn"))
                    :source-sha256 (into (sorted-map)
                                         (for [file (concat (filter #(.isFile %) (file-seq (io/file "src")))
                                                            [(io/file "deps.edn")
                                                             (io/file "bench/ai/obney/ontology/benchmark.clj")
                                                             (io/file "bench/ai/obney/ontology/profile.clj")])]
                                           [(str file) (source-registry/sha256 (slurp file))]))
                    :profile-start-concepts (if (= operation :import-delta)
                                              (+ n (* (:delta-size options) (+ (:warmup options) (:samples options)))) n)
                    :grain-sha "dbf5b5229aad09785e40f71e96adf65c90f971d5"
                    :control control :measurements measurements :summary rows :events @events}
            prefix (or (:output options) "target/profile/import")
            report (str "# Contribution stage profile\n\n" backend ", " n " initial concepts, " operation
                        ", " (:delta-size options) " concepts per command; " (:samples options) " samples.\n\n"
                        "Control median: " (format "%.2f" (:median-ms control)) " ms. Profile median: "
                        (format "%.2f" (bench/median (map :elapsed-ms measurements))) " ms.\n\n"
                        "| Span | Calls/sample | Inclusive ms | Self ms | Self MiB allocated |\n"
                        "|---|---:|---:|---:|---:|\n"
                        (str/join "\n" (map (fn [{:keys [event calls inclusive-ms self-ms self-mib]}]
                                              (format "| `%s` | %s | %.3f | %.3f | %s |" event calls inclusive-ms self-ms
                                                      (if self-mib (format "%.3f" self-mib) "unmeasured"))) rows))
                        "\n\nMedians of per-sample stage totals. Inclusive rows overlap; self rows do not. "
                        "Native Grain spans have no allocation counters; their allocations remain in the nearest measured enclosing span. "
                        "Allocation subtraction stays on each span's thread; the SQLite writer is measured separately. "
                        "Thread allocation is not retained or peak heap. Tracing adds overhead. "
                        "Control precedes profiling in the same JVM; this is not a randomized overhead experiment.\n")]
        (io/make-parents (str prefix ".edn"))
        (spit (str prefix ".edn") (with-out-str (pprint/pprint result)))
        (spit (str prefix ".md") report)
        (println report)
        (dissoc result :events))
      (finally (bench/close-fixture! fixture)))))

(defn -main [& args]
  (try (run! (bench/parse-args args))
       (shutdown-agents)
       (catch Throwable t (.printStackTrace t) (shutdown-agents) (System/exit 1))))
