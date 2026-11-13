#!/usr/bin/env python3
"""Bayx kayıt ve yönetim servisi.

Synapse'in önünde duran küçük bir yardımcı servis:
  * Yeni kayıtları KİLİTLİ hesap olarak açar; yönetici onaylayana kadar kullanılamaz.
  * Üye sayısını sınırlar (varsayılan 50).
  * Yöneticinin onaylama, reddetme, üye çıkarma ve şifre sıfırlama işlemlerini yapar.

Yalnızca Python standart kütüphanesini kullanır. Ayarlar ortam değişkenlerinden okunur.
Şifreler hiçbir yere yazılmaz; doğrudan Synapse'e iletilir.
"""
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SYNAPSE = os.environ.get("BAYX_SYNAPSE_URL", "http://127.0.0.1:8008").rstrip("/")
DOMAIN = os.environ["BAYX_DOMAIN"]
ADMIN_TOKEN = os.environ["BAYX_ADMIN_TOKEN"]
BOT_USER = os.environ.get("BAYX_BOT_USER", "bayx-yonetim")
MAX_MEMBERS = int(os.environ.get("BAYX_MAX_MEMBERS", "50"))
LISTEN_HOST = os.environ.get("BAYX_LISTEN_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("BAYX_LISTEN_PORT", "8010"))

APP_ID = os.environ.get("BAYX_APP_ID", "com.tayfa.chat")
FCM_CREDENTIALS = os.environ.get("BAYX_FCM_CREDENTIALS", "")
FCM_SEND_BASE = os.environ.get("BAYX_FCM_SEND_BASE", "https://fcm.googleapis.com").rstrip("/")

USERNAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,19}$")
MAX_BODY = 8 * 1024
MAX_CALL_BODY = 64 * 1024  # görüntülü arama teklifleri (SDP) birkaç KB tutabilir
REGISTRATIONS_PER_HOUR = 5

PRESENCE_WINDOW = 90  # saniye: bu süre içinde yoklama gönderen üye çevrimiçi sayılır
TOKEN_CACHE_SECONDS = 300

_presence_lock = threading.Lock()
_last_seen = {}
_token_cache = {}

