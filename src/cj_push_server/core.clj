(ns cj-push-server.core
    (:use [compojure.core :only [defroutes GET POST]])
    (:use [ring.middleware stacktrace reload])
    (:require [ring.adapter.jetty :as ring]
              [compojure.handler :as handler]
              [clojure.java.jdbc :as sql] 
              [clojure.string :as string]
              [compojure.route :as route]))

(defroutes routes
  (POST "/subscribe" {params :params} 
        (let [hub (:hub params)]
          (if (string/blank? (:callback hub))
              {:status 400 :body "Callback is required"}
              (if (string/blank? (:topic hub))
                {:status 400 :body "Topic is required"}
                (if (string/blank? (:verify hub))
                  {:status 400 :body "Verify is required"}
                  (sql/with-connection
                    {:connection-uri (System/getenv "DATABASE_URL")}
                    (sql/insert-values :subscriptions
                                       [:callback :topic :verify]
                                       [(:callback hub) (:topic hub) (:verify hub)]
                                       
                                       )
                    {:status 204 :body ""}))))))
  (GET "/" [] {:status 200 :body "Hallo Ruthi!\n"})
  (GET "/hello" [name] (str "Hello " name "\n" ))
  (POST "/test" {params :params} (str "Foobar " (:x params) "\n")))

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
