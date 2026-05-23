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
  (:require [clojure.java.io :as jio]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer ByteOrder MappedByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.charset StandardCharsets)
           (java.time LocalDateTime)
           (java.util Arrays)
           (java.util.zip CRC32 Inflater)))

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

;; ----------------------------------------------------------------------------
;; Signatures, mirrored from `clj-zip-meta.spec` so that namespace
;; (which transitively pulls in Octet) is not required to load core.

(def ^:private ^{:tag "[B"} lfh-sig-bytes
  (byte-array [(byte 0x50) (byte 0x4b) (byte 0x03) (byte 0x04)]))

(def ^:private ^{:tag "[B"} cdr-sig-bytes
  (byte-array [(byte 0x50) (byte 0x4b) (byte 0x01) (byte 0x02)]))

(def ^:private ^{:tag "[B"} eocdr-sig-bytes
  (byte-array [(byte 0x50) (byte 0x4b) (byte 0x05) (byte 0x06)]))

;; ----------------------------------------------------------------------------
;; Hand-rolled fast readers.
;;
;; octet is a great library but it pays a heavy per-field cost via
;; protocol dispatch and dynamic-var lookup; on a 3 770-entry jar a
;; full zip-meta call was taking ~1.9 s. The readers below operate
;; directly on a little-endian ByteBuffer and produce records with the
;; same key/value shape octet would produce, so callers see no
;; difference. They cut the hot path to a few tens of milliseconds.

(defn- str-utf8 ^String [^ByteBuffer bb ^long pos ^long len]
  (if (zero? len)
    ""
    (let [ba (byte-array (int len))]
      (.position bb (int pos))
      (.get bb ba)
      (String. ba 0 (int len) StandardCharsets/UTF_8))))

(defn- bytes-at ^bytes [^ByteBuffer bb ^long pos ^long len]
  (let [ba (byte-array (int len))]
    (when (pos? len)
      (.position bb (int pos))
      (.get bb ba))
    ba))

(defn- read-eocdr! [^ByteBuffer bb ^long pos]
  (let [sig   (.getInt   bb (int pos))
        nd    (.getShort bb (int (+ pos 4)))
        nc    (.getShort bb (int (+ pos 6)))
        ehere (.getShort bb (int (+ pos 8)))
        etot  (.getShort bb (int (+ pos 10)))
        csz   (.getInt   bb (int (+ pos 12)))
        cof   (.getInt   bb (int (+ pos 16)))
        clen  (.getShort bb (int (+ pos 20)))
        cmt   (str-utf8 bb (+ pos 22) (bit-and 0xFFFF clen))]
    {:end-of-cdr-signature       sig
     :number-of-this-disk        nd
     :number-of-cdr-disk         nc
     :cdr-entries-this-disk      ehere
     :cdr-entries-total          etot
     :cdr-size                   csz
     :cdr-offset-from-start-disk cof
     :zip-comment-length         clen
     :zip-comment                cmt}))

(defn- read-cdr! [^ByteBuffer bb ^long pos]
  (let [sig    (.getInt   bb (int pos))
        vmade  (.getShort bb (int (+ pos 4)))
        vneed  (.getShort bb (int (+ pos 6)))
        gp     (.getShort bb (int (+ pos 8)))
        meth   (.getShort bb (int (+ pos 10)))
        time   (.getShort bb (int (+ pos 12)))
        date   (.getShort bb (int (+ pos 14)))
        crc    (.getInt   bb (int (+ pos 16)))
        csize  (.getInt   bb (int (+ pos 20)))
        usize  (.getInt   bb (int (+ pos 24)))
        nlen   (.getShort bb (int (+ pos 28)))
        elen   (.getShort bb (int (+ pos 30)))
        cmtlen (.getShort bb (int (+ pos 32)))
        dn     (.getShort bb (int (+ pos 34)))
        iattr  (.getShort bb (int (+ pos 36)))
        eattr  (.getInt   bb (int (+ pos 38)))
        roff   (.getInt   bb (int (+ pos 42)))
        nlu    (bit-and 0xFFFF nlen)
        elu    (bit-and 0xFFFF elen)
        clu    (bit-and 0xFFFF cmtlen)
        nm     (str-utf8 bb (+ pos 46)          nlu)
        ex     (bytes-at bb (+ pos 46 nlu)      elu)
        cmt    (str-utf8 bb (+ pos 46 nlu elu)  clu)]
    {:cdr-header-signature         sig
     :version-made-by              vmade
     :version-needed-to-extract    vneed
     :general-purpose              gp
     :compression-method           meth
     :last-mod-file-time           time
     :last-mod-file-date           date
     :crc-32                       crc
     :compressed-size              csize
     :uncompressed-size            usize
     :file-name-length             nlen
     :extra-field-length           elen
     :file-comment-length          cmtlen
     :disk-number-start            dn
     :internal-file-attributes     iattr
     :external-file-attributes     eattr
     :relative-offset-local-header roff
     :file-name                    nm
     :extra-field                  ex
     :file-comment                 cmt}))

(defn- cdr-size* ^long [cdr]
  (+ 46
     (bit-and 0xFFFF (long (:file-name-length    cdr)))
     (bit-and 0xFFFF (long (:extra-field-length  cdr)))
     (bit-and 0xFFFF (long (:file-comment-length cdr)))))

(defn- read-lfh! [^ByteBuffer bb ^long pos]
  (let [sig   (.getInt   bb (int pos))
        vneed (.getShort bb (int (+ pos 4)))
        gp    (.getShort bb (int (+ pos 6)))
        meth  (.getShort bb (int (+ pos 8)))
        time  (.getShort bb (int (+ pos 10)))
        date  (.getShort bb (int (+ pos 12)))
        crc   (.getInt   bb (int (+ pos 14)))
        csize (.getInt   bb (int (+ pos 18)))
        usize (.getInt   bb (int (+ pos 22)))
        nlen  (.getShort bb (int (+ pos 26)))
        elen  (.getShort bb (int (+ pos 28)))
        nlu   (bit-and 0xFFFF nlen)
        elu   (bit-and 0xFFFF elen)
        nm    (str-utf8 bb (+ pos 30)     nlu)
        ex    (bytes-at bb (+ pos 30 nlu) elu)]
    {:local-header-signature    sig
     :version-needed-to-extract vneed
     :general-purpose           gp
     :compression-method        meth
     :last-mod-file-time        time
     :last-mod-file-date        date
     :crc-32                    crc
     :compressed-size           csize
     :uncompressed-size         usize
     :file-name-length          nlen
     :extra-field-length        elen
     :file-name                 nm
     :extra-field               ex}))