_rate_lock = threading.Lock()
_rate = {}
_register_lock = threading.Lock()


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def synapse(method, path, body=None, token=None):
    """Synapse'e istek atar; (durum kodu, json) döndürür."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(SYNAPSE + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + (token or ADMIN_TOKEN))
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw)
        except ValueError:
            return err.code, {}
    except (urllib.error.URLError, OSError):
        raise ApiError(502, "SUNUCU_ULASILAMIYOR", "Sohbet sunucusuna ulaşılamıyor.")


def user_id(username):
    return "@%s:%s" % (username, DOMAIN)


def quote(uid):
    return urllib.parse.quote(uid, safe="")


def list_members():
    """Botu ve kapatılmış hesapları saymadan tüm üyeleri (kilitliler dahil) döndürür."""
    members = []
    start = "0"
    while True:
        status, data = synapse(
            "GET",
            "/_synapse/admin/v2/users?from=%s&limit=100&guests=false&deactivated=false&locked=true" % start,
        )
        if status != 200:
            raise ApiError(502, "SUNUCU_HATASI", "Üye listesi alınamadı.")
        for user in data.get("users", []):
            if user.get("name") == user_id(BOT_USER) or user.get("deactivated"):
                continue
            members.append(
                {
                    "user_id": user.get("name"),
                    "display_name": user.get("displayname") or "",
                    "avatar_url": user.get("avatar_url") or "",
                    "admin": bool(user.get("admin")),
                    "pending": bool(user.get("locked")),
                }
            )
        start = data.get("next_token")
        if not start:
            return members


def check_rate(ip):
    now = time.time()
    with _rate_lock:
        recent = [t for t in _rate.get(ip, []) if now - t < 3600]
        if len(recent) >= REGISTRATIONS_PER_HOUR:
            _rate[ip] = recent
            raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla kayıt denemesi. Lütfen daha sonra tekrar dene.")
        recent.append(now)
        _rate[ip] = recent


def validate_password(password):
    if not isinstance(password, str) or not (8 <= len(password) <= 128):
        raise ApiError(400, "GECERSIZ_SIFRE", "Şifre 8 ile 128 karakter arasında olmalı.")


def register(body, ip):
    username = body.get("username")
    password = body.get("password")
    display_name = body.get("display_name") or username
    if not isinstance(username, str) or not USERNAME_RE.match(username) or username == BOT_USER:
        raise ApiError(
            400,
            "GECERSIZ_KULLANICI_ADI",
            "Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir.",
        )
    validate_password(password)
    if not isinstance(display_name, str) or not (1 <= len(display_name.strip()) <= 40):
        raise ApiError(400, "GECERSIZ_AD", "Görünen ad 1 ile 40 karakter arasında olmalı.")
    check_rate(ip)

    uid = user_id(username)
    with _register_lock:
        status, _ = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
        if status == 200:
            raise ApiError(409, "KULLANICI_ADI_ALINMIS", "Bu kullanıcı adı kullanılamıyor.")
        if status != 404:
            raise ApiError(502, "SUNUCU_HATASI", "Kullanıcı adı kontrol edilemedi.")
        if len(list_members()) >= MAX_MEMBERS:
            raise ApiError(403, "KAPASITE_DOLU", "Üye sınırına ulaşıldı. Yeni kayıt alınamıyor.")

        status, _ = synapse(
            "PUT",
            "/_synapse/admin/v2/users/" + quote(uid),
            {"password": password, "displayname": display_name.strip(), "admin": False, "locked": True},
        )
        if status not in (200, 201):
            raise ApiError(502, "SUNUCU_HATASI", "Hesap oluşturulamadı.")
        # Kilidin gerçekten uygulandığından emin ol; uygulanmadıysa hesabı hemen kapat.
        synapse("PUT", "/_synapse/admin/v2/users/" + quote(uid), {"locked": True})
        status, data = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
        if status != 200 or not data.get("locked"):
            synapse("POST", "/_synapse/admin/v1/deactivate/" + quote(uid), {"erase": True})
            raise ApiError(502, "SUNUCU_HATASI", "Hesap onay bekleyen duruma alınamadı.")
    return {"user_id": uid, "pending": True}


def require_admin(headers):
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer ") or len(auth) < 12:
        raise ApiError(401, "YETKISIZ", "Oturum gerekli.")
    token = auth[7:].strip()
    status, data = synapse("GET", "/_matrix/client/v3/account/whoami", token=token)
    if status != 200 or not data.get("user_id"):
        raise ApiError(401, "YETKISIZ", "Oturum geçersiz.")
    status, info = synapse("GET", "/_synapse/admin/v2/users/" + quote(data["user_id"]))
    if status != 200 or not info.get("admin") or info.get("deactivated") or info.get("locked"):
        raise ApiError(403, "YONETICI_DEGIL", "Bu işlem için yönetici olmalısın.")
    return data["user_id"]


def require_member(headers):
    """Onaylanmış herhangi bir üyenin oturumunu doğrular; kullanıcı kimliğini döndürür."""
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer ") or len(auth) < 12:
        raise ApiError(401, "YETKISIZ", "Oturum gerekli.")
    status, data = synapse("GET", "/_matrix/client/v3/account/whoami", token=auth[7:].strip())
    if status != 200 or not data.get("user_id"):
        raise ApiError(401, "YETKISIZ", "Oturum geçersiz.")
    return data["user_id"]


def member_from_token(headers):
    """require_member ile aynı; sık çağrılan uç noktalar için oturum doğrulamasını kısa süre önbellekte tutar."""
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer ") or len(auth) < 12:
        raise ApiError(401, "YETKISIZ", "Oturum gerekli.")
    key = hashlib.sha256(auth.encode()).hexdigest()
    now = time.time()
    with _presence_lock:
        cached = _token_cache.get(key)
    if cached and cached[1] > now:
        return cached[0]
    uid = require_member(headers)
    with _presence_lock:
        if len(_token_cache) > 500:
            _token_cache.clear()
        _token_cache[key] = (uid, now + TOKEN_CACHE_SECONDS)
    return uid


# MSN tarzı durumlar ve durum mesajı. Kalıcı olarak saklanır (sunucu yeniden başlasa da kalır).
STATE_DIR = os.environ.get("BAYX_STATE_DIR", "/var/lib/bayx")
PROFILE_FILE = os.path.join(STATE_DIR, "profil.json")
MODES = {"online", "busy", "away", "invisible"}
_profiles = {}


def load_profiles():
    global _profiles
    try:
        with open(PROFILE_FILE) as f:
            data = json.load(f)
        if isinstance(data, dict):
            _profiles = data
    except (OSError, ValueError):
        _profiles = {}


def save_profiles():
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        tmp = PROFILE_FILE + ".yeni"
        with open(tmp, "w") as f:
            json.dump(_profiles, f, ensure_ascii=False)
        os.replace(tmp, PROFILE_FILE)
    except OSError:
        pass


def public_profile(uid, now):
    """Başkalarının gördüğü durum: görünmez kullanıcı çevrimdışı görünür."""
    prof = _profiles.get(uid, {})
    mode = prof.get("mode", "online")
    seen = _last_seen.get(uid)
    online = seen is not None and now - seen < PRESENCE_WINDOW and mode != "invisible"
    return {"mode": mode if online else "offline", "status": prof.get("status", "")}


def presence(headers, body):
    """Çağıranın yoklamasını, durumunu ve durum mesajını kaydeder; üyelerin durumlarını döndürür."""
    uid = member_from_token(headers)
    now = time.time()
    with _presence_lock:
        prof = dict(_profiles.get(uid, {}))
        changed = False
        mode = body.get("mode")
        if mode in MODES and prof.get("mode") != mode:
            prof["mode"] = mode
            changed = True
        status = body.get("status")
        if isinstance(status, str):
            status = status.strip()[:80]
            if prof.get("status", "") != status:
                prof["status"] = status
                changed = True
        if changed:
            _profiles[uid] = prof
            save_profiles()
        if body.get("active", True):
            _last_seen[uid] = now
        else:
            _last_seen.pop(uid, None)
        users = {u: public_profile(u, now) for u in set(_last_seen) | set(_profiles)}
        online = [u for u, p in users.items() if p["mode"] != "offline"]
        mine = {"mode": _profiles.get(uid, {}).get("mode", "online"), "status": _profiles.get(uid, {}).get("status", "")}
    return {"online": online, "users": users, "me": mine}


# ------------------------------------------------------------------ bildirim (FCM)


class Fcm:
    """Firebase Cloud Messaging HTTP v1 istemcisi. Servis hesabı JWT'si openssl ile imzalanır."""

    def __init__(self, path):
        with open(path) as f:
            info = json.load(f)
        self.project = info["project_id"]
        self.email = info["client_email"]
        self.token_uri = info.get("token_uri") or "https://oauth2.googleapis.com/token"
        fd, self.key_path = tempfile.mkstemp(prefix="fcm-", suffix=".pem")
        with os.fdopen(fd, "w") as f:
            f.write(info["private_key"])
        os.chmod(self.key_path, 0o600)
        self._token = None
        self._expires = 0
        self._lock = threading.Lock()

    @staticmethod
    def _b64(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    def _jwt(self):
        now = int(time.time())
        header = self._b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = self._b64(
            json.dumps(
                {
                    "iss": self.email,
                    "scope": "https://www.googleapis.com/auth/firebase.messaging",
                    "aud": self.token_uri,
                    "iat": now,
                    "exp": now + 3600,
                }
            ).encode()
        )
        signing_input = header + b"." + claims
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", self.key_path],
            input=signing_input,
            capture_output=True,
            check=True,
            timeout=10,
        ).stdout
        return (signing_input + b"." + self._b64(signature)).decode()

    def access_token(self):
        with self._lock:
            if self._token and time.time() < self._expires - 120:
                return self._token
            data = urllib.parse.urlencode(
                {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": self._jwt()}
            ).encode()
            req = urllib.request.Request(self.token_uri, data=data, method="POST")
            req.add_header("Content-Type", "application/x-www-form-urlencoded")
            with urllib.request.urlopen(req, timeout=15) as resp:
                result = json.loads(resp.read())
            self._token = result["access_token"]
            self._expires = time.time() + int(result.get("expires_in", 3600))
            return self._token

    def send(self, token, data, ttl="60s"):
        """True: iletildi, False: cihaz anahtarı artık geçersiz, None: geçici hata."""
        message = {
            "message": {
                "token": token,
                "data": {k: str(v) for k, v in data.items()},
                "android": {"priority": "HIGH", "ttl": ttl},
            }
        }
        try:
            req = urllib.request.Request(
                "%s/v1/projects/%s/messages:send" % (FCM_SEND_BASE, self.project),
                data=json.dumps(message).encode(),
                method="POST",
            )
            req.add_header("Authorization", "Bearer " + self.access_token())
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=15):
                return True
        except urllib.error.HTTPError as err:
            text = err.read().decode(errors="replace")
            if err.code == 404 or "UNREGISTERED" in text:
                return False
            if err.code == 400 and "registration token" in text.lower():
                return False
            return None
        except Exception:
            return None


