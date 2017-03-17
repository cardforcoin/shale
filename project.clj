(defproject shale "0.3.3-SNAPSHOT"
  :description "A Clojure-backed Selenium hub replacement"
  :url "https://github.com/cardforcoin/shale"
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"}
  :dependencies [[amazonica "0.3.84"]
                 [camel-snake-kebab "0.4.0" :exclusions [org.clojure/clojure]]
                 [cheshire "5.6.3"]
                 [clj-http "2.3.0"]
                 [arohner/clj-kube "0.1.3"]
                 [clj-wallhack "1.0.1"]
                 [clj-webdriver "0.6.1" :exclusions [org.clojure/core.cache]]
                 [cljs-ajax "0.5.3"]
                 [com.amazonaws/aws-java-sdk-core "1.11.77"]
                 [com.amazonaws/aws-java-sdk-ec2 "1.11.77"]
                 [com.brweber2/clj-dns "0.0.2"]
                 [com.cemerick/url "0.1.1"]
                 [com.fzakaria/slf4j-timbre "0.3.4"]
                 [com.stuartsierra/component "0.2.3"]
                 [com.taoensso/carmine "2.15.1" :exclusions [org.clojure/clojure com.taoensso/encore]]
                 [com.taoensso/encore "2.87.0" :exclusions [org.clojure/clojure]]
                 [com.taoensso/timbre "4.8.0"]
                 [compojure "1.4.0"]
                 [environ "1.0.0"]
                 [hiccup "1.0.5"]
                 [io.aviso/pretty "0.1.19"]
                 [javax.servlet/servlet-api "2.5"]
                 [liberator "0.14.0"]
                 [org.bovinegenius/exploding-fish "0.3.4"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228" :scope "provided"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [org.clojure/tools.reader "1.0.0-beta4"]
                 [org.slf4j/log4j-over-slf4j "1.7.14"]
                 [org.slf4j/jul-to-slf4j "1.7.14"]
                 [org.slf4j/jcl-over-slf4j "1.7.14"]
                 [org.danielsz/system "0.2.0"]
                 [overtone/at-at "1.2.0"]
                 [prismatic/schema "0.3.0"]
                 [raven-clj "1.5.0"]
                 [reagent "0.5.1" :exclusions [org.clojure/tools.reader]]
                 [reagent-forms "0.5.21"]
                 [reagent-utils "0.1.7"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [secretary "1.2.3"]
                 [slingshot "0.12.2"]
                 [sonian/carica "1.1.0" :exclusions [cheshire]]
                 [venantius/accountant "0.1.7" :exclusions [org.clojure/tools.reader]]
                 [com.google.protobuf/protobuf-java "2.6.1"]
                 [riemann "0.2.12" :exclusions [org.jsoup/jsoup io.netty/netty]]]
  :exclusions [cheshire]
  :scm {:name "git"
        :url "http://github.com/cardforcoin/shale"}
  :auto-clean false
  :main shale.core
  :plugins [[lein-environ "1.0.0"]
            [lein-cljsbuild "1.1.3"]]
  :test-selectors {:default (complement :integration)
                   :integration :integration
                   :all (constantly true)}

  :clean-targets ^{:protect false} [:target-path
                                    [:cljsbuild :builds :app :compiler :output-dir]
                                    [:cljsbuild :builds :app :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc"]
  :resource-paths ["resources" "target/cljsbuild"]

  :minify-assets
  {:assets
   {"resources/public/css/site.min.css" "resources/public/css/site.css"}}

  :cljsbuild {:builds {:app {:source-paths ["src/cljs"] ; "src/cljc"
                             :compiler {:output-to "target/cljsbuild/public/js/app.js"
                                        :output-dir "target/cljsbuild/public/js/out"
                                        :asset-path   "/js/out"
                                        :optimizations :none
                                        :pretty-print  true}}}}

  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.0"]
                                  [ring/ring-devel "1.4.0"]
                                  [prone "1.0.2"]
                                  [org.clojure/tools.trace "0.7.8"]
                                  [lein-figwheel "0.5.0-6"
                                   :exclusions [org.clojure/core.memoize
                                                ring/ring-core
                                                org.clojure/clojure
                                                org.ow2.asm/asm-all
                                                org.clojure/data.priority-map
                                                org.clojure/tools.reader
                                                org.clojure/clojurescript
                                                org.clojure/core.async
                                                org.clojure/tools.analyzer.jvm]]
                                  [org.clojure/tools.nrepl "0.2.12"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [pjstadig/humane-test-output "0.7.1"]]

                   :source-paths ["env/dev/clj"]
                   :plugins [[lein-figwheel "0.5.0-6"
                              :exclusions [org.clojure/core.memoize
                                           ring/ring-core
                                           org.clojure/clojure
                                           org.ow2.asm/asm-all
                                           org.clojure/data.priority-map
                                           org.clojure/tools.reader
                                           org.clojure/clojurescript
                                           org.clojure/core.async
                                           org.clojure/tools.analyzer.jvm]]]

                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]

                   :figwheel {:http-server-root "public"
                              :server-port 3449
                              :nrepl-port 7002
                              :nrepl-middleware ["cemerick.piggieback/wrap-cljs-repl"]
                              :css-dirs ["resources/public/css"]}

                   :env {:dev true}

                   :cljsbuild {:builds {:app {:source-paths ["env/dev/cljs"]
                                              :compiler {:main "shale.dev"
                                                         :source-map true}}}}}

             :uberjar {:hooks [minify-assets.plugin/hooks]
                       :source-paths ["env/prod/clj"]
                       :prep-tasks ["compile" ["cljsbuild" "once"]]
                       :env {:production true}
                       :aot :all
                       :omit-source true
                       :cljsbuild {:jar true
                                   :builds {:app
                                            {:source-paths ["env/prod/cljs"]
                                             :compiler
                                             {:optimizations :advanced
                                              :pretty-print false}}}}}}

  :aot [#"shale\.ext\.*" #"shale\.core"])
