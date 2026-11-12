"""Sezione Motori: MotoGP e Formula 1 dall'integration layer pubblico SRG SSR (lo stesso del player web RSI).

L'app non aggira blocchi: se la RSI risponde con blockReason (es. GEOBLOCK) il contenuto è mostrato come non disponibile.
I percorsi delle liste (dirette, ricerca) non sono documentati: si provano più candidati.
"""
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

import net

IL = "https://il.srgssr.ch/integrationlayer/2.0/rsi"
AKAMAI_TOKEN = "https://tp.srgssr.ch/akahd/token?acl=*"

SPORTS = {
    "motogp": {"label": "MotoGP", "queries": ["MotoGP", "Motomondiale"],
               "keywords": ["motogp", "moto gp", "motomondiale", "moto2", "moto3"]},
    "f1": {"label": "Formula 1", "queries": ["Formula 1", "F1 Gran Premio"],
           "keywords": ["formula 1", "formula uno", "formula1", " f1 ", "f1:", "f1 -", "gp di f1"]},
}

BLOCK_MESSAGES = {
    "GEOBLOCK": "La RSI rende disponibile questo contenuto solo in Svizzera.",
    "STARTDATE": "Non ancora disponibile: la diretta non è iniziata.",
    "ENDDATE": "Non più disponibile sulla RSI.",
    "LEGAL": "Non disponibile per motivi di diritti.",
}


def _search_urls(q):
    e = urllib.parse.quote(q)
    return [f"{IL}/searchResultMediaList?q={e}&sortBy=date&pageSize=40", f"{IL}/mediaList/video/search?q={e}&pageSize=40"]


def _dt(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None
    except ValueError:
        return None


def _media(o):
    if not isinstance(o, dict) or not o.get("id") or not o.get("title"):
        return None
    return {
        "id": str(o["id"]), "urn": o.get("urn") or f"urn:rsi:video:{o['id']}", "title": o["title"],
        "show": (o.get("show") or {}).get("title"), "description": o.get("description") or o.get("lead"),
        "image": o.get("imageUrl"), "date": o.get("date"), "durationMs": o.get("duration"),
        "type": o.get("type"), "blockReason": o.get("blockReason"),
        "validFrom": o.get("validFrom"), "validTo": o.get("validTo"),
    }


def _first_list(urls):
    for url in urls:
        try:
            root = net.get_json(url, timeout=15)
        except Exception:
            continue
        items = []
        if isinstance(root, dict):
            for k in ("mediaList", "searchResultMediaList", "list"):
                if isinstance(root.get(k), list):
                    items = root[k]
                    break
        return [m for m in (_media(x) for x in items) if m]
    return None


def status(m, now=None):
    now = now or datetime.now(timezone.utc)
    vf = _dt(m.get("validFrom"))
    live_type = m.get("type") in ("LIVESTREAM", "SCHEDULED_LIVESTREAM")
    if m.get("blockReason") == "STARTDATE" or (m.get("type") == "SCHEDULED_LIVESTREAM" and vf and vf > now):
        return "upcoming"
    if m.get("blockReason"):
        return "blocked"
    return "live" if live_type else "available"


def feed(sport):
    cfg = SPORTS[sport]
    with ThreadPoolExecutor(max_workers=4) as pool:
        live_f = pool.submit(_first_list, [f"{IL}/mediaList/video/livestreams"])
        sched_f = pool.submit(_first_list, [f"{IL}/mediaList/video/scheduledLivestreams?pageSize=60"])
        search_f = [pool.submit(_first_list, _search_urls(q)) for q in cfg["queries"]]
        live, sched, searches = live_f.result(), sched_f.result(), [f.result() for f in search_f]
    if live is None and sched is None and all(s is None for s in searches):
        raise net.HttpError(0, "Servizio RSI non raggiungibile.")

    def matches(m):
        text = " " + " ".join(filter(None, [m["title"], m.get("show"), m.get("description")])).lower() + " "
        return any(k in text for k in cfg["keywords"])

    now = datetime.now(timezone.utc)
    seen, live_out = set(), []
    for m in (live or []) + (sched or []):
        vt = _dt(m.get("validTo"))
        if matches(m) and not (vt and vt < now) and m["urn"] not in seen:
            seen.add(m["urn"])
            live_out.append({**m, "status": status(m, now)})
    live_out.sort(key=lambda m: (m["status"] != "live", m.get("validFrom") or m.get("date") or ""))

    seen, vod = set(), []
    for lst in searches:
        for m in lst or []:
            if m.get("type") in ("LIVESTREAM", "SCHEDULED_LIVESTREAM") or not matches(m) or m["urn"] in seen:
                continue
            seen.add(m["urn"])
            vod.append({**m, "status": status(m, now)})
    vod.sort(key=lambda m: m.get("date") or "", reverse=True)
    return {"live": live_out, "onDemand": vod}


def resolve_stream(media_id):
    """Chiede alla RSI il flusso HLS. Solleva HttpError con il motivo se la RSI lo nega."""
    root = net.get_json(f"{IL}/mediaComposition/video/{media_id}.json?onlyChapters=true")
    chapters = [c for c in (root.get("chapterList") or []) if isinstance(c, dict)]
    ch = next((c for c in chapters if str(c.get("id")) == str(media_id)), chapters[0] if chapters else None)
    if not ch:
        raise net.HttpError(0, "Nessun flusso RSI disponibile.")
    if ch.get("blockReason"):
        raise net.HttpError(0, BLOCK_MESSAGES.get(ch["blockReason"], f"Contenuto non disponibile ({ch['blockReason']})."))
    res = [r for r in ch.get("resourceList") or [] if (r.get("protocol") or "").upper() == "HLS" or ".m3u8" in (r.get("url") or "")]
    res.sort(key=lambda r: 0 if (r.get("quality") or "").upper() == "HD" else 1)
    if not res or not res[0].get("url"):
        raise net.HttpError(0, "Nessun flusso RSI disponibile.")
    if res[0].get("drmList"):
        raise net.HttpError(0, "Contenuto RSI protetto (DRM): non riproducibile in questa app.")
    url = res[0]["url"]
    if (res[0].get("tokenType") or "").upper() == "AKAMAI":
        try:
            auth = net.get_json(AKAMAI_TOKEN).get("token", {}).get("authparams")
            if auth:
                url += ("&" if "?" in url else "?") + auth
        except Exception:
            pass
    return url
