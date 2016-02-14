# datomock

Mocking and forking Datomic connections in a local context.

[![Clojars Project](https://img.shields.io/clojars/v/vvvvalvalval/datomock.svg)](https://clojars.org/vvvvalvalval/datomock)

## Usage

```clojure 
(require '[datomic.api :as d])
(require '[datomock.core :as dm])

(def my-conn (d/connect "datomic:mem://hello-world"))

;; ... create a mock connection from a Database value:
(def starting-point-db (d/db my-conn))
(def mocked-conn (dm/mock-conn starting-point-db))

;; which is the same as: 
(def mocked-conn (dm/fork-conn my-conn))
```

## Rationale and semantics

Mocked connections use Datomic's speculative writes (`db.with()`) and Clojure's managed references to emulate a Datomic connection locally.

The main benefit is the ability to 'fork' Datomic connections. 
More precisely, if `conn1` is *forked* from `conn2`:
* at the time of forking, `conn1` and `conn2` have the same database value;
* subsequent writes to `conn1` will leave `conn2` unaffected
* subsequent writes to `conn2` will leave `conn1` unaffected

Because Datomic database values are persistent data structures, forking is extremely cheap in both space and time. 

Here are some applications:
* write your tests as walking a tree of possibilities. Just like Nicolas Cage in the [Next](http://www.imdb.com/title/tt0435705/) movie.
* eliminate the need for teardown phases in your tests
* make your test faster by installing your schema and test data only once for your whole test suite.
* speculatively run some code on your production database

See this [blog post](http://vvvvalvalval.github.io/posts/2016-01-03-architecture-datomic-branching-reality.html)
 for a more in-depth analysis of forkability and its applications.

### Mocked connections vs `datomic:mem`

> How is this different than using Datomic memory databases, as in `(d/connect "datomic:mem://my-db")` ?

Mocked connections differ from Datomic's memory connections in several ways:

* you create a memory connection from scratch, whereas you create a mocked connection from a starting-point database value
* a mocked connection is not accessible via a global URI

## Compatibility notes

This library requires Datomic 0.9.4470 or higher, in order to provide an implementation of the most recent methods of `datomic.Connection`.

However, if you need to work with a lower version, forking this library and removing the implementation of the `syncSchema()`, `syncExcise()` and `syncIndex()` should work just fine.

## License

Copyright © 2016 Valentin Waeselynck and contributors.

Distributed under the MIT License.
