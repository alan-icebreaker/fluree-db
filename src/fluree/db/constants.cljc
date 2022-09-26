(ns fluree.db.constants)

#?(:clj (set! *warn-on-reflection* true))


;; Version

(def ^:const data_version 4)

;; iri constants
(def ^:const iri-CommitProof "https://ns.flur.ee/ledger#CommitProof")
(def ^:const iri-Commit "https://ns.flur.ee/ledger#Commit")
(def ^:const iri-commit "https://ns.flur.ee/ledger#commit")
(def ^:const iri-DB "https://ns.flur.ee/ledger#DB")
(def ^:const iri-data "https://ns.flur.ee/ledger#data")
(def ^:const iri-t "https://ns.flur.ee/ledger#t")
(def ^:const iri-address "https://ns.flur.ee/ledger#address")
(def ^:const iri-v "https://ns.flur.ee/ledger#v")
(def ^:const iri-flakes "https://ns.flur.ee/ledger#flakes")
(def ^:const iri-size "https://ns.flur.ee/ledger#size")
(def ^:const iri-assert "https://ns.flur.ee/ledger#assert")
(def ^:const iri-retract "https://ns.flur.ee/ledger#retract")
(def ^:const iri-previous "https://ns.flur.ee/ledger#previous")
(def ^:const iri-alias "https://ns.flur.ee/ledger#alias")
(def ^:const iri-ledger "https://ns.flur.ee/ledger#ledger")
(def ^:const iri-branch "https://ns.flur.ee/ledger#branch")
(def ^:const iri-issuer "https://www.w3.org/2018/credentials#issuer")
(def ^:const iri-index "https://ns.flur.ee/ledger#index")
(def ^:const iri-ns "https://ns.flur.ee/ledger#ns")
(def ^:const iri-time "https://ns.flur.ee/ledger#time")
(def ^:const iri-message "https://ns.flur.ee/ledger#message")
(def ^:const iri-tag "https://ns.flur.ee/ledger#tag")
(def ^:const iri-updates "https://ns.flur.ee/ledger#updates")
(def ^:const iri-context "https://ns.flur.ee/ledger#context")

(def ^:const iri-default-context "fluree-default-context")  ;; @id for default context setting

;; system constants

;; @id (unique subject identifier) in the form of IRI
(def ^:const $iri 0)

;; system collection ids
(def ^:const $_tx -1)                                       ;; Note unlike other collection ids, this is never used to generate _tx values, as _tx has the full negative range
(def ^:const $_predicate 0)
(def ^:const $_collection 1)
(def ^:const $_shard 2)
(def ^:const $_tag 3)
(def ^:const $_fn 4)
(def ^:const $_user 5)
(def ^:const $_auth 6)
(def ^:const $_role 7)
(def ^:const $_rule 8)
(def ^:const $_setting 9)
(def ^:const $_ctx 10)
(def ^:const $_prefix 11)
(def ^:const $_default 12)

(def ^:const $numSystemCollections 19)                      ;; max number reserved for 'system'
(def ^:const $maxSystemPredicates 999)

;; predicate id constants

(def ^:const $_block:hash 1)                                ;; JSON-LD: turning into data/db id
(def ^:const $_block:prevHash 2)
(def ^:const $_block:transactions 3)                        ;; JSON-LD: turning into commit id ref
(def ^:const $_block:ledgers 4)                             ;; JSON-LD - reuse as commit message
(def ^:const $_block:instant 5)                             ;; JSON-LD: turning into commit timestamp
(def ^:const $_block:number 6)                              ;; JSON-LD: reuse as commit tag(s)
(def ^:const $_block:sigs 7)                                ;; JSON-LD: turning into signer of commit



(def ^:const $_predicate:name 10)
(def ^:const $_predicate:doc 11)
(def ^:const $_predicate:type 12)
(def ^:const $_predicate:unique 13)
(def ^:const $_predicate:multi 14)
(def ^:const $_predicate:index 15)
(def ^:const $_predicate:upsert 16)
(def ^:const $_predicate:component 17)
(def ^:const $_predicate:noHistory 18)
(def ^:const $_predicate:restrictCollection 19)
(def ^:const $_predicate:spec 20)
(def ^:const $_predicate:encrypted 21)
(def ^:const $_predicate:deprecated 22)
(def ^:const $_predicate:specDoc 23)
(def ^:const $_predicate:txSpec 24)
(def ^:const $_predicate:txSpecDoc 25)
(def ^:const $_predicate:restrictTag 26)
(def ^:const $_predicate:fullText 27)
(def ^:const $_predicate:equivalentProperty 28)                          ;; any unique alias for predicate
(def ^:const $_predicate:retractDuplicates 29)             ;; if transaction flake duplicates existing flake, always retract/insert (default behavior ignores new flake)
;; TODO - jumping predicate ids - rethink ordering a bit
(def ^:const $rdf:type 200)
(def ^:const $rdfs:subClassOf 201)
(def ^:const $rdfs:subPropertyOf 202)
(def ^:const $rdfs:Class 203)
(def ^:const $rdf:Property 204)
;; owl
(def ^:const $owl:Class 205)
(def ^:const $owl:ObjectProperty 206)
(def ^:const $owl:DatatypeProperty 207)
;; fluree-specific
(def ^:const $fluree:context 208)

