#!/usr/bin/env python3
"""Tayfa kayıt ve yönetim servisi.

Synapse'in önünde duran küçük bir yardımcı servis:
  * Şifresiz kayıt: davet bağlantısıyla gelen hemen girer, davetsiz gelen KİLİTLİ hesap olarak
    açılır ve yönetici onaylayana kadar kullanılamaz.
  * Şifresiz giriş: kurtarma kodu, başka cihazdan giriş kodu ya da yöneticinin giriş bağlantısı
    (ayrıntılar giris.py'de).
  * Üye sayısını sınırlar (varsayılan 50), durum/bildirim/arama sinyalleşmesi yapar.

Yalnızca Python standart kütüphanesini kullanır. Ayarlar ortam değişkenlerinden okunur.
"""
import base64
import hashlib
import json
import os
import re
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import eposta
import giris

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

PUBLIC_BASE = os.environ.get("BAYX_PUBLIC_BASE", "").rstrip("/") or "https://" + DOMAIN
WEB_ORIGIN = os.environ.get("BAYX_WEB_ORIGIN", "").rstrip("/")

USERNAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,19}$")
MAX_BODY = 8 * 1024
MAX_CALL_BODY = 64 * 1024  # görüntülü arama teklifleri (SDP) birkaç KB tutabilir
REGISTRATIONS_PER_HOUR = 10

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
    req.add_header("User-Agent", "TayfaKayit/2")
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
        raise ApiError(400, "GECERSIZ_SIFRE", "Şifre en az 8 karakter olmalı.")


EMAILS = None


def register_simple(body, ip):
    """Kolay kayıt: kullanıcı adı + şifre (+ isteğe bağlı e-posta). Onay beklemeden hesap açılır."""
    username = body.get("username")
    if isinstance(username, str):
        username = username.strip().lower()
    if not isinstance(username, str) or not USERNAME_RE.match(username) or username == BOT_USER:
        raise ApiError(
            400,
            "GECERSIZ_KULLANICI_ADI",
            "Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir.",
        )
    password = body.get("password")
    validate_password(password)
    email = eposta.clean_email(body.get("email") or "")
    if email is None:
        raise ApiError(400, "GECERSIZ_EPOSTA", "E-posta adresi geçersiz görünüyor.")
    if email and EMAILS.owner_of(email, verified_only=True):
        raise ApiError(409, "EPOSTA_KULLANIMDA", "Bu e-posta başka bir hesapta kullanılıyor.")
    check_rate(ip)
    uid = user_id(username)
    with _register_lock:
        status, _ = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
        if status == 200:
            raise ApiError(409, "KULLANICI_ADI_ALINMIS", "Bu kullanıcı adı alınmış, başka bir tane dene.")
        if status != 404:
            raise ApiError(502, "SUNUCU_HATASI", "Kullanıcı adı kontrol edilemedi.")
        if len(list_members()) >= MAX_MEMBERS:
            raise ApiError(403, "KAPASITE_DOLU", "Üye sınırına ulaşıldı. Yeni kayıt alınamıyor.")
        status, _ = synapse(
            "PUT",
            "/_synapse/admin/v2/users/" + quote(uid),
            {"password": password, "displayname": username, "admin": False, "locked": False},
        )
        if status not in (200, 201):
            raise ApiError(502, "SUNUCU_HATASI", "Hesap oluşturulamadı.")
    if email:
        threading.Thread(target=EMAILS.set_email, args=(uid, email), daemon=True).start()
    return {"status": "ok", "username": username, "email_pending": bool(email)}


def reset_request(body, ip):
    """Şifre sıfırlama kodu ister. Hesap var mı yok mu belli etmemek için her zaman aynı yanıt."""
    login = str(body.get("login", "")).strip().lower()
    if _recover_attempts.blocked("reset:" + ip):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla deneme. Bir saat sonra tekrar dene.")
    _recover_attempts.fail("reset:" + ip)  # istek sayısını da sınırla
    uid = None
    if "@" in login and not login.startswith("@"):
        uid = EMAILS.owner_of(login, verified_only=True)
    elif USERNAME_RE.match(login.lstrip("@").split(":")[0]):
        uid = user_id(login.lstrip("@").split(":")[0])
    if uid and active_user(uid):
        threading.Thread(target=EMAILS.start_reset, args=(uid,), daemon=True).start()
    return {
        "ok": True,
        "message": "Bu hesaba doğrulanmış bir e-posta bağlıysa 6 haneli kod gönderildi. Gelen kutunu ve istenmeyen klasörünü kontrol et.",
    }


