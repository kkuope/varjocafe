(ns varjocafe.backend
  (:import (org.apache.commons.io FileUtils)
           (java.io File)
           (org.joda.time DateTime)
           (org.joda.time.format ISODateTimeFormat DateTimeFormatter))
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [clojure.algo.generic.functor :refer [fmap]]
            [org.httpkit.client :as http]))

(defprotocol Backend
  (get-restaurants [this])
  (get-restaurant [this id]))


; RemoteBackend

(defn- json-body [response]
  (if (= 200 (:status response))
    (json/read-str (:body response) :key-fn keyword)
    (do
      (log/warn "Request failed:" (pr-str response))
      nil)))

(deftype RemoteBackend [base-url]
  Backend
  (get-restaurants [_] (future (json-body @(http/get (str base-url "/restaurants")))))
  (get-restaurant [_ id] (future (json-body @(http/get (str base-url "/restaurant/" id))))))

(defn init-remote [base-url]
  (RemoteBackend. base-url))


; LocalBackend

(defprotocol Cache
  (refresh [this origin]))

(defn- delete-directory [dir]
  (FileUtils/deleteDirectory (io/file dir)))

(defn- write-file [file data]
  (do (io/make-parents file)
      (spit file (with-out-str (pprint data)))))

(defn- normalize-maps [data]
  (clojure.walk/postwalk (fn [form] (if (map? form)
                                      (into (sorted-map) form)
                                      form))
                         data))

(defn- index-file [base-dir] (io/file base-dir "restaurants.edn"))
(defn- restaurant-file [base-dir id] (io/file base-dir "restaurant" (str id ".edn")))
(defn- updated-file [base-dir] (io/file base-dir "updated.edn"))
(def ^:private updated-format (ISODateTimeFormat/dateTime))

(defn local-updated [base-dir]
  (let [file (updated-file base-dir)]
    (if (.exists file)
      (.parseDateTime updated-format (edn/read-string (slurp file)))
      nil)))

(defn- refresh-cache [origin base-dir]
  (let [index @(get-restaurants origin)
        ids (map :id (:data index))
        restaurants (doall (map (fn [id] [id (get-restaurant origin id)])
                                ids))]
    (delete-directory base-dir)
    (write-file (updated-file base-dir)
                (.print updated-format (DateTime.)))
    (write-file (index-file base-dir)
                (normalize-maps index))
    (log/info "Cached restaurants index")
    (doseq [[id restaurant] restaurants]
      (write-file (restaurant-file base-dir id)
                  (normalize-maps @restaurant))
      (log/info "Cached restaurant" id))
    (log/info "Cache refreshed")))

(deftype LocalBackend [base-dir]
  Backend
  (get-restaurants [_] (future (edn/read-string (slurp (index-file base-dir)))))
  (get-restaurant [_ id] (future (edn/read-string (slurp (restaurant-file base-dir id)))))
  Cache
  (refresh [_ origin] (refresh-cache origin base-dir)))

(defn init-local [base-dir]
  (LocalBackend. base-dir))
