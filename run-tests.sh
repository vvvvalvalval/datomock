#!/usr/bin/env bash
lein with-profile test,last-clojure,oldest-datomic test
lein with-profile test,last-clojure,last-datomic test

# Uncomment if you have a datomic-pro license
# and access to the dependency via maven.
#lein with-profile test,last-clojure,last-datomic-pro test

lein with-profile test,clojure7,oldest-datomic test
