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
                  (with-open [client (http/create-client)] ; Create client
                    (let [req {"hub[mode]" (:mode hub) "hub[topic]" (:topic hub) "hub[challenge]" challenge}
                          resp (http/POST client (:callback hub) :body req)
                          status (http/status resp)]
                      (if (and (= (:code status) 200) (= challenge (http/string resp)))
                        (sql/with-connection
                          {:connection-uri (System/getenv "DATABASE_URL")}
                          (sql/insert-values :subscriptions
                                             [:callback :topic :verify]
                                             [(:callback hub) (:topic hub) challenge])
                          {:status 204 :body ""})
                        {:status 400 :body "Challenge was not accepted."})))))
             (if (= mode "publish")
               (if (string/blank? (:topic hub))
                 {:status 400 :body "Topic is required"}
                 (sql/with-connection
                   {:connection-uri (System/getenv "DATABASE_URL")} 
                   (sql/with-query-results results
                                           ["SELECT * FROM subscriptions WHERE topic = ?" (:topic hub)]
                                           (into [] results))
                   {:status 204 :body ""}))
               {:status 400 :body "Unknown mode"}))))
  (GET "/" [] {:status 204 :body ""}))

(def app
  (-> #'routes
      (wrap-reload '(cj-push-server.core))
      (handler/site)
      (wrap-stacktrace)))

(defn start []
  (ring/run-jetty app {:port 8080 :join? false}))

(defn -main []
  (let [port (Integer. (System/getenv "PORT"))]
    (start port)))
