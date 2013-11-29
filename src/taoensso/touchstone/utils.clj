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

(defn memoize-ttl "Low-overhead, common-case `memoize*`."
  [ttl-ms f]
  (let [cache (atom {})]
    (fn [& args]
      (when (<= (rand) 0.001) ; GC
        (let [instant (System/currentTimeMillis)]
          (swap! cache
            (fn [m] (reduce-kv (fn [m* k [dv udt :as cv]]
                                (if (> (- instant udt) ttl-ms) m*
                                    (assoc m* k cv))) {} m)))))
      (let [[dv udt] (@cache args)]
        (if (and dv (< (- (System/currentTimeMillis) udt) ttl-ms)) @dv
          (locking cache ; For thread racing
            (let [[dv udt] (@cache args)] ; Retry after lock acquisition!
              (if (and dv (< (- (System/currentTimeMillis) udt) ttl-ms)) @dv
                (let [dv (delay (apply f args))
                      cv [dv (System/currentTimeMillis)]]
                  (swap! cache assoc args cv)
                  @dv)))))))))

(defn round-to
  "Rounds argument to given number of decimal places."
  [places x]
  (if (zero? places)
    (Math/round (double x))
    (let [modifier (Math/pow 10.0 places)]
      (/ (Math/round (* x modifier)) modifier))))

(comment (round-to 0 10)
         (round-to 2 10.123))
