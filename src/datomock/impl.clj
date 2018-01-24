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

(defrecord MockConnState [db logVec deliver-tx-res])

(defn log-item
  [tx-res]
  {:t (d/basis-t (:db-after tx-res))
   :data (:tx-data tx-res)})

(defn log-tail [logVec startT endT]
  (filter (fn [{:as log-item, :keys [t]}]
            (and
              (or (nil? startT) (<= startT t))
              (or (nil? endT) (< t endT))
              )) logVec))

(defrecord ForkedLog [rootLog forkT logVec]
  Log
  (txRange [_ startT endT]
    (concat
      (when rootLog
        (seq (d/tx-range rootLog startT (if (nil? endT) forkT (min forkT endT)))))
      (log-tail logVec startT endT)
      )))

(defrecord MockConnection
  [a_state, forkT, parentLog, a_txq]

  Connection
  (db [_] (:db @a_state))
  (transact [this tx-data]
    (let [fut (.transactAsync this tx-data)]
      (deref fut)
      fut))
  (transactAsync [this tx-data]
    (let [fut (datomic.promise/settable-future)]
      (send a_state
            (fn [old-val]
              (if-let [tx-res (try (d/with (:db old-val) tx-data)
                                   (catch Throwable err
                                     (deliver fut err)
                                     nil))]
                (do (when-let [^BlockingQueue txq @a_txq]
                      (.add ^BlockingQueue txq tx-res))
                    (->MockConnState (:db-after tx-res)
                                     (conj (:logVec old-val) (log-item tx-res))
                                     ;; add a delay that delivers tx-res to the future,
                                     ;; This delay is forced by a watch on the agent, so that
                                     ;; the agent state is updated when the future is completed
                                     (delay (deliver fut tx-res))))
                old-val)))
      fut))

  (requestIndex [_] true)
  (release [_] (do nil))
  (gcStorage [_ olderThan] (do nil))

  (sync [this] (deliver (datomic.promise/settable-future) (.db this)))
  (sync [this t] (let [fut (datomic.promise/settable-future)]
                   (add-watch a_state (Object.)
                              (fn [watch-key reference old new]
                                (let [db (:db new)]
                                  (d/basis-t db) t (>= (d/basis-t db) t)
                                  (when (>= (d/basis-t db) t)
                                    (deliver fut db)
                                    (remove-watch reference watch-key)))))
                   fut))
  (syncExcise [this t] (.sync this))
  (syncIndex [this t] (.sync this))
  (syncSchema [this t] (.sync this))

  (txReportQueue [_]
    (or @a_txq
        (swap! a_txq #(or % (LinkedBlockingDeque.)))))
  (removeTxReportQueue [_]
    (reset! a_txq nil))

  (log [_] (->ForkedLog parentLog forkT (:logVec @a_state)))
  )

(defn mock-conn*
  [^Database db, ^Log parent-log]
  (let [state-agent (-> (agent (->MockConnState db [] nil))
                        (add-watch ::force-deliver-promise (fn [_ _ old new]
                                                             (when-not (identical? old new)
                                                               (force (:deliver-tx-res new))))))]
    (->MockConnection state-agent (d/next-t db) parent-log (atom nil))))
