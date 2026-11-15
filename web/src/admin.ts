// Yönetim paneli için sunucu çağrıları. Her uç ayrıca sunucuda yönetici yetkisi ister;
// yönetici olmayan bir hesap paneli göremez ve işlem yapamaz.

import { client, getHomeserver } from "./session";

export interface Stats {
  members: number;
  pending: number;
  admins: number;
  online: number;
  with_phone: number;
  new_24h: number;
  new_7d: number;
  rooms: number;
  max_members: number;
  disk_used: number;
  disk_total: number;
  sms_ready: boolean;
  days: { day: string; active?: number; signups?: number; logins?: number; sms?: number; calls?: number }[];
}

export interface AdminMember {
  user_id: string;
  display_name: string;
  admin: boolean;
  pending: boolean;
  created: number;
  mode: string;
  last_seen: number;
  phone: string;
}

export interface AuditEntry {
  ts: number;
  actor: string;
  action: string;
  target: string;
  detail: string;
}

export interface AppConfig {
  announcement: string;
  latest_version: number;
  apk_url: string;
}

export class AdminError extends Error {}

async function call<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
  const r = await fetch(getHomeserver() + path, {
    method,
    headers: { Authorization: "Bearer " + (client?.getAccessToken() ?? ""), "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new AdminError(data.message || "İşlem tamamlanamadı.");
  return data as T;
}

/** Bu hesap yönetici mi? (Sunucu yönetici olmayanlara 403 döndürür.) */
export async function isAdmin(): Promise<boolean> {
  try {
    await call("GET", "/bayx/admin/stats");
    return true;
  } catch {
    return false;
  }
}

export const getStats = () => call<Stats>("GET", "/bayx/admin/stats");
export const getMembers = () => call<{ members: AdminMember[]; count: number; max: number }>("GET", "/bayx/admin/members");
export const getAudit = () => call<{ entries: AuditEntry[] }>("GET", "/bayx/admin/audit");
export const memberAction = (action: "lock" | "unlock" | "approve" | "remove", userId: string) =>
  call<{ ok: boolean }>("POST", "/bayx/admin/" + action, { user_id: userId });
export const getConfig = () => call<AppConfig>("POST", "/bayx/config");
export const setConfig = (cfg: AppConfig) => call<AppConfig>("POST", "/bayx/admin/config", cfg);
export const createInvite = (uses: number, days: number) => call<{ code: string; url: string }>("POST", "/bayx/admin/invite", { uses, days });
