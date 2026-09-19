(ns ai.obney.ontology.core.projection
  "Scoped access to Grain's registered reducers and incremental projection engine."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.protocol :as event-protocol]
            [ai.obney.grain.kv-store.interface.protocol :as kv]
            [clj-uuid :as uuid]))

;; L1 belongs to Grain. Hosts may supply :cache for durable L2 storage; an L2
;; sink preserves the existing cache-free host context without another cache.
(def ^:private no-l2
  (reify kv/KVStore
    (start [this] this) (stop [this] this)
    (get! [_ _] nil) (put! [_ _] nil) (put-batch! [_ _] nil)))
(defonce ^:private stores (java.util.WeakHashMap.))
(defonce ^:private locks (vec (repeatedly 128 #(Object.))))
(defn- store-key [ctx]
  (or (:ontology/store-id ctx)
      (locking stores
        (or (.get stores (:event-store ctx))
            (let [key (random-uuid)] (.put stores (:event-store ctx) key) key)))))
(defn- canonical [x]
  (cond (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) (map (fn [[k v]] [k (canonical v)]) x))
        (set? x) (vec (sort-by pr-str (map canonical x)))
        (sequential? x) (mapv canonical x)
        :else x))
(defn- digest [x]
  (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                 (.getBytes (pr-str (canonical x)) "UTF-8")))))
(defn authorize! [ctx]
  (when-not (:tenant-id ctx)
    (throw (ex-info "A tenant-id is required" {:cognitect.anomalies/category :cognitect.anomalies/forbidden}))))

(defn- read-bounded [store query]
  (let [queries (if (vector? query) query [query])
        bound (:as-of (first queries)) after (:after (first queries))]
    (cond
      (and bound after (not (uuid/< after bound))) []
      (and bound after)
      ;; Grain adds :after to historical scopes, but the event-store contract
      ;; forbids combining :after with :as-of. Stream the suffix and stop at the
      ;; common bound instead; never feed already-projected events back in.
      (let [q (mapv #(dissoc % :as-of) queries)
            events (es/read store (if (vector? query) q (first q)))]
        (reify clojure.lang.IReduceInit
          (reduce [_ f init]
            (transduce (take-while #(not (uuid/> (:event/id %) bound))) (completing f) init events))))
      :else (es/read store query))))
(defn- bounded-store [store bound]
  (reify event-protocol/EventStore
    (start [this] this) (stop [this] this)
    (tenants [_] (es/tenants store))
    (append [_ args] (es/append store args))
    (read [_ query]
      (let [bounded #(cond-> % bound (assoc :as-of bound))]
        (read-bounded store (if (vector? query) (mapv bounded query) (bounded query)))))))

(defn project
  ([ctx model queries] (project ctx model queries {}))
  ([ctx model queries options]
   (authorize! ctx)
   (if (empty? queries) {}
       (let [{:keys [reducer-fn version]} (get (rmp/global-read-model-registry) model)
             _ (when-not reducer-fn (throw (ex-info "Unknown ontology projection" {:model model})))
             ;; Grain's default key hashes scope to 32 bits and omits store
             ;; identity. Put the complete namespace digest in its model name.
             name (str model "/" (digest [(store-key ctx) (:tenant-id ctx) queries options]))
             lock (nth locks (mod (hash name) (count locks)))
             reducer (if-let [key (:concept-key options)]
                       (fn [s e] (reducer-fn s (assoc e :projection/concept-key key))) reducer-fn)]
         ;; Serialize same-key updates so a slower caller cannot overwrite a
         ;; newer watermark in Grain's process-global L1.
         (locking lock
           (rmp/p (assoc ctx :cache (or (:cache ctx) no-l2) :event-store (bounded-store (:event-store ctx) (:as-of (first queries))))
                  {:name name :version version :f reducer :query (let [live (mapv #(dissoc % :as-of) queries)]
                                                                            (if (= 1 (count live)) (first live) live))
                   :scope {:queries queries :options options} :l1-ttl-ms 0}))))))
