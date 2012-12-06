(ns test-touchstone.main
  (:use [clojure.test])
  (:require [taoensso.touchstone      :as touchstone]
            [taoensso.carmine         :as car]
            [taoensso.touchstone.ring :as ring])
  (:use     [taoensso.touchstone.utils :as utils :only (scoped-name)]))

;;;; Setup

(def p (car/make-conn-pool))
(def s (car/make-conn-spec))
(defmacro wcar [& body] `(car/with-conn p s ~@body))

(defn tname "Returns namespaced test name."
  [test-name] (keyword "touchstone-tests" (name test-name)))

(comment (tname "foobar"))

(defn clean-up!
  []
  (let [test-keys (wcar (car/keys (#'touchstone/tkey (scoped-name (tname :*)))))]
    (when (seq test-keys)
      (wcar (apply car/del test-keys)))))

(defn db-fixture [f] (clean-up!) (f) (clean-up!))

(use-fixtures :each db-fixture)

;;;; Tests ; TODO

(deftest mab-basics
  (let [select (fn [] (touchstone/mab-select (tname :mab-basics)
                                            :red   "red"
                                            :green "green"
                                            :blue  "blue"))
        choose (fn [] (touchstone/mab-commit! (tname :mab-basics) 1))]))