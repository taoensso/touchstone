(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * mab:<test-name>:nviews -> hash, {form-name views-count}
    * mab:<test-name>:scores -> hash, {form-name score}
    * mab:<test-name>:selection:<user-id> -> string, form-name

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [taoensso.carmine :as car]
            [taoensso.touchstone.utils :as utils]))

;;;; Config & bindings

(defonce config (atom {:carmine {:pool nil :spec nil}}))

(defmacro wcar "With Carmine..."
  [& body]
  `(let [{pool# :pool spec# :spec} (@config :carmine)]
     (car/with-conn pool# spec# ~@body)))

(def ^:private ^:dynamic *user-id* nil)
(def ^:private ^:dynamic *select-default-form*
  "A switch to force selection of default (first) `mab-select` forms? Useful as
  an override for out-of-context testing, bot web requests, etc."
  nil)

(defn wrap-mab-bindings
  "Assumes both `utils.middleware/wrap-identify-bots` and
                `utils.middleware/wrap-user-id` have been applied to request."
  [handler]
  (fn [request]
    (binding [*select-default-form* (or (-> request :session :skip-mab?)
                                        (not= (:client-type request) :human))
              *user-id* (-> request :session :user-id)]
      (handler request))))

;;;;

(def ^:private mkey "Prefixed MAB key" (memoize (car/make-keyfn "mab")))

(def ^:private ucb1-score
  "Use \"UCB1\" formula to score a named MAB test form for selection sorting.

  Formula motivation: we want frequency of exploration to be inversly
  proportional to our confidence in the superiority of the leading form. This
  implies confidence in both relevant sample sizes, as well as the statistical
  significance of the difference between observed form scores."
  (utils/memoize-ttl
   10000 ; 10 secs, for performance
   (fn [test-name form-name]
     (let [[nviews-map score]
           (wcar (car/hgetall* (mkey test-name "nviews"))
                 (car/hget     (mkey test-name "scores") (name form-name)))

           score      (or (car/as-double score) 0)
           nviews     (car/as-long (get nviews-map (name form-name) 0))
           nviews-sum (reduce + (map car/as-long (vals nviews-map)))]

       (if (or (zero? nviews) (zero? nviews-sum))
         1000 ;; Very high score (i.e. always select untested forms)
         (+ (/ score nviews)
            (Math/sqrt (/ 0.5 (Math/log nviews-sum) nviews))))))))

(declare mab-select*)

(defmacro mab-select
  "Defines a named MAB test that selects and evaluates one of the named testing
  forms using the \"UCB1\" selection algorithm.

  Returns default (first) form without testing when *select-default-form* is
  true (e.g. for bots), or *user-id* is unspecified.

  (mab-select :my-test-1
              :my-form-1 \"String 1\"
              :my-form-2 (do (Thread/sleep 2000) \"String 2\"))"
  [test-name & [default-form-name & _ :as name-form-pairs]]
  `(mab-select* ~test-name ~default-form-name
                ;; Note that to prevent caching of form evaluation, we actually
                ;; DO want a fresh delay-map for each call
                (utils/delay-map ~@name-form-pairs)))

(defn- mab-select*
  [test-name default-form-name delayed-forms-map]
  (if (or *select-default-form* (not *user-id*))

    ;; Return default form and do nothing else
    (force (get delayed-forms-map default-form-name))

    (let [selection-mkey           (mkey test-name "selection" *user-id*)
          prior-selected-form-name (keyword (wcar (car/get selection-mkey)))

          try-select-form!
          (fn [form-name]
            (when-let [form (force (get delayed-forms-map form-name))]
              (wcar ; Refresh 2 hr selection stickiness, inc view counter
               (car/setex selection-mkey (* 2 60 60) (name form-name))
               (car/hincrby (mkey test-name "nviews") (name form-name) 1))
              form))]

      ;; Honour a valid pre-existint selection (for consistent user experience),
      ;; otherwise choose form with highest ucb1-score
      (or (try-select-form! prior-selected-form-name)
          (try-select-form!
           (last (sort-by #(ucb1-score test-name %) (keys delayed-forms-map))))))))

(comment (mab-select :landing.buttons.signup
                     :signup   "Signup!"
                     :join     "Join!"
                     :join-now "Join now!"))

(defn mab-commit!
  "Logs the occurrence of one or more events, each of which will contribute
  a specified value (positive or negative) to a named MAB test score.

      ;; On signup button click:
      (mab-commit! :landing.buttons.signup 1
                   :landing.title          1)

      ;; On buy button click:
      (mab-commit! :sale-price order-item-qty)

  There's great flexibility in this to model all kinds of single or
  multivariate test->event interactions. Any event can contribute to the
  score of any test, positively or negatively, to any extent.

  Keep things simple: resist the urge to get fancy with the spices."

  ([test-name value]
     (when (and *user-id* (not *select-default-form*))
       (if-let [selected-form-name
                (keyword (wcar (car/get (mkey test-name "selection"
                                              *user-id*))))]
         (wcar (car/hincrby (mkey test-name "scores") (name selected-form-name)
                            value)))))
  ([test-name value & name-value-pairs]
     (dorun (map (fn [[n v]] (mab-commit! n v))
                 (partition 2 (into [test-name value] name-value-pairs))))))

(comment (mab-commit! :landing.buttons.signup 1 :landing.title 1))

(defn pr-mab-results
  "Prints sorted MAB test results."
  ([test-name]
     (let [[nviews-map scores-map]
           (wcar (car/hgetall* (mkey test-name "nviews"))
                 (car/hgetall* (mkey test-name "scores")))

           nviews-sum (reduce + (map car/as-long   (vals nviews-map)))
           scores-sum (reduce + (map car/as-double (vals scores-map)))]

       (println "---")
       (println (str "MAB test " test-name " with " nviews-sum " total views and"
                     " a cumulative score of " scores-sum ":"))
       (println (->> (for [form-name (keys nviews-map)]
                       [(keyword form-name) (ucb1-score test-name form-name)])
                     (sort-by second)
                     reverse))))
  ([test-name & more] (dorun (map pr-mab-results (cons test-name more)))))

(comment (pr-mab-results :landing.buttons.signup :landing.title))

(comment
  (wcar (car/hgetall* (mkey :landing.buttons.signup "nviews")))

  (binding [*user-id* "foobaruser8"]
    (mab-select
     :landing.buttons.signup
     :red    "Red button"
     :blue   "Blue button"
     :green  "Green button"
     :yellow "Yellow button"))

  (binding [*user-id* "foobaruser8"]
    (mab-commit! :landing.buttons.signup 100)))