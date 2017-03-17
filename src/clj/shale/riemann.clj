(ns shale.riemann
  (require [riemann.client :as riemann]
           [shale.logging :as logging]
           [com.stuartsierra.component :as component]))

(defrecord Riemann [config client]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting the riemann client...")
    (assoc cmp :client (riemann/tcp-client (:riemann config))))
  (stop [cmp]
    (logging/info "Stopping the riemann client...")
    (assoc cmp :client nil)))

(defn new-riemann [config]
  (map->Riemann {:config config}))

(defn send-event [riemann m]
  (let [cl (:client riemann)
        ev (assoc m :service "shale")]
    (logging/debug (str "Sended to riemann: " ev))
    @(riemann/send-event cl ev)))

