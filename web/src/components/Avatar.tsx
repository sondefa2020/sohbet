import { useEffect, useState } from "react";
import { avatarUrl } from "../session";

const COLORS = ["#8E6B9E", "#4F7A8C", "#6E8B4F", "#B07A4E", "#A5566A", "#5D6FA3", "#7A6A4F", "#4F8C7E"];

function colorFor(key: string) {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return COLORS[Math.abs(h) % COLORS.length];
}

/** Profil fotoğrafı; yoksa adın baş harfi renkli dairede. */
export function Avatar({ name, mxc, size = 48, online = false, colorKey }: { name: string; mxc?: string; size?: number; online?: boolean; colorKey?: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    setSrc(null);
    if (mxc) avatarUrl(mxc).then((u) => alive && setSrc(u)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [mxc]);
  const initial = (name.trim()[0] ?? "?").toLocaleUpperCase("tr");
  return (
    <span className="avatar" style={{ width: size, height: size, background: colorFor(colorKey ?? name), fontSize: size * 0.36 }}>
      {src ? <img src={src} alt="" /> : initial}
      {online && <span className="presence" aria-label="çevrimiçi" />}
    </span>
  );
}
