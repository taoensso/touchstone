(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. More
  effective+convenient than traditional A/B testing.

  Redis keys:
    * touchstone:<test-id>:nprospects -> hash, {form-id count}
    * touchstone:<test-id>:scores     -> hash, {form-id score}
    * touchstone:<test-id>:<ts-id>:selection  -> ttl string, form-id
    * touchstone:<test-id>:<ts-id>:committed? -> ttl flag

  General strategy, Ref.
    http://goo.gl/XPlP6 (UCB1 MAB algo)
    http://en.wikipedia.org/wiki/Multi-armed_bandit
    http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [clojure.string             :as str]
            [clojure.math.combinatorics :as combo]
            [taoensso.encore            :as enc :refer (have have-in)]
            [taoensso.carmine           :as car :refer (wcar)]))

;;;; Encore version check

#+clj
(let [min-encore-version 1.21] ; Let's get folks on newer versions here
  (if-let [assert! (ns-resolve 'taoensso.encore 'assert-min-encore-version)]
    (assert! min-encore-version)
    (throw
      (ex-info
        (format
          "Insufficient com.taoensso/encore version (< %s). You may have a Leiningen dependency conflict (see http://goo.gl/qBbLvC for solution)."
          min-encore-version)
        {:min-version min-encore-version}))))

;;;; Bindings, etc.

(def ^:private tkey (memoize (partial car/key :touchstone)))
(def ^:dynamic *ts-id* "Test subject id" nil)
(defmacro with-test-subject [id & body] `(binding [*ts-id* ~id] ~@body))

;;;; Low-level form selection

(defn- select-form!*
  [{:keys [conn-opts ttl-ms non-uniques?]
    :or   {ttl-ms (enc/ms :hours 2)}
    :as   config}
   strategy-fn ts-id test-id form-fns-map]
  (let [valid-form?   (fn [form-id] (and form-id (contains? form-fns-map form-id)))
        get-form      (fn [form-id] ((get form-fns-map form-id)))
        leading-form_ (delay (strategy-fn config test-id (keys form-fns-map)))]

    (if-not ts-id
      (get-form @leading-form_) ; Return leading form and do nothing else
      (let [selection-tkey         (tkey test-id ts-id :selection)
            prior-selected-form-id (keyword (wcar conn-opts (car/get selection-tkey)))
            select-and-return-form!
            (fn [form-id]
              (let [form (get-form form-id)]
                (wcar conn-opts
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
          (select-and-return-form! @leading-form_))))))

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
  [{:keys [conn-opts]} ts-id test-id]
  (keyword (wcar conn-opts (car/get (tkey test-id ts-id :selection)))))

;;;; Selection strategies

(defn- random-select "Simple A/B-style random selection."
  [_ test-id form-ids] (rand-nth form-ids))

(def ^:private low-n-select
  "Returns id of a form with lowest number of prospects (possibly zero)."
  (enc/memoize* (enc/ms :secs 5)
    (fn [{:keys [conn-opts]} test-id form-ids]
      (let [nprospects-map ; {<test-id> <nprospects>}
            (wcar conn-opts
              (-> (car/hgetall (tkey test-id :nprospects))
                  (car/parse-map :keywordize (fn [_ v] (enc/as-?int v)))))]
        ;; `top` would be O(N.log1) vs O(N.logN) but N is usu. small here:
        (first (sort-by #(get nprospects-map % 0) form-ids))))))

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
  [{:keys [conn-opts]} test-id form-id]
  (let [[score nprospects-map]
        (wcar conn-opts
          (car/hget        (tkey test-id :scores) form-id)
          (-> (car/hgetall (tkey test-id :nprospects))
              (car/parse-map :keywordize (fn [_ v] (enc/as-?int v)))))
        score       (or (enc/as-?float score) 0)
        nprospects  (have (enc/as-?int (get nprospects-map form-id 0)))
        nprosps-sum (reduce + (vals nprospects-map))]
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
  (enc/memoize* (enc/ms :secs 5)
    (fn [config test-id form-ids]
      (first (sort-by #(ucb1-score config test-id %) enc/rcompare form-ids)))))

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
  ([{:keys [conn-opts ttl-ms non-uniques?]
     :or   {ttl-ms (enc/ms :hours 2)}
     :as   config}
    ts-id test-id value]
   {:pre [(>= value -1) (<= value 1)]}
   (when ts-id
     (let [committed?-tkey (tkey test-id ts-id :committed?)]
       (when (or non-uniques?
                 (= (wcar conn-opts (car/exists committed?-tkey)) 0))
         (when-let [selected-form-id (selected-form-id config ts-id test-id)]
           (wcar conn-opts
             ;; Count commit value toward score
             (car/hincrbyfloat (tkey test-id :scores) selected-form-id value)

             ;; Mark test as committed for this subject's test-session
             (car/psetex committed?-tkey ttl-ms 1)))))))

  ([config ts-id test-id value & id-value-pairs]
   (doseq [[id v] (partition 2 (into [test-id value] id-value-pairs))]
     (commit! config ts-id id v))))

;;;; Reporting

(defn print-results
  ([{:keys [conn-opts] :as config} test-id]
   (let [[nprospects-map scores-map]
         (wcar conn-opts
           (-> (car/hgetall (tkey test-id :nprospects))
               (car/parse-map :keywordize (fn [_ v] (enc/as-?int v))))
           (-> (car/hgetall (tkey test-id :scores))
               (car/parse-map :keywordize (fn [_ v] (enc/as-?float v)))))

         nprosps-sum (reduce + (vals nprospects-map))
         scores-sum  (reduce + (vals scores-map))
         output
         (format "Touchstone %s results
---
Total prospects: %s, score: %s
[form-id ucb1-score [nprospects score]]:
%s

"
           test-id nprosps-sum (enc/round2 scores-sum)
           (->> (for [form-id (keys nprospects-map)]
                  [(keyword form-id)
                   (enc/round2 (ucb1-score config test-id form-id))
                   [            (enc/as-?int   (nprospects-map form-id 0))
                    (enc/round2 (enc/as-?float (scores-map     form-id 0)))]])
             (sort-by second enc/rcompare) (vec)))]
     (println output)))

  ([config test-id & more]
   (doseq [n (cons test-id more)]
     (print-results config n))))

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
                       (partial enc/distinct-by (partial take take-n)))

        permutations (map vec (take-n (combo/permutations ordered-forms)))
        ids          (map #(keyword (str "form-" (str/join "-" %)))
                          (take-n (combo/permutations
                                   (range (count ordered-forms)))))

        pairs (interleave ids permutations)]
    `(mab-select ~config ~ts-id ~test-id ~@pairs)))

