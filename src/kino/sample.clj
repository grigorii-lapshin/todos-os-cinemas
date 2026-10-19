(ns kino.sample
  "A source with sample data. The public repository uses it, because it has no real scrapers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kino.page :as page])
  (:import [java.time LocalDate LocalTime]))

;; A show is [cinema "hh:mm hh:mm" {:days [offsets from today] :version :vo}]. Without :days, the show is on each of the 7 days.
(defn scrape []
  (let [{:keys [cinemas movies]} (edn/read-string (slurp (io/resource "sample.edn")))
        today (LocalDate/now page/zone)]
    (concat cinemas
      (for [movie movies
            tx    (cons (dissoc movie :shows)
                    (for [[cinema times {:keys [days version]}] (:shows movie)
                          day  (or days (range 7))
                          time (str/split times #" ")]
                      (cond-> {:show/movie  [:movie/id (:movie/id movie)]
                               :show/cinema [:cinema/id cinema]
                               :show/time   (.atTime (.plusDays today day) (LocalTime/parse time))}
                        version (assoc :show/version version))))]
        tx))))
