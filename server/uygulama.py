"""Tayfa kayıt/yönetim servisi — FastAPI (asenkron) uygulaması.

İş mantığı (kayıt, kişiler, durum, e-posta, web girişi, yönetim) kayit.py'deki fonksiyonlarda
durur; bu dosya yalnızca HTTP/WebSocket katmanıdır. Engelleyen işlemler (Synapse'e istek,
dosya yazma) iş parçacığı havuzunda, arama sinyalleşmesi ise tamamen asenkron yürür.

Çalıştırma:  uvicorn uygulama:app --host 127.0.0.1 --port 8010
"""

import asyncio
import json
import os
import tempfile
import threading
import re
import time
from contextlib import asynccontextmanager

import anyio
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from starlette.concurrency import run_in_threadpool

import arama
import eposta
import giris
import kayit
from kayit import ApiError

HUB = arama.CallHub()

WEB_PATHS = ("/bayx/v2/web/redeem", "/bayx/v3/web/start", "/bayx/v3/web/poll", "/bayx/presence", "/bayx/config")


@asynccontextmanager
async def lifespan(app):
    # Synapse'e giden engelleyen istekler için geniş iş parçacığı havuzu (varsayılan 40).
    anyio.to_thread.current_default_thread_limiter().total_tokens = 200
    kayit.init()
    yield
    # Kapanırken bekleyen giriş kayıtları diske yazılsın.
    kayit.GIRIS.flush()


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


# ------------------------------------------------------------------ yardımcılar


def cors_headers(request: Request):
    origin = request.headers.get("origin", "")
    path = request.url.path.rstrip("/")
    # Tayfa Web: QR girişi, çevrimiçi bilgisi ve yönetim paneli (yönetim uçları ayrıca yönetici yetkisi ister).
    if kayit.WEB_ORIGIN and origin == kayit.WEB_ORIGIN and (path in WEB_PATHS or path.startswith("/bayx/admin/")):
        return {
            "Access-Control-Allow-Origin": kayit.WEB_ORIGIN,
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
            "Vary": "Origin",
        }
    return {}


def reply(request: Request, payload, status=200):
    headers = {"Cache-Control": "no-store", **cors_headers(request)}
    return JSONResponse(payload, status_code=status, headers=headers)


def html(text):
    return HTMLResponse(
        text,
        headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY"},
    )


async def body(request: Request, limit=kayit.MAX_BODY, optional=False):
    """JSON nesnesi gövdesi; eski sunucuyla aynı sınırlar ve hata mesajı."""
    raw = await request.body()
    if not raw:
        if optional:
            return {}
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
    if len(raw) > limit:
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
    try:
        data = json.loads(raw)
    except ValueError:
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
    if not isinstance(data, dict):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz istek.")
    return data


def client_ip(request: Request):
    return request.headers.get("x-real-ip") or (request.client.host if request.client else "")


def local_only(request: Request):
    """Yalnızca sunucunun kendisinden (komut satırı araçları, Synapse) gelen istekler."""
    host = request.client.host if request.client else ""
    proxied = request.headers.get("x-real-ip") or request.headers.get("x-forwarded-for") or request.headers.get("forwarded")
    if host != "127.0.0.1" or proxied:
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")


def sync(fn, *args):
    """Engelleyen iş mantığını iş parçacığı havuzunda çalıştırır."""
    return run_in_threadpool(fn, *args)


@app.exception_handler(ApiError)
async def api_error(request: Request, err: ApiError):
    return reply(request, {"error": err.code, "message": err.message}, err.status)


@app.exception_handler(Exception)
async def unexpected(request: Request, err: Exception):
    return reply(request, {"error": "SUNUCU_HATASI", "message": "Beklenmeyen bir hata oluştu."}, 500)


@app.exception_handler(404)
async def not_found(request: Request, err):
    return reply(request, {"error": "BULUNAMADI", "message": "Bulunamadı."}, 404)


@app.exception_handler(405)
async def not_allowed(request: Request, err):
    return reply(request, {"error": "BULUNAMADI", "message": "Bulunamadı."}, 404)


@app.options("/{path:path}")
async def options(request: Request, path: str):
    return Response(status_code=204, headers={"Content-Length": "0", **cors_headers(request)})


# ------------------------------------------------------------------ genel


# Sunucunun hangi sürümü çalıştırdığını görmek için (https://.../bayx/health).
SURUM = "2026.11.21-1"


@app.get("/bayx/health")
async def health(request: Request):
    return reply(request, {"ok": True, "engine": "fastapi", "surum": SURUM})


@app.post("/bayx/config")
async def config(request: Request):
    await sync(kayit.member_from_token, request.headers)
    return reply(request, kayit.get_config())


@app.post("/bayx/admin/config")
async def admin_config(request: Request):
    caller = await sync(kayit.require_admin, request.headers)
    data = await body(request)
    result = await sync(kayit.set_config, data)
    kayit.audit(caller, "config", "", ", ".join(sorted(k for k in data if k in ("announcement", "latest_version", "apk_url"))))
    return reply(request, result)


