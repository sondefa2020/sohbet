import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { t, tf } from "../i18n";
import { loadConfig, loginWithToken } from "../session";
import { LockIcon } from "./Icons";
import { Header } from "./Header";

interface Pairing {
  pairingId: string;
  code: string;
  qr: string;
  expiresAt: number;
}

/** WhatsApp Web tarzı giriş: QR ya da 6 haneli kod, telefondan onay. Şifre yok. */
export function Login({ onLoggedIn, notice }: { onLoggedIn: () => void; notice?: string }) {
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [qrSvg, setQrSvg] = useState("");
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [now, setNow] = useState(Date.now());
  const busy = useRef(false);

  const start = useCallback(async () => {
    setError("");
    setStatus("");
    setPairing(null);
    try {
      const hs = await loadConfig();
      const r = await fetch(hs + "/bayx/v3/web/start", { method: "POST" });
      if (!r.ok) throw new Error();
      const d = await r.json();
      setPairing({ pairingId: d.pairing_id, code: d.code, qr: d.qr, expiresAt: Date.now() + (d.expires_in ?? 300) * 1000 });
      setQrSvg(await QRCode.toString(d.qr, { type: "svg", margin: 1, errorCorrectionLevel: "M", color: { dark: "#1F1B18", light: "#FFFFFF" } }));
    } catch {
      setError(t("Kod alınamadı. İnternet bağlantını kontrol et."));
    }
  }, []);

  useEffect(() => {
    void start();
  }, [start]);

  // Onay bekleme: iki saniyede bir sor. Telefonda tarayıcı arka plana geçince zamanlayıcı durur;
  // sayfaya dönülünce hemen sorulur ve süre dolmuş olsa bile önce onay gelmiş mi diye bakılır.
  useEffect(() => {
    if (!pairing) return;
    let stopped = false;
    const check = async () => {
      if (stopped || busy.current) return;
      busy.current = true;
      setNow(Date.now());
      try {
        const hs = await loadConfig();
        const r = await fetch(hs + "/bayx/v3/web/poll", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ pairing_id: pairing.pairingId }),
        });
        const d = await r.json();
        if (stopped) return;
        if (d.status === "ready" && d.login_token) {
          stopped = true;
          setStatus(t("Onaylandı, giriş yapılıyor…"));
          try {
            await loginWithToken(d.login_token);
            onLoggedIn();
          } catch {
            setStatus("");
            setError(t("Giriş yapılamadı. Yeni kod al ve tekrar dene."));
          }
        } else if (d.status === "gone" || Date.now() > pairing.expiresAt) {
          stopped = true;
          void start();
        }
      } catch {
        /* geçici ağ hatası: bir sonraki turda tekrar dener */
      } finally {
        busy.current = false;
      }
    };
    const timer = window.setInterval(() => void check(), 2000);
    const onVisible = () => {
      if (document.visibilityState === "visible") void check();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    window.addEventListener("pageshow", onVisible);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
      window.removeEventListener("pageshow", onVisible);
    };
  }, [pairing, start, onLoggedIn]);

  const left = pairing ? Math.max(0, Math.round((pairing.expiresAt - now) / 1000)) : 0;
  const leftLabel = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  const code = pairing ? pairing.code.replace(/\D/g, "").replace(/^(\d{3})(\d{3})$/, "$1 $2") : "";

  return (
    <div className="login-page">
      <Header />
      <main className="login-main">
        <section className="login-card">
          <div className="login-info">
            <div className="stack-10">
              <h1 className="login-title">{t("Tayfa'yı bilgisayarında kullan")}</h1>
              <p className="muted lead">
                {t("Şifre gerekmez. Telefonundaki Tayfa ile bu kodu okut; sohbetlerin uçtan uca şifreli olarak burada açılır.")}
              </p>
            </div>
            <ol className="steps">
              <li>
                <span className="step-no">1</span>
                <span>{t("Telefonunda Tayfa'yı aç.")}</span>
              </li>
              <li>
                <span className="step-no">2</span>
                <span>{t("Profil › Web'e bağlan bölümüne gir.")}</span>
              </li>
              <li>
                <span className="step-no">3</span>
                <span>{t("Kodu okut ya da altındaki 6 haneli kodu yaz, sonra telefonda Onayla'ya dokun.")}</span>
              </li>
            </ol>
            <div className="login-note muted">
              <LockIcon size={18} />
              <span>{t("Bu oturum yalnızca bu tarayıcıda açılır. Ortak bir bilgisayardaysan işin bitince çıkış yap.")}</span>
            </div>
          </div>

          <div className="login-qr">
            <div className="qr-box" role="img" aria-label="QR" dangerouslySetInnerHTML={{ __html: qrSvg }} />
            {pairing && (
              <div className="code-wrap">
                <span className="muted small">{t("ya da telefonda bu kodu yaz")}</span>
                <span className="code">{code}</span>
              </div>
            )}
            {notice ? (
              <div className="error" role="alert">
                {notice}
              </div>
            ) : null}
            {status ? (
              <div className="wait">{status}</div>
            ) : error ? (
              <div className="error" role="alert">
                {error}
              </div>
            ) : pairing ? (
              <div className="wait muted">
                <span className="dot-green" aria-hidden="true" />
                <span>
                  {t("Telefonun bekleniyor")} · {tf("kod %s içinde yenilenir", leftLabel)}
                </span>
              </div>
            ) : null}
            <button type="button" className="btn-ghost" onClick={() => void start()}>
              {t("Yeni kod al")}
            </button>
          </div>
        </section>
      </main>
    </div>
  );
}
