(ns cj-push-server.core
    (:use [compojure.core :only [defroutes GET POST]])
    (:use [ring.middleware stacktrace reload])
    (:require [ring.adapter.jetty :as ring]
              [compojure.handler :as handler]
              [clojure.java.jdbc :as sql] 
              [clojure.string :as string]
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

(defroutes routes
  (POST "/" {params :params} 
        (let [challenge (random-str 32)
              hub (:hub params)
              mode (:mode hub)]
          (if (= mode "subscribe")
            (if (string/blank? (:callback hub))
              {:status 400 :body "Callback is required"}
              (if (string/blank? (:topic hub))
                {:status 400 :body "Topic is required"}
                (sql/with-connection
                  {:connection-uri (System/getenv "DATABASE_URL")}
                  (let [topic_row (get-or-create-topic (:topic hub))
                        topic_id (:id topic_row)]
                    (if (subscription-exists? topic_id (:callback hub))
                      {:status 409 :body "Subscription already exists"}
                      (with-open [client (http/create-client)] ; Create client
                        (doto (new java.net.URL (:topic hub)) (.toURI))
                        (doto (new java.net.URL (:callback hub)) (.toURI))
                        (let [req {"hub[mode]" (:mode hub) "hub[topic]" (:topic hub) "hub[challenge]" challenge}
                              resp (http/POST client (:callback hub) :body req)
                              status (http/status resp)]
                          (if (and (= (:code status) 200) (= challenge (http/string resp)))
                            (sql/insert-values :subscriptions
                                               [:callback :topic_id]
                                               [(:callback hub) topic_id])
                          {:status 204 :body ""})
                        {:status 400 :body "Challenge was not accepted."})))))))
             (if (= mode "publish")
               (if (string/blank? (:topic hub))
                 {:status 400 :body "Topic is required"}
                 (sql/with-connection {:connection-uri (System/getenv "DATABASE_URL")} 
                   (let [topic-id (get-topic (:topic hub))]
                     (doto (new java.net.URL (:topic hub)) (.toURI))
                     (sql/with-query-results results
                                             ["SELECT * FROM subscriptions WHERE topic_id = ?" topic-id]
                                           (into [] results))
                   {:status 204 :body ""})))
               {:status 400 :body "Unknown mode"}))))
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
