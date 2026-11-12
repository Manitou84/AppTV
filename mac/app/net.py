"""Chiamate HTTP comuni (solo libreria standard)."""
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_8) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"

_ctx = ssl.create_default_context()
try:  # i certificati di sistema su macOS non sempre sono visti da Python: certifi se c'è
    import certifi
    _ctx = ssl.create_default_context(cafile=certifi.where())
except Exception:
    pass


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def request(url, params=None, headers=None, timeout=30, data=None, method=None):
    if params:
        clean = {k: v for k, v in params.items() if v is not None and v != ""}
        if clean:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(clean)
    h = {"User-Agent": USER_AGENT, "Accept": "application/json, */*"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h, data=data, method=method)
    return urllib.request.urlopen(req, timeout=timeout, context=_ctx)


def get_json(url, params=None, headers=None, timeout=30):
    try:
        with request(url, params, headers, timeout) as r:
            body = r.read()
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, f"HTTP {e.code}") from None
    except urllib.error.URLError as e:
        raise HttpError(0, f"Server non raggiungibile ({e.reason})") from None
    except TimeoutError:
        raise HttpError(0, "Il server non ha risposto in tempo") from None
    try:
        return json.loads(body.decode("utf-8", "replace"))
    except ValueError:
        raise HttpError(200, "Risposta non JSON") from None


def normalize_base_url(raw):
    s = (raw or "").strip().rstrip("/")
    if not s:
        return s
    return s if s.lower().startswith(("http://", "https://")) else "http://" + s


def normalize_domain(raw):
    s = (raw or "").strip()
    for p in ("https://", "http://"):
        if s.lower().startswith(p):
            s = s[len(p):]
    return s.rstrip("/")


def resolve(base_url, path):
    if not path:
        return None
    if path.startswith(("http://", "https://")):
        return path
    if path.startswith("//"):
        return "https:" + path
    return base_url.rstrip("/") + "/" + path.lstrip("/")


def referer_for(iframe_domain):
    d = normalize_domain(iframe_domain)
    return f"https://{d}/" if d else None
