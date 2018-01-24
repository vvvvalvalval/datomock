# datomock

Mocking and forking Datomic connections in-memory.

[![Clojars Project](https://img.shields.io/clojars/v/vvvvalvalval/datomock.svg)](https://clojars.org/vvvvalvalval/datomock)

**Notes:** 

* This library is _not_ an in-memory re-implementation of Datomic - just a thin wrapper on top of the Datomic Peer Library. All the heavy lifting is done by Datomic's 'speculative writes' (a.k.a [`db.with(tx)`](https://docs.datomic.com/on-prem/javadoc/datomic/Database.html#with-java.util.List-)) and Clojure's managed references ([atoms](https://clojure.org/reference/atoms))
* Only for Peers, not Clients.

**Project maturity:** beta quality. Note that you will probably not need to use this library in production.

## Usage

```clojure 
(require '[datomic.api :as d])
(require '[datomock.core :as dm])

(def my-conn (d/connect "datomic:mem://hello-world"))

;; ... create a mock connection from a Database value:
(def starting-point-db (d/db my-conn))
(def mocked-conn (dm/mock-conn starting-point-db))

;; which is essentially the same as: 
(def mocked-conn (dm/fork-conn my-conn))

;; dm/fork-conn is likely what you'll use most.
```

## Rationale and semantics

Mocked connections use Datomic's speculative writes (`db.with()`) and Clojure's managed references to emulate a Datomic connection locally.

The main benefit is the ability to 'fork' Datomic connections. 
More precisely, if `conn1` is *forked* from `conn2`:
* at the time of forking, `conn1` and `conn2` hold the same database value;
* subsequent writes to `conn1` will leave `conn2` unaffected
* subsequent writes to `conn2` will leave `conn1` unaffected

Because Datomic database values are persistent data structures, forking is extremely cheap in both space and time.

## Applications

* **Write expressive tests:** write tests as a tree of scenarios exploring various alternatives. In particular, this makes it very easy to write _system-level_ tests that run fast. Forget about setup and teardown phases: they are respectively replaced by forking and garbage-collection.
* **Cheap, safe debugging:** instantly reproduce your production environment on your local machine. Save and re-use as many checkpoints of your state as you need as you debug. Dry-run data patches and migrations safely before committing them to production.
* **Explore new database schemas:** in particular, you can experiment with changes to your database schema without committing to them. 
* **Staging environments / QA / CI:** want one staging environment (Peer) for each pull-request on your app? Just have each of them use an in-memory fork of a shared database (or even your production database).
* **Ephemeral demos:** want to let people experiment with your app without accumulating their manual changes? Just have them work on a fork, and discard it afterwards.
* **Ephemeral dev environments:** similarly, it's usually better to always work on the same data when developing, and have the manual changes you've made while experimented be discarded at the end of the session.

### Useful links:

* _[Application architecture with Datomic: branching reality](http://vvvvalvalval.github.io/posts/2016-01-03-architecture-datomic-branching-reality.html):_ a blog post providing a more in-depth analysis of forkability and its applications.
* [_Full Stack Teleport Testing with Om Next & Datomic:_](https://youtu.be/qijWBPYkRAQ) a Clojure/West 2017 talk about how Ladder implement system-level testing using Om and Datomic.


## How it works

Essentially, by putting a Datomic Database value in a Clojure Managed Reference (currently an Atom, may evolve to use an Agent instead) and using `db.with()` to update the state of that reference.

_That's it, you now know how to re-implement Datomock yourself!_

Actually, there are a few additional complications to make this work smoothly:

* Log: the reference needs to hold not only a Database Value, but also a Log Value (for some strange reason, in Datomic, Log Values are not part of Database values).
* Futures-based API: to match the interface of Datomic Connections, the library needs to provide a Futures-based API, which requires some additional work on top of Clojure references.
* txReportQueue: the library needs to provide an implementation of that as well.

## Mocked connections vs `datomic:mem`

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
