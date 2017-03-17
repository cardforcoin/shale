(ns shale.test.handler
  (:require [clojure.test :refer :all]
            [cheshire [core :as json]]
            [ring.mock.request :as mock]
            [com.stuartsierra.component :as component]
            [shale.utils :refer [gen-uuid]]
            [shale.test.utils :refer [with-selenium-servers
                                      with-clean-redis
                                      with-riemann-server]
             :as utils]
            [shale.core :refer [get-app-system]]))

; the config we'll be testing

(def system
  (get-app-system {:node-list ["http://localhost:4444/wd/hub"]
                   :riemann utils/riemann-test-conf}))

(defn start-stop-system [test-fn]
  (alter-var-root #'system component/start)
  (try
    (test-fn)
    (finally
      (alter-var-root #'system component/stop))))

(def once-fixtures
  [(with-selenium-servers [4444])
   (with-riemann-server)
   start-stop-system])

(def each-fixtures
  [(with-clean-redis #'system)])

(use-fixtures :once (join-fixtures once-fixtures))

(use-fixtures :each (join-fixtures each-fixtures))

(defn is-status [resp status]
  (is (= (:status resp) status)))

(defn is-200 [resp]
  (is-status resp 200))

(defn is-404 [resp]
  (is-status resp 404))

(defn is-body [resp body]
  (is (= (:body resp) body)))

(defn is-json [resp]
  (is (.contains (get-in resp [:headers "Content-Type"]) "json"))
  (let [[parse-error? result]
        (try
          [false (json/parse-string (:body resp))]
          (catch com.fasterxml.jackson.core.JsonParseException e
            [true nil]))]
    (is (false? parse-error?)
        (str "Body should be valid JSON:" (:body resp)))
    result))

(deftest ^:integration basic-routes
  (testing "main route"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get "/"))]
      (is-200 response)))

  (testing "sessions route"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get "/sessions"))]
      (is-200 response)
      (is-body response "[]\n")))

  (testing "nodes route"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get "/nodes"))]
      (is-200 response)
      (is-body response "[]\n")))

  (testing "proxies route"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get "/proxies"))]
      (is-200 response)
      (is-body response "[]\n")))

  (testing "non-existent sessions 404"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get (str "/sessions/" (gen-uuid))))]
      (is-404 response)))

  (testing "non-existent nodes 404"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get (str "/nodes/" (gen-uuid))))]
      (is-404 response)))

  (testing "non-existent proxies 404"
    (let [app (:ring-app (:app system))
          response (app (mock/request :get (str "/proxies/" (gen-uuid))))]
      (is-404 response))))

(defn refresh-nodes [app]
  (app (mock/request :post "/nodes/refresh")))

(deftest ^:integration node-properties
  (testing "refreshing nodes populates the node list"
    (let [app (:ring-app (:app system))
          resp-1 (app (mock/request :get "/nodes"))
          resp-refresh (refresh-nodes app)
          resp-2 (app (mock/request :get "/nodes"))]
      (doto resp-1
        (is-200)
        (is-json)
        (is-body "[]\n"))
      (doto resp-refresh
        (is-status 201)
        (is-body ""))
      (is-200 resp-2)
      (let [parsed (is-json resp-2)
            node-list (:node-list (:config system))]
        (is (= (count (set (map #(get % "url") parsed)))
               (count (set node-list)))
            (str "There should be the same number of uniquely configured node "
                 "URLs as nodes.")))))
  (testing "nodes have reasonable default properties"
    (let [app (:ring-app (:app system))
          resp (app (mock/request :get "/nodes"))]
      (is-200 resp)
      (let [parsed (is-json resp)
            is-correct (fn [node]
                         (let [{max-sessions "max_sessions"
                                tags "tags"}
                               node]
                           (is (= tags [])
                               "Tags should be an empty list or vector.")
                           (is (or (nil? max-sessions) (number? max-sessions))
                               "Max sessions should be a number or nil.")))]
        (doall
          (map is-correct parsed))))))
