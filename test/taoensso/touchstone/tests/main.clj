(ns taoensso.touchstone.tests.main
  (:require [expectations        :as test       :refer :all]
            [taoensso.touchstone :as touchstone :refer ()]))

(defn- before-run {:expectations-options :before-run} [])
(defn- after-run  {:expectations-options :after-run}  [])

(expect true) ; TODO Add tests (PRs welcome!)