(defproject com.taoensso/touchstone "2.0.1"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Clojure A/B testing library"
  :url "https://github.com/ptaoussanis/touchstone"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}
  :dependencies
  [[org.clojure/clojure            "1.4.0"]
   [com.taoensso/encore            "0.9.2"]
   [com.taoensso/carmine           "2.4.6"]
   [org.clojure/math.combinatorics "0.0.7"]]

  :test-paths ["test" "src"]
  :profiles
  {;; :default [:base :system :user :provided :dev]
   :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure "1.6.0-beta1"]]}
   :test {:dependencies [[expectations            "1.4.56"]
                         [reiddraper/simple-check "0.5.6"]
                         [ring/ring-core          "1.2.1"]]
          :plugins [[lein-expectations "0.0.8"]
                    [lein-autoexpect   "1.2.2"]]}
   :dev* [:dev {:jvm-opts ^:replace ["-server"]
                ;; :hooks [cljx.hooks leiningen.cljsbuild] ; cljx
                }]
   :dev
   [:1.6 :test
    {:dependencies []
     :plugins []}]}

  :plugins [[lein-ancient "0.5.4"]
            [codox        "0.6.7"]]

  ;; :codox {:sources ["target/classes"]} ; cljx
  :aliases
  {"test-all"   ["with-profile" "default:+1.5:+1.6" "expectations"]
   ;; "test-all"   ["with-profile" "default:+1.6" "expectations"]
   "test-auto"  ["with-profile" "+test" "autoexpect"]
   ;; "build-once" ["do" "cljx" "once," "cljsbuild" "once"] ; cljx
   ;; "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"] ; cljx
   "deploy-lib" ["do" "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev*" "repl" ":headless"]}

  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
