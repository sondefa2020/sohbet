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
curl -fsSL -o /opt/bayx/kayit.py.yeni "$RAW_BASE/kayit.py"
python3 -m py_compile /opt/bayx/kayit.py.yeni || fail "kayit.py indirilemedi ya da bozuk."
mv /opt/bayx/kayit.py.yeni /opt/bayx/kayit.py
chmod 644 /opt/bayx/kayit.py
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
BAYX_MAX_MEMBERS=10
EOF
    umask 022
    chown root:bayx "$ENV_FILE"
    chmod 640 "$ENV_FILE"
    unset BOT_PASS BOT_TOKEN
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

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable bayx-kayit >/dev/null 2>&1 || true
systemctl restart bayx-kayit
sleep 2
curl -fsS -o /dev/null http://127.0.0.1:8010/bayx/health || fail "Kayıt servisi açılmadı. Günlük: journalctl -u bayx-kayit -n 50"

# ------------------------------------------------------ 3. Yönetici hesabı
ADMIN_NOTE=""
if [ ! -f /etc/bayx/yonetici-olusturuldu ]; then
    say "Yönetici hesabı"
    ADMIN_USER="${BAYX_ADMIN_USER:-}"
    while ! [[ "$ADMIN_USER" =~ ^[a-z0-9][a-z0-9._-]{2,19}$ ]]; do
        read -r -p "Yönetici kullanıcı adı (küçük harf/rakam, 3-20 karakter): " ADMIN_USER
    done
    ADMIN_PASS="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)"
    register_new_matrix_user -u "$ADMIN_USER" -p "$ADMIN_PASS" -a -c "$SYNAPSE_CONF" "$SYNAPSE_URL" >/dev/null \
        || fail "Yönetici hesabı oluşturulamadı (bu kullanıcı adı alınmış olabilir)."
    echo "$ADMIN_USER" > /etc/bayx/yonetici-olusturuldu
    ADMIN_NOTE="Yönetici kullanıcı adı : $ADMIN_USER
Yönetici şifresi       : $ADMIN_PASS
(Bu şifre yalnızca şimdi gösterilir; bir yere not et.)"
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
    client_max_body_size 16k;
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
LOCAL_OK="hayır"; PUBLIC_OK="hayır"; KAYIT_OK="hayır"
curl -fsS -o /dev/null "$SYNAPSE_URL/_matrix/client/versions" && LOCAL_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/_matrix/client/versions" 2>/dev/null && PUBLIC_OK="evet"
curl -fsS -o /dev/null --max-time 15 "https://$DOMAIN/bayx/health" 2>/dev/null && KAYIT_OK="evet"

cat <<EOF

================ BAYX KURULUM SONUCU ================
Sohbet sunucusu çalışıyor (yerel)      : $LOCAL_OK
https://$DOMAIN üzerinden erişim : $PUBLIC_OK
Kayıt servisi (https üzerinden)        : $KAYIT_OK
$ADMIN_NOTE
=====================================================
EOF
if [ "$PUBLIC_OK" != "evet" ] || [ "$KAYIT_OK" != "evet" ]; then
    echo "Dışarıdan erişim doğrulanamadı. Bu ekranın görüntüsünü gönder; nedenine birlikte bakarız."
fi
