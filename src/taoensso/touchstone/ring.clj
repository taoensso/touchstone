(ns taoensso.touchstone.ring
  "Touchstone middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [taoensso.touchstone :as touchstone]))

(defn bot-user-agent? "Simple test for honest bots."
  [ring-request-headers]
  (->> (get ring-request-headers "user-agent" "")
       (str/lower-case)
       (re-find
        #"(agent|bing|bot|crawl|curl|facebook|google|index|slurp|spider|teoma|wget)")
       (boolean)))

(comment (bot-user-agent? {"user-agent" "GoogleBot"}))

(defn session-swap
  "Small util to help correctly manage (modify) funtional sessions."
  [req resp f & args]
  (when resp
    (if (contains? resp :session) ; Use response session (may be nil)
      (assoc resp :session (apply f (:session resp) args))
      (assoc resp :session (apply f (:session req)  args)))))

(comment
  (session-swap {:session {:req? true}} {:session nil}           assoc :new-k :new-v)
  (session-swap {:session {:req? true}} {:session {:resp? true}} assoc :new-k :new-v)
  (session-swap {:session {:old? true}} {}                       assoc :new-k :new-v))

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
          (session-swap request response assoc :ts-id new-id))))))
