(ns jsx-to-cljs.cmd
  (:require [cljs.nodejs :as nodejs]
            [print.foo :as pf :include-macros true]
            [jsx-to-cljs.core :as jsx-to-cljs]
            [cljs.pprint :refer [pprint]]
            [jsx-to-cljs.utils :as u]
            [clojure.string :as s]))

(nodejs/enable-util-print!)

(def program (js/require "commander"))
(def argv (aget nodejs/process "argv"))

(.. program
    (version "0.1.0")
    (description "Converts JSX string into selected Clojurescript React library format")
    (usage "[options] <string>")
    (option "-t --target [target]" "Target library (om/reagent/rum). Default om" #"(om|reagent|rum)$" "om")
    (option "--ns [string]" "Namespace for compoments. Default ui" "ui")
    (option "--dom-ns [ns]" "Namespace for DOM compoments. Default dom" "dom")
    (option "--lib-ns [ns]" "Target library ns. Default for Om: 'om'. Default for reagent & rum: 'r'")
    (option "--kebab-tags" "Convert tags to kebab-case?")
    (option "--kebab-attrs" "Convert attributes to kebab-case?")
    (option "--camel-styles" "Keep style keys as camelCase")
    (option "--remove-attr-vals" "Remove attribute values?")
    (option "--omit-empty-attrs" "Omit empty attributes?")
    (option "--styles-as-vector" "Keep multiple styles as vector instead of merge")
    (parse argv))

(defn default-lib-ns [opts]
  (update opts :lib-ns (fn [lib-ns]
                         (if-not lib-ns
                           (condp = (:target opts)
                             "om" "om"
                             "reagent" "r"
                             "rum" "r"
                             nil)
                           (if (s/blank? lib-ns) nil lib-ns)))))

(defn -main [& _]
  (let [jsx-str (-> (aget program "args") js->clj first)
        opts (-> (.opts program)
                 (js->clj :keywordize-keys true)
                 u/kebabize-keys
                 default-lib-ns)]
    (if jsx-str
      (pprint (jsx-to-cljs/transform-jsx (:target opts) jsx-str opts))
      (.outputHelp program))))

(set! *main-cli-fn* -main)