def reset_confirm(body, ip):
    login = str(body.get("login", "")).strip().lower()
    uid = None
    if "@" in login and not login.startswith("@"):
        uid = EMAILS.owner_of(login, verified_only=True)
    elif USERNAME_RE.match(login.lstrip("@").split(":")[0]):
        uid = user_id(login.lstrip("@").split(":")[0])
    password = body.get("password")
    validate_password(password)
    if _recover_attempts.blocked("rc:" + ip) or not uid or not EMAILS.check_reset(uid, body.get("code")):
        _recover_attempts.fail("rc:" + ip)
        raise ApiError(403, "KOD_HATALI", "Kod hatalı ya da süresi dolmuş.")
    status, _ = synapse(
        "POST",
        "/_synapse/admin/v1/reset_password/" + quote(uid),
        {"new_password": password, "logout_devices": True},
    )
    if status != 200:
        raise ApiError(502, "SUNUCU_HATASI", "Şifre değiştirilemedi.")
    return {"ok": True, "username": username_of(uid)}


VERIFIED_PAGE = """<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tayfa</title></head><body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
font-family:system-ui,Segoe UI,Roboto,sans-serif;background:linear-gradient(#fbf8f4,#f2ebe2);color:#1f1b18">
<div style="background:#fff;border-radius:28px;box-shadow:0 18px 50px rgba(90,60,30,.12);padding:34px 28px;max-width:360px;width:86%%;text-align:center">
<div style="font-size:54px">%s</div><h1 style="margin:8px 0;font-size:24px">%s</h1><p style="color:#6e655c">%s</p></div></body></html>"""


def validate_profile(body):
    username = body.get("username")
    display_name = body.get("display_name") or username
    if not isinstance(username, str) or not USERNAME_RE.match(username) or username == BOT_USER:
        raise ApiError(
            400,
            "GECERSIZ_KULLANICI_ADI",
            "Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir.",
        )
    if not isinstance(display_name, str) or not (1 <= len(display_name.strip()) <= 40):
        raise ApiError(400, "GECERSIZ_AD", "Görünen ad 1 ile 40 karakter arasında olmalı.")
    return username, display_name.strip()


GIRIS = None


DEFAULT_CONFIG = {"announcement": "", "latest_version": 0, "apk_url": ""}


def get_config():
    cfg = GIRIS.data.setdefault("config", {})
    return {k: cfg.get(k, v) for k, v in DEFAULT_CONFIG.items()}


def set_config(body):
    with GIRIS.lock:
        cfg = GIRIS.data.setdefault("config", {})
        if "announcement" in body:
            cfg["announcement"] = str(body.get("announcement") or "")[:500]
        if "apk_url" in body:
            url = str(body.get("apk_url") or "")
            if url and not url.startswith("https://"):
                raise ApiError(400, "GECERSIZ_URL", "APK bağlantısı https:// ile başlamalı.")
            cfg["apk_url"] = url[:400]
        if "latest_version" in body:
            try:
                cfg["latest_version"] = max(0, int(body.get("latest_version")))
            except (TypeError, ValueError):
                raise ApiError(400, "GECERSIZ_SURUM", "Sürüm numarası sayı olmalı.")
        GIRIS.save()
    return get_config()


def _contacts():
    return GIRIS.data.setdefault("contacts", {})


def _crec(uid):
    rec = _contacts().setdefault(uid, {})
    for k in ("friends", "incoming", "outgoing", "blocked"):
        rec.setdefault(k, [])
    return rec


def are_friends(a, b):
    return b in _crec(a)["friends"]


def friends_of(uid):
    return list(_crec(uid)["friends"])


def contact_relation(me, other):
    rec = _crec(me)
    if other == me:
        return "self"
    if other in rec["friends"]:
        return "friend"
    if other in rec["incoming"]:
        return "incoming"
    if other in rec["outgoing"]:
        return "outgoing"
    if other in rec["blocked"]:
        return "blocked"
    return "none"


def profile_brief(uid):
    """Herkese görünen asgari bilgi: kullanıcı adı ve görünen ad. Durum/foto arkadaşlara özel."""
    info = active_user(uid)
    if info is None:
        return None
    return {"user_id": uid, "username": username_of(uid), "display_name": info.get("displayname") or username_of(uid)}


def contact_find(headers, body):
    me = member_from_token(headers)
    raw = str(body.get("username", "")).strip().lower().lstrip("@").split(":")[0]
    if not USERNAME_RE.match(raw):
        raise ApiError(400, "GECERSIZ_KULLANICI_ADI", "Kullanıcı adını kontrol et.")
    uid = user_id(raw)
    brief = profile_brief(uid)
    if brief is None or uid == user_id(BOT_USER):
        return {"found": False}
    brief["relation"] = contact_relation(me, uid)
    brief["found"] = True
    return brief


def contact_overview(headers, now):
    me = member_from_token(headers)
    rec = _crec(me)
    friends = []
    for uid in rec["friends"]:
        info = active_user(uid)
        if info is None:
            continue
        pub = public_profile(uid, now)
        friends.append({
            "user_id": uid,
            "username": username_of(uid),
            "display_name": info.get("displayname") or username_of(uid),
            "avatar_url": info.get("avatar_url") or "",
            "mode": pub["mode"],
            "status": pub["status"],
        })
    incoming = [b for b in (profile_brief(u) for u in rec["incoming"]) if b]
    outgoing = [b for b in (profile_brief(u) for u in rec["outgoing"]) if b]
    blocked = [b for b in (profile_brief(u) for u in rec["blocked"]) if b]
    return {"friends": friends, "incoming": incoming, "outgoing": outgoing, "blocked": blocked}


