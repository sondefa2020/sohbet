#!/usr/bin/env python3
"""Bayx kayıt ve yönetim servisi.

Synapse'in önünde duran küçük bir yardımcı servis:
  * Yeni kayıtları KİLİTLİ hesap olarak açar; yönetici onaylayana kadar kullanılamaz.
  * Üye sayısını sınırlar (varsayılan 50).
  * Yöneticinin onaylama, reddetme, üye çıkarma ve şifre sıfırlama işlemlerini yapar.

Yalnızca Python standart kütüphanesini kullanır. Ayarlar ortam değişkenlerinden okunur.
Şifreler hiçbir yere yazılmaz; doğrudan Synapse'e iletilir.
"""
import json
import os
import re
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

USERNAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,19}$")
MAX_BODY = 8 * 1024
REGISTRATIONS_PER_HOUR = 5

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

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
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
                members = [
                    {"user_id": m["user_id"], "display_name": m["display_name"], "admin": m["admin"]}
                    for m in list_members()
                    if not m["pending"]
                ]
                self._send(200, {"members": members, "me": caller})
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
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
