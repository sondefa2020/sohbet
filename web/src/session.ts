// Oturum, eşitleme ve şifreleme: Matrix istemcisinin tek merkezi.

import {
  ClientEvent,
  createClient,
  EventStatus,
  EventType,
  HttpApiEvent,
  KnownMembership,
  MatrixClient,
  MatrixEvent,
  MatrixEventEvent,
  MsgType,
  NotificationCountType,
  Room,
  RoomEvent,
  RoomMemberEvent,
  SyncState,
} from "matrix-js-sdk";
import { decodeRecoveryKey } from "matrix-js-sdk/lib/crypto-api";
import { decryptAttachment, encryptAttachment, type EncryptedFile } from "./attachments";

const SESSION_KEY = "tayfa_oturum";

export interface Saved {
  homeserver: string;
  userId: string;
  deviceId: string;
  accessToken: string;
}

let homeserver = "";
/** Yalnızca yerel uçtan uca testlerde (config.json'da "test": true) açılır; gerçek kurulumda yoktur. */
let testHooks = false;

/** Sunucu adresi çalışma anında public/config.json'dan okunur (aynı derleme her sunucuda çalışır). */
export async function loadConfig(): Promise<string> {
  if (homeserver) return homeserver;
  try {
    const r = await fetch("/config.json", { cache: "no-store" });
    const j = await r.json();
    homeserver = String(j.homeserver || "").replace(/\/+$/, "");
    testHooks = j.test === true;
  } catch {
    homeserver = "";
  }
  if (!homeserver) homeserver = "https://sohbet.acioglu.tr";
  return homeserver;
}

export function savedSession(): Saved | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Saved) : null;
  } catch {
    return null;
  }
}

// ------------------------------------------------------------ değişiklik yayını
// Arayüz, istemcideki her değişiklikte yeniden çizilir (useSyncExternalStore).

let version = 0;
const listeners = new Set<() => void>();
export function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
export function getVersion() {
  return version;
}
let pending = false;
function bump() {
  if (pending) return;
  pending = true;
  queueMicrotask(() => {
    pending = false;
    version++;
    listeners.forEach((l) => l());
  });
}

// ------------------------------------------------------------ istemci

export let client: MatrixClient | null = null;
export let syncState: SyncState | null = null;
/** Oturum sunucu tarafından kapatıldıysa (cihaz silindi vb.) giriş ekranına dönülür. */
export let loggedOut = false;

/** Kurtarma anahtarı girildiğinde gizli depoya erişim için bir kez kullanılır. */
let pendingRecoveryKey: Uint8Array<ArrayBuffer> | null = null;

export async function loginWithToken(loginToken: string): Promise<void> {
  const hs = await loadConfig();
  const temp = createClient({ baseUrl: hs });
  const res = await temp.loginRequest({
    type: "m.login.token",
    token: loginToken,
    initial_device_display_name: "Tayfa Web",
  });
  const saved: Saved = { homeserver: hs, userId: res.user_id, deviceId: res.device_id, accessToken: res.access_token };
  localStorage.setItem(SESSION_KEY, JSON.stringify(saved));
}

let starting: Promise<MatrixClient> | null = null;

/** Oturumu bir kez başlatır (aynı anda iki kez çağrılırsa aynı işlemi bekler). */
export function start(saved: Saved): Promise<MatrixClient> {
  if (!starting) starting = doStart(saved).catch((e) => {
    starting = null;
    throw e;
  });
  return starting;
}

