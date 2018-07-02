(ns datomock.test.utils
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [datomock.core :as dm]
            [clojure.test.check.generators :as gen])
  (:import (java.util Date)))


(defn gen-date*
  [{:keys [min max]}]
  (gen/let [time (gen/large-integer*
                   (cond-> {}
                     (some? min) (assoc :min (.getTime ^Date min))
                     (some? max) (assoc :max (.getTime ^Date max))))]
    (Date. (long time))))

(def gen-legal-tx-instant
  (gen-date* {:min #inst "1970" :max #inst "2010"}))

(def gen-basic-txs-chain
  (gen/let [tx-insts (gen/list-distinct gen-legal-tx-instant {:max-elements 100})]
    (->> tx-insts
      sort
      (map-indexed
        (fn [i tx-inst]
          [{:db/id (d/tempid :db.part/tx (- (inc i)))
            :db/txInstant tx-inst}])))))

(defn db-after-tx-chain
  [txs]
  (let [init-db (dm/empty-db)]
    (reduce
      (fn [db tx] (:db-after (d/with db tx)))
      init-db txs)))
