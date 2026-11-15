import { useState, type FormEvent } from "react";
import { t, tf } from "../i18n";
import { unlockWithRecoveryKey } from "../session";
import { KeyIcon } from "./Icons";

/** Eski şifreli mesajları açmak için telefonda oluşturulan yedek anahtarı. */
export function RecoveryDialog({ onClose }: { onClose: () => void }) {
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!key.trim() || busy) return;
    setBusy(true);
    setError("");
    const result = await unlockWithRecoveryKey(key);
    setBusy(false);
    if (result.error) setError(t(result.error));
    else {
      setKey("");
      setDone(tf("%s mesaj anahtarı geri yüklendi.", result.restored ?? 0));
    }
  };

  return (
    <div className="dialog-backdrop" role="presentation" onClick={onClose}>
      <form className="dialog" role="dialog" aria-modal="true" aria-labelledby="kurtarma-baslik" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <div className="dialog-icon" aria-hidden="true">
          <KeyIcon size={24} />
        </div>
        <h2 id="kurtarma-baslik">{t("Eski mesajları aç")}</h2>
        <p className="muted">{t("Eski şifreli mesajları bu tarayıcıda okumak için telefonda oluşturduğun yedek anahtarını yaz.")}</p>
        {done ? (
          <p className="ok" role="status">
            {done}
          </p>
        ) : (
          <label className="field">
            <span>{t("Yedek anahtarı")}</span>
            <textarea
              rows={3}
              value={key}
              onChange={(e) => setKey(e.target.value)}
              autoComplete="off"
              spellCheck={false}
              placeholder="EsT1 abcd …"
            />
          </label>
        )}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            {done ? t("Kapat") : t("Şimdi değil")}
          </button>
          {!done && (
            <button type="submit" className="btn-primary" disabled={busy || !key.trim()}>
              {busy ? t("Mesajlar açılıyor…") : t("Aç")}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
