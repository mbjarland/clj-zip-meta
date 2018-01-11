(defproject clj-zip-meta/clj-zip-meta "0.1.2"
  :description "a library for reading meta data from zip/jar files"
  :url "https://github.com/mbjarland/clj-zip-meta"
  :license {:name "Some Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as clojure"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [funcool/octet "1.1.0-SNAPSHOT"]]
  :source-paths ["src/main"]
  :resource-paths ["src/main/resources"]
  :test-paths ["src/test"]
  :dev {:resource-paths ["src/test/resources"]}

  :profiles {:dev {:dependencies [[midje "1.8.3"]]}}        ; unit testing framework]

  :plugins [[lein-midje "3.2.1"]]                           ; testing framework

  )

