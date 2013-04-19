(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * touchstone:<test-name>:nprospects -> hash, {form-name count}
    * touchstone:<test-name>:scores     -> hash, {form-name score}
    * touchstone:<test-name>:<mab-subject-id>:selection  -> ttl string, form-name
    * touchstone:<test-name>:<mab-subject-id>:committed? -> ttl flag

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [clojure.string             :as str]
            [clojure.math.combinatorics :as combo]
            [taoensso.carmine           :as car])
  (:use     [taoensso.touchstone.utils :as utils :only (scoped-name)]))

;;;; TODO
;; * Per-test config inheritence (namespaces? :test-profiles?
;;   with-test-config?). Bindings are ugly since they need to happen (in sync)
;;   for both selects and commits.
;; * Test commit & reporting groups?

;;;; Config & bindings

(utils/defonce* config
  "Alpha - subject to change.
  This map atom controls everything about the way Touchstone operates.
  See source code for details."
  (atom {:carmine {:pool (car/make-conn-pool)
                   :spec (car/make-conn-spec)}
         :tests {:default {:test-session-ttl 7200 ; Last activity +2hrs
                           ;; Turn on for engagement testing:
                           :count-duplicate-activity? false}}}))

(defn set-config! [ks val] (swap! config assoc-in ks val))

(defn test-config "Returns per-test config, merged over defaults."
  [test-name]
  (let [tests-config (:tests @config)]
    (merge (:default tests-config) (get tests-config test-name))))

(comment (test-config :my-app/landing.buttons.sign-up))

(defmacro ^:private wcar "With Carmine..."
  [& body]
  `(let [{pool# :pool spec# :spec} (@config :carmine)]
     (car/with-conn pool# spec# ~@body)))

(def ^:private tkey "Prefixed Touchstone key"
  (memoize (partial car/kname "touchstone")))

(def ^:dynamic *mab-subject-id* nil)

(defmacro with-test-subject
  "Executes body (e.g. handling of a Ring web request) within the context of a
  thread-local binding for test-subject id. When nil (default), subject will not
  participate in split-testing (useful for staff/bot web requests, etc.)."
  [id & body] `(binding [*mab-subject-id* (str ~id)] ~@body))

;;;; Low-level form selection

(defn- select-form!*
  [strategy-fn test-name form-fns-map]
  (let [valid-form?  (fn [form-name] (and form-name
                                         (contains? form-fns-map form-name)))
        get-form     (fn [form-name] ((get form-fns-map form-name)))
        leading-form (delay (strategy-fn test-name (keys form-fns-map)))]

    (if-not *mab-subject-id*
      (get-form @leading-form) ; Return leading form and do nothing else

      (let [selection-tkey           (tkey test-name *mab-subject-id* "selection")
            prior-selected-form-name (keyword (wcar (car/get selection-tkey)))

            select-form! ; Select and return form
            (fn [form-name]
              (let [form (get-form form-name)
                    ttl  (:test-session-ttl (test-config test-name))]
                (wcar
                 ;; Refresh test-session ttl
                 (car/setex selection-tkey ttl (scoped-name form-name))
                 (car/expire (tkey test-name *mab-subject-id* "committed?") ttl)

                 ;; Count selection as prospect
                 (when (or (:count-duplicate-activity? (test-config test-name))
                           (not prior-selected-form-name) ; New selection
                           )
                   (car/hincrby (tkey test-name "nprospects")
                                (scoped-name form-name) 1)))
                form))]

        ;; Selections are sticky: honour a recent, valid pre-existing selection
        ;; (for consistent user experience); otherwise select leading form
        (if (valid-form? prior-selected-form-name)
          (select-form! prior-selected-form-name)
          (select-form! @leading-form))))))

