"""Backend generico per la tua libreria video.

Risponde con le stesse API che VOD si aspetta:
    GET /api/search?q=...        elenco titoli {name, slug, image, type}
    GET /api/load?slug=...       dettagli, tmdb_id, stagioni ed episodi
    GET /api/get_links?tmdb_id=...&season=..&episode=..   link da riprodurre
    GET /media/<percorso>        i file della cartella, con supporto Range (necessario per i video)

Da dove prende i titoli:
  1) i file nella cartella della libreria (predefinita: ~/Movies/VOD)
       Titolo (2010).mp4                         film
       Titolo (2010) [tmdb-603]/film.mp4          film in una cartella, con id TMDB e poster.jpg
       Serie [tmdb-1399]/S01E01 Titolo.mp4        serie: episodi riconosciuti da SxxEyy o 1x01
       Serie/Stagione 2/Episodio 3.mp4            serie: sottocartelle "Stagione N" / "Season N"
  2) il file catalog.json nella stessa cartella, per link a flussi (.m3u8 o .mp4) che hai il diritto di guardare.

Formati riproducibili su Mac: .mp4, .m4v, .mov e playlist .m3u8 (non .mkv o .avi).

Uso da solo (anche su un altro computer):
    python3 library_backend.py --folder ~/Movies/VOD --port 8765 [--host 0.0.0.0]
"""
import argparse
import json
import mimetypes
import os
import re
import threading
import time
import unicodedata
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VIDEO_EXT = (".mp4", ".m4v", ".mov", ".m3u8")
IMAGE_NAMES = ("poster", "cover", "folder", "locandina")
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp")
TMDB_TAG = re.compile(r"\s*[\[\{](?:tmdb|tmdbid)[-= ]?(\d+)[\]\}]", re.I)
YEAR = re.compile(r"\s*[\(\[](\d{4})[\)\]]")
SXXEYY = re.compile(r"[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})|(\d{1,2})x(\d{2,3})")
SEASON_DIR = re.compile(r"(?i)^(?:stagione|season|serie|s)\s*(\d{1,2})$")
EP_NUM = re.compile(r"(?i)(?:episodio|episode|ep\.?|e)\s*(\d{1,3})|^(\d{1,3})\b")

README = """VOD — la tua libreria
=====================

Metti qui i tuoi video: VOD li mostra in Film e Serie TV.

FILM
  Titolo (2010).mp4
  oppure una cartella:  Titolo (2010) [tmdb-603]/film.mp4  (+ poster.jpg facoltativo)

SERIE TV
  Nome serie [tmdb-1399]/S01E01 Titolo episodio.mp4
  oppure:              Nome serie/Stagione 1/Episodio 1.mp4

  [tmdb-NUMERO] è facoltativo: è il numero del titolo su themoviedb.org
  (nell'indirizzo della pagina). Con quello VOD scarica locandina, trama ed episodi in italiano.

FORMATI
  .mp4  .m4v  .mov  .m3u8   (i file .mkv e .avi il Mac non li riproduce: convertili in .mp4)

LINK A FLUSSI
  Per flussi online che hai il diritto di guardare, crea qui un file catalog.json, ad esempio:

  [
    {"title": "Il mio film", "type": "movie", "year": "2024", "tmdb_id": "603",
     "url": "https://esempio.it/video/master.m3u8"},
    {"title": "La mia serie", "type": "series", "tmdb_id": "1399",
     "seasons": [{"number": 1, "episodes": [
        {"number": 1, "title": "Primo", "url": "https://esempio.it/s1e1.m3u8"}
     ]}]}
  ]
"""


def slugify(text):
    t = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", t).strip("-") or "titolo"


def clean_title(name):
    """'Titolo (2010) [tmdb-603]' -> ('Titolo', '2010', '603')"""
    tmdb = TMDB_TAG.search(name)
    year = YEAR.search(name)
    t = TMDB_TAG.sub("", name)
    t = YEAR.sub("", t)
    t = re.sub(r"[._]+", " ", t).strip(" -")
    return t or name, year.group(1) if year else None, tmdb.group(1) if tmdb else None


