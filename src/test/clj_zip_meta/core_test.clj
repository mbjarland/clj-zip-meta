(ns clj-zip-meta.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as jio]
            [clj-zip-meta.core :as zm]
            [clj-zip-meta.spec :as zspec])
  (:import (java.io ByteArrayOutputStream File FileOutputStream
                    RandomAccessFile)
           (java.nio.file Files StandardCopyOption)
           (java.util.zip CRC32 ZipEntry ZipOutputStream)))

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

(deftest zip-meta-can-skip-local-records
  (let [full (zm/zip-meta good-file)
        cdr  (zm/zip-meta good-file {:include-locals false})
        ;; Byte arrays inside the records (`:extra-field` and
        ;; `:extra-fields[*].data`) are distinct objects on each
        ;; read even when their contents match. Drop them before
        ;; comparing.
        scrub (fn [recs]
                (mapv #(update % :record dissoc :extra-field :extra-fields)
                      recs))]
    (is (contains? full :local-records))
    (is (not (contains? cdr :local-records)))
    (is (= (scrub (:cdr-records full)) (scrub (:cdr-records cdr))))
    (is (= (get-in full [:end-of-cdr-record :record :end-of-cdr-signature])
           (get-in cdr  [:end-of-cdr-record :record :end-of-cdr-signature])))
    (is (= (count (:cdr-records full)) (count (:cdr-records cdr))))))

(deftest set-zip-comment-round-trip
  (let [tmp (copy-to-tmp good-file "comment-")]
    (zm/set-zip-comment! tmp "hello, comment!")
    (is (= "hello, comment!" (zm/zip-comment tmp)))
    (is (true? (:valid? (zm/validate-zip-meta tmp))))))

(deftest round-trip-rewrite-preserves-everything
  ;; A no-op repair-zip-with-preamble-bytes (extra-bytes = 0) should
  ;; leave the file byte-identical, but more interestingly: a repair on
  ;; an archive with preamble bytes should still produce something that
  ;; zip-meta parses to a structurally identical CDR (same offsets and
  ;; primitive fields) after the round trip.
  (let [tmp     (copy-to-tmp bad-prelude-file "round-trip-")
        before  (zm/zip-meta tmp {:decode false})]
    (zm/repair-zip-with-preamble-bytes tmp)
    (let [after (zm/zip-meta tmp {:decode false})]
      (is (= 0 (:extra-bytes after)))
      (is (= (count (:cdr-records before))
             (count (:cdr-records after))))
      ;; Primitive fields preserved (the offsets bumped by extra-bytes).
      (let [extra 317]
        (doseq [[b a] (map vector (:cdr-records before) (:cdr-records after))]
          (let [br (:record b) ar (:record a)]
            (is (= (:file-name br)         (:file-name ar)))
            (is (= (:crc-32 br)            (:crc-32 ar)))
            (is (= (:compressed-size br)   (:compressed-size ar)))
            (is (= (:uncompressed-size br) (:uncompressed-size ar)))
            (is (= (+ extra (long (:relative-offset-local-header br)))
                   (:relative-offset-local-header ar)))))))))

