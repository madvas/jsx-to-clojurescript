# JSX to Clojurescript &nbsp; &nbsp;  ![jsx](https://cloud.githubusercontent.com/assets/3857155/15179972/31c60106-177f-11e6-8c6f-8598d611154d.png) &nbsp; ![arrow](https://cloud.githubusercontent.com/assets/3857155/15180286/eace4644-1780-11e6-8d84-e1b8454a2e2e.png) &nbsp; ![clojurescript](https://cloud.githubusercontent.com/assets/3857155/15180154/246d0378-1780-11e6-800f-6bf069dac6a2.png)
**Moving old ReactJS codebase to Clojurescript? Tired of manually typing ReactJS examples as Clojurescript?**

**Search no more!**


This is command utility and library to **convert JSX snippets to Om/Reagent/Rum Clojurescript-style format.**
Note, this is by no means to be perfect JS->Cljs compiler, output will often still need touch of your loving
hand, but, hey, most of dirty work will be done :sunglasses:

This library uses [acorn-jsx](https://github.com/RReverser/acorn-jsx) to parse JSX into nice AST. So big kudos there.
Since it's Node.js library, you can use this library only in Clojurescript targeted to Node.js

### Installation
**As command:**
```bash
npm install -g jsx-to-clojurescript
```
**As library:**
```
[jsx-to-clojurescript "0.1.9"]
```
**As [Alfred](https://www.alfredapp.com/) workflow (Mac only):**

I also made workflow, you can download it [here](https://github.com/madvas/jsx-to-clojurescript/raw/master/jsx-to-clojurescript.alfredworkflow)
Following things are needed:
* You installed via `npm install -g jsx-to-clojurescript`
* Workflow assumes following paths `/usr/local/bin/node` `/usr/local/bin/jsx-to-clojurescript`. If you
have different, you can change it in Alfred preferences when you open this workflow's run script.

Use it with keyword `jsxcljs` and paste JSX. To change command line arguments for all following queries use
`jsxcljs set` and type arguments. Don't put arguments into `jsxcljs`, only JSX string.


**Build your own:**
```bash
lein cljsbuild once
```

### Usage

```bash
jsx-to-clojurescript -h

  Usage: jsx-to-clojurescript [options] <string>

  Converts JSX string into selected Clojurescript React library format

  Options:

    -h, --help            output usage information
    -V, --version         output the version number
    -t --target [target]  Target library (om/reagent/rum). Default om
    --ns [string]         Namespace for compoments. Default ui
    --dom-ns [ns]         Namespace for DOM compoments. Default dom
    --lib-ns [ns]         Target library ns. Default for Om: 'om'. Default for reagent & rum: 'r'
    --kebab-tags          Convert tags to kebab-case?
    --kebab-attrs         Convert attributes to kebab-case?
    --camel-styles        Keep style keys as camelCase
    --remove-attr-vals    Remove attribute values?
    --omit-empty-attrs    Omit empty attributes?
    --styles-as-vector    Keep multiple styles as vector instead of merge


```

**Okay let's start with something simple** :bowtie:
```javascript
<div>
    <RaisedButton label="Secondary" secondary={true} style={style} />
    <RaisedButton label="Disabled" disabled={true} style={style} />
</div>
```
```bash
jsx-to-clojurescript --kebab-tags "$(pbpaste)"
```
```clojure
(dom/div
 {}
 (ui/raised-button {:label "Secondary", :secondary true, :style style})
 (ui/raised-button {:label "Disabled", :disabled true, :style style}))
```
**Now something more nested...** :wink:
```javascript
<AppBar
    title="Title"
    iconElementLeft={<IconButton><NavigationClose /></IconButton>}
    iconElementRight={
      <IconMenu
        iconButtonElement={
          <IconButton><MoreVertIcon /></IconButton>
        }
        targetOrigin={{horizontal: 'right', vertical: 'top'}}
        anchorOrigin={{horizontal: 'right', vertical: 'top'}}
      >
        <MenuItem primaryText="Refresh" />
        <MenuItem primaryText="Help" />
        <MenuItem primaryText="Sign out" />
      </IconMenu>
    }
  />
```
```bash
 jsx-to-clojurescript --kebab-tags --kebab-attrs --ns "u" --target reagent --omit-empty-attrs "$(pbpaste)"
```
```clojure
[u/app-bar
 {:title "Title",
  :icon-element-left [u/icon-button [u/navigation-close]],
  :icon-element-right
  [u/icon-menu
   {:icon-button-element [u/icon-button [u/more-vert-icon]],
    :target-origin {:horizontal "right", :vertical "top"},
    :anchor-origin {:horizontal "right", :vertical "top"}}
   [u/menu-item {:primary-text "Refresh"}]
   [u/menu-item {:primary-text "Help"}]
   [u/menu-item {:primary-text "Sign out"}]]}]
```
**Conditions and anonymous functions are okay too!** :smiley:
```javascript
<div style={{width: '100%', maxWidth: 700, margin: 'auto'}}>
        <Stepper activeStep={stepIndex}>
          <Step>
            <StepLabel>Select campaign settings</StepLabel>
          </Step>
          <Step>
            <StepLabel>Create an ad group</StepLabel>
          </Step>
          <Step>
            <StepLabel>Create an ad</StepLabel>
          </Step>
        </Stepper>
        <div style={contentStyle}>
          {finished ? (
            <p>
              <a href="#"
                onClick={(event) => {
                  event.preventDefault();
                  this.setState({stepIndex: 0, finished: false});
                }}
              >
                Click here
              </a> to reset the example.
            </p>
          ) : (
            <div>
              <p>{this.getStepContent(stepIndex)}</p>
              <div style={{marginTop: 12}}>
                <FlatButton
                  label="Back"
                  disabled={stepIndex === 0}
                  onTouchTap={this.handlePrev}
                  style={{marginRight: 12}}
                />
                <RaisedButton
                  label={stepIndex === 2 ? 'Finish' : 'Next'}
                  primary={true}
                  onTouchTap={this.handleNext}
                />
              </div>
            </div>
          )}
        </div>
      </div>
```
```bash
jsx-to-clojurescript --kebab-tags --kebab-attrs --ns "u" --target reagent --omit-empty-attrs "$(pbpaste)"
```
```clojure
[:div
 {:style {:width "100%", :max-width 700, :margin "auto"}}
 [u/stepper
  {:active-step step-index}
  [u/step [u/step-label "Select campaign settings"]]
  [u/step [u/step-label "Create an ad group"]]
  [u/step [u/step-label "Create an ad"]]]
 [:div
  {:style content-style}
  (if finished
    [:p
     [:a
      {:href "#",
       :on-click
             (fn [event]
               (prevent-default event)
               (r/set-state this {:step-index 0, :finished false}))}
      "Click here"]
     " to reset the example."]
    [:div
     [:p (get-step-content step-index)]
     [:div
      {:style {:margin-top 12}}
      [u/flat-button
       {:label        "Back",
        :disabled     (= step-index 0),
        :on-touch-tap handle-prev,
        :style        {:margin-right 12}}]
      [u/raised-button
       {:label        (if (= step-index 2) "Finish" "Next"),
        :primary      true,
        :on-touch-tap handle-next}]]])]]
```
**Mapping? No problem! Notice how `map` doesn't require any more editing** :relaxed:
```javascript
<ul>
    {this.props.results.map(function(result) {
        return <ListItemWrapper data={result}/>;
    })}
</ul>
```
```bash
jsx-to-clojurescript --ns "" --target om "$(pbpaste)"
```
```clojure
(dom/ul
 {}
 (map
    (fn [result]
        (ListItemWrapper {:data result}))
    (:results props)))
```
**Still not impressed? We can do spread attributes too!** :grinning:
```javascript
<Animated.View
   {...this.state.panResponder.panHandlers}
   style={this.state.pan.getLayout()}>
   {this.props.children}
 </Animated.View>
```
```bash
jsx-to-clojurescript --ns "" --target om "$(pbpaste)"
```
```clojure
(AnimatedView
 (merge
  (:pan-handlers (:pan-responder state))
  {:style (get-layout (:pan state))})
 (:children props))
```
**Array of styles as a nice merge** :relieved:
```javascript
<View style={styles.container}>
   <View style={[styles.box, {width: this.state.w, height: this.state.h}]} />
</View>
```
```bash
jsx-to-clojurescript --target reagent "$(pbpaste)"
```
```clojure
[ui/View
 {:style (:container styles)}
 [ui/View
  {:style
   (merge (:box styles) {:width (:w state), :height (:h state)})}]]
```
**Reagent can do neat trick with ids and classes** :kissing:
```javascript
<div id="my-id" className="some-class some-other">
    <span className={styles.span}>
        <b className={"home"}>Home</b>
    </span>
</div>
```
```bash
jsx-to-clojurescript --kebab-attrs --target reagent "$(pbpaste)"
```
```clojure
[:div#my-id.some-class.some-other
 {}
 [:span {:class-name (:span styles)} [:b.home {} "Home"]]]
```
**LOL variable declarations and conditions?!** :joy:
```javascript
< Navigator initialRoute = {
    {
      name: 'My First Scene',
      index: 0
    }
  }
  renderScene = {
    (route, navigator) =>
    < MySceneComponent
    name = {
      route.name
    }
    onForward = {
      () => {
        var nextIndex = route.index + 1,
          myOtherIndex = nextIndex + 10;

        navigator.push({
          name: 'Scene ' + nextIndex,
          index: nextIndex,
        });

        var yetAnotherIndex = myOtherIndex - 1;
      }
    }
    onBack = {
      () => {
        if (route.index > 0) {
          navigator.pop();
        } else if (route.index == 0) {
          someFuction();
          namingIsHardFun();
        } else {
        	var myGreatParam = 5;
          someOtherFunction(myGreatParam);
        }
      }
    }
    />
  }
  />
```
```bash
jsx-to-clojurescript --kebab-attrs --kebab-tags "$(pbpaste)"
```
```clojure
(ui/navigator
  {:initial-route {:name "My First Scene", :index 0},
   :render-scene  (fn [route navigator]
                    (ui/my-scene-component
                      {:name (:name route),
                       :on-forward
                             (fn []
                               (let [next-index (+ (:index route) 1)
                                     my-other-index (+ next-index 10)
                                     yet-another-index (- my-other-index 1)]
                                 (push navigator {:name (+ "Scene " next-index), :index next-index}))),
                       :on-back
                             (fn []
                               (if (> (:index route) 0)
                                 (pop navigator)
                                 (if (= (:index route) 0)
                                   (do
                                     (some-fuction)
                                     (naming-is-hard-fun))
                                   (let [my-great-param 5]
                                     (some-other-function my-great-param)))))}))})
```
**No problem with regular JS** :wink:
```javascript
const myConst = {some: 34};

function explode(size) {
	var earth = "planet";
	var foo = "bar";
	return boom(earth) * size + myConst;
}

explode(42);

```
```bash
jsx-to-clojurescript "$(pbpaste)"
```
```clojure
(do
  (def my-const {:some 34})
  (defn explode [size]
    (let [earth "planet"
          foo "bar"]
      (+ (* (boom earth) size) my-const)))
  (explode 42))
```

Alright folks, that's enough of examples, I guess you get the picture :wink:. If you saw error like
`ERROR: Don't know how to handle node type <something>` it means I haven't implmented some JS syntax yet. Open issue or PR :)

### Library API
I won't write API here for 2 reasons:

1. Not sure if this has many use cases as a library

2. Core codebase is just ~200 very straightforward lines of code. You will get it very quickly, when you see it. (gotta love Clojure :purple_heart:)

If interested, library is extendable, you can easily add other targets other than Om/Reagent (with a single function!)

