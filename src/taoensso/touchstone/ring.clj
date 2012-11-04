(ns taoensso.touchstone.ring
  "Touchstone middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [taoensso.touchstone :as touchstone]))

(defn wrap-random-test-subject-id*
  "Wraps Ring handler to randomly generate, sessionize, and bind a test-subject
  id for request."
  [handler]
  (fn [request]
    (if (contains? (:session request) :mab-subject-id)

      (let [sessionized-id (get-in request [:session :mab-subject-id])]
        (touchstone/with-test-subject sessionized-id (handler request)))

      (let [new-id   (str (rand-int 2147483647))
            response (touchstone/with-test-subject new-id (handler request))]
        (assoc-in response [:session :mab-subject-id] new-id)))))

(defn bot-user-agent?
  "Simple test for honest bots."
  [ring-request-headers]
  (boolean
   (re-find
    #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)"
    (str/lower-case (get ring-request-headers "user-agent" "")))))

(comment (bot-user-agent? {"user-agent" "GoogleBot"}))

(defn wrap-random-test-subject-id
  "Like `wrap-random-test-subject-id*` but checks request's User-Agent header to
  exclude honest bots from split-testing. Manually sessionize a nil
  :mab-subject-id to exclude staff or bots detected via more sophisticated
  methods."
  [handler]
  (fn [request]
    (if (bot-user-agent? (:headers request))
      (handler request)
      ((wrap-random-test-subject-id* handler) request))))