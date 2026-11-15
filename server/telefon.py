"""Telefon numarasıyla kayıt/giriş (WhatsApp mantığı) ve SMS gönderimi.

SMS sağlayıcısı: oztekbayi.com (1-N gönderim, XML). Hesap bilgileri koda yazılmaz; sunucuda
/etc/bayx/sms.env dosyasında durur (tayfa-sms-ayarla komutu yazar):
    BAYX_SMS_KNO, BAYX_SMS_USER, BAYX_SMS_PASS, BAYX_SMS_SENDER, BAYX_SMS_URL (isteğe bağlı)
"""

import hashlib
import html
import os
import re
import secrets
import threading
import time
import urllib.parse
import urllib.request

SMS_URL = os.environ.get("BAYX_SMS_URL", "https://www.oztekbayi.com/panel/smsgonder1Npost.php")
SMS_KNO = os.environ.get("BAYX_SMS_KNO", "")
SMS_USER = os.environ.get("BAYX_SMS_USER", "")
SMS_PASS = os.environ.get("BAYX_SMS_PASS", "")
SMS_SENDER = os.environ.get("BAYX_SMS_SENDER", "")
DEFAULT_COUNTRY = os.environ.get("BAYX_PHONE_COUNTRY", "90")

CODE_SECONDS = 300  # SMS kodu 5 dakika geçerli
CODE_TRIES = 5  # bir koda en fazla 5 hatalı deneme
RESEND_SECONDS = 60  # aynı numaraya en erken 60 sn sonra yeni kod
HASH_SALT = "tayfa-rehber-v1:"


def sms_ready():
    return bool(SMS_KNO and SMS_USER and SMS_PASS and SMS_SENDER)


# ------------------------------------------------------------------ numara


def normalize(raw, country=DEFAULT_COUNTRY):
    """Numarayı ülke koduyla, yalnızca rakam olarak döndürür (ör. 905321234567); geçersizse None.

    Türkiye için: 0532..., 532..., +90 532..., 0090 532... hepsi aynı numaraya çevrilir.
    """
    if not isinstance(raw, str):
        return None
    s = raw.strip()
    plus = s.startswith("+")
    digits = re.sub(r"\D", "", s)
    if not digits:
        return None
    if plus:
        pass
    elif digits.startswith("00"):
        digits = digits[2:]
    elif country == "90" and len(digits) == 11 and digits.startswith("0"):
        digits = "90" + digits[1:]
    elif country == "90" and len(digits) == 10 and digits.startswith("5"):
        digits = "90" + digits
    elif digits.startswith("0"):
        digits = country + digits[1:]
    elif not digits.startswith(country):
        digits = country + digits
    if digits.startswith("90") and not re.match(r"^905\d{9}$", digits):
        # Türkiye'de yalnızca cep numaraları (5xx) SMS alabilir
        return None
    if not (8 <= len(digits) <= 15):
        return None
    return digits


def mask(phone):
    return "+" + phone[:2] + " " + "•" * max(0, len(phone) - 6) + " " + phone[-4:]


def contact_hash(phone):
    """Rehber eşleştirmesinde numaralar açık gönderilmez; uygulama bu özeti gönderir."""
    return hashlib.sha256((HASH_SALT + phone).encode()).hexdigest()


# ------------------------------------------------------------------ SMS


_TR = str.maketrans("ğĞşŞıİöÖüÜçÇ", "gGsSiIoOuUcC")
_ALLOWED = set(
    "1234567890QWERTYUIOPASDFGHJKLZXCVBNMqwertyuiopasdfghjklzxcvbnm"
    "$= /!#+%()?*-_@,.:;£'"
)


def clean_message(msg):
    """Sağlayıcının kabul ettiği karakterler (PHP örneğindeki cleanMsg ile aynı kural)."""
    msg = msg.translate(_TR)
    return "".join(ch for ch in msg if ch in _ALLOWED)


