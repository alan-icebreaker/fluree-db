(ns fluree.db.query.fql.parse
  (:require #?(:cljs [cljs.reader :refer [read-string]])
            [clojure.set :as set]
            [clojure.walk :refer [postwalk]]
            [fluree.db.constants :as const]
            [fluree.db.datatype :as datatype]
            [fluree.db.query.exec.eval :as eval]
            [fluree.db.query.exec.select :as select]
            [fluree.db.query.exec.update :as update]
            [fluree.db.query.exec.where :as where]
            [fluree.db.query.fql.syntax :as syntax]
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.validation :as v]
            [fluree.json-ld :as json-ld]))

#?(:clj (set! *warn-on-reflection* true))

(defn parse-var-name
  "Returns x as a symbol if x is a valid variable, or nil otherwise. A valid
  variable is a string, symbol, or keyword whose name starts with '?'."
  [x]
  (when (v/variable? x)
    (symbol x)))

(defn parse-variable
  [x]
  (some-> x parse-var-name where/unmatched-var))

(defn- parse-variable-if-allowed
  [allowed-vars x]
  (if (->> x symbol (contains? allowed-vars))
    (parse-variable x)
    (throw
     (ex-info (str "variable " x " is not bound in where nor values clause")
              {:status 400, :error :db/invalid-transaction}))))

(defn parse-value-binding
  [vars vals]
  (let [var-matches (mapv parse-variable vars)
        binding     (mapv (fn [var-match value]
                            (let [dt (datatype/infer value)]
                              (where/match-value var-match value dt)))
                          var-matches vals)]
    (zipmap vars binding)))

(defn parse-values
  [q]
  (when-let [values (:values q)]
    (let [[vars vals] values
          vars*     (keep parse-var-name (util/sequential vars))
          vals*     (mapv util/sequential vals)
          var-count (count vars*)]
      (if (every? (fn [binding]
                    (= (count binding) var-count))
                  vals*)
        [vars* (mapv (partial parse-value-binding vars*)
                     vals*)]
        (throw (ex-info (str "Invalid value binding: "
                             "number of variables and values don't match: "
                             values)
                        {:status 400 :error :db/invalid-query}))))))

