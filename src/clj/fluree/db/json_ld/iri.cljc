(ns fluree.db.json-ld.iri
  (:require [fluree.db.util.core :as util]
            [fluree.db.util.bytes :as bytes]
            [clojure.string :as str]
            [clojure.set :refer [map-invert]]))

(def ^:const f-ns "https://ns.flur.ee/ledger#")
(def ^:const f-did-ns "did:fluree:")
(def ^:const f-commit-256-ns "fluree:commit:sha256:")
(def ^:const fdb-256-ns "fluree:db:sha256:")
(def ^:const f-mem-ns "fluree:memory://")
(def ^:const f-file-ns "fluree:file://")
(def ^:const f-ipfs-ns "fluree:ipfs://")
(def ^:const f-s3-ns "fluree:s3://")
(def ^:const f-ctx-ns "fluree:context:")


(defn fluree-iri
  [nme]
  (str f-ns nme))

(def default-namespaces
  "iri namespace mapping. 0 signifies relative iris. 1-100 are reserved; user
  supplied namespaces start at 101."
  {"@"                                           1
   "http://www.w3.org/2001/XMLSchema#"           2
   "http://www.w3.org/1999/02/22-rdf-syntax-ns#" 3
   "http://www.w3.org/2000/01/rdf-schema#"       4
   "http://www.w3.org/ns/shacl#"                 5
   "http://www.w3.org/2002/07/owl#"              6
   "http://www.w3.org/2008/05/skos#"             7
   "http://schema.org/"                          8
   "http://xmlns.com/foaf/0.1/"                  9
   "https://www.wikidata.org/wiki/"              10
   "urn:uuid"                                    11
   "urn:isbn:"                                   12
   "urn:issn"                                    13
   "_:"                                          14
   f-ns                                          15
   fdb-256-ns                                    16
   f-did-ns                                      17
   f-commit-256-ns                               18
   f-mem-ns                                      19
   f-file-ns                                     20
   f-ipfs-ns                                     21
   f-s3-ns                                       22
   f-ctx-ns                                      23})


(def default-namespace-codes
  (map-invert default-namespaces))

(def last-default-code 100)

(defn decompose-by-char
  [iri c limit]
  (when-let [char-idx (some-> iri
                              (str/last-index-of c)
                              inc)]
    (when (< char-idx limit)
      (let [ns  (subs iri 0 char-idx)
            nme (subs iri char-idx)]
        [ns nme]))))

(defn decompose
  [iri]
  (let [length (count iri)]
    (or (decompose-by-char iri \@ length)
        (decompose-by-char iri \# length)
        (decompose-by-char iri \? length)
        (decompose-by-char iri \/ length)
        (decompose-by-char iri \: length)
        [nil iri])))

(def name-code-xf
  (comp (partition-all 8)
        (map bytes/UTF8->long)))

(defn append-name-codes
  [ns-sid nme]
  (into ns-sid
        name-code-xf
        (bytes/string->UTF8 nme)))

(defn codes->name
  [nme-codes]
  (->> nme-codes
       (mapcat bytes/long->UTF8)
       bytes/UTF8->string))

(defn get-ns-code
  [sid]
  (nth sid 0))

(defn get-name-codes
  [sid]
  (subvec sid 1))

(defn get-name
  [sid]
  (->> sid get-name-codes codes->name))

(defn iri->sid
  "Converts a string iri into a vector of long integer codes. The first code
  corresponds to the iri's namespace, and the remaining codes correspond to the
  iri's name split into 8-byte chunks"
  ([iri]
   (iri->sid iri default-namespaces))
  ([iri namespaces]
   (let [[ns nme] (decompose iri)]
     (when-let [ns-code (get namespaces ns)]
       (append-name-codes [ns-code] nme)))))

(defn next-namespace-code
  [namespaces]
  (->> namespaces
       vals
       (apply max last-default-code)
       inc))

(defprotocol SIDGenerator
  (generate-sid [g iri])
  (get-namespaces [g]))

(defn sid-generator!
  [initial-namespaces]
  (let [namespaces (volatile! initial-namespaces)]
    (reify SIDGenerator
      (generate-sid [_ iri]
        (let [[ns nme] (decompose iri)
              ns-code  (-> namespaces
                           (vswap! (fn [ns-map]
                                     (if (contains? ns-map ns)
                                       ns-map
                                       (let [new-ns-code (next-namespace-code ns-map)]
                                         (assoc ns-map ns new-ns-code)))))
                           (get ns))]
          (append-name-codes [ns-code] nme)))
      (get-namespaces [_]
        @namespaces))))

(defn sid?
  [x]
  (vector? x))

(def ^:const min-sid
  [util/min-integer util/min-long])

(def ^:const max-sid
  [util/max-integer util/max-long])

(defn sid->iri
  "Converts a vector as would be returned by `iri->subid` back into a string iri."
  ([sid]
   (sid->iri sid default-namespace-codes))
  ([sid namespace-codes]
   (-> sid
       get-ns-code
       (as-> ns-code (get namespace-codes ns-code))
       (str (get-name sid)))))
