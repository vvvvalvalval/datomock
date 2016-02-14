(ns datomock.core
  (:require [datomic.api :as d]
            [datomic.promise])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingDeque ExecutionException]
           [datomic Connection Database]
           [java.util UUID]))

;; ------------------------------------------------------------------------
;; Empty databases

(defn- ^Database make-empty-db []
  (let [uri (str "datomic:mem://" "datomock-" (UUID/randomUUID))
        db (do (d/create-database uri)
               (d/db (d/connect uri)))]
    (d/delete-database uri)
    db))

(def empty-db
  "[]
Returns an empty database. Memoized for efficiency."
  (memoize make-empty-db))

;; ------------------------------------------------------------------------
;; Mocked connection

(defrecord MockConnection
  [a_db, a_txq]

  Connection
  (db [_] @a_db)
  (transact [_ tx-data] (doto (datomic.promise/settable-future)
                             (deliver (let [tx-res
                                            (loop []
                                              (let [old-val @a_db
                                                    tx-res (try (d/with old-val tx-data)
                                                             (catch Throwable err
                                                               (throw (ExecutionException. err))))
                                                    new-val (:db-after tx-res)]
                                                (if (compare-and-set! a_db old-val new-val)
                                                  tx-res
                                                  (recur))
                                                ))]
                                        (when-let [^BlockingQueue txq @a_txq]
                                          (.add ^BlockingQueue txq tx-res))
                                        tx-res))
                             ))
  (transactAsync [this tx-data] (.transact this tx-data))

  (requestIndex [_] true)
  (release [_] (do nil))
  (gcStorage [_ olderThan] (do nil))

  (sync [this] (doto (datomic.promise/settable-future)
                 (deliver (.db this))))
  (sync [this t] (.sync this))
  (syncExcise [this t] (.sync this))
  (syncIndex [this t] (.sync this))
  (syncSchema [this t] (.sync this))

  (txReportQueue [_]
    (or @a_txq
        (swap! a_txq #(or % (LinkedBlockingDeque.)))))
  (removeTxReportQueue [_]
    (reset! a_txq nil))
  )


(defn ^Connection mock-conn
  "Creates a mocked Datomic connection from the given *starting-point* database, or an empty database if none provided.

Implicitly backed by Datomic's speculative writes (#'datomic.api/with).
The returned connection is local and has no URI access.

Exceptions thrown during writes will be systematically wrapped in an j.u.c.ExecutionExecption
to emulate the behaviour of a remote transactor.

The #db(), #transact(), #transactAsync(), #txReportQueue() and #removeTxReportQueue() are fully supported.
Housekeeping methods (#requestIndex(), #release(), #gcStorage()) are implemented as noops.
Sync methods have a trivial implementation, and #log() is not supported.
  "
  ([^Database db]
   (->MockConnection (atom db) (atom nil)))
  ([] (mock-conn (empty-db))))

(defn ^Connection fork-conn
  "Forks the given Datomic connection into a local 'mocked' connection.
The starting-point database will be the current database at the time of forking.

See documentation of #'mock-conn for the characteristics of the returned Connection."
  [^Connection conn]
  (mock-conn (d/db conn)))




