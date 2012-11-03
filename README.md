Current [semantic](http://semver.org/) version:

```clojure
[com.taoensso/touchstone "0.5.0"]
```

# Touchstone, a split-testing library for Clojure

[Split-testing](http://en.wikipedia.org/wiki/A/B_testing) is great for **conversion optimization**. We should all be doing more of it. But traditional A/B tests can be a nuisance to setup and monitor.

Touchstone is an attempt to bring **dead-simple split-testing** to any Clojure web application. It uses [multi-armed bandit](http://en.wikipedia.org/wiki/Multi-armed_bandit) techniques to provide **fast, accurate, low-maintenance** conversion optimization.

## What's In The Box?
 * Small, uncomplicated all-Clojure library.
 * **Great performance** (backed by [Redis](http://redis.io/) and [Carmine](https://github.com/ptaoussanis/carmine)).
 * **High flexibility** (variations are arbitrary Clojure forms).
 * **Low maintenace** (fire-and-forget, automatic-selection algorithm).
 * Fire-and-forget **multivariate** testing.
 * **Ring middleware**.

## Status

Touchstone is still currently *experimental*. It **has not yet been thoroughly tested in production** and its API is subject to change. To run tests against all supported Clojure versions, use:

```bash
lein all test
```
## Getting Started

### Leiningen

Depend on Touchstone in your `project.clj`:

```clojure
[com.taoensso/touchstone "0.5.0"]
```

and `require` the library:

```clojure
(ns my-app (:require [taoensso.touchstone :as touchstone]))
```

### Configuration

Works out-the-box with default Redis configuration. See `touchstone/config` for custom Redis connection requirements.

### Split-Testing

Traditional split-testing consists of 4 steps:
  1. Defining content variations (e.g. possible labels for a sign-up button).
  2. Distributing content variations to test subjects (our users).
  3. Recording events of interest (sign-ups) by variation.
  4. Analyzing the results and adopting our most successful content (best button label).

The particular multi-armed bandit technique used by Touchstone means that we only concern ourselves with steps 1 and 3. Steps 2 and 4 are handled automatically by the algorithm.

To optimize a Ring web application, start by adding `taoensso.touchstone.ring/wrap-random-test-subject-id` to your middleware stack.

One or more named-test selectors can then be used as part of your page content:

```clojure
(touchstone/mab-select :landing.buttons.sign-up ; Test name
                       :sign-up  "Sign-up!"   ; Named variation #1
                       :join     "Join!"      ; Named variation #2
                       :join-now "Join now!"  ; Named variation #3
                       )
```

And relevant events recorded:

```clojure
(touchstone/mab-commit! :landing.buttons.sign-up 1) ; On sign-up button click
```

Touchstone will now **automatically** start using accumulated statistical data to optimize the selection of the `:landing.buttons.signup` test variations for maximum clicks.

You can examine the accumulated statistical data at any point:

```clojure
(touchstone/pr-mab-results :landing.buttons.sign-up)
%> MAB test :landing.buttons.sign-up with 17 total views and a cumulative score of 2:
%> ([:sign-up 28.68] [:join-now 4.33] [:join 1.11])
```

See the `mab-select` and `mab-commit!` docstrings for info on more advanced capabilities like multivariate testing.

## Touchstone supports the ClojureWerkz Project Goals

ClojureWerkz is a growing collection of open-source, batteries-included [Clojure libraries](http://clojurewerkz.org/) that emphasise modern targets, great documentation, and thorough testing.

## Contact & Contribution

Reach me (Peter Taoussanis) at *ptaoussanis at gmail.com* for questions/comments/suggestions/whatever. I'm very open to ideas if you have any!

I'm also on Twitter: [@ptaoussanis](https://twitter.com/#!/ptaoussanis).

## License

Copyright &copy; 2012 Peter Taoussanis

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.