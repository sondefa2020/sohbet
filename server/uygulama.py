"""Tayfa kayıt/yönetim servisi — FastAPI (asenkron) uygulaması.

İş mantığı (kayıt, kişiler, durum, e-posta, web girişi, yönetim) kayit.py'deki fonksiyonlarda
durur; bu dosya yalnızca HTTP/WebSocket katmanıdır. Engelleyen işlemler (Synapse'e istek,
dosya yazma) iş parçacığı havuzunda, arama sinyalleşmesi ise tamamen asenkron yürür.

Çalıştırma:  uvicorn uygulama:app --host 127.0.0.1 --port 8010
"""

import asyncio
import json
import re
import time
from contextlib import asynccontextmanager

import anyio
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, Response
from starlette.concurrency import run_in_threadpool

import arama
import eposta
import giris
import kayit
from kayit import ApiError

HUB = arama.CallHub()

WEB_PATHS = ("/bayx/v2/web/redeem", "/bayx/v3/web/start", "/bayx/v3/web/poll", "/bayx/presence")


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
    if kayit.WEB_ORIGIN and origin == kayit.WEB_ORIGIN and path in WEB_PATHS:
        return {
            "Access-Control-Allow-Origin": kayit.WEB_ORIGIN,
            "Access-Control-Allow-Methods": "POST, OPTIONS",
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
    if host != "127.0.0.1" or request.headers.get("x-real-ip"):
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


@app.get("/bayx/health")
async def health(request: Request):
    return reply(request, {"ok": True, "engine": "fastapi"})


@app.post("/bayx/config")
async def config(request: Request):
    await sync(kayit.member_from_token, request.headers)
    return reply(request, kayit.get_config())


@app.post("/bayx/admin/config")
async def admin_config(request: Request):
    await sync(kayit.require_admin, request.headers)
    return reply(request, await sync(kayit.set_config, await body(request)))


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
    def run():
        kayit.require_admin(request.headers)
        listed = kayit.list_members()
        return {"members": listed, "count": len(listed), "max": kayit.MAX_MEMBERS, "invites": kayit.GIRIS.invite_count()}

    return reply(request, await sync(run))


@app.post("/bayx/admin/invite")
async def admin_invite(request: Request):
    data = await body(request)

    def run():
        return kayit.create_invite(data, kayit.require_admin(request.headers))

    return reply(request, await sync(run))


@app.post("/bayx/admin/{action}")
async def admin_action(request: Request, action: str):
    data = await body(request)

    def run():
        return kayit.admin_action(action, data, kayit.require_admin(request.headers))

    return reply(request, await sync(run))


# ------------------------------------------------------------------ arama


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
    if event["type"] == "invite" and not (kayit.are_friends(caller, to) or kayit.knows_number(caller, to)):
        # WhatsApp kuralı: yalnızca numarası rehberinde kayıtlı (ya da kişi listendeki) birini arayabilirsin.
        raise ApiError(403, "KISI_DEGIL", "Bu kişiyi arayabilmek için numarası rehberinde kayıtlı olmalı.")
    HUB.publish(to, event)
    if event["type"] == "invite":
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
