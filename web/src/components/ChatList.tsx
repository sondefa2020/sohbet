import { useMemo, useState } from "react";
import { locale, t } from "../i18n";
import { chats, online } from "../session";
import { Avatar } from "./Avatar";
import { SearchIcon } from "./Icons";

function timeLabel(ts: number) {
  if (!ts) return "";
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return d.toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === yesterday.toDateString()) return t("Dün");
  return d.toLocaleDateString(locale(), { day: "2-digit", month: "2-digit" });
}

export function ChatList({ selected, onSelect, ready }: { selected: string | null; onSelect: (id: string) => void; ready: boolean }) {
  const [query, setQuery] = useState("");
  const all = chats(t);
  const list = useMemo(() => {
    const q = query.trim().toLocaleLowerCase("tr");
    return q ? all.filter((c) => c.name.toLocaleLowerCase("tr").includes(q)) : all;
  }, [all, query]);

  return (
    <aside className="chatlist" aria-label={t("Sohbet listesi")}>
      <div className="chatlist-head">
        <h1>{t("Sohbetler")}</h1>
        <label className="search">
          <SearchIcon size={18} />
          <span className="sr-only">{t("Sohbetlerde ara")}</span>
          <input type="search" placeholder={t("Ara")} value={query} onChange={(e) => setQuery(e.target.value)} />
        </label>
      </div>
      <ul className="chat-items">
        {list.map((c) => (
          <li key={c.id}>
            <button type="button" className={"chat-row" + (c.id === selected ? " selected" : "")} onClick={() => onSelect(c.id)}>
              <Avatar name={c.name} mxc={c.avatarMxc} online={!!c.dmUserId && online.has(c.dmUserId)} colorKey={c.id} />
              <span className="chat-text">
                <span className="chat-line">
                  <span className="chat-name">{c.name}</span>
                  <span className="chat-time">{timeLabel(c.time)}</span>
                </span>
                <span className="chat-line">
                  <span className="chat-preview">{c.preview}</span>
                  {c.unread > 0 && <span className="badge">{c.unread}</span>}
                </span>
              </span>
            </button>
          </li>
        ))}
        {list.length === 0 && (
          <li className="empty muted">{!ready ? t("Sohbetler yükleniyor…") : query ? t("Eşleşen sohbet yok.") : t("Henüz sohbet yok.")}</li>
        )}
      </ul>
    </aside>
  );
}
