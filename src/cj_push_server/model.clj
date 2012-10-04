(ns cj-push-server.model
    (:use [cj-push-server.util])
    (:require [clojure.java.jdbc :as sql] 
              [clojure.string :as string]
              [clj-time.core :as date]
              [clj-time.format :as datef]
              [clojure.data.xml :as xml]
              [http.async.client :as http]))

(def db
  "Database either from DATABSE_URL Environment variable or defaults"
  (or {:connection-uri (System/getenv "DATABASE_URL")}
      "postgresql://push:push@localhost:5432/push"))

; https://gist.github.com/1177043
(defmacro wrap-connection
  "Wrap the body inside a database connection" 
  [& body]
    `(if (sql/find-connection)
           ~@body
           (sql/with-connection db ~@body)))

(defmacro wrap-transaction
  "Wrap the body inside a database transaction"
  [& body]
    `(if (sql/find-connection)
            (sql/transaction ~@body)
            (sql/with-connection db (sql/transaction ~@body))))

(defn postgres-date [dt]
  (java.sql.Timestamp/valueOf
    (datef/unparse (datef/formatter "YYYY-MM-dd HH:mm:ss") dt)))

(defn postgres-last-insert-id [table col]
  (-> (sql/with-query-results res
                                ["select currval(pg_get_serial_sequence(?,?)) as currval"
                                 (name table) (name col)]
                                (doall res))
    first :currval))

(defprotocol PushServer
  (create-topic [this topic])
  (get-topic [this topic])
  (get-or-create-topic [this topic])
  (mark-topic-fetched [this feed])
  (subscription-exists? [this topic_id callback])
  (create-subscription [this topic_id hub])
  (delete-subscription [this topic_id hub])
  (fetch-required-feeds [this])
  (get-subscriptions [this topic_id])
)

(deftype PostgreSQLPushServer [] PushServer
  (create-topic [this topic]
    (sql/insert-values :topics [:topic :requires_fetching] [topic 1])
    (let [id (postgres-last-insert-id "topics" "id")]
      {:id id :topic topic :requires_fetching 1 :last_fetched_at nil}))

  (get-topic [this topic]
    (sql/with-query-results results ["SELECT * FROM topics WHERE topic = ?" topic]
                            (first results)))

  (get-or-create-topic [this topic]
    (doto (new java.net.URL topic) (.toURI))
      (let [row (get-topic this topic)]
        (if (= row nil)
          (create-topic this topic)
          (do row))))

  (mark-topic-fetched [this feed]
    (let [now (date/now)]
      (sql/update-values :topics ["id = ?" (:id feed)] {:last_fetched_at (postgres-date now)})))

  (subscription-exists? [this topic_id callback]
    (sql/with-query-results results ["SELECT count(*) AS exists FROM subscriptions WHERE topic_id = ? AND callback = ?" 
                                     topic_id
                                     callback]
                            (let [exists (:exists (first results))]
                              (= exists 1))))

  (create-subscription [this topic_id hub]
    (let [callback (:callback hub)]
      (sql/insert-values :subscriptions
                         [:callback :topic_id]
                         [(cast String callback) topic_id]
                         )))
                             
  (delete-subscription [this topic_id hub]
    (let [callback (:callback hub)]
      (sql/delete-rows :subscriptions ["callback = ? AND topic_id = ?" callback topic_id])))


  (fetch-required-feeds [this]
    (let [date (date/minus (date/now) (date/minutes 15))]
      (sql/with-query-results results
                              ["SELECT id, topic FROM topics WHERE last_fetched_at < ? OR last_fetched_at IS NULL" (postgres-date date)]
                              (into [] results))))

  (get-subscriptions [this topic_id]
    (sql/with-query-results results
                            ["SELECT * FROM subscriptions WHERE topic_id = ?" topic_id]
                            (into [] results)))
)

(defn challenge-subscription? [push-server topic_id hub is_subscription]
  (with-open [client (http/create-client)] ; Create client
    (doto (new java.net.URL (:topic hub)) (.toURI))
    (doto (new java.net.URL (:callback hub)) (.toURI))
    (let [challenge (random-str 32)
          req {"hub[mode]" (:mode hub) "hub[topic]" (:topic hub) "hub[challenge]" challenge}
          resp (http/POST client (:callback hub) :body req)
          challenge_check (http/string resp)
          status (http/status resp)]
      (if (and (= (:code status) 200) (= challenge challenge_check))
        (do 
          (if (= is_subscription true)
            (create-subscription push-server topic_id hub)
            (delete-subscription push-server topic_id hub))
          true)
        false))))

(defn distribute-feed-subscription [feed-resp, subscription]
  (with-open [client (http/create-client)]
    (let [resp (http/POST client (:callback subscription) :body (http/string feed-resp))]
        (http/await resp))))

(defn distribute-feed [push-server feed]
  (with-open [client (http/create-client)] ; Create client
    (let [resp (http/GET client (:topic feed))
          subscriptions (get-subscriptions push-server (:id feed))
          resv (http/await resp)]
      (doseq [subscription subscriptions]
        (distribute-feed-subscription resv subscription))
      (mark-topic-fetched push-server feed))))

(defn make-push-server []
  (PostgreSQLPushServer.)
)
