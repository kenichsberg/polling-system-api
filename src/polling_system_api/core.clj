(ns polling-system-api.core
  (:gen-class)
  (:require [polling-system-api.api.routes :refer [router]]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server)))

(defonce ^:private server-ref (atom nil))

(defn app []
  (ring/ring-handler
    (router)
    #_(ring/routes swagger (ring/create-default-handler))))


(defn stop []
  (when @server-ref
    (.stop ^Server @server-ref)
    (reset! server-ref nil)
    (println "Server has stopped.")))


(defn start [port]
  (stop)

  (let [server (jetty/run-jetty (app) 
                                {:port (or port 0)
                                 :join? false})
        port-actual (-> server .getConnectors first .getLocalPort)]
    (reset! server-ref server)
    (println (format "Server has started on %s." port-actual))))


(defn -main
  ([]
   (start nil))

  ([port]
   (start (Integer/parseInt port))))


(comment
  (start 8080)
  (stop)
  )