FCM = None


def push_notify(body):
    """Synapse'in bildirim isteği (Matrix push gateway). Mesaj içeriği gönderilmez, yalnızca oda ve olay kimliği."""
    notification = body.get("notification") or {}
    rejected = []
    devices = notification.get("devices") or []
    if FCM is None or not notification.get("event_id"):
        return {"rejected": []}
    data = {
        "type": "message",
        "room_id": notification.get("room_id", ""),
        "event_id": notification.get("event_id", ""),
        "unread": (notification.get("counts") or {}).get("unread", 0),
    }
    for device in devices:
        pushkey = device.get("pushkey")
        if not pushkey:
            continue
        if device.get("app_id") != APP_ID:
            rejected.append(pushkey)
            continue
        if FCM.send(pushkey, data) is False:
            rejected.append(pushkey)
    return {"rejected": rejected}


def push_call(callee, caller, call_id, name, video):
    """Uygulaması kapalı olan aranan kişiye arama bildirimi gönderir."""
    if FCM is None:
        return
    status, data = synapse("GET", "/_synapse/admin/v1/users/%s/pushers" % quote(callee))
    if status != 200:
        return
    payload = {"type": "call", "call_id": call_id, "from": caller, "name": name, "video": "1" if video else "0"}
    for pusher in data.get("pushers", []):
        if pusher.get("app_id") == APP_ID and pusher.get("pushkey"):
            FCM.send(pusher["pushkey"], payload, ttl="30s")