(defn- lfh-size* ^long [lfh]
  (+ 30
     (bit-and 0xFFFF (long (:file-name-length lfh)))
     (bit-and 0xFFFF (long (:extra-field-length lfh)))))

;; ----------------------------------------------------------------------------
;; Hand-rolled writers (mirroring the readers above).
;;
;; Octet handles writes correctly but at the same per-field protocol-
;; dispatch cost as reads. These writers operate directly on a
;; little-endian ByteBuffer. Each returns the total number of bytes
;; written. The public octet-based write-spec-to-* functions are kept
;; for backwards compatibility.

(defn- write-eocdr! ^long [^ByteBuffer bb ^long pos eocdr]
  (let [cmt  (str (or (:zip-comment eocdr) ""))
        cbs  (.getBytes cmt "UTF-8")
        clen (alength cbs)]
    (.putInt   bb (int pos)        (unchecked-int   (long (:end-of-cdr-signature       eocdr))))
    (.putShort bb (int (+ pos 4))  (unchecked-short (long (:number-of-this-disk        eocdr))))
    (.putShort bb (int (+ pos 6))  (unchecked-short (long (:number-of-cdr-disk         eocdr))))
    (.putShort bb (int (+ pos 8))  (unchecked-short (long (:cdr-entries-this-disk      eocdr))))
    (.putShort bb (int (+ pos 10)) (unchecked-short (long (:cdr-entries-total          eocdr))))
    (.putInt   bb (int (+ pos 12)) (unchecked-int   (long (:cdr-size                   eocdr))))
    (.putInt   bb (int (+ pos 16)) (unchecked-int   (long (:cdr-offset-from-start-disk eocdr))))
    (.putShort bb (int (+ pos 20)) (unchecked-short (long clen)))
    (when (pos? clen)
      (.position bb (int (+ pos 22)))
      (.put bb cbs))
    (+ 22 clen)))

(defn- write-cdr! ^long [^ByteBuffer bb ^long pos cdr]
  (let [nm   (str (or (:file-name    cdr) ""))
        cmt  (str (or (:file-comment cdr) ""))
        ex   (or  (:extra-field cdr) (byte-array 0))
        nbs  (.getBytes nm "UTF-8")
        cbs  (.getBytes cmt "UTF-8")
        nlen (alength nbs)
        elen (alength ^bytes ex)
        clen (alength cbs)]
    (.putInt   bb (int pos)        (unchecked-int   (long (:cdr-header-signature         cdr))))
    (.putShort bb (int (+ pos 4))  (unchecked-short (long (:version-made-by              cdr))))
    (.putShort bb (int (+ pos 6))  (unchecked-short (long (:version-needed-to-extract    cdr))))
    (.putShort bb (int (+ pos 8))  (unchecked-short (long (:general-purpose              cdr))))
    (.putShort bb (int (+ pos 10)) (unchecked-short (long (:compression-method           cdr))))
    (.putShort bb (int (+ pos 12)) (unchecked-short (long (:last-mod-file-time           cdr))))
    (.putShort bb (int (+ pos 14)) (unchecked-short (long (:last-mod-file-date           cdr))))
    (.putInt   bb (int (+ pos 16)) (unchecked-int   (long (:crc-32                       cdr))))
    (.putInt   bb (int (+ pos 20)) (unchecked-int   (long (:compressed-size              cdr))))
    (.putInt   bb (int (+ pos 24)) (unchecked-int   (long (:uncompressed-size            cdr))))
    (.putShort bb (int (+ pos 28)) (unchecked-short (long nlen)))
    (.putShort bb (int (+ pos 30)) (unchecked-short (long elen)))
    (.putShort bb (int (+ pos 32)) (unchecked-short (long clen)))
    (.putShort bb (int (+ pos 34)) (unchecked-short (long (:disk-number-start            cdr))))
    (.putShort bb (int (+ pos 36)) (unchecked-short (long (:internal-file-attributes     cdr))))
    (.putInt   bb (int (+ pos 38)) (unchecked-int   (long (:external-file-attributes     cdr))))
    (.putInt   bb (int (+ pos 42)) (unchecked-int   (long (:relative-offset-local-header cdr))))
    (.position bb (int (+ pos 46)))
    (when (pos? nlen) (.put bb nbs))
    (when (pos? elen) (.put bb ^bytes ex))
    (when (pos? clen) (.put bb cbs))
    (+ 46 nlen elen clen)))

;; ----------------------------------------------------------------------------
;; Decoded convenience fields
;;
;; The raw record fields preserve the on-disk bit pattern verbatim
;; (so writes can round-trip cleanly). These helpers layer
;; higher-level interpretations on top:
;;
;;   :last-modified      java.time.LocalDateTime decoded from
;;                       :last-mod-file-time + :last-mod-file-date
;;   :dos-attributes     set of keywords decoded from the low byte of
;;                       :external-file-attributes (CDR) — :read-only
;;                       :hidden :system :volume :directory :archive
;;   :unix-mode          Unix file mode from the high 16 bits of
;;                       :external-file-attributes when version-made-by
;;                       reports Unix (host code 3); nil otherwise.
;;   :directory?         convenience boolean (file name ends with "/" or
;;                       DOS directory bit is set)
;;   :encrypted?         general-purpose bit 0
;;   :utf8-name?         general-purpose bit 11
;;   :extra-fields       parsed list of TLVs from :extra-field
;;                       (each {:tag T :data bytes [+ decoded keys]}).

(defn- ^LocalDateTime dos->ldt [^long dos-date ^long dos-time]
  (let [year   (+ 1980 (bit-and 0x7F (unsigned-bit-shift-right dos-date 9)))
        month  (bit-and 0x0F (unsigned-bit-shift-right dos-date 5))
        day    (bit-and 0x1F dos-date)
        hour   (bit-and 0x1F (unsigned-bit-shift-right dos-time 11))
        minute (bit-and 0x3F (unsigned-bit-shift-right dos-time 5))
        second (* 2 (bit-and 0x1F dos-time))]
    (when (and (<= 1 month 12) (<= 1 day 31)
               (<= 0 hour 23) (<= 0 minute 59) (<= 0 second 59))
      (try (LocalDateTime/of (int year) (int month) (int day)
                             (int hour) (int minute) (int second))
           (catch Exception _ nil)))))

(def ^:private dos-attr-bits
  [[0x01 :read-only]
   [0x02 :hidden]
   [0x04 :system]
   [0x08 :volume]
   [0x10 :directory]
   [0x20 :archive]])

