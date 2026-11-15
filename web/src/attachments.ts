// Şifreli sohbetlerde dosyalar sunucuya yüklenmeden önce tarayıcıda şifrelenir
// (Matrix şifreli ek biçimi: AES-256-CTR + SHA-256). Sunucu yalnızca şifreli baytları görür.

export interface EncryptedFile {
  url: string;
  key: JsonWebKey & { alg: string; ext: boolean; key_ops: string[]; kty: string; k: string };
  iv: string;
  hashes: { sha256: string };
  v: string;
}

function toB64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/=+$/, "");
}

function fromB64(text: string): Uint8Array<ArrayBuffer> {
  let s = text.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function encryptAttachment(plain: ArrayBuffer): Promise<{ data: ArrayBuffer; info: Omit<EncryptedFile, "url"> }> {
  const iv = new Uint8Array(16);
  crypto.getRandomValues(iv.subarray(0, 8)); // son 8 bayt sayaç: sıfırdan başlar
  const key = await crypto.subtle.generateKey({ name: "AES-CTR", length: 256 }, true, ["encrypt", "decrypt"]);
  const jwk = (await crypto.subtle.exportKey("jwk", key)) as EncryptedFile["key"];
  const data = await crypto.subtle.encrypt({ name: "AES-CTR", counter: iv, length: 64 }, key, plain);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return {
    data,
    info: {
      key: { alg: "A256CTR", ext: true, key_ops: ["encrypt", "decrypt"], kty: "oct", k: jwk.k! },
      iv: toB64(iv),
      hashes: { sha256: toB64(hash) },
      v: "v2",
    },
  };
}

export async function decryptAttachment(cipher: ArrayBuffer, info: EncryptedFile): Promise<ArrayBuffer> {
  const expected = info.hashes?.sha256;
  if (!expected) throw new Error("ek özeti yok");
  const actual = toB64(new Uint8Array(await crypto.subtle.digest("SHA-256", cipher)));
  if (actual !== expected.replace(/=+$/, "")) throw new Error("ek bozuk");
  const key = await crypto.subtle.importKey("jwk", info.key, { name: "AES-CTR" }, false, ["decrypt"]);
  return crypto.subtle.decrypt({ name: "AES-CTR", counter: fromB64(info.iv), length: 64 }, key, cipher);
}
