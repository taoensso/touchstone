Current [semantic](http://semver.org/) version:

```clojure
[com.taoensso/touchstone "1.0.0"] ; Requires Clojure 1.4+ as of 1.0.0
```

# Touchstone, a Clojure A/B testing library

[A/B testing](http://en.wikipedia.org/wiki/A/B_testing) is great for **conversion optimization**. We should all be doing more of it. But traditional A/B tests can be a nuisance to setup and monitor.

Touchstone is an attempt to bring **dead-simple, high-power split-testing** to any Clojure web application. It uses [multi-armed bandit](http://en.wikipedia.org/wiki/Multi-armed_bandit) techniques to provide **fast, accurate, low-maintenance** conversion optimization. The API is very simple and *highly flexible*.

## What's in the box™?
 * Small, uncomplicated **all-Clojure** library.
 * **Great performance** (backed by [Carmine](https://github.com/ptaoussanis/carmine)).
 * **High flexibility** (variations are *arbitrary Clojure forms*).
 * **Low maintenace** (fire-and-forget, automatic-selection algorithm).
 * Fire-and-forget **multivariate** testing.
 * **Advanced capabilities** like test composition (dependent tests), arbitrary scoring, engagement testing, etc.
 * **Ring middleware**.

## Getting started

### Dependencies

Add the necessary dependency to your [Leiningen](http://leiningen.org/) `project.clj` and `require` the library in your ns:

```clojure
[com.taoensso/touchstone "1.0.0"] ; project.clj
(ns my-app (:require [taoensso.touchstone :as touchstone])) ; ns
```

### Configuration

Works out-the-box with default Redis configuration. See `touchstone/config` for custom Redis connection requirements.

### Split-testing

Traditional split-testing consists of 4 steps:
  1. Defining content variations (e.g. possible labels for a sign-up button).
  2. Distributing content variations to test subjects (our users).
  3. Recording events of interest (sign-ups) by variation.
  4. Analyzing the results and adopting our most successful content (best button label).

The particular multi-armed bandit technique used by Touchstone means that we only concern ourselves with steps 1 and 3. Steps 2 and 4 are handled automatically by the algorithm.

**To optimize a Ring web application**, start by adding `(taoensso.touchstone.ring/wrap-random-subject-id)` to your middleware stack.

One or more named-test selectors can then be used as part of your page content:

```clojure
(touchstone/mab-select :my-app/landing.buttons.sign-up ; Test name
                       :sign-up  "Sign-up!"   ; Named variation #1
                       :join     "Join!"      ; Named variation #2
                       :join-now "Join now!"  ; Named variation #3
                       )
```

And relevant events recorded:

```clojure
(touchstone/mab-commit! :my-app/landing.buttons.sign-up 1) ; On sign-up button click
```

Touchstone will now **automatically** start using accumulated statistical data to optimize the selection of the `:my-app/landing.buttons.signup` test variations for maximum clicks.

And you're done! That's literally all there is to it.

If you're interested, you can examine the accumulated statistical data at any point:

```clojure
(touchstone/pr-mab-results :my-app/landing.buttons.sign-up)
%> MAB test :my-app/landing.buttons.sign-up with 17 total views and a cumulative score of 2:
%> ([:sign-up 28.68] [:join-now 4.33] [:join 1.11])
```

See the `mab-select` and `mab-commit!` docstrings for info on more advanced capabilities like **multivariate testing, test composition (dependent tests), arbitrary scoring, engagement testing**, etc.

## Project links

  * [API documentation](http://ptaoussanis.github.io/touchstone/).
  * My other [Clojure libraries](https://www.taoensso.com/clojure-libraries) (Redis & DynamoDB clients, logging+profiling, i18n+L10n, serialization, A/B testing).

##### This project supports the **CDS and ClojureWerkz project goals**:

  * [CDS](http://clojure-doc.org/), the **Clojure Documentation Site**, is a contributer-friendly community project aimed at producing top-notch Clojure tutorials and documentation.

  * [ClojureWerkz](http://clojurewerkz.org/) is a growing collection of open-source, batteries-included **Clojure libraries** that emphasise modern targets, great documentation, and thorough testing.

## Contact & contribution

Please use the [project's GitHub issues page](https://github.com/ptaoussanis/touchstone/issues) for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com](https://www.taoensso.com) or on Twitter ([@ptaoussanis](https://twitter.com/#!/ptaoussanis)). Cheers!

## License

Copyright &copy; 2012, 2013 Peter Taoussanis. Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
