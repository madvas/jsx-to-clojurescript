(require '[cljs.build.api :as b])

(b/watch "src"
  {:main 'jsx-to-cljs.core
   :output-to "out/jsx_to_cljs.js"
   :output-dir "out"})