# ------------------------------------------------------------------ kişiler ve durum


@app.post("/bayx/v3/contacts")
async def contacts(request: Request):
    return reply(request, await sync(kayit.contact_overview, request.headers, time.time()))


CONTACT_ACTIONS = {
    "find": kayit.contact_find,
    "request": kayit.contact_request,
    "accept": kayit.contact_accept,
    "reject": kayit.contact_reject,
    "block": kayit.contact_block,
    "unblock": kayit.contact_unblock,
}


@app.post("/bayx/v3/contacts/{action}")
async def contact_action(request: Request, action: str):
    fn = CONTACT_ACTIONS.get(action)
    if fn is None:
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    return reply(request, await sync(fn, request.headers, await body(request)))


@app.post("/bayx/v3/status/list")
async def status_list(request: Request):
    return reply(request, await sync(kayit.status_list, request.headers))


STATUS_ACTIONS = {"add": kayit.status_add, "delete": kayit.status_delete, "seen": kayit.status_seen}


@app.post("/bayx/v3/status/{action}")
async def status_action(request: Request, action: str):
    fn = STATUS_ACTIONS.get(action)
    if fn is None:
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    return reply(request, await sync(fn, request.headers, await body(request)))


@app.get("/bayx/members")
async def members(request: Request):
    def build():
        caller = kayit.require_member(request.headers)
        now = time.time()
        fset = set(kayit.friends_of(caller))
        listed = kayit.list_members()
        with kayit._presence_lock:
            out = [
                {
                    "user_id": m["user_id"],
                    "display_name": m["display_name"],
                    "avatar_url": m["avatar_url"],
                    "admin": m["admin"],
                    "status": kayit._profiles.get(m["user_id"], {}).get("status", ""),
                    "mode": kayit.public_profile(m["user_id"], now)["mode"],
                }
                for m in listed
                if not m["pending"] and m["user_id"] in fset
            ]
        return {"members": out, "me": caller}

    return reply(request, await sync(build))


@app.post("/bayx/presence")
async def presence(request: Request):
    return reply(request, await sync(kayit.presence, request.headers, await body(request, optional=True)))


# ------------------------------------------------------------------ telefon (WhatsApp mantığı)


@app.post("/bayx/v4/phone/start")
async def phone_start(request: Request):
    return reply(request, await sync(kayit.phone_start, await body(request), client_ip(request)))


@app.post("/bayx/v4/phone/verify")
async def phone_verify(request: Request):
    return reply(request, await sync(kayit.phone_verify, request.headers, await body(request), client_ip(request)))


@app.post("/bayx/v4/phone/me")
async def phone_me(request: Request):
    return reply(request, await sync(kayit.phone_me, request.headers))


@app.post("/bayx/v4/username/check")
async def username_check(request: Request):
    return reply(request, await sync(kayit.handle_check, request.headers, await body(request)))


@app.post("/bayx/v4/username/set")
async def username_set(request: Request):
    return reply(request, await sync(kayit.handle_set, request.headers, await body(request)))


@app.post("/bayx/v4/account/delete")
async def account_delete(request: Request):
    return reply(request, await sync(kayit.account_delete, request.headers, await body(request)))


@app.post("/bayx/v4/profile/card")
async def profile_card(request: Request):
    return reply(request, await sync(kayit.profile_card, request.headers, await body(request)))


@app.get("/bayx/v4/privacy")
async def privacy_get(request: Request):
    return reply(request, await sync(kayit.privacy_get, request.headers))


@app.post("/bayx/v4/privacy")
async def privacy_set(request: Request):
    return reply(request, await sync(kayit.privacy_set, request.headers, await body(request)))


@app.get("/bayx/v4/privacy/allowed")
async def privacy_allowed(request: Request):
    return reply(request, await sync(kayit.reach_allowed, request.headers))


@app.post("/bayx/v4/transfer/start")
async def transfer_start(request: Request):
    return reply(request, await sync(kayit.transfer_start, request.headers, await body(request)))


@app.post("/bayx/v4/transfer/info")
async def transfer_info(request: Request):
    return reply(request, await sync(kayit.transfer_info, request.headers, await body(request)))


@app.post("/bayx/v4/transfer/send")
async def transfer_send(request: Request):
    return reply(request, await sync(kayit.transfer_send, request.headers, await body(request)))


@app.post("/bayx/v4/transfer/poll")
async def transfer_poll(request: Request):
    return reply(request, await sync(kayit.transfer_poll, request.headers, await body(request)))


# ------------------------------------------------------------------ sesli mesajı yazıya çevirme
# Ses yalnızca bu sunucuda, bellekte/geçici dosyada işlenir ve hemen silinir; hiçbir yere kaydedilmez.
_whisper = {"model": None, "error": None}
_whisper_lock = threading.Lock()
_transcribe_slots = threading.BoundedSemaphore(int(os.environ.get("TAYFA_WHISPER_PARALEL", "1")))
_transcribe_attempts = giris.Attempts(limit=60)