(defn- dos-attrs [^long external-file-attributes]
  (let [low (bit-and 0xFF external-file-attributes)]
    (persistent!
      (reduce
        (fn [acc [bit kw]]
          (if (pos? (bit-and bit low)) (conj! acc kw) acc))
        (transient #{})
        dos-attr-bits))))

(defn- unix-mode-from
  "Decode the Unix mode from `:external-file-attributes` when
  version-made-by's host byte is Unix (3). The mode lives in the
  high 16 bits (bits 16..31 after a shift)."
  [^long version-made-by ^long external-file-attributes]
  (when (= 3 (bit-and 0xFF (unsigned-bit-shift-right version-made-by 8)))
    (bit-and 0xFFFF (unsigned-bit-shift-right external-file-attributes 16))))

(def ^:private extra-tags
  {0x0001 :zip64
   0x000A :ntfs
   0x000D :pkware-unix
   0x5455 :extended-timestamp
   0x5855 :infozip-unix-old
   0x7855 :infozip-unix-new
   0x6375 :infozip-utf8-comment
   0x7075 :infozip-utf8-path
   0x9901 :aes})

(defn- read-bytes-from
  [^bytes ba ^long off ^long len]
  (let [out (byte-array (int len))]
    (System/arraycopy ba (int off) out 0 (int len))
    out))

(defn- decode-extended-timestamp
  "Bit 0: mtime, bit 1: atime, bit 2: ctime; each present time is a
  signed 32-bit Unix timestamp following the flag byte."
  [^bytes data]
  (try
    (when (pos? (alength data))
      (let [bb    (-> (ByteBuffer/wrap data) (.order ByteOrder/LITTLE_ENDIAN))
            flags (bit-and 0xFF (long (.get bb 0)))
            out   (transient {:flags flags})]
        (loop [pos 1
               kws [[0x01 :mtime] [0x02 :atime] [0x04 :ctime]]]
          (if (or (empty? kws) (> (+ pos 4) (alength data)))
            (persistent! out)
            (let [[bit kw] (first kws)]
              (if (pos? (bit-and bit flags))
                (do (assoc! out kw (.getInt bb (int pos)))
                    (recur (+ pos 4) (rest kws)))
                (recur pos (rest kws))))))))
    (catch Exception _ nil)))

(defn- decode-extra-field [tag ^bytes data]
  (case tag
    :extended-timestamp (decode-extended-timestamp data)
    nil))

(defn- parse-extra-fields
  "Parse the byte array `ba` as a sequence of zip extra-field TLV
  records. Returns a vector of `{:tag T :tag-name K :size N :data bytes}`
  maps, where `:tag-name` is a known keyword (or nil) and `:decoded`
  appears when the tag has a decoder."
  [^bytes ba]
  (let [len (alength ba)]
    (loop [pos 0
           acc (transient [])]
      (if (> (+ pos 4) len)
        (persistent! acc)
        (let [bb     (-> (ByteBuffer/wrap ba) (.order ByteOrder/LITTLE_ENDIAN))
              tag-i  (bit-and 0xFFFF (long (.getShort bb (int pos))))
              size   (bit-and 0xFFFF (long (.getShort bb (int (+ pos 2)))))
              end    (+ pos 4 size)]
          (if (> end len)
            (persistent! acc)
            (let [data    (read-bytes-from ba (+ pos 4) size)
                  tag-kw  (get extra-tags tag-i)
                  base    {:tag tag-i :tag-name tag-kw :size size :data data}
                  decoded (when tag-kw (decode-extra-field tag-kw data))]
              (recur end
                     (conj! acc (cond-> base
                                  decoded (assoc :decoded decoded)))))))))))

(defn- decorate-cdr
  "Add decoded convenience keys to a raw CDR record."
  [cdr]
  (let [gp     (long (:general-purpose cdr))
        eattr  (long (:external-file-attributes cdr))
        vmade  (long (:version-made-by cdr))
        name   (:file-name cdr)
        dattrs (dos-attrs eattr)]
    (assoc cdr
      :last-modified    (dos->ldt (long (:last-mod-file-date cdr))
                                  (long (:last-mod-file-time cdr)))
      :dos-attributes   dattrs
      :unix-mode        (unix-mode-from vmade eattr)
      :directory?       (or (contains? dattrs :directory)
                            (and (string? name) (str/ends-with? name "/")))
      :encrypted?       (pos? (bit-and gp 0x0001))
      :utf8-name?       (pos? (bit-and gp 0x0800))
      :extra-fields     (parse-extra-fields (:extra-field cdr)))))

(defn- decorate-lfh
  "Add decoded convenience keys to a raw LFH record (no
  :external-file-attributes here)."
  [lfh]
  (let [gp   (long (:general-purpose lfh))
        name (:file-name lfh)]
    (assoc lfh
      :last-modified  (dos->ldt (long (:last-mod-file-date lfh))
                                (long (:last-mod-file-time lfh)))
      :directory?     (and (string? name) (str/ends-with? name "/"))
      :encrypted?     (pos? (bit-and gp 0x0001))
      :utf8-name?     (pos? (bit-and gp 0x0800))
      :extra-fields   (parse-extra-fields (:extra-field lfh)))))

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
      (when-let [idx (last-index-of-bytes buf eocdr-sig-bytes)]
        (+ win-off (long idx))))))

;; Note: in releases prior to 0.4.0 this namespace also defined
;; `read-spec-from-buffer`, `read-spec-from-file`,
;; `write-spec-to-buffer!`, and `write-spec-to-file!`. They live in
;; `clj-zip-meta.spec-io` now so that `core` can be loaded without
;; Octet (Babashka, GraalVM native-image, etc.).

;; ============================================================================
;; Reading the full metadata

(defn- read-cdr-records*
  "Read `entries` consecutive CDR records from `bb` starting at byte
  position `start-off`. Returns a vector of `{:offset N :record M}`."
  [^ByteBuffer bb ^long start-off ^long entries decode?]
  (loop [acc (transient [])
         off start-off
         n   entries]
    (if (zero? n)
      (persistent! acc)
      (let [raw    (read-cdr! bb off)
            sz     (cdr-size* raw)
            record (if decode? (decorate-cdr raw) raw)]
        (recur (conj! acc {:offset off :record record})
               (+ off sz)
               (dec n))))))

(defn- read-local-records*
  "Read the local file headers referenced by `cdr-records`. `bb` is a
  buffer that covers the start of the file at least through the
  beginning of the central directory. `extra-bytes` is added to each
  recorded `:relative-offset-local-header`."
  [^ByteBuffer bb cdr-records ^long extra-bytes decode?]
  (mapv
    (fn [cdr]
      (let [off (+ (long (:relative-offset-local-header cdr)) extra-bytes)
            raw (read-lfh! bb off)]
        {:offset off
         :record (if decode? (decorate-lfh raw) raw)}))
    cdr-records))

