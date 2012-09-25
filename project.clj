(defproject cj-push-server "0.0.1-SNAPSHOT"
  :description "PubSubHubBub Server in Clojure"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [ring/ring-jetty-adapter "1.1.5"]
                 [ring/ring-devel "1.1.5"]
                 [compojure "1.1.3"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [http.async.client "0.5.0-SNAPSHOT"]
                 [org.clojure/java.jdbc "0.2.3"]])

