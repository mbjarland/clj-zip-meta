(ns clj-zip-meta.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as jio]
            [clj-zip-meta.core :as zm]
            [clj-zip-meta.spec :as zspec])
  (:import (java.io ByteArrayOutputStream File FileOutputStream
                    RandomAccessFile)
           (java.nio.file Files StandardCopyOption)
           (java.util.zip ZipEntry ZipOutputStream)))

;; ---------------------------------------------------------------------------
;; fixtures

(def good-file "src/test/resources/good.zip")
(def bad-prelude-file "src/test/resources/bad_prelude.zip")

(defn- copy-to-tmp
  "Copy `src` to a fresh temp file and return the absolute path. The
  temp file is removed when the JVM exits."
  ^String [^String src ^String prefix]
  (let [tmp (doto (File/createTempFile prefix ".zip")
              (.deleteOnExit))]
    (Files/copy (.toPath (jio/file src))
                (.toPath tmp)
                ^"[Ljava.nio.file.CopyOption;"
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (.getAbsolutePath tmp)))

;; ---------------------------------------------------------------------------
;; reading

(deftest parses-clean-archive
  (let [m (zm/zip-meta good-file)]
    (is (= 0 (:extra-bytes m)))
    (is (= 3 (count (:cdr-records m))))
    (is (= 3 (count (:local-records m))))
    (is (= 563 (get-in m [:end-of-cdr-record :offset])))
    (is (= 309 (get-in m [:end-of-cdr-record :record :cdr-offset-from-start-disk])))
    (is (= 309 (get-in m [:cdr-records 0 :offset])))))

(deftest signatures-match-the-spec
  (let [m (zm/zip-meta good-file)]
    (is (= 0x06054b50 (get-in m [:end-of-cdr-record :record :end-of-cdr-signature])))
    (is (= 0x02014b50 (get-in m [:cdr-records 0 :record :cdr-header-signature])))
    (is (= 0x04034b50 (get-in m [:local-records 0 :record :local-header-signature])))))

(deftest parses-archive-with-prepended-bytes
  (let [m (zm/zip-meta bad-prelude-file)]
    (is (= 317 (:extra-bytes m)))
    ;; The local-record offsets in the returned map already include the
    ;; preamble shift, so they point at real LFH signatures on disk.
    (doseq [{:keys [offset record]} (:local-records m)]
      (is (= 0x04034b50 (:local-header-signature record))
          (str "local header signature mismatch at offset " offset)))))

(deftest accepts-file-and-path-and-raf
  (testing "string path"
    (is (map? (zm/zip-meta good-file))))
  (testing "File"
    (is (map? (zm/zip-meta (jio/file good-file)))))
  (testing "open RandomAccessFile (caller-owned)"
    (with-open [r (RandomAccessFile. (jio/file good-file) "r")]
      (let [m (zm/zip-meta r)]
        (is (= 3 (count (:cdr-records m)))))
      ;; The caller still owns the RAF and can keep using it.
      (is (pos? (.length r))))))

;; ---------------------------------------------------------------------------
;; convenience API

(deftest zip-entries-returns-compact-summaries
  (let [entries (zm/zip-entries good-file)]
    (is (= 3 (count entries)))
    (is (every? :file-name entries))
    (is (every? #(contains? % :compressed-size) entries))
    (is (every? #(contains? % :uncompressed-size) entries))
    (is (every? #(contains? % :compression-method) entries))
    (is (every? #(contains? % :crc-32) entries))))

(deftest zip-comment-returns-empty-string-when-absent
  (is (= "" (zm/zip-comment good-file))))

(deftest summarize-returns-aggregate-stats
  (let [s (zm/summarize good-file)]
    (is (= 3 (:entry-count s)))
    (is (= 0 (:extra-bytes s)))
    (is (nat-int? (:total-compressed s)))
    (is (nat-int? (:total-uncompressed s)))
    (is (string? (:zip-comment s)))))

(deftest print-zip-meta-renders-byte-arrays-as-hex
  (let [out (with-out-str (zm/print-zip-meta good-file))]
    ;; Byte-arrays should no longer be rendered as identity strings.
    (is (not (re-find #"\[B@" out)))
    (is (re-find #"#bytes" out))))

;; ---------------------------------------------------------------------------
;; validation

(deftest validate-clean-archive
  (let [r (zm/validate-zip-meta good-file)]
    (is (true? (:valid? r)))
    (is (= [] (:issues r)))
    (is (zero? (:extra-bytes r)))))

(deftest validate-archive-with-preamble
  (let [r (zm/validate-zip-meta bad-prelude-file)]
    ;; The single issue is the prepended-byte count notification.
    (is (false? (:valid? r)))
    (is (= 317 (:extra-bytes r)))
    (is (= 1 (count (:issues r))))
    (is (re-find #"317 extra bytes" (first (:issues r))))))

;; ---------------------------------------------------------------------------
;; repair round-trip

(deftest repair-fixes-preamble-drift
  (let [tmp (copy-to-tmp bad-prelude-file "repair-")]
    (testing "before repair"
      (is (= 317 (:extra-bytes (zm/zip-meta tmp)))))
    (zm/repair-zip-with-preamble-bytes tmp)
    (testing "after repair"
      (let [m (zm/zip-meta tmp)
            r (zm/validate-zip-meta tmp)]
        (is (zero? (:extra-bytes m)))
        (is (true? (:valid? r)))
        (is (empty? (:issues r)))))))

(deftest repair-is-idempotent
  (let [tmp (copy-to-tmp bad-prelude-file "repair-idempotent-")]
    (zm/repair-zip-with-preamble-bytes tmp)
    (zm/repair-zip-with-preamble-bytes tmp)
    (is (zero? (:extra-bytes (zm/zip-meta tmp))))))

(deftest repair-on-clean-archive-is-noop
  (let [tmp     (copy-to-tmp good-file "noop-")
        before  (slurp tmp)]
    (zm/repair-zip-with-preamble-bytes tmp)
    (is (= before (slurp tmp)))))

(deftest validate-with-repair-option
  (let [tmp (copy-to-tmp bad-prelude-file "validate-repair-")
        r   (zm/validate-zip-meta tmp :repair true)]
    (is (true? (:valid? r)))
    (is (zero? (:extra-bytes (zm/zip-meta tmp))))))

;; ---------------------------------------------------------------------------
;; low-level helpers

(deftest find-byte-pattern-backward-from-end
  ;; The end-of-CDR signature occurs exactly once in good.zip.
  (let [sig    (first (vals zspec/rec-end-of-cdr-sig))
        offset (zm/find-byte-pattern good-file sig -1 -1)]
    (is (= 563 offset))))

(deftest find-byte-pattern-returns-nil-when-absent
  (let [absent (byte-array [0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x42])]
    (is (nil? (zm/find-byte-pattern good-file absent)))))

(deftest read-end-of-cdr-record-throws-ex-info-on-non-zip
  (let [tmp (doto (File/createTempFile "notazip-" ".bin")
              (.deleteOnExit))]
    (jio/copy (byte-array (repeat 1024 0)) tmp)
    (is (thrown? clojure.lang.ExceptionInfo
                 (zm/read-end-of-cdr-record (.getAbsolutePath tmp))))))

(deftest dump-helpers-format-as-hex
  (is (= "50 4b 05 06" (zm/dump-sig-number 0x06054b50)))
  (is (= "50 4b 05 06" (zm/dump-sig-bytes (byte-array [0x50 0x4b 0x05 0x06])))))

;; ---------------------------------------------------------------------------
;; deep repair: rebuild CDR from local headers

(defn- write-test-zip
  "Write a small zip with `entries` (a map of name -> content string).
  Returns the absolute path to a temp file marked deleteOnExit."
  ^String [entries]
  (let [tmp (doto (File/createTempFile "ziptest-" ".zip")
              (.deleteOnExit))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (doseq [[name content] entries]
        (.putNextEntry out (ZipEntry. ^String name))
        (.write out (.getBytes (str content) "UTF-8"))
        (.closeEntry out)))
    (.getAbsolutePath tmp)))

(defn- truncate!
  "Truncate `path` to `new-length` bytes."
  [^String path ^long new-length]
  (with-open [r (RandomAccessFile. path "rw")]
    (.setLength r new-length)))

(deftest creates-and-parses-a-test-zip
  (let [path (write-test-zip {"a.txt" "alpha" "b.txt" "bravo"})
        m    (zm/zip-meta path)]
    (is (= 2 (count (:cdr-records m))))
    (is (= ["a.txt" "b.txt"]
           (map (comp :file-name :record) (:cdr-records m))))))

(deftest scan-local-headers-finds-every-lfh
  (let [path (write-test-zip {"a.txt" "alpha"
                              "b.txt" "bravo"
                              "c.txt" "charlie"})
        lfhs (zm/scan-local-headers path)]
    (is (= 3 (count lfhs)))
    (is (= ["a.txt" "b.txt" "c.txt"]
           (map (comp :file-name :record) lfhs)))
    ;; Every found LFH should have the right signature.
    (is (every? #(= 0x04034b50 (-> % :record :local-header-signature)) lfhs))))

(deftest rebuild-central-directory-restores-truncated-zip
  ;; java.util.zip's ZipOutputStream uses data descriptors for deflated
  ;; entries (compressed-size is unknown until after writing). To
  ;; exercise the rebuild scanner we must use STORED entries so the
  ;; LFH carries the real compressed-size.
  (let [path (let [tmp (doto (File/createTempFile "rebuild-" ".zip")
                         (.deleteOnExit))]
               (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
                 (doseq [[name content] {"a.txt" "alpha"
                                         "b.txt" "bravo bravo"
                                         "c.txt" "charlie!"}]
                   (let [bytes (.getBytes ^String content "UTF-8")
                         crc   (doto (java.util.zip.CRC32.)
                                 (.update bytes))
                         entry (doto (ZipEntry. ^String name)
                                 (.setMethod ZipEntry/STORED)
                                 (.setSize (alength bytes))
                                 (.setCompressedSize (alength bytes))
                                 (.setCrc (.getValue crc)))]
                     (.putNextEntry out entry)
                     (.write out bytes)
                     (.closeEntry out))))
               (.getAbsolutePath tmp))
        m-before (zm/zip-meta path)
        cdr-off  (get-in m-before [:end-of-cdr-record :record :cdr-offset-from-start-disk])]
    ;; Truncate so the CDR + EOCDR are gone.
    (truncate! path cdr-off)
    (is (thrown? clojure.lang.ExceptionInfo (zm/zip-meta path)))
    ;; Rebuild and re-validate.
    (zm/rebuild-central-directory! path)
    (let [m-after (zm/zip-meta path)
          v       (zm/validate-zip-meta path)]
      (is (true? (:valid? v)))
      (is (= ["a.txt" "b.txt" "c.txt"]
             (map (comp :file-name :record) (:cdr-records m-after))))
      (is (= 0 (:extra-bytes m-after))))))

(deftest scan-handles-data-descriptor-entries
  ;; ZipOutputStream uses data descriptors for default DEFLATED entries.
  ;; scan-local-headers must locate the descriptor, validate its
  ;; recorded size against the actual data length, and patch the
  ;; missing crc-32 / compressed-size / uncompressed-size back into
  ;; the LFH record it returns.
  (let [path (write-test-zip {"a.txt" "alpha-content"
                              "b.txt" "bravo-content"})
        lfhs (zm/scan-local-headers path)]
    (is (= 2 (count lfhs)))
    ;; The on-disk LFH carries general-purpose bit 3 set and zero sizes;
    ;; after patching, the returned record has the real sizes.
    (doseq [{:keys [record]} lfhs]
      (is (pos? (bit-and (:general-purpose record) 0x8)))
      (is (pos? (:compressed-size record)))
      (is (pos? (:uncompressed-size record))))))

(deftest strip-preamble-shrinks-file-and-validates
  (let [tmp (copy-to-tmp bad-prelude-file "strip-")]
    (let [size-before (.length (jio/file tmp))]
      (zm/strip-preamble! tmp)
      (let [size-after (.length (jio/file tmp))
            v          (zm/validate-zip-meta tmp)]
        (is (= (- size-before 317) size-after))
        (is (true? (:valid? v)))
        (is (= 0 (:extra-bytes (zm/zip-meta tmp))))))))

(deftest strip-preamble-is-noop-on-clean-archive
  (let [tmp    (copy-to-tmp good-file "strip-noop-")
        before (slurp tmp)]
    (zm/strip-preamble! tmp)
    (is (= before (slurp tmp)))))

(deftest repair-zip-handles-preamble-drift
  (let [tmp (copy-to-tmp bad-prelude-file "repair-zip-")
        r   (zm/repair-zip tmp)]
    (is (= :ok (:status r)))
    (is (= [:preamble-fix] (:actions r)))
    (is (true? (:valid? (zm/validate-zip-meta tmp))))))

(deftest repair-zip-with-strip-preamble-option
  (let [tmp  (copy-to-tmp bad-prelude-file "repair-strip-")
        size (.length (jio/file tmp))
        r    (zm/repair-zip tmp {:strip-preamble true})]
    (is (= :ok (:status r)))
    (is (= [:preamble-stripped] (:actions r)))
    (is (= (- size 317) (.length (jio/file tmp))))))

(deftest repair-zip-rebuilds-truncated-archive
  ;; Build a STORED zip then chop the CDR / EOCDR off.
  (let [path (let [tmp (doto (File/createTempFile "repair-rebuild-" ".zip")
                         (.deleteOnExit))]
               (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
                 (doseq [[name content] {"hello.txt" "world"
                                         "foo/bar"   "baz"}]
                   (let [bytes (.getBytes ^String content "UTF-8")
                         crc   (doto (java.util.zip.CRC32.) (.update bytes))
                         entry (doto (ZipEntry. ^String name)
                                 (.setMethod ZipEntry/STORED)
                                 (.setSize (alength bytes))
                                 (.setCompressedSize (alength bytes))
                                 (.setCrc (.getValue crc)))]
                     (.putNextEntry out entry)
                     (.write out bytes)
                     (.closeEntry out))))
               (.getAbsolutePath tmp))
        cdr-off (get-in (zm/zip-meta path)
                        [:end-of-cdr-record :record :cdr-offset-from-start-disk])]
    (truncate! path cdr-off)
    (let [r (zm/repair-zip path)]
      (is (= :ok (:status r)))
      (is (= [:cdr-rebuilt] (:actions r)))
      (let [m (zm/zip-meta path)]
        (is (= 2 (count (:cdr-records m))))
        (is (= #{"hello.txt" "foo/bar"}
               (set (map (comp :file-name :record) (:cdr-records m)))))))))

(deftest repair-zip-on-clean-archive-is-noop
  (let [tmp (copy-to-tmp good-file "repair-noop-")
        r   (zm/repair-zip tmp)]
    (is (= :ok (:status r)))
    (is (= [:no-op] (:actions r)))))

(deftest rebuild-handles-deflated-archive-with-data-descriptors
  (let [path (write-test-zip {"alpha.txt" "the quick brown fox jumps over the lazy dog"
                              "beta.txt"  "the rain in spain stays mainly in the plain"})
        cdr-off (get-in (zm/zip-meta path)
                        [:end-of-cdr-record :record :cdr-offset-from-start-disk])]
    (truncate! path cdr-off)
    (zm/rebuild-central-directory! path)
    (let [m (zm/zip-meta path)
          v (zm/validate-zip-meta path)]
      (is (true? (:valid? v)))
      (is (= ["alpha.txt" "beta.txt"]
             (map (comp :file-name :record) (:cdr-records m))))
      ;; Sizes were patched from the data descriptor; they must be
      ;; reflected in the rebuilt CDR.
      (doseq [{:keys [record]} (:cdr-records m)]
        (is (pos? (:compressed-size record)))
        (is (pos? (:uncompressed-size record)))))))

(deftest repair-zip-preserves-zip-comment
  ;; Build a STORED archive with an archive comment, truncate, rebuild.
  (let [path  (let [tmp (doto (File/createTempFile "comment-rebuild-" ".zip")
                          (.deleteOnExit))]
                (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
                  (.setComment out "rebuilt-comment")
                  (let [bytes (.getBytes "data" "UTF-8")
                        crc   (doto (java.util.zip.CRC32.) (.update bytes))
                        entry (doto (ZipEntry. "x")
                                (.setMethod ZipEntry/STORED)
                                (.setSize (alength bytes))
                                (.setCompressedSize (alength bytes))
                                (.setCrc (.getValue crc)))]
                    (.putNextEntry out entry)
                    (.write out bytes)
                    (.closeEntry out)))
                (.getAbsolutePath tmp))
        cdr-off (get-in (zm/zip-meta path)
                        [:end-of-cdr-record :record :cdr-offset-from-start-disk])]
    (truncate! path cdr-off)
    (zm/rebuild-central-directory! path {:zip-comment "new-comment"})
    (is (= "new-comment" (zm/zip-comment path)))))
