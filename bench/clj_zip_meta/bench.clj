(ns clj-zip-meta.bench
  "Criterium benchmarks for the hot paths.

  Run with: clj -M:bench [PATH-TO-A-JAR]

  Defaults to whatever clojure-*.jar lives in the local Maven repo so
  results are reproducible without manual setup."
  (:require [clj-zip-meta.core :as zm]
            [criterium.core :as cc]
            [clojure.java.io :as jio]))

(defn- pick-jar
  "Find a reasonably sized jar to benchmark against. Tries an explicit
  CLI argument, then ~/.m2 for an org.clojure/clojure jar, then the
  tiny bundled good.zip as last resort."
  [args]
  (let [explicit (when-let [^String p (first args)]
                   (let [f (jio/file p)]
                     (when (.exists f) f)))
        clj-dir  (jio/file (System/getProperty "user.home")
                           ".m2/repository/org/clojure/clojure")
        clj-jar  (when (.isDirectory clj-dir)
                   (->> (file-seq clj-dir)
                        (filter #(re-find #"clojure-\d+(?:\.\d+)+\.jar$"
                                          (.getName ^java.io.File %)))
                        (sort-by #(.length ^java.io.File %))
                        last))
        fallback (jio/file "src/test/resources/good.zip")]
    (.getAbsolutePath ^java.io.File (or explicit clj-jar fallback))))

(defn -main [& args]
  (let [jar (pick-jar args)
        sz  (.length (jio/file jar))
        n   (count (:cdr-records (zm/zip-meta jar)))]
    (println (format "Target: %s (%.1f KiB, %d entries)%n" jar (/ sz 1024.0) n))

    (println "== zip-meta (full, decoded) ==")
    (cc/quick-bench (zm/zip-meta jar))

    (println "== zip-meta (cdr-only, decoded) ==")
    (cc/quick-bench (zm/zip-meta jar {:include-locals false}))

    (println "== zip-meta (cdr-only, raw) ==")
    (cc/quick-bench (zm/zip-meta jar {:include-locals false :decode false}))

    (println "== zip-entries ==")
    (cc/quick-bench (zm/zip-entries jar))

    (println "== zip-comment ==")
    (cc/quick-bench (zm/zip-comment jar))

    (println "== find-end-of-cdr-offset ==")
    (cc/quick-bench (zm/find-end-of-cdr-offset jar))

    (println "== verify-crcs ==")
    (cc/quick-bench (zm/verify-crcs jar))))
