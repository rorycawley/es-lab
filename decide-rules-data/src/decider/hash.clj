(ns decider.hash
  "Deterministic content identity for semantic bundles — README sections 30
   to 33.

   No domain knowledge. This namespace answers \"exactly which content did we
   execute?\" and nothing else."
  (:import
   [java.math BigInteger]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

(defn canonical
  "Rewrite `x` so that its printed form does not depend on map or set
   iteration order.

   Every collection becomes a `[tag contents]` pair, which is also what keeps
   the representation unambiguous: a vector cannot print as the map it happens
   to resemble, because the map prints with `:map` in front of it. Scalars are
   returned unchanged.

   Recursive, and with no depth guard of its own. Inside this project it only
   ever sees a bundle that `decider.schema/problems` has already refused if it
   nests past `decider.schema/max-depth`, and `decider.core/prepare` validates
   before it hashes for exactly that reason. Called directly on arbitrarily
   nested data it will overflow the stack."
  [x]
  (cond
    (map? x)
    [:map
     (->> x
          (map (fn [[k v]]
                 [(canonical k)
                  (canonical v)]))
          (sort-by (comp pr-str first))
          vec)]

    (set? x)
    [:set
     (->> x
          (map canonical)
          (sort-by pr-str)
          vec)]

    (vector? x)
    [:vector (mapv canonical x)]

    (seq? x)
    [:list (mapv canonical x)]

    :else
    x))

(defn sha-256
  "The SHA-256 of `x`'s canonical form, as 64 lowercase hex characters.

   Project-level determinism, not a cross-language canonical EDN standard —
   README section 33."
  [x]
  (let [bytes  (.getBytes (pr-str (canonical x))
                          StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        bytes)]
    (format "%064x" (BigInteger. 1 digest))))

(defn specification-hash
  "The content hash of `specification`, prefixed `sha256:`.

   Excludes any `:spec/hash` already present, so hashing a loaded bundle
   reproduces the hash it was loaded with — README section 30. Covers
   everything else, including `:rule/text`, so a wording-only change still
   changes the hash — README section 32."
  [specification]
  (str "sha256:"
       (sha-256 (dissoc specification :spec/hash))))