def _whisper_model():
    with _whisper_lock:
        if _whisper["model"] is None and _whisper["error"] is None:
            try:
                from faster_whisper import WhisperModel

                _whisper["model"] = WhisperModel(
                    os.environ.get("TAYFA_WHISPER_MODEL", "small"),
                    device="cpu",
                    compute_type="int8",
                    download_root=os.environ.get("TAYFA_WHISPER_DIR", "/var/lib/tayfa-whisper"),
                )
            except Exception as err:  # paket ya da model yoksa özellik kapalı sayılır
                _whisper["error"] = repr(err)
                print("yazıya çevirme kullanılamıyor: %r" % (err,), flush=True)
        return _whisper["model"]


def transcribe_audio(me, data):
    if _transcribe_attempts.blocked(me):
        raise ApiError(429, "COK_FAZLA_DENEME", "Çok fazla sesli mesaj çevirdin. Biraz sonra tekrar dene.")
    _transcribe_attempts.fail(me)
    model = _whisper_model()
    if model is None:
        raise ApiError(503, "CEVIRI_KAPALI", "Yazıya çevirme bu sunucuda henüz hazır değil.")
    if not _transcribe_slots.acquire(timeout=60):
        raise ApiError(503, "MESGUL", "Sunucu şu an meşgul. Biraz sonra tekrar dene.")
    try:
        try:
            audio = _decode_16k(data)
        except Exception as err:
            print("sesli mesaj çözülemedi: %r" % (err,), flush=True)
            raise ApiError(400, "SES_OKUNAMADI", "Bu sesli mesaj okunamadı.")
        if audio.size < 1600:  # 0,1 saniyeden kısa
            return {"text": "", "language": ""}
        try:
            segments, info = model.transcribe(audio, beam_size=1, vad_filter=True)
            text = " ".join(seg.text.strip() for seg in segments).strip()
            if not text:
                # Sessiz algılandıysa bir de ses ayıklama olmadan dene (kısık sesler için).
                segments, info = model.transcribe(audio, beam_size=1, vad_filter=False)
                text = " ".join(seg.text.strip() for seg in segments).strip()
        except Exception as err:
            print("yazıya çevirme hatası: %r" % (err,), flush=True)
            raise ApiError(500, "CEVIRI_HATASI", "Yazıya çevrilemedi. Biraz sonra tekrar dene.")
        return {"text": text, "language": getattr(info, "language", "")}
    finally:
        _transcribe_slots.release()


def _decode_16k(data):
    """Sesi 16 kHz tek kanal float32 diziye çevirir. faster-whisper'ın kendi okuyucusu yeni PyAV
    sürümlerinde çalışmıyor (metadata_errors), bu yüzden sesi burada kendimiz çözüyoruz."""
    import io

    import av
    import numpy as np

    out = []
    with av.open(io.BytesIO(data), mode="r") as container:
        stream = next(st for st in container.streams if st.type == "audio")
        res = av.audio.resampler.AudioResampler(format="s16", layout="mono", rate=16000)
        for frame in container.decode(stream):
            frame.pts = None
            for f in res.resample(frame) or []:
                out.append(f.to_ndarray().reshape(-1))
        for f in res.resample(None) or []:
            out.append(f.to_ndarray().reshape(-1))
    if not out:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(out).astype(np.float32) / 32768.0


@app.post("/bayx/v4/transcribe")
async def transcribe(request: Request):
    me = await sync(kayit.member_from_token, request.headers)
    data = await request.body()
    if not data or len(data) > 12 * 1024 * 1024:
        raise ApiError(400, "GECERSIZ_ISTEK", "Ses dosyası okunamadı.")
    return reply(request, await sync(transcribe_audio, me, data))


@app.post("/bayx/v4/contacts/match")
async def contacts_match(request: Request):
    return reply(request, await sync(kayit.contacts_match, request.headers, await body(request, 400_000)))


@app.post("/bayx/local/smstest")
async def local_smstest(request: Request):
    # Sunucudaki tayfa-sms-test komutu: ayarların doğru olup olmadığını dener.
    local_only(request)
    import telefon

    phone = telefon.normalize((await body(request)).get("phone") or "")
    if not phone:
        raise ApiError(400, "GECERSIZ_NUMARA", "Numara geçersiz.")
    ok, detail = await sync(telefon.send_sms, phone, "Tayfa SMS denemesi. Ayarlar dogru!")
    return reply(request, {"ok": ok, "detail": detail})


@app.post("/bayx/local/yonetici")
async def local_make_admin(request: Request):
    # Sunucudan çalıştırılır: telefon numarası ya da kullanıcı adıyla bir hesaba yönetim yetkisi verir/alır.
    local_only(request)
    data = await body(request)
    return reply(request, await sync(kayit.set_admin, str(data.get("kim") or ""), data.get("yetki", True) is not False))


