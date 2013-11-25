## v2.0.0 → v2.0.1

  Minor, non-breaking release: **2013 Nov 25**.

  * [FIX]: previous Ring middleware could wipe session under certain circumstances. This has been fixed.
  * [HK]: bump some dependencies, clean up `project.clj`.


## v1.0.0 → v2.0.0
  * **BREAKING**: All relevant fns now take an explicit config arg and test-subject id (you can use the `*ts-id*` thread-local binding for this).
  * **BREAKING**: Renamed `mab-commit!` -> `commit!`, `pr-mab-results` -> `pr-results`.


## v0.16.0 → v1.0.0
  * **BREAKING**: Drop Clojure 1.3 support.
  * Some minor bug fixes.
  * Add support for config inheritence. `:my-app.buttons/sign-up` config will be merged from `:default`, `:my-app`, `:my-app.buttons`, `:my-app.buttons/sign-up`.


## For older versions please see the [commit history][]

[commit history]: https://github.com/ptaoussanis/touchstone/commits/master
[API docs]: http://ptaoussanis.github.io/touchstone