# ---------------------------------------------------------- arama sinyalleşmesi
# Arama kurulum mesajları (SDP teklifi/yanıtı, ICE adayları) üyeler arasında bu sunucu
# üzerinden iletilir. Ses ve görüntü cihazlar arasında WebRTC (DTLS-SRTP) ile uçtan uca
# şifrelidir; aktarma sunucusu (TURN) kullanılsa bile içeriği göremez.

CALL_TYPES = {"invite", "answer", "candidate", "hangup", "reject", "busy"}
CALL_TTL = 60
POLL_SECONDS = 25
_call_cond = threading.Condition()
_call_queues = {}


def call_send(headers, body):
    caller = member_from_token(headers)
    to = body.get("to")
    call_id = body.get("call_id")
    kind = body.get("type")
    data = body.get("data") or {}
    if not isinstance(to, str) or not re.match(r"^@[a-z0-9._-]+:" + re.escape(DOMAIN) + "$", to) or to == caller:
        raise ApiError(400, "GECERSIZ_KULLANICI", "Geçersiz kullanıcı.")
    if not isinstance(call_id, str) or not re.match(r"^[A-Za-z0-9_-]{8,64}$", call_id):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz arama.")
    if kind not in CALL_TYPES or not isinstance(data, dict):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz arama.")
    now = time.time()
    event = {"from": caller, "call_id": call_id, "type": kind, "data": data, "ts": now}
    with _call_cond:
        queue = [e for e in _call_queues.get(to, []) if now - e["ts"] < CALL_TTL]
        if len(queue) > 300:
            queue = queue[-300:]
        queue.append(event)
        _call_queues[to] = queue
        _call_cond.notify_all()
    if kind == "invite":
        name = str(data.get("name", ""))[:40]
        threading.Thread(
            target=push_call, args=(to, caller, call_id, name, bool(data.get("video"))), daemon=True
        ).start()
    return {"ok": True}


def call_poll(headers):
    uid = member_from_token(headers)
    deadline = time.time() + POLL_SECONDS
    with _call_cond:
        while True:
            now = time.time()
            queue = [e for e in _call_queues.get(uid, []) if now - e["ts"] < CALL_TTL]
            if queue:
                _call_queues[uid] = []
                return {"events": queue}
            remaining = deadline - now
            if remaining <= 0:
                _call_queues.pop(uid, None)
                return {"events": []}
            _call_cond.wait(remaining)


def turn_server(headers):
    """Synapse'in ürettiği kısa ömürlü TURN bilgilerini istemciye iletir."""
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise ApiError(401, "YETKISIZ", "Oturum gerekli.")
    status, data = synapse("GET", "/_matrix/client/v3/voip/turnServer", token=auth[7:].strip())
    if status != 200:
        return {"uris": []}
    return data


