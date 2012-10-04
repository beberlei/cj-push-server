(ns cj-push-server.test.core
  (:use [cj-push-server model util])
  (:use [clojure.test]))

(def ^{:private true} push-server (make-push-server))

(deftest push-get-or-create-topic
  (is (= "http://www.beberlei.de" (:topic (wrap-transaction (get-or-create-topic push-server "http://www.beberlei.de"))))))

(deftest push-get-unknown-topic
  (is (= nil (wrap-transaction (get-topic push-server "http://www.foobar.com/feed.xml")))))

(deftest push-mark-topic-fetched
    (wrap-transaction
      (let [feed (get-or-create-topic push-server "http://www.foobar.com/feed2.xml")]
        (mark-topic-fetched push-server feed)
        ))
    (is (not= nil (:last_fetched_at (wrap-transaction (get-topic push-server "http://www.foobar.com/feed2.xml"))))))

(deftest subscription-not-exists
  (is (= false (wrap-transaction (subscription-exists? push-server 1 "http://callback.com/1")))))

(deftest subscription-exists
  (wrap-transaction 
    (let [callback (str "http://www.beberlei.de/" (random-str 16))
          feed (get-or-create-topic push-server "http://www.foobar.com/feed3.xml")]
      (create-subscription push-server (:id feed) {:callback callback})
      (is (= true (wrap-transaction (subscription-exists? push-server (:id feed) callback)))))))
  
