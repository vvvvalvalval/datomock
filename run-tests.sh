#!/usr/bin/env bash
lein with-profile test,last-clojure,oldest-datomic test
lein with-profile test,last-clojure,last-datomic test

lein with-profile test,clojure7,oldest-datomic test

lein with-profile test,clojure6,oldest-datomic test
