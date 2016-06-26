<a href="https://www.taoensso.com" title="More stuff by @ptaoussanis at www.taoensso.com">
<img src="https://www.taoensso.com/taoensso-open-source.png" alt="Taoensso open-source" width="400"/></a>

**[CHANGELOG]** | [API] | current [Break Version]:

```clojure
[com.taoensso/touchstone "2.0.2"] ; Stable
```

> Please consider helping to [support my continued open-source Clojure/Script work]? 
> 
> Even small contributions can add up + make a big difference to help sustain my time writing, maintaining, and supporting Touchstone and other Clojure/Script libraries. **Thank you!**
>
> \- Peter Taoussanis

# Touchstone

## Split testing library for Clojure

[A/B testing] is great for **conversion optimization**. We should all be doing more of it. But traditional A/B tests can be a nuisance to setup and monitor.

Touchstone is an attempt to bring **dead-simple, high-power split-testing** to any Clojure web application. It uses [multi-armed bandit] techniques to provide **fast, accurate, low-maintenance** conversion optimization. The API is simple and *highly flexible*.

## Library status

**Last updated**: Jan 2016

Haven't updated the lib in forever, but it's **stable and works well in production**. Do have some new stuff planned for a future update (particularly docs re: use with modern Cljs applications), but no ETA on that yet.

\- [Peter Taoussanis]

## Features
 * Tiny, **simple API**
 * **Great performance** backed by Redis+[Carmine]
 * **High flexibility** (variations are *arbitrary Clojure forms*)
 * **Low maintenace** (fire-and-forget, automatic-selection algorithm)
 * Fire-and-forget **multivariate** testing
 * **Advanced capabilities** like test composition (dependent tests), arbitrary scoring, engagement testing, etc.
 * **Ring middleware**

## Getting started

Add the necessary dependency to your project:

```clojure
[com.taoensso/touchstone "2.0.2"]
```

And setup your namespace imports:

```clojure
(ns my-ns
  (:require [taoensso.touchstone :as touchstone :refer (*ts-id*)]))
```

### Split-testing

Traditional split-testing consists of 4 steps:

 1. Defining content variations (e.g. possible labels for a sign-up button)
 2. Distributing content variations to test subjects (our users)
 3. Recording events of interest (sign-ups) by variation
 4. Analyzing the results and adopting our most successful content (best button label)

The particular multi-armed bandit technique used by Touchstone means that we only concern ourselves with steps 1 and 3. Steps 2 and 4 are handled automatically by the algorithm.

#### To optimize a Ring web application

Start by adding `(taoensso.touchstone.ring/wrap-test-subject-id)` to your middleware stack.

One or more test selectors can then be used as part of your page content:

```clojure
(touchstone/mab-select
  {:conn-opts {} ; Optional, as per Carmine's `wcar` conn-opts
   }
  *ts-id* ; Dynamic test-subject-id (assigned by middleware)
  :my-app/landing.buttons.sign-up ; Test id
  :sign-up  "Sign-up!"   ; Named variation #1
  :join     "Join!"      ; Named variation #2
  :join-now "Join now!"  ; Named variation #3
)
```

And relevant events (e.g. conversions) recorded:

```clojure
;; On sign-up button click, etc.:
(touchstone/mab-commit!
  {} ; Same opts as given to `mab-select`
  *ts-id* :my-app/landing.buttons.sign-up 1)
```

Touchstone will now **automatically** start using accumulated statistical data to optimize the selection of the `:my-app/landing.buttons.signup` test variations for maximum clicks.

And you're done! That's literally all there is to it.

See the `mab-select` and `mab-commit!` [API] docs for info on more advanced capabilities like **multivariate testing, test composition (dependent tests), arbitrary scoring, engagement testing**, etc.

## Contacting me / contributions

Please use the project's [GitHub issues page] for all questions, ideas, etc. **Pull requests welcome**. See the project's [GitHub contributors page] for a list of contributors.

Otherwise, you can reach me at [Taoensso.com]. Happy hacking!

\- [Peter Taoussanis]

## License

Distributed under the [EPL v1.0] \(same as Clojure).  
Copyright &copy; 2012-2016 [Peter Taoussanis].

<!--- Standard links -->
[Taoensso.com]: https://www.taoensso.com
[Peter Taoussanis]: https://www.taoensso.com
[@ptaoussanis]: https://www.taoensso.com
[More by @ptaoussanis]: https://www.taoensso.com
[Break Version]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md
[support my continued open-source Clojure/Script work]: http://taoensso.com/clojure/backers

<!--- Standard links (repo specific) -->
[CHANGELOG]: https://github.com/ptaoussanis/touchstone/releases
[API]: http://ptaoussanis.github.io/touchstone/
[GitHub issues page]: https://github.com/ptaoussanis/touchstone/issues
[GitHub contributors page]: https://github.com/ptaoussanis/touchstone/graphs/contributors
[EPL v1.0]: https://raw.githubusercontent.com/ptaoussanis/touchstone/master/LICENSE
[Hero]: https://raw.githubusercontent.com/ptaoussanis/touchstone/master/hero.png "Title"

<!--- Unique links -->
[A/B testing]: http://en.wikipedia.org/wiki/A/B_testing
[multi-armed bandit]: http://en.wikipedia.org/wiki/Multi-armed_bandit
[Carmine]: https://github.com/ptaoussanis/carmine
