(ns fluree.db.query.subject-crawl.common
  (:require #?(:clj  [clojure.core.async :refer [go <!] :as async]
               :cljs [cljs.core.async :refer [go <!] :as async])
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake]
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.util.log :as log]
            [fluree.db.util.schema :as schema-util]
            [fluree.db.permissions-validate :as perm-validate]
            [fluree.db.query.fql-resp :refer [flakes->res]]))

#?(:clj (set! *warn-on-reflection* true))

(defn where-subj-xf
  "Transducing function to extract matching subjects from initial where clause."
  [{:keys [start-test start-flake end-test end-flake xf]}]
  (apply comp (cond-> [(map :flakes)
                       (map (fn [flakes]
                              (flake/subrange flakes
                                              start-test start-flake
                                              end-test end-flake)))]
                      xf
                      (conj xf))))


(defn result-af
  [{:keys [db cache fuel-vol max-fuel select-spec error-ch] :as _opts}]
  (fn [flakes port]
    (go
      (try*
        (some->> (<? (flakes->res db cache fuel-vol max-fuel select-spec flakes))
                 not-empty
                 (async/put! port))
        (async/close! port)
        (catch* e (async/put! error-ch e) (async/close! port) nil)))))


(defn subj-perm-filter-fn
  "Returns a specific filtering function which takes all subject flakes and
  returns the flakes allowed, or nil if none are allowed."
  [{:keys [permissions] :as db}]
  (let [pred-permissions?  (contains? permissions :predicate)
        coll-permissions   (:collection permissions)
        filter-cache       (atom {})
        default-deny?      (if (true? (:default coll-permissions))
                             false
                             true)
        filter-predicates? (fn [cid]
                             (if-some [cached (get @filter-cache cid)]
                               cached
                               (let [coll-perm (get coll-permissions cid)
                                     filter?   (cond
                                                 (schema-util/is-schema-cid? cid)
                                                 false

                                                 pred-permissions?
                                                 true

                                                 (nil? coll-perm)
                                                 default-deny?

                                                 (and (contains? coll-perm :all)
                                                      (= 1 (count coll-perm)))
                                                 false

                                                 :else true)]
                                 (swap! filter-cache assoc cid filter?)
                                 filter)))]
    (fn [flakes]
      (go-try
        (let [fflake (first flakes)]
          (if (-> fflake flake/s flake/sid->cid filter-predicates?)
            (<? (perm-validate/allow-flakes? db flakes))
            (when (<? (perm-validate/allow-flake? db fflake))
              flakes)))))))


(defn passes-filter?
  [filter-fn vars pred-flakes]
  (some #(filter-fn % vars) pred-flakes))

(defn pass-all-filters?
  "For a group of predicate flakes (all same .-p value)
  and a list of filter-functions, returns true if at least
  one of the predicates passes every function, else returns false."
  [filter-fns vars pred-flakes]
  (loop [[filter-fn & r-fns] filter-fns]
    (if filter-fn
      (if (passes-filter? filter-fn vars pred-flakes)
        (recur r-fns)
        false)
      true)))


(defn filter-subject
  "Filters a set of flakes for a single subject and returns true if
  the subject meets the filter map.

  filter-map is a map where pred-ids are keys and values are a list of filtering functions
  where each flake of pred-id must return a truthy value if the subject is allowed."
  [vars filter-map flakes]
  ;; TODO - fns with multiple vars will have to re-calc vars every time, this could be done once for the entire query
  (loop [[p-flakes & r] (partition-by flake/p flakes)
         required-p (:required-p filter-map)]
    (if p-flakes
      (let [p (-> p-flakes first flake/p)]
        (if-let [filter-fns (get filter-map p)]
          (when (pass-all-filters? filter-fns vars p-flakes)
            (recur r (disj required-p p)))
          (recur r (disj required-p p))))
      ;; only return flakes if all required-p values were found
      (when (empty? required-p)
        flakes))))


(defn order-results
  "If order-by exists in query, orders final results.
  order-by is defined by a map with keys (see analytical-parse for code):
  - :type - :variable or :predicate
  - :order - :asc or :desc
  - :predicate - if type = :predicate, contains predicate pid or name
  - :variable - if type = :variable, contains variable name (not supported for simple subject crawl)"
  [results {:keys [type order predicate]}]
  (if (= :variable type)
    (throw (ex-info "Ordering by a variable not supported in this type of query."
                    {:status 400 :error :db/invalid-query}))
    (cond-> (sort-by (fn [result] (get result predicate)) results)
            (= :desc order) reverse
            true vec)))