# ------------------------------------------------------------------ hesap


@app.post("/bayx/v3/register")
async def register_simple(request: Request):
    return reply(request, await sync(kayit.register_simple, await body(request), client_ip(request)))


@app.post("/bayx/v3/reset/request")
async def reset_request(request: Request):
    return reply(request, await sync(kayit.reset_request, await body(request), client_ip(request)))


@app.post("/bayx/v3/reset/confirm")
async def reset_confirm(request: Request):
    return reply(request, await sync(kayit.reset_confirm, await body(request), client_ip(request)))


@app.post("/bayx/v3/email/status")
async def email_status(request: Request):
    uid = await sync(kayit.member_from_token, request.headers)
    return reply(request, await sync(kayit.EMAILS.status, uid))


@app.post("/bayx/v3/email/set")
async def email_set(request: Request):
    data = await body(request)

    def run():
        uid = kayit.member_from_token(request.headers)
        email = eposta.clean_email(data.get("email") or "")
        if email is None:
            raise ApiError(400, "GECERSIZ_EPOSTA", "E-posta adresi geçersiz görünüyor.")
        if not email:
            kayit.EMAILS.remove(uid)
        else:
            error = kayit.EMAILS.set_email(uid, email)
            if error:
                raise ApiError(409, "EPOSTA_KULLANIMDA", error)
        return kayit.EMAILS.status(uid)

    return reply(request, await sync(run))


@app.post("/bayx/v3/email/resend")
async def email_resend(request: Request):
    def run():
        uid = kayit.member_from_token(request.headers)
        error = kayit.EMAILS.resend(uid)
        if error:
            raise ApiError(400, "GONDERILEMEDI", error)
        return kayit.EMAILS.status(uid)

    return reply(request, await sync(run))


@app.get("/bayx/eposta/{token}")
async def email_verify(token: str):
    if not re.match(r"^[A-Za-z0-9_-]{10,80}$", token):
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    uid = await sync(kayit.EMAILS.verify_token, token)
    if uid:
        return html(kayit.VERIFIED_PAGE % ("✅", "E-posta doğrulandı", "Teşekkürler! Artık şifreni unutursan e-postayla sıfırlayabilirsin."))
    return html(kayit.VERIFIED_PAGE % ("⌛", "Bağlantı geçersiz", "Bağlantının süresi dolmuş ya da zaten kullanılmış. Uygulamada Profil › E-posta bölümünden yenisini iste."))


# ------------------------------------------------------- yalnızca sunucudan (komutlar)


@app.post("/bayx/v3/local/email")
async def local_email(request: Request):
    local_only(request)
    data = await body(request)

    def run():
        uid = kayit.user_id(str(data.get("username", "")).strip().lower())
        email = eposta.clean_email(data.get("email") or "")
        if not email or kayit.active_user(uid) is None:
            raise ApiError(400, "GECERSIZ_ISTEK", "Kullanıcı ya da e-posta geçersiz.")
        error = kayit.EMAILS.set_email(uid, email, verified=True, send=False)
        if error:
            raise ApiError(409, "EPOSTA_KULLANIMDA", error)
        return kayit.EMAILS.status(uid)

    return reply(request, await sync(run))


@app.post("/bayx/v3/local/password")
async def local_password(request: Request):
    # Sunucudaki tayfa-sifre-belirle komutu: yöneticinin şifresini sunucuda belirler.
    local_only(request)
    data = await body(request)

    def run():
        uid = kayit.user_id(str(data.get("username", "")).strip().lower())
        kayit.validate_password(data.get("password"))
        if kayit.active_user(uid, allow_locked=True) is None:
            raise ApiError(404, "KULLANICI_YOK", "Böyle bir üye yok.")
        if not kayit.set_password(uid, data["password"]):
            raise ApiError(502, "SUNUCU_HATASI", "Şifre değiştirilemedi.")
        return {"ok": True}

    return reply(request, await sync(run))


@app.post("/bayx/local/mailtest")
async def local_mailtest(request: Request):
    local_only(request)
    to = eposta.clean_email((await body(request)).get("to") or "")
    if not to:
        raise ApiError(400, "GECERSIZ_EPOSTA", "Adres geçersiz.")
    ok, detail = await sync(kayit.EMAILS.mailer.send, to, "Tayfa e-posta denemesi", "Tayfa sunucusundan deneme e-postası. Ayarlar doğru!")
    return reply(request, {"ok": ok, "detail": detail})


@app.post("/bayx/v2/local/password")
async def local_temp_password(request: Request):
    local_only(request)
    data = await body(request)

    def run():
        uid = kayit.user_id(str(data.get("username", "")).strip().lower())
        if kayit.active_user(uid) is None:
            raise ApiError(404, "KULLANICI_YOK", "Böyle onaylı bir üye yok.")
        return {"password": kayit.issue_otp(uid, 300, readable=True), "minutes": 5}

    return reply(request, await sync(run))


