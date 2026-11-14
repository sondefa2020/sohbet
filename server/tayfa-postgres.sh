#!/usr/bin/env bash
# Tayfa: Synapse veritabanını SQLite'tan PostgreSQL'e taşır (yüzlerce-binlerce kullanıcı için gerekli).
#
# Güvenlik ilkeleri:
#   - Önce tam yedek alınır; yedek alınamazsa hiçbir şey yapılmaz.
#   - SQLite dosyasına dokunulmaz (yalnızca okunur).
#   - Ayar, ancak taşıma ve sayım doğrulaması başarılı olursa PostgreSQL'e çevrilir.
#   - Yeni ayarla sunucu açılmazsa ayar geri alınır ve sunucu SQLite ile eskisi gibi açılır.
#
# Kullanım: sudo tayfa-postgres            (onay ister)
#           sudo tayfa-postgres --evet     (onay sormadan)
set -u

ETC="${TAYFA_SYNAPSE_ETC:-/etc/matrix-synapse}"
VENV="${TAYFA_SYNAPSE_VENV:-/opt/venvs/matrix-synapse}"
URL="${TAYFA_SYNAPSE_URL:-http://127.0.0.1:8008}"
STATE="${TAYFA_PG_STATE:-/etc/bayx}"
LOG="${TAYFA_PG_LOG:-/var/log/tayfa-postgres.log}"
DBCONF="$ETC/conf.d/veritabani.yaml"
PG_DB="synapse"
PG_USER="synapse_user"

say()  { echo; echo "==> $*"; }
fail() { echo; echo "HATA: $*"; echo "Ayrıntı: $LOG"; exit 1; }
svc()  { systemctl "$@" >>"$LOG" 2>&1; }
pg()   { runuser -u postgres -- "$@"; }

