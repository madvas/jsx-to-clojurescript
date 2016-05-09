(ns jsx-to-cljs.core
  (:require [print.foo :as pf :include-macros true]
            [tubax.core :as t]
            [tubax.helpers :as th]
            [clojure.string :as s]
            [clojure.walk :as w]
            [jsx-to-cljs.utils :as u]
            [instaparse.core :as insta]))

(defprotocol TargetLibrary
  (element [this node tag attrs dom-tag? opts])
  (attribute [this node name value opts])
  (new-component [this tag attrs content dom-tag? opts]
                 "How to return new component, which has no children components"))

(defrecord Om []
  TargetLibrary
  (new-component [_ tag attrs content dom-tag? {:keys [omit-empty-attrs]}]
    (cond-> `(~tag)
            (or (not omit-empty-attrs)
                (seq attrs)) (concat [attrs])
            true (concat content))))

(defrecord Reagent []
  TargetLibrary
  (new-component [_ tag attrs content dom-tag? {:keys [omit-empty-attrs]}]
    (into []
          (cond-> [(if dom-tag? (keyword (name tag)) tag)]
                  (or (not omit-empty-attrs)
                      (seq attrs)) (conj attrs)
                  true (concat content)))))

(defn conv-tree
  [tree target opts]
  (let [defaults {:ns               "ui"
                  :dom-ns           "dom"
                  :kebab-tags       true
                  :kebab-attrs      false
                  :remove-attr-vals false
                  :omit-empty-attrs true}
        opts (merge defaults opts)
        {:keys [ns dom-ns kebab-tags kebab-attrs remove-attr-vals]} opts]
    (w/postwalk
      (fn [x]
        (if (and (th/is-node x)
                 (not (u/has-children? x)))
          (let [{:keys [tag attributes content]} x
                dom-tag? (u/dom-tag? tag)
                ns (if dom-tag? dom-ns ns)]
            (new-component target
                           (cond-> tag
                                   kebab-tags u/camel->kebab
                                   (not (s/blank? ns)) (u/add-ns ns)
                                   true u/keyword->symbol)
                           (cond-> attributes
                                   kebab-attrs u/kebabize-keys
                                   remove-attr-vals u/remove-attributes-values
                                   true u/parse-numeric-attributes)
                           content dom-tag? opts))
          x)) tree)))

(def nested-reg
  "(?:\\{(?:\\{(?:\\{(?:\\{[^\\}]*\\}|[^\\}])*\\}|\\{[^\\}]*\\}|[^\\}])*\\}|\\{[^\\}]*\\}|[^\\}])*\\}|\\{[^\\}]*\\}|[^\\}])*"
  )

#_(defn conv-jsx
    ([target jsx-str] (conv-jsx target jsx-str {}))
    ([target jsx-str opts]
     (-> jsx-str
         (s/replace #"[\r\n]" " ")
         (s/replace #"\s\s+" " ")
         #_(s/replace #"(\s*\w*=\{)\s*(<.*>)\s*(\})" #(str (second %1)
                                                           (conv-jsx target (nth %1 2) opts)
                                                           (nth %1 3)))
         #_(s/replace #"(\s*\w*=\{)\s*(<(?:\{(?:\{[^\}]*\}|[^\}])*\}|\{[^\}]*\}|[^\}])*>)\s*(\})"
                      #(str (second %1)
                            (conv-jsx target (nth %1 2) opts)
                            (nth %1 3)))

         (s/replace (re-pattern (str "(\\s*\\w*=\\{)\\s*(<" nested-reg ">)\\s*(\\})"))
                    #(str (second (pf/look %1))
                          (conv-jsx target (nth %1 2) opts)
                          (nth %1 3)))

         (s/replace #"[\"\']" "&quot;")
         (s/replace (re-pattern (str "(\\s*\\w*=)(\\{(" nested-reg ")\\})")),
                    #(str (second (pf/look %1)) "\"" (last %1) "\""))

         ;(s/replace #"(\s*\w*=)&quot;(.*)&quot;", "$1\"$2\"")
         (s/replace (re-pattern (str "(\\s*\\w*=)&quot;(" nested-reg ")&quot;")),
                    #(str (second %1) "\"" (last %1) "\""))
         t/xml->clj
         (conv-tree target opts))))



(defn conv-jsx
  ([target jsx-str] (conv-jsx target jsx-str {}))
  ([target jsx-str opts]
   (-> jsx-str

       t/xml->clj
       (conv-tree target opts))))


(def jsx->om (partial conv-jsx (Om.)))
(def jsx->reagent (partial conv-jsx (Reagent.)))

(defmulti conv-target (fn [target-str & _] target-str))

(defmethod conv-target "om"
  [_ & args]
  (apply jsx->om args))

(defmethod conv-target "reagent"
  [_ & args]
  (apply jsx->reagent args))

(def t0 "<View style={styles.container}></View>")
#_(jsx->om t0)


(def t1 "<View style={styles.container}>\n        <TouchableWithoutFeedback onPressIn={this._onPressIn}\n                                  onPressOut={this._onPressOut}>\n          <Image source={{uri: imageUri}} style={imageStyle} />\n        </TouchableWithoutFeedback>\n      </View>")
(def t2 "<Table>\n    <TableHeader>\n      <TableRow>\n        <TableHeaderColumn value={this.state.firstSlider}>ID</TableHeaderColumn>\n        <TableHeaderColumn>Name</TableHeaderColumn>\n        <TableHeaderColumn>Status</TableHeaderColumn>\n      </TableRow>\n    </TableHeader>\n    <TableBody>\n      <TableRow>\n        <TableRowColumn>1</TableRowColumn>\n        <TableRowColumn>John Smith</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>2</TableRowColumn>\n        <TableRowColumn>Randal White</TableRowColumn>\n        <TableRowColumn>Unemployed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>3</TableRowColumn>\n        <TableRowColumn>Stephanie Sanders</TableRowColumn>\n        <TableRowColumn>Employed <b>Google Inc.</b></TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>4</TableRowColumn>\n        <TableRowColumn>Steve Brown</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n    </TableBody>\n  </Table>")
(def t3 "<div style={this.style}>\n        <Slider\n          defaultValue={0.5}\n          value={this.state.firstSlider}\n          onChange={this.handleFirstSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.firstSlider}</span>\n          <span>{' from a range of 0 to 1 inclusive'}</span>\n        </p>\n        <Slider\n          min={0}\n          max={100}\n          step={1}\n          defaultValue={50}\n          value={this.state.secondSlider}\n          onChange={this.handleSecondSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.secondSlider}</span>\n          <span>{' from a range of 0 to 100 inclusive'}</span>\n        </p>\n      </div>")
(def t4 "<AppBar\n    title={<span style={styles.title}>Title</span>}\n    onTitleTouchTap={handleTouchTap}\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={<FlatButton label=\"Save\" />}  />")
(def t4-5 "<AppBar\ntitle=\"Title\"\niconElementLeft={<IconButton><NavigationClose /></IconButton>}\niconElementRight={\n                  <IconMenu\n                            iconButtonElement={\n                                               <IconButton><MoreVertIcon /></IconButton>\n                                               }\n                            targetOrigin={{horizontal: 'right', vertical: 'top'}}\n                            anchorOrigin={{horizontal: 'right', vertical: 'top'}}>\n                  </IconMenu>\n                  }\n/>")
(def t5 "<AppBar\n    title=\"Title\"\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={\n      <IconMenu\n        iconButtonElement={\n          <IconButton><MoreVertIcon /></IconButton>\n        }\n        targetOrigin={{horizontal: 'right', vertical: 'top'}}\n        anchorOrigin={{horizontal: 'right', vertical: 'top'}}\n      >\n        <MenuItem primaryText=\"Refresh\" />\n        <MenuItem primaryText=\"Help\" />\n        <MenuItem primaryText=\"Sign out\" />\n      </IconMenu>\n    }\n  />")
(def t6 "<div>\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n        />\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n          floatingLabelText=\"Full width\"\n          fullWidth={true}\n        />\n      </div>")
(def t7 "<aa v={<dd>sop</dd>} z={ <bb y={ <cc x={st}>ABC</cc> }></bb> }></aa>")
(def t8 "<aa z={ <bb y={ <cc x={st}>ABC</cc> }></bb> }")
(def t9 "<Animated.Image                         \nsource={{uri: 'http://i.imgur.com/XMKOH81.jpg'}}\nstyle={{\n        flex: 1,\n        transform: [                        \n                    {scale: this.state.bounceValue},\n    ]\n        }}\n/>")

(comment
  (jsx->reagent t1)
  (jsx->om t2)
  (jsx->reagent t2)
  (jsx->om t3)
  (jsx->reagent t3)
  (jsx->om t4)
  (jsx->reagent t4)
  (jsx->om t4-5)
  (jsx->reagent t4-5)
  (jsx->reagent t5)
  (jsx->om t5)
  (jsx->om t6)
  (jsx->om t7)
  (jsx->om t8))