import { useCallback, useEffect, useMemo, useState } from "react";
import { locale, t, tf } from "../i18n";
import {
  AdminError,
  createInvite,
  getAudit,
  getConfig,
  getMembers,
  getStats,
  memberAction,
  setConfig,
  type AdminMember,
  type AppConfig,
  type AuditEntry,
  type Stats,
} from "../admin";
import { BackIcon } from "./Icons";

type Tab = "overview" | "members" | "settings" | "audit";

function fmtBytes(n: number) {
  if (n > 1e12) return (n / 1e12).toFixed(1) + " TB";
  if (n > 1e9) return (n / 1e9).toFixed(1) + " GB";
  return Math.round(n / 1e6) + " MB";
}

function fmtDate(ms: number) {
  if (!ms) return "—";
  return new Date(ms).toLocaleDateString(locale(), { day: "2-digit", month: "2-digit", year: "numeric" });
}

function fmtAgo(sec: number) {
  if (!sec) return "—";
  const diff = Date.now() / 1000 - sec;
  if (diff < 90) return t("şimdi");
  if (diff < 3600) return tf("%s dk önce", Math.round(diff / 60));
  if (diff < 86400) return tf("%s sa önce", Math.round(diff / 3600));
  return new Date(sec * 1000).toLocaleDateString(locale());
}

const ACTIONS: Record<string, string> = {
  lock: "Kilitledi",
  unlock: "Kilidi açtı",
  approve: "Onayladı",
  reject: "Reddetti",
  remove: "Hesabı kapattı",
  "reset-password": "Şifre sıfırladı",
  "login-link": "Giriş bağlantısı üretti",
  config: "Ayarları değiştirdi",
  invite: "Davet kodu oluşturdu",
};

