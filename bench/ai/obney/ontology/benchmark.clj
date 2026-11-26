(ns ai.obney.ontology.benchmark
  "Standalone, single-client end-to-end benchmarks. Timings use monotonic nanoseconds."
  (:require [ai.obney.ontology.interface :as o]
            [ai.obney.ontology.core.source-registry :as source-registry]
            [ai.obney.ontology.core.state :as state]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files]
           [java.time Instant]))

(def operations #{:projection-replay :lookup-uri :list-concepts :search-lexical :search-graph :search-vector
                  :export-current :export-first-import :retry-contribution :update-concept :import-resolved :import-delta})

(def defaults {:sizes [10000 20000] :backends [:in-memory :sqlite] :samples 5 :warmup 2
               :batch-size 1000 :history 0 :dimensions 32 :delta-size 10})

(defn parse-args [args]
  (when (odd? (count args)) (throw (ex-info "Options require values" {:args args})))
  (let [options (reduce (fn [m [k v]]
                          (assoc m (case k
                                     "--sizes" :sizes "--backends" :backends "--samples" :samples
                                     "--warmup" :warmup "--batch-size" :batch-size "--history" :history
                                     "--dimensions" :dimensions "--delta-size" :delta-size "--output" :output "--operations" :operations
                                     (throw (ex-info "Unknown option" {:option k})))
                                 (case k
                                   "--sizes" (mapv #(Long/parseLong %) (str/split v #","))
                                   "--backends" (mapv keyword (str/split v #","))
                                   "--output" v
                                   "--operations" (set (map keyword (str/split v #",")))
                                   (Long/parseLong v)))) defaults (partition 2 args))]
    (when-not (and (or (nil? (:operations options)) (and (seq (:operations options)) (every? operations (:operations options))))
                   (seq (:sizes options)) (every? pos-int? (:sizes options))
                   (seq (:backends options)) (every? #{:in-memory :sqlite} (:backends options))
                   (every? pos-int? (map options [:samples :batch-size :dimensions :delta-size]))
                   (every? nat-int? (map options [:warmup :history])))
      (throw (ex-info "Sizes, samples, batch size, dimensions and delta size must be positive; warmup/history nonnegative" options)))
    options))

(defn verify! [condition data]
  (when-not condition (throw (ex-info "Benchmark result validation failed" data))))
(defn ok! [result]
  (verify! (not (:cognitect.anomalies/category result)) result)
  result)
(defn id [s] (java.util.UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8")))
(defn uri [i] (format "urn:bench:concept:%08d" (long i)))
(defn concept [i dimensions]
  {:uri (uri i) :label (str "Concept " i) :description (str "Benchmark knowledge item category " (mod i 17))
   :scope :benchmark :broader (if (zero? i) [] [(uri (quot (dec i) 2))])
   :embedding-model "benchmark-synthetic"
   :embedding (mapv (fn [d] (Math/sin (double (+ (* 31 i) d 1)))) (range dimensions))})
(defn request [oid revision key concepts]
  {:contribution-id (id key) :expected-revision revision
   :source {:source-id (id (str "source-" key)) :content-hash (str "synthetic-" key)}
   :concepts (vec concepts)})

(defn gc-stats []
  (reduce (fn [m bean]
            (-> m (update :count + (max 0 (.getCollectionCount bean)))
                  (update :ms + (max 0 (.getCollectionTime bean)))))
          {:count 0 :ms 0} (ManagementFactory/getGarbageCollectorMXBeans)))
(defn allocated []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? com.sun.management.ThreadMXBean bean)
               (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean)
               (.isThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean))
      (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.getId (Thread/currentThread))))))
(defn timed [f]
  (let [gc-before (gc-stats) bytes-before (allocated) start (System/nanoTime)
        result (f) elapsed (/ (- (System/nanoTime) start) 1e6)
        bytes-after (allocated) gc-after (gc-stats)]
    [result {:elapsed-ms elapsed
             :allocated-bytes (when (and bytes-before bytes-after) (- bytes-after bytes-before))
             :gc-count (- (:count gc-after) (:count gc-before))
             :gc-ms (- (:ms gc-after) (:ms gc-before))}]))
(defn median [xs]
  (let [v (vec (sort xs)) n (count v) middle (quot n 2)]
    (if (odd? n) (nth v middle) (/ (+ (nth v (dec middle)) (nth v middle)) 2.0))))
(defn measure [warmup samples f check]
  (dotimes [_ warmup] (check (f)))
  (let [observations (mapv (fn [_] (let [[result observation] (timed f)] (check result) observation)) (range samples))
        times (mapv :elapsed-ms observations)
        allocations (keep :allocated-bytes observations)]
    {:samples observations :median-ms (median times) :min-ms (apply min times) :max-ms (apply max times)
     :mean-ms (/ (reduce + times) (count times))
     :median-allocated-bytes (when (seq allocations) (median allocations))
     :gc-count (reduce + (map :gc-count observations)) :gc-ms (reduce + (map :gc-ms observations))}))

(defn open-fixture [backend]
  (let [file (when (= backend :sqlite)
               (require 'ai.obney.grain.event-store-sqlite-v3.interface)
               (.toFile (Files/createTempFile "ontology-benchmark-" ".sqlite" (make-array java.nio.file.attribute.FileAttribute 0))))
        store (es/start {:conn (if file {:type :sqlite :database-file (.getAbsolutePath file)} {:type :in-memory})
                         :event-pubsub nil :logger nil})]
    {:ctx {:event-store store :tenant-id (id "benchmark-tenant")} :file file}))
(defn close-fixture! [{:keys [ctx file]}]
  (try (es/stop (:event-store ctx))
       (finally (when file
                  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
                    (Files/deleteIfExists (.toPath (io/file (str (.getAbsolutePath file) suffix)))))))))
(defn storage-bytes [file]
  (when file (reduce + (map #(.length (io/file (str (.getAbsolutePath file) %))) ["" "-wal" "-shm" "-journal"]))))

(defn populate! [ctx n {:keys [batch-size dimensions history]}]
  (let [oid (:ontology-id (ok! (o/create-ontology! ctx {:name "Benchmark" :scope :benchmark :base-uri "urn:bench:"})))
        records (mapv #(concept % dimensions) (range n))
        revision (atom 1) last-input (atom nil)]
    (doseq [[i batch] (map-indexed vector (partition-all batch-size records))]
      (let [input (request oid @revision (str "initial-" i) batch)]
        (reset! revision (:revision (ok! (o/import-contribution! ctx oid input))))
        (reset! last-input input)))
    (dotimes [i history]
      (let [input (request oid @revision (str "history-" i) [(first records)])]
        (reset! revision (:revision (ok! (o/import-contribution! ctx oid input))))
        (reset! last-input input)))
    {:oid oid :records records :last-input @last-input :revision @revision}))

(defn workloads [ctx {:keys [oid records last-input]} {:keys [delta-size dimensions]}]
  (let [n (count records) target (:uri (last records)) baseline (o/get-revision ctx oid)
        original-retry (ok! (o/import-contribution! ctx oid last-input))
        next-id (atom n) command-index (atom 0) write-revision (atom baseline)
        update-id (:concept-id (o/get-concept-by-uri ctx oid target))
        new-command #(str "measured-" (swap! command-index inc))
        accept-write (fn [result]
                       (ok! result)
                       (verify! (= (inc @write-revision) (:revision result)) result)
                       (reset! write-revision (:revision result)))]
    [{:name :projection-replay :kind :diagnostic
      :run #(reduce state/apply-event {} (state/read-events ctx))
      :check #(verify! (= n (count (:concepts %))) {:operation :projection-replay})}
     {:name :lookup-uri :kind :read
      :run #(o/get-concept-by-uri ctx oid target)
      :check #(verify! (= target (:uri %)) {:operation :lookup-uri})}
     {:name :list-concepts :kind :read
      :run #(o/get-concepts ctx {:ontology-id oid})
      :check #(verify! (= n (count %)) {:operation :list-concepts})}
     {:name :search-lexical :kind :read
      :run #(o/search ctx oid {:query-text "category 7" :limit 10})
      :check #(verify! (and (seq %) (every? (fn [c] (= oid (:ontology-id c))) %)) {:operation :search-lexical})}
     {:name :search-graph :kind :read
      :run #(o/search ctx oid {:seed-uris [(uri 0)] :max-depth 2 :limit 10})
      :check #(verify! (some (fn [c] (= (uri 0) (:uri c))) %) {:operation :search-graph})}
     {:name :search-vector :kind :read
      :run #(o/search ctx oid {:query-text "vector" :limit 10
                          :embedding-model "benchmark-synthetic" :embed-query (constantly (:embedding (last records)))})
      :check #(verify! (= target (:uri (first %))) {:operation :search-vector})}
     {:name :export-current :kind :read
      :run #(o/export-snapshot ctx oid)
      :check #(verify! (and (= n (count (:concepts %))) (pos? (:triple-count %))) {:operation :export-current})}
     {:name :export-first-import :kind :read
      :run #(o/export-snapshot ctx oid 2)
      :check #(verify! (and (= 2 (:revision %)) (seq (:concepts %))) {:operation :export-first-import})}
     {:name :retry-contribution :kind :retry
      :run #(o/import-contribution! ctx oid last-input)
      :check #(verify! (= original-retry %) {:operation :retry-contribution})}
     {:name :update-concept :kind :write
      :run #(o/update-concept! ctx oid update-id {:changes {:attributes {:benchmark (new-command)}}})
      :check accept-write}
     {:name :import-resolved :kind :write
      :run #(let [key (new-command)
                  aliases (mapv (fn [i] (-> (nth records (mod i n))
                                            (assoc :uri (str "urn:bench:alias:" key ":" i))
                                            (dissoc :embedding :embedding-model :broader))) (range (min n delta-size)))]
              (o/import-contribution! ctx oid (assoc (request oid @write-revision key aliases)
                                                :resolution-policy {:match-labels? true :similarity-threshold 1.0})))
      :check (fn [r] (accept-write r) (verify! (= n (:concept-count r)) {:operation :import-resolved :result r}))}
     {:name :import-delta :kind :write
      :run #(let [start @next-id key (new-command)
                  input (request oid @write-revision key (mapv (fn [i] (concept i dimensions)) (range start (+ start delta-size))))]
              (swap! next-id + delta-size)
              (o/import-contribution! ctx oid input))
      :check (fn [r] (accept-write r) (verify! (= @next-id (:concept-count r)) {:operation :import-delta :result r}))}]))

(defn benchmark! [options emit!]
  (doseq [backend (:backends options) n (:sizes options)]
    (println "Populating" (name backend) n "concepts; history" (:history options)) (flush)
    (let [{:keys [ctx file] :as fixture} (open-fixture backend)]
      (try
        (let [[graph setup] (timed #(populate! ctx n options))
              _ (verify! (= n (count (:concepts (state/read-state ctx)))) {:operation :populate})
              initial-events (count (state/read-events ctx))]
          (emit! (merge {:backend backend :concepts n :history (:history options) :operation :populate
                         :kind :setup :event-count initial-events :storage-bytes (storage-bytes file)} setup))
          (doseq [{:keys [name kind run check]} (workloads ctx graph options)
                  :when (or (nil? (:operations options)) ((:operations options) name))]
            (println "Measuring" (clojure.core/name backend) n (clojure.core/name name)) (flush)
            (let [start-revision (o/get-revision ctx (:oid graph))
                  result (measure (:warmup options) (:samples options) run check)
                  end-revision (o/get-revision ctx (:oid graph))]
              (when (not= kind :write) (verify! (= start-revision end-revision) {:operation name :unexpected-write true}))
              (emit! (merge {:backend backend :concepts n :history (:history options) :operation name :kind kind
                             :start-revision start-revision :end-revision end-revision
                             :start-events start-revision :end-events end-revision
                             :storage-bytes (storage-bytes file)} result)))))
        (finally (close-fixture! fixture))))))

(defn report [rows]
  (str "# Ontology benchmark results\n\nSingle client, warmed JVM; milliseconds per operation. Full raw samples and configuration are in results.edn and metadata.edn.\n\n"
       "| Backend | Concepts | Extra history | Operation | Median ms | Min ms | Max ms | Median allocated MiB | GC count |\n| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |\n"
       (apply str (for [r rows :when (:median-ms r)]
                    (format "| %s | %d | %d | %s | %.3f | %.3f | %.3f | %s | %d |\n"
                            (name (:backend r)) (:concepts r) (:history r) (name (:operation r))
                            (:median-ms r) (:min-ms r) (:max-ms r)
                            (if-let [b (:median-allocated-bytes r)] (format "%.3f" (/ b 1048576.0)) "unavailable") (:gc-count r))))))

(def csv-fields [:backend :concepts :history :operation :kind :median-ms :min-ms :max-ms :mean-ms
                 :median-allocated-bytes :gc-count :gc-ms :elapsed-ms :start-revision :end-revision :storage-bytes])

(defn -main [& args]
  (let [options (parse-args args)
        dir (io/file (or (:output options) (str "target/benchmarks/" (System/currentTimeMillis))))
        _ (when (.exists dir) (throw (ex-info "Output directory exists; choose a new path to retain previous runs" {:output (str dir)})))
        _ (.mkdirs dir)
        metadata {:started-at (str (Instant/now)) :options options
                  :java-version (System/getProperty "java.version") :java-vm (System/getProperty "java.vm.name")
                  :jvm-arguments (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
                  :os (System/getProperty "os.name") :os-version (System/getProperty "os.version") :arch (System/getProperty "os.arch")
                  :processors (.availableProcessors (Runtime/getRuntime)) :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                  :source-sha256 (into (sorted-map)
                                      (for [file (concat (filter #(.isFile %) (file-seq (io/file "src")))
                                                        [(io/file "deps.edn") (io/file "bench/ai/obney/ontology/benchmark.clj")])]
                                        [(str file) (source-registry/sha256 (slurp file))]))
                  :deps (edn/read-string (slurp "deps.edn"))}
        rows (atom [])]
    (spit (io/file dir "metadata.edn") (pr-str metadata))
    (spit (io/file dir "results.csv") (str (str/join "," (map name csv-fields)) "\n"))
    (try
      (benchmark! options (fn [row]
                            (swap! rows conj row)
                            (spit (io/file dir "results.edn") (str (pr-str row) "\n") :append true)
                            (spit (io/file dir "results.csv")
                                  (str (str/join "," (map (fn [k] (let [v (get row k)] (if (keyword? v) (name v) (str v)))) csv-fields)) "\n")
                                  :append true)))
      (spit (io/file dir "report.md") (report @rows))
      (spit (io/file dir "status.edn") (pr-str {:status :complete :finished-at (str (Instant/now)) :rows (count @rows)}))
      (println "Results:" (.getAbsolutePath dir))
      (catch Throwable e
        (spit (io/file dir "status.edn") (pr-str {:status :failed :message (.getMessage e) :data (ex-data e)}))
        (throw e))
      (finally (shutdown-agents)))))
