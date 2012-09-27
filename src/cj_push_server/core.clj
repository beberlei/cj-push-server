(ns cj-push-server.core
    (:use [compojure.core :only [defroutes GET POST]])
    (:use [ring.middleware stacktrace reload])
    (:require [ring.adapter.jetty :as ring]
              [compojure.handler :as handler]
              [clojure.java.jdbc :as sql] 
              [clojure.string :as string]
              [clj-time.core :as date]
              [http.async.client :as http]
              [compojure.route :as route]))

(def VALID-CHARS
  (map char (concat (range 48 58) ; 0-9
                    (range 66 91) ; A-Z
                    (range 97 123)))) ; a-z

(defn random-char []
  (nth VALID-CHARS (rand (count VALID-CHARS))))

(defn random-str [length]
  (apply str (take length (repeatedly random-char)))) 

(defn postgres-last-insert-id [table col]
  (-> (sql/with-query-results res
                                ["select currval(pg_get_serial_sequence(?,?)) as currval"
                                 (name table) (name col)]
                                (doall res))
    first :currval))

(defn create-topic [topic]
  (sql/insert-values :topics [:topic :requires_fetching] [topic 1])
  (let [id (postgres-last-insert-id "topics" "id")]
    {:id id :topic topic :requires_fetching 1 :last_fetched_at nil}))

(defn get-topic [topic]
  (sql/with-query-results results ["SELECT * FROM topics WHERE topic = ?" topic]
                          (first results)))

(defn get-or-create-topic [topic]
  (doto (new java.net.URL topic) (.toURI))
    (let [row (get-topic topic)]
      (if (= row nil)
        (create-topic topic)
        (do row))))

(defn subscription-exists? [topic_id callback]
  (sql/with-query-results results ["SELECT count(*) AS exists FROM subscriptions WHERE topic_id = ? AND callback = ?" 
                                   topic_id
                                   callback]
                          (let [exists (:exists (first results))]
                            (= exists 1))))

(defn push-create-subscription [topic_id hub]
  (let [callback (:callback hub)]
    (sql/insert-values :subscriptions
                       [:callback :topic_id]
                       [(cast String callback) topic_id]
                       )))
                             
(defn push-delete-subscription [topic_id hub]
  (let [callback (:callback hub)]
    (sql/delete-rows :subscriptions ["callback = ? AND topic_id = ?" callback topic_id])))

(defn push-challenge-subscription [topic_id hub is_subscription success failure]
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
            (push-create-subscription topic_id hub)
            (push-delete-subscription topic_id hub))
          success)
        failure))))

(defn push-fetch-required-feeds []
  (let [date (date/minus (date/now) (date/minutes 15))]
    (sql/with-query-results results
                            ["SELECT id, topic FROM topics WHERE last_fetched_at < ?" date]
                            (into results))))

(defn push-fetch-feed [feed]
  (with-open [client (http/create-client)] ; Create client
    (let [resp (http/GET client (:topic feed))
          status (http/status resp)]
      (if (= (:code status) 200) (true) (true)))))

(defroutes routes
  (POST "/" {params :params} 
        (let [hub (:hub params)
              mode (:mode hub)]
          (cond
            (= (or (= mode "subscribe") (= mode "unsubscribe")))
            (if (string/blank? (:callback hub))
              {:status 400 :body "Callback is required"}
              (if (string/blank? (:topic hub))
                {:status 400 :body "Topic is required"}
                (sql/with-connection
                  {:connection-uri (System/getenv "DATABASE_URL")}
                  (let [topic_row (get-or-create-topic (:topic hub))
                        topic_id (:id topic_row)]
                    (if (and (= mode "subscription") (subscription-exists? topic_id (:callback hub)))
                      {:status 409 :body "Subscription already exists"}
                      (push-challenge-subscription topic_id hub (= mode "subscribe")
                                                   {:status 204 :body ""}
                                                   {:status 400 :body "Challenge was not accepted."}))))))
            (= mode "fetch")
            (sql/with-connection
              {:connection-uri (System/getenv "DATABASE_URL")}
              (let [feeds push-fetch-required-feeds]
                (apply push-fetch-feed feeds)
              ))
            (= mode "publish") ; not finally implemented, because we don't act as a hub for now.
            (if (string/blank? (:topic hub))
              {:status 400 :body "Topic is required"}
              (sql/with-connection {:connection-uri (System/getenv "DATABASE_URL")} 
                                   (let [topic-id (get-topic (:topic hub))]
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
