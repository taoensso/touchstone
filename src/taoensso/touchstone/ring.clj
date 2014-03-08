(ns taoensso.touchstone.ring
  "Touchstone middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [taoensso.encore     :as encore]
            [taoensso.touchstone :as touchstone]))

(defn bot-user-agent? "Simple test for honest bots."
  [ring-request-headers]
  (->> (get ring-request-headers "user-agent" "")
       (str/lower-case)
       (re-find
        #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)")
       (boolean)))

(comment (bot-user-agent? {"user-agent" "GoogleBot"}))

(defn wrap-test-subject-id
  "Ring middleware that generates, sessionizes, and binds a test-subject id for
  requests eligible for split-testing (by default this excludes clients that
  report themselves as bots)."
  [handler & [wrap-pred]]
  (fn [request]
    (if-not ((or wrap-pred #(not (bot-user-agent? (:headers %)))) request)
      (handler request)

      (if-let [ts-id-entry (find (:session request) :ts-id)] ; May be nil
        (let [sessionized-id (val ts-id-entry)]
          (touchstone/with-test-subject sessionized-id
            (handler (assoc request :ts-id sessionized-id))))

        (let [new-id   (rand-int 2147483647)
              response (touchstone/with-test-subject new-id
                         (handler (assoc request :ts-id new-id)))]
          (encore/session-swap request response assoc :ts-id new-id))))))
