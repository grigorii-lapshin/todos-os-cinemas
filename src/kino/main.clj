(ns kino.main
  (:require [kino.core :as core]
            [nrepl.server :as nrepl]))

(defn -main []
  (nrepl/start-server :bind "127.0.0.1" :port 7888)
  (core/start))
