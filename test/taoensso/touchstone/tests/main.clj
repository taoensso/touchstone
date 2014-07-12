(ns taoensso.touchstone.tests.main
  (:require [expectations        :as test       :refer :all]
            [taoensso.touchstone :as touchstone :refer ()]))

(comment (test/run-tests '[taoensso.touchstone.tests.main]))

(defn- before-run {:expectations-options :before-run} [])
(defn- after-run  {:expectations-options :after-run}  [])

(expect true)
