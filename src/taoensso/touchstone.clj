(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * mab:<test-name>:nviews -> hash, {form-name views-count}
    * mab:<test-name>:scores -> hash, {form-name score}
    * mab:<test-name>:selection:<user-id> -> string, form-name

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [taoensso.carmine :as car]
            [taoensso.touchstone.utils :as utils]))
