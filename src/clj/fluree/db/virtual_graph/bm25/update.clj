(ns fluree.db.virtual-graph.bm25.update
  (:require [clojure.string :as str]
            [fluree.db.virtual-graph.bm25.stemmer :as stm]
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.util.log :as log]))

(set! *warn-on-reflection* true)

(def SPACE_PATTERN #"[^\w]+")

(defn- split-text
  [text]
  (str/split (str/lower-case text) SPACE_PATTERN))

(defn parse-sentence
  [sentence stemmer stopwords]
  (let [xf (comp
            (remove stopwords)
            (map #(stm/stem stemmer %)))]
    (->> sentence
         (split-text)
         (sequence xf))))

(defn vectorize-item
  "Vectorizes an item's parsed term frequency
  based on the terms in the bm25 model"
  [terms term-freq]
  (->> term-freq
       (reduce-kv
        (fn [acc term frequency]
          (if-let [idx (get-in terms [term :idx])] ;; indexed items will always have an index, but queries will not
            (conj acc [idx frequency])
            acc))
        [])
       (sort-by first)))

(defn update-avg-len
  [avg-length item-count doc-len]
  (let [total-len   (* avg-length item-count)
        total-len*  (+ doc-len total-len)
        item-count* (inc item-count)
        avg-len*    (/ total-len* item-count*)]
    [avg-len* item-count*]))

(defn- extract-text
  "Takes an item and returns full concatenated text"
  [item]
  (->> (dissoc item "@id")
       vals
       (reduce
        (fn [all-text sentence]
          (cond
            ;; nested map is a referred node
            (map? sentence)
            (str all-text " " (extract-text sentence))

            ;; multiple items, can be anything
            (sequential? sentence)
            (apply str/join
                   (cons all-text (map #(if (map? %)
                                          (extract-text %)
                                          %) sentence)) " ")

            (nil? sentence)
            all-text

            :else ;; string, or stringify other data types
            (str all-text " " sentence))
          (if (sequential? sentence)
            (apply str all-text " " sentence)
            (str all-text " " sentence))))))

(defn update-terms
  "Updates index's terms map with the new item's distinct terms

  Returns [terms dimensions]

  As we add new terms, we increase the dimensions accordingly."
  [terms dimensions id terms-distinct]
  (loop [[next-term & r] terms-distinct
         terms      terms
         dimensions dimensions]
    (if next-term
      (let [existing    (get terms next-term)
            term-map    (if existing
                          (update existing :items conj id)
                          {:idx   dimensions ;; sparse vector index location
                           :items #{id}})
            dimensions* (if existing
                          dimensions
                          (inc dimensions))]
        (recur r (assoc terms next-term term-map) dimensions*))
      [terms dimensions])))

(defn index-item
  "Returns updated bm25 index map after adding item to it"
  [index stemmer stopwords iri-codec item]
  (let [{:keys [avg-length item-count terms dimensions vectors]} index
        id             (->> (get item "@id")
                            (iri/encode-iri iri-codec))
        item-terms     (-> (extract-text item)
                           (parse-sentence stemmer stopwords))
        doc-len        (count item-terms)
        [avg-length* item-count*] (update-avg-len avg-length item-count doc-len)
        term-freq      (frequencies item-terms)
        terms-distinct (keys term-freq)
        [terms* dimensions*] (update-terms terms dimensions id terms-distinct)
        item-vec       (vectorize-item terms* term-freq)]
    (assoc index :terms terms*
                 :dimensions dimensions*
                 :avg-length avg-length*
                 :item-count item-count*
                 :vectors (assoc vectors id item-vec))))

(defn assert-items
  "Asserts items into the bm25 index, returns updated state."
  [{:keys [stemmer stopwords] :as bm25} latest-index assertions status-update]
  (let [item-count (count assertions)]
    (status-update [0 item-count])
    (loop [[item & r] assertions
           i     1
           index latest-index]
      (if item
        (let [index* (index-item index stemmer stopwords bm25 item)]
          ;; supply status for every 100 items for timeout reporting, etc.
          (when (zero? (mod i 100))
            (status-update [i item-count]))
          (recur r (inc i) index*))
        (do
          (status-update [item-count item-count]) ;; 100% done
          index)))))

;(defn retract-terms-docs
;  "Returns updated terms map with doc-id for sparce vec removed"
;  [terms id sparse-vec]
;  ;; set of term indexes as set we'll disj until empty
;  (let [retract-idxs (reduce #(conj %1 (first %2)) #{} sparse-vec)]
;    ;; iterate over terms until we retract all items
;    (loop [[[term-str term-map] & r] terms
;           retract-idxs retract-idxs
;           terms        (transient terms)]
;      (if (retract-idxs (:idx term-map)) ;; matches one of our retraction items?
;        (let [retract-idxs* (disj retract-idxs (:idx term-map))
;              terms*        (assoc! terms term-str (update term-map :items disj id))]
;          (if (empty? retract-idxs*) ;; no more restriction items, return updated terms map
;            (persistent! terms*)
;            (recur r retract-idxs* terms*)))
;        (recur r retract-idxs terms)))))
;
;(defn- retract-item
;  [{:keys [avg-length item-count vectors terms] :as bm25-state} item]
;  (let [id          (get item "@id")
;        v           (get vectors id)
;        terms*      (retract-terms-docs terms id v)
;        vectors*    (dissoc vectors id)
;        doc-len     (reduce
;                     (fn [acc vec-tuple]
;                       (+ acc (second vec-tuple)))
;                     0
;                     v)
;        total-len   (* avg-length item-count)
;        total-len*  (- total-len doc-len)
;        item-count* (dec item-count)
;        avg-length* (/ total-len* item-count*)]
;    (assoc bm25-state :item-count item-count*
;                      :avg-length avg-length*
;                      :vectors vectors*
;                      :terms terms*)))


