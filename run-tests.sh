#!/usr/bin/env bash
lein with-profile last-clojure,oldest-datomic test
lein with-profile last-clojure,last-datomic test

lein with-profile clojure7,oldest-datomic test

lein with-profile clojure6,oldest-datomic test