def contact_target(me, body):
    raw = body.get("user_id") or body.get("username") or ""
    raw = str(raw).strip().lower().lstrip("@").split(":")[0]
    if not USERNAME_RE.match(raw):
        raise ApiError(400, "GECERSIZ_KULLANICI", "Geçersiz kullanıcı.")
    other = user_id(raw)
    if other == me:
        raise ApiError(400, "GECERSIZ_KULLANICI", "Kendine istek gönderemezsin.")
    if active_user(other) is None or other == user_id(BOT_USER):
        raise ApiError(404, "KULLANICI_YOK", "Böyle bir kullanıcı yok.")
    return other


def contact_request(headers, body):
    me = member_from_token(headers)
    other = contact_target(me, body)
    with GIRIS.lock:
        mine, theirs = _crec(me), _crec(other)
        if me in theirs["blocked"]:
            # Karşı taraf beni engellemiş: isteği sessizce yut (belli etme).
            return {"ok": True, "relation": "outgoing"}
        if other in mine["blocked"]:
            raise ApiError(400, "ENGELLI", "Bu kullanıcıyı engelledin. Önce engeli kaldır.")
        if other in mine["friends"]:
            return {"ok": True, "relation": "friend"}
        if other in mine["incoming"]:
            # Karşılıklı istek: hemen arkadaş yap.
            _accept(me, other)
            return {"ok": True, "relation": "friend"}
        if other not in mine["outgoing"]:
            mine["outgoing"].append(other)
        if me not in theirs["incoming"]:
            theirs["incoming"].append(me)
        GIRIS.save()
    info = active_user(me)
    sender_name = (info.get("displayname") if info else None) or username_of(me)
    threading.Thread(target=push_contact, args=(other, sender_name), daemon=True).start()
    return {"ok": True, "relation": "outgoing"}


def _accept(me, other):
    """me, other'dan gelen isteği kabul eder (kilit çağıran tarafından tutulur)."""
    mine, theirs = _crec(me), _crec(other)
    for lst in (mine["incoming"], mine["outgoing"]):
        if other in lst:
            lst.remove(other)
    for lst in (theirs["incoming"], theirs["outgoing"]):
        if me in lst:
            lst.remove(me)
    if other not in mine["friends"]:
        mine["friends"].append(other)
    if me not in theirs["friends"]:
        theirs["friends"].append(me)
    GIRIS.save()


def contact_accept(headers, body):
    me = member_from_token(headers)
    other = contact_target(me, body)
    with GIRIS.lock:
        if other not in _crec(me)["incoming"] and other not in _crec(me)["friends"]:
            raise ApiError(404, "ISTEK_YOK", "Bu kullanıcıdan bekleyen bir istek yok.")
        _accept(me, other)
    return {"ok": True, "relation": "friend"}


def contact_block(headers, body):
    me = member_from_token(headers)
    other = contact_target(me, body)
    with GIRIS.lock:
        mine, theirs = _crec(me), _crec(other)
        for lst in ("friends", "incoming", "outgoing"):
            if other in mine[lst]:
                mine[lst].remove(other)
            if me in theirs[lst]:
                theirs[lst].remove(me)
        if other not in mine["blocked"]:
            mine["blocked"].append(other)
        GIRIS.save()
    return {"ok": True, "relation": "blocked"}


def contact_unblock(headers, body):
    me = member_from_token(headers)
    other = contact_target(me, body)
    with GIRIS.lock:
        if other in _crec(me)["blocked"]:
            _crec(me)["blocked"].remove(other)
            GIRIS.save()
    return {"ok": True, "relation": "none"}


def contact_reject(headers, body):
    """Geleni reddet, gönderileni geri çek ya da arkadaşlıktan çıkar — hepsi için aynı uç."""
    me = member_from_token(headers)
    other = contact_target(me, body)
    with GIRIS.lock:
        mine, theirs = _crec(me), _crec(other)
        for lst in (mine["friends"], mine["incoming"], mine["outgoing"]):
            if other in lst:
                lst.remove(other)
        for lst in (theirs["friends"], theirs["incoming"], theirs["outgoing"]):
            if me in lst:
                lst.remove(me)
        GIRIS.save()
    return {"ok": True, "relation": "none"}


def contact_forget(uid):
    """Üye silinince kişisini herkesten temizle."""
    with GIRIS.lock:
        _contacts().pop(uid, None)
        for rec in _contacts().values():
            for lst in ("friends", "incoming", "outgoing", "blocked"):
                if uid in rec.get(lst, []):
                    rec[lst].remove(uid)
        GIRIS.save()


