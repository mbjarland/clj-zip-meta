(ns clj-zip-meta.cli
  "Command-line interface to clj-zip-meta.

  Run via `lein run -- COMMAND FILE [args...]` or `clj -M:cli --
  COMMAND FILE [args...]`. Pass `--json` to any read-only command to
  emit machine-readable JSON instead of human-readable text."
  (:require [clj-zip-meta.analysis :as za]
            [clj-zip-meta.core :as zm]
            [clojure.java.io :as jio]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.io File))
  (:gen-class))

(def ^:private version "0.5.2")

(def ^:private usage
  (str/join
   "\n"
   ["clj-zip-meta — read and patch the binary metadata of zip and jar files"
    ""
    "Usage: clj-zip-meta COMMAND FILE [args...]"
    ""
    "Read & explore"
    "  list      FILE [--match PAT]      List entries; optional regex/substring filter"
    "  tree      FILE [--match PAT]      Show entries as a directory tree"
    "  grep      FILE PATTERN            Print entry names matching PATTERN"
    "  inspect   FILE ENTRY              Pretty-print everything about one entry"
    "  cat       FILE ENTRY              Extract one entry to stdout"
    "  meta      FILE                    Pretty-print the full metadata map"
    "  summary   FILE                    Print a high-level summary"
    "  layout    FILE [--width N]        Show the physical layout of records"
    "  hexdump   FILE OFFSET [LENGTH]    Dump bytes around a record offset"
    ""
    "Jar understanding"
    "  describe  FILE                    What is this jar? (manifest + pom + counts)"
    "  manifest  FILE                    Parse META-INF/MANIFEST.MF"
    "  jar-info  FILE                    Distilled jar info (Main-Class, version, …)"
    "  classes   FILE [--all]            .class entries grouped by Java package"
    "  spi       FILE                    META-INF/services providers (SPI)"
    "  duplicate-classes FILE FILE ...   Class names declared by more than one jar"
    ""
    "Integrity & safety"
    "  validate  FILE [--crc]            Check metadata (and optionally CRC-32)"
    "  verify    FILE                    Decompress every entry and check CRC-32"
    "  analyze   FILE [--recursive]      Safety / forensics checks"
    "  diff      FILE-A FILE-B           Compare two archives by file-name + CRC"
    ""
    "Modify"
    "  comment   FILE [NEW-COMMENT]      Print or set the archive comment"
    "  repair    FILE [--strip]          Repair offset drift or rebuild a missing CDR"
    ""
    "Global flags"
    "  --json       Emit JSON on stdout instead of human-readable text"
    "  --no-color   Disable ANSI colour codes"
    "  --version    Print the library version and exit"
    ""]))

;; ============================================================================
;; TUI primitives

(def ^:dynamic ^:private *color?* false)

(defn- ansi [code s]
  (if *color?* (str "[" code "m" s "[0m") (str s)))

(defn- bold   [s] (ansi "1"  s))
(defn- dim    [s] (ansi "2"  s))
(defn- red    [s] (ansi "31" s))
(defn- green  [s] (ansi "32" s))
(defn- yellow [s] (ansi "33" s))
(defn- cyan   [s] (ansi "36" s))

(defn- fmt-num
  "Integer with comma-thousands separators (e.g. 3,770)."
  [n]
  (if (number? n) (format "%,d" (long n)) (str n)))

;; ANSI-aware width / padding. format's "%-Ns" counts the escape codes,
;; so a colored "ok" appears wider than a plain "empty" and the
;; columns drift. visible / vlen / pad-left / pad-right operate on the
;; *visible* length only.

