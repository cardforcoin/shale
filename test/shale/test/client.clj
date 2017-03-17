(ns shale.test.client
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty :as jetty]
            [clj-webdriver.taxi :refer [execute-script]]
            [shale.client :refer :all]
            [shale.test.utils :refer [with-selenium-servers
                                      with-riemann-server
                                      local-port-available?
                                      clear-redis]
             :as utils]
            [shale.nodes :refer [refresh-nodes]]
            [shale.configurer :refer [get-config]]
            [shale.core :refer [get-shale-system init-cheshire]]
            [com.stuartsierra.component :as component]
            [io.aviso.ansi :refer [bold-red bold-green]])
  (:import java.net.Socket
           java.io.IOException))

(defn server-fixture
  "Fixture that spawns a system and clears Redis. Waits for the system's Jetty
  to boot before running tests."
  [f]
  (let [config (get-config)
        _ (init-cheshire)
        system (component/start (get-shale-system config))
        port (or (:port config) 5000)]
    ; give the app 5 seconds to start, or cry real hard
    (print "Waiting for the web server...")
    (try
      (clear-redis (:redis system))
      ; refresh the nodes, since we cleared redis after start
      (refresh-nodes (:node-pool system))
      (loop [tries 0]
        (if (local-port-available? port)
          (if (< tries 50)
            (do
              (print ".")
              (Thread/sleep 100)
              (recur (inc tries)))
            (throw (ex-info "Timed out waiting for the web server." {})))))
      (f)
      (finally
        (clear-redis (:redis system))
        (component/stop system)))))

(defn logged-in-sessions-fixture [f]
  (let [reqs {:browser-name "phantomjs" :tags []}]
    (doseq [tag-config [#{"logged-in"} #{"logged-out"}]]
      (shale.client/get-or-create-session!
        {:create (assoc reqs :tags tag-config)})))
  (f))

(defn reserved-session-fixture [f]
  (shale.client/get-or-create-session! {:modify [[:reserve true]]
                                        :require [:browser-name "phantomjs"]})
  (f))

(defn delete-sessions-fixture [f]
  (try
    (f)
    (finally
      (shale.client/destroy-sessions!))))

(use-fixtures :once
  (with-selenium-servers [4443 4444])
  (with-riemann-server)
  server-fixture)
(use-fixtures :each delete-sessions-fixture)

(defn session-count []
  (count (shale.client/sessions)))

(defn session-diff [f]
  (let [before (session-count)]
    (f)
    (- (session-count) before)))

(deftest ^:integration test-sessions
  (testing "sessions"
    (is (= 0 (session-count)))))

(deftest ^:integration test-get-or-create
  (testing "get-or-create"
    (testing "creating one session"
      (is (= 1 (session-diff #(shale.client/get-or-create-session!
                             {:require [:browser-name "phantomjs"]})))))

    (testing "another session isn't created based on browser name"
      (is (= 0 (session-diff #(shale.client/get-or-create-session!
                             {:require [:browser-name "phantomjs"]})))))

    (testing "that another session is created based on a tag"
      (let [test-fn (fn []
                        (shale.client/get-or-create-session!
                          {:require [:and [[:browser-name "phantomjs"]
                                           [:tag "some-unknown-tag"]]]}))]
        (is (= 3 (session-diff
                   #(logged-in-sessions-fixture test-fn))))))))

(deftest ^:integration test-get-or-create-with-nodes
  (testing "sessions are created on the specified node"
      (let [node-id (-> (shale.client/nodes)
                        first
                        (get "id"))]
        ; clear all the old sessions
        (shale.client/destroy-sessions!)
        ; and get three new ones on this node
        (dotimes [_ 3]
          (shale.client/get-or-create-session!
            {:require [:and [[:browser-name "phantomjs"]
                             [:node [:id node-id]]
                             [:reserved false]]]
             :modify [[:reserve true]]}))
        (is (= 3 (count
                   (filter #(= (get-in % ["node" "id"]) node-id)
                           (shale.client/sessions))))))))

(deftest ^:integration test-get-or-create-with-proxies
  (testing "sessions are created with the specified proxy"
      (let [host "localhost"
            port 1234
            proxy-req [:and [[:host host]
                             [:port port]
                             [:type :socks5]]]]
        (shale.client/destroy-sessions!)
        (dotimes [_ 3]
          (shale.client/get-or-create-session!
            {:require [:and [[:browser-name "phantomjs"]
                             [:proxy proxy-req]]]}))
        (is (= 1 (count (shale.client/sessions))))
        (let [prox (get (first (shale.client/sessions)) "proxy")]
          (is (some? prox))
          (is (= (select-keys prox ["port" "host" "type"])
                 {"port" port
                  "host" host
                  "type" "socks5" }))))))

(deftest ^:integration test-force-create
  (testing "force create a new session"
    (let [test-fn
          (fn []
            (let [session (shale.client/get-or-create-session!
                            {:create {:browser-name "phantomjs"}})]
              (is (get session "browser_name") "phantomjs")))]
      (is (= 1 (session-diff test-fn))))))

(deftest ^:integration test-tag-modification
  (testing "adding tags"
    (let [test-fn (fn []
                    (let [session (first (shale.client/sessions))
                          id (get session "id")]
                      (shale.client/modify-session! id [[:change-tag
                                                        {:tag "test-tag"
                                                         :action :add}]])
                      id))]
      (is (= "test-tag" (-> (logged-in-sessions-fixture test-fn)
                            shale.client/session
                            (get "tags")
                            sort
                            last))))))

(deftest ^:integration test-reservations
  (testing "releasing a session"
    (let [test-fn (fn []
                    (shale.client/release-session!
                      (get (first (shale.client/sessions)) "id"))
                    (is (not-any? #(get % "reserved")
                                  (shale.client/sessions))))]
      (reserved-session-fixture test-fn)))

  (testing "reserving after creating a new session"
    (let [session (shale.client/get-or-create-session!
                    {:create {:browser-name "phantomjs"}
                     :modify [[:reserve true]]})]
      (is (get session "reserved")))))

(deftest ^:integration test-proxies
  (testing "creating a proxy"
    (let [host "127.0.0.1"
          port 6789
          prox (shale.client/create-proxy!
                 {:host host
                  :port port
                  :type "socks5"})]
      (is (= host (get prox "host")))
      (is (= port (get prox "port")))
      (is (= [prox] (->> (shale.client/proxies)
                         (filter #(= (get % "id")
                                     (get prox "id")))
                         vec))))))

(deftest ^:integration test-webdriver-macro
  (testing "that the with-webdriver* macro properly releases its session"
    (shale.client/with-webdriver* {:create {:browser-name "phantomjs"}
                                   :modify [[:reserve true]]}
      (is (get (first (shale.client/sessions)) "reserved"))
      (is (= 1 (session-count))))
    (is (= 1 (session-count)))
    (is (not (get (first (shale.client/sessions)) "reserved")))))

(deftest ^:integration test-webdriver-js
  (testing "that the wrapped webdriver can execute javascript"
    (shale.client/with-webdriver* {:create {:browser-name "phantomjs"}
                                   :modify [[:reserve true]]}
      (is (= 1 (execute-script "return 1;"))))))
