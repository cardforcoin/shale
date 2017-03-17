(ns shale.sessions
  (:require [clojure.set :refer [rename-keys]]
            [clojure.core.match :refer [match]]
            [clojure.walk :refer [postwalk]]
            [clj-webdriver.remote.driver :as remote-webdriver]
            [clj-webdriver.taxi :refer [current-url quit]]
            [clj-http.client :as client]
            [cemerick.url :refer [url]]
            [taoensso.carmine :as car]
            [schema.core :as s]
            [camel-snake-kebab.core :refer :all]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [slingshot.slingshot :refer [try+ throw+]]
            [com.stuartsierra.component :as component]
            [io.aviso.ansi :refer [bold-red bold-green bold-blue]]
            [shale.logging :as logging]
            [shale.nodes :as nodes]
            [shale.proxies :as proxies]
            [shale.utils :refer :all]
            [shale.redis :as redis]
            [shale.selenium :as selenium]
            [shale.webdriver :refer [add-prox-to-capabilities
                                     new-webdriver
                                     resume-webdriver
                                     maybe-add-no-sandbox
                                     to-async
                                     webdriver-capabilities]]
            [shale.riemann :as riemann])
  (:import org.openqa.selenium.WebDriverException
           org.openqa.selenium.remote.UnreachableBrowserException
           org.xbill.DNS.Type
           org.apache.http.conn.ConnectTimeoutException
           java.net.ConnectException
           java.net.SocketTimeoutException
           java.util.concurrent.ExecutionException
           shale.nodes.NodePool))

(s/defrecord SessionPool
  [redis-conn
   riemann
   node-pool
   proxy-pool
   logger
   webdriver-timeout
   start-webdriver-timeout
   config]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting session pool...")
    cmp)
  (stop [cmp]
    (logging/info "Stopping session pool...")
    cmp))

(defn new-session-pool [config]
  (map->SessionPool {:start-webdriver-timeout
                     (or (:start-webdriver-timeout config) 1000)
                     :webdriver-timeout
                     (or (:webdriver-timeout config) 1000)
                     :config config}))

(s/defschema Capabilities {s/Any s/Any})

(s/defschema SessionView
  "A session, as presented to library users."
  {:id             s/Str
   :webdriver-id  (s/maybe s/Str)
   :tags         #{s/Str}
   :reserved       s/Bool
   :current-url   (s/maybe s/Str)
   :browser-name   s/Str
   :node           nodes/NodeView
   :proxy         (s/maybe proxies/ProxyView)
   :capabilities   {s/Keyword s/Any}})

(s/defschema TagChange
  "Add/remove a session/node tag"
  {:action (s/enum :add :remove)
   :tag    s/Str})

