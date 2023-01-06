(ns datomock.impl
  (:require [clojure.reflect :as reflect]
            [datomic.api :as d]
            [datomic.promise])
  (:import (datomic Log Database Connection)
           (java.util UUID Date)
           (java.util.concurrent BlockingQueue ExecutionException LinkedBlockingDeque)))

(defn ^Database make-empty-db []
  (let [uri (str "datomic:mem://" "datomock-" (UUID/randomUUID))
        db (do (d/create-database uri)
               (d/db (d/connect uri)))]
    (d/delete-database uri)
    db))

(defrecord MockConnState [db logVec])

(defn find-db-txInstant
  [db]
  (:db/txInstant
    (d/entity db (d/t->tx (d/basis-t db)))))

(defn log-item
  [{:as tx-res :keys [db-after]}]
  {:t (d/basis-t db-after)
   :data (:tx-data tx-res)
   :db/txInstant (find-db-txInstant db-after)})

(defn coerce-to-t
  [tx-eid-or-t]
  {:pre [(integer? tx-eid-or-t)]}
  (d/tx->t tx-eid-or-t))

(defn date? [x]
  (instance? Date x))

(defn log-tail-tx-range
  [logVec startT endT]
  (->> logVec
    (filter
      (let [start-pred
            (cond
              (nil? startT) (constantly true)
              (integer? startT)
              (let [start-t (coerce-to-t startT)]
                (fn [{:as tx-res, t :t}]
                  (<= start-t t)))
              (date? startT)
              (let [start-time (.getTime ^Date startT)]
                (fn [{:as tx-res, ^Date tx-inst :db/txInstant}]
                  (<= start-time (.getTime tx-inst))))
              :else
              (throw (IllegalArgumentException.
                       (str "startT should be a Long, a Date or nil, found type: " (pr-str (type startT))))))
            end-pred
            (cond
              (nil? endT) (constantly true)
              (integer? endT)
              (let [end-t (coerce-to-t endT)]
                (fn [{:as tx-res, t :t}]
                  (< t end-t)))
              (date? endT)
              (let [end-time (.getTime ^Date endT)]
                (fn [{:as tx-res, ^Date tx-inst :db/txInstant}]
                  (< (.getTime tx-inst) end-time)))
              :else
              (throw (IllegalArgumentException.
                       (str "endT should be a Long, a Date or nil, found type: " (pr-str (type endT))))))]
        (fn [tx-res]
          (and (start-pred tx-res) (end-pred tx-res)))))
    (map #(dissoc % :db/txInstant))))

(defn forked-txRange
  [originLog forkT logVec startT endT]
  (concat
    (when (some? originLog)
      (->> (d/tx-range originLog startT endT)
        ;; NOTE we need this additional filtering step because the originLog has been read
        ;; _after_ reading the starting-point db, leaving time for additional txes to have been added (Val, 01 Jul 2018)
        (filter (fn [{:as tx-res :keys [t]}]
                  (<= t forkT)))))
    (log-tail-tx-range logVec startT endT)))

(defrecord ForkedLog [originLog forkT logVec]
  Log
  (txRange [_ startT endT]
    (forked-txRange originLog forkT logVec startT endT)))

(defn transact!
  [a_state a_txq tx-data]
  (doto (datomic.promise/settable-future)
    (deliver
      (loop []
        (let [old-val @a_state
              db (:db old-val)
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
                            (conj (:logVec old-val) (log-item tx-res)))]
              (if (compare-and-set! a_state old-val new-val)
                (do
                  (when-let [^BlockingQueue txq @a_txq]
                    (.add ^BlockingQueue txq tx-res))
                  tx-res)
                (recur))))
          )))
    ))

(defn- extended-connection-transact-methods?
  "Return true if the datomic.Connection interface has the additional
  transact and transactAsync methods added in datomic 1.0.6527."
  []
  (->> (reflect/reflect Connection)
       :members
       (filter #(and (= (:name %) 'transactAsync)
                     (= (:parameter-types %) '[java.util.List java.lang.Object])))
       seq
       boolean))

(defmacro ^:private def-MockConnection []
  `(defrecord ~'MockConnection
     ~'[a_state,                                               ;; an atom, holding a MockConnState
        forkT,                                                 ;; the basis-t of the starting-point db / connection at the time of forking
        originLog,                                             ;; a Log Value of the origin connection, taken _after_ derefing its db
        a_txq                                                  ;; an atom, holding the txReportQueue when it exists
        ]

     ~'Connection
     ~'(db [_] (:db @a_state))
     ~'(transact [_ tx-data]
                 (transact! a_state a_txq tx-data))
     ~'(transactAsync [this tx-data] (.transact this tx-data))

     ~@(when (extended-connection-transact-methods?)
         ['(transact [this tx-data _] (.transact this tx-data))
          '(transactAsync [this tx-data _] (.transact this tx-data))])

     ~'(requestIndex [_] true)
     ~'(release [_] (do nil))
     ~'(gcStorage [_ olderThan] (do nil))

     ~'(sync [this] (doto (datomic.promise/settable-future)
                      (deliver (.db this))))
     ~'(sync [this t] (.sync this))
     ~'(syncExcise [this t] (.sync this))
     ~'(syncIndex [this t] (.sync this))
     ~'(syncSchema [this t] (.sync this))

     ~'(txReportQueue [_]
                      (or @a_txq
                          (swap! a_txq #(or % (LinkedBlockingDeque.)))))
     ~'(removeTxReportQueue [_]
                            (reset! a_txq nil))

     ~'(log [_] (->ForkedLog originLog forkT (:logVec @a_state)))))

(def-MockConnection)

(defn mock-conn*
  [^Database db, ^Log parent-log]
  (->MockConnection (atom (->MockConnState db [])) (d/next-t db) parent-log (atom nil)))