(defmacro select-form!
  [strategy-fn test-name name-form-pairs]
  (assert (even? (count name-form-pairs)))
  (let [name-form-fn-pairs (into {} (for [[n f] (partition 2 name-form-pairs)]
                                      [n (list 'fn [] f)]))]
    `(#'select-form!* ~strategy-fn ~test-name ~name-form-fn-pairs)))

(defn selected-form-name
  "Returns subject's currently selected form name for test, or nil. One common
  idiom is to use this for creating dependent tests:
      (case (selected-form-name :my-test-1)
        :my-form-1 (mab-select :my-test-1a ...)
        :my-form-2 (mab-select :my-test-1b ...)
        nil)"
  [test-name & [mab-subject-id]]
  (keyword (wcar (car/get (tkey test-name (or mab-subject-id *mab-subject-id*)
                                "selection")))))

(comment (selected-form-name :my-app/landing.buttons.sign-up "user1403"))

;;;; Selection strategies

(defn- random-select
  "Simple A/B-style random selection."
  [test-name form-names] (rand-nth form-names))

(def ^:private low-n-select
  "Returns name of a form with lowest number of prospects (possibly zero)."
  (utils/memoize-ttl 5000
    (fn [test-name form-names]
      (let [nprospects-map (wcar (car/hgetall* (tkey test-name "nprospects")))]
        (first (sort-by #(car/as-long (get nprospects-map (scoped-name %) 0))
                        form-names))))))

(defn- ucb1-score* [N n score]
  (+ (/ score (max n 1)) (Math/sqrt (/ (* 2 (Math/log N)) (max n 1)))))

(defn- ucb1-score
  "Uses \"UCB1\" formula to score a named MAB test form for selection sorting.

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
  (let [[nprospects-map score]
        (wcar (car/hgetall* (tkey test-name "nprospects"))
              (car/hget     (tkey test-name "scores") (scoped-name form-name)))

        score       (or (car/as-double score) 0)
        nprospects  (car/as-long (get nprospects-map (scoped-name form-name) 0))
        nprosps-sum (reduce + (map car/as-long (vals nprospects-map)))]
    (ucb1-score* nprosps-sum nprospects score)))

(comment
  (defn- ucb1-conf [N n mean] (- (ucb1-score* N n (* n mean)) mean))
  (ucb1-conf 10  5    0.5) ; Ref 0.9595
  (ucb1-conf 400 200  0.5) ; Ref 0.2448
  (ucb1-conf 400 200 -0.5) ; Ref 0.2448

  ;; Always select untested forms:
  (ucb1-conf 100 0 0) ; Ref 3.0349
  (ucb1-conf 100 2 0) ; Ref 2.1460
  (ucb1-conf 100 5 0) ; Ref 1.3572
  )

(def ^:private ucb1-select
  "Returns name of the given form with highest current \"UCB1\" score."
  (utils/memoize-ttl 5000 ; 5s cache for performance
   (fn [test-name form-names]
     (last (sort-by #(ucb1-score test-name %) form-names)))))

;;;; Commits

(defn mab-commit!
  "Indicates the occurrence of one or more events, each of which may contribute
  a specified value (-1 <= value <= 1) to a named MAB test score:

      ;; On sign-up button click:
      (mab-commit! :my-app/landing.buttons.sign-up 1
                   :my-app/landing.title           0.5)

      ;; On buy button click:
      (mab-commit! :my-app/sale-price (if (>= order-item-qty 2) 1 0.8))

  There's great flexibility in this to model all kinds of single or
  multivariate test->event interactions. Any event can contribute to the
  score of any test, positively or negatively, to any extent.

  The statistics can get complicated so try keep things simple: resist the urge
  to get fancy with the spices."
  ([test-name value] {:pre [(>= value -1) (<= value 1)]}
     (when *mab-subject-id*
       (let [committed?-tkey (tkey test-name *mab-subject-id* "committed?")]
         (when (or (:count-duplicate-activity? (test-config test-name))
                   (not (car/as-bool (wcar (car/exists committed?-tkey)))))
           (when-let [selected-form-name (selected-form-name test-name)]
             (wcar
              ;; Count commit value toward score
              (car/hincrbyfloat (tkey test-name "scores")
                                (scoped-name selected-form-name)
                                (str value))

              ;; Mark test as committed for this subject's test-session
              (car/setex committed?-tkey
                         (:test-session-ttl (test-config test-name)) 1)))))))
  ([test-name value & name-value-pairs]
     (dorun (map (fn [[n v]] (mab-commit! n v))
                 (partition 2 (into [test-name value] name-value-pairs))))))

(comment (mab-commit! :my-app/landing.buttons.sign-up 1
                      :my-app/landing.title 1))

;;;; Reporting

(defn pr-mab-results
  "Prints sorted MAB test results."
  ([test-name]
     (let [[nprospects-map scores-map]
           (wcar (car/hgetall* (tkey test-name "nprospects"))
                 (car/hgetall* (tkey test-name "scores")))

           nprosps-sum (reduce + (map car/as-long   (vals nprospects-map)))
           scores-sum  (reduce + (map car/as-double (vals scores-map)))]

       (println "---")
       (println (str "MAB test " test-name " with " nprosps-sum " total prospects"
                     " and a cumulative score of " scores-sum ", "
                     "[form-name ucb1-score nprospects score]:"))
       (println (->> (for [form-name (keys nprospects-map)]
                       [(keyword form-name) (ucb1-score test-name form-name)
                        (nprospects-map form-name 0) (scores-map form-name 0)])
                     (sort-by second)
                     reverse))))
  ([test-name & more] (dorun (map pr-mab-results (cons test-name more)))))

(comment

  (wcar (car/hgetall* (tkey :my-app/landing.buttons.sign-up "nprospects"))
        (car/hgetall* (tkey :my-app/landing.buttons.sign-up "scores")))

  (pr-mab-results :my-app/landing.buttons.sign-up
                  :my-app/landing.title)

  (with-test-subject "user1403"
    (mab-select
     :my-app/landing.buttons.sign-up
     :red    (do (println "RED")    "Red button")
     :blue   (do (println "BLUE")   "Blue button")
     :green  (do (println "GREEN")  "Green button")
     :yellow (do (println "YELLOW") "Yellow button")))

  (with-test-subject "user1403"
    (mab-commit! :my-app/landing.buttons.sign-up 1)))

;;;; Selection wrappers

(defmacro mab-select
  "Defines a named MAB test that selects and evaluates one of the named testing
  forms using the \"UCB1\" selection algorithm:
      (mab-select :my-test-1
                  :my-form-1 \"String 1\"
                  :my-form-2 (do (Thread/sleep 2000) \"String 2\"))

  Dependent tests can be created through composition:
      (mab-select :my-test-1
                  :my-form-1 \"String 1\"
                  :my-form-2 (mab-select :my-test-1a ...))

  Test forms can be freely added, reordered, or removed for an ongoing test at
  any time, but avoid changing a particular form once named."
  [test-name & name-form-pairs]
  `(select-form! #'ucb1-select ~test-name ~name-form-pairs))

(comment (mab-select :my-app/landing.buttons.sign-up
                     :sign-up  "Sign-up!"
                     :join     "Join!"
                     :join-now "Join now!"))

(defmacro ab-select
  "Like `mab-select` but uses simple, A/B-style random selection. Unless you
  know all the implications, you probably want `mab-select` instead."
  [test-name & name-form-pairs]
  `(select-form! #'random-select ~test-name ~name-form-pairs))

(defmacro mab-select-name
  "Like `mab-select` but takes only form names and uses each name also as its
  form."
  [test-name & names]
  (let [pairs (interleave names names)]
    `(mab-select ~test-name ~@pairs)))

(comment (mab-select-name :my-name-test :a :b :c))

(defmacro mab-select-ordered
  "Like `mab-select` but takes unnamed forms and automatically names them by
  their order: :form-1, :form-2, ...."
  [test-name & ordered-forms]
  (let [names (map #(keyword (str "form-" %)) (range))
        pairs (interleave names ordered-forms)]
    `(mab-select ~test-name ~@pairs)))

(comment (mab-select-ordered :my-ordered-test :a :b :c))

(defmacro mab-select-permutations
  "Advanced. Defines a positional MAB test with N!/(N-n)! testing forms. Each
  testing form will be a vector permutation of the given `ordered-forms`,
  automatically named for the order of its constituent forms.

  Useful for testing the order of the first n forms out of N. The remaining
  forms will retain their natural order."
  [test-name take-n & ordered-forms]
  (let [N (count ordered-forms) n take-n] ; O(n!) kills puppies
    (assert (<= (reduce * (range (inc (- N n)) (inc N))) 24)))
  (let [take-n (if-not take-n identity
                       (partial utils/distinct-by (partial take take-n)))

        permutations (map vec (take-n (combo/permutations ordered-forms)))
        names        (map #(keyword (str "form-" (str/join "-" %)))
                          (take-n (combo/permutations
                                   (range (count ordered-forms)))))

        pairs (interleave names permutations)]
    ;;`(println ~test-name ~@pairs)
    `(mab-select ~test-name ~@pairs)))

(comment (mab-select-permutations :my-permutations-test 1 :a :b :c))

;;;; Admin

(defn- test-tkeys [test-name]
  (when test-name (wcar (car/keys (tkey test-name "*")))))

(defn delete-test [test-name]
  (when-let [tkeys (seq (test-tkeys test-name))]
    (wcar (apply car/del tkeys))))

(defn rename-test [old-name new-name]
  (when-let [old-tkeys (seq (test-tkeys old-name))]
    (let [new-tkeys (map #(str/replace % (re-pattern (str "^" (tkey old-name)))
                                       (tkey new-name)) old-tkeys)]
      (wcar (doall (map car/renamenx old-tkeys new-tkeys))))))

(comment (test-tkeys  :my-app/landing.buttons.sign-up)
         (delete-test :my-app/landing.buttons.sign-up)
         (rename-test :my-app/landing.buttons.sign-up :my-app/foobar)
         (rename-test :my-app/foobar :my-app/landing.buttons.sign-up))