(defn get-cdr-records
  "Read `entries` central directory records from file `f` starting at
  byte offset `off`. Returns a vector of `{:offset N :record M}`.
  Records include the decoded convenience keys
  (`:last-modified`, `:dos-attributes`, `:unix-mode`, `:directory?`,
  `:encrypted?`, `:utf8-name?`, `:extra-fields`)."
  [f off entries]
  {:pre [(valid-offset? off)]}
  (with-raf [r f "r"]
    (let [bb (map-region r "r" 0 (.length r))]
      (read-cdr-records* bb (long off) (long entries) true))))

(defn get-local-records
  "Read the local file headers referenced by `cdr-records` from file
  `f`. `cdr-offset` is the central-directory start offset (used as a
  buffer-mapping upper bound). `extra-bytes` is added to each
  `:relative-offset-local-header` value. Records include the decoded
  convenience keys (`:last-modified`, `:directory?`, `:encrypted?`,
  `:utf8-name?`, `:extra-fields`)."
  [f cdr-records cdr-offset extra-bytes]
  (with-raf [r f "r"]
    (let [bb (map-region r "r" 0 (long cdr-offset))]
      (read-local-records* bb cdr-records (long extra-bytes) true))))

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
          eocdr-rec      (read-eocdr! bb 0)
          cdr-recorded   (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            (long (:cdr-size eocdr-rec)))
          extra-bytes    (- eocdr-off cdr-recorded)
          cdr-off-actual (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                            extra-bytes)]
      (when-not (valid-signature? r cdr-off-actual 4 cdr-sig-bytes)
        (throw (ex-info "Central directory signature not found at expected offset"
                        {:file             (str f)
                         :eocdr-offset     eocdr-off
                         :expected-cdr-off cdr-off-actual
                         :extra-bytes      extra-bytes})))
      [extra-bytes cdr-off-actual {:offset eocdr-off :record eocdr-rec}])))

(defn zip-meta
  "Read zip metadata from `f` (a path `String`, a `java.io.File`, or
  an open `RandomAccessFile`).

  With one argument or `{:include-locals true}` (the default) returns
  a map with keys:

    `:extra-bytes`       — number of bytes prepended before the zip
                            payload (0 for a well-formed archive)
    `:end-of-cdr-record` — `{:offset N :record M}` for the EOCDR
    `:cdr-records`       — vector of `{:offset N :record M}` central
                            directory entries
    `:local-records`     — vector of `{:offset N :record M}` local
                            file headers

  With `{:include-locals false}` the `:local-records` key is omitted;
  for archives with many entries this can be significantly faster
  when only the central directory is needed.

  Each `:record` map mirrors the corresponding zip-specification
  record (see `clj-zip-meta.spec` and APPNOTE.TXT §4.3) and is
  augmented with decoded convenience keys: `:last-modified`
  (LocalDateTime), `:directory?`, `:encrypted?`, `:utf8-name?`,
  `:extra-fields` (parsed TLV vector), and on CDR entries
  `:dos-attributes` (set) and `:unix-mode` (octal). Pass
  `{:decode false}` to skip decoration and get the raw fields only.

  Throws `ex-info` if the archive is malformed.

  Performance note: this function memory-maps the file once and reads
  every record from a single mapping; the channel is released as the
  function returns."
  ([f] (zip-meta f {}))
  ([f {:keys [include-locals decode]
       :or   {include-locals true decode true}}]
   (with-raf [r f "r"]
     (let [len            (.length r)
           eocdr-off      (or (find-end-of-cdr-offset r)
                              (throw (ex-info "End-of-central-directory record not found"
                                              {:file (str f) :length len})))
           ^ByteBuffer
           file-bb        (map-region r "r" 0 len)
           eocdr-rec      (read-eocdr! file-bb eocdr-off)
           cdr-recorded   (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                             (long (:cdr-size eocdr-rec)))
           extra-bytes    (- eocdr-off cdr-recorded)
           cdr-off-actual (+ (long (:cdr-offset-from-start-disk eocdr-rec))
                             extra-bytes)
           sig            cdr-sig-bytes
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
           cdrs           (read-cdr-records* file-bb cdr-off-actual entries (boolean decode))
           base           {:extra-bytes       extra-bytes
                           :end-of-cdr-record {:offset eocdr-off :record eocdr-rec}
                           :cdr-records       cdrs}]
       (if include-locals
         (assoc base :local-records
                (read-local-records* file-bb (mapv :record cdrs)
                                     extra-bytes (boolean decode)))
         base)))))

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
            (write-cdr! bb offset
                        (update cdr :relative-offset-local-header + extra-bytes)))
          (let [{eo-off :offset eo :record} (:end-of-cdr-record meta)]
            (write-eocdr! bb eo-off
                          (update eo :cdr-offset-from-start-disk + extra-bytes)))
          (.force ^MappedByteBuffer bb))))
    f))