class Library:
    def __init__(self, folder):
        self.folder = os.path.abspath(os.path.expanduser(folder))
        self.items = []
        self.by_slug = {}
        self.lock = threading.Lock()
        self.scanned = 0.0
        self.ensure_folder()

    def ensure_folder(self):
        os.makedirs(self.folder, exist_ok=True)
        readme = os.path.join(self.folder, "LEGGIMI.txt")
        if not os.path.exists(readme):
            with open(readme, "w", encoding="utf-8") as f:
                f.write(README)

    # ---------- scansione ----------

    def refresh(self, max_age=15):
        with self.lock:
            if time.time() - self.scanned < max_age:
                return
            items = self._scan_folder() + self._scan_catalog()
            seen = {}
            for it in items:
                base = it["slug"]
                n = seen.get(base, 0)
                if n:
                    it["slug"] = f"{base}-{n + 1}"
                seen[base] = n + 1
                if not it.get("tmdb_id"):
                    it["tmdb_id"] = "lib-" + it["slug"]
            self.items = sorted(items, key=lambda x: x["title"].lower())
            self.by_slug = {it["slug"]: it for it in self.items}
            self.scanned = time.time()

    def _rel(self, path):
        return os.path.relpath(path, self.folder).replace(os.sep, "/")

    def _poster(self, folder, stem=None):
        try:
            names = os.listdir(folder)
        except OSError:
            return None
        for n in sorted(names):
            low = n.lower()
            base, ext = os.path.splitext(low)
            if ext in IMAGE_EXT and (base in IMAGE_NAMES or (stem and base == stem.lower())):
                return self._rel(os.path.join(folder, n))
        return None

    def _scan_folder(self):
        out = []
        try:
            entries = sorted(os.listdir(self.folder))
        except OSError:
            return out
        for name in entries:
            if name.startswith("."):
                continue
            path = os.path.join(self.folder, name)
            if os.path.isfile(path) and name.lower().endswith(VIDEO_EXT):
                stem = os.path.splitext(name)[0]
                title, year, tmdb = clean_title(stem)
                out.append({"slug": slugify(title + (f"-{year}" if year else "")), "title": title, "year": year,
                            "tmdb_id": tmdb, "type": "movie", "image": self._poster(self.folder, stem),
                            "plot": None, "url": self._rel(path)})
            elif os.path.isdir(path):
                item = self._scan_dir(path, name)
                if item:
                    out.append(item)
        return out

    def _scan_dir(self, path, name):
        title, year, tmdb = clean_title(name)
        videos, episodes = [], {}
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            season_from_dir = None
            m = SEASON_DIR.match(os.path.basename(root).strip())
            if root != path and m:
                season_from_dir = int(m.group(1))
            for f in sorted(files):
                if not f.lower().endswith(VIDEO_EXT) or f.startswith("."):
                    continue
                full = os.path.join(root, f)
                stem = os.path.splitext(f)[0]
                se = SXXEYY.search(stem)
                if se:
                    s = int(se.group(1) or se.group(3))
                    e = int(se.group(2) or se.group(4))
                elif season_from_dir is not None:
                    em = EP_NUM.search(stem)
                    s, e = season_from_dir, int((em.group(1) or em.group(2))) if em else len(episodes.get(season_from_dir, {})) + 1
                else:
                    videos.append(full)
                    continue
                ep_title = SXXEYY.sub("", stem)
                ep_title = re.sub(r"[._]+", " ", ep_title).strip(" -") or f"Episodio {e}"
                if re.fullmatch(r"(?i)(episodio|episode|ep\.?)?\s*\d+", ep_title):
                    ep_title = f"Episodio {e}"
                episodes.setdefault(s, {})[e] = {"number": e, "title": ep_title, "url": self._rel(full)}
        image = self._poster(path)
        if episodes:
            seasons = [{"number": s, "episodes": [eps[k] for k in sorted(eps)]} for s, eps in sorted(episodes.items())]
            return {"slug": slugify(title), "title": title, "year": year, "tmdb_id": tmdb, "type": "series",
                    "image": image, "plot": None, "seasons": seasons}
        if videos:
            main = max(videos, key=lambda p: os.path.getsize(p) if os.path.exists(p) else 0)
            return {"slug": slugify(title + (f"-{year}" if year else "")), "title": title, "year": year,
                    "tmdb_id": tmdb, "type": "movie", "image": image, "plot": None, "url": self._rel(main)}
        return None

    def _scan_catalog(self):
        path = os.path.join(self.folder, "catalog.json")
        if not os.path.exists(path):
            return []
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return []
        out = []
        for o in data if isinstance(data, list) else []:
            if not isinstance(o, dict) or not o.get("title"):
                continue
            is_series = o.get("type") in ("series", "tv", "serie") or bool(o.get("seasons"))
            item = {"slug": slugify(str(o["title"])), "title": str(o["title"]), "year": o.get("year"),
                    "tmdb_id": str(o["tmdb_id"]) if o.get("tmdb_id") else None,
                    "type": "series" if is_series else "movie", "image": o.get("poster") or o.get("image"),
                    "plot": o.get("plot")}
            if is_series:
                item["seasons"] = [
                    {"number": int(s.get("number", i + 1)),
                     "episodes": [{"number": int(e.get("number", j + 1)), "title": e.get("title") or f"Episodio {j + 1}",
                                   "url": e.get("url")} for j, e in enumerate(s.get("episodes") or []) if e.get("url")]}
                    for i, s in enumerate(o.get("seasons") or [])]
            elif o.get("url"):
                item["url"] = o["url"]
            else:
                continue
            out.append(item)
        return out

    # ---------- API ----------

    def media_url(self, base, ref):
        if not ref:
            return None
        if ref.startswith(("http://", "https://")):
            return ref
        return f"{base}/media/{urllib.parse.quote(ref)}"

    def search(self, q, base):
        self.refresh()
        q = (q or "").strip().lower()
        hits = self.items if q in ("", "*") else [it for it in self.items if q in it["title"].lower()]
        return [{"name": it["title"], "slug": it["slug"], "image": self.media_url(base, it.get("image")),
                 "type": it["type"]} for it in hits]

    def load(self, slug, base):
        self.refresh()
        it = self.by_slug.get(slug)
        if not it:
            return None
        out = {"title": it["title"], "plot": it.get("plot"), "year": it.get("year"), "tmdb_id": it["tmdb_id"],
               "image": self.media_url(base, it.get("image")), "type": it["type"]}
        if it["type"] == "series":
            out["seasons"] = [{"number": s["number"], "episodes": [{"number": e["number"], "title": e["title"]}
                                                                    for e in s["episodes"]]} for s in it["seasons"]]
        return out

    def links(self, tmdb_id, season, episode, base):
        self.refresh()
        it = next((x for x in self.items if x["tmdb_id"] == str(tmdb_id)), None)
        if not it:
            return {}
        if it["type"] == "series":
            for s in it["seasons"]:
                if s["number"] == season:
                    for e in s["episodes"]:
                        if e["number"] == episode:
                            return {"Libreria": self.media_url(base, e["url"])}
            return {}
        return {"Libreria": self.media_url(base, it.get("url"))}

    def file_path(self, rel):
        path = os.path.abspath(os.path.join(self.folder, urllib.parse.unquote(rel)))
        if not path.startswith(self.folder + os.sep) or not os.path.isfile(path):
            return None
        return path

    def info(self):
        self.refresh(max_age=0)
        return {"folder": self.folder, "movies": sum(1 for x in self.items if x["type"] == "movie"),
                "series": sum(1 for x in self.items if x["type"] == "series")}