_otp_lock = threading.Lock()
_otp_gen = {}


def username_of(uid):
    return uid[1:].split(":", 1)[0]


def set_password(uid, password):
    status, _ = synapse(
        "POST",
        "/_synapse/admin/v1/reset_password/" + quote(uid),
        {"new_password": password, "logout_devices": False},
    )
    return status == 200


def scramble(uid, gen=None):
    """Hesabın şifresini kimsenin bilmediği rastgele bir değere çevirir."""
    with _otp_lock:
        if gen is not None and _otp_gen.get(uid) != gen:
            return  # bu arada yeni bir tek seferlik şifre verilmiş
        _otp_gen.pop(uid, None)
    set_password(uid, secrets.token_urlsafe(32))


def issue_otp(uid, seconds=None, readable=False):
    """Kısa ömürlü tek seferlik şifre: uygulama bununla giriş yapar, sonra şifre yeniden karıştırılır."""
    otp = giris.group(giris.new_code(12)) if readable else secrets.token_urlsafe(24)
    if not set_password(uid, otp):
        raise ApiError(502, "SUNUCU_HATASI", "Oturum hazırlanamadı.")
    with _otp_lock:
        gen = _otp_gen.get(uid, 0) + 1
        _otp_gen[uid] = gen
    timer = threading.Timer(seconds or giris.OTP_SECONDS, scramble, args=(uid, gen))
    timer.daemon = True
    timer.start()
    return otp


def active_user(uid, allow_locked=False):
    status, info = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
    if status == 404 or (status == 200 and info.get("deactivated")):
        return None
    if status != 200:
        raise ApiError(502, "SUNUCU_HATASI", "Hesap kontrol edilemedi.")
    if info.get("locked") and not allow_locked:
        return None
    return info


def session_reply(uid, recovery=None):
    reply = {"status": "ok", "username": username_of(uid), "user_id": uid, "otp": issue_otp(uid)}
    if recovery:
        reply["recovery_code"] = giris.group(recovery)
    return reply


def register(body, ip):
    """Şifresiz kayıt. Davet kodu geçerliyse hesap hemen açılır; değilse yönetici onayı bekler."""
    username, display_name = validate_profile(body)
    invite = body.get("invite") or ""
    check_rate(ip)
    if invite and not GIRIS.invite_valid(invite):
        raise ApiError(400, "DAVET_GECERSIZ", "Davet kodu geçersiz ya da süresi dolmuş.")

    uid = user_id(username)
    with _register_lock:
        status, _ = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
        if status == 200:
            raise ApiError(409, "KULLANICI_ADI_ALINMIS", "Bu kullanıcı adı alınmış, başka bir tane dene.")
        if status != 404:
            raise ApiError(502, "SUNUCU_HATASI", "Kullanıcı adı kontrol edilemedi.")
        if len(list_members()) >= MAX_MEMBERS:
            raise ApiError(403, "KAPASITE_DOLU", "Üye sınırına ulaşıldı. Yeni kayıt alınamıyor.")
        invited_by = GIRIS.use_invite(invite) if invite else None
        if invite and not invited_by:
            raise ApiError(400, "DAVET_GECERSIZ", "Davet kodu geçersiz ya da süresi dolmuş.")
        locked = invited_by is None

        status, _ = synapse(
            "PUT",
            "/_synapse/admin/v2/users/" + quote(uid),
            {"password": secrets.token_urlsafe(32), "displayname": display_name, "admin": False, "locked": locked},
        )
        if status not in (200, 201):
            raise ApiError(502, "SUNUCU_HATASI", "Hesap oluşturulamadı.")
        if locked:
            # Kilidin gerçekten uygulandığından emin ol; uygulanmadıysa hesabı hemen kapat.
            synapse("PUT", "/_synapse/admin/v2/users/" + quote(uid), {"locked": True})
            status, data = synapse("GET", "/_synapse/admin/v2/users/" + quote(uid))
            if status != 200 or not data.get("locked"):
                synapse("POST", "/_synapse/admin/v1/deactivate/" + quote(uid), {"erase": True})
                raise ApiError(502, "SUNUCU_HATASI", "Hesap onay bekleyen duruma alınamadı.")
            return {"status": "pending", "username": username, "ticket": GIRIS.create_ticket(uid)}
    return session_reply(uid, GIRIS.new_recovery(uid))


def ticket_check(body):
    """Onay bekleyen kayıt: onaylandıysa oturum ve kurtarma kodu verir."""
    ticket = body.get("ticket")
    uid = GIRIS.ticket_user(ticket)
    if not uid:
        raise ApiError(404, "BILET_YOK", "Kayıt isteği bulunamadı.")
    info = active_user(uid, allow_locked=True)
    if info is None:
        GIRIS.drop_ticket(ticket)
        return {"status": "rejected"}
    if info.get("locked"):
        return {"status": "pending", "username": username_of(uid)}
    GIRIS.drop_ticket(ticket)
    return session_reply(uid, GIRIS.new_recovery(uid))