def target_user(body, caller):
    uid = body.get("user_id")
    if not isinstance(uid, str) or not re.match(r"^@[a-z0-9._-]+:" + re.escape(DOMAIN) + "$", uid):
        raise ApiError(400, "GECERSIZ_KULLANICI", "Geçersiz kullanıcı.")
    if uid == user_id(BOT_USER) or uid == caller:
        raise ApiError(400, "GECERSIZ_KULLANICI", "Bu hesap üzerinde bu işlem yapılamaz.")
    status, info = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
    if status != 200 or info.get("deactivated"):
        raise ApiError(404, "KULLANICI_YOK", "Kullanıcı bulunamadı.")
    return uid, info


def admin_action(action, body, caller):
    uid, info = target_user(body, caller)
    if action == "approve":
        status, _ = synapse("PUT", "/_synapse/admin/v2/users/" + quote(uid), {"locked": False})
    elif action == "reject":
        if not info.get("locked"):
            raise ApiError(400, "BEKLEMIYOR", "Bu hesap onay beklemiyor.")
        status, _ = synapse("POST", "/_synapse/admin/v1/deactivate/" + quote(uid), {"erase": True})
    elif action == "remove":
        status, _ = synapse("POST", "/_synapse/admin/v1/deactivate/" + quote(uid), {"erase": True})
    elif action == "reset-password":
        validate_password(body.get("new_password"))
        status, _ = synapse(
            "POST",
            "/_synapse/admin/v1/reset_password/" + quote(uid),
            {"new_password": body["new_password"], "logout_devices": True},
        )
    else:
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    if status != 200:
        raise ApiError(502, "SUNUCU_HATASI", "İşlem tamamlanamadı.")
    return {"ok": True, "user_id": uid}


class Handler(BaseHTTPRequestHandler):
    server_version = "BayxKayit/1"

    def log_message(self, fmt, *args):  # şifre içeren gövdeler asla loglanmaz; erişim logu da tutulmaz
        return

    def _send(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _body(self, limit=MAX_BODY):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > limit:
            raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
        try:
            body = json.loads(self.rfile.read(length))
        except ValueError:
            raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
        if not isinstance(body, dict):
            raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
        return body

    def _handle(self, method):
        path = self.path.split("?", 1)[0].rstrip("/")
        try:
            if method == "GET" and path == "/bayx/health":
                self._send(200, {"ok": True})
            elif method == "POST" and path == "/bayx/register":
                ip = self.headers.get("X-Real-IP") or self.client_address[0]
                self._send(201, register(self._body(), ip))
            elif method == "GET" and path == "/bayx/members":
                caller = require_member(self.headers)
                now = time.time()
                with _presence_lock:
                    members = [
                        {
                            "user_id": m["user_id"],
                            "display_name": m["display_name"],
                            "avatar_url": m["avatar_url"],
                            "admin": m["admin"],
                            "status": _profiles.get(m["user_id"], {}).get("status", ""),
                            "mode": public_profile(m["user_id"], now)["mode"],
                        }
                        for m in list_members()
                        if not m["pending"]
                    ]
                self._send(200, {"members": members, "me": caller})
            elif method == "POST" and path == "/bayx/presence":
                length = self.headers.get("Content-Length", "0")
                self._send(200, presence(self.headers, self._body() if length not in ("", "0") else {}))
            elif method == "POST" and path == "/_matrix/push/v1/notify":
                # Yalnızca aynı sunucudaki Synapse çağırabilir; nginx bu yolu dışarı açmaz.
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                self._send(200, push_notify(self._body(MAX_CALL_BODY)))
            elif method == "POST" and path == "/bayx/call/send":
                self._send(200, call_send(self.headers, self._body(MAX_CALL_BODY)))
            elif method == "POST" and path == "/bayx/call/poll":
                self._send(200, call_poll(self.headers))
            elif method == "GET" and path == "/bayx/turn":
                self._send(200, turn_server(self.headers))
            elif method == "GET" and path == "/bayx/admin/members":
                require_admin(self.headers)
                members = list_members()
                self._send(200, {"members": members, "count": len(members), "max": MAX_MEMBERS})
            elif method == "POST" and path.startswith("/bayx/admin/"):
                caller = require_admin(self.headers)
                self._send(200, admin_action(path[len("/bayx/admin/"):], self._body(), caller))
            else:
                raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
        except ApiError as err:
            self._send(err.status, {"error": err.code, "message": err.message})
        except Exception:
            self._send(500, {"error": "SUNUCU_HATASI", "message": "Beklenmeyen bir hata oluştu."})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


def main():
    global FCM
    load_profiles()
    if FCM_CREDENTIALS and os.path.exists(FCM_CREDENTIALS):
        try:
            FCM = Fcm(FCM_CREDENTIALS)
        except Exception:
            FCM = None
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
