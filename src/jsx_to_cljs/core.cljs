(ns jsx-to-cljs.core
  (:require [print.foo :as pf :include-macros true]
            [clojure.string :as s]
            [clojure.walk :as w]
            [jsx-to-cljs.utils :as u]
            [camel-snake-kebab.core :as cs]
            [com.rpl.specter :as sp]))

(def acorn (js/require "acorn-jsx"))

(defn ^:private move-attr->tag-suffix [[attrs tag-suffix] attr-name s]
  (if (u/non-empty-str? (get attrs attr-name))
    [(dissoc attrs attr-name) (str tag-suffix s)]
    [attrs tag-suffix]))

(defn ^:private replace-set-state-calls [attrs new-call]
  (w/postwalk (fn [x]
                (if (and (seq? x)
                         (symbol? (first x))
                         (= (-> (first x) name cs/->kebab-case) "set-state"))
                  (concat `(~new-call ~'this) (rest x))
                  x)) attrs))

(defprotocol TargetLibrary
  (element [this tag attrs children dom-tag? node opts]))

(defrecord Om []
  TargetLibrary
  (element [_ tag attrs children _ _ {:keys [omit-empty-attrs lib-ns]}]
    (cond-> `(~tag)
            (or (not omit-empty-attrs)
                (seq attrs)) (concat [(replace-set-state-calls attrs (symbol lib-ns 'set-state!))])
            true (concat children))))



(defrecord Reagent []
  TargetLibrary
  (element [_ tag attrs children dom-tag? _ {:keys [omit-empty-attrs kebab-attrs lib-ns]}]
    (let [id (get attrs :id)
          class-attr-name (if kebab-attrs :class-name :className)
          class-name (get attrs class-attr-name)
          [attrs tag-suffix] (-> [attrs ""]
                                 (move-attr->tag-suffix :id (str "#" id))
                                 (move-attr->tag-suffix class-attr-name (u/join-classes class-name)))
          tag (if dom-tag? (-> tag name (str tag-suffix) keyword)
                           tag)]
      (into [] (cond-> [tag]
                       (or (not omit-empty-attrs)
                           (seq attrs)) (conj (replace-set-state-calls attrs (symbol lib-ns 'set-state)))
                       true (concat children))))))

(defmulti ast-node (fn [type node target opts] type))

(defmethod ast-node :default
  [type _ _ _]
  (println "ERROR: Don't know how to handle node type " type))

(defmethod ast-node "Program" [_ node _ _] (:body node))
(defmethod ast-node "ExpressionStatement" [_ node _ _] (:expression node))
(defmethod ast-node "JSXIdentifier" [_ node _ _] (:name node))
(defmethod ast-node "Identifier" [_ node _ _] (-> node :name u/strip-init-underscore cs/->kebab-case-symbol))
(defmethod ast-node "JSXMemberExpression" [_ node _ _] (str (:object node) (:property node)))
(defmethod ast-node "JSXOpeningElement" [_ node _ _] node)
(defmethod ast-node "JSXClosingElement" [_ node _ _] (:name node))
(defmethod ast-node "JSXExpressionContainer" [_ node _ _] (:expression node))
(defmethod ast-node "ThisExpression" [_ _ _ _] (symbol "this"))
(defmethod ast-node "ObjectExpression" [_ node _ _] (into {} (:properties node)))
(defmethod ast-node "ArrayExpression" [_ node _ _] (:elements node))
(defmethod ast-node "Property" [_ node _ _] {(-> node :key cs/->kebab-case-keyword) (:value node)})
(defmethod ast-node "ReturnStatement" [_ node _ _] (:argument node))
(defmethod ast-node "JSXSpreadAttribute" [_ node _ _] (:argument node))
(defmethod ast-node "JSXEmptyExpression" [_ _ _ _] nil)
(defmethod ast-node "Literal" [_ node _ _] (:value node))


(defn ^:private create-let-form [declarations]
  (with-meta `(~'let ~(into [] (apply concat declarations)))
             {:type :let-form}))

(defn ^:private merge-let-forms [body]
  "Merges let statements in body and brings it to the top as first statement"
  (let [[declarations other] (u/filter-remove u/let-form? body)]
    (if declarations
      (-> [(reduce (fn [dcs dc]
                     (concat dcs (second dc))) [] declarations)]
          create-let-form
          (cons other))
      body)))

(defn ^:private remove-do [form]
  "Removes wrapping 'do' form. Returns contents as seq"
  (if (u/do-form? form)
    (rest form)
    (u/ensure-vec form)))

(defn ^:private remove-excess-do [form]
  "Removes wrapping 'do' form if it cointains single expression"
  (if (and (u/do-form? form)
           (= (count form) 2))
    (second form)
    form))

(defn ^:private create-fn-form [_ {:keys [params body]} _ _]
  (concat `(~'fn ~params) (remove-do body)))

(defmethod ast-node "BlockStatement" [_ {:keys [body]} _ _]
  (let [body (merge-let-forms body)
        first-exp (first body)]
    (if (u/let-form? first-exp)
      (concat first-exp (rest body))
      (with-meta (concat `(~'do) body)
                 {:type :do-form}))))

(defmethod ast-node "JSXAttribute" [_ {:keys [name value]} _ _]
  (let [value (if (and (= name "style")
                       (vector? value))
                (with-meta (concat '(merge) value)
                           {:type :styles-merge})
                value)]
    {(keyword name) value}))

(defmethod ast-node "NewExpression" [_ {:keys [callee arguments]} _ _]
  (concat (-> callee (str ".") cs/->PascalCase list)
          arguments))

(defmethod ast-node "CallExpression" [_ {:keys [callee arguments]} _ _]
  (-> callee
      u/force-list
      (u/update-first-in-list u/keyword->symbol)
      (u/update-first-in-list #(if (= % 'bind) 'partial %))
      (concat arguments)
      (cond->
        (u/map-call? callee arguments) u/clojurize-map-call)))

(defmethod ast-node "MemberExpression" [_ {:keys [property object] :as node} _ _]
  (if (= object 'this)
    (-> property cs/->kebab-case)
    `(~(cond-> property
               (not (seq? property)) cs/->kebab-case-keyword)
       ~object)))

(defmethod ast-node "ArrowFunctionExpression" [& args]
  (apply create-fn-form args))

(defmethod ast-node "FunctionExpression" [& args]
  (apply create-fn-form args))

(defmethod ast-node "LogicalExpression" [_ {:keys [operator left right]} _ _]
  (let [opr (u/operator->symbol operator)]
    (if (= opr 'and)
      `(~'when ~left ~right)
      `(~opr ~left ~right))))

(defmethod ast-node "BinaryExpression" [_ {:keys [operator left right]} _ _]
  `(~(u/operator->symbol operator) ~left ~right))

(defmethod ast-node "UnaryExpression" [_ {:keys [operator argument]} _ _]
  `(~(u/operator->symbol operator) ~argument))

(defmethod ast-node "ConditionalExpression" [_ {:keys [test consequent alternate]} _ _]
  `(~'if ~test ~consequent ~alternate))

(defmethod ast-node "IfStatement" [_ {:keys [test consequent alternate]} _ _]
  (if alternate
    `(~'if ~test ~(remove-excess-do consequent) ~(remove-excess-do alternate))
    (concat `(~'when ~test) (remove-do consequent))))

(defn create-attrs-merge [attrs spread-attrs]
  (concat `(~'merge) spread-attrs [attrs]))

(defmethod ast-node "AssignmentExpression" [_ {:keys [operator left right]} _ _]
  `(~(u/operator->symbol operator) ~left ~right))

(defmethod ast-node "VariableDeclaration" [_ {:keys [declarations]} _ _]
  (create-let-form declarations))

(defmethod ast-node "VariableDeclarator" [_ {:keys [id init]} _ _]
  `[~id ~init])

(defn ^:private camelize-styles [attrs]
  (let [styles (:style attrs)]
    (cond
      (map? styles) (assoc attrs :style (u/camelize-keys styles))
      (u/has-node-type? :styles-merge styles) (sp/transform [:style sp/ALL map?] u/camelize-keys attrs)
      :else attrs)))

(defmethod ast-node "JSXElement"
  [_ node target {:keys [ns dom-ns kebab-tags kebab-attrs remove-attr-vals camel-styles] :as opts}]
  (let [el (:openingElement node)
        tag-name (:name el)
        dom-tag? (u/dom-tag? tag-name)
        ns (if dom-tag? dom-ns ns)
        tag (cond-> (keyword tag-name)
                    kebab-tags cs/->kebab-case
                    (not (s/blank? ns)) (u/add-ns ns)
                    true u/keyword->symbol)
        all-attrs (:attributes el)
        spread-attrs (seq (filter (complement map?) all-attrs))
        attrs (cond-> (into {} (filter map? all-attrs))
                      kebab-attrs u/kebabize-keys
                      remove-attr-vals u/remove-attributes-values
                      camel-styles camelize-styles
                      true u/parse-numeric-attributes
                      spread-attrs (create-attrs-merge spread-attrs))
        children (remove s/blank? (:children node))]
    (element target tag attrs children dom-tag? node opts)))


(defn jsx->ast [jsx-str]
  (-> (.parse acorn jsx-str
              (clj->js {"plugins"
                        {"jsx" {"allowNamespacedObjects" true}}}))
      u/to-plain-object))

(defn jsx->target
  "Main function. Does the transformation"
  ([target jsx-str] (jsx->target target jsx-str {}))
  ([target jsx-str opts] (jsx->target target jsx-str opts ast-node))
  ([target jsx-str opts handle-ast-node]
   (first
     (w/postwalk (fn [x]
                   (if (get x :type)
                     #_(do (println (:type x))
                           (pf/print-and-return (handle-ast-node (:type x) x target opts)))
                     (handle-ast-node (:type x) x target opts)
                     x)) (jsx->ast jsx-str)))))


(def jsx->om (partial jsx->target (Om.)))
(def jsx->reagent (partial jsx->target (Reagent.)))

(defmulti transform-jsx (fn [target-str & _] target-str))

(defmethod transform-jsx "om"
  [_ & args]
  (apply jsx->om args))

(defmethod transform-jsx "reagent"
  [_ & args]
  (apply jsx->reagent args))


; Don't have tests yet, this is temporary to try out in REPL
(comment
  (jsx->om t0)
  (jsx->reagent t1 opts)
  (jsx->om t2 opts)
  (jsx->reagent t2 opts)
  (jsx->om t3 opts)
  (jsx->reagent t3 opts)
  (jsx->om t4 opts)
  (jsx->reagent t4 opts)
  (jsx->reagent t5 opts)
  (jsx->om t5 opts)
  (jsx->om t6 opts)
  (jsx->om t8 opts)
  (jsx->om t9 opts)
  (jsx->om t10 opts)
  (jsx->reagent t11 opts)
  (jsx->om t12 opts)
  (jsx->reagent t13 opts)
  (jsx->om t14 opts)
  (jsx->reagent t15 opts)
  (jsx->om t16 opts)
  (jsx->reagent t17 opts)
  (jsx->reagent t18 opts)
  (jsx->reagent t19 opts)
  (jsx->reagent t20 opts)
  (jsx->reagent t21 opts)
  (jsx->reagent t22 opts)
  (jsx->reagent t23 opts)
  (jsx->om t24 opts)
  (jsx->reagent t25 opts)
  (jsx->om t26 opts)
  (jsx->reagent t27 opts)
  (jsx->reagent t28 opts)
  (jsx->reagent t29 opts)
  (jsx->om t30 opts)
  (jsx->ast t24))


(comment
  (do
    (def t0 "<a x={styles}><b>here</b></a>")
    (def t1 "<View style={styles.container}>\n        <TouchableWithoutFeedback onPressIn={this._onPressIn}\n                                  onPressOut={this._onPressOut}>\n          <Image source={{uri: imageUri}} style={imageStyle} />\n        </TouchableWithoutFeedback>\n      </View>")
    (def t2 "<Table>\n    <TableHeader>\n      <TableRow>\n        <TableHeaderColumn value={this.state.firstSlider}>ID</TableHeaderColumn>\n        <TableHeaderColumn>Name</TableHeaderColumn>\n        <TableHeaderColumn>Status</TableHeaderColumn>\n      </TableRow>\n    </TableHeader>\n    <TableBody>\n      <TableRow>\n        <TableRowColumn>1</TableRowColumn>\n        <TableRowColumn>John Smith</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>2</TableRowColumn>\n        <TableRowColumn>Randal White</TableRowColumn>\n        <TableRowColumn>Unemployed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>3</TableRowColumn>\n        <TableRowColumn>Stephanie Sanders</TableRowColumn>\n        <TableRowColumn>Employed <b>Google Inc.</b></TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>4</TableRowColumn>\n        <TableRowColumn>Steve Brown</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n    </TableBody>\n  </Table>")
    (def t3 "<div style={this.style}>\n        <Slider\n          defaultValue={0.5}\n          value={this.state.firstSlider}\n          onChange={this.handleFirstSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.firstSlider}</span>\n          <span>{' from a range of 0 to 1 inclusive'}</span>\n        </p>\n        <Slider\n          min={0}\n          max={100}\n          step={1}\n          defaultValue={50}\n          value={this.state.secondSlider}\n          onChange={this.handleSecondSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.secondSlider}</span>\n          <span>{' from a range of 0 to 100 inclusive'}</span>\n        </p>\n      </div>")
    (def t4 "<AppBar\n    title={<span style={styles.title}>Title</span>}\n    onTitleTouchTap={handleTouchTap}\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={<FlatButton label=\"Save\" />}  />")
    (def t5 "<AppBar\n    title=\"Title\"\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={\n      <IconMenu\n        iconButtonElement={\n          <IconButton><MoreVertIcon /></IconButton>\n        }\n        targetOrigin={{horizontal: 'right', vertical: 'top'}}\n        anchorOrigin={{horizontal: 'right', vertical: 'top'}}\n      >\n        <MenuItem primaryText=\"Refresh\" />\n        <MenuItem primaryText=\"Help\" />\n        <MenuItem primaryText=\"Sign out\" />\n      </IconMenu>\n    }\n  />")
    (def t6 "<div>\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n        />\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n          floatingLabelText=\"Full width\"\n          fullWidth={true}\n        />\n      </div>")
    (def t8 "<Card>\n    <CardHeader\n      title=\"URL Avatar\"\n      subtitle=\"Subtitle\"\n      avatar=\"http://lorempixel.com/100/100/nature/\"\n    />\n    <CardMedia\n      overlay={<CardTitle title=\"Overlay title\" subtitle=\"Overlay subtitle\" />}\n    >\n      <img src=\"http://lorempixel.com/600/337/nature/\" />\n    </CardMedia>\n    <CardTitle title=\"Card title\" subtitle=\"Card subtitle\" />\n    <CardText>\n      Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n      Donec mattis pretium massa. Aliquam erat volutpat. Nulla facilisi.\n      Donec vulputate interdum sollicitudin. Nunc lacinia auctor quam sed pellentesque.\n      Aliquam dui mauris, mattis quis lacus id, pellentesque lobortis odio.\n    </CardText>\n    <CardActions>\n      <FlatButton label=\"Action1\" />\n      <FlatButton label=\"Action2\" />\n    </CardActions>\n  </Card>")
    (def t9 "<Animated.Image                         \nsource={{uri: 'http://i.imgur.com/XMKOH81.jpg'}}\nstyle={{\n        flex: 1,\n        transform: [                        \n                    {scale: this.state.bounceValue},\n    ]\n        }}\n/>")
    (def t10 "<DatePicker\n      hintText=\"Custom date format\"\n      firstDayOfWeek={0}\n      formatDate={new DateTimeFormat('en-US', {\n        day: 'numeric',\n        month: 'long',\n        year: 'numeric',\n      }).format}\n    />")
    (def t11 " <div style={{width: 380, height: 400, margin: 'auto'}}>\n        <Stepper activeStep={stepIndex} orientation=\"vertical\">\n          <Step>\n            <StepLabel>Select campaign settings</StepLabel>\n            <StepContent>\n              <p>\n                For each ad campaign that you create, you can control how much\n                you're willing to spend on clicks and conversions, which networks\n                and geographical locations you want your ads to show on, and more.\n              </p>\n              {this.renderStepActions(0)}\n            </StepContent>\n          </Step>\n          <Step>\n            <StepLabel>Create an ad group</StepLabel>\n            <StepContent>\n              <p>An ad group contains one or more ads which target a shared set of keywords.</p>\n              {this.renderStepActions(1)}\n            </StepContent>\n          </Step>\n          <Step>\n            <StepLabel>Create an ad</StepLabel>\n            <StepContent>\n              <p>\n                Try out different ad text to see what brings in the most customers,\n                and learn how to enhance your ads using features like ad extensions.\n                If you run into any problems with your ads, find out how to tell if\n                they're running and how to resolve approval issues.\n              </p>\n              {this.renderStepActions(2)}\n            </StepContent>\n          </Step>\n        </Stepper>\n        {finished && (\n          <p style={{margin: '20px 0', textAlign: 'center'}}>\n            <a\n              href=\"#\"\n              onClick={(event) => {\n                event.preventDefault();\n                this.setState({stepIndex: 0, finished: false});\n              }}\n            >\n              Click here\n            </a> to reset the example.\n          </p>\n        )}\n      </div>")
    (def t12 "<div style={{margin: '12px 0'}}>\n        <RaisedButton\n          label={stepIndex === 2 ? 'Finish' : 'Next'}\n          disableTouchRipple={true}\n          disableFocusRipple={true}\n          primary={true}\n          onTouchTap={this.handleNext}\n          style={{marginRight: 12}}\n        />\n        {step > 0 && (\n          <FlatButton\n            label=\"Back\"\n            disabled={stepIndex === 0}\n            disableTouchRipple={true}\n            disableFocusRipple={true}\n            onTouchTap={this.handlePrev}\n          />\n        )}\n      </div>")
    (def t13 "<tbody>\n        {data.map(function(item, idx){\n          return <TableRow key={idx} data={item} columns={columns}/>;\n        })}\n      </tbody>")
    (def t14 "<Component {...props} foo={'override'} />;")
    (def t15 " <Nav>\n    {/* child comment, put {} around */}\n    <Person\n      /* multi\n         line\n         comment */\n      name={window.isLoggedIn ? window.name : ''} // end of line comment\n    />\n  </Nav>")
    (def t16 "<tr key={i}>\n              {row.map(function(col, j) {\n                return <td key={j}>{col}</td>;\n              })}\n            </tr>")
    (def t17 "<div style={styles.root}>\n    <GridList\n      cellHeight={200}\n      style={styles.gridList}\n    >\n      <Subheader>December</Subheader>\n      {tilesData.map((tile) => (\n        <GridTile\n          key={tile.img}\n          title={tile.title}\n          subtitle={<span>by <b>{tile.author}</b></span>}\n          actionIcon={<IconButton><StarBorder color=\"white\" /></IconButton>}\n        >\n          <img src={tile.img} />\n        </GridTile>\n      ))}\n    </GridList>\n  </div>")
    (def t18 "<div>\n        <RaisedButton\n          onTouchTap={this.handleTouchTap}\n          label=\"Click me\"\n        />\n        <h3 style={styles.h3}>Current Settings</h3>\n        <pre>\n          anchorOrigin: {JSON.stringify(this.state.anchorOrigin)}\n          <br />\n          targetOrigin: {JSON.stringify(this.state.targetOrigin)}\n        </pre>\n        <h3 style={styles.h3}>Position Options</h3>\n        <p>Use the settings below to toggle the positioning of the popovers above</p>\n        <h3 style={styles.h3}>Anchor Origin</h3>\n        <div style={styles.block}>\n          <div style={styles.block2}>\n            <span>Vertical</span>\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'vertical', 'top')}\n              label=\"Top\" checked={this.state.anchorOrigin.vertical === 'top'}\n            />\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'vertical', 'center')}\n              label=\"Center\" checked={this.state.anchorOrigin.vertical === 'center'}\n            />\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'vertical', 'bottom')}\n              label=\"Bottom\" checked={this.state.anchorOrigin.vertical === 'bottom'}\n            />\n          </div>\n          <div style={styles.block2}>\n            <span>Horizontal</span>\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'horizontal', 'left')}\n              label=\"Left\" checked={this.state.anchorOrigin.horizontal === 'left'}\n            />\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'horizontal', 'middle')}\n              label=\"Middle\" checked={this.state.anchorOrigin.horizontal === 'middle'}\n            />\n            <RadioButton\n              onClick={this.setAnchor.bind(this, 'horizontal', 'right')}\n              label=\"Right\" checked={this.state.anchorOrigin.horizontal === 'right'}\n            />\n          </div>\n        </div>\n        <h3 style={styles.h3}>Target Origin</h3>\n        <div style={styles.block}>\n          <div style={styles.block2}>\n            <span>Vertical</span>\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'vertical', 'top')}\n              label=\"Top\" checked={this.state.targetOrigin.vertical === 'top'}\n            />\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'vertical', 'center')}\n              label=\"Center\" checked={this.state.targetOrigin.vertical === 'center'}\n            />\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'vertical', 'bottom')}\n              label=\"Bottom\" checked={this.state.targetOrigin.vertical === 'bottom'}\n            />\n          </div>\n          <div style={styles.block2}>\n            <span>Horizontal</span>\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'horizontal', 'left')}\n              label=\"Left\" checked={this.state.targetOrigin.horizontal === 'left'}\n            />\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'horizontal', 'middle')}\n              label=\"Middle\" checked={this.state.targetOrigin.horizontal === 'middle'}\n            />\n            <RadioButton\n              onClick={this.setTarget.bind(this, 'horizontal', 'right')}\n              label=\"Right\" checked={this.state.targetOrigin.horizontal === 'right'}\n            />\n          </div>\n        </div>\n        <Popover\n          open={this.state.open}\n          anchorEl={this.state.anchorEl}\n          anchorOrigin={this.state.anchorOrigin}\n          targetOrigin={this.state.targetOrigin}\n          onRequestClose={this.handleRequestClose}\n        >\n          <Menu>\n            <MenuItem primaryText=\"Refresh\" />\n            <MenuItem primaryText=\"Help &amp; feedback\" />\n            <MenuItem primaryText=\"Settings\" />\n            <MenuItem primaryText=\"Sign out\" />\n          </Menu>\n        </Popover>\n      </div>")
    (def t19 "<div>\n        <SelectField\n          value={value}\n          onChange={this.handleChange}\n          errorText={!night && 'Should be Night'}\n        >\n          {items}\n        </SelectField>\n        <br />\n        <SelectField\n          value={value}\n          onChange={this.handleChange}\n          errorText={night && 'Should not be Night (Custom error style)'}\n          errorStyle={{color: 'orange'}}\n        >\n          {items}\n        </SelectField>\n      </div>")
    (def t20 "<div id=\"my-id\" className=\"some-class some-other\"><span className={styles.span}><b className={\"home\"}>Home</b></span></div>")
    (def t21 "<View style={styles.container}>\n        <View style={[styles.box, {width: this.state.w, height: this.state.h}]} />\n        <TouchableOpacity onPress={this._onPress}>\n          <View style={styles.button}>\n            <Text style={styles.buttonText}>Press me!</Text>\n          </View>\n        </TouchableOpacity>\n      </View>")
    (def t22 "<View style={{flex: 1, justifyContent: 'center', alignItems: 'center'}}>\n        <TouchableWithoutFeedback onPress={this._animateOpacity}>\n          <View ref={component => this._box = component}\n                style={{width: 200, height: 200, backgroundColor: 'red',\n                        opacity: this.getTweeningValue('opacity')}} />\n        </TouchableWithoutFeedback>\n      </View>")
    (def t23 "<View>\n        <Modal\n          animated={this.state.animated}\n          transparent={this.state.transparent}\n          visible={this.state.modalVisible}\n          onRequestClose={() => {this._setModalVisible(false)}}\n          >\n          <View style={[styles.container, modalBackgroundStyle]}>\n            <View style={[styles.innerContainer, innerContainerTransparentStyle]}>\n              <Text>This modal was presented {this.state.animated ? 'with' : 'without'} animation.</Text>\n              <Button\n                onPress={this._setModalVisible.bind(this, false)}\n                style={styles.modalButton}>\n                Close\n              </Button>\n            </View>\n          </View>\n        </Modal>\n\n        <View style={styles.row}>\n          <Text style={styles.rowTitle}>Animated</Text>\n          <Switch value={this.state.animated} onValueChange={this._toggleAnimated} />\n        </View>\n\n        <View style={styles.row}>\n          <Text style={styles.rowTitle}>Transparent</Text>\n          <Switch value={this.state.transparent} onValueChange={this._toggleTransparent} />\n        </View>\n\n        <Button onPress={this._setModalVisible.bind(this, true)}>\n          Present\n        </Button>\n      </View>")
    (def t24 "<Navigator\n   initialRoute={{name: 'My First Scene', index: 0}}\n   renderScene={(route, navigator) =>\n     <MySceneComponent\n       name={route.name}\n       onForward={() => {\n         var nextIndex = route.index + 1,\n\t       myVar = \"sth\";\n\tvar prevIndex = route.index - 1;\n         navigator.push({\n           name: 'Scene ' + nextIndex,\n           index: nextIndex,\n         });\n       }}\n       onBack={() => {\n         if (route.index > 0) {\n           navigator.pop();\n         }\n       }}\n     />\n   }\n />")
    (def t25 "<ul>\n        {this.props.results.map(function(result) {\n          return <ListItemWrapper data={result}/>;\n        })}\n      </ul>")
    (def t26 "<View>\n        <Text>Please choose a make for your car:</Text>\n        <PickerIOS\n          selectedValue={this.state.carMake}\n          onValueChange={(carMake) => this.setState({carMake, modelIndex: 0})}>\n          {Object.keys(CAR_MAKES_AND_MODELS).map((carMake) => (\n            <PickerItemIOS\n              key={carMake}\n              value={carMake}\n              label={CAR_MAKES_AND_MODELS[carMake].name}\n            />\n          ))}\n        </PickerIOS>\n        <Text>Please choose a model of {make.name}:</Text>\n        <PickerIOS\n          selectedValue={this.state.modelIndex}\n          key={this.state.carMake}\n          onValueChange={(modelIndex) => this.setState({modelIndex})}>\n          {CAR_MAKES_AND_MODELS[this.state.carMake].models.map((modelName, modelIndex) => (\n            <PickerItemIOS\n              key={this.state.carMake + '_' + modelIndex}\n              value={modelIndex}\n              label={modelName}\n            />\n          ))}\n        </PickerIOS>\n        <Text>You selected: {selectionString}</Text>\n      </View>")
    (def t27 "< Navigator initialRoute = {\n    {\n      name: 'My First Scene',\n      index: 0\n    }\n  }\n  renderScene = {\n    (route, navigator) =>\n    < MySceneComponent\n    name = {\n      route.name\n    }\n    onForward = {\n      () => {\n        var nextIndex = route.index + 1,\n          myOtherIndex = nextIndex + 10;\n\n        navigator.push({\n          name: 'Scene ' + nextIndex,\n          index: nextIndex,\n        });\n\n        var yetAnotherIndex = myOtherIndex - 1;\n      }\n    }\n    onBack = {\n      () => {\n        if (route.index > 0) {\n          navigator.pop();\n        } else if (route.index == 0) {\n          someFuction();\n          namingIsHardFun();\n        } else {\n        \tvar myGreatParam = 5;\n          someOtherFunction(myGreatParam);\n        }\n      }\n    }\n    />\n  }\n  />")
    (def t28 "<View>\n        <ScrollView\n          ref={(scrollView) => { _scrollView = scrollView; }}\n          automaticallyAdjustContentInsets={false}\n          onScroll={() => { console.log('onScroll!'); }}\n          scrollEventThrottle={200}\n          style={styles.scrollView}>\n          {THUMBS.map(createThumbRow)}\n        </ScrollView>\n        <TouchableOpacity\n          style={styles.button}\n          onPress={() => { _scrollView.scrollTo({y: 0}); }}>\n          <Text>Scroll to top</Text>\n        </TouchableOpacity>\n      </View>")
    (def t29 "<Animated.View\n         {...this.state.panResponder.panHandlers}\n         style={this.state.pan.getLayout()}>\n         {this.props.children}\n       </Animated.View>")
    (def t30 "<TextInput\n    style={[styles, {height: 40, borderColor: 'gray', borderWidth: 1}, {borderColor: 'blue'}]}\n    onChangeText={(text) => this.setState({text})}\n    value={this.state.text}\n  />")

    (def opts {:ns               "u"
               :dom-ns           "d"
               :kebab-tags       true
               :kebab-attrs      true
               :camel-styles     true
               :remove-attr-vals false
               :omit-empty-attrs true
               :lib-ns           "lib"})))