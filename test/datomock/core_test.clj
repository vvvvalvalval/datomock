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
          (is (= @fut db)))))))

(deftest sync-methods
  (testing "when sync is called with a t, returns a future that acquires a db such that its basisT >= t"
      (let [conn (dm/mock-conn)
            _ @(d/transact conn [])
            requested-t (+ (d/basis-t (d/db conn)) 200)
            f (d/sync conn requested-t)]
        ;; empty transactions to increase basisT to requested-t
        (future
          (dotimes [_ 200]
            @(d/transact conn [])))
        (is (>= (d/basis-t @f) requested-t))
        (is (future-done? f))))

  (testing "the future returned by sync blocks when called with a t that is not yet available"
      (let [conn (dm/mock-conn)
            _ @(d/transact conn [])
            requested-t (+ (d/basis-t (d/db conn)) 200)
            f (d/sync conn requested-t)]
        ;; empty transactions to increase basisT, but not enough to reach requested-t
        (dotimes [_ 199]
          @(d/transact conn []))
        (is (not (future-done? f)) )
        (is (= :timeout (deref f 10 :timeout)) ))))

(deftest log
  (let [conn (let [uri (str "datomic:mem://" "datomock-" (UUID/randomUUID))]
               (d/create-database uri)
               (d/connect uri))
        mem-supports-log? (some? (d/log conn))
        t0 (d/basis-t (d/db conn))
        t1 (-> @(d/transact conn schema) :db-after d/basis-t)
        t2 (-> @(d/transact conn sample-data) :db-after d/basis-t)
        t3 (d/next-t (d/db conn))
        f1a (fork-conn conn)
        f1b (fork-conn conn)
        f2 (fork-conn f1a)
        tx1 [(tx_set-age "foo.bar@gmail.com" 1)]

        tx-range (fn [conn start-t end-t]
                   (let [txInstant-id (d/entid (d/db conn) :db/txInstant)]
                     (vec
                       (when-let [log (d/log conn)]
                         (->> (d/tx-range log start-t end-t) seq
                           (map (fn [{:keys [t data]}]
                                  {:t t
                                   :data (->> data
                                           (remove (fn [datom]
                                                     (-> datom :a (= txInstant-id))))
                                           vec)}))
                           )))))]
    @(d/transact conn tx1)
    @(d/transact f1a tx1)
    @(d/transact f2 tx1)
    (testing "same log for original conn and fork"
      (when mem-supports-log?
        (is
          (=
            (tx-range conn nil nil)
            (tx-range f1a nil nil)
            (tx-range f2 nil nil))))
      (is (=
            (tx-range conn nil t3)
            (tx-range f1a nil t3)
            (tx-range f1b nil t3)
            (tx-range f2 nil t3)))

      (when mem-supports-log?
        (is
          (=
            (tx-range conn t3 nil)
            (tx-range f1a t3 nil)
            (tx-range f2 t3 nil))))
      (is (-> (tx-range f2 t3 nil) count (= 1))))
    (testing "transacting to original leaves fork unaffected"
      (is (empty? (tx-range f1b t3 nil))))
    (testing "transacting to fork leaves original unaffected"
      (let [tx2 [(tx_set-age "foo.bar@gmail.com" 1)]
            f3 (dm/fork-conn conn)
            f4 (dm/fork-conn f2)
            t4 (d/next-t (d/db conn))]
        @(d/transact f3 tx2)
        (is (empty? (tx-range conn t4 nil)))
        (is (= (tx-range conn nil nil) (tx-range conn nil t4)))

        @(d/transact f4 tx2)
        (is (empty? (tx-range f2 t4 nil)))
        (is (= (tx-range f2 nil nil) (tx-range f2 nil t4)))
        ))
    (d/release conn)))


(deftest transact-async
  (let [conn (dm/mock-conn)]

    @(d/transact conn [{:db/id (d/tempid :db.part/user)
                        :db/ident :test/name
                        :db/valueType :db.type/string
                        :db/cardinality :db.cardinality/one
                        :db.install/_attribute :db.part/db}
                       {:db/ident :test}
                       {:db/ident :after-sleep
                        :db/fn (d/function {:lang "clojure"
                                            :doc "fake delay, to test async transactions"
                                            :params '[db millis facts]
                                            :code '(do (Thread/sleep millis)
                                                       facts)})}])

    @(d/transact conn [{:db/ident :test
                        :test/name "a"}])

    (let [ft (d/transact-async conn [[:after-sleep 100
                                      [{:db/ident :test
                                        :test/name "b"}]]])]
      (testing "after submitting the tx to change :test/name of :test to b, but before the tx completes, :test/name still had the previous value"
        (is (not (future-done? ft)))
        (is (= "a" (:test/name (d/pull (d/db conn) '[*] :test)))))
      (testing "after tx is completed  :test/name of :test has the new value"
        @ft
        (is (future-done? ft))
        (is (= "b" (:test/name (d/pull (d/db conn) '[*] :test))))))

    (d/release conn)))
