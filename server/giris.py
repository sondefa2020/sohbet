"""Tayfa şifresiz giriş.

Kullanıcılar hiçbir zaman şifre görmez ya da yazmaz. Her Matrix hesabının sunucunun bile
bilmediği rastgele bir şifresi vardır. Kişi giriş yapmak istediğinde (davet, kurtarma kodu ya
da giriş bağlantısı ile) bu modül hesabın şifresini kısa ömürlü, tek seferlik rastgele bir
değere çevirir ve onu yalnızca uygulamaya (TLS üzerinden) verir. Uygulama bununla giriş yapar,
ardından şifre yeniden bilinmeyen rastgele bir değere çevrilir (en geç 2 dakika içinde).

Saklanan her kod (davet, kurtarma, giriş bağlantısı, onay bileti) yalnızca özet (hash) olarak
tutulur.
"""
import hashlib
import hmac
import json
import os
import secrets
import threading
import time

# Karışmayan harf ve rakamlar (0/O, 1/I/L yok).
ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

OTP_SECONDS = 120
LINK_SELF_SECONDS = 5 * 60
LINK_ADMIN_SECONDS = 24 * 3600
LINK_LOCAL_SECONDS = 30 * 60
WEB_CODE_SECONDS = 5 * 60
INVITE_DAYS_DEFAULT = 7
PBKDF2_ROUNDS = 120_000


def new_code(length):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def group(code, size=4):
    return "-".join(code[i:i + size] for i in range(0, len(code), size))


def normalize(code):
    """Kullanıcının yazdığı kodu karşılaştırılabilir hale getirir (tire, boşluk, küçük harf)."""
    if not isinstance(code, str):
        return ""
    text = code.strip().upper().replace("-", "").replace(" ", "")
    text = text.replace("0", "O").replace("1", "I")
    return text if all(ch in ALPHABET for ch in text) else ""


def digest(code):
    return hashlib.sha256(("tayfa:" + code).encode()).hexdigest()