async function doStart(saved: Saved): Promise<MatrixClient> {
  await loadConfig();
  homeserver = saved.homeserver;
  const c = createClient({
    baseUrl: saved.homeserver,
    userId: saved.userId,
    deviceId: saved.deviceId,
    accessToken: saved.accessToken,
    timelineSupport: true,
    cryptoCallbacks: {
      getSecretStorageKey: async ({ keys }) => {
        if (!pendingRecoveryKey) return null;
        const defaultId = await c.secretStorage.getDefaultKeyId();
        const id = defaultId && keys[defaultId] ? defaultId : Object.keys(keys)[0];
        return id ? [id, pendingRecoveryKey] : null;
      },
    },
  });
  client = c;
  loggedOut = false;
  await c.initRustCrypto({ cryptoDatabasePrefix: "tayfa-web" });

  c.on(ClientEvent.Sync, (state) => {
    syncState = state;
    bump();
  });
  c.on(RoomEvent.Timeline, () => bump());
  c.on(RoomEvent.Name, () => bump());
  c.on(RoomEvent.Receipt, () => bump());
  c.on(RoomEvent.LocalEchoUpdated, () => bump());
  c.on(MatrixEventEvent.Decrypted, (ev) => {
    bump();
    maybeNotify(ev);
  });
  c.on(RoomMemberEvent.Typing, () => bump());
  c.on(RoomEvent.Timeline, (ev, room, toStart, removed, data) => {
    if (toStart || removed || !data?.liveEvent || !room) return;
    maybeNotify(ev);
  });
  // Özel bir arkadaş çevresi: davetler otomatik kabul edilir (telefondaki uygulama gibi).
  c.on(RoomEvent.MyMembership, (room, membership) => {
    if (membership === KnownMembership.Invite) c.joinRoom(room.roomId).catch(() => undefined);
    bump();
  });
  c.on(ClientEvent.Event, (ev: MatrixEvent) => {
    if (ev.getType() === "m.room.member") bump();
  });
  c.on(HttpApiEvent.SessionLoggedOut, () => {
    loggedOut = true;
    void wipe();
    bump();
  });

  if (testHooks) {
    (window as unknown as Record<string, unknown>).__tayfaTest = {
      client: c,
      setKey: (k: Uint8Array<ArrayBuffer>) => {
        pendingRecoveryKey = k;
      },
    };
  }
  await c.startClient({ initialSyncLimit: 30, lazyLoadMembers: true });
  startPresence();
  return c;
}

export async function logout(): Promise<void> {
  const c = client;
  try {
    await c?.logout(true);
  } catch {
    /* sunucuya ulaşılamasa da yerel her şey silinir */
  }
  await wipe();
  location.reload();
}

async function wipe() {
  const c = client;
  stopPresence();
  try {
    c?.stopClient();
    await c?.clearStores();
  } catch {
    /* yok say */
  }
  localStorage.removeItem(SESSION_KEY);
  mediaCache.forEach((url) => URL.revokeObjectURL(url));
  mediaCache.clear();
}

// ------------------------------------------------------------ sohbetler

const NUDGE = "⁠⁠";
const EFFECT = "⁠⁡";
const SECRET_MARK = "⁠tayfa-gizli";

export interface ChatItem {
  id: string;
  name: string;
  preview: string;
  time: number;
  unread: number;
  isDirect: boolean;
  dmUserId?: string;
  secret: boolean;
  avatarMxc?: string;
}

/** Bir sohbetin karşı tarafı (birebir sohbette). */
export function dmPartner(room: Room): string | undefined {
  const me = client?.getUserId();
  const members = room.getMembers().filter((m) => m.membership === KnownMembership.Join || m.membership === KnownMembership.Invite);
  if (members.length !== 2) return room.getDMInviter() ?? undefined;
  return members.find((m) => m.userId !== me)?.userId;
}

export function isSecret(room: Room): boolean {
  const topic = room.currentState.getStateEvents(EventType.RoomTopic, "")?.getContent()?.topic;
  return typeof topic === "string" && topic.startsWith(SECRET_MARK);
}

export function displayable(ev: MatrixEvent): boolean {
  const type = ev.getType();
  return (
    type === EventType.RoomMessage ||
    type === EventType.RoomMessageEncrypted ||
    type === "m.poll.start" ||
    type === "org.matrix.msc3381.poll.start" ||
    type === EventType.Sticker
  );
}

/** Mesajın kısa, düz metin özeti (liste önizlemesi ve bildirimler için). */
export function describe(ev: MatrixEvent, t: (s: string) => string): string {
  if (ev.isRedacted()) return t("Bu mesaj silindi");
  if (ev.isDecryptionFailure() || ev.getType() === EventType.RoomMessageEncrypted)
    return t("Şifreli mesaj — okumak için yedek anahtarını gir");
  const type = ev.getType();
  const content = ev.getContent();
  if (type === "m.poll.start" || type === "org.matrix.msc3381.poll.start") {
    const poll = content["m.poll"] ?? content["org.matrix.msc3381.poll.start"];
    const q = poll?.question?.body ?? poll?.question?.["org.matrix.msc1767.text"] ?? "";
    return t("Anket") + (q ? ": " + q : "");
  }
  switch (content.msgtype) {
    case MsgType.Image:
      return t("Fotoğraf");
    case MsgType.Video:
      return t("Video");
    case MsgType.Audio:
      return content["org.matrix.msc3245.voice"] !== undefined ? t("Sesli mesaj") : "♪ " + (content.body || t("Dosya"));
    case MsgType.File:
      return t("Dosya") + ": " + (content.body || "");
    case MsgType.Location:
      return t("Konum");
  }
  const body: string = content.body ?? "";
  if (body.startsWith(NUDGE)) return t("Titreşim gönderdi");
  if (body.startsWith(EFFECT)) return t("Efekt gönderdi");
  return body;
}

