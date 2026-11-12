"""Database locale (SQLite): profili, preferiti, cronologia di visione, classificazione titoli, PIN."""
import hashlib
import hmac
import secrets
import sqlite3
import threading
import time
import uuid

SCHEMA = """
CREATE TABLE IF NOT EXISTS profiles (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    is_kid INTEGER NOT NULL DEFAULT 0,
    color INTEGER NOT NULL DEFAULT 0,
    base_url TEXT NOT NULL DEFAULT '',
    source_domain TEXT NOT NULL DEFAULT '',
    iframe_domain TEXT NOT NULL DEFAULT '',
    tmdb_key TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS favorites (
    profile_id TEXT NOT NULL,
    slug TEXT NOT NULL,
    title TEXT NOT NULL,
    image TEXT,
    type TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (profile_id, slug)
);
CREATE TABLE IF NOT EXISTS progress (
    profile_id TEXT NOT NULL,
    id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    origin TEXT NOT NULL,
    title TEXT NOT NULL,
    subtitle TEXT,
    image TEXT,
    type TEXT NOT NULL,
    season INTEGER,
    episode INTEGER,
    position_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    watched INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (profile_id, id)
);
CREATE INDEX IF NOT EXISTS progress_owner ON progress (profile_id, owner_id);
CREATE TABLE IF NOT EXISTS known_titles (
    slug TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    kid_safe INTEGER,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT
);
"""

WATCHED_THRESHOLD = 0.9
MIN_RESUME_MS = 30_000
PIN_LENGTH = 4


def now_ms():
    return int(time.time() * 1000)


