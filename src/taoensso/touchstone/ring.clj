(ns taoensso.touchstone.ring
  "Touchstone middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [taoensso.touchstone :as touchstone]))

(defn bot-user-agent?
  "Simple test for honest bots."
  [ring-request-headers]
  (boolean
   (re-find
    #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)"
    (str/lower-case (get ring-request-headers "user-agent" "")))))

(comment (bot-user-agent? {"user-agent" "GoogleBot"}))

(defn make-wrap-random-test-subject-id
  "Returns Ring middleware that generates, sessionizes, and binds a test-subject
  id for requests eligible for split-testing (by default this excludes clients
  that report themselves as bots)."
  [& {:keys [eligible?-fn]
      :or   {eligible?-fn (fn [request] (not (bot-user-agent? (:headers request))))}}]
  (fn [handler]
    (fn [request]
      (if-not (eligible?-fn request)
        (handler request)

        (if (contains? (:session request) :mab-subject-id)
          (let [sessionized-id (get-in request [:session :mab-subject-id])]
            (touchstone/with-test-subject sessionized-id (handler request)))

          (let [new-id   (str (rand-int 2147483647))
                response (touchstone/with-test-subject new-id (handler request))]
            (assoc-in response [:session :mab-subject-id] new-id)))))))