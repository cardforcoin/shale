(ns shale.nodes
  (:require [clojure.set :refer [difference]]
            [clojure.core.match :refer [match]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [clojure.walk :refer :all]
            [com.stuartsierra.component :as component]
            [shale.logging :as logging]
            [shale.node-providers :as node-providers]
            [shale.redis :as redis]
            [shale.utils :refer :all]
            [shale.riemann :as riemann])
  (:import java.util.UUID))

(deftype ConfigNodeProvider [])

(defn node-pool-impl-from-config [{:keys [get-node add-node remove-node can-add-node can-remove-node] :as impl}]
  (reify node-providers/INodeProvider
    (get-nodes [this]
      ((:get-nodes impl) this))
    (add-node [this url]
      ((:add-node impl) this url))
    (remove-node [this url]
      ((:remove-node impl) this url))
    (can-add-node [this]
      ((:can-add-node impl) this))
    (can-remove-node [this]
      ((:can-remove-node impl) this))))

(defn node-provider-from-config [config]
  (match config
    {:node-pool-cloud-config cloud-config} (match cloud-config
                                             {:provider :aws} (node-providers/new-aws-node-provider cloud-config)
                                             {:provider :kube} (node-providers/new-kube-node-provider cloud-config))
    {:node-pool-impl impl} (node-pool-impl-from-config impl)
    {:node-list nodes} (node-providers/new-default-node-provider nodes)
    _ (node-providers/new-default-node-provider ["http://localhost:5555/wd/hub"])))

(defrecord NodePool
  [redis-conn
   riemann
   logger
   node-provider
   default-session-limit
   config]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting the node pool...")
    (let [node-provider (node-provider-from-config config)
          default-session-limit (or (:node-max-sessions config) 3)]
      (logging/infof "Found nodes: %s" (vec (node-providers/get-nodes node-provider)))
      (assoc cmp
             :node-provider node-provider
             :default-session-limit default-session-limit)))
  (stop [cmp]
    (logging/info "Stopping the node pool...")
    (assoc cmp :node-provider nil)))

(defn new-node-pool [config]
  (map->NodePool {:config config}))

(s/defn node-ids :- [s/Str] [pool :- NodePool]
  (car/wcar (:redis-conn pool)
    (car/smembers (redis/model-ids-key redis/NodeInRedis))))

(s/defschema NodeView
  "A node, as presented to library users."
  {:id           s/Str
   :url          s/Str
   :tags         #{s/Str}
   :max-sessions s/Int})

(s/defn ->NodeView :- NodeView
  [id :- s/Str
   from-redis :- redis/NodeInRedis]
  (->> from-redis
       (merge {:id id})
       keywordize-keys))

(s/defn view-model :- NodeView
  "Given a node pool, get a view model from Redis."
  [pool :- NodePool
   id   :- s/Str]
  (let [m (redis/model (:redis-conn pool) redis/NodeInRedis id)]
    (->NodeView id m)))

