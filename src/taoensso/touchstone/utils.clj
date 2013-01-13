(ns taoensso.touchstone.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.tools.macro :as macro]))

(defmacro defonce*
  "Like `clojure.core/defonce` but supports optional docstring and attributes
  map for name symbol."
  {:arglists '([name expr])}
  [name & sigs]
  (let [[name [expr]] (macro/name-with-attributes name sigs)]
    `(clojure.core/defonce ~name ~expr)))

(defn distinct-by
  "Like `sort-by` for distinct. Based on clojure.core/distinct."
  [keyfn coll]
  (let [step (fn step [xs seen]
               (lazy-seq
                ((fn [[f :as xs] seen]
                   (when-let [s (seq xs)]
                     (let [keyfn-f (keyfn f)]
                       (if (contains? seen keyfn-f)
                         (recur (rest s) seen)
                         (cons f (step (rest s) (conj seen keyfn-f)))))))
                 xs seen)))]
    (step coll #{})))

(defn memoize-ttl
  "Like `memoize` but invalidates the cache for a set of arguments after TTL
  msecs has elapsed."
  [ttl f]
  (let [cache (atom {})]
    (fn [& args]
      (let [{:keys [time-cached d-result]} (@cache args)
            now (System/currentTimeMillis)]

        (if (and time-cached (< (- now time-cached) ttl))
          @d-result
          (let [d-result (delay (apply f args))]
            (swap! cache assoc args {:time-cached now :d-result d-result})
            @d-result))))))

(defn scoped-name
  "Like `name` but includes namespace in string when present."
  [x]
  (if (string? x) x
      (let [name (.getName ^clojure.lang.Named x)]
        (if-let [ns (.getNamespace ^clojure.lang.Named x)]
          (str ns "/" name)
          name))))

(comment (map scoped-name [:foo :foo/bar :foo.bar/baz])
         (time (dotimes [_ 10000] (name :foo)))
         (time (dotimes [_ 10000] (scoped-name :foo))))

(defn approx=
  [x y & {:keys [significance]
          :or   {significance 0.001}}]
  (< (- x y) significance))