(s/defschema Requirement
  "A schema for a session requirement."
  (any-pair
    :id           s/Str
    :tag          s/Str
    :reserved     s/Bool
    :browser-name s/Str
    :current-url  s/Str
    :webdriver-id s/Str
    :node         nodes/NodeRequirement
    :proxy        proxies/ProxyRequirement
    :nil?         (s/enum :webdriver-id :current-url)
    :not          (s/recursive #'Requirement)
    :and          [(s/recursive #'Requirement)]
    :or           [(s/recursive #'Requirement)]))

(defn maybe-bigdec [x]
  (try (bigdec x) (catch NumberFormatException e nil)))

(s/defschema DecString
  "A schema for a string that is convertable to bigdec."
  (s/pred #(boolean (maybe-bigdec %)) 'decimal-string))

(s/defschema Score
  "A schema for a session score rule."
  {:weight  DecString
   :require Requirement})

(s/defn ^:always-validate matches-requirement :- s/Bool
  [session-model :- SessionView
   requirement :- Requirement]
  (logging/debug
    (format "Testing session %s against requirement %s."
            session-model
            requirement))
  (let [[req-type arg] requirement
        s session-model]
    (-> (match req-type
               (:or :id :webdriver-id :reserved :browser-name :current-url)
                 (= arg (get s req-type))
               :tag   (some #{arg} (:tags s))
               :node  (nodes/matches-requirement (:node s) arg)
               :proxy (proxies/matches-requirement (:proxy s) arg)
               :nil?  (nil? (get s arg))
               :not   (not     (matches-requirement s arg))
               :and   (every? #(matches-requirement s %) arg)
               :or    (some   #(matches-requirement s %) arg))
        boolean)))

(declare view-model view-model-exists? view-models view-model-ids
         resume-webdriver-from-id destroy-session)

(defn notify-session-create-error [pool]
  (riemann/send-event
   (:riemann pool)
   {:state "error"}))

(defn start-webdriver!
  "Create a webdriver. Optionally specify a timeout in milliseconds.

  Throws an exception on timeout, but blocks forever by default."
  [pool node capabilities & {:keys [timeout]}]
  (logging/infof "start-webdriver! %s" node)
  (let [future-wd (future (new-webdriver node capabilities))
        wd (if (or (nil? timeout) (= 0 timeout))
               (deref future-wd)
               (deref future-wd timeout ::timeout))]
    (if (= wd ::timeout)
        (do
          (future-cancel future-wd)
          (notify-session-create-error pool)
          (logging/warn (format
                  "Timeout starting new webdriver on node %s"
                  node))
          (throw
            (ex-info "Timeout starting a new webdriver."
                     {:timeout timeout
                      :node-url node})))
        wd)))

(defn notify-session-resume-error [pool id]
  (riemann/send-event
   (:riemann pool)
   {:id id
    :state "error"}))

(defn resume-webdriver-from-id
  "Resume and return a web driver from a session id (versus a webdriver id).
  Optionally include a timeout, in milliseconds.

  Throws an exception on timeout, but blocks forever by default."
  [pool id & {:keys [timeout]}]
  (if-let [model (view-model pool id)]
    (let [webdriver-id (:webdriver-id model)
          node-url (get-in model [:node :url])
          resume-args (map-indexed
                        (fn [index e] (if (= index 2) {"browserName" e} e))
                        [webdriver-id
                         node-url
                         :browser-name])
          future-wd (future (apply resume-webdriver resume-args))
          wd (if (or (nil? timeout) (= 0 timeout))
               (deref future-wd)
               (deref future-wd timeout ::timeout))]
      (if (= wd ::timeout)
        (do
          (future-cancel future-wd)
          (notify-session-resume-error pool id)
          (logging/warn (format
                  "Timeout resuming session %s after %d ms against node %s"
                  webdriver-id
                  timeout
                  node-url))
          (throw
            (ex-info "Timeout resuming webdriver."
                     {:webdriver-id webdriver-id
                      :timeout timeout
                      :node-url node-url})))
        wd))
    ;; TODO Send riemann event for this?
    (throw
      (ex-info "Unknown session id." {:session-id id}))))

(defn webdriver-go-to-url [wd url]
  "Asynchronously point a webdriver to a url.
  Return nil or :webdriver-is-dead."
  (try (do (to-async wd url) nil)
    (catch WebDriverException e :webdriver-is-dead)))

(s/defn ^:always-validate session-go-to-url
  [pool :- SessionPool
   id   :- s/Str
   url  :- s/Str]
  "Asynchronously point a session to a url.
  Return nil or :webdriver-is-dead."
  (let [webdriver-id (:webdriver-id (view-model pool id))]
    (webdriver-go-to-url (resume-webdriver-from-id pool id) url)))

(defn session-go-to-url-or-destroy-session [pool id url]
  "Asynchronously point a session to a url. Destroy the session if the
  webdriver is dead. Return true if everything seems okay."
  (let [okay (not (session-go-to-url pool id current-url))]
    (if-not okay (destroy-session pool id))
    okay))

(def ModifyArg
  "Modification to a session"
  (any-pair
    :change-tag TagChange
    :go-to-url  s/Str
    :reserve    s/Bool))

(s/defn ^:always-validate save-session-tags-to-redis
  [pool :- SessionPool
   id :- s/Str
   tags]
  (car/wcar (:redis-conn pool)
    (redis/sset-all (redis/session-tags-key id) tags)))

(s/defn ^:always-validate save-session-capabilities-to-redis
  [pool :- SessionPool
   id :- s/Str
   capabilities :- {s/Keyword s/Any}]
  (car/wcar (:redis-conn pool)
    (redis/hset-all (redis/session-capabilities-key id) capabilities)))

(s/defn ^:always-validate save-session-diff-to-redis
  "For any key present in the session arg, write that value to redis."
  [pool :- SessionPool
   id   :- s/Str
   session]
  (car/wcar (:redis-conn pool)
    (redis/hset-all
      (redis/model-key redis/SessionInRedis id)
      (merge
        (select-keys
          session
          [:webdriver-id :reserved :current-url :browser-name :proxy-id])
        (if-let [node-id (or (:node-id session) (get-in session [:node :id]))]
          {:node-id node-id})))
    (if (contains? session :tags)
      (save-session-tags-to-redis pool id (:tags session)))
    (if (contains? session :capabilities)
      (save-session-capabilities-to-redis pool id (:capabilities session)))))

(s/defn apply-change-tag :- #{s/Str}
  [tags   :- #{s/Str}
   change :- TagChange]
  (let [{:keys [action tag]} change]
    (case action
      :add (clojure.set/union #{tag} tags)
      :remove (disj tags tag))))


(defn notify-session-modify [pool id tags reserved]
  (riemann/send-event
   (:riemann pool)
   {:id id
    :tags tags
    :reserved reserved
    :state "ok"}))

(s/defn ^:always-validate modify-session :- (s/maybe SessionView)
  "Modify an existing session."
  [pool          :- SessionPool
   id            :- s/Str
   modifications :- [ModifyArg]]
  (logging/info (format "Modifying session %s, %s" id modifications))
  (when (view-model-exists? pool id)
    (let [simple-modifications (->> modifications
                                    (concat [[:change-tag []]])
                                    (map #(apply hash-map %))
                                    (apply merge-with #(if (vector? %1)
                                                         (vec (concat %1 [%2]))
                                                         %2)))
          go-to-url (:go-to-url simple-modifications)
          old-tags (set (:tags (view-model pool id)))
          new-tags (if (contains? simple-modifications :change-tag)
                     (reduce apply-change-tag
                             old-tags
                             (:change-tag simple-modifications))
                     old-tags)
          session-diff (merge (rename-keys simple-modifications
                                           {:reserve :reserved
                                            :go-to-url :current-url})
                              (if-not (nil? new-tags)
                                {:tags new-tags}))]
      (if (or (not go-to-url)
              (session-go-to-url-or-destroy-session id go-to-url))
        (save-session-diff-to-redis pool id session-diff))
      (let [{:keys [tags reserved]} session-diff
            tags (into [] (map name tags))]
        (notify-session-modify pool id tags reserved)))
    (view-model pool id)))

(s/defn ^:always-validate get-node-or-err :- nodes/NodeView
  [pool      :- NodePool
   node-req  :- (s/maybe nodes/NodeRequirement)]
  (let [node (nodes/get-node pool node-req)]
    (when (nil? node)
      (notify-session-create-error pool)
      (throw
        (ex-info "No suitable node found!"
                 {:user-visible true :status 503})))
    node))

(s/defschema CreateArg
  "Session-creation args"
  {(s/optional-key :browser-name)  s/Str
   (s/optional-key :capabilities)  {s/Any s/Any}
   (s/optional-key :node-require)  nodes/NodeRequirement
   (s/optional-key :proxy-require) proxies/ProxyRequirement
   (s/optional-key :reserved)      s/Bool
   (s/optional-key :tags)          #{s/Keyword}})

(def create-defaults
  {:browser-name "firefox"
   :tags #{}
   :reserved false
   :proxy-id nil})

(defn notify-session-create [pool id tags reserved browser-name node-id]
  (riemann/send-event
   (:riemann pool)
   {:id id
    :tags tags
    :reserved reserved
    :browser-name browser-name
    :node-id node-id
    :state "ok"}))

(s/defn ^:always-validate create-session :- SessionView
  [pool :- SessionPool
   options :- CreateArg]
  (logging/info (format "Creating a new session.\nOptions: %s"
                        (pretty options)))
  (when (= 0 (count (nodes/nodes-under-capacity (:node-pool pool))))
    (notify-session-create-error pool)
    (throw
     (ex-info "All nodes are over capacity!"
              {:user-visible true :status 503})))
  (let [{:keys [browser-name
                capabilities
                node-require
                proxy-require
                reserved
                tags]
         :or {node-require nil
              proxy-require nil}
         :as defaulted-options} (merge create-defaults options)
        node (get-node-or-err (:node-pool pool)
                              node-require)
        prox (if proxy-require
               (proxies/get-or-create-proxy!
                 (:proxy-pool pool) proxy-require))
        defaulted-reqs
        (merge defaulted-options
               {:node-id (:id node)
                :proxy-id (:id prox)
                :tags tags
                :current-url nil})
        requested-capabilities
        (let [rekeyed (transform-keys ->camelCaseString
                                      (merge {:browser-name browser-name}
                                             capabilities))]
          (if proxy-require
            (let [{:keys [host port]} prox]
              (add-prox-to-capabilities
                rekeyed (:type prox) host (bigint port)))
            rekeyed))
        requested-capabilities (if (-> pool :config :webdriver :chrome :no-sandbox)
                                 (maybe-add-no-sandbox requested-capabilities)
                                 requested-capabilities)
        id (gen-uuid)
        wd (start-webdriver!
             pool
             (:url node)
             requested-capabilities
             :timeout (:start-webdriver-timeout pool))
        webdriver-id (remote-webdriver/session-id wd)
        actual-capabilities (webdriver-capabilities wd)]

    (let [tags (into [] (map name tags))
          node-id (:id node)]
      (notify-session-create pool id tags reserved browser-name node-id))
    (last
      (car/wcar (:redis-conn pool)
        (car/sadd (redis/model-ids-key redis/SessionInRedis) id)
        (save-session-diff-to-redis pool
                                    id
                                    (merge defaulted-reqs
                                           {:webdriver-id webdriver-id
                                            :capabilities actual-capabilities}))
        (car/return
          (view-model pool id))))))

(s/defn ^:always-validate require->create :- CreateArg
  "Infer session creation options from a session requirement.

  Ignores or'd and regated requirements."
  [req :- (s/maybe Requirement)]
  (let [reqs [req]
        nilled (postwalk #(if (and (sequential? %)
                                   (some #{(first %)} [:or :not]))
                            ::nil
                            %)
                         reqs)
        filtered (postwalk #(if (sequential? %)
                              (vec (filter (partial not= ::nil) %))
                              %)
                           nilled)
        flattened (->> filtered
                       (tree-seq #(and (sequential? %)
                                       (not (#{:node :proxy} (first %))))
                                 identity)
                       (filter #(nil? (s/check Requirement %)))
                       (filter #(and (not= (first %) :and)
                                     (= (count %) 2)))
                       (map (partial apply hash-map))
                       (map #(if (contains? % :tag)
                               (update-in % [:tag] (comp hash-set keyword))
                               %))
                       (apply merge-with (comp vec concat)))
        node-req (:node flattened)
        proxy-req (:proxy flattened)]
    (-> flattened
        (rename-keys {:tag :tags})
        (as-> m
          (if (nil? m)
            {}
            m)
          (if (contains? m :tags)
            (update-in m [:tags] (partial into #{}))
            m))
        (dissoc :current-url :webdriver-id :node :proxy :id)
        (merge (if node-req {:node-require node-req})
               (if proxy-req {:proxy-require proxy-req})))))

(s/defschema GetOrCreateArg
  "The arg to get-or-create-session.

  Note if there's no requirement, but there are creation options, creation will
  be forced."
  {(s/optional-key :require) Requirement  ; filter criteria
   (s/optional-key :score) [Score]        ; sort ranking
   (s/optional-key :create) CreateArg     ; how to create, if creating
   (s/optional-key :modify) [ModifyArg]}) ; modifications to perform always

(s/defn ^:always-validate get-or-create-session
  [pool :- SessionPool
   arg  :- GetOrCreateArg]
  (logging/debug
    (format "Getting or creating a new session.\nRequirements %s" arg))
  (car/wcar (:redis-conn pool)
    (car/return
      (let [force-create (and (contains? arg :create)
                              (not (contains? arg :require)))
            candidate (or
                        (if (not force-create)
                          (->> (view-models pool)
                               (filter
                                 (fn [session-model]
                                   (matches-requirement
                                     session-model
                                     (:require arg))))
                               first))
                        (let [create (or (:create arg)
                                         (require->create (:require arg)))]
                          (create-session pool create)))]
        (if-let [modifications (:modify arg)]
          (modify-session pool
                          (:id candidate)
                          modifications)
          candidate)))))

;; TODO
;; - Send riemann error event for following catches?

(s/defn ^:always-validate destroy-webdriver!
  [pool         :- SessionPool
   webdriver-id :- s/Str
   node-url     :- s/Str
   immediately  :- s/Bool
   callback     :- (s/maybe (s/pred fn? "callback"))]
  (let [timeout (:webdriver-timeout pool)
        session-url (->> webdriver-id
                         (format "./session/%s")
                         (url node-url)
                         str)
        deferred (future
                   (try+
                     (client/delete session-url
                                    {:socket-timeout timeout
                                     :conn-timeout timeout})
                     (logging/info (format "Destroyed webdriver %s on node %s."
                                   webdriver-id
                                   node-url))
                     (catch [:status 404] _
                       (logging/error
                         (format (str "Got a 404 attempting to delete"
                                      " webdriver %s from node %s.")
                                 webdriver-id
                                 node-url)))
                     (catch [:status 500] _
                       (logging/error
                         (format (str "Got a 500 attempting to delete"
                                      " webdriver %s from node %s.")
                                 webdriver-id
                                 node-url)))

                     (catch #(any-instance?
                               [ConnectTimeoutException
                                SocketTimeoutException
                                UnreachableBrowserException]) e
                       (logging/error
                         (format (str "Timeout connecting to node %s"
                                      " to delete webdriver %s.")
                                 node-url
                                 webdriver-id)))
                     (catch ConnectException e
                       (logging/error
                         (format (str "Error connecting to node %s"
                                      " to delete session %s.")
                                 node-url
                                 webdriver-id)))
                     (finally
                       (when-not (nil? callback)
                         (callback)))))]
    (when immediately
      (deref deferred))))

(defn notify-session-destroy [pool id]
  (riemann/send-event
   (:riemann pool)
   {:id id 
    :state "expired"}))

(defn destroy-session
  [pool id & {:keys [immediately] :or {immediately true}}]
  (s/validate SessionPool pool)
  (s/validate s/Str id)
  (car/wcar (:redis-conn pool)
    (logging/info (format "Destroying session %s..." id))
    (car/watch (redis/model-ids-key redis/SessionInRedis))
    (when (view-model-exists? pool id)
      (let [session (view-model pool id)
            redis-conn (:redis-conn pool)
            webdriver-id (:webdriver-id session)
            node-url (get-in session [:node :url])
            _ (redis/soft-delete-model! redis-conn
                                        redis/SessionInRedis
                                        id)
            hard-delete #(redis/delete-model! redis-conn
                                              redis/SessionInRedis
                                              id)]
        (destroy-webdriver! pool
                            webdriver-id
                            node-url
                            (boolean immediately)
                            hard-delete)
        (notify-session-destroy pool id))))
  true)

(def view-model-defaults {:current-url nil
                          :webdriver-id nil})

(s/defn ^:always-validate model->view-model :- (s/maybe SessionView)
  [pool  :- SessionPool
   model :- (s/maybe redis/SessionInRedis)]
  (if-let [base (some->> model
                         (merge view-model-defaults))]
    (let [node (nodes/view-model (:node-pool pool)
                                 (:node-id model))
          prox (if-let [prox-id (:proxy-id model)]
                 (proxies/view-model (:proxy-pool pool) prox-id))]
      (-> base
          (dissoc :node-id :proxy-id)
          (assoc :node node)
          (assoc :proxy prox)))))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- SessionPool
   id   :- s/Str]
  (redis/model-exists? (:redis-conn pool) redis/SessionInRedis id))

(s/defn ^:always-validate view-model :- SessionView
  [pool :- SessionPool
   id   :- s/Str]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
              (car/return (->> (redis/model redis-conn redis/SessionInRedis id)
                               (model->view-model pool))))))

(s/defn ^:always-validate view-models :- [SessionView]
  [pool :- SessionPool]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
      (car/return
        (map (partial model->view-model pool)
             (redis/models redis-conn redis/SessionInRedis))))))

(s/defn ^:always-validate view-model-ids :- [s/Str]
  [pool :- SessionPool]
  (map :id (view-models pool)))

(s/defn ^:always-validate view-model-by-webdriver-id :- SessionView
  "Return a view model with the corresponding webdriver id."
  [pool         :- SessionPool
   webdriver-id :- s/Str]
  (->> (view-models pool)
       (filter #(= (:webdriver-id %) webdriver-id))
       first))

;; TODO
;; - How should refresh riemann event look like?
;; - Should it send also destroy and create event?

(s/defn ^:always-validate refresh-session
  [pool :- SessionPool
   id   :- s/Str]
  (car/wcar (:redis-conn pool)
    (logging/debug (format "Refreshing session %s..." id))
    (car/watch (redis/model-ids-key redis/SessionInRedis))
    (let [sess-key (redis/model-key redis/SessionInRedis id)]
      (car/watch sess-key)
      (try+
        (if-let [wd (resume-webdriver-from-id
                      pool
                      id
                      :timeout (:webdriver-timeout pool))]
          (car/hset sess-key :current-url (current-url wd))
          (destroy-session pool id))
        (catch (any-instance? [WebDriverException
                               UnreachableBrowserException
                               ExecutionException
                               ConnectTimeoutException
                               SocketTimeoutException
                               ConnectException] %)  e
          (destroy-session pool id))
        (catch :timeout e
          (destroy-session pool id)))))
  true)

(def ^:private unmanaged-session-lock {})

(s/defn ^:always-validate destroy-unmanaged-sessions!
  "Destroy any webdrivers on managed nodes without backing Redis records."
  [pool :- SessionPool]
  (locking unmanaged-session-lock
    (let [redis-conn (:redis-conn pool)
          node-pool (:node-pool pool)]
      (car/wcar redis-conn
        (logging/debug "Destroying unmanaged sessions...")
        (car/watch (redis/model-ids-key redis/SessionInRedis))

        (let [known-webdrivers (->> (view-models pool)
                                    (map :webdriver-id)
                                    (into #{}))]
          (doall
            (->> (nodes/view-models node-pool)
                 (map :url)
                 (filter identity)
                 (pmap #(vector % (selenium/session-ids-from-node %)))
                 (map #(map (fn [id] [(first %) id]) (second %)))
                 (apply concat)
                 (pmap #(when-not (known-webdrivers (second %))
                          (destroy-webdriver! pool
                                              (second %)
                                              (first %)
                                              false
                                              nil))))))))))

(s/defn ^:always-validate refresh-sessions
  [pool :- SessionPool
   ids  :- [s/Str]]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
              (logging/debug "Refreshing sessions...")
              (car/watch (redis/model-ids-key redis/SessionInRedis))
              (doall
                (pmap (partial refresh-session pool)
                      (or ids (view-model-ids pool))))
              (destroy-unmanaged-sessions! pool)))
  true)
