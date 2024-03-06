(ns fluree.db.reasoner.core
  (:require [clojure.core.async :as async :refer [alts! go]]
            [fluree.db.flake :as flake]
            [fluree.db.util.core :as util]
            [fluree.db.util.log :as log]
            [fluree.db.util.async :refer [go-try <?]]
            [fluree.db.reasoner.resolve :as resolve]
            [fluree.db.json-ld.transact :as transact]
            [fluree.db.fuel :as fuel]
            [fluree.json-ld :as json-ld]
            [fluree.db.reasoner.graph :refer [task-queue add-rule-dependencies]]))

#?(:clj (set! *warn-on-reflection* true))

(defn reasoned-flake?
  "Returns truthy if the flake has been generated by reasoner"
  [flake]
  (-> flake flake/m :reasoned))

(defn non-reasoned-flakes
  "Takes a sequence of flakes and removes any flakes which are reasoned.

  This is primarily used to remove reasoned flakes from commits."
  [flakes]
  (remove reasoned-flake? flakes))

(defn reasoned-flakes
  "Takes a sequence of flakes and keeps only reasoned flakes"
  [flakes]
  (filter reasoned-flake? flakes))

(defn schedule
  "Returns list of rule @id values in the order they should be run.

  If optional result-summary is provided, rules that don't need to be
  re-run will be filtered out.

  A result summary is list/set of the rule dependency patterns which
  match newly created Flakes from the last run. When the result-summary
  is empty, no rules will be left to run, but based on the dependencies
  it is possible no rules will be left to run even if the result-summary
  is non-empty"
  ([rules]
   (task-queue rules))
  ([rules result-summary]
   (task-queue rules result-summary)))

(defn reasoner-stage
  [db fuel-tracker txn-id author-did rule-id full-rule]
  (go-try
    (let [tx-state      (transact/->tx-state db txn-id author-did rule-id)
          parsed-txn    (:rule-parsed full-rule)
          _             (when-not (:where parsed-txn)
                          (throw (ex-info (str "Unable to execute reasoner rule transaction due to format error: " (:rule full-rule))
                                          {:status 400 :error :db/invalid-transaction})))

          flakes-ch     (transact/generate-flakes db fuel-tracker parsed-txn tx-state)
          fuel-error-ch (:error-ch fuel-tracker)
          chans         (remove nil? [fuel-error-ch flakes-ch])
          [flakes] (alts! chans :priority true)]
      (when (util/exception? flakes)
        (throw flakes))
      flakes)))

(defn execute-reasoner-rule
  [db rule-id reasoning-rules fuel-tracker {:keys [txn-id author-did] :as tx-state}]
  (go-try
    (let [[db reasoner-flakes] (<? (reasoner-stage db fuel-tracker txn-id author-did rule-id (get reasoning-rules rule-id)))
          tx-state* (assoc tx-state :stage-update? true
                                    :db-before db)]
      (log/debug "reasoner flakes: " rule-id reasoner-flakes)
      ;; returns map of :db-after, :add, :remove - but for reasoning we only support adds, so remove should be empty
      (transact/final-db db reasoner-flakes tx-state*))))

(defn execute-reasoner
  "Executes the reasoner on the staged db-after and returns the updated db-after."
  [db reasoning-rules fuel-tracker reasoner-max tx-state]
  (go-try
    (let [rule-schedule (schedule reasoning-rules)]
      (log/debug "reasoning schedule: " rule-schedule)
      (if (seq rule-schedule)
        (loop [[rule-id & r] rule-schedule
               reasoned-flakes nil ;; Note these are in an AVL set in with spot comparator
               reasoned-db     db
               summary         {:iterations      0 ;; holds summary across all runs
                                :reasoned-flakes []
                                :total-flakes    0}]
          (if rule-id
            (let [{:keys [db-after add]} (<? (execute-reasoner-rule reasoned-db rule-id reasoning-rules fuel-tracker tx-state))]
              (log/debug "executed reasoning rule: " rule-id)
              (log/trace "reasoning rule: " rule-id "produced flakes:" add)
              (recur r
                     (if reasoned-flakes
                       (into reasoned-flakes add)
                       add)
                     db-after
                     summary))
            (let [all-reasoned-flakes (into reasoned-flakes (:reasoned-flakes summary))
                  summary*            {:iterations      (-> summary :iterations inc)
                                       :reasoned-flakes all-reasoned-flakes
                                       :total-flakes    (count all-reasoned-flakes)}
                  new-flakes?         (> (:total-flakes summary*)
                                         (:total-flakes summary))
                  maxed?              (when reasoner-max
                                        (= (:iterations summary*) reasoner-max))]
              (log/debug "completed reasoning iteration number: " (:iterations summary*)
                         "Total reasoned flakes:" (:total-flakes summary*))

              (log/debug "Total reasoned flakes:" (:total-flakes summary*))
              "completed in:" (:iterations summary*) "iteration(s)."

              (if (and new-flakes? (not maxed?))
                (recur rule-schedule nil reasoned-db summary*)
                (do
                  (when (and maxed? new-flakes?)
                    (log/warn (str "Reasoner reached max iterations: " reasoner-max
                                   ". Returning db reasoned thus far.")))
                  reasoned-db)))))
        db))))

(defn rules-from-graph
  [graph]
  (let [expanded (-> graph
                     json-ld/expand
                     util/sequential)]
    (reduce
      (fn [acc rule]
        (let [id   (:id rule)
              rule (util/get-first-value rule "http://flur.ee/ns/ledger#rule")]
          (if rule
            (conj acc [(or id (str "_:" (rand-int 2147483647))) rule])
            acc)))
      []
      expanded)))


(defn reason
  [db regimes graph {:keys [max-fuel reasoner-max] :as opts}]
  (go-try
    (let [regimes         (set (util/sequential regimes))
          fuel-tracker    (fuel/tracker max-fuel)
          db*             (update db :reasoner #(into regimes %))
          tx-state        (transact/->tx-state db* nil nil nil)
          raw-rules       (if graph
                            (rules-from-graph graph)
                            (<? (resolve/find-rules db*)))
          reasoning-rules (->> raw-rules
                               (resolve/rules->graph db*)
                               add-rule-dependencies)]
      (log/debug "reasoning rules: " reasoning-rules)
      (if (empty? reasoning-rules)
        db
        (<? (execute-reasoner db* reasoning-rules fuel-tracker reasoner-max tx-state))))))
