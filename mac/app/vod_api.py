"""Client del backend VOD: /api/search, /api/load, /api/get_links. Parsing volutamente tollerante."""
import re

import net

SOURCE_DOMAIN_PARAM = "domain"   # nome del parametro con il dominio sorgente: cambialo qui se il backend ne usa un altro
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")


def content_type(raw):
    r = (raw or "").strip().lower()
    if r in ("movie", "movies", "film", "films"):
        return "movie"
    if r in ("tv", "series", "serie", "serie tv", "tvshow", "tv_show", "show"):
        return "series"
    return "unknown"


def _s(o, *keys):
    for k in keys:
        v = o.get(k) if isinstance(o, dict) else None
        if v is None or isinstance(v, (dict, list)):
            continue
        v = str(v).strip()
        if v:
            return v
    return None


def _i(o, *keys):
    for k in keys:
        v = o.get(k) if isinstance(o, dict) else None
        try:
            if v is not None and not isinstance(v, bool):
                return int(v)
        except (TypeError, ValueError):
            pass
    return None


def _find_list(root, *keys):
    if isinstance(root, list):
        return root
    if isinstance(root, dict):
        for k in keys:
            if isinstance(root.get(k), list):
                return root[k]
        if isinstance(root.get("data"), (dict, list)):
            found = _find_list(root["data"], *keys)
            if found:
                return found
        for v in root.values():
            if isinstance(v, list):
                return v
    return []


class VodClient:
    def __init__(self, settings):
        self.base = net.normalize_base_url(settings.get("baseUrl"))
        self.source = net.normalize_domain(settings.get("sourceDomain"))
        self.iframe = net.normalize_domain(settings.get("iframeDomain"))
        if not self.base:
            raise net.HttpError(0, "Imposta il Base URL del server nelle Impostazioni.")

    def _extra(self):
        return {SOURCE_DOMAIN_PARAM: self.source} if self.source else {}

    def search(self, query):
        root = net.get_json(f"{self.base}/api/search", {"q": query, **self._extra()})
        out, seen = [], set()
        for o in _find_list(root, "results", "items", "data"):
            if not isinstance(o, dict):
                continue
            slug = _s(o, "slug", "id")
            if not slug or slug in seen:
                continue
            seen.add(slug)
            out.append({
                "name": _s(o, "name", "title") or slug,
                "slug": slug,
                "image": net.resolve(self.base, _s(o, "image", "poster", "cover", "thumbnail")),
                "type": content_type(_s(o, "type", "category", "kind", "media_type")),
            })
        return out

    def load(self, slug):
        root = net.get_json(f"{self.base}/api/load", {"slug": slug, **self._extra()})
        if not isinstance(root, dict):
            raise net.HttpError(200, "Formato dettagli non valido")
        o = root.get("data") if isinstance(root.get("data"), dict) else root.get("result") if isinstance(root.get("result"), dict) else root
        seasons = self._seasons(o)
        declared = content_type(_s(o, "type", "category", "kind", "media_type"))
        type_ = "series" if seasons else (declared if declared != "unknown" else "movie")
        year = _s(o, "year", "release_date", "date")
        return {
            "slug": slug,
            "title": _s(o, "name", "title") or slug,
            "plot": _s(o, "plot", "description", "overview", "trama"),
            "image": net.resolve(self.base, _s(o, "image", "poster", "cover")),
            "backdrop": net.resolve(self.base, _s(o, "backdrop", "background", "fanart", "banner")),
            "year": year[:4] if year else None,
            "tmdbId": _s(o, "tmdb_id", "tmdbId", "tmdb"),
            "type": type_,
            "seasons": seasons,
            "genres": [], "genreIds": [], "rating": None, "runtime": None, "adult": False, "tmdbEnriched": False,
        }

    def get_links(self, tmdb_id, season=None, episode=None):
        root = net.get_json(f"{self.base}/api/get_links", {
            "iframe_domain": self.iframe, "tmdb_id": tmdb_id, "season": season, "episode": episode,
        }, timeout=45)
        links = parse_links(root)
        if not links:
            raise net.HttpError(0, "Non è stato trovato nessun flusso riproducibile.")
        return links

    def playback_headers(self):
        ref = net.referer_for(self.iframe)
        return {"Referer": ref, "Origin": ref.rstrip("/")} if ref else {}

    # ---------- stagioni ed episodi ----------

    def _seasons(self, o):
        raw = o.get("seasons") or o.get("episodes")
        seasons = []
        if isinstance(raw, list):
            objs = [x for x in raw if isinstance(x, dict)]
            if objs and not any("episodes" in x for x in objs):
                groups = {}
                for e in objs:
                    groups.setdefault(_i(e, "season", "season_number") or 1, []).append(e)
                seasons = [{"number": n, "name": f"Stagione {n}", "episodes": self._episodes(eps)} for n, eps in groups.items()]
            else:
                seasons = [s for s in (self._season(el, i + 1) for i, el in enumerate(raw)) if s]
        elif isinstance(raw, dict):
            seasons = [s for s in (self._season(v, int(k) if str(k).isdigit() else i + 1) for i, (k, v) in enumerate(raw.items())) if s]
        return sorted([s for s in seasons if s["episodes"]], key=lambda s: s["number"])

    def _season(self, el, fallback):
        if isinstance(el, dict):
            n = _i(el, "number", "season", "season_number") or fallback
            return {"number": n, "name": _s(el, "name", "title") or f"Stagione {n}", "episodes": self._episodes(el.get("episodes"))}
        if isinstance(el, list):
            return {"number": fallback, "name": f"Stagione {fallback}", "episodes": self._episodes(el)}
        return None

    def _episodes(self, el):
        items = list(el.items()) if isinstance(el, dict) else [(None, x) for x in el] if isinstance(el, list) else []
        out, seen = [], set()
        for i, (key, item) in enumerate(items):
            fallback = int(key) if key is not None and str(key).isdigit() else i + 1
            if isinstance(item, dict):
                n = _i(item, "number", "episode", "episode_number") or fallback
                ep = {"number": n, "name": _s(item, "name", "title") or f"Episodio {n}",
                      "plot": _s(item, "plot", "description", "overview"),
                      "image": net.resolve(self.base, _s(item, "image", "still", "thumbnail")), "runtime": None}
            elif isinstance(item, (int, str)) and str(item).isdigit():
                n = int(item)
                ep = {"number": n, "name": f"Episodio {n}", "plot": None, "image": None, "runtime": None}
            elif isinstance(item, str):
                ep = {"number": fallback, "name": item, "plot": None, "image": None, "runtime": None}
            else:
                continue
            if ep["number"] not in seen:
                seen.add(ep["number"])
                out.append(ep)
        return sorted(out, key=lambda e: e["number"])


