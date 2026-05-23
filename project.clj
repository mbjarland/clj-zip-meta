(defproject clj-zip-meta/clj-zip-meta "0.4.0"
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
             :1.12     {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :bench    {:source-paths ["bench"]
                        :dependencies [[criterium/criterium "0.4.6"]]
                        :main         clj-zip-meta.bench}}

  :aliases {"test-all" ["with-profile" "+1.10:+1.11:+1.12" "test"]
            "bench"    ["with-profile" "+bench" "run"]}

  ;; Deploy credentials are read from the CLOJARS_USERNAME and
  ;; CLOJARS_PASSWORD environment variables — the CI release workflow
  ;; sets these from GitHub Secrets. Locally, drop them into
  ;; ~/.lein/credentials.clj.gpg instead.
  :deploy-repositories [["clojars" {:url           "https://repo.clojars.org"
                                    :sign-releases false
                                    :username      :env/clojars_username
                                    :password      :env/clojars_password}]])
