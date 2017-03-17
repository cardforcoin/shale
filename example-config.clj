;; shale service configuration. Rename to 'config" and place somewhere on the
;; class path (eg, resources/)

;; either provide a fn map suitable to implement shale.nodes/INodePool to inject
;; custom node management
{:node-pool-impl {:get-nodes (fn [this requirements]
                              (prn "custom node pool!")
                              "http://localhost:5555/wd/hub")
                  :add-node (fn [this requirements] nil)
                  :remove-node (fn [this url] nil)
                  :can-add-node (fn [this] false)
                  :can-remove-node (fn [this] false)}
 ;; or, configure it to use a cloud provider- AWS is currently supported.
 ;; this option uses https://github.com/mcohen01/amazonica#ec2, so make sure
 ;; you've supplied AWS credentials in your environment variables or elsewhere
 ;; (https://github.com/mcohen01/amazonica#authentication)
 :node-pool-cloud-config {:provider :aws
                          :ami "ami-12345678"
                          :tags {:service :shale}
                          :use-private-dns true}

 :node-pool-cloud-config {:provider :kube
                          :api-url "http://localhost:8001"
                          :api-url :kubernetes-service ;; or if running shale inside kube, use the internal api
                          :kube/label :label {:app :selenium} ;; add all containers w/ metadata label :app :selenium
                          :kube/port-name "selenium" ;; connect
                          }

 ;; if you just need a static list of nodes, provide that instead
 :node-list ["http://localhost:5555/wd/hub" "http://anotherhost:5555/wd/hub"]
 ;; the default max number of sessions to create per node.
 :node-max-sessions 3
 ;; the number of milliseconds between each node refresh
 :node-refresh-delay 1000
 ;; the number of milliseconds between each session refresh
 :session-refresh-delay 200
 ;; any initial proxies that should be added on startup. if they're already
 ;; in redis, they won't be modified
 :proxy-list [{:public-ip "8.8.8.8",
               :host "127.0.0.1",
               :port 1234,
               :type :socks5
               :shared true
               :tags #{"test-tag"}}]
 ;; optionally provide redis connection details suitable for use by carmine
 ;; eg http://ptaoussanis.github.io/carmine/taoensso.carmine.html#var-wcar
 ;; if not provided, the defaults will be used

 ;; host can also be a namespaced keyword starting with :env, such as
 ;; :env/redis-service-host, will use the value of that env var
 :redis {:host "localhost"
         :port 6379
         :db 0}

 ;; optionally provide logging config to use with timbre
 ;; the provided config is merged with the default config using `merge-config!`
 ;; details can be found at https://github.com/ptaoussanis/timbre#configuration
 :logging {:level :info}
 ;; port for the in-process nrepl server
 ;; make sure it's blocked if your machine is open to
 :nrepl-port 5001
 ;; webdriver timeout, in milliseconds. this prevents shale from hanging when
 ;; a node has a memory or network issue. if not set, defaults to 1000. 0 means
 ;; block forever.
 :webdriver-timeout 1000
 ;; timeout starting a new webdriver, in milliseconds. typically this should
 ;; be high if you're using slow machines or a browser with a longer startup
 ;; time, like chrome. defaults to 5000. 0 means block forever.
 :start-webdriver-timeout 5000


 ;; options for various webdriver browsers.

 :webdriver {:chrome #{;; :no-sandbox
                       }  ;; - use no-sandbox when running shale inside docker or kubernetes


             }
 ;; options for riemann client
 :riemann {:host "localhost"
           :port 6666}
 }
