(ns cj-push-server.models.migration
  (:require [clojure.java.jdbc :as sql]))

(defn create-subscriptions []
  (sql/create-table :subscriptions
                    [:id :serial "PRIMARY KEY"]
                    [:callback :varchar "NOT NULL"]
                    [:topic_id :integer "NOT NULL"]
                    [:created_at :timestamp  "DEFAULT CURRENT_TIMESTAMP"]
                    ["UNIQUE" "(topic_id, callback)"]))

(defn create-topics []
  (sql/create-table :topics
                    [:id :serial "PRIMARY KEY"]
                    [:topic :varchar "NOT NULL"]
                    [:requires_fetching :int "NOT NULL DEFAULT 0"]
                    [:last_fetched_at :timestamp "DEFAULT NULL"]
                    [:created_at :timestamp  "DEFAULT CURRENT_TIMESTAMP"]))

(defn drop-tables []
  (do
    (try (sql/drop-table :topics) (catch java.sql.BatchUpdateException e nil)) 
    (try (sql/drop-table :subscriptions) (catch java.sql.BatchUpdateException e nil)))) 

(defn migrate []
  (sql/with-connection {:connection-uri (System/getenv "DATABASE_URL")}
    (drop-tables)
    (create-subscriptions)
    (create-topics)))

(defn -main []
  (print "Migrating database...") (flush)
  (migrate)
  (println " done"))
