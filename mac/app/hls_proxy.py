"""Proxy HLS locale: il player della finestra chiede i flussi a 127.0.0.1, questo li scarica dal CDN
aggiungendo gli header richiesti (Referer/Origin) e riscrive le playlist .m3u8 perché passino di qui."""
import re
import secrets
import threading
import urllib.error
import urllib.parse

import net

_sessions = {}
_lock = threading.Lock()
URI_ATTR = re.compile(r'URI="([^"]+)"')


def register(headers):
    token = secrets.token_urlsafe(12)
    with _lock:
        _sessions[token] = dict(headers or {})
        if len(_sessions) > 50:  # tiene solo le più recenti
            for k in list(_sessions)[:-50]:
                _sessions.pop(k, None)
    return token


def url_for(token, upstream):
    return f"/hls/{token}?u={urllib.parse.quote(upstream, safe='')}"


def _rewrite(text, base, token):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            out.append(line)
        elif s.startswith("#"):
            out.append(URI_ATTR.sub(lambda m: f'URI="{url_for(token, urllib.parse.urljoin(base, m.group(1)))}"', line))
        else:
            out.append(url_for(token, urllib.parse.urljoin(base, s)))
    return "\n".join(out) + "\n"


def handle(handler, token, upstream):
    """handler = BaseHTTPRequestHandler. Scrive direttamente la risposta."""
    with _lock:
        headers = _sessions.get(token)
    if headers is None or not upstream.lower().startswith(("http://", "https://")):
        handler.send_error(404)
        return
    req_headers = dict(headers)
    rng = handler.headers.get("Range")
    if rng:
        req_headers["Range"] = rng
    try:
        resp = net.request(upstream, headers=req_headers, timeout=30)
    except urllib.error.HTTPError as e:
        handler.send_error(e.code)
        return
    except Exception:
        handler.send_error(502)
        return
    with resp:
        ctype = resp.headers.get("Content-Type", "")
        final_url = resp.geturl()
        path = urllib.parse.urlparse(final_url).path.lower()
        is_playlist = "mpegurl" in ctype.lower() or path.endswith(".m3u8")
        body, head = None, b""
        if not is_playlist and not rng:
            # Alcune playlist arrivano senza estensione né Content-Type giusto: si riconoscono dall'intestazione
            head = resp.read(7)
            if head == b"#EXTM3U":
                is_playlist = True
                body, head = head + resp.read(), b""
        if is_playlist:
            if body is None:
                body = resp.read()
            text = _rewrite(body.decode("utf-8", "replace"), final_url, token).encode()
            handler.send_response(200)
            handler.send_header("Content-Type", "application/vnd.apple.mpegurl")
            handler.send_header("Content-Length", str(len(text)))
            handler.send_header("Cache-Control", "no-cache")
            handler.end_headers()
            handler.wfile.write(text)
            return
        handler.send_response(resp.status)
        for h in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"):
            if resp.headers.get(h):
                handler.send_header(h, resp.headers[h])
        handler.end_headers()
        try:
            if head:
                handler.wfile.write(head)
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                handler.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # il player ha chiuso la connessione (seek, uscita): normale
