(ns lint-read-model-versions
  (:require [rewrite-clj.zip :as z]))


(defn- children [loc]
  (take-while some? (iterate z/right (z/down loc))))

(defn- value [loc]
  (when loc
    (try (z/sexpr loc) (catch Exception _ ::expression))))

(defn- call-named? [loc names]
  (and (= :list (z/tag loc))
       (let [head (value (z/down loc))]
         (and (symbol? head) (contains? names (name head))))))

(defn- inactive? [loc]
  (some #(or (= :uneval (z/tag %)) (call-named? % #{"comment"}))
        (take-while some? (iterate z/up loc))))

(defn- finding [file loc message]
  (let [[row col] (when loc (z/position loc))]
    {:file file :row row :col col :message message}))

(defn- check-version [file loc label]
  (when-not (= 1 (value loc))
    (finding file loc (str label " must use literal version 1, found " (z/string loc)))))

(defn- map-entries [loc]
  (partition 2 (children loc)))

(defn- check-options [file loc]
  (for [[key version] (map-entries loc)
        :when (= :version (value key))
        :let [hit (check-version file version "Read-model :version")]
        :when hit]
    hit))

(defn- check-subscriptions [file loc]
  (cond
    (= :map (z/tag loc))
    (keep (fn [[model version]]
            (check-version file version (str "Read-model dependency " (z/string model))))
          (map-entries loc))

    (and (call-named? loc #{"query-models"}) (= 2 (count (children loc)))) []

    :else
    [(finding file loc ":grain/read-models must be a literal version-1 map or a query-models call")]))

(defn lint-source [file source]
  (try
    (vec
     (mapcat
      (fn [loc]
        (when-not (inactive? loc)
          (cond
            (call-named? loc #{"defreadmodel" "register-read-model!"})
            (let [options (nth (children loc) 3 nil)]
              (cond
                (= :map (some-> options z/tag)) (check-options file options)
                (call-named? loc #{"register-read-model!"})
                [(finding file loc "Read-model registration options must be a literal map with version 1")]))

            (= :map (z/tag loc))
            (mapcat (fn [[key subscriptions]]
                      (when (= :grain/read-models (value key))
                        (check-subscriptions file subscriptions)))
                    (map-entries loc)))))
      (take-while (complement z/end?)
                  (iterate z/next (z/of-string source {:track-position? true})))))
    (catch Exception error
      [(finding file nil (str "Cannot lint source: " (ex-message error)))])))
