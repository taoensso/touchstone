(defproject com.taoensso/touchstone "2.1.0"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Split testing library for Clojure"
  :url "https://github.com/ptaoussanis/touchstone"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}
  :dependencies
  [[org.clojure/clojure            "1.5.1"]
   [com.taoensso/encore            "2.68.0"]
   [com.taoensso/carmine           "2.14.0"]
   [org.clojure/math.combinatorics "0.0.7"]]

  :plugins
  [[lein-pprint  "1.1.2"]
   [lein-ancient "0.6.10"]
   [lein-codox   "0.9.5"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
   :1.7  {:dependencies [[org.clojure/clojure "1.7.0"]]}
   :1.8  {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :1.9  {:dependencies [[org.clojure/clojure "1.9.0-alpha10"]]}
   :test {:dependencies [[org.clojure/test.check  "0.9.0"]
                         [ring/ring-core          "1.5.0"]]}
   :dev
   [:1.9 :test :server-jvm]}

  :test-paths ["test" "src"]

  :codox
  {:language :clojure
   :source-uri "https://github.com/ptaoussanis/carmine/blob/master/{filepath}#L{line}"}

  :aliases
  {"test-all"   ["with-profile" "+1.9:+1.8:+1.7:+1.6:+1.5" "test"]
   "deploy-lib" ["do" "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev" "repl" ":headless"]}

  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/groups/public/"})
