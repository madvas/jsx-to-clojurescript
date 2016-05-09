(defproject jsx-to-cljs "0.1.0"
  :description "Library to convert JSX snippets to Om/Reagent or other Clojurescript-style format."
  :url "https://github.com/madvas/jsx-to-cljs"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [figwheel-sidecar "0.5.0-6"]
                 [com.cemerick/piggieback "0.2.1"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [print-foo-cljs "2.0.0"]
                 [funcool/tubax "0.2.0"]
                 [com.lucasbradstreet/instaparse-cljs "1.4.1.2"]
                 [com.cognitect/transit-cljs "0.8.237"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :plugins [[lein-npm "0.6.2"]
            [lein-cljsbuild "1.1.3"]
            [lein-figwheel "0.5.0-6"]]
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :npm {:dependencies [[source-map-support "0.4.0"]
                       [commander "2.9.0"]
                       [acorn-jsx "3.0.1"]]}
  :source-paths ["src" "target/classes"]
  :cljsbuild {:builds
              {:main {:notify-command ["node" "release/jsx_to_cljs.js"]
                      :compiler       {:main          jsx-to-cljs.cmd
                                       :output-to     "release/jsx_to_cljs.js",
                                       :output-dir    "release",
                                       :target        :nodejs,
                                       :optimizations :simple,
                                       :pretty-print  true,
                                       :verbose       false
                                       :source-map    "release/jsx_to_cljs.js.map"
                                       :externs       ["src/jsx_to_cljs/externs.js"]}
                      :source-paths   ["src"]}}}
  :clean-targets ["out" "release"]
  :target-path "target")
