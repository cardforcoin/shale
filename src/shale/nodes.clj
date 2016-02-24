(ns shale.nodes
  (:require [clojure.set :refer [difference]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [shale.redis :refer :all]
            [shale.utils :refer :all]
            [clojure.walk :refer :all]
            [shale.node-providers :as node-providers]
            [com.stuartsierra.component :as component])
  (:import java.util.UUID
           [shale.node_providers DefaultNodeProvider AWSNodeProvider]))

(deftype ConfigNodeProvider [])

(def node-provider-from-config
  "Return a node pool given a config function.

  The config function should expect a single keyword argument and to look up
  config values.

  Note that, though this function isn't referentially transparent, it's
  memoized. This is a hack, and will be cleaned up when we start using
  components (https://github.com/cardforcoin/shale/issues/58)."
  (memoize
    (fn [config-fn]
      (if (nil? (config-fn :node-pool-impl))
        (if (nil? (config-fn :node-pool-cloud-config))
          (node-providers/DefaultNodeProvider. (or (config-fn :node-list)
                                                ["http://localhost:5555/wd/hub"]))
          (if (= ((config-fn :node-pool-cloud-config) :provider) :aws)
            (node-providers/AWSNodeProvider. (config-fn :node-pool-cloud-config))
            (throw (ex-info (str "Issue with cloud config: AWS is "
                                 "the only currently supported "
                                 "provider.")
                            {:user-visible true :status 500}))))
        (do
          (extend ConfigNodeProvider
            node-providers/INodeProvider
            (config-fn :node-pool-impl))
          (ConfigNodeProvider.))))))

(s/defrecord NodePool [config redis-conn node-provider default-session-limit]
  component/Lifecycle
  (start [cmp]
    (prn "Starting the node pool...")
    (-> cmp
        (assoc :node-provider (node-provider-from-config config))
        (assoc :default-session-limit (or (:node-max-sessions config) 3))))
  (stop [cmp]
    (prn "Stopping the node pool...")
    (assoc cmp :node-provider nil)))

(defn new-node-pool [config]
  (map->NodePool {:config config}))

(s/defn node-ids :- [s/Str] [pool :- NodePool]
  (car/wcar (:redis-conn pool)
    (car/smembers node-set-key)))

(def NodeView
  "A node, as presented to library users."
  {(s/optional-key :id)           s/Str
   (s/optional-key :url)          s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :max-sessions) s/Int})

(s/defn ->NodeView [id :- s/Str
                    from-redis :- NodeInRedis]
  (->> from-redis
       (merge {:id id})
       keywordize-keys))

(s/defn view-model :- NodeView
  "Given a node pool, get a view model from Redis."
  [pool :- NodePool
   id   :- s/Str]
  (let [m (model (:redis-conn pool) NodeInRedis id)]
    (->NodeView id m)))

(s/defn view-models :- [NodeView]
  "Get all view models from a node pool."
  [pool :- NodePool]
  (map #(view-model pool %) (node-ids pool)))

(s/defn view-model-from-url :- NodeView
  [pool :- NodePool
   url  :- s/Str]
  (first (filter #(= (% :url) url) (view-models pool))))

(s/defn modify-node :- NodeView
  "Modify a node's url or tags in Redis. Any provided url that's host isn't an
  IP address will be resolved before storing."
  [pool id {:keys [url tags]
            :or {:url nil
                 :tags nil}}]
  (last
    (car/wcar (:redis-conn pool)
      (let [node-key (node-key id)
            node-tags-key (node-tags-key id)]
        (if url (->> url
                     host-resolved-url
                     str
                     (car/hset node-key :url)))
        (if tags (sset-all node-tags-key tags))
        (car/return (view-model pool id))))))

(s/defn create-node :- NodeView
  "Create a node in a given pool."
  [pool {:keys [url tags]
         :or {:tags []}}]
  (last
    (car/wcar (:redis-conn pool)
      (let [id (gen-uuid)
            node-key (node-key id)]
        (car/sadd node-set-key id)
        (modify-node pool id {:url url :tags tags})
        (car/return (view-model pool id))))))

(s/defn destroy-node
  [pool :- NodePool
   id   :- s/Str]
  (car/wcar (:redis-conn pool)
    (car/watch node-set-key)
    (try
      (let [url (get (view-model pool id) :url)]
        (if (some #{url} (node-providers/get-nodes (:node-provider pool)))
          (node-providers/remove-node (:node-provider pool) url)))
      (finally
        (car/srem node-set-key id)
        (car/del (node-key id))
        (car/del (node-tags-key id)))))
  true)

(defn ^:private to-set [s]
  (into #{} s))

(def ^:private refresh-nodes-lock {})

(s/defn refresh-nodes
  "Syncs the node list with the backing node provider."
  [pool :- NodePool]
  (locking refresh-nodes-lock
    (let [nodes (to-set (node-providers/get-nodes (:node-provider pool)))
          registered-nodes (to-set (map #(get % :url) (view-models pool)))]
      (doall
        (concat
          (map #(create-node pool {:url %})
               (filter identity
                       (difference nodes registered-nodes)))
          (map #(destroy-node pool ((view-model-from-url pool %) :id))
               (filter identity
                       (difference registered-nodes nodes))))))
    true))

(def NodeRequirements
  {(s/optional-key :url)   s/Str
   (s/optional-key :tags) [s/Str]
   (s/optional-key :id)    s/Str})

(s/defn session-count [node-id :- s/Str
                       pool    :- NodePool]
  (->> (models (:redis-conn pool) SessionInRedis)
       (filter #(= node-id (:node-id %)))
       count))

(s/defn nodes-under-capacity
  "Nodes with available capacity."
  [pool :- NodePool]
  (let [session-limit (:default-session-limit pool)]
    (filter #(< (session-count pool (:id %)) session-limit)
            (view-models pool))))

(s/defn matches-requirements :- s/Bool
  [model :- NodeView
   requirements :- NodeRequirements]
  (and
    (if (contains? requirements :id)
      (apply = (map :id [requirements model]))
      true)
    (if (contains? requirements :url)
      (apply = (map :url [requirements model]))
      true)
    (if (contains? requirements :tags)
      (apply clojure.set/subset?
             (map :tags [requirements model]))
      true)))

(s/defn get-node :- (s/maybe NodeView)
  [pool         :- NodePool
   requirements :- NodeRequirements]
  (try
    (rand-nth
      (filter #(matches-requirements % requirements)
              (nodes-under-capacity pool)))
    (catch IndexOutOfBoundsException e)))
