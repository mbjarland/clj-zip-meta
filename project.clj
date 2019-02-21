(defproject clj-zip-meta/clj-zip-meta "0.1.3"
  :description "a library for reading meta data from zip/jar files"
  :url "https://github.com/mbjarland/clj-zip-meta"
  :license {:name         "Some Eclipse Public License"
            :url          "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments     "same as clojure"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [funcool/octet "1.1.2"]]
  :source-paths ["src/main"]
  :resource-paths ["src/main/resources"]
  :test-paths ["src/test"]
  :dev {:resource-paths ["src/test/resources"]}
  :profiles {:dev {:dependencies [[midje "1.8.3"]]}}
  :plugins [[lein-midje "3.2.1"]])

