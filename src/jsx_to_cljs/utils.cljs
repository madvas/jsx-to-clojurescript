(ns jsx-to-cljs.utils
  (:require [clojure.string :as s]
            [print.foo :as pf :include-macros true]
            [cognitect.transit :as t]
            [clojure.walk :as w]
            [camel-snake-kebab.core :as cs]))

(defn keyword->symbol [kw]
  (symbol (namespace kw) (name kw)))

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

(defn numeric-str? [str]
  (and (string? str)
       (re-matches #"^[0-9\.]+$" str)))

(declare dom-tags)

(defn dom-tag? [tag]
  (contains? dom-tags (name tag)))

(defn join-classes [class-str]
  (s/replace (str " " class-str) #" " "."))

(def kebabize-keys (partial map-keys cs/->kebab-case))
(def remove-attributes-values (partial map-vals (constantly nil)))
(def parse-numeric-attributes (partial map-vals #(apply-if numeric-str? js/parseFloat %)))

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
            "!" "not"
            "=" "set!"
            js-operator)))

(defn non-empty-str? [str]
  (and (string? str) (not (s/blank? str))))

(defn symbol-or-list? [x]
  (or (symbol? x) (list? x)))

(defn map-call? [callee args]
  (and (list? callee)
       (= (first callee) :map)
       (symbol-or-list? (second callee))
       (or (symbol? (first args))
           (= (ffirst args) 'fn))))

(defn strip-init-underscore [x]
  (s/replace (name x) #"^_" ""))

(defn clojurize-map-call [[_ data f]]
  `(~'map ~f ~data))

(defn filter-remove [pred coll]
  (let [res (group-by pred coll)]
    [(get res true) (get res false)]))

(defn has-node-type? [type x]
  (= (:type (meta x)) type))

(defn ensure-vec [x]
  (if (vector? x) x [x]))

(def let-form? (partial has-node-type? :let-form))
(def do-form? (partial has-node-type? :do-form))

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

