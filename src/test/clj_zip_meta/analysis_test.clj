(ns clj-zip-meta.analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as jio]
            [clj-zip-meta.core :as zm]
            [clj-zip-meta.analysis :as za])
  (:import (java.io ByteArrayOutputStream File FileOutputStream
                    RandomAccessFile)
           (java.util.zip CRC32 ZipEntry ZipOutputStream)))

;; ---------------------------------------------------------------------------
;; fixtures

(def good-file "src/test/resources/good.zip")
(def bad-prelude-file "src/test/resources/bad_prelude.zip")

(defn- write-zip!
  "Write a STORED-method zip with each (name -> content-string)
  entry. Returns the absolute path to a temp file marked
  deleteOnExit."
  ^String [entries]
  (let [tmp (doto (File/createTempFile "analysis-" ".zip")
              (.deleteOnExit))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (doseq [[name content] entries]
        (let [bytes (.getBytes ^String (str content) "UTF-8")
              crc   (doto (CRC32.) (.update bytes))
              entry (doto (ZipEntry. ^String name)
                      (.setMethod ZipEntry/STORED)
                      (.setSize (alength bytes))
                      (.setCompressedSize (alength bytes))
                      (.setCrc (.getValue crc)))]
          (.putNextEntry out entry)
          (.write out bytes)
          (.closeEntry out))))
    (.getAbsolutePath tmp)))

;; ---------------------------------------------------------------------------
;; unsafe-entries

(deftest unsafe-entries-path-traversal
  (let [path (write-zip! {"safe.txt"        "ok"
                          "../escape.txt"   "bad"
                          "a/../b/c.txt"    "also bad"})]
    (let [bad (za/unsafe-entries path)
          by-name (into {} (map (juxt (comp :file-name :entry) :reasons)) bad)]
      (is (= 2 (count bad)))
      (is (contains? (get by-name "../escape.txt") :path-traversal))
      (is (contains? (get by-name "a/../b/c.txt") :path-traversal)))))

(deftest unsafe-entries-clean-archive-empty
  (is (= [] (za/unsafe-entries good-file))))

(deftest unsafe-entries-windows-reserved
  (let [path (write-zip! {"safe.txt"  "ok"
                          "CON.txt"   "bad"
                          "nul"       "also bad"})]
    (let [reasons-for (fn [nm]
                       (some #(when (= nm (:file-name (:entry %)))
                                (:reasons %))
                             (za/unsafe-entries path)))]
      (is (contains? (reasons-for "CON.txt") :windows-reserved-name))
      (is (contains? (reasons-for "nul")     :windows-reserved-name)))))

;; ---------------------------------------------------------------------------
;; zip-bomb-risk

(deftest zip-bomb-risk-flags-extreme-ratios
  ;; A 10000-byte stream of zeros deflates to ~30 bytes — a ~333:1
  ;; ratio. Below the default 1000 threshold but easy to flag at 100.
  (let [tmp (doto (File/createTempFile "bomb-" ".zip") (.deleteOnExit))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (.putNextEntry out (ZipEntry. "all-zeros"))
      (.write out (byte-array 100000))
      (.closeEntry out))
    (let [path  (.getAbsolutePath tmp)
          risks (za/zip-bomb-risk path 100)]
      (is (seq risks))
      (is (> (-> risks first :compression-ratio) 100)))))

(deftest zip-bomb-risk-clean-archive-empty
  (is (= [] (za/zip-bomb-risk good-file))))

;; ---------------------------------------------------------------------------
;; gap-data

(deftest gap-data-clean-archive-empty
  (is (= [] (za/gap-data good-file))))

(deftest gap-data-finds-extra-bytes
  ;; Append junk after the EOCDR. A real archive ends with the EOCDR
  ;; record, so trailing bytes ought to surface as a gap.
  (let [src   (write-zip! {"a" "alpha" "b" "bravo"})
        path  (.getAbsolutePath
                (doto (File/createTempFile "gap-" ".zip")
                  (.deleteOnExit)))
        _     (jio/copy (jio/file src) (jio/file path))
        _     (with-open [r (RandomAccessFile. path "rw")]
                (.seek r (.length r))
                (.writeBytes r "EXTRA STUFF HIDDEN HERE"))
        gaps  (za/gap-data path)]
    (is (= 1 (count gaps)))
    (is (= 23 (long (:length (first gaps)))))))

;; ---------------------------------------------------------------------------
;; cdr-lfh-mismatches

(deftest cdr-lfh-mismatches-clean-archive-empty
  (is (= [] (za/cdr-lfh-mismatches good-file))))

(deftest cdr-lfh-mismatches-detects-tampering
  ;; Build a normal STORED archive, then overwrite the CRC in the
  ;; first CDR record so the CDR no longer agrees with the LFH.
  (let [path (write-zip! {"a" "alpha"})
        m    (zm/zip-meta path)
        cdr0 (first (:cdr-records m))
        cdr-off (:offset cdr0)
        ;; The CRC field sits at +16 inside a CDR record.
        crc-pos (+ (long cdr-off) 16)]
    (with-open [r (RandomAccessFile. path "rw")]
      (.seek r crc-pos)
      (.writeInt r 0))
    (let [mism (za/cdr-lfh-mismatches path)]
      (is (= 1 (count mism)))
      (is (= "a" (:file-name (first mism))))
      (is (contains? (:differences (first mism)) :crc-32)))))

;; ---------------------------------------------------------------------------
;; analyze

(deftest analyze-marks-clean-archive-safe
  (let [r (za/analyze good-file)]
    (is (true? (:safe? r)))
    (is (= 3 (:entry-count r)))))

(deftest analyze-flags-unsafe-archive
  (let [path (write-zip! {"safe.txt"      "ok"
                          "../escape.txt" "bad"})
        r    (za/analyze path)]
    (is (false? (:safe? r)))
    (is (= 1 (count (:unsafe-entries r))))))

;; ---------------------------------------------------------------------------
;; zip64?

(deftest zip64-false-for-normal-archive
  (is (false? (za/zip64? good-file))))