def make_handler(lib):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def _base(self):
            return f"http://{self.headers.get('Host') or '127.0.0.1'}"

        def _json(self, obj, status=200):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            base = self._base()
            if u.path == "/api/search":
                return self._json(lib.search(q.get("q"), base))
            if u.path == "/api/load":
                d = lib.load(q.get("slug", ""), base)
                return self._json(d) if d else self._json({"error": "Titolo non trovato"}, 404)
            if u.path == "/api/get_links":
                def num(k):
                    try:
                        return int(q[k])
                    except (KeyError, ValueError):
                        return None
                return self._json(lib.links(q.get("tmdb_id", ""), num("season"), num("episode"), base))
            if u.path.startswith("/media/"):
                return self._file(u.path[len("/media/"):])
            if u.path == "/":
                return self._json({"service": "VOD libreria", **lib.info()})
            self.send_error(404)

        def _file(self, rel):
            path = lib.file_path(rel)
            if not path:
                return self.send_error(404)
            size = os.path.getsize(path)
            ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
            if path.lower().endswith(".m3u8"):
                ctype = "application/vnd.apple.mpegurl"
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:  # ultimi N byte
                    start = max(0, size - int(m.group(2)))
                end = min(end, size - 1)
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            length = end - start + 1
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            self.end_headers()
            try:
                with open(path, "rb") as f:
                    f.seek(start)
                    left = length
                    while left > 0:
                        chunk = f.read(min(256 * 1024, left))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    return Handler


def start(folder, port=8765, host="127.0.0.1"):
    lib = Library(folder)
    httpd = ThreadingHTTPServer((host, port), make_handler(lib))
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, lib


def main():
    ap = argparse.ArgumentParser(description="Backend VOD per la tua libreria video")
    ap.add_argument("--folder", default="~/Movies/VOD")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="127.0.0.1", help="0.0.0.0 per renderlo raggiungibile da altri dispositivi di casa")
    a = ap.parse_args()
    lib = Library(a.folder)
    print(f"Libreria: {lib.folder} · http://{a.host}:{a.port}", flush=True)
    httpd = ThreadingHTTPServer((a.host, a.port), make_handler(lib))
    httpd.daemon_threads = True
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
