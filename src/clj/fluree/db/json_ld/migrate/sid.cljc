(ns fluree.db.json-ld.migrate.sid
  (:require [fluree.db.constants :as const]
            [fluree.db.indexer.storage :as storage]
            [fluree.db.query.exec.update :as update]
            [fluree.db.json-ld.commit :as commit]
            [fluree.db.json-ld.commit-data :as commit-data]
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.json-ld.reify :as reify]
            [fluree.db.ledger.json-ld :as ledger]
            [fluree.db.indexer.default :as indexer]
            [fluree.db.index :as index]
            [fluree.db.db.json-ld :as db]
            [fluree.db.nameservice.core :as nameservice]
            [fluree.db.util.core :as util :refer [get-first get-first-id get-first-value]]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.ledger.proto :as ledger-proto]
            [fluree.db.util.log :as log :include-macros true]
            [clojure.core.async :as async]))

(defrecord NamespaceMapping [mapping]
  iri/IRICodec
  (encode-iri [_ iri]
    (update/generate-sid! mapping iri))
  (decode-sid [_ sid]
    (iri/sid->iri sid (:namespace-codes @mapping))))

(defn db->namespace-mapping
  [db]
  (-> db
      (select-keys [:namespaces :namespace-codes])
      volatile!
      NamespaceMapping.))

(defn set-namespaces
  [db ns-mapping]
  (let [{:keys [namespaces namespace-codes]} @(:mapping ns-mapping)]
    (assoc db :namespaces namespaces, :namespace-codes namespace-codes)))

(defn merge-commit
  "Process a new commit map, converts commit into flakes, updates
  respective indexes and returns updated db"
  [conn {:keys [alias] :as db} [commit _proof]]
  (go-try
    (let [db-address (-> commit
                         (get-first const/iri-data)
                         (get-first-value const/iri-address))
          db-data    (<? (reify/read-db conn db-address))
          t-new      (reify/db-t db-data)
          ns-mapping (db->namespace-mapping db)

          assert           (reify/db-assert db-data)
          asserted-flakes  (reify/assert-flakes ns-mapping t-new assert)
          retract          (reify/db-retract db-data)
          retracted-flakes (reify/retract-flakes ns-mapping t-new retract)
          db*              (set-namespaces db ns-mapping)

          {:keys [previous issuer message] :as commit-metadata}
          (commit-data/json-ld->map commit db*)

          commit-id          (:id commit-metadata)
          commit-sid         (iri/encode-iri db* commit-id)
          [prev-commit _]    (some->> previous :address (reify/read-commit conn) <?)
          db-sid             (iri/encode-iri db* alias)
          metadata-flakes    (commit-data/commit-metadata-flakes commit-metadata
                                                                 t-new commit-sid db-sid)
          previous-id        (when prev-commit (:id prev-commit))
          prev-commit-flakes (when previous-id
                               (commit-data/prev-commit-flakes db* t-new commit-sid
                                                               previous-id))
          prev-data-id       (get-first-id prev-commit const/iri-data)
          prev-db-flakes     (when prev-data-id
                               (commit-data/prev-data-flakes db* db-sid t-new
                                                             prev-data-id))
          issuer-flakes      (when-let [issuer-iri (:id issuer)]
                               (commit-data/issuer-flakes db* t-new commit-sid issuer-iri))
          message-flakes     (when message
                               (commit-data/message-flakes t-new commit-sid message))
          all-flakes         (-> db*
                                 (get-in [:novelty :spot])
                                 empty
                                 (into metadata-flakes)
                                 (into retracted-flakes)
                                 (into asserted-flakes)
                                 (cond->
                                     prev-commit-flakes (into prev-commit-flakes)
                                     prev-db-flakes (into prev-db-flakes)
                                     issuer-flakes  (into issuer-flakes)
                                     message-flakes (into message-flakes)))]
      (when (empty? all-flakes)
        (reify/commit-error "Commit has neither assertions or retractions!"
                      commit-metadata))
      (-> db*
          (reify/merge-flakes t-new all-flakes)
          (assoc :previous (:commit db*))
          (assoc :commit commit-metadata)))))

(defn merge-commits
  [{:keys [conn] :as ledger} commit-tuples]
  (go-try
    (loop [[commit-tuple & r] commit-tuples
           db                 (db/create ledger)]
      (if commit-tuple
        (let [new-db (<? (merge-commit conn db commit-tuple))]
          (recur r new-db))
        db))))

(defn index
  [{:keys [t] :as db} branch]
  (go-try
    (let [error-ch (async/chan)
          index-ch (indexer/refresh-all db error-ch)]
      (async/alt!
        error-ch ([e] e)
        index-ch ([{:keys [garbage], refreshed-db :db, :as _status}]
                  (let [indexed-db    (-> (indexer/empty-novelty refreshed-db t)
                                          (assoc-in [:stats :indexed] t))
                        db-root-res   (<? (storage/write-db-root indexed-db nil))
                        index-address (:address db-root-res)
                        index-id      (str "fluree:index:sha256:" (:hash db-root-res))
                        commit-data   (-> indexed-db :commit :data)
                        index-roots   (select-keys indexed-db index/types)
                        commit-index  (commit-data/new-index commit-data
                                                             index-id
                                                             index-address
                                                             index-roots)
                        indexed-db*   (db/force-index-update indexed-db commit-index)]
                    (when (seq garbage)
                      (<? (storage/write-garbage indexed-db* nil garbage)))

                    (<? (commit/do-commit+push indexed-db* {:branch branch}))))))))

(defn migrate
  [conn address]
  (go-try
    (let [last-commit-addr  (<? (nameservice/lookup-commit conn address))
          last-commit-tuple (<? (reify/read-commit conn last-commit-addr))
          all-commit-tuples (<? (reify/trace-commits conn last-commit-tuple 1))
          first-commit      (ffirst all-commit-tuples)
          ledger-alias      (ledger/commit->ledger-alias conn address first-commit)
          branch            (keyword (get-first-value first-commit const/iri-branch))
          ledger            (<? (ledger/->ledger conn ledger-alias {:branch branch}))
          db                (<? (merge-commits ledger all-commit-tuples))
          indexed-db        (<? (index db branch))]
      (ledger-proto/-db-update ledger indexed-db)
      ledger)))
