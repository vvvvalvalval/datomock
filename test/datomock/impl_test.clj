(ns datomock.impl-test
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [datomock.test.utils :as tu]
            [datomock.impl :as impl]
            [datomock.core :as dm]
            [datomic.api :as d])
  (:import (java.util Date)))

(defspec gen-basic-txs-chain--yields-valid-tx-chains
  10
  (prop/for-all [txs tu/gen-basic-txs-chain]
    (some? (tu/db-after-tx-chain txs))))

(defspec coerce-to-t--works-idempotently
  100
  (prop/for-all [txs tu/gen-basic-txs-chain]
    (let [db (tu/db-after-tx-chain txs)
          tid-tx (d/tempid :db.part/tx)
          tx [[:db/add tid-tx :db/txInstant #inst "2012"]]
          {:keys [tempids db-after]} (d/with db tx)
          t (d/basis-t db-after)
          tx-eid (d/resolve-tempid db-after tempids tid-tx)]
      (=
        t
        (impl/coerce-to-t tx-eid)
        (impl/coerce-to-t t))
      )))

(deftest find-db-txInstant--works-on-empty-db
  (is
    (instance? Date (impl/find-db-txInstant (dm/empty-db)))))

(defspec find-db-txInstant--finds-the-txInstant-of-the-last-tx
  100
  (prop/for-all [txs tu/gen-basic-txs-chain
                 d (tu/gen-date* {:min #inst "2011" :max #inst "2012"})
                 other-date (tu/gen-date* {})]
    (let [db (tu/db-after-tx-chain txs)
          tid-tx (d/tempid :db.part/tx)
          tx [[:db/add (d/tempid :db.part/user) :db/txInstant other-date]
              [:db/add tid-tx :db/txInstant d]]
          {:keys [db-after]} (d/with db tx)]
      (= d (impl/find-db-txInstant db-after)))))