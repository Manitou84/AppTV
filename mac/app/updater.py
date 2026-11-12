"""Aggiornamento dalle Impostazioni: controlla la release «mac-latest» su GitHub, scarica lo zip con
barra di avanzamento, poi al riavvio sostituisce VOD.app e la riapre."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading

import net
from version import VERSION

REPO = "Manitou84/AppTV"
BASE = f"https://github.com/{REPO}/releases/download/mac-latest"
VERSION_URL = f"{BASE}/version.json"
ZIP_URL = f"{BASE}/VOD-mac.zip"

_state = {"phase": "idle", "progress": 0.0, "latest": None, "notes": None, "problem": None, "ready": None}
_lock = threading.Lock()


def _set(**kw):
    with _lock:
        _state.update(kw)


def status():
    with _lock:
        return dict(_state, current=VERSION, bundle=bundle_path(), translocated=is_translocated())


def bundle_path():
    """Percorso di VOD.app se l'app gira impacchettata, altrimenti None (sviluppo)."""
    if not getattr(sys, "frozen", False):
        return None
    p = os.path.abspath(sys.executable)
    while p and p != "/" and not p.endswith(".app"):
        p = os.path.dirname(p)
    return p if p.endswith(".app") else None


def is_translocated():
    b = bundle_path()
    return bool(b and "/AppTranslocation/" in b)


def check():
    _set(phase="checking", problem=None)
    try:
        with net.request(VERSION_URL, timeout=15) as r:
            info = json.loads(r.read().decode())
    except Exception:
        _set(phase="idle", problem="Impossibile controllare gli aggiornamenti: verifica la connessione.")
        return status()
    latest = str(info.get("version", ""))
    newer = VERSION == "dev" or latest > VERSION
    _set(phase="available" if newer and latest else "uptodate", latest=latest, notes=info.get("notes"))
    return status()


def start_download():
    with _lock:
        if _state["phase"] == "downloading":
            return
        _state.update(phase="downloading", progress=0.0, problem=None)
    threading.Thread(target=_download, daemon=True).start()


def _download():
    try:
        work = tempfile.mkdtemp(prefix="vod-update-")
        zip_path = os.path.join(work, "VOD-mac.zip")
        with net.request(ZIP_URL, timeout=60) as r, open(zip_path, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if total:
                    _set(progress=min(done / total, 0.99))
        out = os.path.join(work, "unpacked")
        os.makedirs(out)
        # ditto conserva link simbolici e permessi del pacchetto .app (zipfile no)
        subprocess.run(["/usr/bin/ditto", "-x", "-k", zip_path, out], check=True)
        app = next((os.path.join(out, n) for n in os.listdir(out) if n.endswith(".app")), None)
        if not app:
            raise RuntimeError("Pacchetto di aggiornamento non valido.")
        _set(phase="ready", progress=1.0, ready=app)
    except Exception as e:
        _set(phase="error", problem=f"Download non riuscito: {e}")


def restart():
    """Chiude l'app e la riapre; se c'è un aggiornamento pronto, prima sostituisce VOD.app."""
    pid = os.getpid()
    target = bundle_path()
    new_app = _state.get("ready")
    script = os.path.join(tempfile.gettempdir(), "vod-restart.sh")
    if target:
        swap = ""
        if new_app:
            swap = f'''
rm -rf "{target}.old"
mv "{target}" "{target}.old" && mv "{new_app}" "{target}" && rm -rf "{target}.old" || mv "{target}.old" "{target}" 2>/dev/null
xattr -dr com.apple.quarantine "{target}" 2>/dev/null
'''
        relaunch = f'open "{target}"'
    else:  # sviluppo: rilancia lo stesso comando
        relaunch = " ".join([f'"{sys.executable}"'] + [f'"{a}"' for a in sys.argv])
        swap = ""
    with open(script, "w") as f:
        f.write(f"""#!/bin/bash
while kill -0 {pid} 2>/dev/null; do sleep 0.3; done
{swap}
{relaunch} &
""")
    os.chmod(script, 0o755)
    subprocess.Popen(["/bin/bash", script], start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    threading.Timer(0.4, lambda: os._exit(0)).start()


def cleanup_old():
    b = bundle_path()
    if b and os.path.exists(b + ".old"):
        shutil.rmtree(b + ".old", ignore_errors=True)
