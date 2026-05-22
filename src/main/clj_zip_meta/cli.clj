(ns clj-zip-meta.cli
  "Command-line interface to clj-zip-meta.

  Run via `lein run -- COMMAND FILE [args...]`, where COMMAND is one
  of `list`, `meta`, `summary`, `comment`, `validate`, or `repair`.
  `lein run` (no args) prints a brief usage banner."
  (:require [clj-zip-meta.core :as zm]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:gen-class))

(def ^:private usage
  (str/join
   "\n"
   ["clj-zip-meta — read and patch the binary metadata of zip and jar files"
    ""
    "Usage: clj-zip-meta COMMAND FILE [args...]"
    ""
    "Commands:"
    "  list      FILE                List entries (name, compressed, uncompressed)"
    "  meta      FILE                Pretty-print the full metadata map"
    "  summary   FILE                Print a high-level summary"
    "  comment   FILE [new-comment]  Print or set the archive comment"
    "  validate  FILE                Check metadata for self-consistency"
    "  repair    FILE [--strip]      Repair offset drift or rebuild a missing CDR"
    ""]))

(defn- list-entries [f]
  (printf "%14s %14s %s%n" "compressed" "uncompressed" "name")
  (printf "%14s %14s %s%n" "----------" "------------" "----")
  (doseq [e (zm/zip-entries f)]
    (printf "%14d %14d %s%n"
            (:compressed-size e)
            (:uncompressed-size e)
            (:file-name e)))
  (flush))

(defn- print-summary [f]
  (pp/pprint (zm/summarize f)))

(defn- print-meta [f]
  (zm/print-zip-meta f))

(defn- comment-cmd [f new-comment]
  (if new-comment
    (do (zm/set-zip-comment! f new-comment)
        (println "comment updated"))
    (println (zm/zip-comment f))))

(defn- validate-cmd [f]
  (let [{:keys [valid? issues extra-bytes]} (zm/validate-zip-meta f)]
    (println (str "extra-bytes: " extra-bytes))
    (if valid?
      (println "OK")
      (do (println "FAILED")
          (run! #(println (str "  - " %)) issues)
          (System/exit 1)))))

(defn- repair-cmd [f flags]
  (let [strip? (contains? (set flags) "--strip")
        r      (zm/repair-zip f {:strip-preamble strip?})]
    (println (str "status:  " (name (:status r))))
    (println (str "actions: " (str/join ", " (map name (:actions r)))))
    (when (= :failed (:status r))
      (when (:error r) (println (str "error:   " (:error r))))
      (run! #(println (str "issue:   " %)) (or (:issues r) []))
      (System/exit 1))))

(defn -main [& args]
  (let [[cmd file & rest] args]
    (case cmd
      "list"     (list-entries file)
      "meta"     (print-meta file)
      "summary"  (print-summary file)
      "comment"  (comment-cmd file (first rest))
      "validate" (validate-cmd file)
      "repair"   (repair-cmd file rest)
      (do (println usage)
          (flush)
          (System/exit (if cmd 1 0))))))