class Store:
    """giris.json: davetler, kurtarma kodları, giriş bağlantıları, onay biletleri."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.data = {"invites": {}, "recovery": {}, "links": {}, "tickets": {}, "web": {}, "emails": {}}
        try:
            with open(path) as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                for key in self.data:
                    if isinstance(loaded.get(key), dict):
                        self.data[key] = loaded[key]
        except (OSError, ValueError):
            pass

    def save(self):
        with self.lock:
            try:
                os.makedirs(os.path.dirname(self.path), exist_ok=True)
                tmp = self.path + ".yeni"
                fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w") as f:
                    json.dump(self.data, f)
                os.replace(tmp, self.path)
            except OSError:
                pass

    def prune(self):
        now = time.time()
        with self.lock:
            for key in ("invites", "links", "web"):
                table = self.data[key]
                for h in [h for h, v in table.items() if v.get("expires", 0) < now]:
                    del table[h]
            tickets = self.data["tickets"]
            for h in [h for h, v in tickets.items() if now - v.get("created", 0) > 30 * 86400]:
                del tickets[h]

    # -------------------------------------------------------------- davetler

    def create_invite(self, by, uses=1, days=INVITE_DAYS_DEFAULT):
        code = new_code(8)
        with self.lock:
            self.prune()
            self.data["invites"][digest(code)] = {
                "by": by,
                "uses": max(1, min(int(uses), 50)),
                "expires": time.time() + max(1, min(int(days), 30)) * 86400,
            }
            self.save()
        return code

    def invite_valid(self, code):
        code = normalize(code)
        if not code:
            return False
        with self.lock:
            self.prune()
            inv = self.data["invites"].get(digest(code))
            return bool(inv and inv.get("uses", 0) > 0)

    def use_invite(self, code):
        code = normalize(code)
        with self.lock:
            self.prune()
            h = digest(code)
            inv = self.data["invites"].get(h)
            if not inv or inv.get("uses", 0) <= 0:
                return None
            inv["uses"] -= 1
            if inv["uses"] <= 0:
                del self.data["invites"][h]
            self.save()
            return inv.get("by")

    def invite_count(self):
        with self.lock:
            self.prune()
            return sum(v.get("uses", 0) for v in self.data["invites"].values())

    # ------------------------------------------------------- kurtarma kodları

    def new_recovery(self, uid):
        code = new_code(16)
        salt = secrets.token_hex(16)
        h = hashlib.pbkdf2_hmac("sha256", code.encode(), bytes.fromhex(salt), PBKDF2_ROUNDS).hex()
        with self.lock:
            self.data["recovery"][uid] = {"salt": salt, "hash": h, "created": time.time()}
            self.save()
        return code

    def has_recovery(self, uid):
        with self.lock:
            return uid in self.data["recovery"]

    def check_recovery(self, uid, code):
        code = normalize(code)
        with self.lock:
            rec = self.data["recovery"].get(uid)
        if not rec or len(code) != 16:
            # Zamanlama farkı olmasın diye yine de hesapla.
            hashlib.pbkdf2_hmac("sha256", b"x", b"y" * 16, PBKDF2_ROUNDS)
            return False
        h = hashlib.pbkdf2_hmac("sha256", code.encode(), bytes.fromhex(rec["salt"]), PBKDF2_ROUNDS).hex()
        return hmac.compare_digest(h, rec["hash"])

    def forget_user(self, uid):
        with self.lock:
            self.data["recovery"].pop(uid, None)
            for key in ("links", "tickets"):
                table = self.data[key]
                for h in [h for h, v in table.items() if v.get("uid") == uid]:
                    del table[h]
            self.save()

    # ------------------------------------------------------ giriş bağlantıları

    def create_link(self, uid, seconds):
        code = new_code(8)
        with self.lock:
            self.prune()
            # Bir kişinin aynı anda en fazla 3 geçerli bağlantısı olsun.
            mine = sorted(
                ((h, v) for h, v in self.data["links"].items() if v.get("uid") == uid),
                key=lambda item: item[1].get("expires", 0),
            )
            for h, _ in mine[:-2] if len(mine) >= 3 else []:
                del self.data["links"][h]
            self.data["links"][digest(code)] = {"uid": uid, "expires": time.time() + seconds}
            self.save()
        return code

    def use_link(self, code):
        code = normalize(code)
        if len(code) != 8:
            return None
        with self.lock:
            self.prune()
            link = self.data["links"].pop(digest(code), None)
            if link:
                self.save()
            return link.get("uid") if link else None

    # ------------------------------------------------------------ onay biletleri

    def create_ticket(self, uid):
        ticket = secrets.token_urlsafe(32)
        with self.lock:
            self.data["tickets"][digest(ticket)] = {"uid": uid, "created": time.time()}
            self.save()
        return ticket

    def ticket_user(self, ticket):
        if not isinstance(ticket, str) or len(ticket) > 100:
            return None
        with self.lock:
            t = self.data["tickets"].get(digest(ticket))
            return t.get("uid") if t else None

    def drop_ticket(self, ticket):
        with self.lock:
            if self.data["tickets"].pop(digest(ticket), None) is not None:
                self.save()

    # ------------------------------------------------------------ web giriş kodu

    def create_web_code(self, login_token):
        code = new_code(8)
        with self.lock:
            self.prune()
            # Bellekte tutulur, dosyaya yazılmaz: 5 dakikalık bir giriş jetonu.
            self._web()[digest(code)] = {"token": login_token, "expires": time.time() + WEB_CODE_SECONDS}
        return code

    def use_web_code(self, code):
        code = normalize(code)
        if len(code) != 8:
            return None
        with self.lock:
            self.prune()
            entry = self._web().pop(digest(code), None)
            return entry.get("token") if entry and entry.get("expires", 0) > time.time() else None

    def _web(self):
        if not hasattr(self, "_web_codes"):
            self._web_codes = {}
        now = time.time()
        for h in [h for h, v in self._web_codes.items() if v["expires"] < now]:
            del self._web_codes[h]
        return self._web_codes


class Attempts:
    """Kod deneme sınırı: anahtar başına saatte belirli sayıda hatalı deneme."""

    def __init__(self, limit, window=3600):
        self.limit = limit
        self.window = window
        self.lock = threading.Lock()
        self.fails = {}

    def blocked(self, key):
        now = time.time()
        with self.lock:
            recent = [t for t in self.fails.get(key, []) if now - t < self.window]
            self.fails[key] = recent
            return len(recent) >= self.limit

    def fail(self, key):
        with self.lock:
            self.fails.setdefault(key, []).append(time.time())
            if len(self.fails) > 5000:
                self.fails.clear()