_recover_attempts = giris.Attempts(limit=8)
_code_attempts = giris.Attempts(limit=20)
_pair_attempts = giris.Attempts(limit=30)


def recover(body, ip):
    """Kurtarma koduyla giriş. Başarılı olunca eski kod geçersiz olur ve yenisi verilir."""
    username = body.get("username")
    if isinstance(username, str):
        username = username.strip().lower().lstrip("@").split(":", 1)[0]
    if not isinstance(username, str) or not USERNAME_RE.match(username):
        raise ApiError(400, "GECERSIZ_KULLANICI_ADI", "Kullanıcı adını kontrol et.")
    uid = user_id(username)
    if _recover_attempts.blocked("ip:" + ip) or _recover_attempts.blocked("u:" + uid):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla hatalı deneme. Bir saat sonra tekrar dene.")
    info = active_user(uid, allow_locked=True)
    if info is None or not GIRIS.check_recovery(uid, body.get("recovery_code")):
        _recover_attempts.fail("ip:" + ip)
        _recover_attempts.fail("u:" + uid)
        raise ApiError(403, "KOD_HATALI", "Kullanıcı adı ya da kurtarma kodu hatalı.")
    if info.get("locked"):
        raise ApiError(403, "ONAY_BEKLIYOR", "Hesabın henüz onaylanmadı.")
    return session_reply(uid, GIRIS.new_recovery(uid))


def redeem_link(body, ip):
    """Giriş kodu (başka cihazdan QR ya da yöneticinin giriş bağlantısı)."""
    if _code_attempts.blocked("ip:" + ip):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla hatalı deneme. Bir saat sonra tekrar dene.")
    uid = GIRIS.use_link(body.get("code"))
    info = active_user(uid) if uid else None
    if info is None:
        _code_attempts.fail("ip:" + ip)
        raise ApiError(403, "KOD_HATALI", "Giriş kodu geçersiz, kullanılmış ya da süresi dolmuş.")
    recovery = None if GIRIS.has_recovery(uid) else GIRIS.new_recovery(uid)
    return session_reply(uid, recovery)


def link_url(kind, code):
    return "%s/bayx/%s/%s" % (PUBLIC_BASE, kind, code)


def web_code(headers):
    """Bilgisayardaki Tayfa Web'e telefondan giriş: 5 dakikalık, tek kullanımlık kod."""
    member_from_token(headers)
    token = headers.get("Authorization", "")[7:].strip()
    status, data = synapse("POST", "/_matrix/client/v1/login/get_token", {}, token=token)
    if status != 200 or not data.get("login_token"):
        raise ApiError(502, "WEB_KAPALI", "Web girişi sunucuda açık değil. Sunucu komutunu yeniden çalıştır.")
    code = GIRIS.create_web_code(data["login_token"])
    return {"code": giris.group(code), "url": WEB_ORIGIN + "/giris.html" if WEB_ORIGIN else ""}


def web_redeem(body, ip):
    if _code_attempts.blocked("web:" + ip):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla hatalı deneme. Bir saat sonra tekrar dene.")
    token = GIRIS.use_web_code(body.get("code"))
    if not token:
        _code_attempts.fail("web:" + ip)
        raise ApiError(403, "KOD_HATALI", "Kod geçersiz ya da süresi dolmuş. Telefonda yeni kod al.")
    return {"login_token": token, "homeserver": PUBLIC_BASE}


# -------------------------------------------- WhatsApp Web tarzı cihaz bağlama
# Akış: Web açılır -> start (QR + 6 haneli kod) -> telefon QR okutur/kod girer
# -> approve (telefon oturumu web jetonunu basar) -> web poll ile jetonu alır.

def web_pair_start(ip):
    """Web tarafı: bekleyen bir eşleştirme başlatır, QR yükü + 6 haneli kod döndürür."""
    if _pair_attempts.blocked("start:" + ip):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla istek. Biraz sonra tekrar dene.")
    _pair_attempts.fail("start:" + ip)
    pairing = GIRIS.create_pairing()
    return {
        "pairing_id": pairing["pairing_id"],
        "code": pairing["code"],
        "qr": "tayfaweb:" + pairing["pairing_id"],
        "expires_in": giris.WEB_PAIR_SECONDS,
    }


