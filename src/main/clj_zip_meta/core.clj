(ns clj-zip-meta.core
  (:require
    [clj-zip-meta.spec :refer :all]
    [octet.core :as buf]
    [octet.util :refer [hex-dump]]
    [clojure.string :as str]
    [clojure.java.io :as jio])
  (:import (java.io File RandomAccessFile EOFException FileInputStream)
           (java.nio.channels Channel FileChannel FileChannel$MapMode)
           (java.util Arrays)
           (java.nio ByteBuffer ByteOrder)))

(defn dump-sig-number [sig-value]
  (let [d (hex-dump (.toByteArray (BigInteger/valueOf sig-value))
                    :frame false :print false)
        s (str/split d #" ")]
    (str (nth s 1) " " (nth s 2))))

(defn dump-sig-bytes [sig-bytes]
  (octet.util/bytes->hex sig-bytes))

(defn raf [f ^String mode]
  "utility function to create a RandomAccessFile"
  (if (instance? RandomAccessFile f)
    f
    (RandomAccessFile. ^File (jio/as-file f) mode)))

(defn find-byte-pattern
  "seek for a byte array pattern in the data of a file.
   start-offset can be -1 to search from the end of the file.
   step can be negative for reverse search. Returns the file
   offset of the pattern when found or nil if not found"
  ([f ^bytes pattern]
   (find-byte-pattern f pattern 0))

  ([f ^bytes pattern ^long start-offset]
   (find-byte-pattern f pattern start-offset 1))

  ([f ^bytes pattern ^long start-offset ^long step]
   {:pre [((some-fn pos? zero? #(= -1 %)) start-offset)]}
   (let [plen  (alength pattern)
         buf   (byte-array plen)
         raf   (raf f "r")
         eof   (- (.length raf) (alength pattern))
         start (if (= -1 start-offset) eof start-offset)]
     (.seek raf start)
     (.readFully raf buf)
     (if (Arrays/equals pattern buf)
       start
       (loop [offset (+ start step)]
         (when (not (neg? offset))
           (.seek raf offset)
           (.read raf buf 0 plen)
           (if (Arrays/equals pattern buf)
             offset
             (recur (+ offset step)))))))))

(defn map-byte-buffer
  "map a file or part of a file to a byte buffer. Mode should be
  either the string r or the string rw. To map parts of the file,
  use the offset and size parameters. Quoting the javadocs for
  FileChannel:
  A mapping, once established, is not dependent upon the file channel
  that was used to create it. Closing the channel, in particular, has
  no effect upon the validity of the mapping"
  ([f mode]
   (map-byte-buffer f mode 0))

  ([f mode offset]
   (map-byte-buffer f mode offset (.length (raf f mode))))

  ([f mode offset size]
   (let [raf (raf f mode)
         cm  (cond (= mode "r") FileChannel$MapMode/READ_ONLY
                   (= mode "rw") FileChannel$MapMode/READ_WRITE
                   :else (throw (IllegalArgumentException. (str "invalid mode " mode))))
         bb  (with-open
               [^FileChannel c (.getChannel raf)]
               (.map c cm offset size))]
     (.order bb ByteOrder/LITTLE_ENDIAN))))

(defn valid-offset?
  "simple validation function for offsets"
  [off]
  (not (or (nil? off) (neg? off))))

(defn valid-signature?
  "checks whether a file has a specific zip record signature
  at a specific offset"
  [f ^long offset ^long size ^bytes signature]
  (let [bb        (map-byte-buffer f "r" offset size)
        ^bytes ba (octet.buffer/read-bytes bb 0 size)]
    (java.util.Arrays/equals ba signature)))

(defn find-end-of-cdr-offset
  "given a file (or anything that jio/as-file will consume)
  will find the end-of-cdr record offset by traversing the file
  backwards starting from the end"
  [f]
  (find-byte-pattern f (first (vals rec-end-of-cdr-sig)) -1 -1))

(defn read-spec-from-buffer [buff spec off]
  (buf/with-byte-order
    :little-endian
    (buf/read buff spec {:offset off})))

(defn read-spec-from-file [f spec off]
  {:pre [(valid-offset? off)]}
  (let [raf (raf f "r")
        buf (map-byte-buffer raf "r" off (- (.length raf) off))]
    (buf/with-byte-order
      :little-endian
      (buf/read buf spec))))

(defn write-spec-to-buffer! [buff data spec off]
  (buf/with-byte-order
    :little-endian
    (buf/write! buff data spec {:offset off})))

(defn write-spec-to-file! [f data spec off]
  {:pre [(valid-offset? off)]}
  (let [raf (raf f "rw")
        buf (map-byte-buffer raf "rw" off (- (.length raf) off))]
    (buf/with-byte-order
      :little-endian
      (buf/write! buf data spec))))

(defn get-cdr-records [f off entries]
  {:pre [(valid-offset? off)]}
  (let [raf (raf f "r")
        len (- (.length raf) off)
        buf (map-byte-buffer raf "r" off len)]
    (first
      (reduce
        (fn [[acc offset] _]
          (let [record (read-spec-from-buffer buf rec-cdr-header offset)]
            [(conj acc {:offset (+ offset off) :record record})
             (+ offset (.size* rec-cdr-header record))]))
        [[] 0]
        (range entries)))))

(defn get-local-records [f cdr-records cdr-offset extra-bytes]
  (let [end cdr-offset
        raf (raf f "r")
        buf (map-byte-buffer raf "r" 0 cdr-offset)]
    (reduce
      (fn [acc cdr-record]
        (let [off    (+ (:relative-offset-local-header cdr-record) extra-bytes)
              record (read-spec-from-buffer buf rec-local-file-header off)]
          (conj acc {:offset off :record record})))
      []
      cdr-records)))

(defn sig-bytes [rec-sig]
  (first (vals rec-sig)))

(defn read-end-of-cdr-record [f]
  (let [actual-eo-off  (find-end-of-cdr-offset f)
        eo-cdr         (read-spec-from-file f rec-end-of-cdr actual-eo-off)
        eo-off-on-file (+ (:cdr-offset-from-start-disk eo-cdr)
                          (:cdr-size eo-cdr))
        extra-bytes    (- actual-eo-off eo-off-on-file)
        actual-cdr-off (+ (:cdr-offset-from-start-disk eo-cdr) extra-bytes)]
    (if (not (valid-signature? f actual-cdr-off 4 (sig-bytes rec-cdr-header-sig)))
      (throw (IllegalStateException. "Unable to locate the start of the zip central directory"))
      [extra-bytes actual-cdr-off {:offset actual-eo-off
                                   :record eo-cdr}])))

(defn zip-meta [f]
  (let [[extra-bytes cdr-offset eo-cdr] (read-end-of-cdr-record f)
        entries-total (:cdr-entries-total (:record eo-cdr))
        cdrs          (get-cdr-records f cdr-offset entries-total)
        locals        (get-local-records f (mapv :record cdrs) cdr-offset extra-bytes)]
    {:extra-bytes       extra-bytes
     :end-of-cdr-record eo-cdr
     :cdr-records       cdrs
     :local-records     locals}))

(defn validate-zip-meta [f]
  (let [meta   (zip-meta f)
        eo-cdr (:end-of-cdr-record meta)
        locals (:local-records meta)
        cdrs   (:cdr-records meta)]
    (when (not (zero? (:extra-bytes meta)))
      (println (:extra-bytes meta) "extra bytes at beginning or within zipfile"))

    (when (not (valid-signature? f (:offset eo-cdr) 4 (sig-bytes rec-end-of-cdr-sig)))
      (println "invalid end of cdr signature, todo: write out actual vs recorded"))

    (when (some
            (fn [{offset :offset cdr :record}]
              (not (valid-signature? f offset 4 (sig-bytes rec-cdr-header-sig))))
            cdrs)
      (println "invalid cdr record signatures found!"))

    (when (some
            (fn [{offset :offset cdr :record}]
              (not (valid-signature? f offset 4 (sig-bytes rec-local-file-header-sig))))
            locals)
      (println "invalid local record signatures found"))))

(defn repair-zip-with-preamble-bytes [f]
  "repairs a zip file with 'extra bytes' at the beginning of the file
  where the zip meta data offsets have not been updated to reflect the
  extra bytes"
  (let [meta        (zip-meta f)
        extra-bytes (:extra-bytes meta)
        g           (fn [off] (+ off extra-bytes))]
    (doseq [{offset :offset cdr :record} (:cdr-records meta)]
      (let [updated (update cdr :relative-offset-local-header g)]
        (write-spec-to-file! f updated rec-cdr-header offset)))


    (let [{eo-cdr-off :offset eo-cdr :record} (:end-of-cdr-record meta)
          updated (update eo-cdr :cdr-offset-from-start-disk g)]
      (write-spec-to-file! f
                           updated
                           rec-end-of-cdr
                           eo-cdr-off))))
