(defproject vvvvalvalval/datomock "0.2.2"
  :description "Mocking and forking Datomic connections locally."
  :url "https://github.com/vvvvalvalval/datomock"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]]

  :profiles
  {:test {:dependencies [[org.clojure/test.check "0.9.0"]]}

   :dev {:dependencies [[com.datomic/datomic-free "0.9.5697"]
                        [org.clojure/clojure "1.8.0"]
                        [criterium "0.4.3"]]}
   :last-datomic-pro
   {:repositories
    {"my.datomic.com" {:url "https://my.datomic.com/repo"}}
    :dependencies
    [[com.datomic/datomic-pro "1.0.6527"]]}

   :last-datomic
   {:dependencies [[com.datomic/datomic-free "0.9.5697"]]}
   :oldest-datomic
   {:dependencies [[com.datomic/datomic-free "0.9.4470"]]}

   :last-clojure
   {:dependencies [[:org.clojure/clojure "1.11.1"]]}
   :clojure7
   {:dependencies [[:org.clojure/clojure "1.7.0"]]}})