wait_up() {
    for _ in $(seq 1 90); do
        curl -fsS -o /dev/null "$URL/_matrix/client/versions" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

[ "$(id -u)" = 0 ] || fail "Bu komut root olarak çalışmalı: sudo tayfa-postgres"
mkdir -p "$(dirname "$LOG")"; : >> "$LOG"
echo "---- $(date '+%F %T') tayfa-postgres başladı" >> "$LOG"

if grep -rqE 'name:[[:space:]]*psycopg2' "$ETC" 2>/dev/null; then
    echo "Sohbet sunucusu zaten PostgreSQL kullanıyor. Yapılacak bir şey yok."
    exit 0
fi

SQLITE="$(grep -rhoE "/[^ \"']*homeserver\.db" "$ETC" 2>/dev/null | head -1)"
SQLITE="${SQLITE:-/var/lib/matrix-synapse/homeserver.db}"
[ -f "$SQLITE" ] || fail "SQLite veritabanı bulunamadı: $SQLITE"
PORT_DB="$VENV/bin/synapse_port_db"
[ -x "$PORT_DB" ] || fail "Taşıma aracı bulunamadı: $PORT_DB"

DB_MB=$(( $(stat -c %s "$SQLITE") / 1048576 + 1 ))
FREE_MB="$(df -Pm "$(dirname "$SQLITE")" | awk 'NR==2{print $4}')"
NEED_MB=$(( DB_MB * 3 + 1024 ))
[ "${FREE_MB:-0}" -ge "$NEED_MB" ] || fail "Diskte yeterli yer yok: ${NEED_MB} MB gerekli, ${FREE_MB} MB boş."

echo "Sohbet veritabanı (${DB_MB} MB) PostgreSQL'e taşınacak."
echo "Bu sırada sohbet sunucusu birkaç dakika kapalı kalır; mesajlar kaybolmaz, uygulama sonra kendiliğinden bağlanır."
if [ "${1:-}" != "--evet" ]; then
    read -r -p "Devam etmek için büyük harflerle EVET yaz: " ans
    [ "$ans" = "EVET" ] || { echo "Vazgeçildi."; exit 1; }
fi

say "1/7 Yedek alınıyor"
if command -v tayfa-yedek >/dev/null 2>&1; then
    tayfa-yedek >>"$LOG" 2>&1 || fail "Yedek alınamadı; güvenlik için taşımaya başlanmadı."
else
    fail "tayfa-yedek komutu yok. Önce kurulum komutunu (kur.sh) çalıştır."
fi

say "2/7 PostgreSQL kuruluyor"
if ! command -v pg_ctlcluster >/dev/null 2>&1 || ! ls /usr/lib/postgresql/*/bin/postgres >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql >>"$LOG" 2>&1 || fail "PostgreSQL kurulamadı."
fi
svc enable postgresql || true
svc start postgresql || true
for _ in $(seq 1 30); do pg psql -qAtc "select 1" >/dev/null 2>&1 && break; sleep 1; done
pg psql -qAtc "select 1" >/dev/null 2>&1 || fail "PostgreSQL çalışmıyor."

say "3/7 Veritabanı hazırlanıyor"
[ -d "$STATE" ] || install -d -m 700 "$STATE"
PASS="$(openssl rand -hex 24)"
if pg psql -qAtc "select 1 from pg_roles where rolname='$PG_USER'" | grep -q 1; then
    pg psql -v ON_ERROR_STOP=1 -qc "alter role $PG_USER with login password '$PASS'" >>"$LOG" 2>&1 || fail "Veritabanı kullanıcısı güncellenemedi."
else
    pg psql -v ON_ERROR_STOP=1 -qc "create role $PG_USER with login password '$PASS'" >>"$LOG" 2>&1 || fail "Veritabanı kullanıcısı oluşturulamadı."
fi
# Ayar hâlâ SQLite'ı gösterdiği için var olan "synapse" veritabanı yalnızca yarım kalmış eski bir denemeden olabilir.
pg dropdb --if-exists "$PG_DB" >>"$LOG" 2>&1
pg createdb --encoding=UTF8 --locale=C --template=template0 --owner="$PG_USER" "$PG_DB" >>"$LOG" 2>&1 \
    || fail "PostgreSQL veritabanı oluşturulamadı."
umask 077
printf 'PG_USER=%s\nPG_PASS=%s\nPG_DB=%s\n' "$PG_USER" "$PASS" "$PG_DB" > "$STATE/postgres.env"
TMPCONF="$(mktemp)"
cat > "$TMPCONF" <<EOF
database:
  name: psycopg2
  txn_limit: 10000
  args:
    user: $PG_USER
    password: "$PASS"
    dbname: $PG_DB
    host: 127.0.0.1
    cp_min: 5
    cp_max: 20
EOF
# Taşıma aracı sunucunun tam ayarını ister: gerçek ayarları birleştirip yalnızca veritabanını değiştir.
# Bu dosya yalnızca taşıma sırasında kullanılır.
PORTCONF="$(mktemp)"
"$VENV/bin/python" - "$ETC" "$TMPCONF" "$PORTCONF" >>"$LOG" 2>&1 <<'PY' || fail "Sunucu ayarları okunamadı."
import glob, os, sys, yaml
etc, dbconf, out = sys.argv[1:4]
files = [os.path.join(etc, "homeserver.yaml")] + sorted(glob.glob(os.path.join(etc, "conf.d", "*.yaml")))
merged = {}
for f in files:
    if os.path.isfile(f):
        merged.update(yaml.safe_load(open(f)) or {})
merged["database"] = yaml.safe_load(open(dbconf))["database"]
# Göreli yollar (ör. imza anahtarı) ayar klasörüne göre çözülsün.
os.chdir(etc)
for k, v in list(merged.items()):
    if k.endswith("_path") and isinstance(v, str) and not os.path.isabs(v):
        merged[k] = os.path.abspath(v)
yaml.safe_dump(merged, open(out, "w"))
PY
umask 022

say "4/7 Sohbet sunucusu durduruluyor"
svc stop matrix-synapse || fail "Sohbet sunucusu durdurulamadı."

restore_sqlite() {
    rm -f "$DBCONF" "$TMPCONF" "$PORTCONF"
    svc start matrix-synapse
    wait_up >/dev/null 2>&1
}

say "5/7 Veriler taşınıyor (büyüklüğe göre birkaç dakika sürebilir)"
if ! "$PORT_DB" --sqlite-database "$SQLITE" --postgres-config "$PORTCONF" >>"$LOG" 2>&1; then
    restore_sqlite
    fail "Taşıma başarısız oldu. Sunucu eski veritabanıyla (SQLite) yeniden açıldı; hiçbir şey kaybolmadı."
fi

say "6/7 Taşınan veriler doğrulanıyor"
BAD=0
for table in users rooms events room_memberships local_media_repository; do
    a="$(python3 - "$SQLITE" "$table" <<'PY' 2>/dev/null
import sqlite3, sys
try:
    print(sqlite3.connect(sys.argv[1]).execute("select count(*) from %s" % sys.argv[2]).fetchone()[0])
except Exception:
    print("yok")
PY
)"
    b="$(PGPASSWORD="$PASS" psql -h 127.0.0.1 -U "$PG_USER" -d "$PG_DB" -qAtc "select count(*) from $table" 2>/dev/null || echo yok)"
    echo "$table: sqlite=$a postgres=$b" >> "$LOG"
    if [ "$a" != "$b" ]; then BAD=1; echo "  $table: SQLite $a satır, PostgreSQL $b satır"; fi
done
if [ "$BAD" = 1 ]; then
    restore_sqlite
    fail "Sayılar tutmadı. Sunucu eski veritabanıyla (SQLite) yeniden açıldı; hiçbir şey kaybolmadı."
fi

say "7/7 Sohbet sunucusu PostgreSQL ile açılıyor"
install -m 600 "$TMPCONF" "$DBCONF"
chown matrix-synapse:matrix-synapse "$DBCONF" 2>/dev/null || true
rm -f "$TMPCONF" "$PORTCONF"
svc start matrix-synapse
if ! wait_up; then
    svc stop matrix-synapse
    restore_sqlite
    fail "Sunucu PostgreSQL ile açılamadı. Ayar geri alındı, sunucu SQLite ile çalışıyor; hiçbir şey kaybolmadı."
fi

echo "---- $(date '+%F %T') tamam" >> "$LOG"
cat <<EOF

================ TAŞIMA TAMAM ================
Sohbet sunucusu artık PostgreSQL kullanıyor.
Eski SQLite dosyası yerinde bırakıldı: $SQLITE
Bir hafta sorunsuz çalıştıktan sonra silebilirsin.
Günlük yedek artık PostgreSQL'den alınıyor.
==============================================
EOF