(deftest verify-crcs-passes-on-intact-archive
  (let [results (zm/verify-crcs good-file)
        summary (zm/verify-crcs-summary good-file)]
    (is (every? #{:ok :empty} (map :status results)))
    (is (true? (:valid? summary)))
    (is (zero? (count (:mismatches summary))))))

(deftest cdr-records-have-decoded-convenience-keys
  (let [recs (->> (zm/zip-meta good-file) :cdr-records (mapv :record))
        dir  (first (filter :directory? recs))
        file (first (remove :directory? recs))]
    (testing "every record has the decode-augmented keys"
      (doseq [r recs]
        (is (some? (:last-modified r)))
        (is (set? (:dos-attributes r)))
        (is (contains? r :unix-mode))
        (is (contains? r :directory?))
        (is (contains? r :encrypted?))
        (is (contains? r :utf8-name?))
        (is (vector? (:extra-fields r)))))
    (testing "directory entry is recognised"
      (is (true? (:directory? dir)))
      (is (contains? (:dos-attributes dir) :directory)))
    (testing "unix-mode decoded as expected octal"
      (is (= 040755 (:unix-mode dir))) ; rwxr-xr-x directory
      (is (= 0100644 (:unix-mode file))))
    (testing "non-archive comment, non-encrypted, ASCII"
      (is (false? (:encrypted? file)))
      (is (false? (:utf8-name? file))))
    (testing "extended-timestamp extra-field is decoded"
      (let [ts (some #(when (= :extended-timestamp (:tag-name %)) %)
                     (:extra-fields file))]
        (is (some? ts))
        (is (integer? (-> ts :decoded :mtime)))))))

(deftest zip-meta-decode-false-skips-decoration
  (let [r (first (:cdr-records (zm/zip-meta good-file {:decode false})))]
    (is (not (contains? (:record r) :last-modified)))
    (is (not (contains? (:record r) :dos-attributes)))
    (is (not (contains? (:record r) :unix-mode)))
    (is (not (contains? (:record r) :extra-fields)))))

(deftest set-zip-comment-rejects-over-65535-bytes
  (let [tmp (copy-to-tmp good-file "comment-big-")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"65 535"
                          (zm/set-zip-comment! tmp (apply str (repeat 65536 "x")))))))

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

(deftest find-entry-by-name
  (let [path (write-test-zip {"alpha.txt" "a" "beta.txt" "b" "gamma.txt" "g"})]
    (is (= "beta.txt" (:file-name (zm/find-entry path "beta.txt"))))
    (is (nil? (zm/find-entry path "nope.txt")))))

(deftest zip-entries-match-filter
  (let [path (write-test-zip {"alpha.txt"   "a"
                              "beta.txt"    "b"
                              "gamma.json"  "g"
                              "delta.json"  "d"})]
    (testing "regex pattern"
      (is (= #{"alpha.txt" "beta.txt"}
             (->> (zm/zip-entries path {:match #"\.txt$"})
                  (map :file-name) set))))
    (testing "substring"
      (is (= #{"delta.json" "gamma.json"}
             (->> (zm/zip-entries path {:match "json"})
                  (map :file-name) set))))
    (testing "function predicate"
      (is (= #{"alpha.txt" "beta.txt" "gamma.json" "delta.json"}
             (->> (zm/zip-entries path {:match (constantly true)})
                  (map :file-name) set))))
    (testing "no match returns empty"
      (is (= [] (zm/zip-entries path {:match "nope"}))))
    (testing "nil match is the same as no option"
      (is (= 4 (count (zm/zip-entries path {:match nil})))))))

(deftest diff-detects-added-removed-changed
  (let [a    (write-test-zip {"keep.txt"   "alpha"
                              "drop.txt"   "this entry vanishes"
                              "change.txt" "before"})
        b    (write-test-zip {"keep.txt"   "alpha"
                              "added.txt"  "brand new"
                              "change.txt" "after differs"})
        d    (zm/diff a b)
        names (fn [k] (set (map :file-name (k d))))]
    (is (= #{"added.txt"} (names :added)))
    (is (= #{"drop.txt"}  (names :removed)))
    (is (= #{"change.txt"} (set (map :file-name (:changed d)))))
    (is (= 1 (:same d)))
    (let [c (first (:changed d))]
      (is (= "change.txt" (:file-name c)))
      (is (not= (:crc-32 (:before c)) (:crc-32 (:after c)))))))

(deftest update-cdr-entries-rewrites-each-record
  (let [tmp (copy-to-tmp good-file "update-")]
    (zm/update-cdr-entries! tmp
                            #(assoc % :external-file-attributes (bit-shift-left 0644 16)))
    (let [recs (->> (zm/zip-meta tmp) :cdr-records (mapv :record))]
      (is (every? #(= 0644 (:unix-mode %)) recs))
      (is (true? (:valid? (zm/validate-zip-meta tmp)))))))

(deftest update-cdr-entries-can-drop-entries
  (let [path (write-test-zip {"keep.txt" "k"
                              "drop.tmp" "d"
                              "also.tmp" "x"})]
    (zm/update-cdr-entries! path
                            (fn [r] (when-not (re-find #"\.tmp$" (:file-name r))
                                      r)))
    (let [recs (zm/zip-entries path)]
      (is (= ["keep.txt"] (map :file-name recs)))
      (is (true? (:valid? (zm/validate-zip-meta path)))))))

(deftest set-entry-comment-roundtrip
  (let [path (write-test-zip {"a.txt" "alpha" "b.txt" "beta"})]
    (zm/set-entry-comment! path "b.txt" "hand-written comment")
    (is (= "hand-written comment"
           (:file-comment (zm/find-entry path "b.txt"))))
    (is (true? (:valid? (zm/validate-zip-meta path))))))

(deftest set-entry-comment-rejects-missing-entry
  (let [path (write-test-zip {"a.txt" "alpha"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"no entry with that file-name"
                          (zm/set-entry-comment! path "nope.txt" "x")))))

(deftest zero-timestamps-clears-mod-time
  (let [path (write-test-zip {"a.txt" "alpha" "b.txt" "beta"})]
    (zm/zero-timestamps! path)
    (let [recs (->> (zm/zip-meta path) :cdr-records (map :record))]
      (doseq [r recs]
        (is (zero? (long (:last-mod-file-time r))))
        (is (zero? (long (:last-mod-file-date r))))))
    (is (true? (:valid? (zm/validate-zip-meta path))))))

(deftest extract-bytes-returns-uncompressed-content
  (let [path (write-test-zip {"hello.txt" "the quick brown fox"
                              "empty.txt" ""})]
    (testing "regular entry"
      (let [out (zm/extract-bytes path "hello.txt")]
        (is (= "the quick brown fox" (String. ^bytes out "UTF-8")))))
    (testing "empty entry"
      (is (= "" (String. ^bytes (zm/extract-bytes path "empty.txt") "UTF-8"))))
    (testing "missing entry returns nil"
      (is (nil? (zm/extract-bytes path "nope.txt"))))))

(deftest extract-string-utf8
  (let [path (write-test-zip {"greeting.txt" "héllo wörld ☃"})]
    (is (= "héllo wörld ☃" (zm/extract-string path "greeting.txt")))))

(deftest manifest-parses-jar-manifest
  ;; Build a tiny jar with a manifest and confirm we read it back.
  (let [tmp (doto (File/createTempFile "manifest-" ".jar") (.deleteOnExit))
        manifest-text
        (str "Manifest-Version: 1.0\r\n"
             "Created-By: clj-zip-meta tests\r\n"
             "Main-Class: my.App\r\n"
             "Long-Header: this is a very long header value that\r\n"
             " continues on the next line via the space-prefix\r\n"
             " convention used by the JAR manifest format\r\n"
             "\r\n")
        bytes (.getBytes manifest-text "UTF-8")
        crc   (doto (CRC32.) (.update bytes))
        entry (doto (ZipEntry. "META-INF/MANIFEST.MF")
                (.setMethod ZipEntry/STORED)
                (.setSize (alength bytes))
                (.setCompressedSize (alength bytes))
                (.setCrc (.getValue crc)))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (.putNextEntry out entry)
      (.write out bytes)
      (.closeEntry out))
    (let [m (zm/manifest (.getAbsolutePath tmp))]
      (is (= "1.0"               (get m "Manifest-Version")))
      (is (= "my.App"            (get m "Main-Class")))
      (is (= "clj-zip-meta tests" (get m "Created-By")))
      (is (re-find #"convention used" (get m "Long-Header"))))))

(deftest jar-info-distils-common-fields
  (let [tmp (doto (File/createTempFile "jar-info-" ".jar") (.deleteOnExit))
        manifest-text
        (str "Manifest-Version: 1.0\r\n"
             "Main-Class: com.example.App\r\n"
             "Implementation-Title: Example Library\r\n"
             "Implementation-Version: 2.4.6\r\n"
             "\r\n")
        bytes (.getBytes manifest-text "UTF-8")
        crc   (doto (CRC32.) (.update bytes))
        entry (doto (ZipEntry. "META-INF/MANIFEST.MF")
                (.setMethod ZipEntry/STORED)
                (.setSize (alength bytes))
                (.setCompressedSize (alength bytes))
                (.setCrc (.getValue crc)))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (.putNextEntry out entry)
      (.write out bytes)
      (.closeEntry out))
    (let [info (zm/jar-info (.getAbsolutePath tmp))]
      (is (= "com.example.App" (:main-class info)))
      (is (= "2.4.6"           (:implementation-version info)))
      (is (= "Example Library" (:implementation-title info)))
      (is (= "1.0"             (:manifest-version info))))))

(deftest jar-info-returns-nil-when-no-manifest
  (is (nil? (zm/jar-info good-file))))

(deftest class-index-groups-by-package
  ;; Build a jar with a few .class entries spread across packages.
  (let [tmp (doto (File/createTempFile "classes-" ".jar") (.deleteOnExit))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (doseq [name ["a/Foo.class" "a/Bar.class"
                    "a/b/Baz.class"
                    "Root.class"]]
        (let [bs (byte-array 4)
              crc (doto (CRC32.) (.update bs))
              entry (doto (ZipEntry. ^String name)
                      (.setMethod ZipEntry/STORED)
                      (.setSize 4)
                      (.setCompressedSize 4)
                      (.setCrc (.getValue crc)))]
          (.putNextEntry out entry)
          (.write out bs)
          (.closeEntry out))))
    (let [idx (zm/class-index (.getAbsolutePath tmp))]
      (is (= ["Bar" "Foo"] (get idx "a")))
      (is (= ["Baz"] (get idx "a.b")))
      (is (= ["Root"] (get idx ""))))))

(deftest pom-info-parses-maven-properties
  (let [tmp (doto (File/createTempFile "pom-" ".jar") (.deleteOnExit))
        props (str "#Generated by Maven\n"
                   "version=1.2.3\n"
                   "groupId=com.example\n"
                   "artifactId=widget\n")
        bs    (.getBytes props "UTF-8")
        crc   (doto (CRC32.) (.update bs))
        entry (doto (ZipEntry. "META-INF/maven/com.example/widget/pom.properties")
                (.setMethod ZipEntry/STORED)
                (.setSize (alength bs))
                (.setCompressedSize (alength bs))
                (.setCrc (.getValue crc)))]
    (with-open [out (ZipOutputStream. (FileOutputStream. tmp))]
      (.putNextEntry out entry)
      (.write out bs)
      (.closeEntry out))
    (let [info (zm/pom-info (.getAbsolutePath tmp))]
      (is (= "com.example" (:group-id info)))
      (is (= "widget"      (:artifact-id info)))
      (is (= "1.2.3"       (:version info))))))

(deftest describe-summarises-archive
  (let [d (zm/describe good-file)]
    (is (map? (:summary d)))
    (is (= 3 (-> d :summary :entry-count)))
    (is (zero? (:class-count d)))
    (is (= ["src/"] (:top-level-dirs d)))))

(deftest hexdump-renders-classic-layout
  (let [s (zm/hexdump good-file 0 32)]
    (is (re-find #"^00000000  50 4b" s))
    (is (re-find #"\|PK" s))
    ;; Two rows for 32 bytes, then a trailing newline.
    (is (= 2 (count (filter #(= % \newline) s))))))

(deftest verify-crcs-detects-tampering
  ;; Build a real zip, then flip a byte inside the compressed payload
  ;; of one entry. The CRC should no longer match.
  (let [path (write-test-zip {"hello.txt" "the quick brown fox jumps over the lazy dog"
                              "two.txt"   "two"})
        m    (zm/zip-meta path)
        ;; ZipOutputStream uses data descriptors, so the LFH carries
        ;; zero sizes. Use the CDR's authoritative :compressed-size
        ;; and pair it with the matching LFH offset from
        ;; :local-records.
        cdr-rec (first (filter #(pos? (long (-> % :record :compressed-size)))
                                (:cdr-records m)))
        cdr     (:record cdr-rec)
        lfh-rec (some #(when (= (-> % :record :file-name) (:file-name cdr)) %)
                       (:local-records m))
        lfh     (:record lfh-rec)
        data-off (+ (long (:offset lfh-rec))
                    30
                    (bit-and 0xFFFF (long (:file-name-length lfh)))
                    (bit-and 0xFFFF (long (:extra-field-length lfh))))]
    (with-open [r (RandomAccessFile. path "rw")]
      (.seek r data-off)
      (let [b (.readByte r)]
        (.seek r data-off)
        (.writeByte r (bit-xor (int b) 0xFF))))
    (let [results (zm/verify-crcs path)
          summary (zm/verify-crcs-summary path)]
      (is (false? (:valid? summary)))
      ;; Flipping a byte inside a deflate stream usually makes the
      ;; stream itself unparseable, which surfaces as :error from the
      ;; inflater. On the off chance the bit happens to land in a
      ;; non-load-bearing position the bytes still inflate but the
      ;; CRC will mismatch. Either outcome counts as detection.
      (is (some #(contains? #{:mismatch :error} (:status %)) results)))))

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
