"""Tayfa e-posta: doğrulama bağlantısı ve şifre sıfırlama kodu.

E-posta isteğe bağlıdır. Kayıtta verilen adres hemen doğrulanmak zorunda değildir; kişiye
DOGRULAMA_GUN gün süre tanınır, uygulama bu süre boyunca hatırlatır. Şifre sıfırlama kodu
yalnızca DOĞRULANMIŞ adrese gönderilir (başkasının adresini yazan biri hesabı ele geçiremesin).

SMTP ayarları /etc/bayx/smtp.env dosyasından ortam değişkeni olarak gelir:
  BAYX_SMTP_HOST, BAYX_SMTP_PORT (587 STARTTLS / 465 SSL), BAYX_SMTP_USER, BAYX_SMTP_PASS,
  BAYX_SMTP_FROM (ör. "Tayfa <acioglu30@gmail.com>")
"""
import hashlib
import hmac
import os
import re
import secrets
import smtplib
import ssl
import threading
import time
from email.message import EmailMessage
from email.utils import formataddr, parseaddr

DOGRULAMA_GUN = 7
LINK_SECONDS = 7 * 86400
RESET_SECONDS = 15 * 60
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}$")


def clean_email(value):
    if not isinstance(value, str):
        return ""
    email = value.strip().lower()
    if not email:
        return ""
    return email if EMAIL_RE.match(email) and len(email) <= 254 else None


def _h(value):
    return hashlib.sha256(("tayfa-eposta:" + value).encode()).hexdigest()


class Mailer:
    def __init__(self):
        self.host = os.environ.get("BAYX_SMTP_HOST", "").strip()
        self.port = int(os.environ.get("BAYX_SMTP_PORT", "587") or 587)
        self.user = os.environ.get("BAYX_SMTP_USER", "").strip()
        self.password = os.environ.get("BAYX_SMTP_PASS", "").replace(" ", "").strip()
        sender = os.environ.get("BAYX_SMTP_FROM", "").strip() or self.user
        name, addr = parseaddr(sender)
        self.sender = formataddr((name or "Tayfa", addr or self.user))

    @property
    def ready(self):
        return bool(self.host and self.user and self.password)

    def send(self, to, subject, text, html=None):
        """True/False döndürür; hata ayrıntısı (şifre içermez) ikinci değer."""
        if not self.ready:
            return False, "SMTP ayarlı değil"
        msg = EmailMessage()
        msg["From"] = self.sender
        msg["To"] = to
        msg["Subject"] = subject
        msg.set_content(text)
        if html:
            msg.add_alternative(html, subtype="html")
        try:
            context = ssl.create_default_context()
            if self.port == 465:
                with smtplib.SMTP_SSL(self.host, self.port, context=context, timeout=20) as s:
                    s.login(self.user, self.password)
                    s.send_message(msg)
            else:
                with smtplib.SMTP(self.host, self.port, timeout=20) as s:
                    s.starttls(context=context)
                    s.login(self.user, self.password)
                    s.send_message(msg)
            return True, ""
        except Exception as e:  # noqa: BLE001
            return False, type(e).__name__ + ": " + str(e)[:200]


def _page(title, body_html, button=None):
    btn = ""
    if button:
        btn = (
            '<p style="margin:26px 0"><a href="%s" style="display:inline-block;padding:14px 26px;border-radius:16px;'
            'background:#d4ae86;color:#2a1c10;text-decoration:none;font-weight:700">%s</a></p>' % button
        )
    return (
        '<div style="font-family:Segoe UI,Roboto,Arial,sans-serif;background:#f8f3ed;padding:30px">'
        '<div style="max-width:460px;margin:auto;background:#fff;border-radius:24px;padding:30px;color:#1f1b18">'
        '<h2 style="margin:0 0 12px">%s</h2>%s%s'
        '<p style="color:#8c7f72;font-size:13px;margin-top:24px">Bu e-postayı sen istemediysen görmezden gelebilirsin.</p>'
        "</div></div>" % (title, body_html, btn)
    )


