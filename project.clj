(defproject vvvvalvalval/datomock "0.1.0-SNAPSHOT"
  :description "Mocking and forking Datomic connections locally."
  :url "https://github.com/vvvvalvalval/datomock"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]]

  :profiles
  {:dev {:dependencies [[com.datomic/datomic-free "0.9.5350"]
                        [criterium "0.4.3"]]}
   :last-datomic
   {:dependencies [[com.datomic/datomic-free "0.9.5350"]]}
   :oldest-datomic
   {:dependencies [[com.datomic/datomic-free "0.9.4470"]]}

   :last-clojure
   {:dependencies [[:org.clojure/clojure "1.8.0"]]}
   :clojure7
   {:dependencies [[:org.clojure/clojure "1.7.0"]]}
   :clojure6
   {:dependencies [[:org.clojure/clojure "1.6.0"]]}})
