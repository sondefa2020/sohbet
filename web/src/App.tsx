import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { SyncState } from "matrix-js-sdk";
import { t } from "./i18n";
import { backupState, client, getVersion, loggedOut, savedSession, setNotificationText, setOpenRoom, start, subscribe, syncState, unreadTotal } from "./session";
import { SettingsMenu } from "./components/SettingsMenu";
import { ChatList } from "./components/ChatList";
import { RoomView } from "./components/RoomView";
import { Login } from "./components/Login";
import { RecoveryDialog } from "./components/Recovery";
import { ChatIcon, KeyIcon, LogoMark, ShieldIcon } from "./components/Icons";
import { AdminPanel } from "./components/AdminPanel";
import { isAdmin } from "./admin";

function useDark(): [boolean, (v: boolean) => void] {
  const [dark, setDark] = useState(() => {
    const saved = localStorage.getItem("tayfa_tema");
    return saved ? saved === "koyu" : window.matchMedia("(prefers-color-scheme: dark)").matches;
  });
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("tayfa_tema", dark ? "koyu" : "acik");
  }, [dark]);
  return [dark, setDark];
}

export function App() {
  const [phase, setPhase] = useState<"loading" | "login" | "app">(savedSession() ? "loading" : "login");
  const [dark, setDark] = useDark();

  const boot = useCallback(async () => {
    const saved = savedSession();
    if (!saved) {
      setPhase("login");
      return;
    }
    setPhase("loading");
    try {
      await start(saved);
      setPhase("app");
    } catch {
      setPhase("login");
    }
  }, []);

  useEffect(() => {
    if (savedSession()) void boot();
  }, [boot]);

  if (phase === "login") return <Login onLoggedIn={() => void boot()} />;
  if (phase === "loading")
    return (
      <div className="center-screen">
        <LogoMark size={56} />
        <span className="muted">{t("Yükleniyor…")}</span>
      </div>
    );
  return <Main dark={dark} setDark={setDark} />;
}

function Main({ dark, setDark }: { dark: boolean; setDark: (v: boolean) => void }) {
  useSyncExternalStore(subscribe, getVersion);
  const [selected, setSelected] = useState<string | null>(null);
  const [recovery, setRecovery] = useState(false);
  const [backup, setBackup] = useState<"none" | "locked" | "ready">("none");
  const [admin, setAdmin] = useState(false);
  const [showAdmin, setShowAdmin] = useState(false);
  // Yönetici hesabıysa "Yönetim" bölümü açılır (sunucu her işlemde yetkiyi ayrıca denetler).
  useEffect(() => {
    isAdmin().then(setAdmin);
  }, []);
  const ready = syncState === SyncState.Prepared || syncState === SyncState.Syncing;

  // İlk eşitlemeden sonra: sunucuda mesaj yedeği var ama bu tarayıcı kilitliyse anahtarı sor.
  useEffect(() => {
    if (!ready) return;
    backupState().then((s) => {
      setBackup(s);
      if (s === "locked" && !sessionStorage.getItem("tayfa_kurtarma_soruldu")) {
        sessionStorage.setItem("tayfa_kurtarma_soruldu", "1");
        setRecovery(true);
      }
    });
  }, [ready, recovery]);

  // Açık sohbet (o sohbetin mesajları için bildirim çıkmaz) ve sekme başlığında okunmamış sayısı.
  useEffect(() => setOpenRoom(selected), [selected]);
  useEffect(() => setNotificationText(() => t("Yeni mesaj")), []);
  const unread = unreadTotal();
  useEffect(() => {
    document.title = unread > 0 ? `(${unread}) Tayfa` : "Tayfa";
  }, [unread]);
  // Masaüstü bildirimine tıklanınca o sohbeti aç.
  useEffect(() => {
    const open = (e: Event) => setSelected((e as CustomEvent<string>).detail);
    window.addEventListener("tayfa-ac", open);
    return () => window.removeEventListener("tayfa-ac", open);
  }, []);

  if (loggedOut) {
    return (
      <div className="center-screen">
        <LogoMark size={56} />
        <span>{t("Oturumun kapandı. Lütfen yeniden giriş yap.")}</span>
        <button type="button" className="btn-primary" onClick={() => location.reload()}>
          {t("Aç")}
        </button>
      </div>
    );
  }

  const room = selected ? client?.getRoom(selected) ?? null : null;
  const offline = syncState === SyncState.Error || syncState === SyncState.Reconnecting;

  return (
    <div className={"app" + (room ? " has-room" : "")}>
      <nav className="rail" aria-label={t("Ana menü")}>
        <LogoMark size={42} />
        <button type="button" className={"rail-btn" + (showAdmin ? "" : " active")} aria-label={t("Sohbetler")} onClick={() => { setShowAdmin(false); setSelected(null); }}>
          <ChatIcon />
        </button>
        {admin && (
          <button type="button" className={"rail-btn" + (showAdmin ? " active" : "")} aria-label={t("Yönetim")} onClick={() => setShowAdmin(true)}>
            <ShieldIcon />
          </button>
        )}
        <div className="grow" />
        {backup === "locked" && (
          <button type="button" className="rail-btn" aria-label={t("Eski mesajları aç")} onClick={() => setRecovery(true)}>
            <KeyIcon />
          </button>
        )}
        <SettingsMenu dark={dark} setDark={setDark} backupLocked={backup === "locked"} onRecovery={() => setRecovery(true)} placement="rail" />
      </nav>

      {showAdmin ? (
        <AdminPanel onClose={() => setShowAdmin(false)} />
      ) : (
      <>
      <ChatList
        selected={selected}
        onSelect={setSelected}
        ready={ready}
        menu={<SettingsMenu dark={dark} setDark={setDark} backupLocked={backup === "locked"} onRecovery={() => setRecovery(true)} placement="top" />}
        backupLocked={backup === "locked"}
        onRecovery={() => setRecovery(true)}
        onAdmin={admin ? () => setShowAdmin(true) : undefined}
      />

      {room ? (
        <RoomView room={room} onBack={() => setSelected(null)} />
      ) : (
        <section className="room empty-room">
          <LogoMark size={64} />
          <span className="muted">{t("Bir sohbet seç")}</span>
        </section>
      )}

      </>
      )}

      {offline && (
        <div className="toast" role="status">
          {t("Bağlantı koptu, yeniden bağlanılıyor…")}
        </div>
      )}
      {recovery && <RecoveryDialog onClose={() => setRecovery(false)} />}
    </div>
  );
}
