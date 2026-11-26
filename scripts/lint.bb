#!/usr/bin/env bb

(ns ontology-lint
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(load-file "scripts/lint/code_comments.bb")
(load-file "scripts/lint/explicit_dependencies.bb")
(load-file "scripts/lint/no_declare.bb")
(load-file "scripts/lint/no_sql_delete.bb")
(load-file "scripts/lint/grain_declarations.bb")
(load-file "scripts/lint/grain_logic.bb")
(load-file "scripts/lint/read_model_versions.bb")
(load-file "scripts/lint/no_any_schemas.bb")

(def roots ["src" "test" "bench" "scripts" "deps.edn"])
(def clojure-extensions #{"clj" "cljc" "cljs" "bb"})

(defn checks [file]
  (let [ext (fs/extension file)
        clojure? (contains? clojure-extensions ext)
        fixtures? (or (str/starts-with? file "test/") (str/starts-with? file "scripts/tests/"))
        production? (and clojure? (str/starts-with? file "src/"))]
    (cond-> []
      (contains? lint-no-sql-delete/source-extensions ext) (conj lint-no-sql-delete/lint-source)
      clojure? (into [lint-code-comments/lint-source lint-explicit-dependencies/lint-source lint-no-declare/lint-source])
      (and clojure? (not fixtures?)) (into [lint-grain-declarations/lint-source lint-read-model-versions/lint-source])
      production? (into [lint-grain-logic/lint-source lint-no-any-schemas/lint-source]))))

(defn source-files [paths]
  (->> paths
       (mapcat #(if (fs/directory? %) (fs/glob % "**") [(fs/path %)]))
       (filter fs/regular-file?)
       (map #(str (fs/relativize (fs/cwd) (fs/normalize (fs/absolutize %)))))
       (filter #(seq (checks %)))
       distinct sort))

(defn lint-file [file]
  (let [source (slurp file)]
    (mapcat (fn [check]
              (try (vec (check file source))
                   (catch Exception e
                     [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message e))}])))
            (checks file))))

(defn -main [& paths]
  (doseq [path paths]
    (when-not (fs/exists? path) (throw (ex-info "Lint input does not exist" {:path path}))))
  (let [files (source-files (or (seq paths) roots))
        findings (mapcat lint-file files)]
    (doseq [{:keys [file row col message]} findings]
      (println (format "%s:%s:%s: %s" file row col message)))
    (println (format "%d lint findings in %d files checked." (count findings) (count files)))
    (if (seq findings) 1 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
