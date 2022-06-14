(ns fluree.db.method.ipfs.core
  (:require [fluree.db.util.xhttp :as xhttp]
            #?(:clj  [org.httpkit.client :as client]
               :cljs ["axios" :as axios])
            [fluree.db.util.async :refer [<? go-try channel?]]
            [fluree.db.util.core :as util :refer [try* catch*]]
            #?(:clj  [clojure.core.async :as async]
               :cljs [cljs.core.async :as async])
            [fluree.db.util.json :as json]
            [clojure.string :as str]
            [fluree.db.util.log :as log]))

#?(:clj (set! *warn-on-reflection* true))


(defn get-json
  [server block-id]
  (log/debug "Retrieving json from IPFS cid:" block-id)
  (let [url         (str server "api/v0/cat?arg=" block-id)
        res #?(:clj @(client/post url {})
               :cljs (let [res (atom nil)]
                       (-> axios
                           (.request (clj->js {:url  url
                                               :post "post"
                                               :data {}}))
                           (.then (fn [resp] (reset! res resp)))
                           (.catch (fn [err] (reset! res err))))
                       @res))]
    (try* (json/parse (:body res) false)
          (catch* e (log/error e "JSON parse error for data: " (:body res))
                  (throw e)))))


(defn add-json
  "Adds json from clojure data structure"
  [ipfs-server json]
  (let [endpoint (str ipfs-server "api/v0/add")
        req      {:multipart [{:name        "json-ld"
                               :content     json
                               :contentType "application/ld+json"}]}]
    #?(:clj  @(client/post endpoint req)
       :cljs (let [res (atom nil)]
               (-> axios
                   (.request (clj->js {:url  endpoint
                                       :post "post"
                                       :data req}))
                   (.then (fn [resp] (reset! res resp)))
                   (.catch (fn [err] (reset! res err))))
               @res))))


(defn add
  "Adds clojure data structure to IPFS by serializing first into JSON"
  [ipfs-server data]
  (let [json (json/stringify data)]
    (add-json ipfs-server json)))

(defn ipns-push
  "Adds json from clojure data structure"
  [ipfs-server ipfs-cid]
  (let [endpoint (str ipfs-server "api/v0/name/publish?arg=" ipfs-cid)]
    #?(:clj  @(client/post endpoint {})
       :cljs (let [res (atom nil)]
               (-> axios
                   (.request (clj->js {:url  endpoint
                                       :post "post"
                                       :data {}}))
                   (.then (fn [resp] (reset! res resp)))
                   (.catch (fn [err] (reset! res err))))
               @res))))


(defn default-commit-fn
  "Default push function for IPFS"
  [ipfs-endpoint]
  (fn
    ([json]
     (let [res  (add-json ipfs-endpoint json)
           body (json/parse (:body res))
           name (:Name body)]
       (when-not name
         (throw (ex-info (str "IPFS publish error, unable to retrieve IPFS name. Response object: " res)
                         {:status 500 :error :db/push-ipfs})))
       (str "fluree:ipfs://" name)))
    ([json opts]
     (throw (ex-info (str "IPFS commit does not support a second argument: opts.")
                     {:status 500 :error :db/commit-ipfs-2})))))


(defn default-push-fn
  "Default publish function updates IPNS record based on a
  provided Fluree IPFS database ID, i.e.
  fluree:ipfs:<ipfs cid>

  Returns an async promise-chan that will eventually contain a result."
  [ipfs-endpoint]
  (fn [fluree-dbid]
    #?(:clj
       (let [p (promise)]
         (future
           (log/info (str "Pushing db " fluree-dbid " to IPNS. (IPNS is slow!)"))
           (let [start-time (System/currentTimeMillis)
                 [_ _ ipfs-cid] (str/split fluree-dbid #":")
                 res        (ipns-push ipfs-endpoint ipfs-cid)
                 seconds    (quot (- (System/currentTimeMillis) start-time) 1000)
                 body       (json/parse (:body res))
                 name       (:Name body)]
             #_(when-not name
                 (throw (ex-info (str "IPNS publish error, unable to retrieve IPFS name. Response object: " res)
                                 {:status 500 :error :db/push-ipfs})))
             (log/info (str "Successfully updated fluree:ipns:" name " with db: " fluree-dbid " in "
                            seconds " seconds. (IPNS is slow!)"))
             (deliver p (str "fluree:ipns:" name))))
         p)
       :cljs
       (js/Promise
         (fn [resolve reject]
           (log/info (str "Pushing db " fluree-dbid " to IPNS. (IPNS is slow!)"))
           (let [start-time (js/Date.now)
                 [_ _ ipfs-cid] (str/split fluree-dbid #":")
                 res        (ipns-push ipfs-endpoint ipfs-cid)
                 seconds    (quot (- (js/Date.now) start-time) 1000)
                 body       (json/parse (:body res))
                 name       (:Name body)]
             #_(when-not name
                 (throw (ex-info (str "IPNS publish error, unable to retrieve IPFS name. Response object: " res)
                                 {:status 500 :error :db/push-ipfs})))
             (log/info (str "Successfully updated fluree:ipns:" name " with db: " fluree-dbid " in "
                            seconds " seconds. (IPNS is slow!)"))
             (resolve (str "fluree:ipns:" name))))))))


(defn default-read-fn
  "Default reading function for IPFS. Reads either IPFS or IPNS docs"
  [ipfs-endpoint]
  (fn [file-key]
    (when-not (string? file-key)
      (throw (ex-info (str "Invalid file key, cannot read: " file-key)
                      {:status 500 :error :db/invalid-commit})))
    (let [[address path] (str/split file-key #"://")
          [type method] (str/split address #":")
          ipfs-cid (str "/" method "/" path)]
      (when-not (and (= "fluree" type)
                     (#{"ipfs" "ipns"} method))
        (throw (ex-info (str "Invalid file type or method: " file-key)
                        {:status 500 :error :db/invalid-commit})))

      (get-json ipfs-endpoint ipfs-cid))))


