(ns clj-zip-meta.core-test
  (:require [midje.sweet :refer :all]
            [midje.repl :as m]
            [clj-zip-meta.core :refer :all]))


(def good-file "src/test/resources/good.zip")
(def bad-prelude-file "src/test/resources/bad_prelude.zip")

; example negative test
; (fact "Should throw exception on empty layout string"
;       (parse-layout-string "") => (throws AssertionError))

(facts "should parse a valid zip file"
       (let [meta (get-zip-meta good-file)]
         (:extra-bytes meta) => 0))


(facts "should parse a prelude zip file"
       (let [meta (get-zip-meta bad-prelude-file)]
         (:extra-bytes meta) => 317))

(comment
  (tabular
    (fact "long-date->ymd should parse valid dates"
          (long-date->ymd ?date) => ?ymd)
    ?date ?ymd
    20160101 [2016 1 1]
    20161111 [2016 11 11]
    20161231 [2016 12 31]
    20161111 [2016 11 11]
    20100101 [2010 1 1])

  (tabular
    (fact "date->ymd-str should parse valid dates"
          (long-date->ymd-str ?date) => ?ymd-str)
    ?date ?ymd-str
    20160101 ["2016" "01" "01"]
    20161111 ["2016" "11" "11"]
    20161231 ["2016" "12" "31"]
    20161111 ["2016" "11" "11"]
    20100101 ["2010" "01" "01"])

  (tabular
    (fact "str-date->long-date should parse valid dates"
          (str-date->long-date ?str-date) => ?date)
    ?str-date ?date
    "20160101" 20160101
    "20161111" 20161111
    "20161231" 20161231
    "20161111" 20161111
    "20100101" 20100101)

  (tabular
    (fact "long-date->local-date should work for valid dates"
          (long-date->local-date ?date) => ?local-date)
    ?date ?local-date
    20160101 (local-date 2016 01 01)
    20161111 (local-date 2016 11 11)
    20161231 (local-date 2016 12 31)
    20161111 (local-date 2016 11 11)
    20100101 (local-date 2010 01 01))


  (tabular
    (fact "local-date->long-date should work for valid dates"
          (local-date->long-date ?local-date) => ?date)
    ?local-date ?date
    (local-date 2016 01 01) 20160101
    (local-date 2016 11 11) 20161111
    (local-date 2016 12 31) 20161231
    (local-date 2016 11 11) 20161111
    (local-date 2010 01 01) 20100101)

  (tabular
    (fact "next-day should work for valid dates"
          (next-day ?date) => ?expected-date)
    ?date ?expected-date
    20160101 20160102
    20160228 20160229                                       ; leap year
    20161111 20161112
    20161231 20170101)

  (tabular
    (fact "prev-day should work for valid dates"
          (prev-day ?date) => ?expected-date)
    ?date ?expected-date
    20160101 20151231
    20160229 20160228
    20161111 20161110
    20161231 20161230)

  (fact "day-seq should produce a valid date sequence for leap years"
        (take 5 (day-seq 20160227)) => [20160227
                                        20160228
                                        20160229
                                        20160301
                                        20160302])

  (fact "day-seq should produce a valid date sequence across year boundaries"
        (take 5 (day-seq 20161228)) => [20161228
                                        20161229
                                        20161230
                                        20161231
                                        20170101])
  )