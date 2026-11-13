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
presence:
  enabled: true
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
for f in kayit.py giris.py; do
    curl -fsSL -o "/opt/bayx/$f.yeni" "$RAW_BASE/$f" || fail "$f indirilemedi."
    python3 -m py_compile "/opt/bayx/$f.yeni" || fail "$f bozuk indi."
done
for f in kayit.py giris.py; do
    mv "/opt/bayx/$f.yeni" "/opt/bayx/$f"
    chmod 644 "/opt/bayx/$f"
done
rm -rf /opt/bayx/__pycache__

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
ExecStart=/usr/bin/python3 /opt/bayx/kayit.py
Restart=on-failure
RestartSec=3
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
sleep 2
curl -fsS -o /dev/null http://127.0.0.1:8010/bayx/health || fail "Kayıt servisi açılmadı. Günlük: journalctl -u bayx-kayit -n 50"

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
# Element Web, Tayfa adıyla ayrı bir alt alan adında yayınlanır (güvenlik için sohbet
# sunucusundan ayrı bir adreste olmalı). Alt alan adı panelde oluşturulmamışsa atlanır.
WEB_DIR="${BAYX_WEB_DIR:-/home/acioglu/acioglu.tr/subdomains/${WEB_DOMAIN%%.*}/public_html}"
WEB_OK="hayır (panelde $WEB_DOMAIN alt alan adı yok)"
if [ -d "$WEB_DIR" ]; then
    say "Tayfa Web kuruluyor ($WEB_DOMAIN)"
    WEB_TAG="$(curl -fsSL https://api.github.com/repos/element-hq/element-web/releases/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')" \
        || fail "Element Web sürümü öğrenilemedi."
    WEB_TMP="$(mktemp -d)"
    curl -fsSL -o "$WEB_TMP/element.tar.gz" \
        "https://github.com/element-hq/element-web/releases/download/$WEB_TAG/element-$WEB_TAG.tar.gz" \
        || fail "Element Web indirilemedi."
    tar -xzf "$WEB_TMP/element.tar.gz" -C "$WEB_TMP"
    WEB_SRC="$(find "$WEB_TMP" -maxdepth 1 -type d -name 'element-*' | head -1)"
    [ -f "$WEB_SRC/index.html" ] || fail "Element Web paketi beklenen yapıda değil."
    if [ -n "$(ls -A "$WEB_DIR" 2>/dev/null)" ] && [ ! -f "$WEB_DIR/.tayfa-web" ]; then
        mkdir -p "$WEB_DIR/../public_html.yedek"
        cp -a "$WEB_DIR/." "$WEB_DIR/../public_html.yedek/"
    fi
    find "$WEB_DIR" -mindepth 1 -delete
    cp -a "$WEB_SRC/." "$WEB_DIR/"
    cat > "$WEB_DIR/config.json" <<WEBCONF
{
  "default_server_config": {
    "m.homeserver": { "base_url": "https://$DOMAIN", "server_name": "$DOMAIN" }
  },
  "brand": "Tayfa",
  "default_theme": "light",
  "default_country_code": "TR",
  "disable_custom_urls": true,
  "disable_guests": true,
  "disable_3pid_login": true,
  "show_labs_settings": false,
  "mobile_guide_toast": false,
  "room_directory": { "servers": [] },
  "embedded_pages": { "login_for_welcome": true },
  "setting_defaults": {
    "UIFeature.registration": false,
    "UIFeature.passwordReset": false,
    "UIFeature.voip": false,
    "UIFeature.widgets": false,
    "UIFeature.feedback": false,
    "UIFeature.thirdPartyId": false,
    "UIFeature.identityServer": false,
    "UIFeature.shareSocialMedia": false,
    "UIFeature.urlPreviews": false,
    "UIFeature.locationSharing": false
  }
}
WEBCONF
    cat > "$WEB_DIR/giris.html" <<'WEBGIRIS'
