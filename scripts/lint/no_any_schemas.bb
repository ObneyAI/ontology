(ns lint-no-any-schemas
  (:require [rewrite-clj.zip :as z]))

(defn- head [loc]
  (some-> loc z/down z/string))

(defn- schema-context? [file loc]
  (some (fn [ancestor]
          (case (z/tag ancestor)
            :list (or (re-matches #"(?:[^/]+/)?defschemas?" (or (head ancestor) ""))
                      (and (= file "src/ai/obney/ontology/core/schemas.clj")
                           (= "def" (head ancestor))))
            :vector (contains? #{":map" ":map-of" ":vector" ":tuple" ":set" ":sequential"
                                 ":or" ":and" ":maybe" ":=>" ":function"} (head ancestor))
            false))
        (take-while some? (iterate z/up (z/up loc)))))

(defn lint-source [file source]
  (binding [*read-eval* false]
    (let [root (z/of-string source {:track-position? true})]
      (vec (for [node (take-while (complement z/end?) (iterate z/next root))
                 :when (and (= :token (z/tag node)) (= ":any" (z/string node))
                            (schema-context? file node))
                 :let [[row col] (z/position node)]]
             {:file file :row row :col col :message "Use a concrete Malli schema instead of :any"})))))
