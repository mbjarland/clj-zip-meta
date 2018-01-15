(ns clj-zip-meta.core-test
  (:require [midje.sweet :refer :all]
            [midje.repl :as m]
            [clj-zip-meta.core :refer :all]))


(def good-file "src/test/resources/good.zip")
(def bad-prelude-file "src/test/resources/bad_prelude.zip")

(facts "should parse a valid zip file"
       (let [meta (zip-meta good-file)]
         (:extra-bytes meta) => 0))


(facts "should parse a prelude zip file"
       (let [meta (zip-meta bad-prelude-file)]
         (:extra-bytes meta) => 317))

(facts "should parse good zip to valid signatures"
       (let [meta (zip-meta good-file)]
         (get-in meta [:end-of-cdr-record :record :end-of-cdr-signature]) => 101010256
         (get-in meta [:cdr-records 0 :record :cdr-header-signature]) => 33639248
         (get-in meta [:local-records 0 :record :local-header-signature]) => 67324752
         ))

(facts "should parse good zip file to valid meta data"
       (let [meta (zip-meta good-file)]
         (:extra-bytes meta) => 0
         (get-in meta [:end-of-cdr-record :offset]) => 563

         ; the offset in the eo-cdr should match
         (get-in meta [:end-of-cdr-record :record :cdr-offset-from-start-disk]) => 309
         (get-in meta [:cdr-records 0 :offset]) => 309

         (get-in meta [:end-of-cdr-record :record :cdr-entries-this-disk]) => 3
         (get-in meta [:end-of-cdr-record :record :cdr-entries-total]) => 3

         (count (:cdr-records meta)) => 3
         (count (:local-records meta)) => 3

         ))

;; TODO: more tests 