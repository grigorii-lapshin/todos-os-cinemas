(ns kino.core
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datascript.core :as d]
            [kino.page :as page]
            [kino.sample :as sample]
            [org.httpkit.client :as client]
            [org.httpkit.server :as http])
  (:import [java.net URLDecoder]
           [java.nio.file Files StandardCopyOption]
           [java.text Normalizer Normalizer$Form]
           [java.time Instant LocalDateTime]
           [java.util.concurrent Executors TimeUnit]))

(def schema
  {:cinema/id    {:db/unique :db.unique/identity}
   :movie/id     {:db/unique :db.unique/identity}
   :movie/genres {:db/cardinality :db.cardinality/many}
   :show/cinema  {:db/valueType :db.type/ref}
   :show/movie   {:db/valueType :db.type/ref}})

;; The private repository has kino.sources with the real scrapers. The public repository does not,
;; so it uses the sample data.
(def scrapers
  (or (some-> (try (requiring-resolve 'kino.sources/scrapers)
                   (catch java.io.FileNotFoundException _ nil))
              deref)
      {:sample #'sample/scrape}))

(def db-file (io/file "data/db.json"))

(defonce *db (atom (d/empty-db schema)))

;; DataScript writes non-JSON values with freeze-fn. A LocalDateTime becomes a tagged EDN string.
(defn freeze [v]
  (if (instance? LocalDateTime v)
    (str "#time/ldt \"" v "\"")
    (pr-str v)))

(defn thaw [s]
  (edn/read-string {:readers {'time/ldt #(LocalDateTime/parse %)}} s))

(defn save! [db]
  (let [tmp (io/file "data/db.json.tmp")]
    (io/make-parents tmp)
    (spit tmp (json/write-str (d/serializable db {:freeze-fn freeze})))
    (Files/move (.toPath tmp) (.toPath db-file)
      (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn load-db []
  (d/from-serializable (json/read-str (slurp db-file)) {:thaw-fn thaw}))

;; A source can give "QUEDA DA BALEIA, A". The key puts the article at the start.
(defn movie-key [title]
  (let [[_ head article] (re-matches #"(?i)(.*),\s*(a|o|as|os|the)" (str/trim title))]
    (-> (Normalizer/normalize (str/lower-case (if article (str article " " head) title)) Normalizer$Form/NFD)
        (str/replace #"\p{M}" "")
        (str/replace #"[^\p{L}\p{N}]+" " ")
        str/trim)))

;; The scrapers give the id "<source>/<id>". The same film from two sources gets one id here.
(defn merge-movies [tx]
  ;; Without overrides.edn, overrides is nil and each key stays the same.
  (let [overrides (some-> (io/resource "overrides.edn") slurp edn/read-string)
        ids       (into {} (for [e tx :when (:movie/title e)]
                             (let [k (movie-key (:movie/title e))]
                               [(:movie/id e) (get overrides k k)])))
        id        #(ids % %)]
    (for [e tx]
      (cond
        (:movie/id e)   (update e :movie/id id)
        (:show/movie e) (update-in e [:show/movie 1] id)
        :else           e))))

(def posters-dir (io/file "data/posters"))

(def poster-name #"[a-z0-9-]+\.jpg")

;; "a queda da baleia" → "a-queda-da-baleia.jpg". A title without Latin letters or digits gets no file.
(defn poster-file [id]
  (some-> (str/replace id #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "") not-empty (str ".jpg")))

(defn download! [url file]
  (try
    (let [{:keys [status body error]} @(client/get url {:as :byte-array :timeout 30000})]
      (when error (throw error))
      (when (not= 200 status) (throw (ex-info (str "HTTP " status) {})))
      (io/make-parents file)
      (io/copy body file)
      true)
    (catch Exception e
      (println (str (Instant/now)) "poster" url "failed:" (ex-message e))
      false)))

;; ponytail: the original images, never resized and never deleted. Some sources give 1 MB posters.
;; Resize on download if page weight matters, delete files of old films if the disk fills.
(defn add-posters
  "Downloads the missing posters one after the other. Adds :movie/poster for each film with a file."
  [tx]
  (let [urls (into {} (for [e tx :when (:movie/poster-url e)] [(:movie/id e) (:movie/poster-url e)]))]
    (concat tx
      (for [[id url] urls
            :let  [name (poster-file id)
                   file (some->> name (io/file posters-dir))]
            :when (and file (or (.exists file) (download! url file)))]
        {:movie/id id :movie/poster name}))))

(defn old-shows
  "The shows of a source in db, as transaction data. The films and cinemas come first, for the lookup refs."
  [db source]
  (let [shows (for [s (d/q '[:find [?s ...] :in $ ?src :where [?s :show/source ?src]] db source)]
                (d/pull db '[* {:show/movie [*]} {:show/cinema [*]}] s))]
    (concat
      (distinct (for [s shows, k [:show/movie :show/cinema]] (dissoc (k s) :db/id)))
      (for [s shows]
        (-> (dissoc s :db/id)
            (update :show/movie #(vector :movie/id (:movie/id %)))
            (update :show/cinema #(vector :cinema/id (:cinema/id %))))))))

(defn refresh! []
  (let [results (into {} (for [[source scrape] scrapers]
                           [source (try
                                     (mapv #(cond-> % (:show/movie %) (assoc :show/source source)) (scrape))
                                     (catch Exception e
                                       (println (str (Instant/now)) source "failed:" (ex-message e))
                                       nil))]))]
    ;; Without any result (for example, no network) the old database is better than an empty one.
    (if (every? nil? (vals results))
      @*db
      ;; A failed source keeps its shows from the last database. merge-movies runs after, so they get the new film ids.
      (let [tx (mapcat (fn [[source tx]] (or tx (old-shows @*db source))) results)
            db (d/db-with (d/empty-db schema) (add-posters (merge-movies tx)))]
        (reset! *db db)
        (save! db)
        db))))

(defonce *server (atom nil))

(defonce *schedule (atom nil))

(def period-minutes (* 6 60))

;; ponytail: whitelist of static files, add a static handler when there are more
(def static
  {"/fonts/FiraSans-Regular.woff2"  "font/woff2"
   "/fonts/FiraSans-SemiBold.woff2" "font/woff2"})

;; "cidade=Porto&x=1" → {:cidade "Porto"}. Unknown, empty, and badly encoded parameters are ignored.
(defn query-params [s]
  (into {} (for [pair  (str/split (or s "") #"&")
                 :let  [[k v] (str/split pair #"=" 2)
                        v (try (some-> v (URLDecoder/decode "UTF-8") not-empty)
                               (catch IllegalArgumentException _ nil))]
                 :when (and v (page/filters (keyword k)))]
             [(keyword k) v])))

(defn handler [{:keys [uri query-string]}]
  (let [poster (some->> (re-find #"^/posters/(.*)$" uri) second (re-matches poster-name) (io/file posters-dir))]
    (cond
      (= uri "/")
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (page/render @*db (query-params query-string))}

      (static uri)
      {:status 200
       :headers {"Content-Type" (static uri) "Cache-Control" "max-age=31536000"}
       :body (io/input-stream (io/resource (str "public" uri)))}

      (some-> poster .exists)
      {:status 200
       :headers {"Content-Type" "image/jpeg" "Cache-Control" "max-age=86400"}
       :body poster}

      :else
      {:status 404 :body "Not found"})))

(defn start []
  (when (.exists db-file)
    (reset! *db (load-db)))
  (reset! *server (http/run-server #'handler {:ip (or (System/getenv "KINO_IP") "127.0.0.1") :port 1895}))
  ;; An exception stops a scheduled task forever, so catch everything.
  (reset! *schedule
    (doto (Executors/newScheduledThreadPool 1)
      (.scheduleAtFixedRate
        #(try (refresh!) (catch Throwable e (println (str (Instant/now)) "refresh failed:" (ex-message e))))
        0 period-minutes TimeUnit/MINUTES)))
  (println "http://localhost:1895"))

(defn stop []
  (when-let [s @*schedule]
    (.shutdownNow s)
    (reset! *schedule nil))
  (when-let [s @*server]
    (s)
    (reset! *server nil)))