(def type-pred-iris #{const/iri-type const/iri-rdf-type})

(defn type-pred-match?
  [p-mch]
  (let [p-iri (::where/iri p-mch)]
    (contains? type-pred-iris p-iri)))

(defn safe-read
  [code-str]
  (try*
    (let [code (read-string code-str)]
      (when-not (list? code)
        (throw (ex-info (code-str "Invalid function: " code-str)
                        {:status 400 :error :db/invalid-query})))
      code)
    (catch* e
      (log/warn e "Invalid query function attempted: " code-str)
      (throw (ex-info (str "Invalid query function: " code-str)
                      {:status 400 :error :db/invalid-query})))))

(defn variables
  "Returns the set of items within the arbitrary data structure `data` that
  are variables ."
  [data]
  (postwalk (fn [x]
              (if (coll? x)
                (apply set/union x)
                (if-let [var (parse-var-name x)]
                  #{var}
                  #{})))
            data))

(defn find-filtered-var
  "Returns the var that will represent flake/o when passed in a flake to execute
  filter fn.

  There can be multiple vars in the filter function which can utilize the
  original query's 'vars' map, however there should be exactly one var in the
  filter fn that isn't in that map - which should be the var that will receive
  flake/o."
  [params vars]
  (let [non-assigned-vars (set/difference params vars)]
    (case (count non-assigned-vars)
      1 (first non-assigned-vars)
      0 (throw (ex-info (str "Query filter function has no variable assigned to it, all parameters "
                             "exist in the 'vars' map. Filter function params: " params ". "
                             "Vars assigned in query: " vars ".")
                        {:status 400
                         :error  :db/invalid-query}))
      ;; else
      (throw (ex-info (str "Vars used in a filter function are not included in the 'vars' map "
                           "or as a binding. Should only be missing one var, but missing: " (vec non-assigned-vars) ".")
                      {:status 400
                       :error  :db/invalid-query})))))

(defn parse-code
  [x]
  (if (list? x)
    x
    (safe-read x)))

(defn parse-filter-function
  "Evals and returns filter function."
  [fltr vars]
  (let [code      (parse-code fltr)
        code-vars (or (not-empty (variables code))
                      (throw (ex-info (str "Filter function must contain a valid variable. Provided: " code)
                                      {:status 400 :error :db/invalid-query})))
        var-name  (find-filtered-var code-vars vars)
        f         (eval/compile-filter code var-name)]
    (where/->var-filter var-name f)))

(defn parse-bind-function
  "Evals and returns bind function."
  [var-name fn-code]
  (let [code (parse-code fn-code)
        f    (eval/compile code false)]
    (where/->var-filter var-name f)))

(defn parse-iri
  [x context]
  (-> x
      (json-ld/expand-iri context)
      where/->iri-ref))

(defn parse-class
  [o-iri context]
  (-> o-iri
      (json-ld/expand-iri context)
      where/->iri-ref))

(defmulti parse-pattern
  (fn [pattern _vars _context]
    (v/where-pattern-type pattern)))

(defn filter-pattern?
  [pattern]
  (-> pattern v/where-pattern-type (= :filter)))

(defn parse-filter-maps
  [vars filters]
  (let [vars (set vars)]
    (->> filters
         (mapcat rest)
         flatten
         (map (fn [fltr]
                (parse-filter-function fltr vars)))
         (reduce (fn [m fltr]
                   (let [var-name (::where/var fltr)]
                     (update m var-name (fn [var-fltrs]
                                          (-> var-fltrs
                                              (or [])
                                              (conj fltr))))))
                 {}))))

(defn parse-bind-map
  [binds]
  (into {}
        (comp (partition-all 2)
              (map (fn [[k v]]
                     (let [var (parse-var-name k)
                           f   (parse-bind-function var v)]
                       [var f]))))
        binds))

(defn parse-where-clause
  [clause vars context]
  (let [clause*  (util/sequential clause)
        patterns (->> clause*
                      (remove filter-pattern?)
                      (mapcat (fn [pattern]
                                (parse-pattern pattern vars context))))
        filters  (->> clause*
                      (filter filter-pattern?)
                      (parse-filter-maps vars))]
    (where/->where-clause patterns filters)))

(defn expand-keys
  [m context]
  (reduce-kv (fn [expanded p o]
               (let [p* (if (v/variable? p)
                          p
                          (json-ld/expand-iri p context))]
                 (assoc expanded p* o)))
             {} m))

(defn parse-value-attributes
  [v attrs]
  (if-let [lang (get attrs const/iri-language)]
    (let [matcher (where/lang-matcher lang)]
      (where/->val-filter v matcher))
    (where/anonymous-value v)))

(defn generate-subject-var
  "Generate a unique subject variable"
  []
  (gensym "?s"))

(defn id-or-variable
  [id]
  (or id (generate-subject-var)))

(defn with-id
  "Searches for the id key, expands it or adds a variable as a value."
  [m context]
  (let [[id-key id] (reduce-kv (fn [_res p o]
                                 (let [expanded (if (v/variable? p)
                                                  p
                                                  (json-ld/expand-iri p context))]
                                   (when (= const/iri-id expanded)
                                     (reduced [p o]))))
                               nil m)]
    (-> m
        (dissoc id-key)
        (assoc const/iri-id id)
        (update const/iri-id id-or-variable))))

(defn parse-subject
  [id context]
  (if (v/variable? id)
    (parse-variable id)
    (parse-iri id context)))

(defn parse-predicate
  [p context]
  (if (v/variable? p)
    (parse-variable p)
    (let [[expanded {reverse :reverse}] (json-ld/details p context)]
      (where/->predicate expanded reverse))))

(def id-predicate-match
  (parse-predicate const/iri-id nil))

(declare parse-statement parse-statements)

(defn flip-reverse-pattern
  [[s-mch p-mch o-mch :as pattern]]
  (if (::where/reverse p-mch)
    [o-mch p-mch s-mch]
    pattern))

(defn parse-object-map
  [s-mch p-mch o context]
  (let [o* (expand-keys o context)]
    (if-let [v (get o* const/iri-value)]
      ;; literal
      (let [attrs (dissoc o* const/iri-value)
            o-mch (parse-value-attributes v attrs)]
        [(flip-reverse-pattern [s-mch p-mch o-mch])])
      ;; ref
      (let [id-map  (with-id o context) ; not o*, we can't use expanded or we'll lose @reverse
            o-mch   (-> id-map
                        (get const/iri-id)
                        (parse-subject context))
            o-attrs (dissoc id-map const/iri-id)]
        ;; return a thunk wrapping the recursive call to preserve stack
        ;; space by delaying execution
        #(into [(flip-reverse-pattern [s-mch p-mch o-mch])]
               (parse-statements o-mch o-attrs context))))))

(defn parse-statement*
  [s-mch p-mch o context]
  (cond
    (v/variable? o)
    (let [o-mch (parse-variable o)]
      [(flip-reverse-pattern [s-mch p-mch o-mch])])

    (map? o)
    (parse-object-map s-mch p-mch o context)

    (sequential? o)
    #(mapcat (fn [o*]
               (parse-statement s-mch p-mch o* context))
             o)

    (type-pred-match? p-mch)
    (let [class-ref (parse-class o context)]
      [(where/->pattern :class (flip-reverse-pattern [s-mch p-mch class-ref]))])

    :else
    (let [o-mch (where/anonymous-value o)]
      [(flip-reverse-pattern [s-mch p-mch o-mch])])))

(defn parse-statement
  [s-mch p-mch o context]
  (trampoline parse-statement* s-mch p-mch o context))

(defn parse-statements*
  [s-mch attrs context]
  #(mapcat (fn [[p o]]
             (let [p-mch (parse-predicate p context)]
               (parse-statement s-mch p-mch o context)))
           attrs))

