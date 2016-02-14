(defproject datomock "0.1.0-SNAPSHOT"
  :description "Mocking and forking Datomic connections locally."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]]

  :profiles {:dev {:dependencies [[com.datomic/datomic-free "0.9.5350"]]}})
