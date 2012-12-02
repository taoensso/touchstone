(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * touchstone:<test-name>:nviews -> hash, {form-name views-count}
    * touchstone:<test-name>:scores -> hash, {form-name score}
    * touchstone:<test-name>:selection:<mab-subject-id> -> string, form-name

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [taoensso.carmine          :as car]
            [taoensso.touchstone.utils :as utils]))

;;;; Config & bindings

(defonce config (atom {:carmine {:pool (car/make-conn-pool)
                                 :spec (car/make-conn-spec)}}))

(defn set-config! [[k & ks] val] (swap! config assoc-in (cons k ks) val))

(defmacro ^:private wcar "With Carmine..."
  [& body]
  `(let [{pool# :pool spec# :spec} (@config :carmine)]
     (car/with-conn pool# spec# ~@body)))

(def ^:private ^:dynamic *mab-subject-id* nil)

(defmacro with-test-subject
  "Executes body (e.g. handling of a Ring web request) within the context of a
  thread-local binding for test-subject id. Ids are used to make selected
  testing forms \"sticky\", presenting a consistent user experience to each
  test subject during a particular testing session (+/- 2hrs).

  When nil/unspecified, subject will not participate in split-testing (useful
  for staff/bot web requests, etc.)."
  [id & body] `(binding [*mab-subject-id* (str ~id)] ~@body))

;;;;

(def ^:private tkey "Prefixed Touchstone key"
  (memoize (car/make-keyfn "touchstone")))

(declare ucb1-score*)

(defn- ucb1-score
  "Use \"UCB1\" formula to score a named MAB test form for selection sorting.

  UCB1 MAB provides a number of nice properties including:
    * Fire-and-forget capability.
    * Fast and accurate convergence.
    * Resilience to confounding factors over time.
    * Support for test-form hot-swapping.
    * Support for multivariate testing.

  Formula motivation: we want untested forms to be selected first and, more
  generally, the frequency of exploration to be inversly proportional to our
  confidence in the superiority of the leading form. This implies confidence in
  both relevant sample sizes, as well as the statistical significance of the
  difference between observed form scores."
  [test-name form-name]
  (let [[nviews-map score]
        (wcar (car/hgetall* (tkey test-name "nviews"))
              (car/hget     (tkey test-name "scores") (name form-name)))

        score      (or (car/as-double score) 0)
        nviews     (car/as-long (get nviews-map (name form-name) 0))
        nviews-sum (reduce + (map car/as-long (vals nviews-map)))]
    (ucb1-score* nviews-sum nviews score)))

(defn- ucb1-score* [N n score]
  (+ (/ score (max n 1)) (Math/sqrt (/ (* 2 (Math/log N)) (max n 1)))))

(comment
  ;; Confidence bound:
  (- (ucb1-score* 10  5   (* 0.5  5))    0.5) ; Reference: 0.9595
  (- (ucb1-score* 400 200 (* 0.5  200))  0.5) ; Reference: 0.2448
  (- (ucb1-score* 400 200 (* 0.5  200))  0.5) ; Reference: 0.2448
  (- (ucb1-score* 400 200 (* -0.5 200)) -0.5) ; Reference: 0.2448

  ;; Always select untested forms:
  (ucb1-score* 100 0 0) ; 3.035
  (ucb1-score* 100 2 0) ; 2.146
  (ucb1-score* 100 3 0) ; 1.752
  )

(def ^:private ucb1-select
  "Returns name of the given form with highest current \"UCB1\" score."
  (utils/memoize-ttl
   10000 ; 10 secs, for performance
   (fn [test-name form-names]
     (last (sort-by #(ucb1-score test-name %) form-names)))))

(declare mab-select*)

(defmacro mab-select
  "Defines a named MAB test that selects and evaluates one of the named testing
  forms using the \"UCB1\" selection algorithm.

      (mab-select :my-test-1
                  :my-form-1 \"String 1\"
                  :my-form-2 (do (Thread/sleep 2000) \"String 2\"))

  Test forms may be added or removed at any time, but avoid changing forms once
  named.

  Tests are fully composable for advanced testing: forms may contain other MAB
  [sub-]tests."
  [test-name & name-form-pairs]
  ;; To prevent caching of form eval, delay-map is regenerated for each call
  `(mab-select* ~test-name (utils/delay-map ~@name-form-pairs)))

(defn mab-select*
  [test-name delayed-forms-map]
  (let [get-form         (fn [form-name] (force (get delayed-forms-map form-name)))
        leading-form     (delay (ucb1-select test-name (keys delayed-forms-map)))]

    (if-not *mab-subject-id*
      (get-form @leading-form) ; Return leading form and do nothing else

      (let [selection-tkey           (tkey test-name "selection" *mab-subject-id*)
            prior-selected-form-name (keyword (wcar (car/get selection-tkey)))

            select-form! ; Return a form and select for testing
            (fn [form-name]
              (when-let [form (get-form form-name)]
                (wcar ; Refresh 2 hr selection stickiness, inc view counter
                 (car/setex selection-tkey (* 2 60 60) (name form-name))
                 (car/hincrby (tkey test-name "nviews") (name form-name) 1))
                form))]

        ;; Honour a recent, valid pre-existing selection (for consistent user
        ;; experience); otherwise select leading form for testing
        (or (select-form! prior-selected-form-name)
            (select-form! @leading-form))))))

(comment (mab-select :landing.buttons.sign-up
                     :sign-up  "Sign-up!"
                     :join     "Join!"
                     :join-now "Join now!"))

(defn mab-commit!
  "Records the occurrence of one or more events, each of which will contribute
  a specified value (-1 <= value <= 1) to a named MAB test score.

      ;; On sign-up button click:
      (mab-commit! :landing.buttons.sign-up 1
                   :landing.title           0.5)

      ;; On buy button click:
      (mab-commit! :sale-price (if (>= order-item-qty 2) 1 0.8))

  There's great flexibility in this to model all kinds of single or
  multivariate test->event interactions. Any event can contribute to the
  score of any test, positively or negatively, to any extent.

  The statistics can get complicated so try keep things simple: resist the urge
  to get fancy with the spices."
  ([test-name value] {:pre [(>= value -1) (<= value 1)]}
     (when *mab-subject-id*
       (when-let [selected-form-name
                  (keyword (wcar (car/get (tkey test-name "selection"
                                                *mab-subject-id*))))]
         (wcar (car/hincrbyfloat (tkey test-name "scores") (name selected-form-name)
                                 (str value))))))
  ([test-name value & name-value-pairs]
     (dorun (map (fn [[n v]] (mab-commit! n v))
                 (partition 2 (into [test-name value] name-value-pairs))))))

(comment (mab-commit! :landing.buttons.sign-up 1 :landing.title 1))

(defn pr-mab-results
  "Prints sorted MAB test results."
  ([test-name]
     (let [[nviews-map scores-map]
           (wcar (car/hgetall* (tkey test-name "nviews"))
                 (car/hgetall* (tkey test-name "scores")))

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

(comment (pr-mab-results :landing.buttons.sign-up :landing.title))

(comment (wcar (car/hgetall* (tkey :landing.buttons.sign-up "nviews"))
               (car/hgetall* (tkey :landing.buttons.sign-up "scores")))

  (with-test-subject "user1403"
    (mab-select
     :landing.buttons.sign-up
     :red    "Red button"
     :blue   "Blue button"
     :green  "Green button"
     :yellow "Yellow button"))

  (with-test-subject "user1403"
    (mab-commit! :landing.buttons.sign-up 1)))