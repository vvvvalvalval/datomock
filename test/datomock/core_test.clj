(ns datomock.core-test
  (:require [clojure.test :as test :refer :all]
            [datomic.api :as d]
            [datomock.core :as dm :refer :all]
            [datomic.promise]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec assert-check]]
            [datomock.test.utils :as tu]
            [datomock.impl :as impl])
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
        (let [fut-res (transact-fn conn tx)]
          (= :ok (try
                   @fut-res
                   :no-exception
                   (catch ExecutionException _ :ok)
                   (catch Throwable _ :wrong-exception))))
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

(defn- thrown-derefed
  ^Throwable
  [p]
  (try
    @p
    nil
    (catch Throwable err
      err)))

(deftest datomic-ListenableFuture-impl
  (testing "datomic.promise/settable-future, which behaviour we rely on to implement the return value of datomic.api/transact, and which may be subject implementation detail."
    (testing "datomic.promise/settable-future returns an instance of datomic.api.ListenableFuture"
      (let [p (datomic.promise/settable-future)]
        (is (instance? ListenableFuture p))))
    (testing "The promise can be resolved via clojure.core/deliver"
      (let [p (datomic.promise/settable-future)
            v (Object.)]
        (deliver p v)
        (is (identical? @p v)))
      (testing
        "Unlike clojure.core/promise, the promise can be set to a failed state by delivering a Throwable to it. Derefing will throw an ExecutionException wrapping the delivered Throwable."
        (doseq [t [(Throwable.)
                   (ex-info "aaaaaaargh" {:x 42})
                   (ExecutionException. (ex-info "aaaaaaargh" {}))]]
          (let [p (datomic.promise/settable-future)]
            (deliver p t)
            (let [t1 (thrown-derefed p)]
              (is (instance? ExecutionException t1))
              (is (identical? (.getCause t1) t))))))
      )))

;; ------------------------------------------------------------------------------
;; Log API

(defn- tx-add-v-at
  "Creates transaction data which creates a named entity at a specified :db/txInstant `txInstant`;
  useful for checking whether the transaction appears in the Log."
  [txInstant v]
  {:pre [(instance? Date txInstant)
         (keyword? v)]}
  [[:db/add (d/tempid :db.part/tx) :db/txInstant txInstant]
   [:db/add (d/tempid :db.part/user) :db/ident v]])

(defn- collect-added
  "Finds the value added as by tx-add-v in a sequence of Log entries."
  [log-range]
  (->> log-range
    (mapcat :data)
    (map :v)
    (filter keyword?)
    vec))

(defn prop--well-behaved-log
  [make-empty-conn]
  (prop/for-all
    [[v+d+ts
      log
      [start-type startT]
      [end-type endT]]
     (gen/let [ds (gen/fmap sort
                    (gen/list tu/gen-legal-tx-instant))
               n-post-vs gen/nat]
       (let [conn (make-empty-conn)
             t-init (d/basis-t (d/db conn))
             v+d+ts
             (into []
               (map-indexed
                 (fn [i d]
                   (let [v (keyword (str "v_" i))
                         {:keys [db-after]}
                         @(d/transact conn
                            (tx-add-v-at d v))
                         t (d/basis-t db-after)]
                     [v d t])))
               ds)
             log (d/log conn)]
         ;; NOTE: side effect - adding some more values to make sure the log's immutable (Val, 02 Jul 2018)
         (let [max-d (impl/find-db-txInstant (d/db conn))]
           (dotimes [i n-post-vs]
             @(d/transact conn
                (tx-add-v-at max-d (keyword (str "post_" i))))))
         (gen/let [[[start-type startT]
                    [end-type endT]]
                   (gen/vector
                     (gen/one-of
                       (cond->
                         [(gen/return [:nil nil])
                          (gen/let [d tu/gen-legal-tx-instant]
                            [:date d])
                          (gen/let [t (gen/elements (into [t-init]
                                                      (map (fn [[_v _d t]] t))
                                                      v+d+ts))]
                            [:t t])]
                         (seq v+d+ts)
                         (into
                           [(gen/let [t (gen/elements (map (fn [[_v _d t]] t) v+d+ts))]
                              [:tx (d/t->tx t)])])))
                     2)]
           [v+d+ts
            log
            [start-type startT]
            [end-type endT]])))]
    (let [expected-vs
          (->> v+d+ts
            (filter
              (fn [[_v ^Date d t]]
                (case start-type
                  :nil true
                  :date (<= (.getTime ^Date startT) (.getTime d))
                  :t (<= startT t)
                  :tx (<= (d/tx->t startT) t))))
            (filter
              (fn [[_v ^Date d t]]
                (case end-type
                  :nil true
                  :date (< (.getTime d) (.getTime ^Date endT))
                  :t (< t endT)
                  :tx (< t (d/tx->t endT)))))
            (mapv first))
          txr (d/tx-range log startT endT)
          actual-vs (collect-added txr)]
      (= expected-vs actual-vs))))

