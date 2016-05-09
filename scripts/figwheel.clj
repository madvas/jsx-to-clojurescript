
(do
  (require '[figwheel-sidecar.repl-api :as ra])
  (ra/start-figwheel!
    {:figwheel-options {}
     :build-ids        ["dev"]
     :all-builds       [{:id           "dev"
                         :figwheel     true
                         :source-paths ["src"]
                         :compiler     {:main           'jsx-to-cljs.core
                                        :output-to      "out/jsx_to_cljs.js"
                                        :output-dir     "out"
                                        :parallel-build true
                                        :compiler-stats true
                                        :verbose        true}}]})
  (ra/cljs-repl))



(do
  (require '[figwheel-sidecar.repl-api :as ra])
  (ra/start-figwheel!
    {:figwheel-options {}
     :build-ids        ["dev"]
     :all-builds       [{:id           "dev"
                         :figwheel     true
                         :source-paths ["src"]
                         :compiler     {:main           'jsx-to-cljs.cmd
                                        :output-to      "release/jsx_to_cljs.js"
                                        :output-dir     "release"
                                        :target         :nodejs,
                                        :optimizations  :none,
                                        :pretty-print   true,
                                        :parallel-build true
                                        :verbose        true}}]})
  (ra/cljs-repl))

(ra/stop-figwheel!)