(s/defn view-models :- [NodeView]
  "Get all view models from a node pool."
  [pool :- NodePool]
  (map #(view-model pool %) (node-ids pool)))

(s/defn view-model-from-url :- NodeView
  [pool :- NodePool
   url  :- s/Str]
  (first (filter #(= (% :url) url) (view-models pool))))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- NodePool
   id   :- s/Str]
  (redis/model-exists? (:redis-conn pool) redis/NodeInRedis id))

(defn notify-node-modify [pool id tags max-sessions]
  (riemann/send-event
   (:riemann pool)
   {:id id
    :max-sessions max-sessions
    :state "ok"
    :tags tags}))

(s/defn modify-node :- NodeView
  "Modify a node's url or tags in Redis."
  [pool :- NodePool
   id   :- s/Str
   {:keys [url tags max-sessions]
    :or {url nil
         tags nil
         max-sessions nil}
    :as node}]
  (last
    (car/wcar (:redis-conn pool)
      (let [node-key (redis/model-key redis/NodeInRedis id)
            node-tags-key (redis/node-tags-key id)]
        (redis/hset-all node-key
                        (merge {}
                               (if url {:url (str url)})
                               (if max-sessions {:max-sessions max-sessions})))
        (when tags (redis/sset-all node-tags-key tags))
        (notify-node-modify pool id (into [] tags) max-sessions)
        (car/return (view-model pool id))))))

(defn notify-node-create [pool id tags max-sessions]
  {:id id
   :state "ok"
   :max-sessions max-sessions
   :tags tags})

(s/defn ^:always-validate create-node :- NodeView
  "Create a node in a given pool."
  [pool :- NodePool
   {:keys [url tags max-sessions]
    :or {tags #{}
         max-sessions (:default-session-limit pool)}}]
  (last
    (car/wcar (:redis-conn pool)
      (let [id (gen-uuid)
            node-key (redis/model-key redis/NodeInRedis id)]
        (car/sadd (redis/model-ids-key redis/NodeInRedis) id)
        (notify-node-create pool id (into [] tags) max-sessions)
        (car/return (modify-node pool id {:url url
                                          :tags tags
                                          :max-sessions max-sessions}))))))

(defn notify-node-destroy [pool id]
  (riemann/send-event
   (:riemann pool)
   {:id id
    :state "expired"}))

(s/defn ^:always-validate destroy-node
  [pool :- NodePool
   id   :- s/Str]
  (car/wcar (:redis-conn pool)
    (car/watch (redis/model-ids-key redis/NodeInRedis))
    (try
      (let [url (:url (view-model pool id))]
        (if (some #{url} (node-providers/get-nodes (:node-provider pool)))
          (node-providers/remove-node (:node-provider pool) url)))
      (finally
        (redis/delete-model! (:redis-conn pool) redis/NodeInRedis id)
        (car/del (redis/node-tags-key id))
        (notify-node-destroy pool id))))
  true)

(defn ^:private to-set [s]
  (into #{} s))

(def ^:private refresh-nodes-lock {})

;; TODO
;; - How should refresh riemann event look like?
;; - Should it send also destroy and create event?

(s/defn refresh-nodes
  "Syncs the node list with the backing node provider."
  [pool :- NodePool]
  (locking refresh-nodes-lock
    (logging/debug "Refreshing nodes...")
    (let [nodes (->> (:node-provider pool)
                     node-providers/get-nodes
                     to-set)
          registered-nodes (->> (view-models pool)
                                (map :url)
                                to-set)]
      (logging/debug "Live nodes:")
      (logging/debug nodes)
      (logging/debug "Nodes in Redis:")
      (logging/debug registered-nodes)
      (doall
        (concat
          (map #(create-node pool {:url %})
               (filter identity
                       (difference nodes registered-nodes)))
          (map #(destroy-node pool (:id (view-model-from-url pool %)))
               (filter identity
                       (difference registered-nodes nodes))))))
    true))

(s/defschema NodeRequirement
  "A schema for a node requirement."
  (any-pair
    :id  s/Str
    :tag s/Str
    :url s/Str
    :not (s/recursive #'NodeRequirement)
    :and [(s/recursive #'NodeRequirement)]
    :or  [(s/recursive #'NodeRequirement)]))

(s/defn ^:always-validate raw-sessions-with-node [pool    :- NodePool
                                                  node-id :- s/Str]
  (let [redis-conn (:redis-conn pool)]
    (->> (redis/models redis-conn
                       redis/SessionInRedis
                       :include-soft-deleted? true)
         (filter #(= node-id (:node-id %))))))

(s/defn ^:always-validate raw-session-count [pool    :- NodePool
                                             node-id :- s/Str]
  (count (raw-sessions-with-node pool node-id)))

(s/defn ^:always-validate nodes-under-capacity
  "Nodes with available capacity."
  [pool :- NodePool]
  (let [session-limit (:default-session-limit pool)]
    (filter #(< (raw-session-count pool (:id %)) session-limit)
            (view-models pool))))

(s/defn ^:always-validate matches-requirement :- s/Bool
  [model       :- NodeView
   requirement :- (s/maybe NodeRequirement)]
  (logging/debug
    (format "Testing node %s against requirement %s."
            model
            requirement))
  (if requirement
    (let [[req-type arg] requirement
          n model]
      (-> (match req-type
                 :tag (some #{arg} (:tags n))
                 :id  (= arg (:id n))
                 :url (= arg (:url n))
                 :not (not     (matches-requirement n arg))
                 :and (every? #(matches-requirement n %) arg)
                 :or  (some   #(matches-requirement n %) arg))
          boolean))
    true))

(s/defn ^:always-validate get-node :- (s/maybe NodeView)
  [pool        :- NodePool
   requirement :- (s/maybe NodeRequirement)]
  (try
    (rand-nth
      (filter #(matches-requirement % requirement)
              (nodes-under-capacity pool)))
    (catch IndexOutOfBoundsException e)))
