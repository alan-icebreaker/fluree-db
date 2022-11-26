(ns fluree.db.query.fql
  (:require [clojure.core.async :as async :refer [<! >! go]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :refer [vswap! try* catch*]]
            [fluree.db.query.analytical-parse :as q-parse]
            [fluree.db.dbproto :as db-proto]
            [fluree.db.query.subject-crawl.core :refer [simple-subject-crawl]]
            [fluree.db.query.compound :as compound]
            [fluree.db.query.range :as query-range]
            [fluree.db.query.json-ld.response :as json-ld-resp]
            [fluree.db.dbproto :as db-proto]
            [fluree.db.constants :as const])
  (:refer-clojure :exclude [vswap!])
  #?(:cljs (:require-macros [clojure.core])))

#?(:clj (set! *warn-on-reflection* true))

(declare query)


(defn process-where-item
  [db cache compact-fn fuel-vol fuel where-item spec inVector? error-ch]
  (go
    (try*
     (loop [[spec-item & r'] spec
            result-item []]
       (if spec-item
         (let [{:keys [selection in-n iri? o-var? grouped? function]} spec-item
               value  (nth where-item in-n)
               value* (cond
                        ;; there is a sub-selection (graph crawl)
                        selection
                        (let [flakes (<? (query-range/index-range db :spot = [value]))]
                          (<? (json-ld-resp/flakes->res db cache compact-fn fuel-vol fuel (:spec spec-item) 0 flakes)))

                        grouped?
                        (cond->> value
                          o-var?   (mapv first)
                          function function)

                        ;; subject id coming it, we know it is an IRI so resolve here
                        iri?
                        (or (get @cache value)
                            (let [c-iri (<? (db-proto/-iri db value compact-fn))]
                              (vswap! cache assoc value c-iri)
                              c-iri))

                        o-var?
                        (let [[val datatype] value]
                          (if (= const/$xsd:anyURI datatype)
                            (or (get @cache val)
                                (let [c-iri (<? (db-proto/-iri db val compact-fn))]
                                  (vswap! cache assoc val c-iri)
                                  c-iri))
                            val))

                        :else
                        value)]
           (recur r' (conj result-item value*)))
         (if inVector?
           result-item
           (first result-item))))
     (catch* e
             (log/error e "Error processing query")
             (>! error-ch e)))))


(defn process-select-results
  "Processes where results into final shape of specified select statement."
  [db out-ch where-ch error-ch {:keys [select fuel compact-fn group-by] :as _parsed-query}]
  (let [{:keys [spec inVector?]} select
        cache    (volatile! {})
        fuel-vol (volatile! 0)
        {:keys [group-finish-fn]} group-by
        finish-xf (if group-finish-fn
                    (map group-finish-fn)
                    (map identity))
        process-xf (comp cat finish-xf)
        process-ch (async/chan 2 process-xf)]
    (->> (async/pipe where-ch process-ch)
         (async/pipeline-async 2
                               out-ch
                               (fn [where-item ch]
                                 (async/pipe (process-where-item db cache compact-fn fuel-vol fuel where-item spec inVector? error-ch)
                                             ch))))
    out-ch))


(defn group-results
  [partition-fn result-ch]
  (if partition-fn
    (let [group-ch (async/reduce (fn [groups result-chunk]
                                   (reduce (fn [grps result]
                                             (let [grp-key (partition-fn result)]
                                               (update grps grp-key
                                                       (fn [grp]
                                                         (-> grp
                                                             (or [])
                                                             (conj result))))))
                                           groups result-chunk))
                                 {} result-ch)]
      (async/pipe group-ch (async/chan 1 (mapcat vals))))
    (async/reduce into [] result-ch)))


(defn compare-by-first
  [cmp]
  (fn [x y]
    (cmp (first x) (first y))))

(defn sort-groups
  [result-cmp groups]
  (let [group-cmp (compare-by-first result-cmp)]
    (->> groups
         (map (partial sort result-cmp)) ; sort results in each group
         (sort group-cmp))))             ; then sort all the groups

(defn order-result-groups
  [cmp group-ch]
  (if cmp
    (let [group-coll-ch (async/into [] group-ch)
          sort-xf       (comp (map (partial sort-groups cmp))
                              cat)
          sorted-ch     (async/chan 1 sort-xf)]
      (async/pipe group-coll-ch sorted-ch))
    group-ch))


(defn order+group-results
  "Ordering must first consume all results and then sort."
  [results-ch error-ch fuel max-fuel {:keys [comparator] :as _order-by} {:keys [partition-fn grouping-fn] :as _group-by}]
  (async/go
    (let [results (<! (async/reduce into [] results-ch))]
      (cond-> (sort comparator results)
        grouping-fn grouping-fn))))

(defn- ad-hoc-query
  "Legacy ad-hoc query processor"
  [db {:keys [fuel order-by group-by] :as parsed-query}]
  (let [out-ch (async/chan)]
    (let [max-fuel  fuel
          fuel      (volatile! 0)
          partition (:partition-fn group-by)
          cmp       (:comparator order-by)
          error-ch  (async/chan)
          where-ch  (->> (compound/where db parsed-query fuel max-fuel error-ch)
                         (group-results partition)
                         (order-result-groups cmp))]
      (process-select-results db out-ch where-ch error-ch parsed-query))
    out-ch))

;; (defn- ad-hoc-query
;;   "Legacy ad-hoc query processor"
;;   [db {:keys [fuel order-by group-by] :as parsed-query}]
;;   (let [out-ch (async/chan)]
;;     (let [max-fuel fuel
;;           fuel     (volatile! 0)
;;           error-ch (async/chan)
;;           where-ch (cond-> (compound/where db parsed-query fuel max-fuel error-ch)
;;                      order-by (order+group-results error-ch fuel max-fuel order-by group-by))]
;;       (process-select-results db out-ch where-ch error-ch parsed-query))
;;     out-ch))


(defn cache-query
  "Returns already cached query from cache if available, else
  executes and stores query into cache."
  [{:keys [network ledger-id block auth conn] :as db} query-map]
  ;; TODO - if a cache value exists, should max-fuel still be checked and throw if not enough?
  (let [oc        (:object-cache conn)
        query*    (update query-map :opts dissoc :fuel :max-fuel)
        cache-key [:query network ledger-id block auth query*]]
    ;; object cache takes (a) key and (b) fn to retrieve value if null
    (oc cache-key
        (fn [_]
          (let [pc (async/promise-chan)]
            (async/go
              (let [res (async/<! (query db (assoc-in query-map [:opts :cache]
                                                      false)))]
                (async/put! pc res)))
            pc)))))


(defn cache?
  "Returns true if query was requested to run from the cache."
  [{:keys [opts] :as _query-map}]
  #?(:clj (:cache opts) :cljs false))


(defn first-async
  "Returns first result of a sequence returned from an async channel."
  [ch]
  (go-try
    (let [res (<? ch)]
      (first res))))


(defn query
  "Returns core async channel with results or exception"
  [db query-map]
  (log/debug "Running query:" query-map)
  (if (cache? query-map)
    (cache-query db query-map)
    (let [parsed-query (q-parse/parse db query-map)
          db*          (assoc db :ctx-cache (volatile! {}))] ;; allow caching of some functions when available
      (if (= :simple-subject-crawl (:strategy parsed-query))
        (simple-subject-crawl db* parsed-query)
        (cond-> (async/into [] (ad-hoc-query db* parsed-query))
                (:selectOne? parsed-query) (first-async))))))
