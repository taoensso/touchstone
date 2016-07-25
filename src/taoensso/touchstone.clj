(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * touchstone:<test-id>:nprospects -> hash, {form-id count}
    * touchstone:<test-id>:scores     -> hash, {form-id score}
    * touchstone:<test-id>:<ts-id>:selection  -> ttl string, form-id
    * touchstone:<test-id>:<ts-id>:committed? -> ttl flag

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [clojure.string             :as str]
            [clojure.math.combinatorics :as combo]
            [taoensso.encore            :as enc]
            [taoensso.carmine           :as car :refer (wcar)]
            [taoensso.touchstone.utils  :as utils]))

(if (vector? enc/encore-version)
  (enc/assert-min-encore-version [2 67 2])
  (enc/assert-min-encore-version  2.67))

;; TODO `conn`->`conn-opts` for consistency with Carmine v3

;;;; Bindings, etc.

(def ^:dynamic *ts-id* "Test subject id" nil)
(defmacro with-test-subject
  "Executes body within the context of thread-local test-subject-id binding."
  [id & body] `(binding [*ts-id* ~id] ~@body))

(def ^:private tkey (memoize (partial car/key :touchstone)))

;;;; Low-level form selection

(defn- select-form!*
  [{:keys [conn ttl-ms non-uniques?] :or {ttl-ms (* 1000 60 60 2)} :as config}
   strategy-fn ts-id test-id form-fns-map]
  (let [valid-form?  (fn [form-id] (and form-id (contains? form-fns-map form-id)))
        get-form     (fn [form-id] ((get form-fns-map form-id)))
        leading-form (delay (strategy-fn config test-id (keys form-fns-map)))]

    (if-not ts-id (get-form @leading-form) ; Return leading form and do nothing else
      (let [selection-tkey         (tkey test-id ts-id :selection)
            prior-selected-form-id (keyword (wcar conn (car/get selection-tkey)))
            select-and-return-form!
            (fn [form-id]
              (let [form (get-form form-id)]
                (wcar conn
                 ;;; Refresh test-session ttl
                 (car/psetex selection-tkey ttl-ms form-id)
                 (car/pexpire (tkey test-id ts-id :committed?) ttl-ms)

                 ;;; Count selection as prospect
                 (when (or non-uniques? (not prior-selected-form-id))
                   (car/hincrby (tkey test-id :nprospects) form-id 1)))
                form))]

        ;; Selections are sticky: honour a recent, valid pre-existing selection
        ;; (for consistent user experience); otherwise select leading form
        (if (valid-form? prior-selected-form-id)
          (select-and-return-form! prior-selected-form-id)
          (select-and-return-form! @leading-form))))))

(defmacro select-form! "Implementation detail."
  [config strategy-fn ts-id test-id id-form-pairs]
  (assert (even? (count id-form-pairs)))
  (let [id-form-fn-pairs (into {} (for [[id f] (partition 2 id-form-pairs)]
                                    [id (list 'fn [] f)]))]
    `(#'select-form!* ~config ~strategy-fn ~ts-id ~test-id ~id-form-fn-pairs)))

(defn selected-form-id
  "Returns subject's currently selected form id for test, or nil. One common
  idiom is to use this for creating dependent tests:
      (case (selected-form-id {} *ts-id* :my-test-1)
        :my-form-1 (mab-select {} *ts-id* :my-test-1a ...)
        :my-form-2 (mab-select {} *ts-id* :my-test-1b ...)
        nil)"
  [{:keys [conn]} ts-id test-id]
  (keyword (wcar conn (car/get (tkey test-id ts-id :selection)))))

;;;; Selection strategies

(defn- kvs->kw-map [kvs] (enc/reduce-kvs (fn [m k v] (assoc m (keyword k) v)) {} kvs))
(defn- hgetall*-kw [k] (car/parse kvs->kw-map (car/hgetall k)))

(comment (kvs->kw-map ["a" "A" "b" "B"]))

(defn- random-select "Simple A/B-style random selection."
  [_ test-id form-ids] (rand-nth form-ids))

(def ^:private low-n-select
  "Returns id of a form with lowest number of prospects (possibly zero)."
  (enc/memoize* 5000
    (fn [{:keys [conn]} test-id form-ids]
      (let [nprospects-map (wcar conn (hgetall*-kw (tkey test-id :nprospects)))]
        (first (sort-by #(car/as-int (get nprospects-map % 0)) form-ids))))))

(defn- ucb1-score* [N n score]
  (+ (/ score (max n 1)) (Math/sqrt (/ (* 2 (Math/log N)) (max n 1)))))

(defn- ucb1-score
  "Uses \"UCB1\" formula to score a test form for selection sorting.

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
  [{:keys [conn]} test-id form-id]
  (let [[nprospects-map score]
        (wcar conn (hgetall*-kw  (tkey test-id :nprospects))
                   (car/hget     (tkey test-id :scores) form-id))

        score       (or (car/as-float score) 0)
        nprospects  (car/as-int (get nprospects-map form-id 0))
        nprosps-sum (reduce + (map car/as-int (vals nprospects-map)))]
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
  "Returns id of a given form with highest current \"UCB1\" score."
  (enc/memoize* 5000 ; 5s cache for performance
   (fn [config test-id form-ids]
     (last (sort-by #(ucb1-score config test-id %) form-ids)))))

;;;; Commits

(defn commit!
  "Signals the occurrence of one or more events, each of which may contribute
  a specified value (-1 <= value <= 1) to a test score:

      ;; On sign-up button click:
      (commit! {} *ts-id* :my-app.landing.buttons/sign-up 1
                          :my-app.landing/title           0.5)

      ;; On buy button click:
      (commit! {} *ts-id* :my-app/sale-price (if (>= order-item-qty 2) 1 0.8))

  There's great flexibility in this to model all kinds of single or multivariate
  test->event interactions. Any event can contribute to the score of any test,
  positively or negatively, to any extent.

  The statistics can get complicated so try keep things simple: resist the urge
  to get fancy with the spices."
  ([{:keys [conn ttl-ms non-uniques?] :or {ttl-ms (* 1000 60 60 2)} :as config}
    ts-id test-id value] {:pre [(>= value -1) (<= value 1)]}
    (when ts-id
      (let [committed?-tkey (tkey test-id ts-id :committed?)]
        (when (or non-uniques?
                  (not (car/as-bool (wcar conn (car/exists committed?-tkey)))))
          (when-let [selected-form-id (selected-form-id config ts-id test-id)]
            (wcar conn
             ;; Count commit value toward score
             (car/hincrbyfloat (tkey test-id :scores) selected-form-id value)

             ;; Mark test as committed for this subject's test-session
             (car/psetex committed?-tkey ttl-ms 1)))))))

  ([config ts-id test-id value & id-value-pairs]
     (doseq [[id v] (partition 2 (into [test-id value] id-value-pairs))]
       (commit! config ts-id id v))))

;;;; Reporting

(defn pr-results
  ([{:keys [conn] :as config} test-id]
     (let [[nprospects-map scores-map]
           (wcar conn (hgetall*-kw (tkey test-id :nprospects))
                      (hgetall*-kw (tkey test-id :scores)))
           nprosps-sum (reduce + (map car/as-int   (vals nprospects-map)))
           scores-sum  (reduce + (map car/as-float (vals scores-map)))
           round       enc/round2
           output
           (str "\nTouchstone " test-id " results\n"
                "-----------------------\n"
                "Total prospects: " nprosps-sum ", score: " (round scores-sum)
                "\n[form-id ucb1-score [nprospects score]]:\n"
                (->> (for [form-id (keys nprospects-map)]
                       [(keyword form-id)
                        (round (ucb1-score config test-id form-id))
                        [(car/as-int          (nprospects-map form-id 0))
                         (round (car/as-float (scores-map     form-id 0)))]])
                     (sort-by second) (reverse) (vec)) "\n")]

       (println output)))
  ([config test-id & more] (doseq [n (cons test-id more)] (pr-results config n))))

;;;; Selection wrappers

(defmacro mab-select
  "Defines a test that selects and evaluates one of the testing forms using the
 \"UCB1\" selection algorithm:
    (mab-select {} *ts-id* :my-test-1
                :my-form-1 \"String 1\"
                :my-form-2 (do (Thread/sleep 2000) \"String 2\"))

  Dependent tests can be created through composition:
    (mab-select {} *ts-id* :my-test-1
                :my-form-1 \"String 1\"
                :my-form-2 (mab-select {} *ts-id* :my-test-1a ...))

  Test forms can be freely added, reordered, or removed for an ongoing test at
  any time, but avoid changing a particular form once id'd."
  [config ts-id test-id & id-form-pairs]
  `(select-form! ~config ~#'ucb1-select ~ts-id ~test-id ~id-form-pairs))

(defmacro ab-select
  "Like `mab-select` but uses simple, A/B-style random selection. Unless you
  know all the implications, you probably want `mab-select` instead."
  [config ts-id test-id & id-form-pairs]
  `(select-form! ~config ~#'random-select ~ts-id ~test-id ~id-form-pairs))

(defmacro mab-select-id
  "Like `mab-select` but takes only form ids and uses each id also as its form."
  [config ts-id test-id & ids]
  (let [pairs (interleave ids ids)] `(mab-select ~config ~ts-id ~test-id ~@pairs)))

(defmacro mab-select-ordered
  "Like `mab-select` but takes un-id'd forms and automatically ids them by their
  order: :form-1, :form-2, ...."
  [config ts-id test-id & ordered-forms]
  (let [ids   (map #(keyword (str "form-" %)) (range))
        pairs (interleave ids ordered-forms)]
    `(mab-select ~config ~ts-id ~test-id ~@pairs)))

(defn- distinct-by [keyfn coll]
    (let [step (fn step [xs seen]
                 (lazy-seq
                   ((fn [[v :as xs] seen]
                      (when-let [s (seq xs)]
                        (let [v* (keyfn v)]
                          (if (contains? seen v*)
                            (recur (rest s) seen)
                            (cons v (step (rest s) (conj seen v*)))))))
                    xs seen)))]
      (step coll #{})))

(defmacro mab-select-permutations
  "Advanced. Defines a positional test with N!/(N-n)! testing forms. Each
  testing form will be a vector permutation of the given `ordered-forms`,
  automatically id'd for the order of its constituent forms.

  Useful for testing the order of the first n forms out of N. The remaining
  forms will retain their natural order."
  [config ts-id test-id take-n & ordered-forms]
  (let [N (count ordered-forms) n take-n] ; O(n!) kills puppies
    (assert (<= (reduce * (range (inc (- N n)) (inc N))) 24)))
  (let [take-n (if-not take-n identity
                       (partial distinct-by (partial take take-n)))

        permutations (map vec (take-n (combo/permutations ordered-forms)))
        ids          (map #(keyword (str "form-" (str/join "-" %)))
                          (take-n (combo/permutations
                                   (range (count ordered-forms)))))

        pairs (interleave ids permutations)]
    `(mab-select ~config ~ts-id ~test-id ~@pairs)))

;;;; Tests, etc.

(comment
  (wcar {} (hgetall*-kw (tkey :touchstone1 :nprospects))
           (hgetall*-kw (tkey :touchstone2 :scores)))

  (pr-results {} :touchstone1 :touchstone2)

  (mab-select {} 147 :touchstone1
    :red    "Red"
    :blue   "Blue"
    :green  "Green"
    :yellow "Yellow")

  (mab-select-id      {} 142 :touchstone2 :a :b :c)
  (mab-select-ordered {} 200 :touchstone2 "first form" "second" "third")
  (mab-select-permutations {} 142 :touchstone3 2 :a :b :c)

  (selected-form-id {} 142 :touchstone1)
  (selected-form-id {} 200 :touchstone2)

  (commit! {} 200 :touchstone1 1 :touchstone2 1))

;;;; Admin

(defn- test-tkeys [{:keys [conn]} test-id]
  (when test-id (wcar conn (car/keys (tkey test-id :*)))))

(defn delete-test [{:keys [conn]} test-id]
  (when-let [tkeys (seq (test-tkeys test-id))]
    (wcar conn (apply car/del tkeys))))

(defn move-test [{:keys [conn]} old-id new-id]
  (when-let [old-tkeys (seq (test-tkeys old-id))]
    (let [new-tkeys (mapv #(str/replace % (re-pattern (str "^" (tkey old-id)))
                                        (tkey new-id)) old-tkeys)]
      (wcar conn (mapv car/renamenx old-tkeys new-tkeys)))))

(comment (test-tkeys  {} :touchstone1)
         (delete-test {} :touchstone1)
         (move-test   {} :touchstone1  :touchstone1b)
         (move-test   {} :touchstone1b :touchstone1))