def parse_links(root):
    """Raccoglie tutti gli URL http(s) dal dizionario di get_links, preferendo gli .m3u8."""
    found = []

    def walk(el, path):
        if isinstance(el, dict):
            for k, v in el.items():
                walk(v, f"{path} · {k}" if path else str(k))
        elif isinstance(el, list):
            for i, v in enumerate(el):
                walk(v, f"{path} {i + 1}" if path else f"Sorgente {i + 1}")
        elif isinstance(el, str) and el.strip().lower().startswith(("http://", "https://")):
            found.append({"label": path or "Sorgente", "url": el.strip()})

    walk(root, "")
    hls = [l for l in found if ".m3u8" in l["url"].lower()]
    cands = hls or [l for l in found if not l["url"].split("?")[0].lower().endswith(IMAGE_EXT)]
    seen, out = set(), []
    for l in cands:
        if l["url"] not in seen:
            seen.add(l["url"])
            out.append(l)
    return out


# ---------- profilo bambini ----------

KID_ALLOWED = {16, 10751, 10762}           # Animazione, Famiglia, Kids
KID_BLOCKED = {27, 53, 80, 10752, 10768}   # Horror, Thriller, Crime, Guerra, War & Politics


def kid_safe(detail):
    """Solo titoli riconosciuti da TMDB come animazione/famiglia/kids. Senza TMDB: escluso (scelta prudente)."""
    ids = set(detail.get("genreIds") or [])
    return bool(detail.get("tmdbEnriched")) and not detail.get("adult") and bool(ids & KID_ALLOWED) and not (ids & KID_BLOCKED)


GENERIC_EPISODE = re.compile(r"(?i)^(episodio|episode|ep\.?)\s*\d+$")
