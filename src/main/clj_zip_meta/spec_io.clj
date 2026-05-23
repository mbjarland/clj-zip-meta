(ns clj-zip-meta.spec-io
  "Octet-backed read/write helpers for the record specs defined in
  `clj-zip-meta.spec`.

  This namespace exists separately from `clj-zip-meta.core` so that
  `core` can be loaded under runtimes — Babashka, for example — that
  do not bundle Octet's transitive dependencies. Loading this
  namespace pulls Octet (and through it `io.netty.buffer`) onto the
  classpath; loading `clj-zip-meta.core` does not.

  The four functions below cover the original public read/write
  surface from older releases. They are useful when you have a
  custom Octet spec and want to plug it into the same I/O plumbing
  the library uses internally. Most callers should reach for the
  higher-level API in `clj-zip-meta.core` instead."
  (:require [clj-zip-meta.spec]
            [octet.core :as buf]
            [clojure.java.io :as jio])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer ByteOrder MappedByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)))

;; ---------------------------------------------------------------------------
;; Local I/O helpers (mirroring what core uses internally). Kept small
;; and self-contained so this namespace does not depend on any
;; private symbol from core.

(defn ^:private ^RandomAccessFile coerce-raf [f ^String mode]
  (if (instance? RandomAccessFile f)
    f
    (RandomAccessFile. ^File (jio/as-file f) mode)))

(defn ^:private ^ByteBuffer map-region
  [^RandomAccessFile raf ^String mode ^long offset ^long size]
  (let [cm (case mode
             "r"  FileChannel$MapMode/READ_ONLY
             "rw" FileChannel$MapMode/READ_WRITE)
        ch (.getChannel raf)]
    (-> (.map ch cm offset size)
        (.order ByteOrder/LITTLE_ENDIAN))))

;; ---------------------------------------------------------------------------
;; Public API

(defn read-spec-from-buffer
  "Read a single record matching `spec` from `ByteBuffer` `buff` at
  byte position `off`. Always little-endian."
  [^ByteBuffer buff spec off]
  (buf/with-byte-order :little-endian
    (buf/read buff spec {:offset (long off)})))

(defn read-spec-from-file
  "Read a single record matching `spec` from `f` (a path string, a
  `java.io.File`, or an already-open `java.io.RandomAccessFile`) at
  byte offset `off`. Always little-endian."
  [f spec off]
  {:pre [(integer? off) (not (neg? (long off)))]}
  (let [close? (not (instance? RandomAccessFile f))
        ^RandomAccessFile r (coerce-raf f "r")]
    (try
      (let [bb (map-region r "r" (long off) (- (.length r) (long off)))]
        (read-spec-from-buffer bb spec 0))
      (finally
        (when close? (.close r))))))

(defn write-spec-to-buffer!
  "Write `data` matching `spec` to `ByteBuffer` `buff` at byte
  position `off`. Always little-endian."
  [^ByteBuffer buff data spec off]
  (buf/with-byte-order :little-endian
    (buf/write! buff data spec {:offset (long off)})))

(defn write-spec-to-file!
  "Write `data` matching `spec` to file `f` at byte offset `off`.
  Always little-endian. The file must already be at least large
  enough to hold the record."
  [f data spec off]
  {:pre [(integer? off) (not (neg? (long off)))]}
  (let [close? (not (instance? RandomAccessFile f))
        ^RandomAccessFile r (coerce-raf f "rw")]
    (try
      (let [bb (map-region r "rw" (long off) (- (.length r) (long off)))]
        (write-spec-to-buffer! bb data spec 0)
        (.force ^MappedByteBuffer bb))
      (finally
        (when close? (.close r))))))
