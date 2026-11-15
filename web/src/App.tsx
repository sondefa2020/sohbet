import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { SyncState } from "matrix-js-sdk";
import { getLang, setLang, t, type Lang } from "./i18n";
import { backupState, client, getVersion, loggedOut, logout, savedSession, start, subscribe, syncState } from "./session";
import { ChatList } from "./components/ChatList";
import { RoomView } from "./components/RoomView";
import { Login } from "./components/Login";
import { RecoveryDialog } from "./components/Recovery";
import { ChatIcon, KeyIcon, LogoMark, SettingsIcon } from "./components/Icons";

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
  const [menu, setMenu] = useState(false);
  const [recovery, setRecovery] = useState(false);
  const [backup, setBackup] = useState<"none" | "locked" | "ready">("none");
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
        <button type="button" className="rail-btn active" aria-label={t("Sohbetler")} onClick={() => setSelected(null)}>
          <ChatIcon />
        </button>
        <div className="grow" />
        {backup === "locked" && (
          <button type="button" className="rail-btn" aria-label={t("Eski mesajları aç")} onClick={() => setRecovery(true)}>
            <KeyIcon />
          </button>
        )}
        <div className="menu-wrap">
          <button type="button" className="rail-btn" aria-label={t("Ayarlar")} aria-expanded={menu} onClick={() => setMenu((m) => !m)}>
            <SettingsIcon />
          </button>
          {menu && (
            <div className="menu" role="menu">
              <label className="menu-row">
                <span>{t("Karanlık mod")}</span>
                <input type="checkbox" checked={dark} onChange={(e) => setDark(e.target.checked)} />
              </label>
              <label className="menu-row">
                <span>{t("Dil")}</span>
                <select
                  value={getLang()}
                  onChange={(e) => {
                    setLang(e.target.value as Lang);
                    location.reload();
                  }}
                >
                  <option value="tr">Türkçe</option>
                  <option value="en">English</option>
                  <option value="ar">العربية</option>
                </select>
              </label>
              {backup === "locked" && (
                <button type="button" className="menu-row" onClick={() => setRecovery(true)}>
                  {t("Eski mesajları aç")}
                </button>
              )}
              <button type="button" className="menu-row danger" onClick={() => void logout()}>
                {t("Çıkış yap")}
              </button>
            </div>
          )}
        </div>
      </nav>

      <ChatList selected={selected} onSelect={setSelected} ready={ready} />

      {room ? (
        <RoomView room={room} onBack={() => setSelected(null)} />
      ) : (
        <section className="room empty-room">
          <LogoMark size={64} />
          <span className="muted">{t("Bir sohbet seç")}</span>
        </section>
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
