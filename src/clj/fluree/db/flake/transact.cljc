(ns fluree.db.flake.transact
  (:require [clojure.core.async :as async :refer [go]]
            [fluree.db.constants :as const]
            [fluree.db.flake :as flake]
            [fluree.db.flake.index.novelty :as novelty]
            [fluree.db.query.exec.where :as where]
            [fluree.db.query.range :as query-range]
            [fluree.db.json-ld.policy :as policy]
            [fluree.db.util.core :as util]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.fuel :as fuel]
            [fluree.db.json-ld.shacl :as shacl]
            [fluree.db.json-ld.policy.modify :as policy.modify]
            [fluree.db.query.exec.update :as update]
            [fluree.db.json-ld.commit-data :as commit-data]
            [fluree.db.json-ld.vocab :as vocab]
            [fluree.db.virtual-graph.index-graph :as vg]
            [fluree.db.util.log :as log]))

#?(:clj (set! *warn-on-reflection* true))

;; TODO - can use transient! below
(defn stage-update-novelty
  "If a db is staged more than once, any retractions in a previous stage will
  get completely removed from novelty. This returns flakes that must be added and removed
  from novelty."
  [novelty-flakes new-flakes]
  (loop [[f & r] new-flakes
         adds    new-flakes
         removes (empty new-flakes)]
    (if f
      (if (true? (flake/op f))
        (recur r adds removes)
        (let [flipped (flake/flip-flake f)]
          (if (contains? novelty-flakes flipped)
            (recur r (disj adds f) (conj removes flipped))
            (recur r adds removes))))
      [(not-empty adds) (not-empty removes)])))

(defn ->tx-state
  "Generates a state map for transaction processing. When optional
  reasoned-from-IRI is provided, will mark any new flakes as reasoned from the
  provided value in the flake's metadata (.-m) as :reasoned key."
  [& {:keys [db context txn author annotation reasoned-from-iri]}]
  (let [{:keys [policy], db-t :t} db

        commit-t  (-> db :commit commit-data/t)
        t         (flake/next-t commit-t)
        db-before (policy/root db)]
    {:db-before     db-before
     :context       context
     :txn           txn
     :annotation    annotation
     :author        author
     :policy        policy
     :stage-update? (= t db-t) ; if a previously staged db is getting updated again before committed
     :t             t
     :reasoner-max  10 ; maximum number of reasoner iterations before exception
     :reasoned      reasoned-from-iri}))

(defn into-flakeset
  [fuel-tracker error-ch flake-ch]
  (let [flakeset (flake/sorted-set-by flake/cmp-flakes-spot)
        error-xf (halt-when util/exception?)
        flake-xf (if fuel-tracker
                   (let [track-fuel (fuel/track fuel-tracker error-ch)]
                     (comp error-xf track-fuel))
                   error-xf)]
    (async/transduce flake-xf (completing conj) flakeset flake-ch)))

(defn generate-flakes
  [db fuel-tracker parsed-txn tx-state]
  (go
    (let [error-ch  (async/chan)
          db-vol    (volatile! db)
          update-ch (->> (where/search db parsed-txn fuel-tracker error-ch)
                         (update/modify db-vol parsed-txn tx-state fuel-tracker error-ch)
                         (into-flakeset fuel-tracker error-ch))]
      (async/alt!
        error-ch ([e] e)
        update-ch ([result]
                   (if (util/exception? result)
                     result
                     [@db-vol result]))))))

(defn modified-subjects
  "Returns a map of sid to s-flakes for each modified subject."
  [db flakes]
  (go-try
    (loop [[s-flakes & r] (partition-by flake/s flakes)
           sid->s-flakes {}]
      (if s-flakes
        (let [sid             (some-> s-flakes first flake/s)
              existing-flakes (<? (query-range/index-range db :spot = [sid]))]
          (recur r (assoc sid->s-flakes sid (into (set s-flakes) existing-flakes))))
        sid->s-flakes))))

(defn new-virtual-graph
  "Creates a new virtual graph. If the virtual graph is invalid, an
  exception will be thrown and the transaction will not complete."
  [db add new-vgs]
  (loop [[new-vg & r] new-vgs
         db db]
    (if new-vg
      (let [vg-flakes (filter #(= (flake/s %) new-vg) add)
            [db* alias vg-record] (vg/create db vg-flakes)]
        ;; TODO - VG - ensure alias is not being used, throw if so
        (recur r (assoc-in db* [:vg alias] vg-record)))
      db)))

(defn check-virtual-graph
  [db add rem]
  ;; TODO - VG - should also check for retractions to "delete" virtual graph
  ;; TODO - VG - check flakes if user updated existing virtual graph
  (let [new-vgs  (keep #(when (= (flake/o %) const/$fluree:VirtualGraph)
                          (flake/s %)) add)
        has-vgs? (not-empty (:vg db))]
    (cond-> db
            (seq new-vgs) (new-virtual-graph add (set new-vgs))
            has-vgs? (vg/update-vgs add rem))))


(defn final-db
  "Returns map of all elements for a stage transaction required to create an
  updated db."
  [db new-flakes {:keys [stage-update? policy t txn author annotation db-before context] :as _tx-state}]
  (go-try
    (let [[add remove] (if stage-update?
                         (stage-update-novelty (get-in db [:novelty :spot]) new-flakes)
                         [new-flakes nil])
          mods-ch      (modified-subjects (policy/root db) add) ;; kick off mods in background
          db-after     (-> db
                           (update :staged conj [txn author annotation])
                           (assoc :t t
                                  :policy policy) ; re-apply policy to db-after
                           (commit-data/update-novelty add remove)
                           (commit-data/add-tt-id))
          mods         (<? mods-ch)
          db-after*    (-> db-after
                           (vocab/hydrate-schema add mods)
                           (check-virtual-graph add remove))]
      {:add       add
       :remove    remove
       :db-after  db-after*
       :db-before db-before
       :mods      mods
       :context   context})))

(defn validate-db-update
  [{:keys [db-after db-before mods context] :as staged-map}]
  (go-try
    (<? (shacl/validate! db-before (policy/root db-after) (vals mods) context))
    (let [allowed-db (<? (policy.modify/allowed? staged-map))]
      allowed-db)))

(defn stage
  [db fuel-tracker context identity author annotation raw-txn parsed-txn]
  (go-try
    (when (novelty/max-novelty? db)
      (throw (ex-info "Maximum novelty exceeded, no transactions will be processed until indexing has completed."
                      {:status 503 :error :db/max-novelty-exceeded})))
    (when (policy.modify/deny-all? db)
      (throw (ex-info "Database policy denies all modifications."
                      {:status 403 :error :db/policy-exception})))
    (let [tx-state   (->tx-state :db db
                                 :context context
                                 :txn raw-txn
                                 :author (or author identity)
                                 :annotation annotation)
          [db** new-flakes] (<? (generate-flakes db fuel-tracker parsed-txn tx-state))
          updated-db (<? (final-db db** new-flakes tx-state))]
      (<? (validate-db-update updated-db)))))