class Emails:
    """Kayıtlı e-postalar store.data['emails'] içinde: uid -> {email, verified, created, token}."""

    def __init__(self, store, public_base, mailer):
        self.store = store
        self.public_base = public_base
        self.mailer = mailer
        self.lock = threading.Lock()
        self.resets = {}  # uid -> {hash, expires, tries}
        self.last_sent = {}
        store.data.setdefault("emails", {})

    @property
    def table(self):
        return self.store.data.setdefault("emails", {})

    def owner_of(self, email, verified_only=False):
        for uid, rec in self.table.items():
            if rec.get("email") == email and (rec.get("verified") or not verified_only):
                return uid
        return None

    def status(self, uid):
        rec = self.table.get(uid)
        if not rec:
            return {"email": "", "verified": False, "days_left": 0, "mail_ready": self.mailer.ready}
        left = 0
        if not rec.get("verified"):
            left = max(0, int((rec.get("created", 0) + DOGRULAMA_GUN * 86400 - time.time()) // 86400) + 1)
        return {"email": rec["email"], "verified": bool(rec.get("verified")), "days_left": left, "mail_ready": self.mailer.ready}

    def set_email(self, uid, email, verified=False, send=True):
        """Adresi kaydeder (eski adresin yerine). Başkasının doğrulanmış adresiyse hata mesajı döndürür."""
        other = self.owner_of(email, verified_only=True)
        if other and other != uid:
            return "Bu e-posta başka bir hesapta kullanılıyor."
        token = secrets.token_urlsafe(24)
        with self.store.lock:
            self.table[uid] = {
                "email": email,
                "verified": bool(verified),
                "created": time.time(),
                "token": None if verified else _h(token),
                "token_exp": time.time() + LINK_SECONDS,
            }
            self.store.save()
        if send and not verified:
            self.send_verification(uid, email, token)
        return None

    def remove(self, uid):
        with self.store.lock:
            if self.table.pop(uid, None) is not None:
                self.store.save()

    def resend(self, uid):
        rec = self.table.get(uid)
        if not rec or rec.get("verified"):
            return "Doğrulanacak bir e-posta yok."
        now = time.time()
        if now - self.last_sent.get(uid, 0) < 60:
            return "Biraz bekle; doğrulama e-postası dakikada bir gönderilebilir."
        token = secrets.token_urlsafe(24)
        with self.store.lock:
            rec["token"] = _h(token)
            rec["token_exp"] = now + LINK_SECONDS
            self.store.save()
        return None if self.send_verification(uid, rec["email"], token) else "E-posta gönderilemedi."

    def send_verification(self, uid, email, token):
        self.last_sent[uid] = time.time()
        link = "%s/bayx/eposta/%s" % (self.public_base, token)
        name = uid[1:].split(":")[0]
        text = (
            "Merhaba %s,\n\nTayfa e-posta adresini doğrulamak için bu bağlantıyı aç:\n%s\n\n"
            "Doğrulanmış e-posta, şifreni unutursan sıfırlamanı sağlar. Bağlantı 7 gün geçerli." % (name, link)
        )
        html = _page(
            "E-postanı doğrula",
            "<p>Merhaba <b>%s</b>,</p><p>Tayfa hesabının e-posta adresini doğrulamak için düğmeye dokun. "
            "Doğrulanmış e-posta, şifreni unutursan sıfırlamanı sağlar.</p>" % name,
            (link, "E-postamı doğrula"),
        )
        ok, _ = self.mailer.send(email, "Tayfa: e-postanı doğrula", text, html)
        return ok

    def verify_token(self, token):
        if not isinstance(token, str) or len(token) > 80:
            return None
        h = _h(token)
        with self.store.lock:
            for uid, rec in self.table.items():
                if rec.get("token") and hmac.compare_digest(rec["token"], h):
                    if rec.get("token_exp", 0) < time.time():
                        return None
                    rec["verified"] = True
                    rec["token"] = None
                    self.store.save()
                    return uid
        return None

    # ----------------------------------------------------------- şifre sıfırlama

    def start_reset(self, uid):
        rec = self.table.get(uid)
        if not rec or not rec.get("verified"):
            return False
        now = time.time()
        with self.lock:
            prev = self.resets.get(uid)
            if prev and now - prev.get("sent", 0) < 60:
                return True
            code = "".join(secrets.choice("0123456789") for _ in range(6))
            self.resets[uid] = {"hash": _h(uid + code), "expires": now + RESET_SECONDS, "tries": 0, "sent": now}
        name = uid[1:].split(":")[0]
        text = (
            "Merhaba %s,\n\nTayfa şifre sıfırlama kodun: %s\n\nKod 15 dakika geçerli. "
            "Uygulamada Şifremi unuttum ekranına yaz." % (name, code)
        )
        html = _page(
            "Şifre sıfırlama kodun",
            "<p>Merhaba <b>%s</b>,</p><p>Uygulamada <b>Şifremi unuttum</b> ekranına bu kodu yaz:</p>"
            '<p style="font-size:34px;letter-spacing:8px;font-weight:700;margin:18px 0">%s</p>'
            "<p>Kod 15 dakika geçerli.</p>" % (name, code),
        )
        ok, _ = self.mailer.send(rec["email"], "Tayfa şifre sıfırlama kodu", text, html)
        return ok

    def check_reset(self, uid, code):
        now = time.time()
        with self.lock:
            entry = self.resets.get(uid)
            if not entry or entry["expires"] < now or entry["tries"] >= 5:
                return False
            entry["tries"] += 1
            if isinstance(code, str) and hmac.compare_digest(entry["hash"], _h(uid + code.strip())):
                del self.resets[uid]
                return True
            return False
