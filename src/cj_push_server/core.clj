(ns cj-push-server.core
    (:use [compojure.core :only [defroutes GET POST]])
    (:use [ring.middleware stacktrace reload])
    (:use [cj-push-server util model controller])
    (:require [ring.adapter.jetty :as ring]
              [compojure.handler :as handler]
              [clojure.string :as string]
              [compojure.route :as route]))

(defroutes routes
  (POST "/fetch" {} (fetch-action))
  (POST "/" {params :params} 
        (let [hub (:hub params)
              mode (:mode hub)]
          (cond
            (or (= mode "subscribe") (= mode "unsubscribe"))
            (subscription-action hub mode)
            (= mode "publish")
            (publish-action)
            :default
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