def web_pair_approve(headers, body):
    """Telefon (giriş yapmış): QR'dan pairing_id veya elle 6 haneli kod ile onaylar."""
    uid = member_from_token(headers)
    if _pair_attempts.blocked("approve:" + uid):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla deneme. Biraz bekle.")
    pairing_id = body.get("pairing_id")
    raw = pairing_id if isinstance(pairing_id, str) else ""
    # QR yükü tüm olarak gelebilir ("tayfaweb:<id>").
    if raw.startswith("tayfaweb:"):
        raw = raw[len("tayfaweb:"):]
    if not raw:
        raw = GIRIS.pairing_by_code(str(body.get("code", ""))) or ""
    if not raw:
        _pair_attempts.fail("approve:" + uid)
        raise ApiError(404, "ESLESME_YOK", "Kod geçersiz ya da süresi dolmuş. Web'de yeni kod al.")
    token = headers.get("Authorization", "")[7:].strip()
    status, data = synapse("POST", "/_matrix/client/v1/login/get_token", {}, token=token)
    if status != 200 or not data.get("login_token"):
        raise ApiError(502, "WEB_KAPALI", "Web girişi sunucuda açık değil. Sunucu komutunu yeniden çalıştır.")
    if not GIRIS.approve_pairing(raw, data["login_token"]):
        _pair_attempts.fail("approve:" + uid)
        raise ApiError(404, "ESLESME_YOK", "Kod geçersiz ya da süresi dolmuş. Web'de yeni kod al.")
    return {"ok": True}


def web_pair_poll(body):
    """Web tarafı: eşleştirme onaylandıysa giriş jetonunu döndürür."""
    status, token = GIRIS.poll_pairing(body.get("pairing_id"))
    if status == "ready":
        return {"status": "ready", "login_token": token, "homeserver": PUBLIC_BASE}
    if status == "pending":
        return {"status": "pending"}
    return {"status": "gone"}


