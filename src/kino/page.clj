(ns kino.page
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [hiccup2.core :as h])
  (:import [java.net URLEncoder]
           [java.time LocalDate LocalDateTime ZoneId]))

(def css "
@font-face { font-family: 'Fira Sans'; font-weight: 400; font-display: swap; src: url(/fonts/FiraSans-Regular.woff2) format('woff2'); }
@font-face { font-family: 'Fira Sans'; font-weight: 600; font-display: swap; src: url(/fonts/FiraSans-SemiBold.woff2) format('woff2'); }
:root {
  --bg: #FFF; --surface: #F5F5F5; --text: #15191D; --muted: #767676; --past: #A0A0A0;
  --link: #0060A0; --hover: #D43B16; --hover-bg: #FEE1E4; --border: #DDD; --badge: #FFF;
  --font: 'Fira Sans', system-ui, -apple-system, 'Segoe UI', sans-serif;
}
@media (prefers-color-scheme: dark) { :root {
  --bg: #171B20; --surface: #232930; --text: #E1E6EC; --muted: #A4AEBB; --past: #6E7884;
  --link: #80C5F7; --hover: #FF9975; --hover-bg: #48302E; --border: #3B444F; --badge: #2C343E; color-scheme: dark } }

* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { height: 100%; }
body { background: var(--bg); color: var(--text); font: 16px/19px var(--font); padding-inline: 16px; display: flex; flex-direction: column; }
a, .t { color: var(--link); text-decoration: none; cursor: pointer; }
a:hover { color: var(--hover); text-decoration: underline; }

.scroll { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: none; }

.grid { display: grid; grid-template-columns: 135px 310px repeat(7, 200px); width: max-content; cursor: default; }
.grid > * { padding: 5px; }

.filters { grid-column: 1 / 3; position: sticky; left: 0; top: 0; z-index: 60; background: var(--surface);
  border: 1px solid var(--border); display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; }
.chip { display: inline-flex; gap: 6px; align-items: center; background: var(--badge); border: 1px solid var(--border); padding: 1px 7px; border-radius: 3px; font-size: 14px; }
.chip .x { color: var(--muted); }
.none { grid-column: 1 / 3; }

.day { position: sticky; top: 0; z-index: 20; background: var(--surface); text-align: center; line-height: 20px; font-size: 15px;
  border-top: 1px solid var(--border); border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.day.today { font-weight: 600; }

.mp { grid-column: 1; border-left: 1px solid var(--border); border-bottom: 1px solid var(--border); background: var(--bg); }
.poster { position: sticky; top: 36px; width: 125px; height: 177px; border-radius: 2px; color: #fff; display: flex; align-items: flex-end;
  padding: 8px; font-size: 13px; line-height: 15px; font-weight: 600; text-wrap: balance; }
img.poster { padding: 0; object-fit: cover; }

.mt { grid-column: 2 / span 2; position: sticky; left: 0; z-index: 10; background: var(--surface);
  border-left: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.mt h2 { font-size: 19px; line-height: 22px; font-weight: 600; margin-bottom: 6px; }
.mi, .md { font-size: 14px; line-height: 18px; }
.md { color: var(--muted); margin-top: 2px; }
.e { grid-column: span 6; background: var(--surface); border-bottom: 1px solid var(--border); border-right: 1px solid var(--border); }
.age { display: inline-block; border: 1px solid currentColor; border-radius: 3px; padding: 0 4px; font-size: 12px; line-height: 15px; }

.c { grid-column: 2; position: sticky; left: 0; z-index: 20; background: var(--bg); min-height: 55px;
  border-left: 1px solid var(--border); border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.c .addr { color: var(--muted); font-size: 14px; }
.c .addr a { color: inherit; text-decoration: underline; }
.v { margin-left: 6px; font-size: 11px; line-height: 14px; padding: 0 4px; border-radius: 3px; background: var(--surface); border: 1px solid var(--border); color: var(--muted); vertical-align: 1px; font-weight: 600; letter-spacing: .03em; }
.v.vp { color: var(--hover); border-color: currentColor; }

.s { position: relative; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.s > a { position: absolute; padding: 5px; line-height: 10px; font-size: 14px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.s > .t:hover { background: var(--hover-bg); color: var(--hover); text-decoration: none; }
.s > .past { color: var(--past); }
")

(def zone (ZoneId/of "Europe/Lisbon"))

(def weekdays ["seg." "ter." "qua." "qui." "sex." "sáb." "dom."])
(def months ["jan." "fev." "mar." "abr." "mai." "jun." "jul." "ago." "set." "out." "nov." "dez."])

(defn day-label [^LocalDate date]
  (str (weekdays (dec (.getValue (.getDayOfWeek date)))) " "
       (.getDayOfMonth date) " "
       (months (dec (.getMonthValue date)))))

(defn duration [minutes]
  (str (quot minutes 60) "h " (mod minutes 60) "min"))

(defn minutes [^LocalDateTime t]
  (+ (* 60 (.getHour t)) (.getMinute t)))

;; 10:00 is at the left edge, 24:00 is at the right edge. Earlier times (after midnight) stay at the left edge.
(defn x [m]
  (max 4 (Math/round (+ 4 (/ (* (- m 600) 152) 840.0)))))

(defn cell
  "One day cell. Overlapping times go into the next lane."
  [shows now]
  (let [[lanes spans]
        (reduce
          (fn [[lanes spans] {t :show/time url :show/url}]
            (let [left (x (minutes t))
                  lane (or (first (keep-indexed #(when (<= (+ %2 2) left) %1) lanes))
                           (count lanes))]
              [(assoc lanes lane (+ left 44))
               (conj spans [:a {:href url
                                :class (if (.isBefore ^LocalDateTime t now) "past" "t")
                                :style (str "left:" left "px;top:" (+ 6 (* lane 22)) "px")}
                            (format "%02d:%02d" (.getHour t) (.getMinute t))])]))
          [[] []]
          (sort-by :show/time shows))]
    (into [:div.s {:style (str "height:" (max 55 (+ 12 (* 22 (count lanes)))) "px")}] spans)))

(def badges
  {:vp [:span.v.vp {:title "Versão portuguesa (dobrado)"} "VP"]
   :vo [:span.v {:title "Versão original, legendada"} "VO"]
   :pt [:span.v {:title "Falado em português, sem legendas"} "sem legendas"]})

;; Without a poster file: a box with a hue from the movie id.
(defn poster [movie]
  (if-let [file (:movie/poster movie)]
    [:img.poster {:src (str "/posters/" file) :alt (:movie/title movie) :loading "lazy"}]
    (let [hue (mod (hash (:movie/id movie)) 360)]
      [:div.poster {:style (str "background:linear-gradient(30deg,hsl(" hue " 45% 22%),hsl(" (+ hue 25) " 40% 48%))")}
       (:movie/title movie)])))

(def filters
  "URL parameter → the value of a show. The order is the order of the chips and the URL."
  (array-map
    :cidade (comp :cinema/city :show/cinema)
    :cinema (comp :cinema/name :show/cinema)
    :filme  (comp :movie/title :show/movie)))

(defn href [params]
  (if (empty? params)
    "/"
    (str "/?" (str/join "&" (for [k (keys filters) :let [v (params k)] :when v]
                              (str (name k) "=" (URLEncoder/encode ^String v "UTF-8")))))))

(defn movie-rows [movie shows days now params]
  (let [rows (->> (group-by (juxt :show/cinema :show/version) shows)
                  (sort-by (fn [[[cinema version] _]] [(:cinema/name cinema) (name (or version :_))])))]
    (list*
      [:div.mp {:style (str "grid-row:span " (inc (count rows)))} (poster movie)]
      [:div.mt
       [:h2 [:a {:href (href (assoc params :filme (:movie/title movie)))} (:movie/title movie)]]
       [:div.mi
        (interpose " • "
          (concat (sort (:movie/genres movie))
                  (keep identity
                    [(:movie/year movie)
                     (some-> (:movie/duration movie) duration)
                     (some->> (:movie/age movie) (vector :span.age))])))]
       [:div.md (:movie/synopsis movie)]]
      [:div.e]
      (for [[[cinema version] shows] rows
            :let [by-day (group-by #(.toLocalDate ^LocalDateTime (:show/time %)) shows)]
            el (cons [:div.c
                      [:a {:href (href (assoc params :cinema (:cinema/name cinema)))} (:cinema/name cinema)] (badges version)
                      [:div.addr
                       (interpose " • "
                         (keep identity
                           [(:cinema/address cinema)
                            (some->> (:cinema/city cinema) (vector :a {:href (href (assoc params :cidade (:cinema/city cinema)))}))
                            (:cinema/district cinema)]))]]
                     (for [day days]
                       (cell (by-day day) now)))]
        el))))

(defn render
  "params: the filters from the URL, for example {:cidade \"Porto\"}. A show must match all of them."
  [db params]
  (let [now   (LocalDateTime/now zone)
        today (.toLocalDate now)
        days  (map #(.plusDays today %) (range 7))
        end   (.atStartOfDay (.plusDays today 7))
        shows (->> (d/q '[:find [(pull ?s [:show/time :show/url :show/version {:show/cinema [*]} {:show/movie [*]}]) ...]
                          :where [?s :show/time]]
                        db)
                   (filter #(and (not (.isBefore ^LocalDateTime (:show/time %) (.atStartOfDay today)))
                                 (.isBefore ^LocalDateTime (:show/time %) end)))
                   (filter (fn [s] (every? (fn [[k v]] (= v ((filters k) s))) params))))
        movies (->> (group-by :show/movie shows)
                    (sort-by (fn [[movie shows]] [(- (count shows)) (:movie/title movie)])))]
    (str
      "<!DOCTYPE html>"
      (h/html
        [:html {:lang "pt"}
         [:head
          [:meta {:charset "utf-8"}]
          [:title (str/join " — " (concat (keep params (keys filters)) ["Todos os Cinemas"]))]
          [:style (h/raw css)]]
         [:body
          [:div.scroll
           [:div.grid
            [:div.filters
             [:a {:href "/" :title "Início"} "⌂"]
             (for [k (keys filters) :let [v (params k)] :when v]
               [:span.chip v [:a.x {:href (href (dissoc params k)) :title "Remover filtro"} "×"]])]
            (for [[i day] (map-indexed vector days)]
              (if (zero? i)
                [:div.day.today (str "Hoje, " (day-label day))]
                [:div.day (day-label day)]))
            (if (empty? movies)
              [:div.none "Nenhuma sessão encontrada. " [:a {:href "/"} "Ver todas as sessões"]]
              (for [[movie shows] movies]
                (movie-rows movie shows days now params)))]]]]))))
