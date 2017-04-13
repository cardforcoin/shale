(ns shale.redis
  (:require [clojure.walk :refer [keywordize-keys]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [schema.coerce :as coerce]
            [shale.utils :refer :all]))

(defn hset-all [k m]
  (doseq [[a b] m]
    (car/hset k a b)))

(defn sset-all [k s]
  (car/del k)
  (doseq [a s]
    (car/sadd k a)))

;; in-database models
(def redis-key-prefix "_shale")

(def session-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "tags"])))

(def session-node-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "node"])))

(def session-capabilities-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "capabilities"])))

(defn session-tags-key [session-id]
  (format session-tags-key-template session-id))

(defn session-node-key [session-id]
  (format session-node-key-template session-id))

(defn session-capabilities-key [session-id]
  (format session-capabilities-key-template session-id))

(def node-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "nodes" "%s" "tags"])))

(defn node-tags-key [id]
  (format node-tags-key-template id))

(def soft-delete-sub-key
  (apply str (interpose "/" [redis-key-prefix "deleting"])))

;; model schemas
(defmacro defmodel
  "A wrapper around schema's `defschema` to attach additional model metadata.

  For example,
  ```
  > (defmodel Model
      \"My Redis model.\"
      {(s/optional-key :name) s/Str})
  > (meta Model)
  {:doc \"My Redis model.\"
   :name \"Model\"
   :model-name \"Model\"
   :redis-key \"_shale/Model\"}

  > (defmodel FancyModel
      \"My fancy model.\"
      :model-name \"fancy\"
      {(s/optional-key :field) s/Int})
  > (meta FancyModel)
  {:doc \"My fancy model.\"
   :name \"Fancy\"
   :model-name \"fancy\"
   :redis-key \"_shale/fancy\"}
  ```"
  [name & forms]
  (let [optional-args (butlast forms)
        docstring (if (string? (first optional-args))
                    (first optional-args))
        options (s/validate (s/either (s/eq nil)
                                      (s/pred #(even? (count %))))
                            (if (nil? docstring)
                              optional-args
                              (rest optional-args)))
        schema (last forms)
        option-map (->> options
                        (partition 2)
                        (map vec)
                        (into {}))
        meta-options (update-in option-map
                                [:model-name]
                                #(or % name))
        default-redis-key (->> [redis-key-prefix (:model-name meta-options)]
                               (clojure.string/join "/"))]
    `(def ~name
       ~(or docstring "")
       (merge-meta (schema.core/schema-with-name ~schema '~name)
                   (merge {:redis-key ~default-redis-key}
                          ~meta-options)))))

(defmodel SessionInRedis
  "A session, as represented in redis."
  :model-name "sessions"
  {:id             s/Str
   :webdriver-id   (s/maybe s/Str)
   :tags           #{s/Str}
   :reserved       s/Bool
   :current-url    (s/maybe s/Str)
   :browser-name   s/Str
   :node-id        s/Str
   :proxy-id       (s/maybe s/Str)
   :capabilities   {s/Keyword s/Any}})

(defmodel NodeInRedis
  "A node, as represented in redis."
  :model-name "nodes"
  {:id             s/Str
   :url            s/Str
   :tags         #{s/Str}
   :max-sessions   s/Int})

(s/defschema IPAddress
  (s/pred is-ip?))

(defmodel ProxyInRedis
  "A proxy, as represented in redis."
  :model-name "proxies"
  {:id        s/Str
   :public-ip (s/maybe IPAddress)
   :type      (s/enum :socks5 :http)
   :host      s/Str
   :port      s/Int
   :tags      #{s/Str}
   :active    s/Bool
   :shared    s/Bool})

;; model fetching

(s/defn ^:always-validate model-ids-key :- s/Str
  "Return the key containing all IDs of a particular model in Redis.

  This is useful, for example, to watch when we need atomicity across model
  records."
  [model-schema]
  (:redis-key (meta model-schema)))

(s/defn ^:always-validate model-key :- s/Str
  [model-schema
   id :- s/Str]
  (clojure.string/join "/" [(model-ids-key model-schema) id]))

(s/defn ^:always-validate model-ids :- [s/Str]
  "Return all "
  [redis-conn model-schema]
  (wcar redis-conn
    (car/smembers (model-ids-key model-schema))))

(s/defn ^:always-validate model-exists? :- s/Bool
  [redis-conn
   model-schema
   id :- s/Str]
  (not (nil? (some #{id} (model-ids redis-conn model-schema)))))

(defn is-map-type?
  "Unfortunately, distinguishing between maps and records isn't the default
  behavior of map?."
  [m]
  (and (map? m) (not (record? m))))

(defn coerce-model
  [model-schema data]
  (let [coercer (coerce/coercer model-schema coerce/string-coercion-matcher)
        coerced (coercer data)]
    (when (instance? schema.utils.ErrorContainer coerced)
      (throw (ex-info "Cannot coerce Redis model"
                      {:schema model-schema
                       :data data
                       :error coerced})))
    coerced))

(defn key-exists? [conn k]
  (pos? (car/wcar conn (car/exists k))))

(defn model
  "Return a model from a Redis key given a particular schema.

  Models understand and serialize most clojure primitives, including shallow
  sequentials, maps, and sets. Int, strings, etc are stored on the hash at the
  model's key, while the rest are stored at keys that share the model's key as
  a prefix. For example,

  ```
  > (defmodel Person
      {(s/optional-key :age)          s/Int
       (s/optional-key :nicknames)   #{s/Str}})
  ;; stored as {\"age\" \"10\"} at \"_shale/Person/<id>\" and #{\"oli\" \"joe\"}
  ;; at \"_shale/Person/<id>/nicknames\".
  ```"
  [redis-conn model-schema id]
  (let [set-keys (->> model-schema
                      (keys-with-vals-matching-pred set?)
                      (map #(name (if (keyword? %)
                                    %
                                    (:k %)))))
        list-keys (->> model-schema
                       (keys-with-vals-matching-pred sequential?)
                       (map #(name (if (keyword? %)
                                     %
                                     (:k %)))))
        map-keys (->> model-schema
                      (keys-with-vals-matching-pred is-map-type?)
                      (map #(name (if (keyword? %)
                                    %
                                    (:k %)))))
        k (model-key model-schema id)]
    (last
     (wcar redis-conn
           (car/watch k)
           (car/return
            (when (key-exists? redis-conn k)
              (let [base (vector->map (wcar redis-conn
                                            (car/hgetall k)))
                    sets (for [set-k set-keys]
                           {set-k (->> [k set-k]
                                       (clojure.string/join "/")
                                       car/smembers
                                       (wcar redis-conn)
                                       (into #{}))})
                    lists (for [list-k list-keys]
                            {list-k (list
                                     (wcar redis-conn
                                           (-> (clojure.string/join "/" [k list-k])
                                               (car/lrange 0 -1))))})
                    maps (for [map-k map-keys]
                           {map-k (->> [k map-k]
                                       (clojure.string/join "/")
                                       car/hgetall
                                       (wcar redis-conn)
                                       (apply hash-map))})]
                (->> (concat sets lists maps)
                     (list* base)
                     (reduce merge)
                                        ; remove internal keys
                     (filter #(not (.startsWith (key %) redis-key-prefix)))
                     (into {})
                     keywordize-keys
                     (merge {:id id})
                     (coerce-model model-schema)))))))))

(defn soft-delete-model!
  "Add a flag to a Redis model to signify a \"soft delete\".

  Soft-deleted models won't show up when listing a model unless specified."
  [redis-conn model-schema id]
  (let [m-key (model-key model-schema id)
        ids-key (model-ids-key model-schema)]
    (wcar redis-conn
      (car/watch ids-key)
      (car/hset m-key soft-delete-sub-key true))))

(s/defn delete-model!
  "Hard delete a model from Redis."
  [redis-conn
   model-schema
   id :- s/Str]
  (let [m-key (model-key model-schema id)
        ids-key (model-ids-key model-schema)]
    (wcar redis-conn
      (car/watch ids-key)
      ; delete any associated keys first
      (doall
        (for [k (->> model-schema
                     (keys-with-vals-matching-pred coll?)
                     (map :k))]
          (->> [m-key k]
               (clojure.string/join "/")
               car/del
               (car/wcar redis-conn))))
      ; delete the base model data
      (car/del m-key)
      (car/srem ids-key id)))
  true)

(defn models [redis-conn model-schema & {:keys [include-soft-deleted?]
                                         :or {include-soft-deleted? false}}]
  (wcar redis-conn
    (car/return
      (->> (model-ids redis-conn model-schema)
           (map #(model redis-conn model-schema %))
           (filter #(or (not (get % soft-delete-sub-key))
                        include-soft-deleted?))
           (filter identity)))))