export function chats(t: (s: string) => string): ChatItem[] {
  const c = client;
  if (!c) return [];
  return c
    .getRooms()
    .filter((r) => r.getMyMembership() === KnownMembership.Join)
    .map((room) => {
      const events = room.getLiveTimeline().getEvents();
      let last: MatrixEvent | undefined;
      for (let i = events.length - 1; i >= 0; i--) {
        if (displayable(events[i])) {
          last = events[i];
          break;
        }
      }
      const partner = dmPartner(room);
      const members = room.getJoinedMemberCount() + room.getInvitedMemberCount();
      const isDirect = !!partner && members <= 2;
      const secret = isSecret(room);
      let avatarMxc = room.getMxcAvatarUrl() ?? undefined;
      if (!avatarMxc && isDirect && partner) avatarMxc = room.getMember(partner)?.getMxcAvatarUrl() ?? undefined;
      return {
        id: room.roomId,
        name: secret ? t("Gizli sohbet") + " · " + room.name : room.name,
        preview: last ? describe(last, t) : "",
        time: last?.getTs() ?? room.getLastActiveTimestamp(),
        unread: room.getUnreadNotificationCount(NotificationCountType.Total),
        isDirect,
        dmUserId: isDirect ? partner : undefined,
        secret,
        avatarMxc,
      };
    })
    .sort((a, b) => b.time - a.time);
}

export function messages(room: Room): MatrixEvent[] {
  return room.getLiveTimeline().getEvents().filter(displayable);
}

export async function loadOlder(room: Room): Promise<void> {
  if (!client) return;
  await client.scrollback(room, 30);
  bump();
}

export function markRead(room: Room) {
  const c = client;
  if (!c) return;
  const events = room.getLiveTimeline().getEvents();
  const last = events[events.length - 1];
  if (last && last.getId() && !last.isSending() && last.getSender() !== c.getUserId()) {
    c.sendReadReceipt(last).catch(() => undefined);
  }
}

export async function sendText(roomId: string, text: string) {
  await client?.sendMessage(roomId, { msgtype: MsgType.Text, body: text });
}

export const MAX_UPLOAD = 100 * 1024 * 1024;

async function imageSize(file: File): Promise<{ w: number; h: number } | null> {
  if (!file.type.startsWith("image/")) return null;
  try {
    const bmp = await createImageBitmap(file);
    const size = { w: bmp.width, h: bmp.height };
    bmp.close();
    return size;
  } catch {
    return null;
  }
}

/** Dosya/fotoğraf gönderir; şifreli sohbette önce tarayıcıda şifreler. */
export async function sendFile(room: Room, file: File) {
  const c = client;
  if (!c) return;
  const encrypted = room.hasEncryptionStateEvent();
  const msgtype = file.type.startsWith("image/")
    ? MsgType.Image
    : file.type.startsWith("video/")
      ? MsgType.Video
      : file.type.startsWith("audio/")
        ? MsgType.Audio
        : MsgType.File;
  const size = await imageSize(file);
  const info: Record<string, unknown> = { mimetype: file.type || "application/octet-stream", size: file.size };
  if (size) {
    info.w = size.w;
    info.h = size.h;
  }
  const content: Record<string, unknown> = { msgtype, body: file.name, info };
  if (encrypted) {
    const { data, info: keyInfo } = await encryptAttachment(await file.arrayBuffer());
    const up = await c.uploadContent(new Blob([data]), { type: "application/octet-stream", includeFilename: false });
    content.file = { ...keyInfo, url: up.content_uri };
  } else {
    const up = await c.uploadContent(file, { type: file.type, name: file.name });
    content.url = up.content_uri;
  }
  await c.sendMessage(room.roomId, content as never);
}

// ------------------------------------------------------------ medya

const mediaCache = new Map<string, string>();

