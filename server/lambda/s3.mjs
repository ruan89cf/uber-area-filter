// Links temporarios do S3 (assinatura SigV4 feita aqui, sem biblioteca extra).
// O app e o painel enviam/baixam arquivos direto no S3 com esses links (APK, backups, registros).
import crypto from "node:crypto";

const REGION = process.env.FILES_REGION || process.env.AWS_REGION || "sa-east-1";
export const BUCKET = () => process.env.FILES_BUCKET || "";

const hmac = (key, s) => crypto.createHmac("sha256", key).update(s).digest();
const sha = (s) => crypto.createHash("sha256").update(s).digest("hex");
const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());

/** Link assinado para GET/PUT/DELETE de um objeto. `expires` em segundos (max 7 dias). */
export function presign(method, key, expires = 3600, now = new Date(), creds = process.env) {
  const bucket = BUCKET();
  if (!bucket || !creds.AWS_ACCESS_KEY_ID) return "";
  const host = `${bucket}.s3.${REGION}.amazonaws.com`;
  const amzDate = now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const day = amzDate.slice(0, 8);
  const scope = `${day}/${REGION}/s3/aws4_request`;
  const path = "/" + key.split("/").map(enc).join("/");
  const q = {
    "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
    "X-Amz-Credential": `${creds.AWS_ACCESS_KEY_ID}/${scope}`,
    "X-Amz-Date": amzDate,
    "X-Amz-Expires": String(Math.min(Math.max(1, expires), 604800)),
    "X-Amz-SignedHeaders": "host"
  };
  if (creds.AWS_SESSION_TOKEN) q["X-Amz-Security-Token"] = creds.AWS_SESSION_TOKEN;
  const query = Object.keys(q).sort().map((k) => `${enc(k)}=${enc(q[k])}`).join("&");
  const canonical = [method, path, query, `host:${host}\n`, "host", "UNSIGNED-PAYLOAD"].join("\n");
  const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha(canonical)].join("\n");
  let k = hmac("AWS4" + creds.AWS_SECRET_ACCESS_KEY, day);
  k = hmac(k, REGION); k = hmac(k, "s3"); k = hmac(k, "aws4_request");
  const sig = crypto.createHmac("sha256", k).update(toSign).digest("hex");
  return `https://${host}${path}?${query}&X-Amz-Signature=${sig}`;
}

/** Apaga um objeto (usado ao remover versao). */
export async function deleteObject(key) {
  const url = presign("DELETE", key, 60);
  if (!url) return false;
  const r = await fetch(url, { method: "DELETE" }).catch(() => null);
  return !!r && (r.ok || r.status === 204);
}

/** Confere se o objeto existe e devolve o tamanho. */
export async function headObject(key) {
  const url = presign("HEAD", key, 60);
  if (!url) return null;
  const r = await fetch(url, { method: "HEAD" }).catch(() => null);
  if (!r || !r.ok) return null;
  return { size: Number(r.headers.get("content-length")) || 0 };
}
