(ns taoensso.touchstone.ring
  "Touchstone middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [taoensso.touchstone :as touchstone]))

(defn wrap-random-test-subject-id
  "Wraps Ring handler to randomly generate, sessionize, and bind a test-subject
  id for request. Request's User-Agent header will be checked, and bots excluded
  from split-testing by having their id set to nil.

  Use custom middleware for more sophisticated bot testing, exclusion of staff
  requests, etc."
  [handler]
  (fn [request]
    (if (contains? (:session request) :mab-subject-id)
      (let [sessionized-id (get-in request [:session :mab-subject-id])]
        (touchstone/with-test-subject sessionized-id (handler request)))

      ;; Handle with then sessionize a new, randomly-generated id
      (let [user-agent (-> (get-in request [:headers "user-agent"] "")
                           str/lower-case)
            known-bots-regex
            #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)"

            new-id (when-not (re-find known-bots-regex user-agent)
                     (str (java.util.UUID/randomUUID))) ; nil for bots
            response (touchstone/with-test-subject new-id (handler request))]
        (assoc-in response [:session :mab-subject-id] new-id)))))


