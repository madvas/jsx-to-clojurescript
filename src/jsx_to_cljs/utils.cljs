(ns jsx-to-cljs.utils
  (:require [tubax.helpers :as th]
            [clojure.string :as s]
            [print.foo :as pf :include-macros true]
            [cognitect.transit :as t]
            [clojure.walk :as w]))

(defn camel->kebab [x]
  (cond-> (s/join "-" (map s/lower-case (re-seq #"\w[a-z0-9]*" (name x))))
          (keyword? x) keyword
          (symbol? x) symbol))

(defn kebab->camel
  ([x] (kebab->camel x false))
  ([x capitalize?]
   (cond-> (name x)
           capitalize? s/capitalize
           true (s/replace #"-(\w)" (comp s/upper-case second))
           (keyword? x) keyword
           (symbol? x) symbol)))

(defn keyword->symbol [kw]
  (symbol (namespace kw) (name kw)))

(defn has-children? [{:keys [content]}]
  (some th/is-node content))

(defn add-ns [tag ns]
  (keyword ns (name tag)))

(defn map-vals [f m]
  (into {} (for [[k v] m] [k (f v)])))

(defn map-keys [f m]
  (into {} (for [[k v] m] [(f k) v])))

(defn apply-if [pred f x & args]
  (if (pred x)
    (apply f x args)
    x))

(defn apply-if-not [pred & args]
  (apply apply-if (complement pred) args))

(defn numeric-str? [str]
  (and (string? str)
       (re-matches #"^[0-9\.]+$" str)))

(declare dom-tags)

(defn dom-tag? [tag]
  (contains? dom-tags (name tag)))

(def kebabize-keys (partial map-keys camel->kebab))
(def remove-attributes-values (partial map-vals (constantly nil)))
(def parse-numeric-attributes (partial map-vals #(apply-if numeric-str? js/parseFloat %)))
(def nil-if-empty (partial apply-if-not seq (constantly nil)))

(def read-json (partial t/read (t/reader :json)))

(defn to-plain-object [jsobj]
  (-> jsobj
      js/JSON.stringify
      read-json
      w/keywordize-keys))

(defn update-first-in-list [l f]
  (-> (first l)
      f
      (cons (drop 1 l))))

(defn force-list [x]
  (if (list? x) x `(~x)))

(defn operator->symbol [js-operator]
  (symbol (condp = js-operator
            "&&" "and"
            "||" "or"
            "==" "="
            "===" "="
            js-operator)))

(def dom-tags
  #{"a"
    "abbr"
    "address"
    "area"
    "article"
    "aside"
    "audio"
    "b"
    "base"
    "bdi"
    "bdo"
    "big"
    "blockquote"
    "body"
    "br"
    "button"
    "canvas"
    "caption"
    "cite"
    "code"
    "col"
    "colgroup"
    "data"
    "datalist"
    "dd"
    "del"
    "details"
    "dfn"
    "dialog"
    "div"
    "dl"
    "dt"
    "em"
    "embed"
    "fieldset"
    "figcaption"
    "figure"
    "footer"
    "form"
    "h1"
    "h2"
    "h3"
    "h4"
    "h5"
    "h6"
    "head"
    "header"
    "hr"
    "html"
    "i"
    "iframe"
    "img"
    "ins"
    "kbd"
    "keygen"
    "label"
    "legend"
    "li"
    "link"
    "main"
    "map"
    "mark"
    "menu"
    "menuitem"
    "meta"
    "meter"
    "nav"
    "noscript"
    "object"
    "ol"
    "optgroup"
    "output"
    "p"
    "param"
    "picture"
    "pre"
    "progress"
    "q"
    "rp"
    "rt"
    "ruby"
    "s"
    "samp"
    "script"
    "section"
    "small"
    "source"
    "span"
    "strong"
    "style"
    "sub"
    "summary"
    "sup"
    "table"
    "tbody"
    "td"
    "tfoot"
    "th"
    "thead"
    "time"
    "title"
    "tr"
    "track"
    "u"
    "ul"
    "var"
    "video"
    "wbr"
    ;; svg
    "circle"
    "clipPath"
    "ellipse"
    "g"
    "line"
    "mask"
    "path"
    "pattern"
    "polyline"
    "rect"
    "svg"
    "text"
    "defs"
    "linearGradient"
    "polygon"
    "radialGradient"
    "stop"
    "tspan"
    "use"})