(def mem-conn-supports-log?
  (memoize
    (fn []
      (with-scratch-conn conn
        (some? (d/log conn))))))

(defspec mock-conn-has-well-behaved-log
  50
  (prop--well-behaved-log dm/mock-conn))

(deftest mem-conn-has-well-behaved-log
  (when (mem-conn-supports-log?)
    (let [conn-uris (atom [])
          make-empty-conn
          (fn []
            (let [uri (str "datomic:mem://datomock-test-" (UUID/randomUUID))]
              (d/create-database uri)
              (let [conn (d/connect uri)]
                (swap! conn-uris conj uri)
                conn)))]
      (try
        (assert-check
          (tc/quick-check 50
            (prop--well-behaved-log make-empty-conn)))
        (finally
          (doseq [uri @conn-uris]
            (d/delete-database uri)))))))

(deftest forked-log--examples
  (letfn [(add-v-at! [conn v d]
            (-> @(d/transact conn
                   (tx-add-v-at d v))
              :db-after d/basis-t))]
    (let [conn-origin (dm/mock-conn)
          to0 (add-v-at! conn-origin :o0 #inst "2000")
          to1 (add-v-at! conn-origin :o1 #inst "2001")
          conn-forked (dm/fork-conn conn-origin)
          tf1-bis (add-v-at! conn-forked :f1-bis #inst "2001")
          to1-bis (add-v-at! conn-origin :o1-bis #inst "2001")
          tf2 (add-v-at! conn-forked :f2 #inst "2002")
          to3 (add-v-at! conn-origin :o3 #inst "2003")
          tf4 (add-v-at! conn-forked :f4 #inst "2004")
          log-origin (d/log conn-origin)
          log-forked (d/log conn-forked)]
      (is
        (= [:o0])
        (collect-added (d/tx-range log-forked to0 to1)))
      (is
        (= [:o0 :o1]
          (collect-added (d/tx-range log-forked to0 tf1-bis))))
      (is
        (= [:o0 :o1 :f1-bis :f2 :f4]
          (collect-added (d/tx-range log-forked nil nil))))
      (is
        (= [:o0 :o1 :o1-bis :o3]
          (collect-added (d/tx-range log-origin nil nil))))
      (is
        (= [:o0 :o1 :f1-bis :f2]
          (collect-added (d/tx-range log-forked nil tf4))))
      (is
        (= [:o0 :o1 :o1-bis :o3]
          (collect-added (d/tx-range log-origin nil tf4))))
      (is
        (= [:o0 :o1 :f1-bis]
          (collect-added (d/tx-range log-forked nil #inst "2002"))))
      (is
        (= [:o0 :o1 :f1-bis :f2]
          (collect-added (d/tx-range log-forked nil #inst "2002-02"))))
      (is
        (= [:o0 :o1 :f1-bis :f2]
          (collect-added (d/tx-range log-forked nil #inst "2003"))))
      (is
        (= [:f2 :f4]
          (collect-added (d/tx-range log-forked #inst "2001-02" nil))))
      (is
        (= []
          (collect-added (d/tx-range log-forked #inst "2001" #inst "2001"))))
      (is
        (= []
          (collect-added (d/tx-range log-forked to1 #inst "2001"))))
      )))