LANDING = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>Tayfa</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:linear-gradient(#fbf8f4,#f2ebe2);color:#1f1b18}
.k{background:#fff;border-radius:28px;box-shadow:0 18px 50px rgba(90,60,30,.12);padding:32px 26px;max-width:360px;width:86%%;text-align:center}
.l{width:84px;height:84px;border-radius:50%%;margin:0 auto 14px;background:radial-gradient(circle at 35%% 30%%,#2b3a63,#0b1224);
display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 4px #efe7dd}
.l span{width:44px;height:34px;border:3px solid #5fe3ff;border-radius:18px;box-shadow:0 0 12px #8b7bff}
h1{margin:6px 0 4px;font-size:26px}p{color:#6e655c;line-height:1.45}
a.b{display:block;margin:20px 0 10px;padding:15px;border-radius:18px;background:linear-gradient(#e2c3a1,#c99d74);
color:#2a1c10;text-decoration:none;font-weight:700;font-size:17px}
code{display:inline-block;margin-top:6px;padding:8px 14px;border-radius:12px;background:#f4ede5;font-size:20px;letter-spacing:2px}
</style></head><body><div class="k"><div class="l"><span></span></div><h1>Tayfa</h1><p>%(text)s</p>
<a class="b" href="intent://%(kind)s/%(code)s#Intent;scheme=tayfa;package=com.tayfa.chat;end">Tayfa'da aç</a>
<p style="font-size:14px">Açılmazsa uygulamada <b>%(label)s</b> alanına bu kodu yaz:<br><code>%(shown)s</code></p>
</div></body></html>"""


def landing(kind, code):
    clean = giris.normalize(code)
    if kind == "davet":
        text, label = "Tayfa'ya davet edildin. Uygulamayı açıp birkaç saniyede katılabilirsin.", "Davet kodu"
    else:
        text, label = "Bu bağlantı Tayfa hesabına giriş içindir. Kimseyle paylaşma.", "Giriş kodu"
    return LANDING % {"text": text, "kind": kind, "code": clean, "label": label, "shown": giris.group(clean)}


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
        visible = set(friends_of(uid))
        users = {u: public_profile(u, now) for u in visible}
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


def push_contact(target, sender_name):
    """Arkadaşlık isteği gelen kişiye bildirim gönderir (uygulaması kapalıyken de)."""
    if FCM is None:
        return
    status, data = synapse("GET", "/_synapse/admin/v1/users/%s/pushers" % quote(target))
    if status != 200:
        return
    payload = {"type": "contact_request", "name": sender_name or "Biri"}
    for pusher in data.get("pushers", []):
        if pusher.get("app_id") == APP_ID and pusher.get("pushkey"):
            FCM.send(pusher["pushkey"], payload)


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
    elif action == "login-link":
        if info.get("locked"):
            raise ApiError(400, "ONAY_BEKLIYOR", "Önce üyeliği onayla.")
        code = GIRIS.create_link(uid, giris.LINK_ADMIN_SECONDS)
        return {"ok": True, "user_id": uid, "code": giris.group(code), "url": link_url("giris", code), "hours": 24}
    else:
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    if status != 200:
        raise ApiError(502, "SUNUCU_HATASI", "İşlem tamamlanamadı.")
    if action in ("reject", "remove"):
        GIRIS.forget_user(uid)
        EMAILS.remove(uid)
        contact_forget(uid)
    return {"ok": True, "user_id": uid}


def create_invite(body, caller):
    try:
        uses = int(body.get("uses", 1))
        days = int(body.get("days", giris.INVITE_DAYS_DEFAULT))
    except (TypeError, ValueError):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
    code = GIRIS.create_invite(caller, uses, days)
    return {"code": giris.group(code), "url": link_url("davet", code), "uses": max(1, min(uses, 50)), "days": max(1, min(days, 30))}


class Handler(BaseHTTPRequestHandler):
    server_version = "BayxKayit/1"

    def log_message(self, fmt, *args):  # şifre içeren gövdeler asla loglanmaz; erişim logu da tutulmaz
        return

    def _cors(self):
        origin = self.headers.get("Origin", "")
        path = self.path.split("?", 1)[0].rstrip("/")
        web_paths = ("/bayx/v2/web/redeem", "/bayx/v3/web/start", "/bayx/v3/web/poll")
        if WEB_ORIGIN and origin == WEB_ORIGIN and path in web_paths:
            self.send_header("Access-Control-Allow-Origin", WEB_ORIGIN)
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Vary", "Origin")

    def _send(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        self.wfile.write(raw)

    def _html(self, text):
        raw = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
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
            elif method == "POST" and path == "/bayx/config":
                member_from_token(self.headers)
                self._send(200, get_config())
            elif method == "POST" and path == "/bayx/admin/config":
                require_admin(self.headers)
                self._send(200, set_config(self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts":
                self._send(200, contact_overview(self.headers, time.time()))
            elif method == "POST" and path == "/bayx/v3/contacts/find":
                self._send(200, contact_find(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts/request":
                self._send(200, contact_request(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts/accept":
                self._send(200, contact_accept(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts/reject":
                self._send(200, contact_reject(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts/block":
                self._send(200, contact_block(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/contacts/unblock":
                self._send(200, contact_unblock(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/register":
                self._send(200, register_simple(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v3/reset/request":
                self._send(200, reset_request(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v3/reset/confirm":
                self._send(200, reset_confirm(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v3/email/status":
                self._send(200, EMAILS.status(member_from_token(self.headers)))
            elif method == "POST" and path == "/bayx/v3/email/set":
                uid = member_from_token(self.headers)
                email = eposta.clean_email(self._body().get("email") or "")
                if email is None:
                    raise ApiError(400, "GECERSIZ_EPOSTA", "E-posta adresi geçersiz görünüyor.")
                if not email:
                    EMAILS.remove(uid)
                else:
                    error = EMAILS.set_email(uid, email)
                    if error:
                        raise ApiError(409, "EPOSTA_KULLANIMDA", error)
                self._send(200, EMAILS.status(uid))
            elif method == "POST" and path == "/bayx/v3/email/resend":
                uid = member_from_token(self.headers)
                error = EMAILS.resend(uid)
                if error:
                    raise ApiError(400, "GONDERILEMEDI", error)
                self._send(200, EMAILS.status(uid))
            elif method == "GET" and re.match(r"^/bayx/eposta/[A-Za-z0-9_-]{10,80}$", path):
                uid = EMAILS.verify_token(path.rsplit("/", 1)[1])
                if uid:
                    self._html(VERIFIED_PAGE % ("✅", "E-posta doğrulandı", "Teşekkürler! Artık şifreni unutursan e-postayla sıfırlayabilirsin."))
                else:
                    self._html(VERIFIED_PAGE % ("⌛", "Bağlantı geçersiz", "Bağlantının süresi dolmuş ya da zaten kullanılmış. Uygulamada Profil › E-posta bölümünden yenisini iste."))
            elif method == "POST" and path == "/bayx/v3/local/email":
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                body = self._body()
                uid = user_id(str(body.get("username", "")).strip().lower())
                email = eposta.clean_email(body.get("email") or "")
                if not email or active_user(uid) is None:
                    raise ApiError(400, "GECERSIZ_ISTEK", "Kullanıcı ya da e-posta geçersiz.")
                error = EMAILS.set_email(uid, email, verified=True, send=False)
                if error:
                    raise ApiError(409, "EPOSTA_KULLANIMDA", error)
                self._send(200, EMAILS.status(uid))
            elif method == "POST" and path == "/bayx/v3/local/password":
                # Sunucudaki tayfa-sifre-belirle komutu: yöneticinin şifresini sunucuda belirler.
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                body = self._body()
                uid = user_id(str(body.get("username", "")).strip().lower())
                validate_password(body.get("password"))
                if active_user(uid, allow_locked=True) is None:
                    raise ApiError(404, "KULLANICI_YOK", "Böyle bir üye yok.")
                if not set_password(uid, body["password"]):
                    raise ApiError(502, "SUNUCU_HATASI", "Şifre değiştirilemedi.")
                self._send(200, {"ok": True})
            elif method == "POST" and path == "/bayx/local/mailtest":
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                to = eposta.clean_email(self._body().get("to") or "")
                if not to:
                    raise ApiError(400, "GECERSIZ_EPOSTA", "Adres geçersiz.")
                ok, detail = EMAILS.mailer.send(to, "Tayfa e-posta denemesi", "Tayfa sunucusundan deneme e-postası. Ayarlar doğru!")
                self._send(200, {"ok": ok, "detail": detail})
            elif method == "POST" and path == "/bayx/v2/register":
                self._send(200, register(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v2/ticket":
                self._send(200, ticket_check(self._body()))
            elif method == "POST" and path == "/bayx/v2/recover":
                self._send(200, recover(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v2/link/redeem":
                self._send(200, redeem_link(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v2/link/new":
                uid = member_from_token(self.headers)
                code = GIRIS.create_link(uid, giris.LINK_SELF_SECONDS)
                self._send(200, {"code": giris.group(code), "url": link_url("giris", code), "minutes": 5})
            elif method == "POST" and path == "/bayx/v2/recovery/new":
                uid = member_from_token(self.headers)
                self._send(200, {"recovery_code": giris.group(GIRIS.new_recovery(uid))})
            elif method == "POST" and path == "/bayx/v2/seal":
                uid = member_from_token(self.headers)
                scramble(uid)
                self._send(200, {"ok": True})
            elif method == "POST" and path == "/bayx/v2/web/password":
                # Tayfa Web (Element) bazı güvenlik adımlarında hesap şifresi sorar: 5 dakikalık geçici şifre.
                uid = member_from_token(self.headers)
                self._send(200, {"password": issue_otp(uid, 300, readable=True), "minutes": 5})
            elif method == "POST" and path == "/bayx/v2/local/password":
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                uid = user_id(str(self._body().get("username", "")).strip().lower())
                if active_user(uid) is None:
                    raise ApiError(404, "KULLANICI_YOK", "Böyle onaylı bir üye yok.")
                self._send(200, {"password": issue_otp(uid, 300, readable=True), "minutes": 5})
            elif method == "POST" and path == "/bayx/v2/web/code":
                self._send(200, web_code(self.headers))
            elif method == "POST" and path == "/bayx/v2/web/redeem":
                self._send(200, web_redeem(self._body(), self._ip()))
            elif method == "POST" and path == "/bayx/v3/web/start":
                self._send(200, web_pair_start(self._ip()))
            elif method == "POST" and path == "/bayx/v3/web/approve":
                self._send(200, web_pair_approve(self.headers, self._body()))
            elif method == "POST" and path == "/bayx/v3/web/poll":
                self._send(200, web_pair_poll(self._body()))
            elif method == "POST" and path == "/bayx/v2/local/link":
                # Yalnızca sunucudaki tayfa-giris komutu çağırabilir (nginx üzerinden gelen istek reddedilir).
                if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Real-IP"):
                    raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
                body = self._body()
                uid = user_id(str(body.get("username", "")).strip().lower())
                if active_user(uid) is None:
                    raise ApiError(404, "KULLANICI_YOK", "Böyle onaylı bir üye yok.")
                code = GIRIS.create_link(uid, giris.LINK_LOCAL_SECONDS)
                self._send(200, {"code": giris.group(code), "url": link_url("giris", code), "minutes": 30})
            elif method == "GET" and re.match(r"^/bayx/(davet|giris)/[A-Za-z0-9-]{4,24}$", path):
                kind, code = path.split("/")[2:4]
                self._html(landing(kind, code))
            elif method == "GET" and path == "/bayx/members":
                caller = require_member(self.headers)
                now = time.time()
                fset = set(friends_of(caller))
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
                        if not m["pending"] and m["user_id"] in fset
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
                self._send(200, {"members": members, "count": len(members), "max": MAX_MEMBERS, "invites": GIRIS.invite_count()})
            elif method == "POST" and path == "/bayx/admin/invite":
                caller = require_admin(self.headers)
                self._send(200, create_invite(self._body(), caller))
            elif method == "POST" and path.startswith("/bayx/admin/"):
                caller = require_admin(self.headers)
                self._send(200, admin_action(path[len("/bayx/admin/"):], self._body(), caller))
            else:
                raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
        except ApiError as err:
            self._send(err.status, {"error": err.code, "message": err.message})
        except Exception:
            self._send(500, {"error": "SUNUCU_HATASI", "message": "Beklenmeyen bir hata oluştu."})

    def _ip(self):
        return self.headers.get("X-Real-IP") or self.client_address[0]

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


def main():
    global FCM, GIRIS, EMAILS
    load_profiles()
    GIRIS = giris.Store(os.path.join(STATE_DIR, "giris.json"))
    EMAILS = eposta.Emails(GIRIS, PUBLIC_BASE, eposta.Mailer())
    if FCM_CREDENTIALS and os.path.exists(FCM_CREDENTIALS):
        try:
            FCM = Fcm(FCM_CREDENTIALS)
        except Exception:
            FCM = None
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
