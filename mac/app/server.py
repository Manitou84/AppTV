"""Server locale (127.0.0.1): serve l'interfaccia, le API JSON per l'interfaccia e il proxy HLS."""
import json
import mimetypes
import os
import threading
import time
import traceback
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import db as dbm
import hls_proxy
import net
import rsi
import tmdb
import updater
from version import VERSION
from vod_api import VodClient, kid_safe


class UserError(Exception):
    """Errore da mostrare all'utente così com'è."""


def _friendly(e):
    if isinstance(e, UserError):
        return str(e)
    if isinstance(e, net.HttpError):
        if e.status == 404:
            return "Contenuto non trovato (404)."
        if 500 <= e.status < 600:
            return f"Errore del server ({e.status}). Riprova più tardi."
        if e.status in (401, 403):
            return f"Accesso negato dal server (HTTP {e.status})."
        return str(e)
    return f"Errore imprevisto: {e}"


def fmt_dur(ms):
    s = max(int(ms // 1000), 0)
    h, m, s = s // 3600, (s % 3600) // 60, s % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def ep_key(slug, season, episode):
    return f"vod:{slug}:s{season}:e{episode}"


def movie_key(slug):
    return f"vod:{slug}"


class VodApp:
    def __init__(self, db, web_dir, token):
        self.db = db
        self.web_dir = web_dir
        self.token = token
        self.sessions = {}          # token proxy -> contesto della riproduzione
        self.details = {}           # (profilo, slug) -> (istante, dettaglio)
        self.pool = ThreadPoolExecutor(max_workers=4)
        self.library = None         # {"url", "lib"} impostato da main.py

    # ---------- profilo attivo ----------

    def active(self):
        return self.db.active_profile()

    def require_adult(self):
        if self.active()["isKid"]:
            raise UserError("Operazione non consentita nel profilo bambini.")

    def state(self):
        p = self.active()
        return {"version": VERSION, "profiles": self.db.profiles(), "activeId": p["id"], "hasPin": self.db.has_pin()}

    # ---------- titoli ----------

    def detail(self, slug, enrich=True, profile=None):
        p = profile or self.active()
        ck = (p["id"], slug, enrich)
        hit = self.details.get(ck)
        if hit and time.time() - hit[0] < 600:
            return hit[1]
        d = VodClient(p).load(slug)
        if enrich and p["tmdbKey"]:
            d = tmdb.enrich(d, p["tmdbKey"])
        self.db.remember(slug, d["type"], kid_safe(d) if d.get("tmdbEnriched") else None)
        self.details[ck] = (time.time(), d)
        return d

    def search(self, q):
        p = self.active()
        results = VodClient(p).search(q)
        known = self.db.known([r["slug"] for r in results])
        for r in results:
            k = known.get(r["slug"])
            if r["type"] == "unknown" and k:
                r["type"] = k["type"]
            elif r["type"] != "unknown":
                self.db.remember(r["slug"], r["type"])
            r["kidSafe"] = None if not k or k["kid_safe"] is None else bool(k["kid_safe"])
        return {"results": results, "kid": p["isKid"], "kidNeedsTmdb": p["isKid"] and not p["tmdbKey"]}

    def inspect(self, slugs):
        """Classifica film/serie e, per i bambini, verifica con TMDB. Massimo 4 in parallelo."""
        p = self.active()
        kid = p["isKid"]

        def one(slug):
            try:
                d = self.detail(slug, enrich=kid, profile=p)
                return slug, {"type": d["type"], "kidSafe": kid_safe(d) if kid else None}
            except Exception:
                return slug, {"type": "unknown", "kidSafe": False if kid else None}

        return dict(self.pool.map(one, slugs[:40]))

    def detail_view(self, slug):
        p = self.active()
        d = self.detail(slug)
        progress = self.db.progress_for_owner(p["id"], slug)
        return {
            "detail": d,
            "isFavorite": self.db.is_favorite(p["id"], slug),
            "progress": progress,
            "resume": self.resume(d, progress),
            "kidBlocked": p["isKid"] and not kid_safe(d),
        }

    @staticmethod
    def resume(d, progress):
        """Cosa fa il pulsante principale: quale episodio e da che punto."""
        if d["type"] != "series":
            pr = progress.get(movie_key(d["slug"]))
            if pr and not pr["watched"] and pr["positionMs"] >= dbm.MIN_RESUME_MS:
                return {"season": None, "episode": None, "positionMs": pr["positionMs"], "label": f"Riprendi da {fmt_dur(pr['positionMs'])}"}
            return {"season": None, "episode": None, "positionMs": 0, "label": "Riguarda" if pr and pr["watched"] else "Riproduci"}
        plan = [(s["number"], e["number"]) for s in d["seasons"] for e in s["episodes"]]
        if not plan:
            return None
        entries = [p for p in progress.values() if p["season"] is not None and p["episode"] is not None]
        partial = [p for p in entries if not p["watched"] and p["positionMs"] >= dbm.MIN_RESUME_MS]
        if partial:
            p = max(partial, key=lambda x: x["updatedAt"])
            return {"season": p["season"], "episode": p["episode"], "positionMs": p["positionMs"],
                    "label": f"Riprendi S{p['season']} · E{p['episode']} da {fmt_dur(p['positionMs'])}"}
        watched = {(p["season"], p["episode"]) for p in entries if p["watched"]}
        done = [p for p in entries if p["watched"]]
        if done:
            last = max(done, key=lambda x: x["updatedAt"])
            if (last["season"], last["episode"]) in plan:
                after = plan[plan.index((last["season"], last["episode"])) + 1:]
                nxt = next((x for x in after if x not in watched), None)
                if nxt:
                    return {"season": nxt[0], "episode": nxt[1], "positionMs": 0, "label": f"Prossimo: S{nxt[0]} · E{nxt[1]}"}
        first = next((x for x in plan if x not in watched), None)
        if first:
            return {"season": first[0], "episode": first[1], "positionMs": 0, "label": f"Riproduci S{first[0]} · E{first[1]}"}
        return {"season": plan[0][0], "episode": plan[0][1], "positionMs": 0, "label": f"Riguarda S{plan[0][0]} · E{plan[0][1]}"}

    # ---------- riproduzione ----------

    def _ctx(self, d, season, episode):
        is_ep = d["type"] == "series" and season is not None and episode is not None
        name = None
        if is_ep:
            for s in d["seasons"]:
                if s["number"] == season:
                    name = next((e["name"] for e in s["episodes"] if e["number"] == episode), None)
        return {
            "key": ep_key(d["slug"], season, episode) if is_ep else movie_key(d["slug"]),
            "ownerId": d["slug"], "origin": "vod", "title": d["title"],
            "subtitle": (f"S{season} · E{episode}" + (f" · {name}" if name else "")) if is_ep else None,
            "image": d.get("image"), "type": d["type"], "season": season if is_ep else None,
            "episode": episode if is_ep else None, "slug": d["slug"], "tmdbId": d.get("tmdbId"), "isLive": False,
        }

    def play(self, slug, season, episode, resume_ms):
        p = self.active()
        d = self.detail(slug)
        if p["isKid"] and not kid_safe(d):
            raise UserError("Titolo non disponibile nel profilo bambini.")
        if not d.get("tmdbId"):
            raise UserError("Questo titolo non ha un tmdb_id: impossibile recuperare i flussi.")
        client = VodClient(p)
        links = client.get_links(d["tmdbId"], season, episode)
        ctx = self._ctx(d, season, episode)
        if resume_ms is None:
            pr = self.db.progress(p["id"], ctx["key"])
            resume_ms = pr["positionMs"] if pr and not pr["watched"] and pr["positionMs"] >= dbm.MIN_RESUME_MS else 0
        ctx["resumeMs"] = resume_ms
        ctx["headers"] = client.playback_headers()
        out = {"links": links}
        if len(links) == 1:
            out["session"] = self.open_session(ctx, links[0]["url"])
        else:
            self._pending = ctx
        return out

    def choose_link(self, url):
        ctx = getattr(self, "_pending", None)
        if not ctx:
            raise UserError("Sessione scaduta: riprova.")
        return self.open_session(ctx, url)

    def open_session(self, ctx, url):
        token = hls_proxy.register(ctx.get("headers"))
        self.sessions[token] = ctx
        nxt = self._next_ref(ctx)
        return {
            "token": token, "src": hls_proxy.url_for(token, url), "title": ctx["title"], "subtitle": ctx.get("subtitle"),
            "resumeMs": ctx.get("resumeMs", 0), "isLive": ctx.get("isLive", False), "slug": ctx.get("slug"),
            "next": nxt,
        }

    def _next_ref(self, ctx):
        if ctx.get("season") is None or not ctx.get("slug"):
            return None
        d = self.detail(ctx["slug"])
        plan = [(s["number"], e["number"], e["name"]) for s in d["seasons"] for e in s["episodes"]]
        idx = next((i for i, x in enumerate(plan) if x[0] == ctx["season"] and x[1] == ctx["episode"]), None)
        if idx is None or idx + 1 >= len(plan):
            return None
        s, e, n = plan[idx + 1]
        return {"season": s, "episode": e, "name": n}

    def next_episode(self, token):
        ctx = self.sessions.get(token)
        if not ctx:
            raise UserError("Sessione scaduta.")
        nxt = self._next_ref(ctx)
        if not nxt:
            raise UserError("Non ci sono altri episodi.")
        res = self.play(ctx["slug"], nxt["season"], nxt["episode"], None)
        if "session" not in res:  # più sorgenti: per il successivo si prende automaticamente la prima
            res["session"] = self.choose_link(res["links"][0]["url"])
        return res["session"]

    def save_progress(self, token, position_ms, duration_ms):
        ctx = self.sessions.get(token)
        if ctx:
            self.db.save_progress(self.active()["id"], ctx, position_ms, duration_ms)

    def set_watched(self, slug, season, episode, watched):
        d = self.detail(slug)
        ctx = self._ctx(d, season, episode)
        self.db.set_watched(self.active()["id"], ctx, watched)

    def rsi_play(self, m):
        url = rsi.resolve_stream(m["id"])
        key = f"rsi:{m['urn']}"
        pr = None if m.get("isLive") else self.db.progress(self.active()["id"], key)
        ctx = {
            "key": key, "ownerId": key, "origin": "rsi", "title": m["title"], "subtitle": m.get("show"),
            "image": m.get("image"), "type": "unknown", "season": None, "episode": None, "slug": None,
            "isLive": bool(m.get("isLive")), "headers": {},
            "resumeMs": pr["positionMs"] if pr and not pr["watched"] and pr["positionMs"] >= dbm.MIN_RESUME_MS else 0,
        }
        return self.open_session(ctx, url)

    # ---------- home e preferiti ----------

    def home(self):
        p = self.active()
        return {"continue": self.db.continue_watching(p["id"]), "favorites": self.db.favorites(p["id"])[:16]}

    def favorites(self):
        p = self.active()
        recent = {x["ownerId"]: x for x in self.db.continue_watching(p["id"], limit=500)}
        out = []
        for f in self.db.favorites(p["id"]):
            pr = recent.get(f["slug"])
            status = None
            if pr and f["type"] == "series" and pr["season"] is not None:
                status = (f"Visto fino a S{pr['season']} · E{pr['episode']}" if pr["watched"]
                          else f"In corso: S{pr['season']} · E{pr['episode']}")
            elif pr and not pr["watched"] and pr["durationMs"]:
                status = f"In corso · {int(pr['positionMs'] * 100 / pr['durationMs'])}%"
            out.append({**f, "status": status})
        return out

    # ---------- impostazioni ----------

    def verify(self, pid):
        self.require_adult()
        p = self.db.profile(pid)
        lines, err = [], False
        try:
            n = len(VodClient(p).search("a"))
            lines.append(f"Server: ok ({n} risultati di prova).")
        except Exception as e:
            lines.append(f"Server: {_friendly(e)}")
            err = True
        if not p["tmdbKey"]:
            lines.append("TMDB: chiave non impostata" + (" (obbligatoria per i bambini)." if p["isKid"] else ", uso i dati del server."))
            err = err or p["isKid"]
        else:
            try:
                tmdb.check_key(p["tmdbKey"])
                lines.append("TMDB: chiave valida.")
            except Exception:
                lines.append("TMDB: chiave non valida o servizio non raggiungibile.")
                err = True
        return {"message": "\n".join(lines), "error": err}

    def switch(self, pid, pin):
        cur, target = self.active(), self.db.profile(pid)
        if not target:
            raise UserError("Profilo non trovato.")
        if cur["isKid"] and not target["isKid"] and self.db.has_pin():
            if not pin:
                return {"pinRequired": True}
            if not self.db.verify_pin(pin):
                raise UserError("PIN errato, riprova.")
        self.db.switch_profile(pid)
        return {"ok": True}

    def create_profile(self, kid, template_id):
        self.require_adult()
        if kid and not self.db.has_pin():
            return {"pinRequired": True}
        tpl = self.db.profile(template_id) if template_id else self.active()
        return {"profile": self.db.create_profile(kid, tpl)}

    def update_profile(self, b):
        self.require_adult()
        name = (b.get("name") or "").strip()
        if not name:
            raise UserError("Il nome del profilo è obbligatorio.")
        self.db.update_profile(b["id"], name, net.normalize_base_url(b.get("baseUrl")),
                               net.normalize_domain(b.get("sourceDomain")), net.normalize_domain(b.get("iframeDomain")),
                               (b.get("tmdbKey") or "").strip())
        self.details.clear()
        return {"profile": self.db.profile(b["id"])}

    def delete_profile(self, pid):
        self.require_adult()
        all_ = self.db.profiles()
        target = next((p for p in all_ if p["id"] == pid), None)
        if not target:
            raise UserError("Profilo non trovato.")
        if pid == self.active()["id"]:
            raise UserError("Non puoi eliminare il profilo in uso: passa prima a un altro profilo.")
        if not target["isKid"] and sum(1 for p in all_ if not p["isKid"]) <= 1:
            raise UserError("Serve almeno un profilo standard.")
        self.db.delete_profile(pid)
        return {"ok": True}


def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def _json(self, obj, status=200):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _static(self, rel):
            path = os.path.normpath(os.path.join(app.web_dir, rel))
            if not path.startswith(os.path.normpath(app.web_dir)) or not os.path.isfile(path):
                self.send_error(404)
                return
            with open(path, "rb") as f:
                data = f.read()
            if rel == "index.html":
                data = data.replace(b"__VOD_KEY__", app.token.encode())
            self.send_response(200)
            self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            if u.path in ("/", "/index.html"):
                return self._static("index.html")
            if u.path.startswith("/web/"):
                return self._static(u.path[len("/web/"):])
            if u.path.startswith("/hls/"):
                return hls_proxy.handle(self, u.path[len("/hls/"):], q.get("u", ""))
            if u.path.startswith("/api/"):
                return self._api("GET", u.path[5:], q)
            self.send_error(404)

        def do_POST(self):
            u = urllib.parse.urlparse(self.path)
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                body = {}
            if u.path.startswith("/api/"):
                return self._api("POST", u.path[5:], body)
            self.send_error(404)

        def _api(self, method, route, a):
            if self.headers.get("X-VOD-Key") != app.token:
                return self._json({"error": "Non autorizzato"}, 403)
            try:
                self._json(route_api(app, method, route, a))
            except Exception as e:
                if not isinstance(e, (UserError, net.HttpError)):
                    traceback.print_exc()
                self._json({"error": _friendly(e)}, 400)

    return Handler


def _int(v):
    try:
        return None if v is None or v == "" else int(v)
    except (TypeError, ValueError):
        return None


def route_api(app, method, route, a):
    if route == "state":
        return app.state()
    if route == "search":
        return app.search((a.get("q") or "").strip())
    if route == "inspect":
        return app.inspect(list(a.get("slugs") or []))
    if route == "detail":
        return app.detail_view(a["slug"])
    if route == "play":
        return app.play(a["slug"], _int(a.get("season")), _int(a.get("episode")), _int(a.get("resumeMs")))
    if route == "choose":
        return app.choose_link(a["url"])
    if route == "next":
        return app.next_episode(a["token"])
    if route == "progress":
        app.save_progress(a["token"], int(a.get("positionMs") or 0), int(a.get("durationMs") or 0))
        return {"ok": True}
    if route == "watched":
        app.set_watched(a["slug"], _int(a.get("season")), _int(a.get("episode")), bool(a.get("watched")))
        return {"ok": True}
    if route == "favorite":
        app.db.set_favorite(app.active()["id"], a["slug"], a["title"], a.get("image"), a.get("type", "unknown"), bool(a.get("fav")))
        return {"ok": True}
    if route == "home":
        return app.home()
    if route == "favorites":
        return {"items": app.favorites()}
    if route == "motors":
        return rsi.feed(a.get("sport") if a.get("sport") in rsi.SPORTS else "motogp")
    if route == "rsi/play":
        return app.rsi_play(a)
    if route == "switch":
        return app.switch(a["id"], a.get("pin"))
    if route == "profiles/create":
        return app.create_profile(bool(a.get("kid")), a.get("templateId"))
    if route == "profiles/update":
        return app.update_profile(a)
    if route == "profiles/delete":
        return app.delete_profile(a["id"])
    if route == "pin":
        app.require_adult()
        try:
            app.db.set_pin(str(a.get("pin") or ""))
        except ValueError as e:
            raise UserError(str(e))
        return {"ok": True}
    if route == "verify":
        return app.verify(a["id"])
    if route == "history/clear":
        app.require_adult()
        app.db.clear_history(a["id"])
        return {"ok": True}
    if route == "library/info":
        if not app.library:
            return {"available": False}
        return {"available": True, "url": app.library["url"], **app.library["lib"].info()}
    if route == "library/open":
        app.require_adult()
        if app.library:
            import subprocess
            subprocess.Popen(["open", app.library["lib"].folder])
        return {"ok": True}
    if route == "update/status":
        return updater.status()
    if route == "update/check":
        return updater.check()
    if route == "update/download":
        updater.start_download()
        return updater.status()
    if route == "restart":
        updater.restart()
        return {"ok": True}
    raise UserError(f"Funzione sconosciuta: {route}")


def start(db_path, web_dir, token, port=0):
    db = dbm.Database(db_path)
    app = VodApp(db, web_dir, token)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app))
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, app
