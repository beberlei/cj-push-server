(defproject cj-push-server "0.0.1-SNAPSHOT"
  :description "PubSubHubBub Server in Clojure"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [ring/ring-jetty-adapter "1.1.5"]
                 [ring/ring-devel "1.1.5"]
                 [compojure "1.1.3"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [http.async.client "0.5.0-SNAPSHOT"]
                 [clj-time "0.4.4"]
                 [org.clojars.scsibug/feedparser-clj "0.4.0"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/data.xml "0.0.6"]
                 [org.clojure/java.jdbc "0.2.3"]])

