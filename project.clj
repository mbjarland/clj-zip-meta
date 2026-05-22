(defproject clj-zip-meta/clj-zip-meta "0.2.0"
  :description "A Clojure library for reading and patching the binary
                metadata of zip and jar files (local file headers, central
                directory headers, end-of-central-directory record)."
  :url "https://github.com/mbjarland/clj-zip-meta"
  :license {:name         "Eclipse Public License 1.0"
            :url          "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :scm {:name "git" :url "https://github.com/mbjarland/clj-zip-meta"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [funcool/octet "1.1.2"]]

  :source-paths   ["src/main"]
  :resource-paths ["src/main/resources"]
  :test-paths     ["src/test"]
  :main           clj-zip-meta.cli

  :profiles {:dev      {:resource-paths ["src/test/resources"]
                        :global-vars    {*warn-on-reflection* true}}
             :provided {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :1.10     {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.11     {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :1.12     {:dependencies [[org.clojure/clojure "1.12.0"]]}}

  :aliases {"test-all" ["with-profile" "+1.10:+1.11:+1.12" "test"]}

  :deploy-repositories [["clojars" {:url           "https://repo.clojars.org"
                                    :sign-releases false}]])
