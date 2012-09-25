(ns cj-push-server.models.migration
  (:require [clojure.java.jdbc :as sql]))

(defn create-subscriptions []
  (sql/with-connection {:connection-uri (System/getenv "DATABASE_URL")}
    (sql/create-table :subscriptions
                      [:id :serial "PRIMARY KEY"]
                      [:callback :varchar "NOT NULL"]
                      [:topic :varchar "NOT NULL"]
                      [:last_fetched_at :timestamp  "DEFAULT CURRENT_TIMESTAMP"]
                      [:created_at :timestamp  "DEFAULT CURRENT_TIMESTAMP"])))

(defn -main []
  (print "Migrating database...") (flush)
  (create-subscriptions)
  (println " done"))