(def ^:private ansi-re #"\[[0-9;]*m")

(defn- visible [s] (str/replace (str s) ansi-re ""))
(defn- vlen    [s] (count (visible s)))

(defn- pad-right [s w]
  (let [s (str s) n (- w (vlen s))]
    (if (pos? n) (str s (apply str (repeat n \space))) s)))

(defn- pad-left [s w]
  (let [s (str s) n (- w (vlen s))]
    (if (pos? n) (str (apply str (repeat n \space)) s) s)))

(defn- path-short
  "Trim a file path for display: keep filename only when run in a
  modern terminal, full path in JSON output."
  [^String p]
  (.getName (jio/file p)))

(defn- title
  "Print a one-line bold command title."
  [cmd path]
  (println (bold (str cmd "  " (path-short path)))))

(defn- subtitle
  "Print a dim header inside a command's body."
  [s]
  (println)
  (println (dim s)))

(defn- numeric? [v]
  (or (number? v)
      (and (string? v) (re-matches #"-?\d+(?:,\d{3})*" (visible v)))))

(defn- max-width [strs]
  (apply max 0 (map vlen strs)))

(defn- kv-block
  "Print a block of `[label value]` pairs with consistent
  alignment. Numeric values are right-aligned when the entire block
  is numeric; otherwise values are left-aligned. ANSI-aware: colour
  codes in labels or values don't break column widths. Pairs whose
  value is nil are skipped."
  ([pairs] (kv-block 2 pairs))
  ([indent pairs]
   (let [pairs    (remove (fn [[_ v]] (nil? v)) pairs)
         labels   (map (comp str first)  pairs)
         vals     (map (comp str second) pairs)
         lw       (max-width labels)
         all-num? (and (seq pairs) (every? numeric? (map second pairs)))
         vw       (when all-num? (max-width vals))
         pad      (apply str (repeat indent \space))]
     (doseq [[l v] pairs]
       (let [ls (str l) vs (str v)]
         (if all-num?
           (println (str pad (pad-right ls lw) "  " (pad-left vs vw)))
           (println (str pad (pad-right ls lw) "  " vs))))))))

(defn- print-table
  "Print a table with `headers` and `rows`. Numeric columns
  right-align, text columns left-align. Header row is dim, separator
  is a light horizontal rule. ANSI-aware: colour codes don't break
  column widths."
  [headers rows]
  (let [ncols     (count headers)
        col-vals  (mapv (fn [i] (map #(nth % i) rows)) (range ncols))
        col-num?  (mapv (fn [vs] (and (seq vs) (every? #(or (nil? %) (number? %)) vs))) col-vals)
        cell-str  (fn [v n?]
                    (cond
                      (nil? v) ""
                      n?       (fmt-num v)
                      :else    (str v)))
        widths    (mapv (fn [i]
                          (apply max (vlen (str (nth headers i)))
                                 (map #(vlen (cell-str (nth % i) (nth col-num? i))) rows)))
                        (range ncols))
        join-row  (fn [cells] (str/join "  " cells))
        pad-cell  (fn [v w n?] (if n? (pad-left v w) (pad-right v w)))]
    ;; Header row: always left-aligned.
    (println (dim (join-row (map #(pad-right (str (nth headers %)) (nth widths %))
                                  (range ncols)))))
    ;; Separator rule
    (println (dim (join-row (map #(apply str (repeat (nth widths %) \─))
                                  (range ncols)))))
    (doseq [r rows]
      (println (join-row
                 (map (fn [i]
                        (pad-cell (cell-str (nth r i) (nth col-num? i))
                                  (nth widths i)
                                  (nth col-num? i)))
                      (range ncols)))))))

(defn- status-line
  "Print a trailing OK / FAILED line. `text` defaults to the verdict
  word itself."
  ([ok?] (status-line ok? (if ok? "OK" "FAILED")))
  ([ok? text]
   (println)
   (println (if ok? (green text) (red text)))))

;; ============================================================================
;; JSON emitter (unchanged from the previous CLI, just minus the now
;; redundant clojure.walk require)

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
    (nil? v)                              "null"
    (boolean? v)                          (if v "true" "false")
    (number? v)                           (str v)
    (string? v)                           (escape-string v)
    (keyword? v)                          (escape-string (subs (str v) 1))
    (instance? java.time.LocalDateTime v) (escape-string (str v))
    (map? v)
    (str "{"
         (str/join ","
                   (for [[k val] (sort-by (comp str key) v)]
                     (str (escape-string (if (keyword? k) (subs (str k) 1) (str k)))
                          ":" (->json val))))
         "}")
    (set? v)        (->json (vec (sort-by str v)))
    (sequential? v) (str "[" (str/join "," (map ->json v)) "]")
    (and (some? v) (.isArray ^Class (class v))
         (= "byte" (.getName (.getComponentType ^Class (class v)))))
    (escape-string (bytes->hex-str v))
    :else (escape-string (str v))))

(defn- emit [json? human-thunk data]
  (if json?
    (do (println (->json data)) (flush))
    (human-thunk)))

(defn- flag-value
  "Find `--name` in `flags` and return its succeeding token, or
  `default`."
  [flags name default]
  (loop [xs flags]
    (cond
      (empty? xs)              default
      (= name (first xs))      (second xs)
      :else                    (recur (rest xs)))))

;; ============================================================================
;; Commands

(defn- list-cmd [f flags json?]
  (let [match   (flag-value flags "--match" nil)
        match*  (when match
                  (try (re-pattern match) (catch Exception _ match)))
        entries (zm/zip-entries f (cond-> {} match* (assoc :match match*)))]
    (emit json?
          (fn []
            (title "list" f)
            (when match (subtitle (str "matching " (pr-str match))))
            (println)
            (print-table
              ["name" "compressed" "uncompressed" "modified"]
              (mapv (fn [e]
                      [(:file-name e)
                       (:compressed-size e)
                       (:uncompressed-size e)
                       (if-let [m (:last-modified e)] (str m) "")])
                    entries))
            (println)
            (println (dim (str (count entries) " entr" (if (= 1 (count entries)) "y" "ies")))))
          entries)))

(defn- summary-cmd [f json?]
  (let [s (zm/summarize f)]
    (emit json?
          (fn []
            (title "summary" f)
            (println)
            (kv-block
              [["entries"            (fmt-num (:entry-count s))]
               ["extra bytes"        (fmt-num (:extra-bytes s))]
               ["total compressed"   (fmt-num (:total-compressed s))]
               ["total uncompressed" (fmt-num (:total-uncompressed s))]
               ["zip comment"
                (let [c (:zip-comment s)]
                  (if (str/blank? c) (dim "(none)") c))]]))
          s)))

(defn- meta-cmd [f json?]
  (let [m (zm/zip-meta f)]
    (emit json? #(zm/print-zip-meta m) m)))

(defn- comment-cmd [f new-comment json?]
  (if new-comment
    (do (zm/set-zip-comment! f new-comment)
        (emit json?
              (fn []
                (title "comment" f)
                (println)
                (kv-block [["new comment" new-comment]])
                (status-line true "UPDATED"))
              {:status "updated" :comment new-comment}))
    (let [c (zm/zip-comment f)]
      (emit json?
            (fn []
              (title "comment" f)
              (println)
              (if (str/blank? c)
                (println (dim "  (no comment)"))
                (kv-block [["comment" c]])))
            {:comment c}))))

(defn- validate-cmd [f flags json?]
  (let [crc? (contains? (set flags) "--crc")
        r    (zm/validate-zip-meta f :verify-crcs crc?)]
    (emit json?
          (fn []
            (title "validate" f)
            (println)
            (kv-block [["extra bytes" (fmt-num (:extra-bytes r))]
                       ["crc check"   (if crc? "yes" "no")]])
            (when (seq (:issues r))
              (subtitle "issues")
              (doseq [i (:issues r)]
                (println (str "  " (red "•") " " i))))
            (status-line (:valid? r)))
          r)
    (when-not (:valid? r) (System/exit 1))))

(defn- verify-cmd [f json?]
  (let [s (zm/verify-crcs-summary f)]
    (emit json?
          (fn []
            (title "verify" f)
            (println)
            (let [counts (:counts s)
                  ;; Stable, sensible ordering: ok, mismatch, empty,
                  ;; unsupported-method, error, then anything else.
                  order  [:ok :mismatch :empty :unsupported-method :error]
                  shown  (concat (filter counts order)
                                 (remove (set order) (keys counts)))
                  rows   (for [k shown] [(name k) (get counts k 0)])
                  colorize (fn [k]
                             (case k
                               "ok"                 (green k)
                               "mismatch"           (red   k)
                               "error"              (red   k)
                               "unsupported-method" (yellow k)
                               k))
                  lw     (max 5 (max-width (map first rows)))     ; "total" min
                  vw     (max-width (cons (fmt-num (:total s))
                                          (map #(fmt-num (second %)) rows)))]
              (doseq [[k n] rows]
                (println (str "  " (pad-right (colorize k) lw)
                              "  " (pad-left (fmt-num n) vw))))
              (println (str "  " (dim (apply str (repeat (+ lw 2 vw) \─)))))
              (println (str "  " (pad-right (bold "total") lw)
                            "  " (pad-left (fmt-num (:total s)) vw))))
            (when (seq (:mismatches s))
              (subtitle "mismatches")
              (doseq [m (:mismatches s)]
                (println (str "  " (red "•") " " (:file-name m)))))
            (when (seq (:errors s))
              (subtitle "errors")
              (doseq [m (:errors s)]
                (println (str "  " (red "•") " " (:file-name m) " -- " (:error m)))))
            (status-line (:valid? s)))
          s)
    (when-not (:valid? s) (System/exit 1))))

(defn- tree-cmd [f flags json?]
  (let [match   (flag-value flags "--match" nil)
        match*  (when match (try (re-pattern match) (catch Exception _ match)))
        entries (zm/zip-entries f (cond-> {} match* (assoc :match match*)))
        tree    (reduce
                  (fn [acc {:keys [file-name]}]
                    (let [segs     (str/split file-name #"/")
                          path-vec (vec (butlast segs))
                          leaf     (last segs)]
                      (if (str/ends-with? file-name "/")
                        (update-in acc (mapv #(str % "/") segs)
                                   #(or % (sorted-map)))
                        (assoc-in acc
                                  (conj (mapv #(str % "/") path-vec) leaf)
                                  :file))))
                  (sorted-map)
                  entries)]
    (emit json?
          (fn []
            (title "tree" f)
            (println)
            (letfn [(walk [node prefix]
                      (let [ks (vec (keys node))]
                        (doseq [[i k] (map-indexed vector ks)]
                          (let [last?    (= i (dec (count ks)))
                                marker   (if last? "└── " "├── ")
                                continue (if last? "    " "│   ")
                                v        (get node k)
                                dir?     (map? v)]
                            (println (str prefix (dim marker)
                                          (if dir? (cyan k) k)))
                            (when dir? (walk v (str prefix continue)))))))]
              (walk tree "")))
          tree)))

(defn- inspect-cmd [f entry-name json?]
  (let [e (zm/find-entry f entry-name)]
    (if-not e
      (do (println (red (str "no entry named " (pr-str entry-name))))
          (System/exit 1))
      (emit json?
            (fn []
              (title "inspect" f)
              (subtitle entry-name)
              (let [or-none  (fn [v]
                               (if (or (nil? v) (and (string? v) (str/blank? v)))
                                 (dim "(none)") v))
                    fmt-attrs (fn [s] (if (seq s)
                                        (str/join " " (map name s))
                                        (dim "(none)")))
                    fmt-mode  (fn [m]
                                (if m (format "%d (0o%o)" m m)
                                      (dim "(none)")))]
                (kv-block
                  [["file-name"          (:file-name e)]
                   ["file-comment"       (or-none (:file-comment e))]
                   ["compressed-size"    (fmt-num (:compressed-size e))]
                   ["uncompressed-size"  (fmt-num (:uncompressed-size e))]
                   ["compression-method" (:compression-method e)]
                   ["crc-32"             (:crc-32 e)]
                   ["offset"             (fmt-num (:offset e))]
                   ["last-modified"      (or-none (:last-modified e))]
                   ["directory?"         (:directory? e)]
                   ["symlink?"           (:symlink? e)]
                   ["encrypted?"         (:encrypted? e)]
                   ["unix-mode"          (fmt-mode (:unix-mode e))]
                   ["dos-attributes"     (fmt-attrs (:dos-attributes e))]])))
            e))))

(defn- grep-cmd [f pattern json?]
  (let [re      (re-pattern pattern)
        entries (zm/zip-entries f {:match re})]
    (emit json?
          (fn []
            (doseq [e entries] (println (:file-name e))))
          (mapv :file-name entries))
    (when (empty? entries) (System/exit 1))))

(defn- layout-cmd [f flags json?]
  (let [w-arg (flag-value flags "--width" nil)
        width (if w-arg (Long/parseLong w-arg) 0)]
    (if json?
      (do (println (->json (zm/layout f))) (flush))
      (do (title "layout" f) (println)
          (zm/print-layout f {:width width})))))

(defn- manifest-cmd [f json?]
  (if-let [m (zm/manifest f)]
    (emit json?
          (fn []
            (title "manifest" f)
            (println)
            (kv-block (sort-by first m)))
          m)
    (do (println (red "no MANIFEST.MF in this archive"))
        (System/exit 1))))

(defn- jar-info-cmd [f json?]
  (if-let [info (zm/jar-info f)]
    (emit json?
          (fn []
            (title "jar-info" f)
            (println)
            (kv-block (for [[k v] (sort-by key info)] [(name k) v])))
          info)
    (do (println (red "no MANIFEST.MF in this archive"))
        (System/exit 1))))

(defn- describe-cmd [f json?]
  (let [d (zm/describe f)]
    (emit json?
          (fn []
            (let [{:keys [summary jar-info pom-info
                          class-count resource-count top-level-dirs]} d]
              (title "describe" f)
              (println)
              (kv-block
                [["entries"            (str (fmt-num (:entry-count summary))
                                            "  "
                                            (dim (str "("
                                                      (fmt-num class-count) " classes, "
                                                      (fmt-num resource-count) " resources)")))]
                 ["uncompressed bytes" (fmt-num (:total-uncompressed summary))]
                 ["compressed bytes"   (fmt-num (:total-compressed   summary))]
                 ["zip comment"
                  (let [c (:zip-comment summary)]
                    (if (str/blank? c) (dim "(none)") c))]])
              (when jar-info
                (subtitle "manifest")
                (kv-block (for [[k v] (sort-by key jar-info)] [(name k) v])))
              (when pom-info
                (subtitle "maven")
                (kv-block (for [[k v] (sort-by key pom-info)] [(name k) v])))
              (when (seq top-level-dirs)
                (subtitle "top-level dirs")
                (doseq [d top-level-dirs]
                  (println (str "  " (cyan d)))))))
          d)))

(defn- classes-cmd [f flags json?]
  (let [all?   (contains? (set flags) "--all")
        idx    (zm/class-index f)
        total  (reduce + 0 (map count (vals idx)))]
    (emit json?
          (fn []
            (title "classes" f)
            (println)
            (cond
              (empty? idx)
              (println (dim "  (no .class entries)"))

              all?
              (do
                (doseq [[pkg cs] idx]
                  (println (cyan (if (empty? pkg) "<root>" pkg))
                           (dim (str "(" (fmt-num (count cs)) ")")))
                  (doseq [c cs] (println (str "  " c)))
                  (println))
                (println (dim (str (fmt-num total) " classes in "
                                   (count idx) " packages"))))

              :else
              (do
                (kv-block
                  (for [[pkg cs] (sort-by (comp - count val) idx)]
                    [(if (empty? pkg) "<root>" pkg) (fmt-num (count cs))]))
                (println)
                (println (dim (str (fmt-num total) " classes in "
                                   (count idx) " packages")))
                (println (dim "(pass --all to list every class)")))))
          idx)))

(defn- spi-cmd [f json?]
  (let [m (zm/spi-providers f)]
    (emit json?
          (fn []
            (title "spi" f)
            (if (empty? m)
              (do (println) (println (dim "  (no META-INF/services entries)")))
              (do (println)
                  (doseq [[iface impls] m]
                    (println (cyan iface))
                    (doseq [c impls] (println (str "  " c)))
                    (println)))))
          m)))

(defn- duplicate-classes-cmd [jars json?]
  (let [m (zm/duplicate-classes jars)]
    (emit json?
          (fn []
            (println (bold (str "duplicate-classes  "
                                (fmt-num (count jars)) " jars")))
            (println)
            (if (empty? m)
              (println (str (green "OK") (dim " — no duplicate classes")))
              (let [all-jars  (distinct (mapcat val m))
                    short-w   (apply max 0 (map #(vlen (path-short %)) all-jars))]
                (println (red (str (fmt-num (count m))
                                   " duplicate class(es)")))
                (println)
                (doseq [[cls jars] m]
                  (println (cyan cls))
                  (doseq [j jars]
                    (println (str "  " (pad-right (path-short j) short-w)
                                  "  " (dim j))))))))
          m)
    (when (seq m) (System/exit 2))))

(defn- cat-cmd [f entry-name]
  (if-let [ba (zm/extract-bytes f entry-name)]
    (.write (System/out) ^bytes ba)
    (do (println (red (str "no entry named " (pr-str entry-name))))
        (System/exit 1))))

(defn- analyze-cmd [f flags json?]
  (let [recursive? (contains? (set flags) "--recursive")
        r          (za/analyze f)
        nested     (when recursive? (za/analyze-nested f))
        nested-safe? (or (nil? nested)
                         (every? #(get-in % [:analysis :safe?] true) nested))
        overall-safe? (and (:safe? r) nested-safe?)]
    (emit json?
          (fn []
            (title "analyze" f)
            (println)
            (kv-block
              [["safe?"        (if overall-safe? (green "yes") (red "no"))]
               ["file size"    (fmt-num (:file-size r))]
               ["entries"      (fmt-num (:entry-count r))]
               ["zip64?"       (if (:zip64? r) (yellow "yes") "no")]])
            (let [u (:unsafe-entries r)]
              (when (seq u)
                (subtitle (str "unsafe entries (" (count u) ")"))
                (doseq [{:keys [entry reasons]} u]
                  (println (format "  %s %s %s"
                                   (red "!")
                                   (:file-name entry)
                                   (dim (str "[" (str/join ", " (map name reasons)) "]")))))))
            (let [b (:zip-bomb-risks r)]
              (when (seq b)
                (subtitle (str "zip-bomb risks (" (count b) ")"))
                (doseq [e (take 5 b)]
                  (println (format "  %s %.0f:1  %s"
                                   (red "!")
                                   (:compression-ratio e)
                                   (:file-name e))))))
            (let [g (:gap-data r)]
              (when (seq g)
                (subtitle (str "gap data (" (count g) ")"))
                (doseq [{:keys [start end length]} g]
                  (println (format "  %s %s-%s (%s bytes)"
                                   (yellow "!")
                                   (fmt-num start) (fmt-num end)
                                   (fmt-num length))))))
            (let [m (:cdr-lfh-mismatches r)]
              (when (seq m)
                (subtitle (str "cdr / lfh mismatches (" (count m) ")"))
                (doseq [{:keys [file-name differences]} m]
                  (println (format "  %s %s %s"
                                   (red "!")
                                   file-name
                                   (dim (str/join ", " (map name (keys differences)))))))))
            (when (seq nested)
              (subtitle (str "nested archives (" (count nested) ")"))
              (doseq [{:keys [entry-name analysis error]} nested]
                (println (format "  %s %s"
                                 (if (:safe? analysis) (green "✓") (red "✗"))
                                 entry-name))
                (when error
                  (println (str "      " (red "error: ") error)))
                (doseq [{e :entry rs :reasons} (:unsafe-entries analysis)]
                  (println (format "      %s %s %s"
                                   (red "!")
                                   (:file-name e)
                                   (dim (str/join ", " (map name rs))))))))
            (status-line overall-safe? (if overall-safe? "SAFE" "UNSAFE")))
          (cond-> r recursive? (assoc :nested nested)))
    (when-not overall-safe? (System/exit 1))))

(defn- diff-cmd [a b json?]
  (let [d (zm/diff a b)]
    (emit json?
          (fn []
            (println (bold (str "diff  " (path-short a)
                                "  ⇄  " (path-short b))))
            (let [{:keys [added removed changed same]} d
                  total-changes (+ (count added) (count removed) (count changed))]
              (println)
              (kv-block
                [["unchanged" (fmt-num same)]
                 ["added"     (let [n (fmt-num (count added))]
                                (if (zero? (count added)) n (green n)))]
                 ["removed"   (let [n (fmt-num (count removed))]
                                (if (zero? (count removed)) n (red n)))]
                 ["changed"   (let [n (fmt-num (count changed))]
                                (if (zero? (count changed)) n (yellow n)))]])
              (let [size-w (apply max 1
                                   (map #(vlen (fmt-num (:uncompressed-size %)))
                                        (concat added removed)))]
                (when (seq added)
                  (subtitle (str "added (" (count added) ")"))
                  (doseq [e added]
                    (println (str "  " (green "+") " "
                                  (pad-left (fmt-num (:uncompressed-size e)) size-w)
                                  "  " (:file-name e)))))
                (when (seq removed)
                  (subtitle (str "removed (" (count removed) ")"))
                  (doseq [e removed]
                    (println (str "  " (red "-") " "
                                  (pad-left (fmt-num (:uncompressed-size e)) size-w)
                                  "  " (:file-name e))))))
              (when (seq changed)
                (subtitle (str "changed (" (count changed) ")"))
                (doseq [{:keys [file-name before after]} changed]
                  (println (str "  " (yellow "~") " " file-name))
                  (println (str "      " (dim "before:")
                                " csize=" (fmt-num (:compressed-size before))
                                " usize=" (fmt-num (:uncompressed-size before))
                                " crc="   (:crc-32 before)))
                  (println (str "      " (dim "after: ")
                                " csize=" (fmt-num (:compressed-size after))
                                " usize=" (fmt-num (:uncompressed-size after))
                                " crc="   (:crc-32 after)))))
              (when (zero? total-changes)
                (println)
                (println (green "identical")))))
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
        r      (zm/repair-zip f {:strip-preamble strip?})
        ok?    (= :ok (:status r))]
    (emit json?
          (fn []
            (title "repair" f)
            (println)
            (kv-block
              [["status"  (if ok? (green "ok") (red "failed"))]
               ["actions" (if (seq (:actions r))
                            (str/join ", " (map name (:actions r)))
                            (dim "(none)"))]])
            (when-not ok?
              (when (:error r)
                (subtitle "error")
                (println (str "  " (red (:error r)))))
              (when (seq (:issues r))
                (subtitle "issues")
                (doseq [i (:issues r)]
                  (println (str "  " (red "•") " " i)))))
            (status-line ok?))
          (-> r
              (update :status name)
              (update :actions #(mapv name %))))
    (when-not ok? (System/exit 1))))

;; ============================================================================
;; Entry point

(defn -main [& args]
  (when (some #(= "--version" %) args)
    (println (str "clj-zip-meta " version))
    (flush)
    (System/exit 0))
  (let [json?    (boolean (some #(= "--json" %) args))
        no-col?  (boolean (some #(= "--no-color" %) args))
        args     (remove #{"--json" "--no-color"} args)
        [cmd file & rest] args
        tty?     (some? (System/console))
        term     (System/getenv "TERM")]
    (binding [*color?* (or (and (not json?) (not no-col?)
                                (= "1" (System/getenv "CLICOLOR_FORCE")))
                           (and (not json?)
                                (not no-col?)
                                tty?
                                (not= "dumb" term)))]
      (case cmd
        "list"     (list-cmd file rest json?)
        "tree"     (tree-cmd file rest json?)
        "meta"     (meta-cmd file json?)
        "summary"  (summary-cmd file json?)
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
        "classes"  (classes-cmd file rest json?)
        "spi"      (spi-cmd file json?)
        "duplicate-classes" (duplicate-classes-cmd (cons file rest) json?)
        "cat"      (cat-cmd file (first rest))
        (do (println usage)
            (flush)
            (System/exit (if cmd 1 0)))))))
