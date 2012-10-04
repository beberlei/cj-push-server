(ns cj-push-server.controller
    (:use [cj-push-server.util])
    (:use [cj-push-server.model])
    (:require [clojure.string :as string]))

(def ^{:private true} push-server (make-push-server))

(defn subscription-action [hub mode]
  (if (string/blank? (:callback hub))
    {:status 400 :body "Callback is required for subscription/unsubscription"}
    (if (string/blank? (:topic hub))
      {:status 400 :body "Topic is required for subscription/unsubscription"}
      (wrap-connection
        (let [topic_row (get-or-create-topic push-server (:topic hub))
              topic_id (:id topic_row)]
          (if (and (= mode "subscription") (subscription-exists? push-server topic_id (:callback hub)))
            {:status 409 :body "Subscription already exists"}
            (if (challenge-subscription? push-server topic_id hub (= mode "subscribe"))
              {:status 204 :body ""}
              {:status 400 :body "Challenge was not accepted."})))))))

(defn fetch-action []
  (wrap-connection
    (let [feeds (fetch-required-feeds push-server)]
      (doseq [feed feeds]
        (distribute-feed push-server feed))
      {:status 202 :body "" })))

(defn publish-action [hub]
  (if (string/blank? (:topic hub))
    {:status 400 :body "Topic is required for publishing"}
    (wrap-connection
      (let [feed (get-topic push-server (:topic hub))]
        (distribute-feed push-server feed)
        {:status 204 :body ""}))))
