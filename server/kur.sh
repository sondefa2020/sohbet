#!/usr/bin/env bash
# Bayx sunucu kurulumu (Ubuntu 24.04 + Panelica).
#
# Kurduğu şeyler:
#   1. Matrix Synapse sohbet sunucusu (resmi apt deposundan), yalnızca 127.0.0.1:8008'de dinler.
#   2. Bayx kayıt/yönetim servisi (127.0.0.1:8010): yeni hesaplar yönetici onayına kadar kilitli kalır.
#   3. Panelin nginx'inde yalnızca sohbet alt alan adı için yönlendirme.
#
# Dokunmadığı şeyler: diğer siteler, panelin kendi ayarları, güvenlik duvarı.
# Tekrar çalıştırmak güvenlidir; var olan gizli anahtarları ve hesapları korur.
set -euo pipefail

DOMAIN="${BAYX_DOMAIN:-sohbet.acioglu.tr}"
NGINX_BIN="${BAYX_NGINX_BIN:-/opt/panelica/services/nginx-customer/sbin/nginx}"
NGINX_CONF="${BAYX_NGINX_CONF:-/opt/panelica/etc/nginx-customer/nginx.conf}"
INCLUDE_DIR="${BAYX_INCLUDE_DIR:-/home/acioglu/acioglu.tr/.system/conf/subdomains}"
INCLUDE_FILE="$INCLUDE_DIR/$DOMAIN.conf.bayx"
RAW_BASE="${BAYX_RAW_BASE:-https://raw.githubusercontent.com/sondefa2020/sohbet/main/server}"
# Dosyaların hepsi aynı sürümden insin: GitHub "main" adresini birkaç dakika önbellekte tutar ve
# yeni bir güncellemeden hemen sonra dosyaların bir kısmı eski gelebilir. Bu yüzden en son
# sürümün kimliği (commit) öğrenilip bütün dosyalar o kimlikle indirilir.
if [ -z "${BAYX_RAW_BASE:-}" ]; then
    LATEST_SHA="$(curl -fsSL -m 20 -H 'Accept: application/vnd.github.sha' \
        https://api.github.com/repos/sondefa2020/sohbet/commits/main 2>/dev/null | tr -cd '0-9a-f' || true)"
    if [ "${#LATEST_SHA}" -eq 40 ]; then
        RAW_BASE="https://raw.githubusercontent.com/sondefa2020/sohbet/$LATEST_SHA/server"
    fi
fi
BOT_USER="bayx-yonetim"
SYNAPSE_URL="http://127.0.0.1:8008"
SYNAPSE_CONF="/etc/matrix-synapse/conf.d/bayx.yaml"
ENV_FILE="/etc/bayx/kayit.env"

