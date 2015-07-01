(defproject net.unit8/back-channeling "0.1.0-SNAPSHOT"
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [hiccup "1.0.5"]
                 [garden "1.2.5"]
                 [compojure "1.3.4"]
                 [environ "1.0.0"]
                 [org.clojure/clojurescript "0.0-3165"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.3.4"]
                 [prismatic/om-tools "0.3.11"]
                 [bouncer "0.3.2"]
                 [secretary "1.2.2"]
                 [org.omcljs/om "0.8.8"]
                 [io.undertow/undertow-websockets-jsr "1.1.1.Final"]

                 [com.datomic/datomic-free "0.9.5130" :exclusions [org.slf4j/slf4j-api org.slf4j/slf4j-nop joda-time]]
                 [ch.qos.logback/logback-classic "1.1.3"]
                 [datomic-schema "1.3.0"]
                 [liberator "0.13"]

                 [ring/ring-defaults "0.1.4"]
                 [ring "1.3.2"]]
  
    :plugins [[lein-ring "0.9.3"]
              [lein-cljsbuild "1.0.5"]
              [lein-environ "1.0.0"]]
    
    :main back-channeling.core

    :profiles {:dev {:env {:dev true}}}
    :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/extern/back-channeling.js"
                           :pretty-print true
                           :optimizations :simple}}
               {:id "production"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/extern/back-channeling.min.js"
                           :output-dir "resources/public/js/extern"
                           :pretty-print true
                           :optimizations :advanced
                           :source-map "resources/public/js/extern/back-channeling.min.js.map"}}]})