/** Medyayı oturumla indirir, gerekiyorsa çözer ve tarayıcıda gösterilebilir bir adres döndürür. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export async function mediaUrl(content: { url?: string; file?: EncryptedFile; info?: { mimetype?: string } } | Record<string, any>): Promise<string | null> {
  const mxc = content.file?.url ?? content.url;
  if (!mxc || !mxc.startsWith("mxc://") || !client) return null;
  const cached = mediaCache.get(mxc);
  if (cached) return cached;
  const [server, id] = mxc.slice(6).split("/");
  const r = await fetch(`${homeserver}/_matrix/client/v1/media/download/${encodeURIComponent(server)}/${encodeURIComponent(id)}`, {
    headers: { Authorization: "Bearer " + client.getAccessToken() },
  });
  if (!r.ok) return null;
  let data = await r.arrayBuffer();
  if (content.file) data = await decryptAttachment(data, content.file);
  const url = URL.createObjectURL(new Blob([data], { type: content.info?.mimetype || "application/octet-stream" }));
  mediaCache.set(mxc, url);
  return url;
}

/** Profil fotoğrafı (küçük boy). */
export async function avatarUrl(mxc: string): Promise<string | null> {
  if (!client) return null;
  const key = "avatar:" + mxc;
  const cached = mediaCache.get(key);
  if (cached) return cached;
  const [server, id] = mxc.slice(6).split("/");
  const r = await fetch(
    `${homeserver}/_matrix/client/v1/media/thumbnail/${encodeURIComponent(server)}/${encodeURIComponent(id)}?width=96&height=96&method=crop`,
    { headers: { Authorization: "Bearer " + client.getAccessToken() } },
  );
  if (!r.ok) return null;
  const url = URL.createObjectURL(await r.blob());
  mediaCache.set(key, url);
  return url;
}

// ------------------------------------------------------------ çevrimiçi durumu
// Tayfa'nın kendi servisi: telefondaki uygulamayla aynı çevrimiçi noktaları.

export let online = new Set<string>();
let presenceTimer: number | undefined;

async function reportPresence(active: boolean) {
  const c = client;
  if (!c) return;
  try {
    const r = await fetch(`${homeserver}/bayx/presence`, {
      method: "POST",
      headers: { Authorization: "Bearer " + c.getAccessToken(), "Content-Type": "application/json" },
      body: JSON.stringify({ active }),
    });
    if (!r.ok) return;
    const j = await r.json();
    online = new Set<string>(j.online ?? []);
    bump();
  } catch {
    /* geçici ağ hatası */
  }
}

function startPresence() {
  stopPresence();
  void reportPresence(true);
  presenceTimer = window.setInterval(() => void reportPresence(document.visibilityState === "visible"), 30_000);
}

function stopPresence() {
  if (presenceTimer) window.clearInterval(presenceTimer);
  presenceTimer = undefined;
}

// ------------------------------------------------------------ mesaj yedeği

/** Kurtarma (yedek) anahtarı var mı ve bu tarayıcı eski mesajları açabiliyor mu? */
export async function backupState(): Promise<"none" | "locked" | "ready"> {
  const crypto = client?.getCrypto();
  if (!crypto) return "none";
  const info = await crypto.getKeyBackupInfo().catch(() => null);
  if (!info) return "none";
  const key = await crypto.getSessionBackupPrivateKey().catch(() => null);
  return key ? "ready" : "locked";
}

/**
 * Telefonda oluşturulan yedek anahtarıyla: bu tarayıcıyı doğrulanmış cihaz yapar ve eski
 * mesajların anahtarlarını geri yükler. Hata varsa açıklama döner.
 */
export async function unlockWithRecoveryKey(key: string): Promise<{ error?: string; restored?: number }> {
  const c = client;
  const crypto = c?.getCrypto();
  if (!c || !crypto) return { error: "Oturum yok." };
  let bytes: Uint8Array<ArrayBuffer>;
  try {
    bytes = decodeRecoveryKey(key.trim());
  } catch {
    return { error: "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz." };
  }
  const found = await c.secretStorage.getKey();
  if (!found) return { error: "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz." };
  const [, info] = found;
  const ok = await c.secretStorage.checkKey(bytes, info as never).catch(() => false);
  if (!ok) return { error: "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz." };
  pendingRecoveryKey = bytes;
  try {
    // Çapraz imza anahtarları gizli depodan alınır: bu tarayıcı "doğrulanmış" olur.
    await crypto.bootstrapCrossSigning({});
    await crypto.loadSessionBackupPrivateKeyFromSecretStorage();
    await crypto.checkKeyBackupAndEnable();
    const result = await crypto.restoreKeyBackup();
    bump();
    return { restored: result.imported };
  } catch {
    return { error: "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz." };
  } finally {
    pendingRecoveryKey = null;
  }
}