(defn parse-statements
  [s-mch attrs context]
  (trampoline parse-statements* s-mch attrs context))

(defn parse-id-map-pattern
  [m context]
  (let [s-mch (-> m
                  (get const/iri-id)
                  (parse-subject context))
        attrs (dissoc m const/iri-id)]
    (if (empty? attrs)
      (let [o-mch (-> s-mch ::where/iri where/anonymous-value)]
        [[s-mch id-predicate-match o-mch]])
      (parse-statements s-mch attrs context))))

(defn parse-node-map
  [m context]
  (-> m
      (with-id context)
      (parse-id-map-pattern context)))

(defmethod parse-pattern :node
  [m _vars context]
  (parse-node-map m context))

(defmethod parse-pattern :union
  [[_ & unions] vars context]
  (let [parsed (mapv (fn [clause]
                       (parse-where-clause clause vars context))
                     unions)]
    [(where/->pattern :union parsed)]))

(defmethod parse-pattern :optional
  [[_ & optionals] vars context]
  (into []
        (comp (map (fn [clause]
                     (parse-where-clause clause vars context)))
              (map (partial where/->pattern :optional)))
        optionals))

(defmethod parse-pattern :bind
  [[_ & binds] _vars _context]
  (let [parsed (parse-bind-map binds)]
    [(where/->pattern :bind parsed)]))

(defmethod parse-pattern :graph
  [[_ graph where] vars context]
  (let [graph* (or (parse-variable graph)
                   graph)
        where* (parse-where-clause where vars context)]
    [(where/->pattern :graph [graph* where*])]))

(defn parse-where
  [q vars context]
  (when-let [where (:where q)]
    (-> where
        syntax/coerce-where
        (parse-where-clause vars context))))

(defn parse-as-fn
  [f]
  (let [parsed-fn  (parse-code f)
        fn-name    (some-> parsed-fn second first)
        bind-var   (last parsed-fn)
        aggregate? (when fn-name (eval/allowed-aggregate-fns fn-name))]
    (-> parsed-fn
        eval/compile
        (select/as-selector bind-var aggregate?))))

(defn parse-fn
  [f]
  (-> f parse-code eval/compile select/aggregate-selector))

(defn reverse?
  [context k]
  (-> context
      (get-in [k :reverse])
      boolean))

