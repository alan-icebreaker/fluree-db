(ns fluree.db.virtual-graph.flat-rank
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [clojure.core.async :as async :refer [>! go]]
            [fluree.db.flake :as flake]
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.query.exec.where :as where]
            [fluree.db.util.async :refer [<?]]
            [fluree.db.query.range :as query-range]
            [fluree.db.vector.scoring :refer [dot-product cosine-similarity euclidian-distance]]
            [fluree.db.virtual-graph.parse :as vg-parse]
            [fluree.db.util.core :refer [try* catch*]]
            [fluree.db.util.log :as log]))

(def flatrank-vg-re (re-pattern "##FlatRank-(.*)"))

(defn result-sort
  [a b]
  (compare (:score a) (:score b)))

(defn reverse-result-sort
  [a b]
  (compare (:score b) (:score a)))

(defn format-result
  [f score]
  {:id    (flake/s f)
   :score score
   :vec   (flake/o f)})

(defn score-flake
  [score-fn v f]
  (when-let [score (score-fn (flake/o f) v)]
    (format-result f score)))

(defn search
  [db score-fn sort-fn solution error-ch out-ch]
  (go
    (try*
      (let [{::vg-parse/keys [property target limit] :as search-params} (vg-parse/get-search-params solution)

            pid       (iri/encode-iri db property)
            score-opt {:flake-xf (comp (map (partial score-flake score-fn target))
                                       (remove nil?))}
            ;; For now, pulling all matching values from full index once hitting
            ;; the actual vector index, we'll only need to pull matches out of
            ;; novelty (if that)
            vectors   (<? (query-range/index-range db :post = [pid] score-opt))]
        (->> vectors
             (sort sort-fn)
             (vg-parse/limit-results limit)
             (vg-parse/process-results db solution search-params false)
             (async/onto-chan! out-ch)))
      (catch* e
        (log/error e "Error ranking vectors")
        (>! error-ch e)))))

(defrecord DotProductGraph [db]
  where/Matcher
  (-match-triple [_ _fuel-tracker solution triple _error-ch]
    (vg-parse/match-search-triple solution triple))

  (-finalize [_ _ error-ch solution-ch]
    (vg-parse/finalize (partial search db dot-product reverse-result-sort) error-ch solution-ch))

  (-match-id [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-match-class [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-activate-alias [_ alias']
    (where/-activate-alias db alias'))

  (-aliases [_]
    (where/-aliases db)))

(defn dot-product-graph
  [db]
  (->DotProductGraph db))

(defrecord CosineGraph [db]
  where/Matcher
  (-match-triple [_ _fuel-tracker solution triple _error-ch]
    (vg-parse/match-search-triple solution triple))

  (-finalize [_ _ error-ch solution-ch]
    (vg-parse/finalize (partial search db cosine-similarity reverse-result-sort) error-ch solution-ch))

  (-match-id [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-match-class [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-activate-alias [_ alias']
    (where/-activate-alias db alias'))

  (-aliases [_]
    (where/-aliases db)))

(defn cosine-graph
  [db]
  (->CosineGraph db))

(defrecord EuclideanGraph [db]
  where/Matcher
  (-match-triple [_ _fuel-tracker solution triple _error-ch]
    (vg-parse/match-search-triple solution triple))

  (-finalize [_ _ error-ch solution-ch]
    (vg-parse/finalize (partial search db euclidian-distance result-sort) error-ch solution-ch))

  (-match-id [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-match-class [_ _fuel-tracker _solution _s-mch _error-ch]
    where/nil-channel)

  (-activate-alias [_ alias']
    (where/-activate-alias db alias'))

  (-aliases [_]
    (where/-aliases db)))

(defn euclidean-graph
  [db]
  (->EuclideanGraph db))

(defn extract-metric
  "Takes the graph alias as a string and extracts the metric name from the
  end of the IRI"
  [graph-alias]
  (some-> (re-find flatrank-vg-re graph-alias)
          second
          ->kebab-case-keyword))

(defn index-graph
  [db graph-alias]
  (let [metric (extract-metric graph-alias)]
    (cond
      (= metric :cosine)
      (cosine-graph db)

      (= metric :dot-product)
      (dot-product-graph db)

      (= metric :distance)
      (euclidean-graph db))))

(defn flatrank-alias?
  [alias]
  (re-matches flatrank-vg-re alias))