@app.post("/bayx/v2/local/link")
async def local_link(request: Request):
    # Yalnızca sunucudaki tayfa-giris komutu çağırabilir (nginx üzerinden gelen istek reddedilir).
    local_only(request)
    data = await body(request)

    def run():
        uid = kayit.user_id(str(data.get("username", "")).strip().lower())
        if kayit.active_user(uid) is None:
            raise ApiError(404, "KULLANICI_YOK", "Böyle onaylı bir üye yok.")
        code = kayit.GIRIS.create_link(uid, giris.LINK_LOCAL_SECONDS)
        return {"code": giris.group(code), "url": kayit.link_url("giris", code), "minutes": 30}

    return reply(request, await sync(run))


@app.post("/_matrix/push/v1/notify")
async def push_notify(request: Request):
    # Yalnızca aynı sunucudaki Synapse çağırabilir; nginx bu yolu dışarı açmaz.
    local_only(request)
    return reply(request, await sync(kayit.push_notify, await body(request, kayit.MAX_CALL_BODY)))


# ------------------------------------------------------------ eski giriş yolları


@app.post("/bayx/v2/register")
async def register_v2(request: Request):
    return reply(request, await sync(kayit.register, await body(request), client_ip(request)))


@app.post("/bayx/v2/ticket")
async def ticket(request: Request):
    return reply(request, await sync(kayit.ticket_check, await body(request)))


@app.post("/bayx/v2/recover")
async def recover(request: Request):
    return reply(request, await sync(kayit.recover, await body(request), client_ip(request)))


@app.post("/bayx/v2/link/redeem")
async def link_redeem(request: Request):
    return reply(request, await sync(kayit.redeem_link, await body(request), client_ip(request)))


@app.post("/bayx/v2/link/new")
async def link_new(request: Request):
    def run():
        uid = kayit.member_from_token(request.headers)
        code = kayit.GIRIS.create_link(uid, giris.LINK_SELF_SECONDS)
        return {"code": giris.group(code), "url": kayit.link_url("giris", code), "minutes": 5}

    return reply(request, await sync(run))


@app.post("/bayx/v2/recovery/new")
async def recovery_new(request: Request):
    def run():
        uid = kayit.member_from_token(request.headers)
        return {"recovery_code": giris.group(kayit.GIRIS.new_recovery(uid))}

    return reply(request, await sync(run))


@app.post("/bayx/v2/seal")
async def seal(request: Request):
    def run():
        kayit.scramble(kayit.member_from_token(request.headers))
        return {"ok": True}

    return reply(request, await sync(run))


# ------------------------------------------------------------------ web girişi


@app.post("/bayx/v2/web/password")
async def web_password(request: Request):
    # Tayfa Web bazı güvenlik adımlarında hesap şifresi sorar: 5 dakikalık geçici şifre.
    def run():
        uid = kayit.member_from_token(request.headers)
        return {"password": kayit.issue_otp(uid, 300, readable=True), "minutes": 5}

    return reply(request, await sync(run))


@app.post("/bayx/v2/web/code")
async def web_code(request: Request):
    return reply(request, await sync(kayit.web_code, request.headers))


@app.post("/bayx/v2/web/redeem")
async def web_redeem(request: Request):
    return reply(request, await sync(kayit.web_redeem, await body(request), client_ip(request)))


@app.post("/bayx/v3/web/start")
async def web_start(request: Request):
    return reply(request, await sync(kayit.web_pair_start, client_ip(request)))


@app.post("/bayx/v3/web/approve")
async def web_approve(request: Request):
    return reply(request, await sync(kayit.web_pair_approve, request.headers, await body(request)))


@app.post("/bayx/v3/web/poll")
async def web_poll(request: Request):
    return reply(request, await sync(kayit.web_pair_poll, await body(request)))


# ------------------------------------------------------------------ yönetim


@app.get("/bayx/admin/members")
async def admin_members(request: Request):
    return reply(request, await sync(kayit.admin_members_full, request.headers))


@app.get("/bayx/admin/stats")
async def admin_stats(request: Request):
    return reply(request, await sync(kayit.admin_stats, request.headers))


@app.get("/bayx/admin/audit")
async def admin_audit(request: Request):
    return reply(request, await sync(kayit.admin_audit, request.headers))


@app.post("/bayx/admin/invite")
async def admin_invite(request: Request):
    data = await body(request)

    def run():
        caller = kayit.require_admin(request.headers)
        result = kayit.create_invite(data, caller)
        kayit.audit(caller, "invite", "", "%s kullanım" % data.get("uses", 1))
        return result

    return reply(request, await sync(run))


@app.post("/bayx/admin/{action}")
async def admin_action(request: Request, action: str):
    data = await body(request)

    def run():
        return kayit.admin_action(action, data, kayit.require_admin(request.headers))

    return reply(request, await sync(run))


# ------------------------------------------------------------------ arama