/** Yönetim paneli: genel bakış, üyeler, ayarlar ve işlem günlüğü. Mesaj içerikleri hiçbir zaman görünmez. */
export function AdminPanel({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<Tab>("overview");
  return (
    <section className="admin" aria-label={t("Yönetim")}>
      <header className="admin-head">
        <button type="button" className="icon-btn only-narrow" aria-label={t("Geri")} onClick={onClose}>
          <BackIcon />
        </button>
        <h1>{t("Yönetim")}</h1>
        <nav className="admin-tabs" aria-label={t("Yönetim")}>
          {(
            [
              ["overview", "Genel bakış"],
              ["members", "Üyeler"],
              ["settings", "Duyuru ve ayarlar"],
              ["audit", "İşlem günlüğü"],
            ] as [Tab, string][]
          ).map(([id, label]) => (
            <button key={id} type="button" className={"tab" + (tab === id ? " active" : "")} aria-pressed={tab === id} onClick={() => setTab(id)}>
              {t(label)}
            </button>
          ))}
        </nav>
      </header>
      <div className="admin-body">
        {tab === "overview" && <Overview />}
        {tab === "members" && <Members />}
        {tab === "settings" && <Settings />}
        {tab === "audit" && <Audit />}
      </div>
    </section>
  );
}

function Overview() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(() => {
    getStats()
      .then((s) => {
        setStats(s);
        setError("");
      })
      .catch((e) => setError(e instanceof AdminError ? t(e.message) : t("Yüklenemedi.")));
  }, []);
  useEffect(() => {
    load();
    const timer = window.setInterval(load, 15_000); // canlı: 15 sn'de bir yenilenir
    return () => window.clearInterval(timer);
  }, [load]);
  if (error) return <p className="error">{error}</p>;
  if (!stats) return <p className="muted">{t("Yükleniyor…")}</p>;
  const cards: [string, string | number, string?][] = [
    ["Toplam üye", stats.members, t("sınır yok")],
    ["Şu an çevrimiçi", stats.online],
    ["Bugün aktif", stats.days[stats.days.length - 1]?.active ?? 0],
    ["Son 24 saatte yeni", stats.new_24h],
    ["Son 7 günde yeni", stats.new_7d],
    ["Numarası doğrulanmış", stats.with_phone],
    ["Sohbet odası", stats.rooms],
    ["Disk", fmtBytes(stats.disk_used), tf("/ %s", fmtBytes(stats.disk_total))],
  ];
  const max = Math.max(1, ...stats.days.map((d) => d.active ?? 0));
  return (
    <div className="stack-20">
      {!stats.sms_ready && (
        <p className="warn">{t("SMS servisi ayarlı değil: yeni kullanıcılar kayıt olamaz. Sunucuda: sudo tayfa-sms-ayarla")}</p>
      )}
      <div className="stat-grid">
        {cards.map(([label, value, sub]) => (
          <div className="stat" key={label}>
            <span className="stat-label">{t(label)}</span>
            <span className="stat-value">{value}</span>
            {sub && <span className="stat-sub">{sub}</span>}
          </div>
        ))}
      </div>
      <div className="card">
        <h2>{t("Son 14 gün: günlük aktif kullanıcı")}</h2>
        <div className="bars" role="img" aria-label={t("Son 14 gün: günlük aktif kullanıcı")}>
          {stats.days.map((d) => (
            <div className="bar-col" key={d.day} title={`${d.day}: ${d.active ?? 0}`}>
              <span className="bar-num">{d.active ?? 0}</span>
              <div className="bar" style={{ height: `${((d.active ?? 0) / max) * 100}%` }} />
              <span className="bar-day">{d.day.slice(8)}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="card">
        <h2>{t("Son 14 gün")}</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("Gün")}</th>
                <th>{t("Aktif")}</th>
                <th>{t("Yeni kayıt")}</th>
                <th>{t("Giriş")}</th>
                <th>{t("SMS")}</th>
                <th>{t("Arama")}</th>
              </tr>
            </thead>
            <tbody>
              {[...stats.days].reverse().map((d) => (
                <tr key={d.day}>
                  <td>{d.day}</td>
                  <td>{d.active ?? 0}</td>
                  <td>{d.signups ?? 0}</td>
                  <td>{d.logins ?? 0}</td>
                  <td>{d.sms ?? 0}</td>
                  <td>{d.calls ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function Members() {
  const [list, setList] = useState<AdminMember[] | null>(null);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | "online" | "locked" | "admin">("all");
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const load = useCallback(() => {
    getMembers()
      .then((r) => setList(r.members))
      .catch((e) => setMessage(e instanceof AdminError ? t(e.message) : t("Yüklenemedi.")));
  }, []);
  useEffect(load, [load]);

  const shown = useMemo(() => {
    const q = query.trim().toLocaleLowerCase("tr");
    return (list ?? [])
      .filter((m) => !q || m.display_name.toLocaleLowerCase("tr").includes(q) || m.user_id.includes(q) || m.phone.includes(q))
      .filter((m) => filter === "all" || (filter === "online" ? m.mode !== "offline" : filter === "locked" ? m.pending : m.admin))
      .sort((a, b) => b.created - a.created);
  }, [list, query, filter]);

  const act = async (action: "lock" | "unlock" | "remove", m: AdminMember) => {
    if (action === "remove" && !window.confirm(tf("%s hesabı kalıcı olarak kapatılsın mı? Bu geri alınamaz.", m.display_name || m.user_id))) return;
    setBusy(m.user_id);
    try {
      await memberAction(action, m.user_id);
      setMessage(t("İşlem tamam."));
      load();
    } catch (e) {
      setMessage(e instanceof AdminError ? t(e.message) : t("İşlem tamamlanamadı."));
    } finally {
      setBusy("");
    }
  };

  return (
    <div className="stack-20">
      <div className="toolbar">
        <label className="search grow">
          <span className="sr-only">{t("Üye ara")}</span>
          <input type="search" placeholder={t("Ad, kimlik ya da numaranın son hanesi")} value={query} onChange={(e) => setQuery(e.target.value)} />
        </label>
        <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)} aria-label={t("Filtre")}>
          <option value="all">{t("Hepsi")}</option>
          <option value="online">{t("Çevrimiçi")}</option>
          <option value="locked">{t("Kilitli")}</option>
          <option value="admin">{t("Yöneticiler")}</option>
        </select>
      </div>
      {message && (
        <p className="muted" role="status">
          {message}
        </p>
      )}
      {!list ? (
        <p className="muted">{t("Yükleniyor…")}</p>
      ) : (
        <div className="table-wrap card">
          <table>
            <thead>
              <tr>
                <th>{t("Üye")}</th>
                <th>{t("Numara")}</th>
                <th>{t("Durum")}</th>
                <th>{t("Son görülme")}</th>
                <th>{t("Kayıt")}</th>
                <th>{t("İşlem")}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((m) => (
                <tr key={m.user_id}>
                  <td>
                    <strong>{m.display_name || "—"}</strong>
                    {m.admin && <span className="tag">{t("Yönetici")}</span>}
                    <div className="small muted">{m.user_id}</div>
                  </td>
                  <td>{m.phone || "—"}</td>
                  <td>{m.pending ? <span className="tag danger">{t("Kilitli")}</span> : m.mode !== "offline" ? <span className="tag ok">{t("Çevrimiçi")}</span> : t("Çevrimdışı")}</td>
                  <td>{fmtAgo(m.last_seen)}</td>
                  <td>{fmtDate(m.created)}</td>
                  <td className="actions">
                    {!m.admin &&
                      (m.pending ? (
                        <button type="button" className="btn-ghost sm" disabled={busy === m.user_id} onClick={() => void act("unlock", m)}>
                          {t("Kilidi aç")}
                        </button>
                      ) : (
                        <button type="button" className="btn-ghost sm" disabled={busy === m.user_id} onClick={() => void act("lock", m)}>
                          {t("Kilitle")}
                        </button>
                      ))}
                    {!m.admin && (
                      <button type="button" className="btn-ghost sm danger" disabled={busy === m.user_id} onClick={() => void act("remove", m)}>
                        {t("Kapat")}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {shown.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">
                    {t("Eşleşen üye yok.")}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Settings() {
  const [cfg, setCfg] = useState<AppConfig | null>(null);
  const [saved, setSaved] = useState("");
  const [uses, setUses] = useState(5);
  const [days, setDays] = useState(7);
  const [invite, setInvite] = useState<{ code: string; url: string } | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    getConfig()
      .then(setCfg)
      .catch(() => setError(t("Yüklenemedi.")));
  }, []);
  if (error) return <p className="error">{error}</p>;
  if (!cfg) return <p className="muted">{t("Yükleniyor…")}</p>;
  const save = async () => {
    try {
      setCfg(await setConfig(cfg));
      setSaved(t("Kaydedildi. Uygulamalar bir sonraki açılışta görür."));
    } catch (e) {
      setSaved(e instanceof AdminError ? t(e.message) : t("İşlem tamamlanamadı."));
    }
  };
  return (
    <div className="stack-20">
      <div className="card stack-12">
        <h2>{t("Duyuru")}</h2>
        <p className="muted small">{t("Herkese uygulamayı açtığında bir kez gösterilir. Boş bırakırsan duyuru kalkar.")}</p>
        <textarea className="input" rows={3} maxLength={500} value={cfg.announcement} onChange={(e) => setCfg({ ...cfg, announcement: e.target.value })} />
      </div>
      <div className="card stack-12">
        <h2>{t("Güncelleme bildirimi")}</h2>
        <p className="muted small">{t("Bu sürüm numarasından eski uygulamalara güncelleme önerisi gösterilir.")}</p>
        <label className="field">
          <span>{t("Son sürüm numarası")}</span>
          <input className="input" type="number" min={0} value={cfg.latest_version} onChange={(e) => setCfg({ ...cfg, latest_version: Number(e.target.value) || 0 })} />
        </label>
        <label className="field">
          <span>{t("İndirme bağlantısı (APK)")}</span>
          <input className="input" type="url" value={cfg.apk_url} onChange={(e) => setCfg({ ...cfg, apk_url: e.target.value })} />
        </label>
      </div>
      <div className="row-12">
        <button type="button" className="btn-primary" onClick={() => void save()}>
          {t("Kaydet")}
        </button>
        {saved && (
          <span className="muted" role="status">
            {saved}
          </span>
        )}
      </div>
      <div className="card stack-12">
        <h2>{t("Davet kodu")}</h2>
        <div className="row-12 wrap">
          <label className="field">
            <span>{t("Kullanım")}</span>
            <input className="input" type="number" min={1} max={100} value={uses} onChange={(e) => setUses(Number(e.target.value) || 1)} />
          </label>
          <label className="field">
            <span>{t("Gün")}</span>
            <input className="input" type="number" min={1} max={30} value={days} onChange={(e) => setDays(Number(e.target.value) || 1)} />
          </label>
        </div>
        <button
          type="button"
          className="btn-ghost"
          onClick={() =>
            createInvite(uses, days)
              .then(setInvite)
              .catch((e) => setSaved(e instanceof AdminError ? t(e.message) : t("İşlem tamamlanamadı.")))
          }
        >
          {t("Oluştur")}
        </button>
        {invite && (
          <p>
            <strong className="code-sm">{invite.code}</strong> <span className="muted small">{invite.url}</span>
          </p>
        )}
      </div>
    </div>
  );
}

function Audit() {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  useEffect(() => {
    getAudit()
      .then((r) => setEntries(r.entries))
      .catch(() => setEntries([]));
  }, []);
  if (!entries) return <p className="muted">{t("Yükleniyor…")}</p>;
  if (entries.length === 0) return <p className="muted">{t("Henüz kayıtlı işlem yok.")}</p>;
  return (
    <div className="table-wrap card">
      <table>
        <thead>
          <tr>
            <th>{t("Zaman")}</th>
            <th>{t("Yapan")}</th>
            <th>{t("İşlem")}</th>
            <th>{t("Hedef")}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e, i) => (
            <tr key={i}>
              <td>{new Date(e.ts * 1000).toLocaleString(locale())}</td>
              <td>{e.actor}</td>
              <td>
                {t(ACTIONS[e.action] ?? e.action)}
                {e.detail && <div className="small muted">{e.detail}</div>}
              </td>
              <td>{e.target || "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