(defn validate-zip-meta
  "Check the metadata in `f` for self-consistency. Returns a map:

    `:valid?`      — true iff no problems were found
    `:issues`      — vector of human-readable strings describing
                      each problem
    `:extra-bytes` — number of bytes prepended (informational)

  Options:

    `:repair`     — when truthy, rewrites prepended-byte offset
                    drift in place before re-running the validation.
                    Defaults to false.
    `:verify-crcs` — when truthy, also runs `verify-crcs` and rolls
                    any CRC mismatches / inflate errors into the
                    `:issues` vector. Defaults to false because it
                    has to read every entry's compressed data.
    `:print`      — when truthy, prints each issue to `*out*`.
                    Defaults to false. Provided for compatibility
                    with the prior side-effecting behavior."
  [f & {:keys [repair print verify-crcs]}]
  (when repair
    (repair-zip-with-preamble-bytes f))
  (let [meta   (zip-meta f)
        eo-cdr (:end-of-cdr-record meta)
        locals (:local-records meta)
        cdrs   (:cdr-records meta)
        extra  (long (:extra-bytes meta))
        crc-fail (when verify-crcs
                   (->> (verify-crcs f)
                        (filter #(contains? #{:mismatch :error} (:status %)))
                        seq))
        issues (cond-> []
                 (pos? extra)
                 (conj (str extra " extra bytes at beginning or within zipfile"))

                 (not (valid-signature? f (:offset eo-cdr) 4
                                        eocdr-sig-bytes))
                 (conj "invalid end of cdr signature")

                 (some (fn [{offset :offset}]
                         (not (valid-signature? f offset 4
                                                cdr-sig-bytes)))
                       cdrs)
                 (conj "invalid cdr record signatures found")

                 (some (fn [{offset :offset}]
                         (not (valid-signature? f offset 4
                                                lfh-sig-bytes)))
                       locals)
                 (conj "invalid local record signatures found")

                 crc-fail
                 (into (map (fn [r]
                              (str "CRC " (name (:status r)) " for " (:file-name r)))
                            crc-fail)))]
    (when print (run! println issues))
    {:valid?      (empty? issues)
     :issues      issues
     :extra-bytes extra}))

;; ============================================================================
;; Deep repair: rebuild CDR/EOCDR from local file headers

(def ^:private lfh-sig-int   0x04034b50)
(def ^:private cdr-sig-int   0x02014b50)
(def ^:private eocdr-sig-int 0x06054b50)
(def ^:private data-desc-sig-int 0x08074b50)

(defn- locate-data-descriptor
  "Scan `bb` from `data-start` to `end` for a data descriptor that
  validates: the descriptor's `compressed-size` must match the
  distance from `data-start` to the descriptor itself.

  Handles three cases:
    1. The optional 0x08074b50 signature appears, followed by the
       12 data-descriptor bytes (used by Java's `ZipOutputStream`).
       Located by direct signature match — works even when the
       descriptor is the very last record in the file.
    2. The descriptor has no signature; we locate the following
       LFH / CDR / EOCDR record and back up 12 bytes.
    3. The descriptor has a signature AND is followed by another
       record; case (1) finds it first.

  Returns

      {:dd-start          descriptor first byte
       :next-sig-pos      offset of the next LFH/CDR/EOCDR (or
                           data-descriptor-end when EOF)
       :crc-32            crc-32 from the descriptor
       :compressed-size   from the descriptor
       :uncompressed-size from the descriptor}

  or `nil` if no validating descriptor is found before `end`."
  [^ByteBuffer bb ^long data-start ^long end]
  (let [limit (- end 4)]
    (loop [i data-start]
      (when (<= i limit)
        (let [v (.getInt bb (int i))]
          (cond
            (= v data-desc-sig-int)
            (let [dd-start  i
                  crc-off   (+ dd-start 4)
                  has-room? (<= (+ crc-off 12) end)
                  csize     (when has-room? (.getInt bb (int (+ crc-off 4))))
                  actual    (- dd-start data-start)]
              (if (and has-room? (= (long csize) actual))
                {:dd-start          dd-start
                 :next-sig-pos      (+ dd-start 16)
                 :crc-32            (.getInt bb (int crc-off))
                 :compressed-size   csize
                 :uncompressed-size (.getInt bb (int (+ crc-off 8)))}
                (recur (inc i))))

            (or (= v lfh-sig-int) (= v cdr-sig-int) (= v eocdr-sig-int))
            (let [dd-with-sig? (and (>= i (+ data-start 16))
                                    (= data-desc-sig-int
                                       (.getInt bb (int (- i 16)))))
                  dd-start (if dd-with-sig? (- i 16) (- i 12))
                  crc-off  (if dd-with-sig? (+ dd-start 4) dd-start)
                  ok?      (and (>= dd-start data-start)
                                (>= crc-off 0))
                  csize    (when ok? (.getInt bb (int (+ crc-off 4))))
                  actual   (- dd-start data-start)]
              (if (and ok? (= (long csize) actual))
                {:dd-start          dd-start
                 :next-sig-pos      i
                 :crc-32            (.getInt bb (int crc-off))
                 :compressed-size   csize
                 :uncompressed-size (.getInt bb (int (+ crc-off 8)))}
                (recur (inc i))))

            :else
            (recur (inc i))))))))

(defn scan-local-headers
  "Walk file `f` from `:extra-bytes` (default 0) and return a vector of
  `{:offset N :record M}` maps, one per local file header found.

  The cursor moves through the archive by reading each LFH and then
  advancing past its data. When an entry uses a data descriptor
  (general-purpose bit 3) — the compressed-size in the LFH is zero —
  this function scans forward for the descriptor (with or without its
  optional 0x08074b50 signature), validates it against the actual
  data length, and yields an LFH record with the correct sizes and
  CRC-32 patched in. Scanning stops at the first central directory
  header or EOCDR.

  Useful for reconstructing a missing or corrupt central directory.

  Throws `ex-info` when a data descriptor cannot be located for an
  entry that claims one (corruption or truncation past the data
  descriptor)."
  ([f] (scan-local-headers f {}))
  ([f {:keys [extra-bytes max-entries]
       :or   {extra-bytes 0 max-entries 1000000}}]
   (with-raf [r f "r"]
     (let [len            (.length r)
           ^ByteBuffer bb (map-region r "r" 0 len)
           lfh-sig        lfh-sig-bytes]
       (loop [pos   (long extra-bytes)
              acc   (transient [])
              guard (long max-entries)]
         (cond
           (zero? guard)
           (throw (ex-info "scan-local-headers exceeded max-entries"
                           {:max-entries max-entries :found (count acc)}))

           (> (+ pos 4) len)
           (persistent! acc)

           :else
           (let [sig-int (.getInt bb (int pos))]
             (cond
               (or (= cdr-sig-int sig-int) (= eocdr-sig-int sig-int))
               (persistent! acc)

               (= lfh-sig-int sig-int)
               (let [lfh      (read-lfh! bb pos)
                     gp       (long (:general-purpose lfh))
                     hdr-size (lfh-size* lfh)]
                 (if (pos? (bit-and gp 0x8))
                   (let [data-start (+ pos hdr-size)
                         dd         (locate-data-descriptor bb data-start len)]
                     (if-not dd
                       (throw (ex-info "Cannot locate data descriptor for entry"
                                       {:file (str f) :offset pos
                                        :file-name (:file-name lfh)}))
                       (recur (long (:next-sig-pos dd))
                              (conj! acc {:offset pos
                                          :end-offset (:next-sig-pos dd)
                                          :record (assoc lfh
                                                    :compressed-size   (:compressed-size dd)
                                                    :uncompressed-size (:uncompressed-size dd)
                                                    :crc-32            (:crc-32 dd))})
                              (dec guard))))
                   (let [next-pos (+ pos hdr-size (long (:compressed-size lfh)))]
                     (recur next-pos
                            (conj! acc {:offset pos :end-offset next-pos :record lfh})
                            (dec guard)))))

               :else
               (if-let [next-pos (find-byte-pattern r lfh-sig pos 1)]
                 (recur (long next-pos) acc guard)
                 (persistent! acc))))))))))

(defn- lfh->cdr
  "Build a CDR record from a local file header `lfh` whose data
  begins at `relative-offset` bytes into the zip payload (i.e. after
  any prepended preamble)."
  [lfh ^long relative-offset]
  (let [name (:file-name lfh)
        dir? (and (string? name) (str/ends-with? name "/"))]
    {:cdr-header-signature         cdr-sig-int
     :version-made-by              0x14
     :version-needed-to-extract    (:version-needed-to-extract lfh)
     :general-purpose              (:general-purpose lfh)
     :compression-method           (:compression-method lfh)
     :last-mod-file-time           (:last-mod-file-time lfh)
     :last-mod-file-date           (:last-mod-file-date lfh)
     :crc-32                       (:crc-32 lfh)
     :compressed-size              (:compressed-size lfh)
     :uncompressed-size            (:uncompressed-size lfh)
     :file-name-length             (:file-name-length lfh)
     :extra-field-length           (:extra-field-length lfh)
     :file-comment-length          0
     :disk-number-start            0
     :internal-file-attributes     0
     :external-file-attributes     (if dir? 0x10 0)
     :relative-offset-local-header relative-offset
     :file-name                    name
     :extra-field                  (:extra-field lfh)
     :file-comment                 ""}))

(defn- mk-eocdr
  [cdrs ^long cdr-offset ^long cdr-size ^String zip-comment]
  (let [n (count cdrs)]
    {:end-of-cdr-signature       eocdr-sig-int
     :number-of-this-disk        0
     :number-of-cdr-disk         0
     :cdr-entries-this-disk      n
     :cdr-entries-total          n
     :cdr-size                   cdr-size
     :cdr-offset-from-start-disk cdr-offset
     :zip-comment-length         (count (.getBytes zip-comment "UTF-8"))
     :zip-comment                zip-comment}))

(defn rebuild-central-directory!
  "Rebuild the central directory and end-of-central-directory record
  in `f` from the local file headers found in the archive. The new
  CDR is written immediately after the last entry's data and the file
  is truncated at the end of the new EOCDR.

  Opts (all optional):

    `:extra-bytes`         number of prepended bytes before the zip
                            payload (default: auto-detect via
                            `zip-meta`, falling back to 0)
    `:zip-comment`         archive comment to use (default \"\")
    `:preserve-attrs?`     when truthy, read the file's existing CDR
                            entries (if any) and copy their
                            `:internal-file-attributes`,
                            `:external-file-attributes`,
                            `:version-made-by`, and `:file-comment`
                            fields onto the rebuilt entries (matched
                            by `:file-name`). Default true.

  Returns `f`. Throws `ex-info` if no local file headers are found or
  if any entry uses a data descriptor (general-purpose bit 3)."
  ([f] (rebuild-central-directory! f {}))
  ([f {:keys [extra-bytes zip-comment preserve-attrs?]
       :or   {zip-comment "" preserve-attrs? true}}]
   (let [extra  (long (or extra-bytes
                          (try (:extra-bytes (zip-meta f))
                               (catch clojure.lang.ExceptionInfo _ 0))))
         locals (scan-local-headers f {:extra-bytes extra})
         _      (when (empty? locals)
                  (throw (ex-info "Cannot rebuild central directory: no local file headers found"
                                  {:file (str f) :extra-bytes extra})))
         attrs  (when preserve-attrs?
                  (try
                    (into {}
                          (map (fn [{:keys [record]}]
                                 [(:file-name record)
                                  (select-keys record [:internal-file-attributes
                                                       :external-file-attributes
                                                       :version-made-by
                                                       :file-comment
                                                       :file-comment-length])]))
                          (:cdr-records (zip-meta f)))
                    (catch clojure.lang.ExceptionInfo _ {})))
         cdrs   (mapv (fn [{:keys [offset record]}]
                        (let [base (lfh->cdr record (- offset extra))]
                          (if-let [a (get attrs (:file-name record))]
                            (merge base a)
                            base)))
                      locals)
         cdr-start (long (:end-offset (last locals)))
         cdr-size  (reduce + 0 (map cdr-size* cdrs))
         eocdr     (mk-eocdr cdrs (- cdr-start extra) cdr-size zip-comment)
         eocdr-size (+ 22 (alength (.getBytes ^String zip-comment "UTF-8")))
         total     (+ cdr-start cdr-size eocdr-size)]
     (with-raf [r f "rw"]
       (.setLength r total)
       (let [^ByteBuffer bb (map-region r "rw" 0 total)]
         (loop [pos cdr-start
                rs  cdrs]
           (when-let [rec (first rs)]
             (write-cdr! bb pos rec)
             (recur (+ pos (cdr-size* rec))
                    (rest rs))))
         (write-eocdr! bb (+ cdr-start cdr-size) eocdr)
         (.force ^MappedByteBuffer bb)))
     f)))

(defn strip-preamble!
  "Physically remove the bytes prepended before the zip payload in
  `f`. After this call the file is `extra-bytes` shorter and the
  recorded CDR/EOCDR offsets — which were already correct for the
  un-prepended archive — match the file again.

  Returns `f`. No-op when there are no extra bytes.

  Important: do not call this after `repair-zip-with-preamble-bytes`
  on the same archive. The repair function bumps the recorded offsets
  by the preamble length; if you then strip the preamble too, the
  offsets become wrong in the other direction. Use one or the other,
  not both."
  [f]
  (let [m     (zip-meta f)
        extra (long (:extra-bytes m))]
    (when (pos? extra)
      (with-raf [r f "rw"]
        (let [len      (.length r)
              new-len  (- len extra)
              ;; Buffer size must not exceed `extra`, otherwise the read
              ;; region would overlap the just-written destination.
              buf-size (int (min extra 0x100000))
              buf      (byte-array buf-size)]
          (loop [src (long extra) dst 0]
            (when (< dst new-len)
              (let [n (int (min buf-size (- new-len dst)))]
                (.seek r src)
                (.readFully r buf 0 n)
                (.seek r (long dst))
                (.write r buf 0 n)
                (recur (+ src n) (+ dst n)))))
          (.setLength r new-len)
          (.. r getFD sync))))
    f))

(defn repair-zip
  "Attempt a holistic repair of `f`.

  Strategy:

    1. If the archive parses but reports `:extra-bytes > 0`:
       - With `:strip-preamble true`, physically remove the prepended
         bytes (the file shrinks).
       - Otherwise, rewrite the recorded offsets so they match the
         file again (the file size is unchanged).
    2. If the archive does NOT parse (missing EOCDR or CDR-signature
       mismatch) and `:rebuild-cdr` is truthy (default), rebuild the
       central directory from the local file headers.
    3. A final `validate-zip-meta` is run to confirm the result.

  Returns a map:

    `:status`   :ok | :failed
    `:actions`  vector of keywords describing what was done
                (`:preamble-fix`, `:preamble-stripped`,
                `:cdr-rebuilt`, `:no-op`)
    `:before`   the meta map before repair, or `nil` if unreadable
    `:after`    the meta map after repair, when status is :ok
    `:issues`   vector of remaining validation issues, when failed
    `:error`    error message, when failed unrecoverably"
  ([f] (repair-zip f {}))
  ([f opts]
   (let [{:keys [rebuild-cdr strip-preamble zip-comment]
          :or   {rebuild-cdr true strip-preamble false}} opts
         before (try (zip-meta f)
                     (catch clojure.lang.ExceptionInfo _ nil))
         actions (transient [])]
     (try
       (cond
         (and before (pos? (long (:extra-bytes before))))
         (if strip-preamble
           (do (strip-preamble! f) (conj! actions :preamble-stripped))
           (do (repair-zip-with-preamble-bytes f) (conj! actions :preamble-fix)))

         (nil? before)
         (if rebuild-cdr
           (do (rebuild-central-directory! f
                                           (cond-> {}
                                             zip-comment (assoc :zip-comment zip-comment)))
               (conj! actions :cdr-rebuilt))
           (throw (ex-info "Cannot read archive metadata and :rebuild-cdr is false"
                           {:file (str f)})))

         :else
         (conj! actions :no-op))

       (let [v (validate-zip-meta f)]
         (if (:valid? v)
           {:status :ok :actions (persistent! actions)
            :before before :after (zip-meta f)}
           {:status :failed :actions (persistent! actions)
            :before before :issues (:issues v)}))
       (catch Exception e
         {:status :failed :actions (persistent! actions)
          :before before :error (.getMessage e)
          :data (ex-data e)})))))

;; ============================================================================
;; Convenience / ergonomic API

(defn- entry-matcher
  "Turn the value of `:match` into a predicate over file-name strings.
  Accepts a `java.util.regex.Pattern`, a substring `String`, a
  function, or `nil` (match everything)."
  [match]
  (cond
    (nil? match)         (constantly true)
    (instance? java.util.regex.Pattern match) #(boolean (re-find match %))
    (string? match)      #(boolean (and % (str/includes? % match)))
    (fn? match)          match
    :else (throw (ex-info ":match must be a Pattern, String, fn, or nil"
                          {:match match}))))

(defn zip-entries
  "Return a vector of compact entry summaries for `f`, one per central
  directory record. Each entry has the keys:

    `:file-name`           — the entry's path inside the archive
    `:file-comment`        — per-entry comment (often empty)
    `:compressed-size`     — compressed size in bytes
    `:uncompressed-size`   — uncompressed size in bytes
    `:crc-32`              — CRC-32 of the uncompressed data
    `:compression-method`  — 0 = stored, 8 = deflate, etc.
    `:offset`              — file offset of the CDR record
    `:directory?`          — true if the entry is a directory
    `:encrypted?`          — true if general-purpose bit 0 is set
    `:last-modified`       — `java.time.LocalDateTime` of the entry
    `:unix-mode`           — Unix file mode (octal) or nil
    `:dos-attributes`      — set of DOS-attribute keywords

  Reads only the central directory — much faster than `zip-meta` for
  archives with many entries when local file headers are not needed.

  Options:
    `:match` — keep only entries whose `:file-name` matches. May be a
                `java.util.regex.Pattern`, a substring `String`, or a
                function `(fn [file-name] ...)`. Defaults to nil
                (match everything)."
  ([f] (zip-entries f {}))
  ([f {:keys [match]}]
   (let [pred (entry-matcher match)]
     (into []
           (comp
             (map (fn [{offset :offset cdr :record}]
                    {:file-name          (:file-name cdr)
                     :file-comment       (:file-comment cdr)
                     :compressed-size    (:compressed-size cdr)
                     :uncompressed-size  (:uncompressed-size cdr)
                     :crc-32             (:crc-32 cdr)
                     :compression-method (:compression-method cdr)
                     :offset             offset
                     :directory?         (:directory? cdr)
                     :encrypted?         (:encrypted? cdr)
                     :last-modified      (:last-modified cdr)
                     :unix-mode          (:unix-mode cdr)
                     :dos-attributes     (:dos-attributes cdr)}))
             (filter (fn [e] (pred (:file-name e)))))
           (:cdr-records (zip-meta f {:include-locals false}))))))

(defn find-entry
  "Find the entry whose `:file-name` equals `file-name`. Returns the
  compact summary map (see `zip-entries`) or `nil` if not found."
  [f file-name]
  (some #(when (= (:file-name %) file-name) %) (zip-entries f)))

(defn diff
  "Compare two archives by file-name. Returns a map describing the
  differences:

    `:added`    — vector of entries in `b` but not in `a`
    `:removed`  — vector of entries in `a` but not in `b`
    `:changed`  — vector of `{:file-name N :before E :after E}` for
                  entries whose CRC, compressed-size or
                  uncompressed-size differ between the two archives
    `:same`     — number of entries present in both archives with
                  identical CRC and sizes

  Useful for answering \"did this jar actually change?\" — for
  instance comparing the artifact a build produced today against
  yesterday's."
  [a b]
  (let [as       (zip-entries a)
        bs       (zip-entries b)
        a-by-nm  (into {} (map (juxt :file-name identity)) as)
        b-by-nm  (into {} (map (juxt :file-name identity)) bs)
        a-names  (set (keys a-by-nm))
        b-names  (set (keys b-by-nm))
        only-a   (sort (remove b-names a-names))
        only-b   (sort (remove a-names b-names))
        both     (sort (filter a-names b-names))
        same?    (fn [ea eb]
                   (and (= (:crc-32 ea)            (:crc-32 eb))
                        (= (:compressed-size ea)   (:compressed-size eb))
                        (= (:uncompressed-size ea) (:uncompressed-size eb))))]
    {:added   (mapv b-by-nm only-b)
     :removed (mapv a-by-nm only-a)
     :changed (vec
                (for [n     both
                      :let  [ea (get a-by-nm n)
                             eb (get b-by-nm n)]
                      :when (not (same? ea eb))]
                  {:file-name n :before ea :after eb}))
     :same    (count (filter (fn [n] (same? (a-by-nm n) (b-by-nm n))) both))}))

(defn hexdump
  "Return a classic hex-dump string of `length` bytes starting at byte
  offset `offset` in file `f`. Output rows look like:

      00000000  50 4b 03 04 14 00 00 00  08 00 00 00 00 00 00 00  |PK..............|

  Useful for debugging when you have a record offset (from `zip-meta`)
  and want to eyeball the surrounding bytes. `length` defaults to 256."
  ([f offset] (hexdump f offset 256))
  ([f offset length]
   (with-raf [r f "r"]
     (let [file-len (.length r)
           start    (long offset)
           want     (min (long length) (- file-len start))
           buf      (byte-array (int want))]
       (.seek r start)
       (.readFully r buf 0 (int want))
       (let [sb (StringBuilder.)]
         (loop [i 0]
           (when (< i want)
             (.append sb (format "%08x  " (+ start i)))
             (dotimes [j 16]
               (cond
                 (< (+ i j) want)
                 (do (.append sb (format "%02x " (bit-and 0xFF (long (aget buf (int (+ i j)))))))
                     (when (= j 7) (.append sb " ")))

                 (= j 7) (.append sb "    ")
                 :else   (.append sb "   ")))
             (.append sb " |")
             (dotimes [j 16]
               (when (< (+ i j) want)
                 (let [b (bit-and 0xFF (long (aget buf (int (+ i j)))))]
                   (.append sb (if (and (<= 0x20 b) (<= b 0x7E)) (char b) \.)))))
             (.append sb "|\n")
             (recur (+ i 16))))
         (.toString sb))))))

;; ----------------------------------------------------------------------------
;; CRC verification

(defn- compute-crc-for-entry
  "Verify one entry's data against its recorded CRC-32. Returns a
  map with :file-name, :status (`:ok` / `:mismatch` / `:empty` /
  `:unsupported-method` / `:error`), :recorded-crc, optional
  :computed-crc, optional :error / :method."
  [^ByteBuffer file-bb cdr ^long extra-bytes]
  (let [name      (:file-name cdr)
        recorded  (long (:crc-32 cdr))
        recorded* (bit-and 0xFFFFFFFF recorded)
        meth      (long (:compression-method cdr))
        usize     (long (:uncompressed-size cdr))
        csize     (long (:compressed-size cdr))
        lfh-off   (+ (long (:relative-offset-local-header cdr)) extra-bytes)
        nlen      (bit-and 0xFFFF (long (.getShort file-bb (int (+ lfh-off 26)))))
        elen      (bit-and 0xFFFF (long (.getShort file-bb (int (+ lfh-off 28)))))
        data-off  (+ lfh-off 30 nlen elen)
        base      {:file-name name :recorded-crc recorded}]
    (cond
      (zero? usize)
      (assoc base :status :empty)

      (= 0 meth)
      (try
        (let [data (byte-array (int csize))]
          (.position file-bb (int data-off))
          (.get file-bb data)
          (let [c        (doto (CRC32.) (.update data))
                computed (.getValue c)]
            (assoc base
              :status       (if (= recorded* computed) :ok :mismatch)
              :computed-crc computed)))
        (catch Exception e
          (assoc base :status :error :error (.getMessage e))))

      (= 8 meth)
      (let [compressed (byte-array (int csize))
            inflater   (Inflater. true)]
        (try
          (.position file-bb (int data-off))
          (.get file-bb compressed)
          (.setInput inflater compressed)
          (let [out    (byte-array (int usize))
                ilen   (.inflate inflater out 0 (int usize))
                c      (doto (CRC32.) (.update out 0 ilen))
                computed (.getValue c)]
            (assoc base
              :status       (if (= recorded* computed) :ok :mismatch)
              :computed-crc computed))
          (catch Exception e
            (assoc base :status :error :error (.getMessage e)))
          (finally (.end inflater))))

      :else
      (assoc base :status :unsupported-method :method meth))))

(defn verify-crcs
  "Read every entry's compressed data from `f`, decompress it, and
  compare the resulting CRC-32 against the value recorded in the
  central directory. Returns a vector of maps, one per entry:

      {:file-name    the entry name
       :status       :ok | :mismatch | :empty | :unsupported-method | :error
       :recorded-crc the recorded CRC-32 (signed long, as octet returns)
       :computed-crc the computed CRC-32 (unsigned long; only present
                      when actually computed)
       :method       the compression method (only when :unsupported-method)
       :error        the exception message (only when :error)}

  Supports STORED (0) and DEFLATE (8) — the methods used by every
  jar and the vast majority of zips. Other methods (BZIP2, LZMA,
  etc.) yield `:unsupported-method` so the caller can decide whether
  to treat that as a failure.

  This is the strongest integrity check the library performs: it
  verifies the data itself, not just the metadata."
  [f]
  (with-raf [r f "r"]
    (let [len            (.length r)
          ^ByteBuffer bb (map-region r "r" 0 len)
          m              (zip-meta r {:decode false :include-locals false})
          extra          (long (:extra-bytes m))]
      (mapv #(compute-crc-for-entry bb (:record %) extra) (:cdr-records m)))))

(defn verify-crcs-summary
  "Run `verify-crcs` and return a map summarising the per-status
  counts plus the entries (if any) whose CRC did not match:

      {:total       N
       :counts      {:ok N :mismatch N :empty N
                     :unsupported-method N :error N}
       :mismatches  [{...verify entry...} ...]
       :errors      [{...} ...]
       :valid?      true iff every non-skipped entry verified}"
  [f]
  (let [results (verify-crcs f)
        counts  (frequencies (map :status results))
        mis     (filterv #(= :mismatch (:status %)) results)
        errs    (filterv #(= :error    (:status %)) results)]
    {:total      (count results)
     :counts     counts
     :mismatches mis
     :errors     errs
     :valid?     (and (empty? mis) (empty? errs))}))

(defn zip-comment
  "Return the archive-level comment from `f` (an empty string if
  none)."
  [f]
  (get-in (zip-meta f {:include-locals false})
          [:end-of-cdr-record :record :zip-comment]))

(defn set-zip-comment!
  "Replace the archive-level comment in `f` with `comment` (encoded
  UTF-8). The file is resized if the new comment is a different
  length than the old one. Returns `f`.

  Note: the zip specification limits the comment to 65 535 bytes."
  [f ^String comment]
  (let [cbytes  (.getBytes comment "UTF-8")]
    (when (> (alength cbytes) 0xFFFF)
      (throw (ex-info "zip comment exceeds the 65 535-byte maximum"
                      {:length (alength cbytes)})))
    (let [m       (zip-meta f {:include-locals false :decode false})
          {eo-off :offset eo :record} (:end-of-cdr-record m)
          new-eo  (assoc eo :zip-comment comment
                           :zip-comment-length (alength cbytes))
          new-eo-size (+ 22 (alength cbytes))
          new-len     (+ (long eo-off) new-eo-size)]
      (with-raf [r f "rw"]
        (.setLength r new-len)
        (let [^ByteBuffer bb (map-region r "rw" 0 new-len)]
          (write-eocdr! bb eo-off new-eo)
          (.force ^MappedByteBuffer bb))))
    f))

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