<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Tayfa Web'e giriş</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:linear-gradient(#fbf8f4,#f2ebe2);color:#1f1b18}
.k{background:#fff;border-radius:28px;box-shadow:0 18px 50px rgba(90,60,30,.12);padding:34px 30px;max-width:380px;width:86%;text-align:center}
h1{margin:0 0 6px;font-size:26px}p{color:#6e655c;line-height:1.45;margin:6px 0 18px}
input{width:100%;box-sizing:border-box;padding:16px;border-radius:16px;border:1px solid #e6ddd2;background:#faf6f1;font-size:24px;letter-spacing:4px;text-align:center;text-transform:uppercase}
button{width:100%;margin-top:14px;padding:15px;border:0;border-radius:18px;background:linear-gradient(#e2c3a1,#c99d74);color:#2a1c10;font-weight:700;font-size:17px;cursor:pointer}
#h{color:#b4232a;min-height:22px;margin-top:12px;font-size:14px}</style></head><body><div class="k">
<h1>Tayfa Web</h1><p>Telefonda <b>Profil › Web'de aç</b> bölümündeki 8 haneli kodu yaz.</p>
<input id="c" maxlength="9" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="XXXX-XXXX">
<button id="b">Giriş yap</button><div id="h"></div></div><script>
const HS="https://__DOMAIN__";
async function go(){const h=document.getElementById("h");h.textContent="";
const code=document.getElementById("c").value;
try{const r=await fetch(HS+"/bayx/v2/web/redeem",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({code})});
const d=await r.json();if(!r.ok){h.textContent=d.message||"Giriş yapılamadı.";return}
localStorage.setItem("mx_sso_hs_url",d.homeserver);localStorage.removeItem("mx_sso_is_url");
location.replace("/?loginToken="+encodeURIComponent(d.login_token));}catch(e){h.textContent="Sunucuya ulaşılamadı.";}}
document.getElementById("b").onclick=go;document.getElementById("c").addEventListener("keydown",e=>{if(e.key==="Enter")go()});
</script></body></html>
WEBGIRIS
    sed -i "s|__DOMAIN__|$DOMAIN|" "$WEB_DIR/giris.html"
    echo "$WEB_TAG" > "$WEB_DIR/.tayfa-web"
    chown -R --reference="$(dirname "$WEB_DIR")" "$WEB_DIR"
    find "$WEB_DIR" -type d -exec chmod 755 {} +
    find "$WEB_DIR" -type f -exec chmod 644 {} +
    rm -rf "$WEB_TMP"
    WEB_OK="evet (https://$WEB_DOMAIN, $WEB_TAG)"
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
    ADMIN_NOTE="Yönetici ($(cat /etc/bayx/yonetici-olusturuldu)) için giriş — telefonda bağlantıyı aç ya da kodu yaz:
$(/usr/local/bin/tayfa-giris 2>&1 || true)
Yeni bağlantı gerekirse: sudo tayfa-giris  (başka üye için: sudo tayfa-giris kullanici_adi)"
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

# ------------------------------------------------------------ 5. Kontrol
say "Kontrol"
LOCAL_OK="hayır"; PUBLIC_OK="hayır"; KAYIT_OK="hayır"; TURN_OK="hayır"
ss -uln 2>/dev/null | grep -E "[:.]3478 " >/dev/null && TURN_OK="evet"
curl -fsS -o /dev/null "$SYNAPSE_URL/_matrix/client/versions" && LOCAL_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/_matrix/client/versions" 2>/dev/null && PUBLIC_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/bayx/health" 2>/dev/null && KAYIT_OK="evet"

cat <<EOF

================ BAYX KURULUM SONUCU ================
Sohbet sunucusu çalışıyor (yerel)      : $LOCAL_OK
https://$DOMAIN üzerinden erişim : $PUBLIC_OK
Kayıt servisi (https üzerinden)        : $KAYIT_OK
Arama aktarma sunucusu (coturn)        : $TURN_OK
Bildirim servisi (Firebase)            : $PUSH_OK
Tayfa Web                              : $WEB_OK
$FIREWALL_NOTE
$ADMIN_NOTE
=====================================================
EOF
if [ "$PUBLIC_OK" != "evet" ] || [ "$KAYIT_OK" != "evet" ]; then
    echo "Dışarıdan erişim doğrulanamadı. Bu ekranın görüntüsünü gönder; nedenine birlikte bakarız."
fi
