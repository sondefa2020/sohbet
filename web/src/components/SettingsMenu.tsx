import { useEffect, useRef, useState } from "react";
import { getLang, setLang, t, type Lang } from "../i18n";
import { askNotifications, logout, notificationsAllowed } from "../session";
import { SettingsIcon } from "./Icons";

/** Ayarlar: karanlık mod, bildirim, ses, dil, eski mesajları açma ve çıkış. */
export function SettingsMenu({
  dark,
  setDark,
  backupLocked,
  onRecovery,
  placement,
}: {
  dark: boolean;
  setDark: (v: boolean) => void;
  backupLocked: boolean;
  onRecovery: () => void;
  placement: "rail" | "top";
}) {
  const [open, setOpen] = useState(false);
  const [notify, setNotify] = useState(notificationsAllowed());
  const [sound, setSound] = useState(localStorage.getItem("tayfa_ses") !== "kapali");
  const wrap = useRef<HTMLDivElement>(null);

  // Menü dışına dokununca ya da Esc'e basınca kapanır.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const supportsNotify = typeof Notification !== "undefined";

  return (
    <div className={"menu-wrap " + placement} ref={wrap}>
      <button
        type="button"
        className={placement === "rail" ? "rail-btn" : "icon-btn"}
        aria-label={t("Ayarlar")}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <SettingsIcon />
      </button>
      {open && (
        <div className={"menu " + placement} role="menu">
          <label className="menu-row">
            <span>{t("Karanlık mod")}</span>
            <input type="checkbox" checked={dark} onChange={(e) => setDark(e.target.checked)} />
          </label>
          {supportsNotify && (
            <label className="menu-row">
              <span>{t("Masaüstü bildirimleri")}</span>
              <input
                type="checkbox"
                checked={notify}
                onChange={async (e) => {
                  if (e.target.checked) setNotify(await askNotifications());
                  else setNotify(false);
                }}
                disabled={notify && Notification.permission === "granted"}
              />
            </label>
          )}
          <label className="menu-row">
            <span>{t("Mesaj sesi")}</span>
            <input
              type="checkbox"
              checked={sound}
              onChange={(e) => {
                setSound(e.target.checked);
                localStorage.setItem("tayfa_ses", e.target.checked ? "acik" : "kapali");
              }}
            />
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
          {backupLocked && (
            <button
              type="button"
              className="menu-row"
              onClick={() => {
                setOpen(false);
                onRecovery();
              }}
            >
              {t("Eski mesajları aç")}
            </button>
          )}
          <button type="button" className="menu-row danger" onClick={() => void logout()}>
            {t("Çıkış yap")}
          </button>
        </div>
      )}
    </div>
  );
}
