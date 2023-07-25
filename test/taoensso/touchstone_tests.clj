(ns taoensso.touchstone-tests
  (:require
   [clojure.test    :as test :refer [deftest testing is]]
   [taoensso.encore :as enc]))

(comment
  (remove-ns      'taoensso.touchstone-tests)
  (test/run-tests 'taoensso.touchstone-tests))

;;;;

;; (deftest pass (is (= 1 1)))
;; (deftest fail (is (= 1 0)))