class Database:
    def __init__(self, path):
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.conn.executescript(SCHEMA)
            self.conn.commit()
        self.ensure_default_profile()

    # ---------- utilità ----------

    def _q(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.conn.execute(sql, args).fetchall()]

    def _x(self, sql, args=()):
        with self.lock:
            self.conn.execute(sql, args)
            self.conn.commit()

    def meta(self, key, default=None):
        rows = self._q("SELECT value FROM meta WHERE key = ?", (key,))
        return rows[0]["value"] if rows else default

    def set_meta(self, key, value):
        self._x("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))

    # ---------- profili ----------

    def ensure_default_profile(self):
        if self._q("SELECT id FROM profiles LIMIT 1"):
            return
        pid = str(uuid.uuid4())
        self._x(
            "INSERT INTO profiles (id, name, is_kid, color, created_at) VALUES (?, ?, 0, 0, ?)",
            (pid, "Principale", now_ms()),
        )
        self.set_meta("active_profile", pid)

    def profiles(self):
        return [self._profile(r) for r in self._q("SELECT * FROM profiles ORDER BY created_at")]

    def profile(self, pid):
        rows = self._q("SELECT * FROM profiles WHERE id = ?", (pid,))
        return self._profile(rows[0]) if rows else None

    def active_profile(self):
        p = self.profile(self.meta("active_profile") or "")
        if p:
            return p
        all_ = self.profiles()
        return all_[0] if all_ else None

    def switch_profile(self, pid):
        if self.profile(pid):
            self.set_meta("active_profile", pid)

    def create_profile(self, is_kid, template):
        existing = self.profiles()
        pid = str(uuid.uuid4())
        name = "Bambini" if is_kid else f"Profilo {len(existing) + 1}"
        color = max([p["color"] for p in existing] or [-1]) + 1
        t = template or {}
        self._x(
            "INSERT INTO profiles (id, name, is_kid, color, base_url, source_domain, iframe_domain, tmdb_key, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (pid, name, 1 if is_kid else 0, color, t.get("baseUrl", ""), t.get("sourceDomain", ""),
             t.get("iframeDomain", ""), t.get("tmdbKey", ""), now_ms()),
        )
        return self.profile(pid)

    def update_profile(self, pid, name, base_url, source_domain, iframe_domain, tmdb_key):
        self._x(
            "UPDATE profiles SET name = ?, base_url = ?, source_domain = ?, iframe_domain = ?, tmdb_key = ? WHERE id = ?",
            (name, base_url, source_domain, iframe_domain, tmdb_key, pid),
        )

    def delete_profile(self, pid):
        with self.lock:
            self.conn.execute("DELETE FROM favorites WHERE profile_id = ?", (pid,))
            self.conn.execute("DELETE FROM progress WHERE profile_id = ?", (pid,))
            self.conn.execute("DELETE FROM profiles WHERE id = ?", (pid,))
            self.conn.commit()

    @staticmethod
    def _profile(r):
        return {
            "id": r["id"], "name": r["name"], "isKid": bool(r["is_kid"]), "color": r["color"],
            "baseUrl": r["base_url"], "sourceDomain": r["source_domain"],
            "iframeDomain": r["iframe_domain"], "tmdbKey": r["tmdb_key"],
        }

    # ---------- PIN genitori (hash SHA-256 con sale, mai in chiaro) ----------

    def has_pin(self):
        return bool(self.meta("pin_hash"))

    def set_pin(self, pin):
        if len(pin) != PIN_LENGTH or not pin.isdigit():
            raise ValueError(f"Il PIN deve avere {PIN_LENGTH} cifre.")
        salt = secrets.token_hex(16)
        self.set_meta("pin_salt", salt)
        self.set_meta("pin_hash", self._hash(pin, salt))

    def verify_pin(self, pin):
        salt, stored = self.meta("pin_salt"), self.meta("pin_hash")
        if not salt or not stored:
            return False
        return hmac.compare_digest(self._hash(pin, salt), stored)

    @staticmethod
    def _hash(pin, salt):
        return hashlib.sha256(f"{salt}:{pin}".encode()).hexdigest()

    # ---------- preferiti ----------

    def favorites(self, pid):
        return self._q("SELECT slug, title, image, type, added_at FROM favorites WHERE profile_id = ? ORDER BY added_at DESC", (pid,))

    def is_favorite(self, pid, slug):
        return bool(self._q("SELECT 1 FROM favorites WHERE profile_id = ? AND slug = ?", (pid, slug)))

    def set_favorite(self, pid, slug, title, image, type_, fav):
        if fav:
            self._x(
                "INSERT OR REPLACE INTO favorites (profile_id, slug, title, image, type, added_at) VALUES (?, ?, ?, ?, ?, ?)",
                (pid, slug, title, image, type_, now_ms()),
            )
        else:
            self._x("DELETE FROM favorites WHERE profile_id = ? AND slug = ?", (pid, slug))

    # ---------- avanzamento di visione ----------

    def progress_for_owner(self, pid, owner_id):
        return {r["id"]: self._progress(r) for r in self._q(
            "SELECT * FROM progress WHERE profile_id = ? AND owner_id = ?", (pid, owner_id))}

    def progress(self, pid, key):
        rows = self._q("SELECT * FROM progress WHERE profile_id = ? AND id = ?", (pid, key))
        return self._progress(rows[0]) if rows else None

    def save_progress(self, pid, s, position_ms, duration_ms):
        """s = contesto della sessione di riproduzione. Visto = oltre il 90% della durata."""
        if s.get("isLive") or duration_ms <= 0:
            return
        prev = self.progress(pid, s["key"])
        reached_end = position_ms >= duration_ms * WATCHED_THRESHOLD
        watched = reached_end or (prev is not None and prev["watched"] and position_ms < MIN_RESUME_MS)
        self._upsert_progress(pid, s, position_ms, duration_ms, watched)

    def set_watched(self, pid, s, watched):
        prev = self.progress(pid, s["key"])
        duration = prev["durationMs"] if prev else 0
        self._upsert_progress(pid, s, duration if watched else 0, duration, watched)

    def _upsert_progress(self, pid, s, position_ms, duration_ms, watched):
        self._x(
            "INSERT OR REPLACE INTO progress (profile_id, id, owner_id, origin, title, subtitle, image, type, season, episode,"
            " position_ms, duration_ms, watched, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (pid, s["key"], s["ownerId"], s.get("origin", "vod"), s["title"], s.get("subtitle"), s.get("image"),
             s.get("type", "unknown"), s.get("season"), s.get("episode"), int(position_ms), int(duration_ms),
             1 if watched else 0, now_ms()),
        )

    def continue_watching(self, pid, limit=20):
        """Per ogni titolo l'ultima cosa vista. Film finiti esclusi; serie finite restano (si propone il successivo)."""
        rows = [self._progress(r) for r in self._q(
            "SELECT * FROM progress WHERE profile_id = ? ORDER BY updated_at DESC", (pid,))]
        # Per ogni titolo la voce più recente che abbia senso riprendere (un episodio segnato
        # "non visto" a mano non deve far sparire la serie)
        seen, out = set(), []
        for p in rows:
            if p["ownerId"] in seen:
                continue
            if (not p["watched"] and p["positionMs"] >= MIN_RESUME_MS) or (p["watched"] and p["type"] == "series"):
                seen.add(p["ownerId"])
                out.append(p)
                if len(out) >= limit:
                    break
        return out

    def clear_history(self, pid):
        self._x("DELETE FROM progress WHERE profile_id = ?", (pid,))

    @staticmethod
    def _progress(r):
        return {
            "id": r["id"], "ownerId": r["owner_id"], "origin": r["origin"], "title": r["title"],
            "subtitle": r["subtitle"], "image": r["image"], "type": r["type"], "season": r["season"],
            "episode": r["episode"], "positionMs": r["position_ms"], "durationMs": r["duration_ms"],
            "watched": bool(r["watched"]), "updatedAt": r["updated_at"],
        }

    # ---------- classificazione film / serie (condivisa fra profili) ----------

    def known(self, slugs):
        if not slugs:
            return {}
        marks = ",".join("?" * len(slugs))
        return {r["slug"]: r for r in self._q(f"SELECT * FROM known_titles WHERE slug IN ({marks})", tuple(slugs))}

    def remember(self, slug, type_, kid_safe=None):
        if type_ == "unknown":
            return
        prev = self.known([slug]).get(slug)
        if kid_safe is None and prev:
            kid_safe = prev["kid_safe"]
        self._x(
            "INSERT OR REPLACE INTO known_titles (slug, type, kid_safe, updated_at) VALUES (?, ?, ?, ?)",
            (slug, type_, None if kid_safe is None else int(bool(kid_safe)), now_ms()),
        )