# ------------------------------------------------------------------ davet linki: https://alan/t/<kullanıcı adı>
_INVITE_PAGE = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>Tayfa'da {name} ile konuş</title>
<style>
body{{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f7f3ee;color:#1f1b18;display:flex;min-height:100vh;align-items:center;justify-content:center}}
.k{{background:#fff;border-radius:24px;padding:32px 26px;max-width:380px;width:88%;text-align:center;box-shadow:0 10px 40px rgba(60,40,20,.08)}}
.a{{width:84px;height:84px;border-radius:50%;background:#dff3e6;color:#1e6b3a;font-size:38px;font-weight:700;display:flex;align-items:center;justify-content:center;margin:0 auto 14px}}
h1{{font-size:22px;margin:6px 0}} p{{color:#6b625a;line-height:1.5;font-size:15px}}
a.b{{display:block;margin:12px 0;padding:15px;border-radius:16px;text-decoration:none;font-weight:700;font-size:16px}}
.b1{{background:#c8956a;color:#1f1b18}} .b2{{background:#f1ebe4;color:#5a4636}}
small{{color:#9a8f85;display:block;margin-top:14px;line-height:1.5}}
</style></head><body><div class="k">
<div class="a">{initial}</div><h1>{name}</h1><p>seni <b>Tayfa</b>'da sohbet etmeye davet ediyor.</p>
<a class="b b1" href="tayfa://t/{handle}">Uygulamada aç</a>
<a class="b b2" href="/bayx/app/tayfa.apk">Tayfa'yı indir (Android)</a>
<small>Uygulamayı kurduktan sonra bu linke tekrar dokun; {name} ile sohbet doğrudan açılır.</small>
</div></body></html>"""

_INVITE_MISSING = """<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tayfa</title></head><body style="font-family:system-ui;background:#f7f3ee;text-align:center;padding:60px 20px">
<h2>Bu davet linki geçerli değil</h2><p>Kişi kullanıcı adını değiştirmiş olabilir.</p>
<p><a href="/bayx/app/tayfa.apk">Tayfa'yı indir</a></p></body></html>"""


_GIZLILIK = """Tayfa Gizlilik Politikası

Son güncelleme: Kasım 2026
Veri sorumlusu: Acıoğlu Records (acioglu30@gmail.com)

1. Hangi veriler işlenir
- Telefon numaran (hesabını doğrulamak ve seni tanıyanların seni bulabilmesi için).
- Kullanıcı adın, görünen adın, profil fotoğrafın ve "Hakkında" yazın.
- Çevrimiçi durumun (gizlilik ayarlarından kapatabilirsin).
- Bildirim gönderebilmek için cihazının bildirim anahtarı.
- İsteğe bağlı verdiğin e-posta adresin (yalnızca hesap kurtarma için).

2. Rehber
Rehber izni verirsen, numaralar telefonundan çıkmadan önce tek yönlü özetlenir (hash) ve yalnızca bu özetler, hangi kişilerin Tayfa kullandığını bulmak için sunucuyla karşılaştırılır. Rehberin, isimler ya da numaralar sunucuda saklanmaz.

3. Mesaj ve arama içeriği
Mesajların, fotoğraf, video, sesli mesajların ve aramaların uçtan uca şifrelidir; sunucu ya da Acıoğlu Records bunları okuyamaz veya dinleyemez. Doğrudan bağlantı kurulamayan aramalarda şifreli ses/görüntü sunucumuz üzerinden aktarılır ama çözülemez. Gelen medya telefonun galerisine kaydedilmez.

4. Sesli mesajı yazıya çevirme
Bu özelliği kullandığında yalnızca seçtiğin sesli mesaj, yazıya çevrilmek için kendi sunucumuza gönderilir; işlem sunucumuzda yapılır, üçüncü bir tarafa gönderilmez ve ses kaydı saklanmaz.

5. Bildirimler
Anlık bildirim için Google Firebase Cloud Messaging kullanılır. Bildirime mesaj içeriği konmaz.

6. Takip ve reklam
Uygulama reklam, analiz ya da üçüncü taraf takip aracı kullanmaz. Uygulama çökerse, teknik hata özeti yalnızca senin telefonunda tutulur ve sen istersen paylaşırsın.

7. Saklama ve silme
Uygulamada Ayarlar → Hesabımı sil ile hesabını istediğin an kalıcı olarak silebilirsin: adın, fotoğrafın, kullanıcı adın, numara eşleşmen ve kişi listen sunucudan kaldırılır. Uygulamaya erişemiyorsan https://sohbet.acioglu.tr/bayx/hesap-sil adresindeki yolu izle. Çıkış yaptığında bu cihazdaki oturum, mesaj ve önbellek silinir.

8. Haklarınız (KVKK)
6698 sayılı KVKK kapsamında verilerine erişme, düzeltilmesini ya da silinmesini isteme hakların vardır. Başvuru: acioglu30@gmail.com

9. Değişiklikler
Bu politika güncellenebilir; önemli değişiklikler uygulamada duyurulur."""


def _plain_page(title, text):
    import html as _h
    parts = []
    for para in text.split("\n\n"):
        lines = para.strip().split("\n")
        if re.match(r"^\d+\. ", lines[0]):
            parts.append("<h2>%s</h2>" % _h.escape(lines[0]))
            lines = lines[1:]
        if lines:
            parts.append("<p>%s</p>" % "<br>".join(_h.escape(x) for x in lines))
    body = "".join(parts)
    return HTMLResponse(
        "<!doctype html><html lang=tr><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
        "<title>%s</title><style>body{font-family:system-ui,sans-serif;max-width:720px;margin:0 auto;padding:24px;"
        "line-height:1.6;color:#2a1c10;background:#f8f4ef}h1{font-size:26px}h2{font-size:18px;margin-top:26px}</style>"
        "<body><h1>%s</h1>%s</body></html>" % (_h.escape(title), _h.escape(title), body)
    )


@app.get("/bayx/gizlilik")
async def gizlilik():
    text = _GIZLILIK.split("\n", 1)[1].strip()
    return _plain_page("Tayfa Gizlilik Politikası", text)


@app.get("/bayx/hesap-sil")
async def hesap_sil():
    return _plain_page("Tayfa hesabını silme", """Tayfa hesabını uygulamadan kalıcı olarak silebilirsin:

1. Uygulamada
Tayfa'yı aç → Ayarlar → Hesabımı sil → onay kutusuna SİL yaz → Hesabı sil.

2. Neler silinir
Adın, profil fotoğrafın, "Hakkında" yazın, kullanıcı adın, telefon numarası eşleşmen, kişi listen ve giriş kayıtların hemen silinir; tüm gruplardan çıkarılırsın. Uçtan uca şifreli mesajların zaten sunucuda okunabilir değildir; hesap kapanınca bu hesaba bir daha erişilemez. Güvenlik kayıtlarında yalnızca "hesap silindi" bilgisi kalır.

3. Uygulamaya erişemiyorsan
Telefonunu kaybettiysen ya da uygulamayı açamıyorsan, kayıtlı telefon numaranı yazarak acioglu30@gmail.com adresine "Hesabımı silin" diye yaz. Numaranın sana ait olduğunu SMS koduyla doğruladıktan sonra hesabın en geç 7 gün içinde silinir.""")


@app.get("/t/{name}")
async def invite_page(name: str):
    card = await sync(kayit.invite_card, name)
    if not card:
        return HTMLResponse(_INVITE_MISSING, status_code=404, headers={"Cache-Control": "no-store"})
    import html as _h

    shown = _h.escape(card["display_name"])
    return HTMLResponse(
        _INVITE_PAGE.format(name=shown, initial=_h.escape(card["display_name"][:1].upper() or "T"), handle=_h.escape(card["username"])),
        headers={"Cache-Control": "no-store", "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer"},
    )


# ------------------------------------------------------------------ uygulama güncellemesi
# En son APK sunucuda durur (tayfa-apk-guncelle indirir); telefonlar sürümü sorar ve buradan indirir.
APK_DIR = os.environ.get("BAYX_APK_DIR", "/var/lib/tayfa-apk")
_apk_info = {"mtime": 0.0, "data": None}


def apk_info():
    path = os.path.join(APK_DIR, "surum.json")
    try:
        mtime = os.path.getmtime(path)
        if mtime != _apk_info["mtime"]:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            _apk_info.update(mtime=mtime, data={
                "version_code": int(data.get("version_code", 0)),
                "version_name": str(data.get("version_name", ""))[:20],
                "sha256": str(data.get("sha256", ""))[:64],
                "size": int(data.get("size", 0)),
                "notes": str(data.get("notes", ""))[:600],
            })
        if _apk_info["data"] and os.path.exists(os.path.join(APK_DIR, "tayfa.apk")):
            return _apk_info["data"]
    except (OSError, ValueError):
        pass
    return None


@app.get("/bayx/app/latest")
async def app_latest(request: Request):
    info = apk_info()
    if not info:
        return reply(request, {"version_code": 0})
    return reply(request, dict(info, url=kayit.PUBLIC_BASE + "/bayx/app/tayfa.apk"))


@app.get("/bayx/app/tayfa.apk")
async def app_download(request: Request):
    if not apk_info():
        raise ApiError(404, "BULUNAMADI", "Güncelleme dosyası henüz sunucuda yok.")
    return FileResponse(
        os.path.join(APK_DIR, "tayfa.apk"),
        media_type="application/vnd.android.package-archive",
        filename="tayfa.apk",
    )


@app.get("/bayx/turn")
async def turn(request: Request):
    return reply(request, await sync(kayit.turn_server, request.headers))


def validate_signal(caller, data):
    """Arama sinyalini doğrular; geçerliyse (alıcı, olay) döndürür."""
    to = data.get("to")
    call_id = data.get("call_id")
    kind = data.get("type")
    payload = data.get("data") or {}
    if not isinstance(to, str) or not re.match(r"^@[a-z0-9._-]+:" + re.escape(kayit.DOMAIN) + "$", to) or to == caller:
        raise ApiError(400, "GECERSIZ_KULLANICI", "Geçersiz kullanıcı.")
    if not isinstance(call_id, str) or not re.match(r"^[A-Za-z0-9_-]{8,64}$", call_id):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz arama.")
    if kind not in arama.CALL_TYPES or not isinstance(payload, dict):
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz arama.")
    if len(json.dumps(payload)) > kayit.MAX_CALL_BODY:
        raise ApiError(400, "GECERSIZ_ISTEK", "Geçersiz arama.")
    return to, {"from": caller, "call_id": call_id, "type": kind, "data": payload}


def deliver(caller, data):
    to, event = validate_signal(caller, data)
    if event["type"] == "invite" and not kayit.can_reach(caller, to):
        # Engellendiysen ya da kişi yalnızca kişilerinden arama kabul ediyorsa arama gitmez.
        raise ApiError(403, "ULASILAMIYOR", "Bu kişi şu an yalnızca kişilerinden arama kabul ediyor.")
    HUB.publish(to, event)
    if event["type"] == "invite":
        kayit.stat_inc("calls")
        # Uygulaması kapalı olan aranana bildirim (Firebase): ağ isteği, arka planda.
        name = str(event["data"].get("name", ""))[:40]
        asyncio.get_running_loop().run_in_executor(
            None, kayit.push_call, to, caller, event["call_id"], name, bool(event["data"].get("video"))
        )


@app.post("/bayx/call/send")
async def call_send(request: Request):
    caller = await sync(kayit.member_from_token, request.headers)
    deliver(caller, await body(request, kayit.MAX_CALL_BODY))
    return reply(request, {"ok": True})


@app.post("/bayx/call/poll")
async def call_poll(request: Request):
    """Uzun yoklama (eski uygulamalar ve WebSocket'in kurulamadığı ağlar için)."""
    uid = await sync(kayit.member_from_token, request.headers)
    data = await body(request, kayit.MAX_CALL_BODY, optional=True)
    after = data.get("after")
    acked = isinstance(after, int) and not isinstance(after, bool)
    events = await HUB.poll(uid, after if acked else None)
    return reply(request, {"events": events})


@app.websocket("/bayx/call/ws")
async def call_ws(ws: WebSocket):
    """Anlık arama sinyali.

    İstemci -> sunucu:  {"after": N}  (N'ye kadar olanları aldım)
                         {"op": "send", "to": ..., "call_id": ..., "type": ..., "data": {...}, "ref": ...}
    Sunucu -> istemci:  {"events": [...]}     {"ok": ref} / {"error": ..., "ref": ...}
    """
    try:
        uid = await sync(kayit.member_from_token, ws.headers)
    except ApiError:
        await ws.close(code=4401)
        return
    try:
        acked = int(ws.query_params.get("after", "0"))
    except ValueError:
        acked = 0
    await ws.accept()
    sent = acked
    signal = HUB.subscribe(uid)

    async def sender():
        nonlocal sent
        while True:
            signal.clear()
            events = [e for e in HUB.pending(uid, acked) if e["id"] > sent]
            if events:
                await ws.send_json({"events": events})
                sent = events[-1]["id"]
                continue
            try:
                await asyncio.wait_for(signal.wait(), 30)
            except asyncio.TimeoutError:
                pass

    async def receiver():
        nonlocal acked, sent
        while True:
            msg = await ws.receive_json()
            if not isinstance(msg, dict):
                continue
            after = msg.get("after")
            if isinstance(after, int) and not isinstance(after, bool) and after > acked:
                acked = after
                HUB.pending(uid, acked)  # teyit edilenleri sil
            if msg.get("op") == "send":
                ref = msg.get("ref")
                try:
                    deliver(uid, msg)
                    await ws.send_json({"ok": ref})
                except ApiError as err:
                    await ws.send_json({"error": err.code, "ref": ref})
                except WebSocketDisconnect:
                    raise
                except Exception as err:  # beklenmeyen hata bağlantıyı düşürmesin
                    print("arama sinyali hatası: %r" % (err,), flush=True)
                    await ws.send_json({"error": "SUNUCU_HATASI", "ref": ref})

    tasks = [asyncio.create_task(sender()), asyncio.create_task(receiver())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.exception()  # bağlantı kopması beklenen bir durum; hatayı tüket
    finally:
        for task in tasks:
            task.cancel()
        HUB.unsubscribe(uid, signal)


# Genel kalıp: en sonda tanımlanır ki /bayx/admin/members gibi belirli yolları gölgelemesin.
@app.get("/bayx/{kind}/{code}")
async def landing(kind: str, code: str):
    if kind not in ("davet", "giris") or not re.match(r"^[A-Za-z0-9-]{4,24}$", code):
        raise ApiError(404, "BULUNAMADI", "Bulunamadı.")
    return html(kayit.landing(kind, code))
