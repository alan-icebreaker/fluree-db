(ns fluree.db.storage.ipfs
  (:require [fluree.db.method.ipfs.xhttp :as ipfs]
            [fluree.db.storage :as storage]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.json :as json]
            [fluree.json-ld :as json-ld]
            [clojure.string :as str]))

(def method-name "ipfs")

(defn build-ipfs-path
  [method local]
  (str/join "/" ["" method local]))

(defn ipfs-address
  [path]
  (storage/build-fluree-address method-name path))

(defrecord IpfsStore [endpoint]
  storage/JsonArchive
  (-read-json [_ address keywordize?]
    (go-try
      (let [{:keys [ns method local]} (storage/parse-address address)
            path                      (build-ipfs-path method local)]
        (when-not (and (= "fluree" ns)
                       (#{"ipfs" "ipns"} method))
          (throw (ex-info (str "Invalid file type or method: " address)
                          {:status 500 :error :db/invalid-address})))
        (when-let [data (<? (ipfs/cat endpoint path false))]
          (json/parse data keywordize?)))))

  storage/ContentAddressedStore
  (-content-write [_ path v]
    (go-try
      (let [content (if (string? v)
                      v
                      (json-ld/normalize-data v))

            {:keys [hash size] :as res} (<? (ipfs/add endpoint path content))]
        (when-not size
          (throw
            (ex-info
              "IPFS publish error, unable to retrieve IPFS name."
              {:status 500 :error :db/push-ipfs :result res})))
        {:path    hash
         :hash    hash
         :address (ipfs-address hash)
         :size    size}))))

(defn open
  [endpoint]
  (->IpfsStore endpoint))
