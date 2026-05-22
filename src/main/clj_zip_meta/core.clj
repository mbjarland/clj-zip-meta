(ns clj-zip-meta.core
  "Read and patch the binary metadata of zip and jar files.

  Public entry points:

    * `zip-meta`                       — full metadata map
    * `zip-entries`                    — vector of compact entry summaries
    * `zip-comment`                    — archive-level comment
    * `summarize`                      — high-level statistics
    * `print-zip-meta`                 — pretty-printer
    * `validate-zip-meta`              — sanity-check the metadata
    * `repair-zip-with-preamble-bytes` — fix offsets when bytes were
                                          prepended before the zip
                                          payload

  All file-accepting functions take a `String` path, a `java.io.File`,
  or an already-open `java.io.RandomAccessFile`. See APPNOTE.TXT §4.3
  for the zip-format details that drive the field names this library
  returns."
  (:require [clj-zip-meta.spec :refer [rec-local-file-header
                                       rec-local-file-header-sig
                                       rec-cdr-header
                                       rec-cdr-header-sig
                                       rec-end-of-cdr
                                       rec-end-of-cdr-sig]]
            [octet.core :as buf]
            [octet.spec :as ospec]
            [clojure.java.io :as jio]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer ByteOrder MappedByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.util Arrays)))

(declare scan-backwards scan-forwards)

;; ============================================================================
;; Internal helpers

(def ^:private ^:const scan-block-size
  "Sliding-window size for `find-byte-pattern`. 64 KiB."
  (long 0x10000))

(def ^:private eocdr-window-size
  "Max number of tail bytes to scan when locating the EOCDR signature:
  22-byte EOCDR header + 65 535-byte max ZIP comment + slack."
  (long (+ 22 0xFFFF 1024)))

(defn ^RandomAccessFile raf
  "Coerce `f` to a `RandomAccessFile` opened in `mode` (\"r\" or \"rw\").
  If `f` is already a `RandomAccessFile`, return it unchanged — the
  caller is then responsible for closing it.

  `f` may be a `String` path or a `java.io.File`."
  [f ^String mode]
  (if (instance? RandomAccessFile f)
    ^RandomAccessFile f
    (RandomAccessFile. ^File (jio/as-file f) mode)))

(defn- ^ByteBuffer map-region
  "Memory-map a region of `raf` and return a little-endian `ByteBuffer`.
  The mapping outlives the channel per the `FileChannel.map`
  contract. We deliberately do **not** call `.close` on the channel —
  on the JVM, closing a `FileChannel` obtained from a
  `RandomAccessFile` also closes that RAF, which would invalidate a
  caller-supplied RAF. The channel is released when its owning RAF
  is closed."
  [^RandomAccessFile raf ^String mode ^long offset ^long size]
  (let [cm (case mode
             "r"  FileChannel$MapMode/READ_ONLY
             "rw" FileChannel$MapMode/READ_WRITE)
        ch (.getChannel raf)]
    (-> (.map ch cm offset size)
        (.order ByteOrder/LITTLE_ENDIAN))))

(defn- borrows-raf? [f]
  (instance? RandomAccessFile f))

