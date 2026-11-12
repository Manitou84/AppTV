"""VOD per Mac: avvia il server locale e apre la finestra (pywebview, motore WebKit di macOS)."""
import os
import secrets
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import server   # noqa: E402
import updater  # noqa: E402
from version import VERSION  # noqa: E402


def web_dir():
    # Dentro VOD.app py2app mette le risorse in Contents/Resources (variabile RESOURCEPATH)
    res = os.environ.get("RESOURCEPATH")
    if res and os.path.isdir(os.path.join(res, "web")):
        return os.path.join(res, "web")
    return os.path.join(HERE, "web")


def data_dir():
    d = os.path.expanduser("~/Library/Application Support/VOD")
    os.makedirs(d, exist_ok=True)
    return d


class JsApi:
    """Funzioni chiamabili dall'interfaccia: window.pywebview.api.*"""

    def __init__(self):
        self.window = None

    def toggle_fullscreen(self):
        if self.window:
            self.window.toggle_fullscreen()


def main():
    updater.cleanup_old()
    token = secrets.token_urlsafe(24)
    httpd, _ = server.start(os.path.join(data_dir(), "vod.db"), web_dir(), token)
    url = f"http://127.0.0.1:{httpd.server_address[1]}/"

    if "--server-only" in sys.argv:   # per le prove senza finestra
        print(url, flush=True)
        try:
            import time
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            return

    import webview
    api = JsApi()
    api.window = webview.create_window(
        f"VOD {VERSION}", url, js_api=api, width=1440, height=900, min_size=(1100, 700), background_color="#0E1014",
    )
    webview.start(private_mode=False)
    os._exit(0)


if __name__ == "__main__":
    main()
