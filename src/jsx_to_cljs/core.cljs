(ns jsx-to-cljs.core
  (:require [print.foo :as pf :include-macros true]
            [clojure.string :as s]
            [clojure.walk :as w]
            [jsx-to-cljs.utils :as u]))

(def acorn (js/require "acorn-jsx"))

(defprotocol TargetLibrary
  (element [this tag attrs children dom-tag? node opts]))

(defrecord Om []
  TargetLibrary
  (element [_ tag attrs children _ _ {:keys [omit-empty-attrs]}]
    (cond-> `(~tag)
            (or (not omit-empty-attrs)
                (seq attrs)) (concat [attrs])
            true (concat children))))

(defrecord Reagent []
  TargetLibrary
  (element [_ tag attrs children dom-tag? _ {:keys [omit-empty-attrs]}]
    (into [] (cond-> [(if dom-tag? (keyword (name tag)) tag)]
                     (or (not omit-empty-attrs)
                         (seq attrs)) (conj attrs)
                     true (concat children)))))

(defmulti ast-node (fn [type node target opts] type))

(defmethod ast-node :default
  [type _ _ _]
  (println "ERROR: Don't know how to handle node type " type))

(defmethod ast-node "Program" [_ node _ _] (:body node))
(defmethod ast-node "ExpressionStatement" [_ node _ _] (:expression node))
(defmethod ast-node "JSXIdentifier" [_ node _ _] (:name node))
(defmethod ast-node "Identifier" [_ node _ _] (-> node :name symbol u/camel->kebab))
(defmethod ast-node "JSXMemberExpression" [_ node _ _] (str (:object node) (:property node)))
(defmethod ast-node "JSXOpeningElement" [_ node _ _] node)
(defmethod ast-node "JSXClosingElement" [_ node _ _] (:name node))
(defmethod ast-node "JSXExpressionContainer" [_ node _ _] (:expression node))
(defmethod ast-node "JSXAttribute" [_ node _ _] {(keyword (:name node)) (:value node)})
(defmethod ast-node "ThisExpression" [_ _ _ _] (symbol "this"))
(defmethod ast-node "ObjectExpression" [_ node _ _] (into {} (:properties node)))
(defmethod ast-node "ArrayExpression" [_ node _ _] (:elements node))
(defmethod ast-node "Property" [_ node _ _] {(-> node :key keyword u/camel->kebab) (:value node)})
(defmethod ast-node "BlockStatement" [_ node _ _] node)
(defmethod ast-node "ReturnStatement" [_ node _ _] (:argument node))
(defmethod ast-node "JSXSpreadAttribute" [_ node _ _] (:argument node))
(defmethod ast-node "JSXEmptyExpression" [_ _ _ _] nil)
(defmethod ast-node "Literal" [_ node _ _] (:value node))

(defmethod ast-node "NewExpression" [_ {:keys [callee arguments]} _ _]
  (concat (-> callee (str ".") symbol (u/kebab->camel true) list)
          arguments))

(defmethod ast-node "CallExpression" [_ {:keys [callee arguments]} _ _]
  (-> callee
      u/force-list
      (u/update-first-in-list u/keyword->symbol)
      (u/update-first-in-list #(if (= % 'bind) 'partial %))
      (concat arguments)))

(defmethod ast-node "MemberExpression" [_ {:keys [property object]} _ _]
  (if (= object 'this)
    (-> property u/camel->kebab)
    `(~(-> property keyword u/camel->kebab) ~object)))

(defn fn-expression [_ {:keys [params body]} _ _]
  (concat `(~'fn ~params) (if (= "BlockStatement" (get body :type))
                            (:body body)
                            [body])))

(defmethod ast-node "ArrowFunctionExpression" [& args]
  (apply fn-expression args))

(defmethod ast-node "FunctionExpression" [& args]
  (apply fn-expression args))

(defmethod ast-node "LogicalExpression" [_ {:keys [operator left right]} _ _]
  (let [opr (u/operator->symbol operator)]
    (if (= opr 'and)
      `(~'when ~left ~right)
      `(~opr ~left ~right))))

(defmethod ast-node "BinaryExpression" [_ {:keys [operator left right]} _ _]
  `(~(u/operator->symbol operator) ~left ~right))

(defmethod ast-node "ConditionalExpression" [_ {:keys [test consequent alternate]} _ _]
  `(~'if ~test ~consequent ~alternate))

(defn create-attrs-merge [attrs spread-attrs]
  (concat `(~'merge) spread-attrs [attrs]))

(defmethod ast-node "JSXElement"
  [_ node target {:keys [ns dom-ns kebab-tags kebab-attrs remove-attr-vals] :as opts}]
  (let [el (:openingElement node)
        tag-name (:name el)
        dom-tag? (u/dom-tag? tag-name)
        ns (if dom-tag? dom-ns ns)
        tag (cond-> (keyword tag-name)
                    kebab-tags u/camel->kebab
                    (not (s/blank? ns)) (u/add-ns ns)
                    true u/keyword->symbol)
        all-attrs (:attributes el)
        spread-attrs (seq (filter symbol? all-attrs))
        attrs (cond-> (into {} (filter map? all-attrs))
                      kebab-attrs u/kebabize-keys
                      remove-attr-vals u/remove-attributes-values
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
  ([target jsx-str] (jsx->target target jsx-str {}))
  ([target jsx-str opts] (jsx->target target jsx-str opts ast-node))
  ([target jsx-str opts handle-ast-node]
   (first
     (w/postwalk (fn [x]
                   (if (get x :type)
                     #_(do (println (:type x))
                           (pf/print-and-return (ast-node (:type x) x target opts)))
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

(def t0 "<a x={styles}><b>here</b></a>")
(def t1 "<View style={styles.container}>\n        <TouchableWithoutFeedback onPressIn={this._onPressIn}\n                                  onPressOut={this._onPressOut}>\n          <Image source={{uri: imageUri}} style={imageStyle} />\n        </TouchableWithoutFeedback>\n      </View>")
(def t2 "<Table>\n    <TableHeader>\n      <TableRow>\n        <TableHeaderColumn value={this.state.firstSlider}>ID</TableHeaderColumn>\n        <TableHeaderColumn>Name</TableHeaderColumn>\n        <TableHeaderColumn>Status</TableHeaderColumn>\n      </TableRow>\n    </TableHeader>\n    <TableBody>\n      <TableRow>\n        <TableRowColumn>1</TableRowColumn>\n        <TableRowColumn>John Smith</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>2</TableRowColumn>\n        <TableRowColumn>Randal White</TableRowColumn>\n        <TableRowColumn>Unemployed</TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>3</TableRowColumn>\n        <TableRowColumn>Stephanie Sanders</TableRowColumn>\n        <TableRowColumn>Employed <b>Google Inc.</b></TableRowColumn>\n      </TableRow>\n      <TableRow>\n        <TableRowColumn>4</TableRowColumn>\n        <TableRowColumn>Steve Brown</TableRowColumn>\n        <TableRowColumn>Employed</TableRowColumn>\n      </TableRow>\n    </TableBody>\n  </Table>")
(def t3 "<div style={this.style}>\n        <Slider\n          defaultValue={0.5}\n          value={this.state.firstSlider}\n          onChange={this.handleFirstSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.firstSlider}</span>\n          <span>{' from a range of 0 to 1 inclusive'}</span>\n        </p>\n        <Slider\n          min={0}\n          max={100}\n          step={1}\n          defaultValue={50}\n          value={this.state.secondSlider}\n          onChange={this.handleSecondSlider.bind(this)}\n        />\n        <p>\n          <span>{'The value of this slider is: '}</span>\n          <span>{this.state.secondSlider}</span>\n          <span>{' from a range of 0 to 100 inclusive'}</span>\n        </p>\n      </div>")
(def t4 "<AppBar\n    title={<span style={styles.title}>Title</span>}\n    onTitleTouchTap={handleTouchTap}\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={<FlatButton label=\"Save\" />}  />")
(def t4-5 "<AppBar\ntitle=\"Title\"\niconElementLeft={<IconButton><NavigationClose /></IconButton>}\niconElementRight={\n                  <IconMenu\n                            iconButtonElement={\n                                               <IconButton><MoreVertIcon /></IconButton>\n                                               }\n                            targetOrigin={{horizontal: 'right', vertical: 'top'}}\n                            anchorOrigin={{horizontal: 'right', vertical: 'top'}}>\n                  </IconMenu>\n                  }\n/>")
(def t5 "<AppBar\n    title=\"Title\"\n    iconElementLeft={<IconButton><NavigationClose /></IconButton>}\n    iconElementRight={\n      <IconMenu\n        iconButtonElement={\n          <IconButton><MoreVertIcon /></IconButton>\n        }\n        targetOrigin={{horizontal: 'right', vertical: 'top'}}\n        anchorOrigin={{horizontal: 'right', vertical: 'top'}}\n      >\n        <MenuItem primaryText=\"Refresh\" />\n        <MenuItem primaryText=\"Help\" />\n        <MenuItem primaryText=\"Sign out\" />\n      </IconMenu>\n    }\n  />")
(def t6 "<div>\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n        />\n        <AutoComplete\n          hintText=\"Type anything\"\n          dataSource={this.state.dataSource}\n          onUpdateInput={this.handleUpdateInput}\n          floatingLabelText=\"Full width\"\n          fullWidth={true}\n        />\n      </div>")
(def t7 "<aa v={<dd>sop</dd>} z={ <bb y={ <cc x={st}>ABC</cc> }></bb> }></aa>")
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

(def opts {:ns               "u"
           :dom-ns           "d"
           :kebab-tags       true
           :kebab-attrs      true
           :remove-attr-vals false
           :omit-empty-attrs true})

(comment
  (jsx->om t0)
  (jsx->reagent t1 opts)
  (jsx->om t2 opts)
  (jsx->reagent t2 opts)
  (jsx->om t3 opts)
  (jsx->reagent t3 opts)
  (jsx->om t4 opts)
  (jsx->reagent t4 opts)
  (jsx->om t4-5 opts)
  (jsx->reagent t4-5 opts)
  (jsx->reagent t5 opts)
  (jsx->om t5 opts)
  (jsx->om t6 opts)
  (jsx->om t7 opts)
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
  (jsx->ast t17)
  )
