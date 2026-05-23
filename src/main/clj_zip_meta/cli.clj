(ns clj-zip-meta.cli
  "Command-line interface to clj-zip-meta.

  Run via `lein run -- COMMAND FILE [args...]` or `clj -M:cli --
  COMMAND FILE [args...]`. Pass `--json` to any read-only command to
  emit machine-readable JSON instead of human-readable text."
  (:require [clj-zip-meta.analysis :as za]
            [clj-zip-meta.core :as zm]
            [clojure.java.io :as jio]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:gen-class))

(def ^:private version "0.5.0")

(def ^:private usage
  (str/join
   "\n"
   ["clj-zip-meta — read and patch the binary metadata of zip and jar files"
    ""
    "Usage: clj-zip-meta COMMAND FILE [args...]"
    ""
    "Commands:"
    "  list      FILE [--match PAT]      List entries; optional regex/substring filter"
    "  tree      FILE [--match PAT]      Show entries as a directory tree"
    "  manifest  FILE                    Parse META-INF/MANIFEST.MF (jars)"
    "  jar-info  FILE                    High-level jar info (Main-Class, version, …)"
    "  describe  FILE                    What is this jar? (manifest + pom + counts)"
    "  classes   FILE                    .class entries grouped by Java package"
    "  spi       FILE                    META-INF/services providers (SPI)"
    "  duplicate-classes FILE FILE ...   Class names declared by more than one jar"
    "  cat       FILE ENTRY              Extract one entry to stdout"
    "  meta      FILE                    Pretty-print the full metadata map"
    "  summary   FILE                    Print a high-level summary"
    "  inspect   FILE ENTRY-NAME         Pretty-print everything about one entry"
    "  grep      FILE PATTERN            Print entry names matching PATTERN"
    "  comment   FILE [new-comment]      Print or set the archive comment"
    "  validate  FILE [--crc]            Check metadata (and optionally CRC-32)"
    "  verify    FILE                    Decompress every entry and check CRC-32"
    "  repair    FILE [--strip]          Repair offset drift or rebuild a missing CDR"
    "  diff      FILE-A FILE-B           Compare two archives by file-name + CRC"
    "  layout    FILE [--width N]        Show the physical layout of records in FILE"
    "  hexdump   FILE OFFSET [LENGTH]    Dump bytes around a record offset"
    "  analyze   FILE [--recursive]      Run safety / forensics checks"
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

(defn- list-entries [f flags json?]
  (let [match   (loop [xs flags]
                  (cond
                    (empty? xs)        nil
                    (= "--match" (first xs)) (second xs)
                    :else              (recur (rest xs))))
        ;; Substring by default; if the pattern looks regex-y, compile it.
        match*  (when match
                  (try (re-pattern match)
                       (catch Exception _ match)))
        entries (zm/zip-entries f (cond-> {} match* (assoc :match match*)))]
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

(defn- flag-value
  "Find `--name` in `flags` and return its succeeding token, or
  `default`."
  [flags name default]
  (loop [xs flags]
    (cond
      (empty? xs)         default
      (= name (first xs)) (second xs)
      :else               (recur (rest xs)))))

(defn- tree-cmd [f flags json?]
  (let [match  (flag-value flags "--match" nil)
        match* (when match (try (re-pattern match) (catch Exception _ match)))
        entries (zm/zip-entries f (cond-> {} match* (assoc :match match*)))
        ;; Build a nested map: {part {part {... {nil :leaf-name}}}}.
        tree    (reduce
                  (fn [acc {:keys [file-name]}]
                    (let [segs (str/split file-name #"/")
                          last? #(= (count %) 1)
                          path-vec (vec (butlast segs))
                          leaf     (last segs)]
                      (if (str/ends-with? file-name "/")
                        (update-in acc (mapv #(str % "/") segs) #(or % (sorted-map)))
                        (assoc-in acc (conj (mapv #(str % "/") path-vec) leaf) :file))))
                  (sorted-map)
                  entries)]
    (emit json?
          (fn []
            (println f)
            (letfn [(walk [node prefix]
                      (let [ks (vec (keys node))]
                        (doseq [[i k] (map-indexed vector ks)]
                          (let [last?    (= i (dec (count ks)))
                                marker   (if last? "└── " "├── ")
                                continue (if last? "    " "│   ")
                                v        (get node k)]
                            (println (str prefix marker k))
                            (when (map? v)
                              (walk v (str prefix continue)))))))]
              (walk tree "")))
          tree)))

(defn- inspect-cmd [f entry-name json?]
  (let [e (zm/find-entry f entry-name)]
    (if-not e
      (do (println (str "no entry named " (pr-str entry-name)))
          (System/exit 1))
      (emit json?
            (fn []
              (doseq [k [:file-name :file-comment :compressed-size
                         :uncompressed-size :crc-32 :compression-method
                         :offset :directory? :symlink? :encrypted?
                         :last-modified :unix-mode :dos-attributes]]
                (println (format "%-22s %s" (str (name k) ":") (pr-str (get e k))))))
            e))))

(defn- grep-cmd [f pattern json?]
  (let [re      (re-pattern pattern)
        entries (zm/zip-entries f {:match re})]
    (emit json?
          (fn []
            (doseq [e entries] (println (:file-name e))))
          (mapv :file-name entries))))

(defn- layout-cmd [f flags json?]
  (let [w-arg (flag-value flags "--width" nil)
        width (if w-arg (Long/parseLong w-arg) 0)]
    (if json?
      (do (println (->json (zm/layout f))) (flush))
      (zm/print-layout f {:width width}))))

(defn- manifest-cmd [f json?]
  (if-let [m (zm/manifest f)]
    (emit json?
          (fn []
            (doseq [[k v] (sort-by key m)]
              (println (format "%-30s %s" (str k ":") v))))
          m)
    (do (println "no MANIFEST.MF in this archive") (System/exit 1))))

(defn- jar-info-cmd [f json?]
  (if-let [info (zm/jar-info f)]
    (emit json? #(pp/pprint info) info)
    (do (println "no MANIFEST.MF in this archive") (System/exit 1))))

(defn- describe-cmd [f json?]
  (let [d (zm/describe f)]
    (emit json?
          (fn []
            (let [{:keys [summary jar-info pom-info
                          class-count resource-count top-level-dirs]} d]
              (println (str "entries:        " (:entry-count summary)
                            " (" class-count " classes, " resource-count " resources)"))
              (println (str "uncompressed:   " (:total-uncompressed summary) " bytes"))
              (println (str "compressed:     " (:total-compressed   summary) " bytes"))
              (when (seq (:zip-comment summary))
                (println (str "archive-comment: " (:zip-comment summary))))
              (when jar-info
                (println "manifest:")
                (doseq [[k v] (sort-by key jar-info)]
                  (println (format "  %-22s %s" (str (name k) ":") v))))
              (when pom-info
                (println "maven:")
                (doseq [[k v] (sort-by key pom-info)]
                  (println (format "  %-22s %s" (str (name k) ":") v))))
              (when (seq top-level-dirs)
                (println (str "top-level dirs: " (str/join " " top-level-dirs))))))
          d)))

(defn- classes-cmd [f json?]
  (let [idx (zm/class-index f)]
    (emit json?
          (fn []
            (doseq [[pkg cs] idx]
              (println (str (if (empty? pkg) "<root>" pkg)
                            " (" (count cs) ")"))
              (doseq [c cs] (println (str "  " c)))))
          idx)))

(defn- spi-cmd [f json?]
  (let [m (zm/spi-providers f)]
    (emit json?
          (fn []
            (if (empty? m)
              (println "no META-INF/services entries")
              (doseq [[iface impls] m]
                (println iface)
                (doseq [c impls] (println (str "  " c))))))
          m)))

(defn- duplicate-classes-cmd [jars json?]
  (let [m (zm/duplicate-classes jars)]
    (emit json?
          (fn []
            (if (empty? m)
              (println "no duplicate classes across these jars")
              (doseq [[cls jars] m]
                (println cls)
                (doseq [j jars] (println (str "  " j))))))
          m)
    (when (seq m) (System/exit 2))))

(defn- cat-cmd [f entry-name]
  (if-let [ba (zm/extract-bytes f entry-name)]
    (.write (System/out) ^bytes ba)
    (do (println (str "no entry named " (pr-str entry-name))) (System/exit 1))))

(defn- analyze-cmd [f flags json?]
  (let [recursive? (contains? (set flags) "--recursive")
        r          (za/analyze f)
        nested     (when recursive? (za/analyze-nested f))]
    (emit json?
          (fn []
            (println (str "safe?:               " (:safe? r)))
            (println (str "file-size:           " (:file-size r)))
            (println (str "entry-count:         " (:entry-count r)))
            (println (str "zip64?:              " (:zip64? r)))
            (let [u (:unsafe-entries r)]
              (println (str "unsafe-entries (" (count u) "):"))
              (doseq [{:keys [entry reasons]} u]
                (println (format "  %s -- %s"
                                 (:file-name entry)
                                 (str/join ", " (map name reasons))))))
            (let [b (:zip-bomb-risks r)]
              (println (str "zip-bomb-risks (" (count b) "):"))
              (doseq [e (take 5 b)]
                (println (format "  %.0f:1  %s"
                                 (:compression-ratio e)
                                 (:file-name e)))))
            (let [g (:gap-data r)]
              (println (str "gap-data (" (count g) "):"))
              (doseq [{:keys [start end length]} g]
                (println (format "  %d-%d  (%d bytes)" start end length))))
            (let [m (:cdr-lfh-mismatches r)]
              (println (str "cdr-lfh-mismatches (" (count m) "):"))
              (doseq [{:keys [file-name differences]} m]
                (println (format "  %s -- fields differ: %s"
                                 file-name
                                 (str/join ", " (map name (keys differences)))))))
            (when (seq nested)
              (println)
              (println (str "nested archives (" (count nested) "):"))
              (doseq [{:keys [entry-name analysis error]} nested]
                (println (format "  %s %s"
                                 (if (:safe? analysis) "[OK]" "[!!]")
                                 entry-name))
                (when error
                  (println (str "      error: " error)))
                (when-let [u (seq (:unsafe-entries analysis))]
                  (doseq [{e :entry rs :reasons} u]
                    (println (format "      unsafe: %s -- %s"
                                     (:file-name e)
                                     (str/join "," (map name rs)))))))))
          (cond-> r recursive? (assoc :nested nested)))
    (when-not (:safe? r) (System/exit 1))))

(defn- diff-cmd [a b json?]
  (let [d (zm/diff a b)]
    (emit json?
          (fn []
            (let [{:keys [added removed changed same]} d]
              (println (str "same:    " same))
              (when (seq added)
                (println (str "added (" (count added) "):"))
                (doseq [e added]
                  (println (format "  + %14d  %s"
                                   (:uncompressed-size e) (:file-name e)))))
              (when (seq removed)
                (println (str "removed (" (count removed) "):"))
                (doseq [e removed]
                  (println (format "  - %14d  %s"
                                   (:uncompressed-size e) (:file-name e)))))
              (when (seq changed)
                (println (str "changed (" (count changed) "):"))
                (doseq [{:keys [file-name before after]} changed]
                  (println (format "  ~ %s" file-name))
                  (println (format "      before: csize=%d usize=%d crc=%d"
                                   (:compressed-size before)
                                   (:uncompressed-size before)
                                   (:crc-32 before)))
                  (println (format "      after:  csize=%d usize=%d crc=%d"
                                   (:compressed-size after)
                                   (:uncompressed-size after)
                                   (:crc-32 after)))))))
          d)
    (when (or (seq (:added d)) (seq (:removed d)) (seq (:changed d)))
      (System/exit 2))))

(defn- hexdump-cmd [f offset length json?]
  (let [off (Long/parseLong offset)
        len (if length (Long/parseLong length) 256)
        s   (zm/hexdump f off len)]
    (emit json? #(print s) {:offset off :length len :hex s})))

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
      "list"     (list-entries file rest json?)
      "tree"     (tree-cmd file rest json?)
      "meta"     (print-meta file json?)
      "summary"  (print-summary file json?)
      "inspect"  (inspect-cmd file (first rest) json?)
      "grep"     (grep-cmd file (first rest) json?)
      "comment"  (comment-cmd file (first rest) json?)
      "validate" (validate-cmd file rest json?)
      "verify"   (verify-cmd file json?)
      "repair"   (repair-cmd file rest json?)
      "diff"     (diff-cmd file (first rest) json?)
      "layout"   (layout-cmd file rest json?)
      "hexdump"  (hexdump-cmd file (first rest) (second rest) json?)
      "analyze"  (analyze-cmd file rest json?)
      "manifest" (manifest-cmd file json?)
      "jar-info" (jar-info-cmd file json?)
      "describe" (describe-cmd file json?)
      "classes"  (classes-cmd file json?)
      "spi"      (spi-cmd file json?)
      "duplicate-classes" (duplicate-classes-cmd (cons file rest) json?)
      "cat"      (cat-cmd file (first rest))
      (do (println usage)
          (flush)
          (System/exit (if cmd 1 0))))))
