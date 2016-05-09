(require
  '[cljs.build.api :as b]
  '[cljs.repl :as repl]
  '[cljs.repl.browser :as browser])

(b/build "src"
  {:main 'jsx-to-cljs.core
   :output-to "out/jsx_to_cljs.js"
   :output-dir "out"
   :verbose true})

(repl/repl (browser/repl-env :port 9000)
  :output-dir "out")
