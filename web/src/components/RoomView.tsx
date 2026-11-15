import { useEffect, useLayoutEffect, useRef, useState, type ClipboardEvent, type DragEvent, type FormEvent, type KeyboardEvent } from "react";
import { MsgType, type MatrixEvent, type Room } from "matrix-js-sdk";
import { locale, t } from "../i18n";
import { client, describe, dmPartner, eventStatus, isSecret, loadOlder, MAX_UPLOAD, markRead, mediaUrl, messages, online, readByOthers, retry, sendFile, sendText, setTyping, typingNames } from "../session";
import { Avatar } from "./Avatar";
import { BackIcon, ClipIcon, FileIcon, LockIcon, SendIcon } from "./Icons";

const outgoingSound = new Audio("/giden.wav");
outgoingSound.volume = 0.6;

function dayLabel(ts: number) {
  const d = new Date(ts);
  const today = new Date();
  const y = new Date();
  y.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return t("Bugün");
  if (d.toDateString() === y.toDateString()) return t("Dün");
  return d.toLocaleDateString(locale(), { day: "numeric", month: "long", year: "numeric" });
}

function clock(ts: number) {
  return new Date(ts).toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit" });
}

function formatSize(bytes?: number) {
  if (!bytes) return "";
  if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024)) + " KB";
  return (bytes / 1024 / 1024).toFixed(1) + " MB";
}

/** Görsel, video ve dosya içerikleri: oturumla indirilip (gerekirse çözülüp) gösterilir. */
function Media({ ev }: { ev: MatrixEvent }) {
  const content = ev.getContent();
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const kind = content.msgtype as string;
  const auto = kind === MsgType.Image || kind === MsgType.Video || kind === MsgType.Audio;
  useEffect(() => {
    let alive = true;
    if (auto) {
      mediaUrl(content)
        .then((u) => {
          if (!alive) return;
          if (u) setUrl(u);
          else setFailed(true);
        })
        .catch(() => alive && setFailed(true));
    }
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ev.getId()]);

  const w = content.info?.w as number | undefined;
  const h = content.info?.h as number | undefined;
  const ratio = w && h ? `${w} / ${h}` : "4 / 3";

  if (kind === MsgType.Image) {
    return url ? (
      <a href={url} target="_blank" rel="noreferrer" className="media-link">
        <img className="media-img" src={url} alt={content.body ?? t("Fotoğraf")} style={{ aspectRatio: ratio }} />
      </a>
    ) : (
      <div className="media-ph" style={{ aspectRatio: ratio }}>{failed ? t("Fotoğraf") : t("Yükleniyor…")}</div>
    );
  }
  if (kind === MsgType.Video) {
    return url ? <video className="media-img" src={url} controls preload="metadata" /> : <div className="media-ph">{t("Yükleniyor…")}</div>;
  }
  if (kind === MsgType.Audio) {
    return url ? <audio src={url} controls preload="metadata" /> : <div className="muted small">{t("Yükleniyor…")}</div>;
  }
  // Dosya: indirmek için dokununca açılır.
  const download = async () => {
    const u = url ?? (await mediaUrl(content));
    if (!u) return;
    setUrl(u);
    const a = document.createElement("a");
    a.href = u;
    a.download = content.body || "dosya";
    a.click();
  };
  return (
    <button type="button" className="file-chip" onClick={() => void download()}>
      <FileIcon size={22} />
      <span className="file-text">
        <span className="file-name">{content.body || t("Dosya")}</span>
        <span className="small">{formatSize(content.info?.size)} · {t("İndir")}</span>
      </span>
    </button>
  );
}

function Bubble({ ev, mine, showSender, read }: { ev: MatrixEvent; mine: boolean; showSender: boolean; read: boolean }) {
  const content = ev.getContent();
  const media =
    !ev.isRedacted() &&
    !ev.isDecryptionFailure() &&
    [MsgType.Image, MsgType.Video, MsgType.Audio, MsgType.File].includes(content.msgtype as MsgType);
  const status = mine ? eventStatus(ev) : "sent";
  const special = ev.isRedacted() || ev.isDecryptionFailure() || ev.getType() === "m.room.encrypted";
  const sender = ev.sender?.name ?? ev.getSender() ?? "";
  return (
    <div className={"msg-row " + (mine ? "mine" : "theirs")}>
      <div className={"bubble" + (special ? " special" : "")}>
        {showSender && !mine && <span className="sender">{sender}</span>}
        {media ? <Media ev={ev} /> : <span className="msg-text">{describe(ev, t)}</span>}
        <span className="msg-meta">
          {status === "failed" ? (
            <button type="button" className="retry" onClick={() => retry(ev)}>
              {t("Gönderilemedi")}
            </button>
          ) : status === "sending" ? (
            t("Gönderiliyor…")
          ) : (
            <>
              {clock(ev.getTs())}
              {mine && (
                <span className={"ticks" + (read ? " read" : "")} aria-label={read ? t("Okundu") : t("Gönderildi")}>
                  {read ? "✓✓" : "✓"}
                </span>
              )}
            </>
          )}
        </span>
      </div>
    </div>
  );
}