(def ^:const $fluree:default-context 300)


(def ^:const $_tag:id 30)
(def ^:const $_tag:doc 31)

(def ^:const $_collection:name 40)
(def ^:const $_collection:doc 41)
(def ^:const $_collection:version 42)
(def ^:const $_collection:spec 43)
(def ^:const $_collection:specDoc 44)
(def ^:const $_collection:shard 45)
;(def ^:const $_collection:equivalentClass 46)
(def ^:const $_collection:partition 47)

(def ^:const $_user:username 50)
(def ^:const $_user:auth 51)
(def ^:const $_user:roles 52)
(def ^:const $_user:doc 53)

(def ^:const $_auth:id 60)
(def ^:const $_auth:password 61)
(def ^:const $_auth:salt 62)
(def ^:const $_auth:roles 65)
(def ^:const $_auth:doc 66)
(def ^:const $_auth:type 67)
(def ^:const $_auth:authority 68)
(def ^:const $_auth:fuel 69)

(def ^:const $_role:id 70)
(def ^:const $_role:doc 71)
(def ^:const $_role:rules 72)
(def ^:const $_role:ctx 73)

(def ^:const $_rule:id 80)
(def ^:const $_rule:doc 81)
(def ^:const $_rule:collection 82)
(def ^:const $_rule:predicates 83)
(def ^:const $_rule:fns 84)
(def ^:const $_rule:ops 85)
(def ^:const $_rule:collectionDefault 86)
(def ^:const $_rule:errorMessage 87)

(def ^:const $_fn:name 90)
(def ^:const $_fn:params 91)
(def ^:const $_fn:code 92)
(def ^:const $_fn:doc 93)
(def ^:const $_fn:spec 94)
(def ^:const $_fn:language 95)

(def ^:const $_tx:hash 99)

(def ^:const $_tx:id 100)
(def ^:const $_tx:auth 101)
(def ^:const $_tx:authority 102)
(def ^:const $_tx:nonce 103)
(def ^:const $_tx:altId 104)
(def ^:const $_tx:doc 105)
(def ^:const $_tx:tx 106)
(def ^:const $_tx:sig 107)
(def ^:const $_tx:tempids 108)
(def ^:const $_tx:error 109)
(def ^:const $_tx:signed 130)

(def ^:const $_setting:anonymous 110)
(def ^:const $_setting:ledgers 111)
(def ^:const $_setting:consensus 112)
(def ^:const $_setting:doc 113)
(def ^:const $_setting:passwords 114)
(def ^:const $_setting:txMax 115)
(def ^:const $_setting:id 116)
(def ^:const $_setting:language 117)

(def ^:const $_shard:name 120)
(def ^:const $_shard:miners 121)
(def ^:const $_shard:mutable 122)

(def ^:const $_ctx:name 140)
(def ^:const $_ctx:key 141)
(def ^:const $_ctx:fn 142)
(def ^:const $_ctx:doc 143)

;; tags
;; _predicate/type tags
(def ^:const _predicate$type:string 1)
(def ^:const _predicate$type:ref 2)
(def ^:const _predicate$type:boolean 4)
(def ^:const _predicate$type:instant 5)
(def ^:const _predicate$type:uuid 6)
(def ^:const _predicate$type:uri 7)
(def ^:const _predicate$type:bytes 8)
(def ^:const _predicate$type:int 9)
(def ^:const _predicate$type:long 10)
(def ^:const _predicate$type:bigint 11)
(def ^:const _predicate$type:float 12)
(def ^:const _predicate$type:double 13)
(def ^:const _predicate$type:bigdec 14)
(def ^:const _predicate$type:tag 15)
(def ^:const _predicate$type:json 16)
(def ^:const _predicate$type:geojson 17)
;; _rule/ops tags
(def ^:const _rule$ops:all 30)
(def ^:const _rule$ops:transact 31)
(def ^:const _rule$ops:query 32)
(def ^:const _rule$ops:logs 33)
(def ^:const _rule$ops:token 34)
;; _setting/consensus tags
(def ^:const _setting$consensus:raft 40)
(def ^:const _setting$consensus:pbft 41)
;; _auth/type tags
(def ^:const _auth$type:secp256k1 50)
(def ^:const _auth$type:password-secp256k1 55)
;; _setting/language tags
(def ^:const _setting$language:ar 61)                       ; Arabic
(def ^:const _setting$language:bn 62)                       ; Bengali
(def ^:const _setting$language:br 63)                       ; Brazilian Portuguese
(def ^:const _setting$language:cn 64)                       ; "Smart Chinese"
(def ^:const _setting$language:en 65)                       ; English
(def ^:const _setting$language:es 66)                       ; Spanish
(def ^:const _setting$language:fr 67)                       ; French
(def ^:const _setting$language:hi 68)                       ; Hindi
(def ^:const _setting$language:id 69)                       ; Indonesian
(def ^:const _setting$language:ru 70)                       ; Russian


;;

