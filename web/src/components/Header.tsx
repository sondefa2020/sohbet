import { useState } from "react";
import { getLang, setLang, t, type Lang } from "../i18n";
import { LogoMark } from "./Icons";

/** Giriş sayfasının üst şeridi: logo ve dil seçimi. */
export function Header() {
  const [lang, setL] = useState<Lang>(getLang());
  return (
    <header className="login-header">
      <div className="row-12">
        <LogoMark size={40} />
        <span className="brand">Tayfa</span>
        <span className="pill">Web</span>
      </div>
      <label className="lang muted">
        <span>{t("Dil")}</span>
        <select
          value={lang}
          onChange={(e) => {
            setLang(e.target.value as Lang);
            setL(e.target.value as Lang);
            location.reload();
          }}
        >
          <option value="tr">Türkçe</option>
          <option value="en">English</option>
          <option value="ar">العربية</option>
        </select>
      </label>
    </header>
  );
}
