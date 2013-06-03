(defproject com.taoensso/touchstone "1.0.0"
  :description "Clojure A/B testing library"
  :url "https://github.com/ptaoussanis/touchstone"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure            "1.4.0"]
                 [org.clojure/tools.macro        "0.1.1"]
                 [com.taoensso/carmine           "1.7.0"]
                 [org.clojure/math.combinatorics "0.0.3"]]
  :profiles {:1.4   {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5   {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :dev   {:dependencies []}
             :test  {:dependencies []}
             :bench {:dependencies []}}
  :aliases {"test-all" ["with-profile" "test,1.4:test,1.5" "test"]}
  :plugins [[codox "0.6.4"]]
  :min-lein-version "2.0.0"
  :warn-on-reflection true)
