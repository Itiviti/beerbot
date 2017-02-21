(ns beerbot.core
  (:require [clojure.java.shell :refer [sh]]
            [mount.core :refer [defstate start stop]])
  (:require [instaparse.core :refer [parser failure?]])
  (:require [instaparse.transform :refer [transform]])
  (:require [instaparse.failure :refer [pprint-failure]])
  (:require [clojure.string :refer [replace-first]])
  (:require [incanter.core :refer [col-names to-dataset]]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:gen-class)
  (:import (com.ullink.slack.simpleslackapi.impl SlackSessionFactory)
           (java.net Proxy$Type)
           (com.ullink.slack.simpleslackapi.events SlackMessagePosted)
           (com.ullink.slack.simpleslackapi SlackSession)
           (com.ullink.slack.simpleslackapi.listeners SlackMessagePostedListener)
           (java.io DataInputStream DataOutputStream)))

(declare connect)
(declare disconnect)
(declare beerboard)
(declare load-data!)
(declare store-data!)
(def data-path "data/beer.dat")
(def whitespace (parser "whitespace = #'\\s+'"))
(def query-parser (parser (clojure.java.io/resource "query.bnf") :auto-whitespace whitespace))
(defstate configuration :start (read-string (slurp "conf/init.edn")))
(defstate connection
          :start (connect beerboard)
          :stop (disconnect connection))
(defstate db
          :start (load-data!)
          :stop (store-data! db))


(defn listener [f]
  (reify SlackMessagePostedListener
    (onEvent [_ event session]
      (f event session))))

(defn connect [listener-fn]
  (let [{:keys [slack-bot-auth-token proxy-host proxy-port]} configuration
        session (-> (SlackSessionFactory/getSlackSessionBuilder slack-bot-auth-token)
                    (.withProxy Proxy$Type/HTTP proxy-host proxy-port)
                    (.withAutoreconnectOnDisconnection true)
                    (.build))]

    (.addMessagePostedListener session (listener listener-fn))
    (.connect session)
    session))

(defn disconnect [connection]
  (.disconnect connection))

(defn send-message [^SlackMessagePosted event ^SlackSession session message]
  (.sendMessage session (.getChannel event) (str message))
  nil)


(defn help [event session]
  (send-message event session "https://gaia.slack.com/files/vladimir/F48GBKSSZ/beerboard_help"))

(defn entry [from reason to]
  {:from from :to to :reason reason})

(defn offer-beers [{:keys [beers] :as db} from users reason]
  (->> users
       (map (partial entry from reason))
       (concat beers)
       (assoc db :beers)))

(defn board [{:keys [beers]}]
  (->> beers
       (map :to)
       frequencies
       (sort-by second)
       reverse))

(defn notify-users! [event session users reason]
  (doseq [user users]
    (.sendMessageToUser session (.findUserByUserName session (.findUserById session user)) (str "you got a beer from " (-> event .getSender .getRealName) " for " reason " :beer: !!") nil)))

(defn to-real-name [session user-id]
  (->> user-id
      (.findUserById session)
      .getRealName))

(defn wrap [s] (str "```" s "```"))

(defn format-board [session data]
  (->> data
      (map #(vector (to-real-name session (first %)) (second %)))
       vec
       to-dataset
       (#(col-names % ["user" "beer count"]))
       pr-str
       wrap))

(defn nwrite! [data]
  (with-open [w (io/output-stream data-path)]
    (nippy/freeze-to-out! (DataOutputStream. w) data)))

(defn nread! []
  (with-open [r (io/input-stream data-path)]
    (nippy/thaw-from-in! (DataInputStream. r))))

(defn load-data! []
  (atom (nread!)))

(defn store-data! [data]
  (nwrite! @data))

(defn compile-query [query]
  (->> query
       (transform
         {:users     (fn [& users] users)
          :offer     (fn [users reason] (fn [event session data]
                                          (swap! data offer-beers (-> event .getSender .getId) users reason)
                                          (notify-users! event session users reason)))
          :showboard (fn [] (fn [event session data] (send-message event session (format-board session (board @data)))))
          :help      (fn [] (fn [event session _] (help event session)))
          :query     (fn [f] (fn [event session data] (f event session data)))
          })))

(defn run-query [^SlackMessagePosted event ^SlackSession session data query-str]
  (let [parsed (->> query-str
                    query-parser)]
    (if (failure? parsed)
      (send-message event session (with-out-str (pprint-failure parsed)))
      ((compile-query parsed) event session data))))


(defn beerboard [^SlackMessagePosted event ^SlackSession session]
  (let [me (-> session .sessionPersona .getId)
        sender (-> event .getSender .getId)
        channel (.getChannel event)
        content (.getMessageContent event)]
    (when (and (or (.isDirect channel) (.startsWith content (str "<@" me ">")))
               (not (= me sender)))
      (run-query event session db (replace-first content (re-pattern (str "<@" me ">")) "")))))