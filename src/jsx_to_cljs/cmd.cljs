(ns jsx-to-cljs.cmd
  (:require [cljs.nodejs :as nodejs]
            [print.foo :as pf :include-macros true]
            [jsx-to-cljs.core :as jsx-to-cljs :refer [t1 t2 t3 t4 t5 t9]]
            [cljs.pprint :refer [pprint]]
            [jsx-to-cljs.utils :as u]))

(nodejs/enable-util-print!)

(def program (js/require "commander"))
(def argv (aget nodejs/process "argv"))



(.. program
    (version "0.0.1")
    (description "Converts JSX string into selected Clojurescript React library format")
    (usage "[options] <string>")
    (option "-t --target [target]" "Target library (om/reagent)" #"(om|reagent)$" "om")
    (option "--ns [string]" "Namespace to prepend compoments. Default ui" "ui")
    (option "--dom-ns [string]" "Namespace to prepend DOM compoments. Default dom" "dom")
    (option "--kebab-tags" "Convert tags to kebab-case?")
    (option "--kebab-attrs" "Convert attributes to kebab-case?")
    (option "--remove-attr-vals" "Remove attribute values?")
    (option "--omit-empty-attrs" "Omit empty attributes?")
    (parse argv))


(defn -main [& _]
  (let [jsx-str (-> (aget program "args") js->clj first)
        opts (u/kebabize-keys (js->clj (.opts program) :keywordize-keys true))]
    (if jsx-str
      (pprint (jsx-to-cljs/transform-jsx (:target opts) jsx-str opts))
      (.outputHelp program))))

(set! *main-cli-fn* -main)