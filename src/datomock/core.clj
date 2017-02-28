(ns datomock.core
  (:require [datomic.api :as d]
            [datomock.impl :as impl])
  (:import (datomic Connection Database)))

;; ------------------------------------------------------------------------
;; Empty databases

(def empty-db
  "[]
Returns an empty database. Memoized for efficiency."
  (memoize impl/make-empty-db))

;; ------------------------------------------------------------------------
;; Mocked connection


(defn ^Connection mock-conn
  "Creates a mocked Datomic connection from the given *starting-point* database, or an empty database if none provided.

Implicitly backed by Datomic's speculative writes (#'datomic.api/with).
The returned connection is local and has no URI access.

Exceptions thrown during writes will be systematically wrapped in an j.u.c.ExecutionExecption
to emulate the behaviour of a remote transactor.

The #db(), #transact(), #transactAsync(), #txReportQueue(), #removeTxReportQueue() and #log() methods are fully supported.
Housekeeping methods (#requestIndex(), #release(), #gcStorage()) are implemented as noops.
Sync methods have a trivial implementation.
  "
  ([^Database db] (impl/mock-conn* db nil))
  ([] (mock-conn (empty-db))))

(defn ^Connection fork-conn
  "Forks the given Datomic connection into a local 'mocked' connection.
The starting-point database will be the current database at the time of forking.

Note that if #log() is not supported by `conn` (as is the case for mem connections prior to Datomic 0.9.5407),
the forked connection will support #log(), but the returned Log will only contain transactions committed after forking.

See documentation of #'mock-conn for the characteristics of the returned Connection."
  [^Connection conn]
  (impl/mock-conn* (d/db conn) (d/log conn)))