def _success(text):
    """Sağlayıcı yanıtı başarılı mı? Hatalar "2:Kullanici bulunamadi" gibi "kod:açıklama" biçiminde gelir."""
    if not text:
        return False
    low = text.lower()
    if any(w in low for w in ("bulunamadi", "hata", "error", "gecersiz", "yetersiz", "hatali", "yetkisiz", "basarisiz")):
        return False
    m = re.match(r"^\s*(-?\d+)\s*:", text)
    if m and m.group(1) not in ("1", "00"):
        return False  # yalnızca 1 (ya da 00) başarı kodu sayılır; diğer kodlar hata
    return True


def send_sms(phone, message):
    """SMS gönderir. (başarılı mı, sağlayıcı yanıtı) döner."""
    if not sms_ready():
        return False, "SMS ayarlı değil"
    xml = (
        "<sms>"
        "<kno>%s</kno><kulad>%s</kulad><sifre>%s</sifre><gonderen>%s</gonderen>"
        "<mesaj>%s</mesaj><numaralar>%s</numaralar><tur>Turkce</tur>"
        "</sms>"
    ) % tuple(
        html.escape(v, quote=False)
        for v in (SMS_KNO, SMS_USER, SMS_PASS, SMS_SENDER, clean_message(message), phone)
    )
    body = urllib.parse.urlencode({"data": xml}).encode()
    urls = [SMS_URL]
    if SMS_URL.startswith("https://"):
        urls.append("http://" + SMS_URL[len("https://"):])  # sağlayıcı https desteklemiyorsa
    last = ""
    for url in urls:
        try:
            req = urllib.request.Request(url, data=body, method="POST")
            req.add_header("Content-Type", "application/x-www-form-urlencoded")
            with urllib.request.urlopen(req, timeout=20) as r:
                text = r.read().decode("utf-8", "replace").strip()
            return _success(text), text[:200]
        except Exception as e:  # ağ hatası: bir sonraki adresi dene
            last = type(e).__name__
    return False, last


# ------------------------------------------------------------------ kodlar


class Codes:
    """SMS doğrulama kodları: numara başına tek, kısa ömürlü, deneme sınırlı."""

    def __init__(self):
        self.lock = threading.Lock()
        self.codes = {}  # numara -> {code_hash, expires, tries, sent}
        self.sends = {}  # anahtar -> [zaman, ...]

    def _recent(self, key, window):
        now = time.time()
        times = [t for t in self.sends.get(key, []) if now - t < window]
        self.sends[key] = times
        return times

    def can_send(self, phone, ip):
        """Gönderim sınırı: numaraya 60 sn'de 1 ve saatte 5, IP başına saatte 15."""
        with self.lock:
            now = time.time()
            rec = self.codes.get(phone)
            if rec and now - rec["sent"] < RESEND_SECONDS:
                return int(RESEND_SECONDS - (now - rec["sent"])) + 1
            if len(self._recent("n:" + phone, 3600)) >= 5 or len(self._recent("ip:" + ip, 3600)) >= 15:
                return -1
            return 0

    def new(self, phone, ip):
        code = "%06d" % secrets.randbelow(1000000)
        now = time.time()
        with self.lock:
            self.codes[phone] = {
                "hash": hashlib.sha256(code.encode()).hexdigest(),
                "expires": now + CODE_SECONDS,
                "tries": 0,
                "sent": now,
            }
            self.sends.setdefault("n:" + phone, []).append(now)
            self.sends.setdefault("ip:" + ip, []).append(now)
            if len(self.sends) > 20000:
                self.sends.clear()
        return code

    def drop(self, phone):
        with self.lock:
            self.codes.pop(phone, None)

    def check(self, phone, code):
        """'ok', 'wrong', 'expired' ya da 'locked' döner. Doğru kod bir kez kullanılır."""
        with self.lock:
            rec = self.codes.get(phone)
            if not rec or time.time() > rec["expires"]:
                self.codes.pop(phone, None)
                return "expired"
            if rec["tries"] >= CODE_TRIES:
                return "locked"
            given = hashlib.sha256(str(code).strip().encode()).hexdigest()
            if secrets.compare_digest(given, rec["hash"]):
                self.codes.pop(phone, None)
                return "ok"
            rec["tries"] += 1
            return "locked" if rec["tries"] >= CODE_TRIES else "wrong"