export function eventStatus(ev: MatrixEvent): "sending" | "failed" | "sent" {
  if (ev.status === EventStatus.NOT_SENT) return "failed";
  if (ev.isSending()) return "sending";
  return "sent";
}

export function retry(ev: MatrixEvent) {
  const room = client?.getRoom(ev.getRoomId() ?? "");
  if (room && client) client.resendEvent(ev, room).catch(() => undefined);
}

export function getHomeserver() {
  return homeserver;
}


// ------------------------------------------------------------ yazıyor, okundu

/** Bu sohbette şu an yazmakta olan diğer kişilerin adları. */
export function typingNames(room: Room): string[] {
  const me = client?.getUserId();
  return room
    .getMembers()
    .filter((m) => m.typing && m.userId !== me)
    .map((m) => m.name);
}

let typingRoom = "";
let typingUntil = 0;

/** Yazmaya başlayınca/bırakınca karşı tarafa "yazıyor…" bildirir (sık istek göndermeden). */
export function setTyping(roomId: string, typing: boolean) {
  const c = client;
  if (!c) return;
  const now = Date.now();
  if (typing) {
    if (typingRoom === roomId && now < typingUntil) return;
    typingRoom = roomId;
    typingUntil = now + 4000;
    c.sendTyping(roomId, true, 6000).catch(() => undefined);
  } else if (typingRoom === roomId) {
    typingRoom = "";
    typingUntil = 0;
    c.sendTyping(roomId, false, 0).catch(() => undefined);
  }
}

/** Kendi mesajımı karşı taraf okudu mu? */
export function readByOthers(room: Room, ev: MatrixEvent): boolean {
  const me = client?.getUserId();
  return room.getUsersReadUpTo(ev).some((u) => u !== me);
}

// ------------------------------------------------------------ bildirim ve ses

/** Ekranda açık olan sohbet: onun mesajları için bildirim gösterilmez. */
export let openRoomId: string | null = null;
export function setOpenRoom(id: string | null) {
  openRoomId = id;
}

const incomingSound = typeof Audio !== "undefined" ? new Audio("/gelen.wav") : null;
const notified = new Set<string>();
const startedAt = Date.now();

export function notificationsAllowed(): boolean {
  return typeof Notification !== "undefined" && Notification.permission === "granted";
}

export async function askNotifications(): Promise<boolean> {
  if (typeof Notification === "undefined") return false;
  if (Notification.permission === "granted") return true;
  return (await Notification.requestPermission()) === "granted";
}

/** Yeni gelen mesaj: ses çalar, sekme gizliyse masaüstü bildirimi gösterir (içerik gösterilmez). */
function maybeNotify(ev: MatrixEvent) {
  const c = client;
  if (!c || !displayable(ev) || ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) return;
  const id = ev.getId();
  if (!id || notified.has(id)) return;
  if (ev.getSender() === c.getUserId() || ev.getTs() < startedAt - 5000 || Date.now() - ev.getTs() > 60_000) return;
  const roomId = ev.getRoomId() ?? "";
  const room = c.getRoom(roomId);
  if (!room) return;
  notified.add(id);
  const visible = document.visibilityState === "visible";
  if (visible && openRoomId === roomId) return;
  if (localStorage.getItem("tayfa_ses") !== "kapali") incomingSound?.play().catch(() => undefined);
  if (!visible && notificationsAllowed()) {
    const n = new Notification(room.name || "Tayfa", { body: tNew(), icon: "/icon-192.png", tag: roomId });
    n.onclick = () => {
      window.focus();
      window.dispatchEvent(new CustomEvent("tayfa-ac", { detail: roomId }));
      n.close();
    };
  }
}

let tNew = () => "Yeni mesaj";
export function setNotificationText(fn: () => string) {
  tNew = fn;
}

/** Sekme başlığında okunmamış mesaj sayısı: "(3) Tayfa". */
export function unreadTotal(): number {
  const c = client;
  if (!c) return 0;
  return c
    .getRooms()
    .filter((r) => r.getMyMembership() === KnownMembership.Join)
    .reduce((sum, r) => sum + r.getUnreadNotificationCount(NotificationCountType.Total), 0);
}