export function RoomView({ room, onBack }: { room: Room; onBack: () => void }) {
  const me = client?.getUserId();
  const list = messages(room);
  const scroller = useRef<HTMLDivElement>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [loadingOlder, setLoadingOlder] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const atBottom = useRef(true);
  const lastId = list[list.length - 1]?.getId();

  const partner = dmPartner(room);
  const members = room.getJoinedMemberCount();
  const isDirect = !!partner && members <= 2;
  const secret = isSecret(room);
  const typing = typingNames(room);
  const subtitle = typing.length > 0
    ? (isDirect ? "" : typing.join(", ") + " ") + t("yazıyor…")
    : isDirect
    ? partner && online.has(partner)
      ? t("Çevrimiçi") + " · " + t("şifreli")
      : t("şifreli")
    : `${t("Grup")} · ${members} ${t("kişi")}`;

  // Yeni mesaj gelince (kullanıcı en alttaysa) en alta kaydır; okundu bilgisini gönder.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [lastId, room.roomId]);
  useEffect(() => {
    atBottom.current = true;
    setText("");
    setError("");
  }, [room.roomId]);
  useEffect(() => {
    if (document.visibilityState === "visible") markRead(room);
  }, [lastId, room]);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (el.scrollTop < 60 && !loadingOlder) {
      setLoadingOlder(true);
      const before = el.scrollHeight;
      loadOlder(room)
        .then(() => {
          requestAnimationFrame(() => {
            if (scroller.current) scroller.current.scrollTop += scroller.current.scrollHeight - before;
          });
        })
        .finally(() => setLoadingOlder(false));
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const body = text.trim();
    if (!body) return;
    setText("");
    atBottom.current = true;
    setTyping(room.roomId, false);
    sendText(room.roomId, body)
      .then(() => {
        if (localStorage.getItem("tayfa_ses") !== "kapali") outgoingSound.play().catch(() => undefined);
      })
      .catch(() => setError(t("Gönderilemedi")));
  };

  // Enter gönderir, Shift+Enter yeni satır açar.
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit(e as unknown as FormEvent);
    }
  };

  // Panodan yapıştırılan fotoğraf ya da dosya doğrudan gönderilir.
  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    if (e.clipboardData.files.length > 0) {
      e.preventDefault();
      void pick(e.clipboardData.files);
    }
  };

  // Sohbetin üstüne sürüklenip bırakılan dosyalar gönderilir.
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    if (e.dataTransfer.files.length > 0) void pick(e.dataTransfer.files);
  };

  const pick = async (files: FileList | null) => {
    if (!files) return;
    for (const f of Array.from(files)) {
      if (f.size > MAX_UPLOAD) {
        setError(t("Dosya çok büyük (en fazla 100 MB)."));
        continue;
      }
      atBottom.current = true;
      await sendFile(room, f).catch(() => setError(t("Gönderilemedi")));
    }
    if (fileInput.current) fileInput.current.value = "";
  };

  // Okundu tiki: karşı tarafın okuduğu son mesaja kadar olan kendi mesajlarım.
  let readUpTo = -1;
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i].getSender() === me && readByOthers(room, list[i])) {
      readUpTo = i;
      break;
    }
  }
  const [dragging, setDragging] = useState(false);

  let lastDay = "";
  return (
    <section
      className={"room" + (dragging ? " dragging" : "")}
      aria-label={t("Açık sohbet")}
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes("Files")) {
          e.preventDefault();
          setDragging(true);
        }
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      {dragging && <div className="drop-hint">{t("Göndermek için bırak")}</div>}
      <header className="room-head">
        <button type="button" className="icon-btn only-narrow" aria-label={t("Geri")} onClick={onBack}>
          <BackIcon />
        </button>
        <Avatar name={room.name} size={44} colorKey={room.roomId} mxc={room.getMxcAvatarUrl() ?? (partner ? room.getMember(partner)?.getMxcAvatarUrl() : undefined) ?? undefined} />
        <span className="room-title">
          <span className="room-name">{secret ? t("Gizli sohbet") + " · " + room.name : room.name}</span>
          <span className={"room-sub" + ((isDirect && partner && online.has(partner)) || typing.length > 0 ? " on" : "")}>{subtitle}</span>
        </span>
      </header>

      <div className="timeline" ref={scroller} onScroll={onScroll}>
        <div className="e2e-note">
          <LockIcon size={14} />
          <span>{t("Mesajlar uçtan uca şifreli. Tayfa dahil kimse okuyamaz.")}</span>
        </div>
        {list.map((ev, index) => {
          const day = dayLabel(ev.getTs());
          const showDay = day !== lastDay;
          lastDay = day;
          return (
            <div key={ev.getId() ?? ev.getTxnId()}>
              {showDay && <div className="day">{day}</div>}
              <Bubble ev={ev} mine={ev.getSender() === me} showSender={!isDirect} read={index <= readUpTo} />
            </div>
          );
        })}
      </div>

      {error && (
        <div className="room-error" role="alert">
          {error}
        </div>
      )}
      <form className="composer" onSubmit={submit}>
        <input ref={fileInput} type="file" multiple hidden onChange={(e) => void pick(e.target.files)} />
        <button type="button" className="icon-btn" aria-label={t("Dosya ekle")} onClick={() => fileInput.current?.click()}>
          <ClipIcon />
        </button>
        <label className="composer-field">
          <span className="sr-only">{t("Mesaj")}</span>
          <textarea
            rows={1}
            placeholder={t("Mesaj yaz")}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setTyping(room.roomId, e.target.value.trim().length > 0);
            }}
            onKeyDown={onKeyDown}
            onPaste={onPaste}
            onBlur={() => setTyping(room.roomId, false)}
            autoComplete="off"
          />
        </label>
        <button type="submit" className="send-btn" aria-label={t("Gönder")} disabled={!text.trim()}>
          <SendIcon size={20} />
        </button>
      </form>
    </section>
  );
}
