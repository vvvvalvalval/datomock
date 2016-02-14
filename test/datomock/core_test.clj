(ns datomock.core-test
  (:require [clojure.test :as test :refer :all]
            [datomic.api :as d]
            [datomock.core :as dm :refer :all])
  (:import [datomic Database Connection ListenableFuture]
           [java.util.concurrent BlockingQueue TimeUnit ExecutionException]
           [java.util NoSuchElementException UUID Date]))

(def schema
  [{:db/ident :person/email
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/id #db/id[:db.part/db]
    :db/cardinality :db.cardinality/one
    :db/fulltext true
    :db/index true
    :db.install/_attribute :db.part/db}
   {:db/ident :person/age
    :db/valueType :db.type/long
    :db/id #db/id[:db.part/db]
    :db/cardinality :db.cardinality/one
    :db/fulltext true
    :db/index true
    :db.install/_attribute :db.part/db}])

(def sample-data
  [{:db/id (d/tempid :db.part/user)
    :person/email "foo.bar@gmail.com"
    :person/age 42}
   {:db/id (d/tempid :db.part/user)
    :person/email "jane.doe@gmail.com"
    :person/age 23}])

(defn tx_set-age [email age]
  {:db/id (d/tempid :db.part/user)
   :person/email email
   :person/age age})

(defmacro with-scratch-conn [conn-binding & body]
  `(let [uri# (str "datomic:mem://datomock-test-" (UUID/randomUUID))
         ~conn-binding (do (d/create-database uri#)
                           (d/connect uri#))]
     (try ~@body
       (finally (d/delete-database uri#)))
     ))

(deftest make-empty-db
  (testing "Creating an empty database"
    (is (instance? Database (empty-db)))))

(deftest test-mock-connections
  (testing "Mocked connections behave like regular connections."
    (with-scratch-conn regular-conn
      (doseq [conn [regular-conn (dm/mock-conn) (dm/mock-conn (d/db regular-conn))]]
        (let [db0 (d/db conn)
              txs @(d/transact conn schema)
              db1 (d/db conn)
              txd @(d/transact-async conn sample-data)
              db2 (d/db conn)]
          (is (instance? Connection conn))
          (testing "Transactions results"
            (is (= (set (keys txs)) (set (keys txd))
                   #{:db-before :db-after :tx-data :tempids}))
            (is (and (= (:db-before txs) db0)
                     (= (:db-after txs) db1)
                     (= (:db-before txd) db1)
                     (= (:db-after txd) db2))))
          (testing "Writes occured as expected"
            (is (= (d/q '[:find ?email ?age :where
                          [?p :person/email ?email]
                          [?p :person/age ?age]]
                        (d/db conn))
                   #{["foo.bar@gmail.com" 42]
                     ["jane.doe@gmail.com" 23]})))

          (testing "tx Report queue"
            (let [^BlockingQueue txq (d/tx-report-queue conn)]
              (testing "Was just created, so starts are empty"
                (is (try
                      (.remove txq)
                      false
                      (catch NoSuchElementException _
                        true))))
              (testing "When you transact, will put tx-data in the queue"
                (let [f (d/transact conn [(tx_set-age "jane.doe@gmail.com" 24)])]
                  (is (= (.poll txq 10 TimeUnit/MILLISECONDS) @f))))

              (testing "Starts again as empty after removal"
                @(d/transact conn [(tx_set-age "foo.bar@gmail.com" 43)])
                (d/remove-tx-report-queue conn)
                (let [^BlockingQueue txq (d/tx-report-queue conn)]
                  (is (try
                        (.remove txq)
                        false
                        (catch NoSuchElementException _
                          true)))))))


          ))))
  (testing "Exceptions in writes will be wrapped in ExecutionExceptions"
    (let [conn (dm/mock-conn)
          _ @(d/transact conn schema)
          _ @(d/transact conn sample-data)
          jane-id (ffirst (d/q '[:find ?e :in $ ?email :where [?e :person/email ?email]] (d/db conn) "jane.doe@gmail.com"))]
      (are [transact-fn tx]
        (= :ok (try
                 @(transact-fn conn tx)
                 :no-exception
                 (catch ExecutionException _ :ok)
                 (catch Throwable _ :wrong-exception)))
        d/transact [[:db/add jane-id :person/firstName "Jane"]]
        d/transact-async [[:db/add jane-id :person/firstName "Jane"]]
        )))
  (testing "May be created from any database value"
    (with-scratch-conn mem-conn
      @(d/transact mem-conn schema)
      @(d/transact mem-conn sample-data)
      (let [starting-point-db (d/db mem-conn)
            conn (dm/mock-conn starting-point-db)]
        (is (instance? Connection conn))
        (is (= (d/db conn) starting-point-db)))
      ))
  )

(deftest forking-connections
  (testing "Forking connections"
    (with-scratch-conn mem-conn
      @(d/transact mem-conn schema)
      @(d/transact mem-conn sample-data)
      (testing "forked connections live their separate ways"
        (let [conn1 mem-conn
              conn2 (dm/fork-conn conn1)
              conn3 (dm/fork-conn conn2)]
          (doseq [[conn janes-age] [[conn1 18]
                                    [conn2 21]
                                    [conn3 51]]]
            @(d/transact conn [(tx_set-age "jane.doe@gmail.com" janes-age)]))
          (is (= (for [conn [conn1 conn2 conn3]]
                   [conn (ffirst (d/q '[:find ?age :in $ ?email :where
                                        [?p :person/email ?email]
                                        [?p :person/age ?age]] (d/db conn) "jane.doe@gmail.com"))])
                 [[conn1 18]
                  [conn2 21]
                  [conn3 51]]))
          ))
      )))

(deftest housekeeping-methods
  (let [conn (dm/mock-conn)]
    (testing "Housekeeping methods are noops"
      (is (= true (.requestIndex conn)))
      (is (nil? (.release conn)))
      (is (nil? (.gcStorage conn (new Date)))))
    (testing "Sync methods are trivailly implemented"
      (let [db (d/db conn)
            t (d/basis-t db)]
        (doseq [fut [(.sync conn)
                     (.sync conn t)
                     (.syncExcise conn t)
                     (.syncIndex conn t)
                     (.syncSchema conn t)]]
          (is (instance? ListenableFuture fut))
          (is (= @fut db)))
        ))
    ))
