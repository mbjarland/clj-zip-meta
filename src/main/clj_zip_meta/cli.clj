(ns clj-zip-meta.cli
  "Command-line interface to clj-zip-meta.

  Run via `lein run -- COMMAND FILE [args...]` or `clj -M:cli --
  COMMAND FILE [args...]`. Pass `--json` to any read-only command to
  emit machine-readable JSON instead of human-readable text."
  (:require [clj-zip-meta.core :as zm]
            [clojure.java.io :as jio]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:gen-class))

(def ^:private version "0.4.0")

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
    "  validate  FILE [--crc]        Check metadata (and optionally CRC-32)"
    "  verify    FILE                Decompress every entry and check CRC-32"
    "  repair    FILE [--strip]      Repair offset drift or rebuild a missing CDR"
    ""
    "Global flags:"
    "  --json     Emit JSON on stdout instead of human-readable text"
    "  --version  Print the library version and exit"
    ""]))

;; --- minimal JSON emitter -------------------------------------------------

(defn- bytes->hex-str [^bytes ba]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength ba)]
      (.append sb (format "%02x" (bit-and 0xFF (aget ba i)))))
    (.toString sb)))

(declare ->json)

(defn- escape-string [^String s]
  (let [sb (StringBuilder.)]
    (.append sb \")
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \\        (.append sb "\\\\")
          \"        (.append sb "\\\"")
          \newline  (.append sb "\\n")
          \return   (.append sb "\\r")
          \tab      (.append sb "\\t")
          \backspace (.append sb "\\b")
          \formfeed  (.append sb "\\f")
          (if (< (int c) 0x20)
            (.append sb (format "\\u%04x" (int c)))
            (.append sb c)))))
    (.append sb \")
    (.toString sb)))

(defn- ->json [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (number? v)     (str v)
    (string? v)     (escape-string v)
    (keyword? v)    (escape-string (subs (str v) 1))
    (instance? java.time.LocalDateTime v) (escape-string (str v))
    (map? v)
    (str "{"
         (str/join ","
                   (for [[k val] (sort-by (comp str key) v)]
                     (str (escape-string (if (keyword? k) (subs (str k) 1) (str k)))
                          ":" (->json val))))
         "}")
    (set? v) (->json (vec (sort-by str v)))
    (sequential? v) (str "[" (str/join "," (map ->json v)) "]")
    (and (some? v) (.isArray ^Class (class v))
         (= "byte" (.getName (.getComponentType ^Class (class v)))))
    (escape-string (bytes->hex-str v))
    :else (escape-string (str v))))

;; --- output helpers ------------------------------------------------------

(defn- print-table [headers rows]
  (let [widths (map (fn [i]
                      (apply max (count (nth headers i))
                             (map #(count (str (nth % i))) rows)))
                    (range (count headers)))
        fmt    (->> widths (map #(str "%-" % "s")) (str/join "  "))]
    (println (apply format fmt headers))
    (println (apply format fmt (map #(apply str (repeat % \-)) widths)))
    (doseq [r rows]
      (println (apply format fmt (map str r))))))

(defn- emit [json? human-thunk data]
  (if json?
    (do (println (->json data)) (flush))
    (human-thunk)))

;; --- commands -------------------------------------------------------------

(defn- list-entries [f json?]
  (let [entries (zm/zip-entries f)]
    (emit json?
          (fn []
            (print-table ["compressed" "uncompressed" "modified" "name"]
                         (mapv (fn [e]
                                 [(:compressed-size e)
                                  (:uncompressed-size e)
                                  (or (:last-modified e) "")
                                  (:file-name e)])
                               entries)))
          entries)))

(defn- print-summary [f json?]
  (let [s (zm/summarize f)]
    (emit json? #(pp/pprint s) s)))

(defn- print-meta [f json?]
  (let [m (zm/zip-meta f)]
    (emit json? #(zm/print-zip-meta m) m)))

(defn- comment-cmd [f new-comment json?]
  (if new-comment
    (do (zm/set-zip-comment! f new-comment)
        (emit json? #(println "comment updated")
              {:status "updated" :comment new-comment}))
    (let [c (zm/zip-comment f)]
      (emit json? #(println c) {:comment c}))))

(defn- validate-cmd [f flags json?]
  (let [crc? (contains? (set flags) "--crc")
        r    (zm/validate-zip-meta f :verify-crcs crc?)]
    (emit json?
          (fn []
            (println (str "extra-bytes: " (:extra-bytes r)))
            (if (:valid? r)
              (println "OK")
              (do (println "FAILED")
                  (run! #(println (str "  - " %)) (:issues r)))))
          r)
    (when-not (:valid? r) (System/exit 1))))

(defn- verify-cmd [f json?]
  (let [s (zm/verify-crcs-summary f)]
    (emit json?
          (fn []
            (println (str "total:    " (:total s)))
            (doseq [[st n] (sort-by key (:counts s))]
              (println (format "  %-22s %d" (name st) n)))
            (if (:valid? s)
              (println "OK")
              (do (when (seq (:mismatches s))
                    (println "mismatches:")
                    (doseq [m (:mismatches s)]
                      (println (str "  - " (:file-name m)))))
                  (when (seq (:errors s))
                    (println "errors:")
                    (doseq [m (:errors s)]
                      (println (str "  - " (:file-name m) ": " (:error m))))))))
          s)
    (when-not (:valid? s) (System/exit 1))))

(defn- repair-cmd [f flags json?]
  (let [strip? (contains? (set flags) "--strip")
        r      (zm/repair-zip f {:strip-preamble strip?})]
    (emit json?
          (fn []
            (println (str "status:  " (name (:status r))))
            (println (str "actions: " (str/join ", " (map name (:actions r)))))
            (when (= :failed (:status r))
              (when (:error r) (println (str "error:   " (:error r))))
              (run! #(println (str "issue:   " %)) (or (:issues r) []))))
          (-> r
              (update :status name)
              (update :actions #(mapv name %))))
    (when (= :failed (:status r)) (System/exit 1))))

(defn -main [& args]
  (when (some #(= "--version" %) args)
    (println (str "clj-zip-meta " version))
    (flush)
    (System/exit 0))
  (let [json? (boolean (some #(= "--json" %) args))
        args  (remove #(= "--json" %) args)
        [cmd file & rest] args]
    (case cmd
      "list"     (list-entries file json?)
      "meta"     (print-meta file json?)
      "summary"  (print-summary file json?)
      "comment"  (comment-cmd file (first rest) json?)
      "validate" (validate-cmd file rest json?)
      "verify"   (verify-cmd file json?)
      "repair"   (repair-cmd file rest json?)
      (do (println usage)
          (flush)
          (System/exit (if cmd 1 0))))))