say()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mHATA: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "Bu komut root olarak çalıştırılmalı."
[ -x "$NGINX_BIN" ] || fail "Panelin nginx'i bulunamadı: $NGINX_BIN"
[ -f "$NGINX_CONF" ] || fail "Panelin nginx ayarı bulunamadı: $NGINX_CONF"
# Panel bu klasörü yalnızca özel ayar eklendiğinde oluşturur; yoksa var olan en yakın
# üst klasörün sahipliğiyle oluştur.
if [ ! -d "$INCLUDE_DIR" ]; then
    ANCESTOR="$INCLUDE_DIR"
    while [ ! -d "$ANCESTOR" ]; do ANCESTOR="$(dirname "$ANCESTOR")"; done
    case "$ANCESTOR" in
        /home/*/*) ;;
        *) fail "Alan adı klasörü bulunamadı: $INCLUDE_DIR (var olan en yakın klasör: $ANCESTOR)" ;;
    esac
    mkdir -p "$INCLUDE_DIR"
    NEW_TOP="$INCLUDE_DIR"
    while [ "$(dirname "$NEW_TOP")" != "$ANCESTOR" ]; do NEW_TOP="$(dirname "$NEW_TOP")"; done
    chown -R --reference="$ANCESTOR" "$NEW_TOP"
    chmod -R u=rwX,go=rX "$NEW_TOP"
fi

# Not: "set -o pipefail" altında "grep -q" kullanılmaz; erken çıkıp borunun başını öldürür.
port_in_use() { ss -tln 2>/dev/null | grep -E "[:.]$1 " >/dev/null; }
unit_exists() { systemctl cat "$1" >/dev/null 2>&1; }

if ! unit_exists matrix-synapse.service; then
    port_in_use 8008 && fail "8008 portu başka bir program tarafından kullanılıyor."
fi
if ! unit_exists bayx-kayit.service; then
    port_in_use 8010 && fail "8010 portu başka bir program tarafından kullanılıyor."
fi
if ! unit_exists coturn.service; then
    port_in_use 3478 && fail "3478 portu (arama aktarma) başka bir program tarafından kullanılıyor."
fi
PUBLIC_IP="${BAYX_PUBLIC_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')}"
[ -n "$PUBLIC_IP" ] || fail "Sunucunun IPv4 adresi bulunamadı (BAYX_PUBLIC_IP ile verilebilir)."

# ---------------------------------------------------------------- 1. Synapse
say "Sohbet sunucusu (Matrix Synapse) kuruluyor"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates openssl python3 debconf-utils >/dev/null
if [ ! -f /usr/share/keyrings/matrix-org-archive-keyring.gpg ]; then
    curl -fsSL -o /usr/share/keyrings/matrix-org-archive-keyring.gpg \
        https://packages.matrix.org/debian/matrix-org-archive-keyring.gpg
fi
. /etc/os-release
echo "deb [signed-by=/usr/share/keyrings/matrix-org-archive-keyring.gpg] https://packages.matrix.org/debian/ ${VERSION_CODENAME} main" \
    > /etc/apt/sources.list.d/matrix-org.list
apt-get update -qq
echo "matrix-synapse-py3 matrix-synapse/server-name string $DOMAIN" | debconf-set-selections
echo "matrix-synapse-py3 matrix-synapse/report-stats boolean false" | debconf-set-selections
apt-get install -y -qq matrix-synapse-py3 >/dev/null

if [ -f "$SYNAPSE_CONF" ]; then
    REG_SECRET="$(sed -n 's/^registration_shared_secret: "\(.*\)"$/\1/p' "$SYNAPSE_CONF")"
fi
REG_SECRET="${REG_SECRET:-$(openssl rand -hex 32)}"
if [ -f "$SYNAPSE_CONF" ]; then
    TURN_SECRET="$(sed -n 's/^turn_shared_secret: "\(.*\)"$/\1/p' "$SYNAPSE_CONF")"
fi
TURN_SECRET="${TURN_SECRET:-$(openssl rand -hex 32)}"

umask 077
cat > "$SYNAPSE_CONF" <<EOF
# Bayx ayarları (kur.sh tarafından yazılır)
public_baseurl: "https://$DOMAIN/"
enable_registration: false
registration_shared_secret: "$REG_SECRET"
allow_guest_access: false
# Özel sunucu: başka Matrix sunucularıyla bağlantı kapalı.
federation_domain_whitelist: []
trusted_key_servers: []
suppress_key_server_warning: true
report_stats: false
url_preview_enabled: false
max_upload_size: 100M
# Çevrimiçi bilgisini Tayfa kendi servisiyle tutar; Synapse'in ağır presence sistemi kapalı
# (kalabalık sunucularda en çok yük getiren kısımlardan biri).
presence:
  enabled: false
# Önbellek: kullanıcı sayısı arttıkça veritabanına daha az gidilsin.
caches:
  global_factor: 2.0
  expire_caches: true
  cache_entry_ttl: 30m
# Sesli/görüntülü arama için TURN aktarma sunucusu (coturn, aynı makinede)
turn_uris:
  - "turn:$DOMAIN:3478?transport=udp"
  - "turn:$DOMAIN:3478?transport=tcp"
turn_shared_secret: "$TURN_SECRET"
turn_user_lifetime: 86400000
turn_allow_guests: false
# Bildirimler aynı makinedeki Bayx servisine (127.0.0.1:8010) iletilir.
ip_range_whitelist:
  - "127.0.0.1"
# Kaybolan mesajlar: sohbet için süre belirlenmişse süresi dolan mesajlar sunucudan silinir.
retention:
  enabled: true
  allowed_lifetime_min: 1m
  allowed_lifetime_max: 365d
  purge_jobs:
    - longest_max_lifetime: 2d
      interval: 15m
    - shortest_max_lifetime: 2d
      interval: 6h
# Herkese açık oda dizini yok: her şey kişi onayına dayalı, odalar listede görünmez.
room_list_publication_rules:
  - action: deny
# Telefondan Tayfa Web'e giriş: oturum açık telefon 5 dakikalık tek seferlik giriş jetonu alabilir.
login_via_existing_session:
  enabled: true
  require_ui_auth: false
  token_timeout: "5m"
EOF
chown matrix-synapse:matrix-synapse "$SYNAPSE_CONF"
chmod 600 "$SYNAPSE_CONF"
umask 022

systemctl enable matrix-synapse >/dev/null 2>&1 || true
systemctl restart matrix-synapse

say "Sohbet sunucusunun açılması bekleniyor"
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$SYNAPSE_URL/health" 2>/dev/null; then break; fi
    sleep 2
done
curl -fsS -o /dev/null "$SYNAPSE_URL/health" || fail "Synapse açılmadı. Günlük: journalctl -u matrix-synapse -n 50"

# ------------------------------------------------- 2. Kayıt/yönetim servisi
say "Kayıt ve yönetim servisi kuruluyor"
id bayx >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin bayx
install -d -m 755 /opt/bayx
install -d -m 750 -o root -g bayx /etc/bayx
BAYX_FILES="kayit.py giris.py eposta.py arama.py uygulama.py telefon.py"
# SMS (telefonla kayıt) ayarları: tayfa-sms-ayarla yazar; yoksa boş dosya (servis yine açılır).
[ -f /etc/bayx/sms.env ] || install -m 640 -o root -g bayx /dev/null /etc/bayx/sms.env
for f in $BAYX_FILES; do
    curl -fsSL -o "/opt/bayx/$f.yeni" "$RAW_BASE/$f" || fail "$f indirilemedi."
    python3 -m py_compile "/opt/bayx/$f.yeni" || fail "$f bozuk indi."
done
for f in $BAYX_FILES; do
    mv "/opt/bayx/$f.yeni" "/opt/bayx/$f"
    chmod 644 "/opt/bayx/$f"
done
rm -rf /opt/bayx/__pycache__

# Modern asenkron sunucu (FastAPI + uvicorn) kendi sanal ortamında; sistem Python'una dokunmaz.
# Kurulamazsa (ör. internet sorunu) eski yerleşik sunucuyla çalışmaya devam eder.
VENV=/opt/bayx/venv
FASTAPI_OK=0
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv >/dev/null 2>&1 || true
if [ ! -x "$VENV/bin/python" ]; then python3 -m venv "$VENV" >/dev/null 2>&1 || true; fi
if [ -x "$VENV/bin/pip" ] && "$VENV/bin/pip" install -q --upgrade "fastapi==0.115.*" "uvicorn[standard]==0.32.*" >/dev/null 2>&1 \
    && "$VENV/bin/python" -c "import fastapi, uvicorn" >/dev/null 2>&1; then
    FASTAPI_OK=1
    KAYIT_EXEC="$VENV/bin/uvicorn uygulama:app --app-dir /opt/bayx --host 127.0.0.1 --port 8010 --no-access-log --log-level warning --ws-max-size 131072 --timeout-graceful-shutdown 5"
else
    KAYIT_EXEC="/usr/bin/python3 /opt/bayx/kayit.py"
fi

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1],""))' "$1"; }

if [ ! -s "$ENV_FILE" ]; then
    BOT_PASS="$(openssl rand -hex 24)"
    register_new_matrix_user -u "$BOT_USER" -p "$BOT_PASS" -a -c "$SYNAPSE_CONF" "$SYNAPSE_URL" >/dev/null \
        || fail "Yönetim hesabı oluşturulamadı."
    BOT_TOKEN="$(curl -fsS -X POST "$SYNAPSE_URL/_matrix/client/v3/login" \
        -H 'Content-Type: application/json' \
        -d "{\"type\":\"m.login.password\",\"identifier\":{\"type\":\"m.id.user\",\"user\":\"$BOT_USER\"},\"password\":\"$BOT_PASS\",\"initial_device_display_name\":\"bayx-kayit\"}" \
        | json_field access_token)"
    [ -n "$BOT_TOKEN" ] || fail "Yönetim hesabı için oturum alınamadı."
    umask 077
    cat > "$ENV_FILE" <<EOF
BAYX_DOMAIN=$DOMAIN
BAYX_ADMIN_TOKEN=$BOT_TOKEN
BAYX_BOT_USER=$BOT_USER
BAYX_MAX_MEMBERS=50
EOF
    umask 022
    chown root:bayx "$ENV_FILE"
    chmod 640 "$ENV_FILE"
    unset BOT_PASS BOT_TOKEN
fi

set_env() {
    if grep -E "^$1=" "$ENV_FILE" >/dev/null; then
        sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
    else
        echo "$1=$2" >> "$ENV_FILE"
    fi
}
set_env BAYX_APP_ID com.tayfa.chat
WEB_DOMAIN="${BAYX_WEB_DOMAIN:-web.acioglu.tr}"
set_env BAYX_PUBLIC_BASE "https://$DOMAIN"
set_env BAYX_WEB_ORIGIN "https://$WEB_DOMAIN"
# Telefonla kayıt açık: eski 50 kişilik sınırı 5000'e çıkar (elle değiştirildiyse dokunma).
grep -qE '^BAYX_MAX_MEMBERS=50$' "$ENV_FILE" && set_env BAYX_MAX_MEMBERS 5000

# SMS ayarı (telefonla kayıt): sudo tayfa-sms-ayarla — bilgiler yalnızca bu sunucuda saklanır.
cat > /usr/local/bin/tayfa-sms-ayarla <<'SMSCMD'
#!/bin/bash
# Tayfa: SMS sağlayıcısı (oztekbayi) bilgilerini sunucuya kaydeder. Şifre ekranda görünmez.
set -eu
[ "$(id -u)" = 0 ] || { echo "sudo ile çalıştır: sudo tayfa-sms-ayarla"; exit 1; }
read -r -p "Kullanıcı no (kno): " KNO
read -r -p "Kullanıcı adı: " KUL
read -r -s -p "Şifre (ekranda görünmez): " SIF; echo
read -r -p "Gönderen başlığı (ör. TAYFA): " GON
for v in "$KNO" "$KUL" "$SIF" "$GON"; do
    [ -n "$v" ] || { echo "Boş bırakılamaz."; exit 1; }
    case "$v" in *$'\n'*|*"'"*) echo "Geçersiz karakter (tek tırnak kullanılamaz)."; exit 1;; esac
done
umask 027
# Değerler tek tırnak içinde: "H. UYSAL" gibi boşluklu başlıklar ve özel karakterli şifreler bozulmaz.
cat > /etc/bayx/sms.env <<EOF
BAYX_SMS_KNO='$KNO'
BAYX_SMS_USER='$KUL'
BAYX_SMS_PASS='$SIF'
BAYX_SMS_SENDER='$GON'
EOF
chown root:bayx /etc/bayx/sms.env
chmod 640 /etc/bayx/sms.env
unset SIF
systemctl restart bayx-kayit
echo "Kaydedildi. Denemek için: sudo tayfa-sms-test 05xxxxxxxxx"
SMSCMD
chmod 755 /usr/local/bin/tayfa-sms-ayarla

cat > /usr/local/bin/tayfa-sms-test <<'SMSTEST'
#!/bin/bash
# Tayfa: SMS ayarını dener: sudo tayfa-sms-test 05xxxxxxxxx
[ -n "${1:-}" ] || { echo "Kullanım: sudo tayfa-sms-test 05xxxxxxxxx"; exit 1; }
curl -sS -X POST http://127.0.0.1:8010/bayx/local/smstest -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$(printf %s "$1" | tr -cd '0-9+')\"}"; echo
SMSTEST
chmod 755 /usr/local/bin/tayfa-sms-test

# Sunucudan giriş bağlantısı üretme komutu: sudo tayfa-giris <kullanıcı_adı>
cat > /usr/local/bin/tayfa-giris <<'GIRISCMD'
#!/bin/bash
# Tayfa: bir üye için 30 dakikalık, tek kullanımlık giriş bağlantısı üretir.
set -euo pipefail
U="${1:-}"
if [ -z "$U" ] && [ -f /etc/bayx/yonetici-olusturuldu ]; then U="$(cat /etc/bayx/yonetici-olusturuldu)"; fi
[ -n "$U" ] || { echo "Kullanım: sudo tayfa-giris <kullanıcı_adı>"; exit 1; }
OUT="$(curl -fsS -X POST http://127.0.0.1:8010/bayx/v2/local/link -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\"}" 2>/dev/null)" || { echo "Bağlantı üretilemedi: kullanıcı adı yanlış ya da hesap onaylı değil."; exit 1; }
echo "$OUT" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("Giriş bağlantısı (30 dk, tek kullanımlık): " + d["url"])
print("ya da uygulamada Giriş kodu: " + d["code"])
print("Bu bilgiyi kimseyle paylaşma; ekran görüntüsü alırsan bu satırları gizle.")'
GIRISCMD
chmod 755 /usr/local/bin/tayfa-giris

# Tayfa Web şifre sorarsa: sudo tayfa-sifre <kullanıcı_adı>  (5 dakikalık geçici şifre)
cat > /usr/local/bin/tayfa-sifre <<'SIFRECMD'
#!/bin/bash
set -euo pipefail
U="${1:-}"
if [ -z "$U" ] && [ -f /etc/bayx/yonetici-olusturuldu ]; then U="$(cat /etc/bayx/yonetici-olusturuldu)"; fi
[ -n "$U" ] || { echo "Kullanım: sudo tayfa-sifre <kullanıcı_adı>"; exit 1; }
OUT="$(curl -fsS -X POST http://127.0.0.1:8010/bayx/v2/local/password -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\"}" 2>/dev/null)" || { echo "Şifre üretilemedi: kullanıcı adı yanlış ya da hesap onaylı değil."; exit 1; }
echo "$OUT" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("Geçici şifre (5 dakika): " + d["password"])
print("Tayfa Web şifre sorduğunda bunu yaz (tireler dahil). 5 dakika sonra kendiliğinden geçersiz olur.")'
SIFRECMD
chmod 755 /usr/local/bin/tayfa-sifre

# Sunucuda bir üyenin şifresini belirle: sudo tayfa-sifre-belirle [kullanıcı_adı]
cat > /usr/local/bin/tayfa-sifre-belirle <<'BELIRLECMD'
#!/bin/bash
set -euo pipefail
U="${1:-}"
if [ -z "$U" ] && [ -f /etc/bayx/yonetici-olusturuldu ]; then U="$(cat /etc/bayx/yonetici-olusturuldu)"; fi
[ -n "$U" ] || { echo "Kullanım: sudo tayfa-sifre-belirle <kullanıcı_adı>"; exit 1; }
read -r -s -p "$U için yeni şifre (en az 8 karakter): " P1; echo
read -r -s -p "Yeni şifre (tekrar): " P2; echo
[ "$P1" = "$P2" ] || { echo "Şifreler aynı değil."; exit 1; }
BODY="$(U="$U" P="$P1" python3 -c 'import json,os; print(json.dumps({"username": os.environ["U"], "password": os.environ["P"]}))')"
if curl -fsS -o /dev/null -X POST http://127.0.0.1:8010/bayx/v3/local/password -H 'Content-Type: application/json' -d "$BODY"; then
    echo "Şifre belirlendi. Uygulamada kullanıcı adı: $U"
else
    echo "Şifre belirlenemedi (en az 8 karakter olmalı; kullanıcı adı doğru mu?)."
fi
BELIRLECMD
chmod 755 /usr/local/bin/tayfa-sifre-belirle

# E-posta ayarını dene: sudo tayfa-eposta-test adres@ornek.com
cat > /usr/local/bin/tayfa-eposta-test <<'MAILTEST'
#!/bin/bash
set -euo pipefail
TO="${1:-}"
[ -n "$TO" ] || { echo "Kullanım: sudo tayfa-eposta-test adres@ornek.com"; exit 1; }
systemctl restart bayx-kayit; sleep 2
BODY="$(TO="$TO" python3 -c 'import json,os; print(json.dumps({"to": os.environ["TO"]}))')"
curl -fsS -X POST http://127.0.0.1:8010/bayx/local/mailtest -H 'Content-Type: application/json' -d "$BODY" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("Deneme e-postası gönderildi; gelen kutunu kontrol et." if d["ok"] else "Gönderilemedi: " + d["detail"])'
MAILTEST
chmod 755 /usr/local/bin/tayfa-eposta-test

# E-posta (doğrulama ve şifre sıfırlama): SMTP bilgileri sunucuda elle girilir (gizli şifre içerir).
if [ ! -f /etc/bayx/smtp.env ]; then
    umask 077
    cat > /etc/bayx/smtp.env <<'SMTPENV'
# Tayfa e-posta ayarları. Doldurduktan sonra: sudo systemctl restart bayx-kayit
# Gmail için: 2 adımlı doğrulama açık olmalı; https://myaccount.google.com/apppasswords
# adresinden "Tayfa" adıyla 16 haneli uygulama şifresi oluştur ve BAYX_SMTP_PASS'a yaz.
BAYX_SMTP_HOST=smtp.gmail.com
BAYX_SMTP_PORT=587
BAYX_SMTP_USER=acioglu30@gmail.com
BAYX_SMTP_PASS=
BAYX_SMTP_FROM=Tayfa <acioglu30@gmail.com>
SMTPENV
    umask 022
fi
chown root:bayx /etc/bayx/smtp.env
chmod 640 /etc/bayx/smtp.env
if grep -E "^BAYX_SMTP_PASS=.+" /etc/bayx/smtp.env >/dev/null; then
    MAIL_OK="ayarlı (denemek için: sudo tayfa-eposta-test adres@ornek.com)"
else
    MAIL_OK="hayır (/etc/bayx/smtp.env içine SMTP şifresi yazılmalı)"
fi
if grep -E "^BAYX_SMS_PASS=.+" /etc/bayx/sms.env >/dev/null 2>&1; then
    SMS_OK="ayarlı (denemek için: sudo tayfa-sms-test 05xxxxxxxxx)"
else
    SMS_OK="hayır (telefonla kayıt için: sudo tayfa-sms-ayarla)"
fi

# Bildirimler: Firebase servis hesabı dosyası sunucuya elle konur (gizli anahtar içerir).
PUSH_OK="hayır (/etc/bayx/firebase.json yok)"
if [ -s /etc/bayx/firebase.json ]; then
    if python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["type"]=="service_account" and d["private_key"] and d["client_email"]' /etc/bayx/firebase.json 2>/dev/null; then
        chown root:bayx /etc/bayx/firebase.json
        chmod 640 /etc/bayx/firebase.json
        set_env BAYX_FCM_CREDENTIALS /etc/bayx/firebase.json
        PUSH_OK="evet"
    else
        PUSH_OK="hayır (/etc/bayx/firebase.json bir servis hesabı anahtarı değil)"
    fi
fi

cat > /etc/systemd/system/bayx-kayit.service <<EOF
[Unit]
Description=Bayx kayit ve yonetim servisi
After=network.target matrix-synapse.service

[Service]
User=bayx
Group=bayx
EnvironmentFile=$ENV_FILE
EnvironmentFile=-/etc/bayx/smtp.env
EnvironmentFile=-/etc/bayx/sms.env
ExecStart=$KAYIT_EXEC
WorkingDirectory=/opt/bayx
Restart=on-failure
RestartSec=3
# Binlerce eşzamanlı bağlantı (arama sinyali, yoklama) için dosya sınırı
LimitNOFILE=65535
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
StateDirectory=bayx
Environment=BAYX_STATE_DIR=/var/lib/bayx

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable bayx-kayit >/dev/null 2>&1 || true
systemctl restart bayx-kayit
KAYIT_UP=0
for _ in $(seq 1 20); do
    if curl -fsS -o /dev/null http://127.0.0.1:8010/bayx/health 2>/dev/null; then KAYIT_UP=1; break; fi
    sleep 1
done
if [ "$KAYIT_UP" = 0 ] && [ "$FASTAPI_OK" = 1 ]; then
    # Yeni sunucu açılmadıysa eski yerleşik sunucuya dön: servis kesintisiz kalsın.
    FASTAPI_OK=0
    sed -i "s|^ExecStart=.*|ExecStart=/usr/bin/python3 /opt/bayx/kayit.py|" /etc/systemd/system/bayx-kayit.service
    systemctl daemon-reload
    systemctl restart bayx-kayit
    sleep 2
fi
curl -fsS -o /dev/null http://127.0.0.1:8010/bayx/health || fail "Kayıt servisi açılmadı. Günlük: journalctl -u bayx-kayit -n 50"
if [ "$FASTAPI_OK" = 1 ]; then
    KAYIT_ENGINE="FastAPI (asenkron, anlık arama sinyali)"
else
    KAYIT_ENGINE="yerleşik Python sunucusu (FastAPI kurulamadı)"
fi

# ------------------------------------------- 2b. Arama aktarma sunucusu (TURN)
say "Arama aktarma sunucusu (coturn) kuruluyor"
apt-get install -y -qq coturn >/dev/null
if [ -f /etc/turnserver.conf ] && ! head -1 /etc/turnserver.conf | grep -E "^# Bayx" >/dev/null; then
    cp /etc/turnserver.conf "/etc/turnserver.conf.yedek-$(date +%Y%m%d%H%M%S)"
fi
umask 077
cat > /etc/turnserver.conf <<TURNCONF
# Bayx/Tayfa arama aktarma ayarları (kur.sh tarafından yazılır)
listening-port=3478
external-ip=$PUBLIC_IP
realm=$DOMAIN
use-auth-secret
static-auth-secret=$TURN_SECRET
min-port=49160
max-port=49200
fingerprint
no-tls
no-dtls
no-cli
no-multicast-peers
no-software-attribute
user-quota=12
total-quota=600
syslog
# Aktarma sunucusu iç ağlara erişemesin
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=::1
TURNCONF
umask 022
chown root:turnserver /etc/turnserver.conf 2>/dev/null || true
chmod 640 /etc/turnserver.conf
if [ -f /etc/default/coturn ]; then
    sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
fi
systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn
systemctl restart matrix-synapse
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$SYNAPSE_URL/health" 2>/dev/null; then break; fi
    sleep 2
done

FIREWALL_NOTE=""
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -E "^Status: active" >/dev/null; then
    ufw allow 3478/udp >/dev/null
    ufw allow 3478/tcp >/dev/null
    ufw allow 49160:49200/udp >/dev/null
    FIREWALL_NOTE="ufw: 3478 TCP/UDP ve 49160-49200 UDP portları açıldı."
else
    FIREWALL_NOTE="Panelde ya da sağlayıcıda güvenlik duvarı varsa şu portları aç: 3478 TCP ve UDP, 49160-49200 UDP."
fi

# ------------------------------------------------- 2c. Tayfa Web (tarayıcı)
# Tayfa Web, ayrı bir alt alan adında yayınlanır (güvenlik için sohbet
# sunucusundan ayrı bir adreste olmalı). Alt alan adı panelde oluşturulmamışsa atlanır.
WEB_DIR="${BAYX_WEB_DIR:-/home/acioglu/acioglu.tr/subdomains/${WEB_DOMAIN%%.*}/public_html}"
WEB_OK="hayır (panelde $WEB_DOMAIN alt alan adı yok)"
if [ -d "$WEB_DIR" ]; then
    say "Tayfa Web kuruluyor ($WEB_DOMAIN)"
    # Tayfa'nın kendi web uygulaması (uçtan uca şifreli, QR ile giriş). Element Web'in yerini alır.
    WEB_TMP="$(mktemp -d)"
    curl -fsSL -o "$WEB_TMP/tayfa-web.tar.gz" "$RAW_BASE/web/tayfa-web.tar.gz" || fail "Tayfa Web indirilemedi."
    tar -xzf "$WEB_TMP/tayfa-web.tar.gz" -C "$WEB_TMP"
    WEB_SRC="$WEB_TMP/tayfa-web"
    [ -f "$WEB_SRC/index.html" ] || fail "Tayfa Web paketi beklenen yapıda değil."
    WEB_TAG="$(cat "$WEB_SRC/.surum" 2>/dev/null || echo yeni)"
    if [ -n "$(ls -A "$WEB_DIR" 2>/dev/null)" ] && [ ! -f "$WEB_DIR/.tayfa-web" ]; then
        mkdir -p "$WEB_DIR/../public_html.yedek"
        cp -a "$WEB_DIR/." "$WEB_DIR/../public_html.yedek/"
    fi
    find "$WEB_DIR" -mindepth 1 -delete
    cp -a "$WEB_SRC/." "$WEB_DIR/"
    printf '{ "homeserver": "https://%s" }\n' "$DOMAIN" > "$WEB_DIR/config.json"
    echo "tayfa-web $WEB_TAG" > "$WEB_DIR/.tayfa-web"
    chown -R --reference="$(dirname "$WEB_DIR")" "$WEB_DIR"
    find "$WEB_DIR" -type d -exec chmod 755 {} +
    find "$WEB_DIR" -type f -exec chmod 644 {} +
    rm -rf "$WEB_TMP"
    WEB_OK="evet (https://$WEB_DOMAIN, Tayfa Web $WEB_TAG)"
fi

# ------------------------------------------------------ 3. Yönetici hesabı
ADMIN_NOTE=""
if [ ! -f /etc/bayx/yonetici-olusturuldu ]; then
    say "Yönetici hesabı"
    ADMIN_USER="${BAYX_ADMIN_USER:-}"
    while ! [[ "$ADMIN_USER" =~ ^[a-z0-9][a-z0-9._-]{2,19}$ ]]; do
        read -r -p "Yönetici kullanıcı adı (küçük harf/rakam, 3-20 karakter): " ADMIN_USER
    done
    # Şifresiz: hesabın şifresi rastgeledir ve kimseye gösterilmez; giriş bağlantıyla yapılır.
    register_new_matrix_user -u "$ADMIN_USER" -p "$(openssl rand -hex 32)" -a -c "$SYNAPSE_CONF" "$SYNAPSE_URL" >/dev/null \
        || fail "Yönetici hesabı oluşturulamadı (bu kullanıcı adı alınmış olabilir)."
    echo "$ADMIN_USER" > /etc/bayx/yonetici-olusturuldu
fi
if [ -f /etc/bayx/yonetici-olusturuldu ]; then
    ADMIN_U="$(cat /etc/bayx/yonetici-olusturuldu)"
    ADMIN_EMAIL="${BAYX_ADMIN_EMAIL:-acioglu30@gmail.com}"
    BODY="$(U="$ADMIN_U" E="$ADMIN_EMAIL" python3 -c 'import json,os; print(json.dumps({"username": os.environ["U"], "email": os.environ["E"]}))')"
    curl -fsS -o /dev/null -X POST http://127.0.0.1:8010/bayx/v3/local/email -H 'Content-Type: application/json' -d "$BODY" || true
    ADMIN_NOTE="Yönetici: $ADMIN_U  (e-posta: $ADMIN_EMAIL)
Yönetici şifresini belirlemek ya da değiştirmek için: sudo tayfa-sifre-belirle"
fi

# ------------------------------------------------- 4. nginx yönlendirmesi
say "$DOMAIN için yönlendirme ekleniyor"
cat > "$INCLUDE_FILE.yeni" <<EOF
# Bayx: sohbet sunucusu ve kayıt servisi (kur.sh tarafından yazılır)
location ^~ /_matrix/client/ {
    proxy_pass http://127.0.0.1:8008;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_read_timeout 120s;
    client_max_body_size 100M;
}
location ^~ /_matrix/media/ {
    proxy_pass http://127.0.0.1:8008;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto \$scheme;
    client_max_body_size 100M;
}
location = /bayx/call/ws {
    # Anlık arama sinyali (WebSocket)
    proxy_pass http://127.0.0.1:8010;
    proxy_http_version 1.1;
    proxy_set_header Upgrade \$http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
location ^~ /bayx/ {
    proxy_pass http://127.0.0.1:8010;
    proxy_http_version 1.1;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_read_timeout 60s;
    client_max_body_size 64k;
}
EOF
mv "$INCLUDE_FILE.yeni" "$INCLUDE_FILE"
chmod 644 "$INCLUDE_FILE"

if ! "$NGINX_BIN" -t -c "$NGINX_CONF" >/tmp/bayx-nginx-test.log 2>&1; then
    rm -f "$INCLUDE_FILE"
    cat /tmp/bayx-nginx-test.log >&2
    fail "nginx ayar testi başarısız oldu; yönlendirme geri alındı, mevcut siteler etkilenmedi."
fi
"$NGINX_BIN" -s reload -c "$NGINX_CONF"
sleep 2

# ------------------------------------------------------------ 4b. Otomatik yedek
say "Otomatik yedek"
cat > /usr/local/bin/tayfa-yedek <<'YEDEKCMD'
#!/usr/bin/env bash
# Tayfa günlük yedeği: veritabanı, ayarlar ve giriş kayıtları (son 7 gün) + medya kopyası.
# Gizli anahtarlar da içerdiği için yedek klasörünü yalnızca root okuyabilir.
set -u
ROOT="${TAYFA_YEDEK_KOK:-/var/backups/tayfa}"
ETC="${TAYFA_SYNAPSE_ETC:-/etc/matrix-synapse}"
STATE="${TAYFA_DURUM_DIR:-/var/lib/bayx}"
DAY="$(date +%Y-%m-%d)"
DEST="$ROOT/gunluk/$DAY"
umask 077
mkdir -p "$DEST" "$ROOT/medya"
chmod 700 "$ROOT"
log() { echo "$(date '+%F %T') $*"; }

FREE_MB="$(df -Pm "$ROOT" | awk 'NR==2{print $4}')"
if [ "${FREE_MB:-0}" -lt 1024 ]; then
    log "HATA: diskte 1 GB'tan az boş yer var, yedek alınmadı."
    echo "$DAY hata disk" > "$ROOT/son"
    exit 1
fi

OK=1
DB="$(grep -rhoE "/[^ \"']*homeserver\.db" "$ETC" 2>/dev/null | head -1)"
DB="${DB:-/var/lib/matrix-synapse/homeserver.db}"
MEDIA="$(grep -rhE '^media_store_path:' "$ETC" 2>/dev/null | head -1 | sed -E 's/^media_store_path:[[:space:]]*"?([^"]*)"?[[:space:]]*$/\1/')"
MEDIA="${MEDIA:-/var/lib/matrix-synapse/media}"

if grep -rqE 'name:[[:space:]]*psycopg2' "$ETC" 2>/dev/null; then
    # PostgreSQL kullanılıyorsa
    if runuser -u postgres -- pg_dump -Fc synapse > "$DEST/synapse.pgdump" 2>/dev/null; then :; else OK=0; log "HATA: PostgreSQL yedeği alınamadı."; fi
elif [ -f "$DB" ]; then
    # SQLite: çalışırken bile tutarlı kopya için SQLite'ın kendi yedekleme yöntemi
    if python3 - "$DB" "$DEST/synapse.db" <<'PY'
import sqlite3, sys
src = sqlite3.connect(sys.argv[1], timeout=120)
dst = sqlite3.connect(sys.argv[2])
src.backup(dst)
dst.close()
src.close()
PY
    then gzip -f "$DEST/synapse.db" || OK=0
    else OK=0; log "HATA: veritabanı yedeği alınamadı."; fi
else
    OK=0; log "UYARI: veritabanı bulunamadı ($DB)."
fi

tar czf "$DEST/ayarlar.tar.gz" --ignore-failed-read "$ETC" /etc/bayx /etc/turnserver.conf 2>/dev/null || true
if [ -d "$STATE" ]; then tar czf "$DEST/tayfa-durum.tar.gz" "$STATE" 2>/dev/null || OK=0; fi
if [ -d "$MEDIA" ]; then cp -a -u "$MEDIA/." "$ROOT/medya/" 2>/dev/null || OK=0; fi

# 7 günden eski günlük yedekleri sil
find "$ROOT/gunluk" -mindepth 1 -maxdepth 1 -type d -mtime +7 -exec rm -rf {} + 2>/dev/null

if [ "$OK" = 1 ]; then echo "$DAY tamam" > "$ROOT/son"; log "Yedek tamam: $DEST"; else echo "$DAY eksik" > "$ROOT/son"; log "Yedek eksik: $DEST"; exit 1; fi
YEDEKCMD
chmod 755 /usr/local/bin/tayfa-yedek

cat > /usr/local/bin/tayfa-yedek-geri <<'GERICMD'
#!/usr/bin/env bash
# Sunucuyu seçilen günün yedeğine döndürür (veritabanı + giriş kayıtları).
set -eu
ROOT="${TAYFA_YEDEK_KOK:-/var/backups/tayfa}"
ETC="${TAYFA_SYNAPSE_ETC:-/etc/matrix-synapse}"
if [ $# -lt 1 ]; then
    echo "Kullanım: sudo tayfa-yedek-geri TARİH   (ör. sudo tayfa-yedek-geri 2026-11-14)"
    echo "Mevcut yedekler:"; ls -1 "$ROOT/gunluk" 2>/dev/null || echo "  (yok)"
    exit 1
fi
SRC="$ROOT/gunluk/$1"
[ -d "$SRC" ] || { echo "Bu tarihte yedek yok: $1"; exit 1; }
PG=0
grep -rqE 'name:[[:space:]]*psycopg2' "$ETC" 2>/dev/null && PG=1
if [ "$PG" = 1 ]; then
    [ -f "$SRC/synapse.pgdump" ] || { echo "Bu yedek PostgreSQL'e geçişten önce alınmış; elle geri yüklenmeli."; exit 1; }
else
    [ -f "$SRC/synapse.db.gz" ] || { echo "Bu yedekte SQLite veritabanı yok; elle geri yüklenmeli."; exit 1; }
fi
echo "DİKKAT: Sunucu $1 tarihli yedeğe döndürülecek."
echo "O tarihten sonra gönderilen mesajlar ve açılan hesaplar kaybolur. Şu anki veritabanı ayrıca saklanır."
read -r -p "Devam etmek için büyük harflerle EVET yaz: " ans
[ "$ans" = "EVET" ] || { echo "Vazgeçildi."; exit 1; }
if [ "$PG" = 1 ]; then
    systemctl stop bayx-kayit matrix-synapse
    KEEP="synapse_once_$(date +%Y%m%d%H%M%S)"
    OWNER="$(runuser -u postgres -- psql -qAtc "select pg_get_userbyid(datdba) from pg_database where datname='synapse'")"
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -qc "alter database synapse rename to $KEEP"
    runuser -u postgres -- createdb --encoding=UTF8 --locale=C --template=template0 --owner="$OWNER" synapse
    if ! runuser -u postgres -- pg_restore --no-owner --role="$OWNER" -d synapse < "$SRC/synapse.pgdump"; then
        echo "Geri yükleme başarısız; eski veritabanına dönülüyor."
        runuser -u postgres -- dropdb synapse
        runuser -u postgres -- psql -qc "alter database $KEEP rename to synapse"
        systemctl start matrix-synapse bayx-kayit
        exit 1
    fi
    [ -f "$SRC/tayfa-durum.tar.gz" ] && tar xzf "$SRC/tayfa-durum.tar.gz" -C /
    systemctl start matrix-synapse bayx-kayit
    echo "Geri yükleme tamam. Önceki veritabanı PostgreSQL'de '$KEEP' adıyla duruyor."
    exit 0
fi
DB="$(grep -rhoE "/[^ \"']*homeserver\.db" "$ETC" 2>/dev/null | head -1)"
DB="${DB:-/var/lib/matrix-synapse/homeserver.db}"
systemctl stop bayx-kayit matrix-synapse
KEEP="$DB.geri-yuklemeden-once-$(date +%Y%m%d%H%M%S)"
cp -a "$DB" "$KEEP"
rm -f "$DB-wal" "$DB-shm"
gunzip -c "$SRC/synapse.db.gz" > "$DB"
chown --reference="$KEEP" "$DB"; chmod --reference="$KEEP" "$DB"
[ -f "$SRC/tayfa-durum.tar.gz" ] && tar xzf "$SRC/tayfa-durum.tar.gz" -C /
systemctl start matrix-synapse bayx-kayit
echo "Geri yükleme tamam. Önceki veritabanı şurada duruyor: $KEEP"
GERICMD
chmod 755 /usr/local/bin/tayfa-yedek-geri

# Büyüme için: SQLite'tan PostgreSQL'e tek komutla geçiş (sudo tayfa-postgres)
if curl -fsSL -o /usr/local/bin/tayfa-postgres.yeni "$RAW_BASE/tayfa-postgres.sh" && bash -n /usr/local/bin/tayfa-postgres.yeni; then
    mv /usr/local/bin/tayfa-postgres.yeni /usr/local/bin/tayfa-postgres
    chmod 755 /usr/local/bin/tayfa-postgres
else
    rm -f /usr/local/bin/tayfa-postgres.yeni
fi

cat > /etc/cron.d/tayfa-yedek <<'CRONEOF'
# Tayfa: her gece 04:17'de yedek al
17 4 * * * root /usr/local/bin/tayfa-yedek >> /var/log/tayfa-yedek.log 2>&1
CRONEOF
chmod 644 /etc/cron.d/tayfa-yedek

BACKUP_OK="hayır"
if /usr/local/bin/tayfa-yedek >> /var/log/tayfa-yedek.log 2>&1; then
    BACKUP_OK="evet (her gece 04:17, /var/backups/tayfa, son 7 gün; geri yüklemek için: sudo tayfa-yedek-geri)"
else
    BACKUP_OK="sorunlu (ayrıntı: /var/log/tayfa-yedek.log)"
fi

# ------------------------------------------------------------ 5. Kontrol
say "Kontrol"
LOCAL_OK="hayır"; PUBLIC_OK="hayır"; KAYIT_OK="hayır"; TURN_OK="hayır"
if grep -rqE 'name:[[:space:]]*psycopg2' /etc/matrix-synapse 2>/dev/null; then
    DB_KIND="PostgreSQL (binlerce kullanıcıya hazır)"
else
    DB_KIND="SQLite (100+ kullanıcıya çıkmadan önce bir kez: sudo tayfa-postgres)"
fi
ss -uln 2>/dev/null | grep -E "[:.]3478 " >/dev/null && TURN_OK="evet"
curl -fsS -o /dev/null "$SYNAPSE_URL/_matrix/client/versions" && LOCAL_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/_matrix/client/versions" 2>/dev/null && PUBLIC_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/bayx/health" 2>/dev/null && KAYIT_OK="evet"

cat <<EOF

================ BAYX KURULUM SONUCU ================
Sohbet sunucusu çalışıyor (yerel)      : $LOCAL_OK
https://$DOMAIN üzerinden erişim : $PUBLIC_OK
Kayıt servisi (https üzerinden)        : $KAYIT_OK
Kayıt servisi altyapısı                : $KAYIT_ENGINE
Arama aktarma sunucusu (coturn)        : $TURN_OK
Bildirim servisi (Firebase)            : $PUSH_OK
Tayfa Web                              : $WEB_OK
Otomatik yedek                         : $BACKUP_OK
Veritabanı                             : $DB_KIND
E-posta (doğrulama, şifre sıfırlama)   : $MAIL_OK
SMS (telefonla kayıt)                  : $SMS_OK
$FIREWALL_NOTE
$ADMIN_NOTE
=====================================================
EOF
if [ "$PUBLIC_OK" != "evet" ] || [ "$KAYIT_OK" != "evet" ]; then
    echo "Dışarıdan erişim doğrulanamadı. Bu ekranın görüntüsünü gönder; nedenine birlikte bakarız."
fi