(defmacro ^:private with-raf
  "Open a `RandomAccessFile` for `f` in `mode`, bind it to `sym`, run
  `body`, and close the RAF when done — unless `f` was already a RAF
  passed in by the caller (in which case ownership remains with the
  caller)."
  [[sym f mode] & body]
  (let [tagged (with-meta sym {:tag `RandomAccessFile})]
    `(let [close?# (not (borrows-raf? ~f))
           ~tagged (raf ~f ~mode)]
       (try
         ~@body
         (finally
           (when close?# (.close ~tagged)))))))

(defn- sig-bytes [rec-sig]
  (first (vals rec-sig)))

(defn- bytes-equal-at?
  [^bytes haystack ^long start ^bytes needle ^long nlen]
  (loop [j 0]
    (cond
      (= j nlen) true
      (not= (aget haystack (+ start j)) (aget needle j)) false
      :else (recur (inc j)))))

(defn- last-index-of-bytes
  "Highest index `i` in `haystack` such that `haystack` matches
  `needle` at `i`, or `nil` if not found."
  [^bytes haystack ^bytes needle]
  (let [nlen (alength needle)
        hlen (alength haystack)]
    (loop [i (- hlen nlen)]
      (when (not (neg? i))
        (if (bytes-equal-at? haystack i needle nlen)
          i
          (recur (dec i)))))))

(defn- index-of-bytes
  "Lowest index `i` in `haystack` such that `haystack` matches
  `needle` at `i`, or `nil` if not found."
  [^bytes haystack ^bytes needle]
  (let [nlen (alength needle)
        hlen (alength haystack)
        last (- hlen nlen)]
    (loop [i 0]
      (when (<= i last)
        (if (bytes-equal-at? haystack i needle nlen)
          i
          (recur (inc i)))))))

(defn- read-spec-bb
  "Read a record described by `spec` from `bb` at byte position `off`
  (little-endian)."
  [^ByteBuffer bb spec ^long off]
  (buf/with-byte-order :little-endian
    (buf/read bb spec {:offset off})))

(defn- write-spec-bb!
  "Write `data` matching `spec` into `bb` at byte position `off`
  (little-endian)."
  [^ByteBuffer bb data spec ^long off]
  (buf/with-byte-order :little-endian
    (buf/write! bb data spec {:offset off})))

;; ============================================================================
;; Public low-level API

(defn valid-offset?
  "True iff `off` is a non-negative integer."
  [off]
  (and (integer? off) (not (neg? ^long off))))

(defn valid-signature?
  "Return true iff the `size` bytes at `offset` in file `f` equal
  `signature`."
  [f ^long offset ^long size ^bytes signature]
  (with-raf [r f "r"]
    (let [^ByteBuffer bb (map-region r "r" offset size)
          ba             (byte-array size)]
      (.position bb 0)
      (.get bb ba)
      (Arrays/equals ba signature))))

(defn find-byte-pattern
  "Scan file `f` for the first occurrence of `pattern` (a byte array).

  `start-offset` is the byte offset where the scan begins; pass `-1`
  to start from the last possible match position (`(.length f) -
  (alength pattern)`). `step` is `+1` for a forward scan or `-1` for
  a backward scan; the default is `+1`.

  Returns the byte offset where `pattern` begins, or `nil` if no
  occurrence is found.

  Uses a 64 KiB sliding-window buffer, so this is fast even on large
  archives — locating the end-of-central-directory record on a
  hundred-megabyte jar reads at most one window."
  ([f pattern]
   (find-byte-pattern f pattern 0 1))
  ([f pattern start-offset]
   (find-byte-pattern f pattern start-offset 1))
  ([f ^bytes pattern ^long start-offset ^long step]
   {:pre [(or (= -1 start-offset) (not (neg? start-offset)))
          (or (= 1 step) (= -1 step))]}
   (with-raf [r f "r"]
     (let [plen     (alength pattern)
           len      (.length r)
           last-pos (- len plen)]
       (cond
         (neg? last-pos) nil
         (neg? step) (scan-backwards r pattern
                                     (if (= -1 start-offset) last-pos
                                         (min (long start-offset) last-pos)))
         :else       (scan-forwards r pattern
                                    (max 0 (long start-offset))
                                    last-pos))))))

(defn- scan-backwards
  [^RandomAccessFile r ^bytes pattern ^long start-hi]
  (let [plen (alength pattern)]
    (loop [hi start-hi]
      (when (not (neg? hi))
        (let [lo  (max 0 (- (inc hi) scan-block-size))
              sz  (- (+ hi plen) lo)
              buf (byte-array sz)]
          (.seek r lo)
          (.readFully r buf)
          (if-let [idx (last-index-of-bytes buf pattern)]
            (+ lo (long idx))
            (when (pos? lo)
              (recur (dec lo)))))))))

(defn- scan-forwards
  [^RandomAccessFile r ^bytes pattern ^long start-lo ^long last-pos]
  (let [plen (alength pattern)]
    (loop [lo start-lo]
      (when (<= lo last-pos)
        (let [hi  (min last-pos (dec (+ lo scan-block-size)))
              sz  (- (+ hi plen) lo)
              buf (byte-array sz)]
          (.seek r lo)
          (.readFully r buf)
          (if-let [idx (index-of-bytes buf pattern)]
            (+ lo (long idx))
            (recur (inc hi))))))))

(defn find-end-of-cdr-offset
  "Locate the end-of-central-directory record in `f` by scanning the
  tail of the file. Returns the byte offset of the EOCDR signature,
  or `nil` if no signature is found within the last ~64 KiB + 22
  bytes (the maximum size of a valid EOCDR + comment)."
  [f]
  (with-raf [r f "r"]
    (let [len      (.length r)
          win-size (min len eocdr-window-size)
          win-off  (- len win-size)
          buf      (byte-array win-size)]
      (.seek r win-off)
      (.readFully r buf)
      (when-let [idx (last-index-of-bytes buf (sig-bytes rec-end-of-cdr-sig))]
        (+ win-off (long idx))))))

(defn read-spec-from-buffer
  "Read a single record matching `spec` from `ByteBuffer` `buff` at
  byte position `off`. Always little-endian."
  [buff spec off]
  (read-spec-bb buff spec (long off)))

(defn read-spec-from-file
  "Read a single record matching `spec` from `f` at byte offset `off`.
  Always little-endian."
  [f spec off]
  {:pre [(valid-offset? off)]}
  (with-raf [r f "r"]
    (let [bb (map-region r "r" (long off) (- (.length r) (long off)))]
      (read-spec-bb bb spec 0))))

(defn write-spec-to-buffer!
  "Write `data` matching `spec` to `ByteBuffer` `buff` at byte
  position `off`. Always little-endian."
  [buff data spec off]
  (write-spec-bb! buff data spec (long off)))

(defn write-spec-to-file!
  "Write `data` matching `spec` to file `f` at byte offset `off`.
  Always little-endian. The file must already be at least large
  enough to hold the record."
  [f data spec off]
  {:pre [(valid-offset? off)]}
  (with-raf [r f "rw"]
    (let [bb (map-region r "rw" (long off) (- (.length r) (long off)))]
      (write-spec-bb! bb data spec 0)
      (.force ^MappedByteBuffer bb))))

;; ============================================================================
;; Reading the full metadata

(defn- read-cdr-records*
  "Read `entries` consecutive CDR records from `bb` starting at byte
  position `start-off`. Returns a vector of `{:offset N :record M}`."
  [^ByteBuffer bb ^long start-off ^long entries]
  (loop [acc (transient [])
         off start-off
         n   entries]
    (if (zero? n)
      (persistent! acc)
      (let [record (read-spec-bb bb rec-cdr-header off)
            sz     (long (ospec/size* rec-cdr-header record))]
        (recur (conj! acc {:offset off :record record})
               (+ off sz)
               (dec n))))))

(defn- read-local-records*
  "Read the local file headers referenced by `cdr-records`. `bb` is a
  buffer that covers the start of the file at least through the
  beginning of the central directory. `extra-bytes` is added to each
  recorded `:relative-offset-local-header`."
  [^ByteBuffer bb cdr-records ^long extra-bytes]
  (mapv
    (fn [cdr]
      (let [off    (+ (long (:relative-offset-local-header cdr)) extra-bytes)
            record (read-spec-bb bb rec-local-file-header off)]
        {:offset off :record record}))
    cdr-records))

(defn get-cdr-records
  "Read `entries` central directory records from file `f` starting at
  byte offset `off`. Returns a vector of `{:offset N :record M}`."
  [f off entries]
  {:pre [(valid-offset? off)]}
  (with-raf [r f "r"]
    (let [bb (map-region r "r" 0 (.length r))]
      (read-cdr-records* bb (long off) (long entries)))))

(defn get-local-records
  "Read the local file headers referenced by `cdr-records` from file
  `f`. `cdr-offset` is the central-directory start offset (used as a
  buffer-mapping upper bound). `extra-bytes` is added to each
  `:relative-offset-local-header` value."
  [f cdr-records cdr-offset extra-bytes]
  (with-raf [r f "r"]
    (let [bb (map-region r "r" 0 (long cdr-offset))]
      (read-local-records* bb cdr-records (long extra-bytes)))))

(defn read-end-of-cdr-record
  "Find and read the end-of-central-directory record from `f`.

  Returns `[extra-bytes cdr-offset-actual {:offset N :record M}]`:

    * `extra-bytes`       — number of bytes prepended before the zip
                             payload (0 for a well-formed archive)
    * `cdr-offset-actual` — file offset where the central directory
                             actually begins
    * the third element   — the parsed EOCDR with its file offset

  Throws `clojure.lang.ExceptionInfo` if no EOCDR signature is
  located or if the central directory does not start where the EOCDR
  claims it should."
  [f]
  (with-raf [r f "r"]
    (let [len            (.length r)
          eocdr-off      (or (find-end-of-cdr-offset r)
                             (throw (ex-info "End-of-central-directory record not found"
                                             {:file (str f) :length len})))
          bb             (map-region r "r" eocdr-off (- len eocdr-off))
          eocdr-rec      (read-spec-bb bb rec-end-of-cdr 0)
          cdr-recorded   (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            (long (:cdr-size eocdr-rec)))
          extra-bytes    (- eocdr-off cdr-recorded)
          cdr-off-actual (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            extra-bytes)]
      (when-not (valid-signature? r cdr-off-actual 4 (sig-bytes rec-cdr-header-sig))
        (throw (ex-info "Central directory signature not found at expected offset"
                        {:file             (str f)
                         :eocdr-offset     eocdr-off
                         :expected-cdr-off cdr-off-actual
                         :extra-bytes      extra-bytes})))
      [extra-bytes cdr-off-actual {:offset eocdr-off :record eocdr-rec}])))

(defn zip-meta
  "Read all zip metadata from `f` (a path `String`, a `java.io.File`,
  or an open `RandomAccessFile`). Returns a map with keys:

    `:extra-bytes`       — number of bytes prepended before the zip
                            payload (0 for a well-formed archive)
    `:end-of-cdr-record` — `{:offset N :record M}` for the EOCDR
    `:cdr-records`       — vector of `{:offset N :record M}` central
                            directory entries
    `:local-records`     — vector of `{:offset N :record M}` local
                            file headers

  Each `:record` map mirrors the corresponding zip-specification
  record (see `clj-zip-meta.spec` and APPNOTE.TXT §4.3). Throws
  `ex-info` if the archive is malformed.

  Performance note: this function memory-maps the file once and
  reads every record from a single mapping, then releases the
  channel."
  [f]
  (with-raf [r f "r"]
    (let [len            (.length r)
          eocdr-off      (or (find-end-of-cdr-offset r)
                             (throw (ex-info "End-of-central-directory record not found"
                                             {:file (str f) :length len})))
          ^ByteBuffer
          file-bb        (map-region r "r" 0 len)
          eocdr-rec      (read-spec-bb file-bb rec-end-of-cdr eocdr-off)
          cdr-recorded   (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            (long (:cdr-size eocdr-rec)))
          extra-bytes    (- eocdr-off cdr-recorded)
          cdr-off-actual (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            extra-bytes)
          sig            (sig-bytes rec-cdr-header-sig)
          siglen         (alength ^bytes sig)
          sig-ba         (byte-array siglen)
          _              (do (.position file-bb (int cdr-off-actual))
                             (.get file-bb sig-ba)
                             (when-not (Arrays/equals ^bytes sig-ba ^bytes sig)
                               (throw (ex-info "Central directory signature not found at expected offset"
                                               {:file             (str f)
                                                :eocdr-offset     eocdr-off
                                                :expected-cdr-off cdr-off-actual
                                                :extra-bytes      extra-bytes}))))
          entries        (long (:cdr-entries-total eocdr-rec))
          cdrs           (read-cdr-records* file-bb cdr-off-actual entries)
          locals         (read-local-records* file-bb (mapv :record cdrs) extra-bytes)]
      {:extra-bytes       extra-bytes
       :end-of-cdr-record {:offset eocdr-off :record eocdr-rec}
       :cdr-records       cdrs
       :local-records     locals})))

;; ============================================================================
;; Validation and repair

(defn repair-zip-with-preamble-bytes
  "Rewrite the offsets in an archive that has bytes prepended before
  its zip payload (e.g. a self-extracting jar with a shell prelude).
  After this call, every CDR record's `:relative-offset-local-header`
  and the EOCDR's `:cdr-offset-from-start-disk` are bumped by the
  number of prepended bytes, so other zip tooling sees a well-formed
  archive again.

  No-op when the file already validates cleanly. Returns `f` so the
  call can be threaded."
  [f]
  (let [meta        (zip-meta f)
        extra-bytes (long (:extra-bytes meta))]
    (when (pos? extra-bytes)
      (with-raf [r f "rw"]
        (let [len (.length r)
              bb  (map-region r "rw" 0 len)]
          (doseq [{offset :offset cdr :record} (:cdr-records meta)]
            (write-spec-bb! bb
                            (update cdr :relative-offset-local-header + extra-bytes)
                            rec-cdr-header offset))
          (let [{eo-off :offset eo :record} (:end-of-cdr-record meta)]
            (write-spec-bb! bb
                            (update eo :cdr-offset-from-start-disk + extra-bytes)
                            rec-end-of-cdr eo-off))
          (.force ^MappedByteBuffer bb))))
    f))

(defn validate-zip-meta
  "Check the metadata in `f` for self-consistency. Returns a map:

    `:valid?`      — true iff no problems were found
    `:issues`      — vector of human-readable strings describing
                      each problem
    `:extra-bytes` — number of bytes prepended (informational)

  Options:

    `:repair` — when truthy, rewrites prepended-byte offset drift in
                place before re-running the validation. Defaults to
                false.
    `:print`  — when truthy, prints each issue to `*out*`. Defaults
                to false. Provided for compatibility with the prior
                side-effecting behavior."
  [f & {:keys [repair print]}]
  (when repair
    (repair-zip-with-preamble-bytes f))
  (let [meta   (zip-meta f)
        eo-cdr (:end-of-cdr-record meta)
        locals (:local-records meta)
        cdrs   (:cdr-records meta)
        extra  (long (:extra-bytes meta))
        issues (cond-> []
                 (pos? extra)
                 (conj (str extra " extra bytes at beginning or within zipfile"))

                 (not (valid-signature? f (:offset eo-cdr) 4
                                        (sig-bytes rec-end-of-cdr-sig)))
                 (conj "invalid end of cdr signature")

                 (some (fn [{offset :offset}]
                         (not (valid-signature? f offset 4
                                                (sig-bytes rec-cdr-header-sig))))
                       cdrs)
                 (conj "invalid cdr record signatures found")

                 (some (fn [{offset :offset}]
                         (not (valid-signature? f offset 4
                                                (sig-bytes rec-local-file-header-sig))))
                       locals)
                 (conj "invalid local record signatures found"))]
    (when print (run! println issues))
    {:valid?      (empty? issues)
     :issues      issues
     :extra-bytes extra}))

;; ============================================================================
;; Convenience / ergonomic API

(defn zip-entries
  "Return a vector of compact entry summaries for `f`, one per central
  directory record. Each entry has the keys:

    `:file-name`           — the entry's path inside the archive
    `:file-comment`        — per-entry comment (often empty)
    `:compressed-size`     — compressed size in bytes
    `:uncompressed-size`   — uncompressed size in bytes
    `:crc-32`              — CRC-32 of the uncompressed data
    `:compression-method`  — 0 = stored, 8 = deflate, etc.
    `:offset`              — file offset of the CDR record"
  [f]
  (mapv
    (fn [{offset :offset cdr :record}]
      {:file-name          (:file-name cdr)
       :file-comment       (:file-comment cdr)
       :compressed-size    (:compressed-size cdr)
       :uncompressed-size  (:uncompressed-size cdr)
       :crc-32             (:crc-32 cdr)
       :compression-method (:compression-method cdr)
       :offset             offset})
    (:cdr-records (zip-meta f))))

(defn zip-comment
  "Return the archive-level comment from `f` (an empty string if
  none)."
  [f]
  (get-in (zip-meta f) [:end-of-cdr-record :record :zip-comment]))

(defn summarize
  "Return a compact summary map for `f`:

    `:entry-count`        — number of entries
    `:extra-bytes`        — bytes prepended before the zip payload
    `:total-compressed`   — sum of compressed sizes
    `:total-uncompressed` — sum of uncompressed sizes
    `:zip-comment`        — archive-level comment"
  [f]
  (let [m    (zip-meta f)
        recs (map :record (:cdr-records m))]
    {:entry-count        (count recs)
     :extra-bytes        (:extra-bytes m)
     :total-compressed   (reduce + 0 (map :compressed-size recs))
     :total-uncompressed (reduce + 0 (map :uncompressed-size recs))
     :zip-comment        (get-in m [:end-of-cdr-record :record :zip-comment])}))

(defn- byte-array? [v]
  (and (some? v)
       (let [c (class v)]
         (and (.isArray ^Class c)
              (= "byte" (.getName (.getComponentType ^Class c)))))))

(defn- bytes->hex [^bytes ba]
  (str/join " " (map #(format "%02x" (bit-and 0xff (long %))) (seq ba))))

(defn- hexify-byte-arrays [m]
  (walk/postwalk
    (fn [v]
      (if (byte-array? v)
        (str "#bytes \"" (bytes->hex v) "\"")
        v))
    m))

(defn print-zip-meta
  "Pretty-print a meta map. When passed a file, calls `zip-meta` on it
  first. Byte-array fields (e.g. `:extra-field`) are rendered as hex
  strings instead of `\"[B@...\"` identity strings. Returns the
  printed (un-hexified) meta map so calls can be threaded."
  [f-or-meta]
  (let [meta (if (map? f-or-meta) f-or-meta (zip-meta f-or-meta))]
    (pp/pprint (hexify-byte-arrays meta))
    meta))

;; ============================================================================
;; Diagnostic helpers retained for backwards compatibility

(defn dump-sig-number
  "Format an integer record-signature value as the space-separated hex
  representation you would see in a hex dump of the file (little-endian
  byte order). Useful when comparing observed values to the signatures
  listed in APPNOTE.TXT.

      (dump-sig-number 0x06054b50) ;=> \"50 4b 05 06\""
  [sig-value]
  (let [bs (.toByteArray (BigInteger/valueOf (long sig-value)))
        le (byte-array (reverse bs))]
    (bytes->hex le)))

(defn dump-sig-bytes
  "Format a record-signature byte array as a space-separated hex
  string."
  [sig-bytes-arg]
  (bytes->hex sig-bytes-arg))

(defn map-byte-buffer
  "Memory-map a region of `f` and return a little-endian `ByteBuffer`.

  `mode` is `\"r\"` or `\"rw\"`. With one argument the whole file is
  mapped. With two, mapping starts at `offset` and runs to the end.
  Provided for backwards compatibility — most callers should not need
  this directly."
  ([f mode]
   (map-byte-buffer f mode 0))
  ([f mode offset]
   (let [r (raf f mode)]
     (map-byte-buffer r mode offset (- (.length r) (long offset)))))
  ([f mode offset size]
   (let [r (raf f mode)]
     (map-region r mode (long offset) (long size)))))