;;;; Tests, etc.

(comment
  (wcar {} (car/hgetall (tkey :touchstone1 :nprospects))
           (car/hgetall (tkey :touchstone2 :scores)))

  (print-results {} :touchstone1 :touchstone2)
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

(defn- test-tkeys [{:keys [conn-opts] :as config} test-id]
  (when test-id (wcar conn-opts (car/keys (tkey test-id :*)))))

(defn delete-test [{:keys [conn-opts] :as config} test-id]
  (when-let [tkeys (seq (test-tkeys config test-id))]
    (wcar conn-opts (apply car/del tkeys))))

(defn move-test [{:keys [conn-opts] :as config} old-id new-id]
  (when-let [old-tkeys (seq (test-tkeys config old-id))]
    (let [new-tkeys (mapv #(str/replace % (re-pattern (str "^" (tkey old-id)))
                             (tkey new-id)) old-tkeys)]
      (wcar conn-opts (mapv car/renamenx old-tkeys new-tkeys)))))

(comment
  (test-tkeys  {} :touchstone1)
  (delete-test {} :touchstone1)
  (move-test   {} :touchstone1  :touchstone1b)
  (move-test   {} :touchstone1b :touchstone1))

;;;; Ring middleware

(defn bot-user-agent? "Simple test for honest bots."
  [ring-request-headers]
  (->> (get ring-request-headers "user-agent" "")
       (str/lower-case)
       (re-find
         #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)")
       first))

(comment (bot-user-agent? {"user-agent" "GoogleBot"}))

(defn ring-wrap-test-subject-id
  "Ring middleware that generates, sessionizes, and binds a test-subject id for
  requests eligible for split-testing (by default this excludes clients that
  report themselves as bots)."
  [ring-handler & [wrap-pred]]
  (let [wrap-pred (if-not wrap-pred
                    (fn [req] (not (bot-user-agent? (:headers req))))
                    wrap-pred)]

    (fn [ring-req]
      (if-not (wrap-pred ring-req)
        (ring-handler ring-req)

        (if-let [ts-id-entry (find (:session ring-req) :ts-id)] ; May be nil
          (let [sessionized-id (val ts-id-entry)]
            (with-test-subject sessionized-id
              (ring-handler (assoc ring-req :ts-id sessionized-id))))

          (let [new-id   (rand-int Integer/MAX_VALUE)
                response (with-test-subject new-id
                           (ring-handler (assoc ring-req :ts-id new-id)))]
            (enc/session-swap ring-req response assoc :ts-id new-id)))))))

;;;; Client (ClojureScript)

;; TODO