(defn expand-selection
  [selection depth context]
  (reduce
   (fn [acc select-item]
     (cond
       (map? select-item)
       (let [[k v]  (first select-item)
             iri    (json-ld/expand-iri k context)
             spec   {:iri iri}
             depth* (if (zero? depth)
                      0
                      (dec depth))
             spec*  (-> spec
                        (assoc :spec (expand-selection v depth* context)
                          :as k))]
         (if (reverse? context k)
           (assoc-in acc [:reverse iri] spec*)
           (assoc acc iri spec*)))

       (#{"*" :* '*} select-item)
       (assoc acc :wildcard? true)

       (#{"_id" :_id} select-item)
       (assoc acc :_id? true)

       :else
       (let [iri  (json-ld/expand-iri select-item context)
             spec {:iri iri, :as select-item}]
         (if (reverse? context select-item)
           (assoc-in acc [:reverse iri] spec)
           (assoc acc iri spec)))))
   {:depth depth} selection))

(defn parse-select-map
  [sm depth context]
  (log/trace "parse-select-map:" sm)
  (let [[subj selection] (first sm)
        spec             (expand-selection selection depth context)]
    (if (v/variable? subj)
      (let [var (parse-var-name subj)]
        (select/subgraph-selector var selection depth spec))
      (let [iri (json-ld/expand-iri subj context)]
        (select/subgraph-selector iri selection depth spec)))))

(defn parse-selector
  [context depth s]
  (let [[selector-type selector-val] (syntax/parse-selector s)]
    (case selector-type
      :wildcard select/wildcard-selector
      :var (-> selector-val symbol select/variable-selector)
      :aggregate (case (first selector-val)
                   :string-fn (if (re-find #"^\(as " s)
                                (parse-as-fn s)
                                (parse-fn s))
                   :list-fn (if (= 'as (first s))
                              (parse-as-fn s)
                              (parse-fn s)))
      :select-map (parse-select-map s depth context))))

(defn parse-select-clause
  [clause context depth]
  (if (sequential? clause)
    (mapv (partial parse-selector context depth)
          clause)
    (parse-selector context depth clause)))

(defn parse-select
  [q context]
  (let [depth      (or (:depth q) 0)
        select-key (some (fn [k]
                           (when (contains? q k) k))
                         [:select :selectOne :select-one
                          :selectDistinct :select-distinct])
        select     (-> q
                       (get select-key)
                       (parse-select-clause context depth))]
    (case select-key
      (:select
       :select-one
       :select-distinct) (assoc q select-key select)

      :selectOne (-> q
                     (dissoc :selectOne)
                     (assoc :select-one select))

      :selectDistinct (-> q
                          (dissoc :selectDistinct)
                          (assoc :select-distinct select)))))

(defn ensure-vector
  [x]
  (if (vector? x)
    x
    [x]))

(defn parse-grouping
  [q]
  (some->> (or (:groupBy q)
               (:group-by q))
           ensure-vector
           (mapv parse-var-name)))

(defn parse-ordering
  [q]
  (some->> (or (:order-by q)
               (:orderBy q))
           ensure-vector
           (mapv (fn [ord]
                   (if-let [v (parse-var-name ord)]
                     [v :asc]
                     (let [[dir dim] ord
                           v (parse-var-name dim)]
                       (if (syntax/asc? dir)
                         [v :asc]
                         [v :desc])))))))

(defn parse-having
  [q]
  (if-let [code (some-> q :having parse-code)]
    (assoc q :having (eval/compile code))
    q))

(defn parse-fuel
  [{:keys [opts] :as q}]
  (if-let [max-fuel (or (:max-fuel opts) (:maxFuel opts))]
    (assoc q :fuel max-fuel)
    q))

(defn parse-analytical-query
  [q context]
  (let [[vars values] (parse-values q)
        where    (parse-where q vars context)
        grouping (parse-grouping q)
        ordering (parse-ordering q)]
    (-> q
        (assoc :context context
          :where where)
        (cond-> (seq values) (assoc :values values)
                grouping (assoc :group-by grouping)
                ordering (assoc :order-by ordering))
        parse-having
        (parse-select context)
        parse-fuel)))

(defn parse-query
  [q context]
  (log/trace "parse-query" q)
  (-> q
      syntax/coerce-query
      (parse-analytical-query context)))

(defn temp-bnode-id
  "Generate a temporary bnode id. This will get replaced during flake creation when a sid is generated."
  [bnode-counter]
  (str "_:fdb" (vswap! bnode-counter inc)))

(declare parse-subj-cmp)
(defn parse-obj-cmp
  [bnode-counter allowed-vars subj-cmp pred-cmp m triples
   {:keys [list id value type language] :as v-map}]
  (cond
    list
    (reduce (fn [triples [i list-item]]
              (parse-obj-cmp bnode-counter allowed-vars subj-cmp pred-cmp {:i i}
                             triples list-item))
            triples
            (map vector (range) list))

    ;; literal object
    (some? value)
    (let [m*      (cond-> m language (assoc :lang language))
          obj-cmp (if (v/variable? value)
                    (parse-variable-if-allowed allowed-vars value)
                    (where/anonymous-value value type m*))]
      (conj triples [subj-cmp pred-cmp obj-cmp]))

    ;; ref object
    :else
    (let [ref-obj (if (v/variable? id)
                    (parse-variable-if-allowed allowed-vars id)
                    (where/match-iri
                     (if (nil? id)
                       (temp-bnode-id bnode-counter)
                       id)))
          ref-cmp (if m
                    (assoc ref-obj ::where/meta m)
                    ref-obj)
          v-map*  (if (nil? id)
                    ;; project newly created bnode-id into v-map
                    (assoc v-map :id (where/get-iri ref-cmp))
                    v-map)]
      (conj (parse-subj-cmp bnode-counter allowed-vars triples v-map*)
            [subj-cmp pred-cmp ref-cmp]))))

(defn parse-pred-cmp
  [bnode-counter allowed-vars subj-cmp triples [pred values]]
  (let [values*  (cond (= pred :type)
                       ;; homogenize @type values so they have the same structure as other predicates
                       (map #(do {:id %}) values)

                       (= pred const/iri-rdf-type)
                       (throw (ex-info (str (pr-str const/iri-rdf-type) " is not a valid predicate IRI."
                                            " Please use the JSON-LD \"@type\" keyword instead.")
                                       {:status 400 :error :db/invalid-predicate}))
                       :else
                       values)
        pred-cmp (cond (v/variable? pred) (parse-variable-if-allowed
                                           allowed-vars pred)
                       ;; we want the actual iri here, not the keyword
                       (= pred :type)     (where/match-iri const/iri-type)
                       :else              (where/match-iri pred))]
    (reduce (partial parse-obj-cmp bnode-counter allowed-vars subj-cmp pred-cmp
                     nil)
            triples
            values*)))

(defn parse-subj-cmp
  [bnode-counter allowed-vars triples {:keys [id] :as node}]
  (let [subj-cmp (cond (v/variable? id) (parse-variable-if-allowed allowed-vars id)
                       (nil? id)        (where/match-iri (temp-bnode-id bnode-counter))
                       :else            (where/match-iri id))]
    (reduce (partial parse-pred-cmp bnode-counter allowed-vars subj-cmp)
            triples
            (dissoc node :id :idx))))

(defn parse-triples
  "Flattens and parses expanded json-ld into update triples."
  [expanded allowed-vars]
  (let [bnode-counter (volatile! 0)]
    (reduce (partial parse-subj-cmp bnode-counter allowed-vars)
            []
            expanded)))

(defn parse-txn
  [txn context]
  (let [vals-map      {:values (util/get-first-value txn const/iri-values)}
        [vars values] (parse-values vals-map)
        where-map     {:where (util/get-first-value txn const/iri-where)}
        where         (parse-where where-map vars context)
        bound-vars    (-> where where/bound-variables (into vars))

        delete (-> (util/get-first-value txn const/iri-delete)
                   (json-ld/expand context)
                   util/sequential
                   (parse-triples bound-vars))
        insert (-> (util/get-first-value txn const/iri-insert)
                   (json-ld/expand context)
                   util/sequential
                   (parse-triples bound-vars))]
    (when (and (empty? insert) (empty? delete))
      (throw (ex-info (str "Invalid transaction, insert or delete clause must contain nodes with objects.")
                      {:status 400 :error :db/invalid-transaction})))
    (cond-> {}
      context      (assoc :context context)
      where        (assoc :where where)
      (seq values) (assoc :values values)
      (seq delete) (assoc :delete delete)
      (seq insert) (assoc :insert insert))))
