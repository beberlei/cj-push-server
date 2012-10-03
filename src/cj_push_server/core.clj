(ns cj-push-server.core
    (:use [compojure.core :only [defroutes GET POST]])
    (:use [ring.middleware stacktrace reload])
    (:require [ring.adapter.jetty :as ring]
              [compojure.handler :as handler]
              [clojure.java.jdbc :as sql] 
              [clojure.string :as string]
              [clj-time.core :as date]
              [clj-time.format :as datef]
              [clojure.data.xml :as xml]
              [http.async.client :as http]
              [compojure.route :as route]))

;debugging parts of expressions
(defmacro dbg[x] `(let [x# ~x] (println "dbg:" '~x "=" x#) x#))

(def VALID-CHARS
  (map char (concat (range 48 58) ; 0-9
                    (range 66 91) ; A-Z
                    (range 97 123)))) ; a-z

(defn random-char []
  (nth VALID-CHARS (rand (count VALID-CHARS))))

(defn random-str [length]
  (apply str (take length (repeatedly random-char)))) 

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
  (challenge-subscription [this topic_id hub is_subscription success failure])
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

  (challenge-subscription [this topic_id hub is_subscription success failure]
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
              (create-subscription this topic_id hub)
              (delete-subscription this topic_id hub))
            success)
          failure))))

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

(defn distribute-feed-subscription [feed-resp, subscription]
  (with-open [client (http/create-client)]
    (let [resp (http/POST client (:callback subscription) :body (http/string feed-resp))]
        (http/await resp))))

(defn distribute-feed [storage feed]
  (with-open [client (http/create-client)] ; Create client
    (let [resp (http/GET client (:topic feed))
          subscriptions (get-subscriptions storage (:id feed))
          resv (http/await resp)]
      (doseq [subscription subscriptions]
        (distribute-feed-subscription resv subscription))
      (mark-topic-fetched storage feed))))

(defn- make-push-server []
  (PostgreSQLPushServer.)
)

(def ^{:private true} push-server (make-push-server))

(defroutes routes
  (POST "/" {params :params} 
        (let [hub (:hub params)
              mode (:mode hub)]
          (cond
            (or (= mode "subscribe") (= mode "unsubscribe"))
            (if (string/blank? (:callback hub))
              {:status 400 :body "Callback is required for subscription/unsubscription"}
              (if (string/blank? (:topic hub))
                {:status 400 :body "Topic is required for subscription/unsubscription"}
                (wrap-connection
                  (let [topic_row (get-or-create-topic push-server (:topic hub))
                        topic_id (:id topic_row)]
                    (if (and (= mode "subscription") (subscription-exists? push-server topic_id (:callback hub)))
                      {:status 409 :body "Subscription already exists"}
                      (challenge-subscription push-server topic_id hub (= mode "subscribe")
                                                   {:status 204 :body ""}
                                                   {:status 400 :body "Challenge was not accepted."}))))))
            (= mode "fetch")
            (wrap-connection
              (let [feeds (fetch-required-feeds push-server)]
                (doseq [feed feeds]
                  (distribute-feed push-server feed))
                {:status 202 :body "" }
              ))
            (= mode "publish") ; not finally implemented, because we don't act as a hub for now.
            (if (string/blank? (:topic hub))
              {:status 400 :body "Topic is required for publishing"}
              (wrap-connection
                (let [topic-id (get-topic push-server (:topic hub))]
                  (doto (new java.net.URL (:topic hub)) (.toURI))
                  (sql/with-query-results results
                                          ["SELECT * FROM subscriptions WHERE topic_id = ?" topic-id]
                                          (into [] results))
                  {:status 204 :body ""})))
            (= true true)
            {:status 400 :body "Unknown mode"})))
  (GET "/" [] {:status 204 :body ""}))

(def app
  (-> #'routes
      (wrap-reload '(cj-push-server.core))
      (handler/api)
      (wrap-stacktrace)))

(defn start []
  (ring/run-jetty app {:port 8080 :join? false}))

(defn -main []
  (let [port (Integer. (System/getenv "PORT"))]
    (start port)))
