# Todos os Cinemas

All screenings in all cinemas of Lisbon and Porto on one page: https://kino.161-35-65-11.sslip.io

The idea and the design come from [allekinos.de](https://allekinos.de) by Nikita Prokopov (Tonsky): https://tonsky.me/blog/allekinos/

## Architecture

One Clojure process. A schedule runs the scrapers every 6 hours. Each run makes a new DataScript database and replaces the old one in one step. The database is also kept in `data/db.json`. The server makes HTML from the database in memory. There is no JavaScript.

## Run

Use the Clojure CLI. Run `clj -M:dev`, connect your editor to the nREPL, and call `(start)`. Then open http://localhost:1895. It shows sample data from `resources/sample.edn`.

## Scrapers

The scrapers of the live site are not public. A scraper is a function without arguments. It gives DataScript transaction data:

- cinema: `:cinema/id` (keyword), `:cinema/name`, `:cinema/address`, `:cinema/city`, optional `:cinema/district`
- film: `:movie/id` (`"<source>/<id>"`), `:movie/title`, optional `:movie/year`, `:movie/duration` (minutes), `:movie/age`, `:movie/synopsis`, `:movie/genres`, `:movie/poster-url`
- show: `:show/movie` (`[:movie/id id]`), `:show/cinema` (`[:cinema/id id]`), `:show/time` (`LocalDateTime`, Lisbon), optional `:show/url`, `:show/version` (`:vo`, `:vp`, `:pt`)

Put your scrapers in a namespace `kino.sources`, as a map `scrapers` (`{:my-source #'my.scraper/scrape}`). Without it, the application uses `kino.sample`. It is also an example scraper.

## License

MIT. The Fira Sans font: SIL Open Font License, in `resources/public/fonts/OFL.txt`.
