(ns shale.test.utils
  (:require [clojure.java.io :refer [writer]]
            [taoensso.carmine :as car]
            [com.stuartsierra.component :as component]
            [shale.core :refer [get-app-system]]
            [riemann.core :as rc]
            [riemann.transport.tcp :as rt])
  (:import [org.openqa.selenium.server
            SeleniumServer
            RemoteControlConfiguration]
           [java.io
            IOException
            PrintStream]
           java.net.Socket
           com.aphyr.riemann.client.IRiemannClient))

(defn local-port-available? [port]
  (try
    (Socket. "127.0.0.1" port)
    false
    (catch IOException e
      true)))

(defn start-selenium-server [port & [output-stream]]
  (let [config (RemoteControlConfiguration.)
        _ (.setPort config port)]
    ;; if we've been given an output stream, redirect *out* and System.out
    (binding [*out* (if output-stream
                      (writer output-stream)
                      *out*)]
      (if output-stream
        (System/setOut output-stream))
      (if (local-port-available? port)
        (let [server (SeleniumServer. config)]
          (.start server)
          (.boot server)
          (loop [tries 0]
            (if (local-port-available? port)
              (if (> tries 20)
                (throw (ex-info "Timed out waiting for Selenium to start." {:port port}))
                (do
                  (Thread/sleep 100)
                  (recur (inc tries))))))
          server)
        (throw (ex-info "Selenium port is unavailable." {:port port}))))))

(defn stop-selenium-server [server]
  (.stop server))

(defn make-output-stream [port]
  (PrintStream. (str "selenium-server-" port ".txt")))

(defn with-selenium-servers
  "Returns a fixture that starts and cleans up Selenium nodes based on a list
  of ports."
  [ports & {:keys [redirect-output]
            :or {redirect-output true}}]
  (fn [test-fn]
    (let [output-stream (if redirect-output
                          (PrintStream. "selenium-servers.log"))
          servers (doall
                    (pmap #(start-selenium-server % output-stream) ports))]
      (try
        (test-fn)
        (finally (doall (pmap stop-selenium-server servers)))))))

(defn clear-redis
  "Clear a Redis database given connection options."
  [redis-conn]
  (car/wcar redis-conn
    (car/flushdb)))

(defn with-system-from-config
  [system-atom config]
  (fn [test-fn]
    (reset! system-atom (component/start (get-app-system config)))
    (try
      (test-fn)
      (finally
        (component/stop @system-atom)
        (reset! system-atom nil)))))

(defn with-clean-redis
  "Given a system variable (eg #'system), return a fixture that clears Redis
  before and after running the test function."
  [system-atom]
  (fn [test-fn]
    (let [redis (:redis @system-atom)]
      (clear-redis redis)
      (try
        (test-fn)
        (finally
          (clear-redis redis))))))

(def riemann-test-conf {:host "127.0.0.1" :port 6666})

(defn with-riemann-server
  ([] (with-riemann-server (constantly nil)))
  ([test-events]
   (fn [test-fn]
     (let [stream (atom [])
           s (riemann.streams/append stream)
           server (rt/tcp-server riemann-test-conf)
           core (rc/transition! (rc/core) {:services [server] :streams [s]})]
       (try
         (test-fn)
         (finally
           (test-events @stream)
           (shale.logging/debug (str "Riemann recieved: " @stream))
           (rc/stop! core)))))))
