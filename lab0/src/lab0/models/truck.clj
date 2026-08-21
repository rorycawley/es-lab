(ns lab0.models.truck
  "`models/truck.clj`, and the folder name is the whole joke.

  Open almost any web framework, find the folder called `models`, and what is
  in it is rarely a model of anything. It is a set of classes whose fields are
  a table's columns, whose lifecycle is a row's lifecycle, and whose methods
  read and write. The framework calls them models; they are a persistence
  mechanism wearing the domain's vocabulary.

  ## This is not a straw man

  Nothing below is badly written. It is what every ORM tutorial shows you, and
  it works. The store is an atom rather than Postgres so that this lab needs
  nothing installed — the objection is not that databases are slow. It is
  what the business rule has been **tied to**.

  Compare `truck.clj`, which answers the same questions, and notice three
  things this namespace cannot do:

    - it cannot be asked a question without a store
    - it cannot answer one twice the same way, because it reads a clock
    - it has no *name* for either of its rules

  That last one is the expensive one. A rule that exists only as an `if` in
  the middle of a method that also writes cannot be pointed at, cannot be
  tested on its own, and cannot be read back to the person who asked for it.")

;; ---------------------------------------------------------------------------
;; The row
;;
;; Note what the domain has grown: a surrogate id, two timestamps, and a
;; naming convention borrowed from SQL. None of it is anything an ice cream
;; seller has ever mentioned. All of it is load-bearing for the machinery.
;; ---------------------------------------------------------------------------

(defrecord TruckRow [id registration stock created_at updated_at])

(defn ->store
  "Stands in for a database. An atom, so this lab installs nothing."
  []
  (atom {:next-id 1 :rows {}}))

(defn insert!
  [store {:keys [registration stock]}]
  (let [id  (:next-id @store)
        now (java.util.Date.)
        row (map->TruckRow {:id id :registration registration
                            :stock (or stock {})
                            :created_at now :updated_at now})]
    (swap! store #(-> % (assoc-in [:rows id] row) (update :next-id inc)))
    id))

(defn find-by-id
  [store id]
  (get-in @store [:rows id]))

;; ---------------------------------------------------------------------------
;; The business rules, such as they are
;;
;; "You cannot sell what you have not got" is in here somewhere. So is "the
;; truck holds forty cones". Neither has a name, neither can be evaluated
;; without a store, and both are three lines away from a write.
;;
;; This is what the video means by *complecting*: the what of the business and
;; the how of the storage, braided together so that touching either means
;; touching both.
;; ---------------------------------------------------------------------------

(def capacity 40)

(defn sell!
  [store id flavour]
  (let [row (find-by-id store id)]
    (when-not row
      (throw (ex-info "No such truck" {:id id})))
    (when-not (pos? (get (:stock row) flavour 0))
      (throw (ex-info "Sold out" {:flavour flavour})))
    (swap! store (fn [s] (-> s
                             (update-in [:rows id :stock flavour] dec)
                             (assoc-in [:rows id :updated_at] (java.util.Date.)))))
    (find-by-id store id)))

(defn load!
  [store id flavour quantity]
  (let [row (find-by-id store id)]
    (when-not row
      (throw (ex-info "No such truck" {:id id})))
    (when-not (<= (+ (reduce + 0 (vals (:stock row))) quantity) capacity)
      (throw (ex-info "No room on the truck" {:flavour flavour :quantity quantity})))
    (swap! store (fn [s] (-> s
                             (update-in [:rows id :stock flavour] (fnil + 0) quantity)
                             (assoc-in [:rows id :updated_at] (java.util.Date.)))))
    (find-by-id store id)))
