(ns datomock.impl
  (:require [datomic.api :as d]
            [datomic.promise])
  (:import (datomic Log Database Connection)
           (java.util UUID)
           (java.util.concurrent BlockingQueue ExecutionException LinkedBlockingDeque)))

(defn ^Database make-empty-db []
  (let [uri (str "datomic:mem://" "datomock-" (UUID/randomUUID))
        db (do (d/create-database uri)
               (d/db (d/connect uri)))]
    (d/delete-database uri)
    db))

(defrecord MockConnState [db logVec])

(defn log-item
  [txInstant-id tx-res]
  (let [tx-data (:tx-data tx-res)
        {:keys [e v] :as instant-datom} (first (filter #(= txInstant-id (:a %)) tx-data))]
    {:t          (d/basis-t (:db-after tx-res))
     :tx-id      e
     :tx-instant v
     :data       (:tx-data tx-res)}))

(defn log-tail [logVec startT endT]
  (filter
    (fn [{:as log-item, :keys [t tx-id tx-instant]}]
      (let [in-lower-bound? (if (inst? startT)
                              (<= startT tx-instant)
                              (or (<= startT tx-id) (<= startT t)))
            in-upper-bound? (if (inst? endT)
                              (< tx-instant endT)
                              (or (< tx-id endT) (< t endT)))]
        (and in-lower-bound? in-upper-bound?)))
    logVec))

(defrecord ForkedLog [rootLog forkT logVec]
  Log
  (txRange [_ startT endT]
    (let [result (map #(select-keys % #{:t :data})
                   (concat
                     (when rootLog
                       (seq (d/tx-range rootLog startT (if (nil? endT) forkT (min forkT endT)))))
                     (log-tail logVec startT endT)))]
      result)))

(defn transact!
  [a_state a_txq tx-data]
  (doto (datomic.promise/settable-future)
    (deliver
      (loop []
        (let [old-val @a_state
              db (:db old-val)
              txInstant (:db/id (d/entity db :db/txInstant))
              tx-res (try (d/with db tx-data)
                          (catch Throwable err
                            err
                            #_(throw (ExecutionException. err))))]
          (if (instance? Throwable tx-res)
            ;; NOTE unlike a regular Clojure Promise (as returned by clojure.core/promise),
            ;; delivering a Throwable will result in throwing when deref'ing,
            ;; which is the intended behaviour here. (Val, 15 Jun 2018)
            tx-res
            (let [new-val (->MockConnState
                            (:db-after tx-res)
                            (conj (:logVec old-val) (log-item txInstant tx-res)))]
              (if (compare-and-set! a_state old-val new-val)
                (do
                  (when-let [^BlockingQueue txq @a_txq]
                    (.add ^BlockingQueue txq tx-res))
                  tx-res)
                (recur)))))))))

(defrecord MockConnection
  [a_state, forkT, parentLog, a_txq]

  Connection
  (db [_] (:db @a_state))
  (transact [_ tx-data]
    (transact! a_state a_txq tx-data))
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

  (log [_] (->ForkedLog parentLog forkT (:logVec @a_state))))

(defn mock-conn*
  [^Database db, ^Log parent-log]
  (->MockConnection (atom (->MockConnState db [])) (d/next-t db) parent-log (atom nil)))

