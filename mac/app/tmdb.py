"""Arricchimento con TMDB (in italiano): titolo, trama, locandina, sfondo, generi, voto, durate, episodi."""
from concurrent.futures import ThreadPoolExecutor

import net
from vod_api import GENERIC_EPISODE

API = "https://api.themoviedb.org/3"
IMAGES = "https://image.tmdb.org/t/p"
_cache = {}


def _get(path, key):
    params = {"language": "it-IT"}
    headers = {}
    if key.startswith("eyJ"):
        headers["Authorization"] = f"Bearer {key}"   # Read Access Token v4
    else:
        params["api_key"] = key                      # chiave v3
    return net.get_json(f"{API}/{path}", params, headers, timeout=15)


def check_key(key):
    _get("configuration", key.strip())


def _img(path, size):
    return f"{IMAGES}/{size}{path}" if path else None


def enrich(detail, key):
    """Restituisce il dettaglio arricchito; se TMDB non risponde, quello originale."""
    tid = detail.get("tmdbId")
    if not tid or not key:
        return detail
    ck = (detail["slug"], tid)
    if ck in _cache:
        return _cache[ck]
    try:
        kind, info = _fetch(tid, detail["type"], key)
        if info is None:
            return detail
        out = _merge(detail, kind, info, key)
        _cache[ck] = out
        return out
    except Exception:
        return detail


def _fetch(tid, type_, key):
    order = ["tv"] if type_ == "series" else ["movie", "tv"]
    for kind in order:
        try:
            return kind, _get(f"{kind}/{tid}", key)
        except net.HttpError:
            continue
    return None, None


def _merge(d, kind, info, key):
    is_series = kind == "tv" or bool(d["seasons"])
    genres = [g for g in info.get("genres") or [] if isinstance(g, dict)]
    runtime = info.get("runtime") or ((info.get("episode_run_time") or [None])[0])
    out = dict(d)
    out.update({
        "title": info.get("title") or info.get("name") or d["title"],
        "plot": info.get("overview") or d["plot"],
        "image": _img(info.get("poster_path"), "w500") or d["image"],
        "backdrop": _img(info.get("backdrop_path"), "w1280") or d["backdrop"],
        "year": (info.get("release_date") or info.get("first_air_date") or "")[:4] or d["year"],
        "type": "series" if is_series else "movie",
        "genres": [g.get("name") for g in genres if g.get("name")],
        "genreIds": [g.get("id") for g in genres if isinstance(g.get("id"), int)],
        "rating": info.get("vote_average") or None,
        "runtime": runtime or None,
        "adult": bool(info.get("adult")),
        "tmdbEnriched": True,
    })
    if is_series:
        out["seasons"] = _seasons(d, info, key)
    return out


def _seasons(d, info, key):
    tmdb_numbers = [s.get("season_number") for s in info.get("seasons") or [] if (s.get("season_number") or 0) > 0]
    numbers = [s["number"] for s in d["seasons"]] or tmdb_numbers
    tid = d["tmdbId"]

    def fetch(n):
        try:
            return n, _get(f"tv/{tid}/season/{n}", key)
        except Exception:
            return n, None

    with ThreadPoolExecutor(max_workers=4) as pool:
        remote = dict(pool.map(fetch, numbers))

    seasons = []
    for n in numbers:
        own = next((s for s in d["seasons"] if s["number"] == n), None)
        r = remote.get(n) or {}
        r_eps = {e.get("episode_number"): e for e in r.get("episodes") or []}
        if own:
            eps = [_merge_ep(e, r_eps.get(e["number"])) for e in own["episodes"]]
        else:
            eps = [_merge_ep({"number": num, "name": f"Episodio {num}", "plot": None, "image": None, "runtime": None}, e)
                   for num, e in sorted(r_eps.items()) if num]
        if eps:
            seasons.append({"number": n, "name": r.get("name") or (own or {}).get("name") or f"Stagione {n}", "episodes": eps})
    return seasons


def _merge_ep(ep, e):
    if not e:
        return ep
    name = e.get("name")
    if not name or (GENERIC_EPISODE.match(name) and not GENERIC_EPISODE.match(ep["name"])):
        name = ep["name"]
    return {**ep, "name": name, "plot": e.get("overview") or ep["plot"],
            "image": _img(e.get("still_path"), "w300") or ep["image"], "runtime": e.get("runtime") or ep["runtime"]}
