// Servidor do Filtro Inteligente (AWS Lambda, Node.js 22, sa-east-1).
// Login Google, teste de 7 dias (conta E celular novos), licenca assinada, cupons, bloqueio,
// atualizacao obrigatoria, transito (Amazon Location) e painel de administracao.
// Sem dependencias externas: crypto/fetch do Node + AWS SDK v3 que ja vem no runtime da Lambda.
import crypto from "node:crypto";
import { readFileSync } from "node:fs";
import { store } from "./store.mjs";

const DAY = 86_400_000;
const TRIAL_DAYS = 7;
const SESSION_TTL = 30 * DAY;
const LICENSE_TTL = 6 * 3_600_000;
const env = process.env;

// ---------------------------------------------------------------- tokens (ES256, assinatura DER)
const b64u = (buf) => Buffer.from(buf).toString("base64url");
const fromB64u = (s) => Buffer.from(s, "base64url");

export function signToken(payload, privateKeyPem) {
  const body = b64u(JSON.stringify(payload));
  const sig = crypto.sign("sha256", Buffer.from(body), { key: privateKeyPem, dsaEncoding: "der" });
  return `${body}.${b64u(sig)}`;
}

export function verifyToken(token, publicKeyPem, typ, now = Date.now()) {
  if (typeof token !== "string" || !token.includes(".")) return null;
  const [body, sig] = token.split(".");
  let ok = false;
  try {
    ok = crypto.verify("sha256", Buffer.from(body), { key: publicKeyPem, dsaEncoding: "der" }, fromB64u(sig));
  } catch { ok = false; }
  if (!ok) return null;
  let p;
  try { p = JSON.parse(fromB64u(body).toString("utf8")); } catch { return null; }
  if (p.typ !== typ || typeof p.exp !== "number" || p.exp < now) return null;
  return p;
}

// ---------------------------------------------------------------- regras de plano
export function computeStatus(user, now = Date.now()) {
  if (!user) return { status: "none" };
  if (user.blocked) return { status: "blocked", reason: user.blockReason || "Conta bloqueada" };
  if (user.lifetime) return { status: "active", plan: "vitalicio", until: null };
  if ((user.paidUntil || 0) > now) return { status: "active", plan: user.plan || "assinatura", until: user.paidUntil };
  if ((user.trialEndsAt || 0) > now) return { status: "trial", plan: "teste", until: user.trialEndsAt };
  return { status: "expired", plan: user.plan || null, until: Math.max(user.paidUntil || 0, user.trialEndsAt || 0) || null };
}

/** Teste de 7 dias: so para conta que nunca testou E celular que nunca testou. */
export function trialDecision(user, device) {
  if (user?.trialUsed) return { grant: false, why: "conta já usou o teste" };
  if (device?.trialUsed) return { grant: false, why: "celular já usou o teste" };
  return { grant: true };
}

export function versionGate(appVersion, cfg) {
  const v = Number(appVersion) || 0;
  const min = Number(cfg?.minVersionCode) || 0;
  const latest = Number(cfg?.latestVersionCode) || 0;
  return {
    mustUpdate: min > 0 && v < min,
    hasUpdate: latest > 0 && v < latest,
    minVersionCode: min,
    latestVersionCode: latest,
    updateUrl: cfg?.updateUrl || "",
    message: cfg?.message || ""
  };
}

/** Aplica um cupom ao usuario (dias somam a partir do maior entre hoje e o fim atual). */
export function applyCoupon(user, coupon, now = Date.now()) {
  const u = { ...user };
  if (coupon.type === "lifetime") {
    u.lifetime = true;
    u.plan = "vitalicio";
  } else {
    const days = Number(coupon.days) || 0;
    const base = Math.max(now, u.paidUntil || 0, u.trialEndsAt || 0);
    u.paidUntil = base + days * DAY;
    u.plan = u.plan === "vitalicio" ? u.plan : "cupom";
  }
  return u;
}

// ---------------------------------------------------------------- Google
async function verifyGoogleIdToken(idToken) {
  const r = await fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken));
  if (!r.ok) return null;
  const t = await r.json();
  const allowed = (env.GOOGLE_CLIENT_IDS || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!allowed.includes(t.aud)) return null;
  if (t.iss !== "https://accounts.google.com" && t.iss !== "accounts.google.com") return null;
  if (String(t.email_verified) !== "true") return null;
  return { sub: t.sub, email: (t.email || "").toLowerCase(), name: t.name || "" };
}

// Play Billing: conta de servico (JSON em base64 na variavel GOOGLE_SA_JSON_B64)
async function googleAccessToken() {
  if (!env.GOOGLE_SA_JSON_B64) return null;
  const sa = JSON.parse(Buffer.from(env.GOOGLE_SA_JSON_B64, "base64").toString("utf8"));
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64u(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600
  }));
  const sig = b64u(crypto.sign("sha256", Buffer.from(`${head}.${claim}`), sa.private_key));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${head}.${claim}.${sig}`
  });
  if (!r.ok) return null;
  return (await r.json()).access_token;
}

// ---------------------------------------------------------------- respostas
/** Ids que aparecem no painel: so letras, numeros, _ e - (evita injecao no site de administracao). */
export const cleanId = (v) => String(v || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 80);
const cleanCode = (v) => String(v || "").toUpperCase().replace(/[^A-Z0-9_-]/g, "").slice(0, 40);

const json = (status, body) => ({
  statusCode: status,
  headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  body: JSON.stringify(body)
});
const fail = (status, code, message) => json(status, { ok: false, code, message });

function keys() {
  const priv = Buffer.from(env.SIGNING_PRIVATE_KEY_B64 || "", "base64").toString("utf8");
  const pub = Buffer.from(env.SIGNING_PUBLIC_KEY_B64 || "", "base64").toString("utf8");
  return { priv, pub };
}

function issueLicense(user, did, priv, now = Date.now()) {
  const st = computeStatus(user, now);
  if (st.status !== "trial" && st.status !== "active") return null;
  const exp = Math.min(now + LICENSE_TTL, st.until || Number.MAX_SAFE_INTEGER);
  return signToken({ typ: "lic", sub: user.sub, did, st: st.status, plan: st.plan, until: st.until, iat: now, exp }, priv);
}

async function statusPayload(user, did, appVersion, priv) {
  const cfg = await store.getAppConfig();
  const gate = versionGate(appVersion, cfg);
  const st = computeStatus(user);
  return {
    ok: true,
    account: { email: user.email, name: user.name || "" },
    status: st.status,
    plan: st.plan || null,
    until: st.until || null,
    trialUsed: !!user.trialUsed,
    update: gate,
    license: gate.mustUpdate ? null : issueLicense(user, did, priv)
  };
}

async function sessionFrom(body, pub) {
  const s = verifyToken(body?.session, pub, "ses");
  if (!s) return { err: fail(401, "SESSION_INVALID", "Faça login de novo") };
  const user = await store.getUser(s.sub);
  if (!user) return { err: fail(401, "SESSION_INVALID", "Faça login de novo") };
  if (user.blocked) return { err: fail(403, "ACCOUNT_BLOCKED", user.blockReason || "Conta bloqueada") };
  const device = await store.getDevice(s.did);
  if (device?.blocked) return { err: fail(403, "DEVICE_BLOCKED", device.blockReason || "Este celular foi bloqueado") };
  if (user.activeDevice && user.activeDevice !== s.did) {
    return { err: fail(409, "OTHER_DEVICE", "Sua conta entrou em outro celular. Faça login de novo para usar neste.") };
  }
  return { s, user, device };
}

// ---------------------------------------------------------------- rotas do app
async function routeLogin(body, now = Date.now()) {
  const { priv } = keys();
  const did = cleanId(body?.deviceId);
  if (!did || !body?.idToken) return fail(400, "BAD_REQUEST", "Dados de login incompletos");
  const g = await verifyGoogleIdToken(body.idToken);
  if (!g) return fail(401, "GOOGLE_INVALID", "Não foi possível confirmar sua conta Google");
  return finishLogin(g, did, body, now);
}

export async function finishLogin(g, did, body, now = Date.now()) {
  const { priv } = keys();
  let device = await store.getDevice(did);
  if (device?.blocked) return fail(403, "DEVICE_BLOCKED", device.blockReason || "Este celular foi bloqueado");
  let user = await store.getUser(g.sub);
  const isNew = !user;
  user = user || { sub: g.sub, createdAt: now, trialUsed: false };
  user.email = g.email;
  user.name = g.name || user.name || "";
  if (user.blocked) return fail(403, "ACCOUNT_BLOCKED", user.blockReason || "Conta bloqueada");

  const trial = trialDecision(user, device);
  if (trial.grant) {
    user.trialUsed = true;
    user.trialEndsAt = now + TRIAL_DAYS * DAY;
    device = { ...(device || {}), trialUsed: true, trialBy: g.sub, trialAt: now };
  }
  user.activeDevice = did;
  user.lastSeenAt = now;
  user.lastAppVersion = Number(body?.appVersion) || 0;
  device = {
    ...(device || {}), did, firstSeen: device?.firstSeen || now, lastSeen: now, lastUser: g.sub,
    model: String(body?.deviceModel || "").slice(0, 60)
  };
  await store.putUser(user);
  await store.putDevice(device);
  await store.log(g.sub, isNew ? "conta criada" : "login", { did, trial: trial.grant ? "7 dias" : trial.why });
  const session = signToken({ typ: "ses", sub: g.sub, did, iat: now, exp: now + SESSION_TTL }, priv);
  return json(200, { ...(await statusPayload(user, did, body?.appVersion, priv)), session, trialGranted: trial.grant, trialNote: trial.grant ? "" : trial.why });
}

async function routeLicense(body) {
  const { priv, pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const user = { ...r.user, lastSeenAt: Date.now(), lastAppVersion: Number(body?.appVersion) || r.user.lastAppVersion || 0 };
  await store.putUser(user);
  return json(200, await statusPayload(user, r.s.did, body?.appVersion, priv));
}

async function routeRedeem(body, now = Date.now()) {
  const { priv, pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const code = cleanCode(body?.code);
  if (!code) return fail(400, "COUPON_INVALID", "Digite o cupom");
  const c = await store.getCoupon(code);
  if (!c || c.active === false) return fail(404, "COUPON_INVALID", "Cupom inválido");
  if (c.expiresAt && c.expiresAt < now) return fail(410, "COUPON_EXPIRED", "Cupom vencido");
  if (await store.couponUsedBy(code, r.user.sub)) return fail(409, "COUPON_ALREADY", "Você já usou este cupom");
  if (c.maxUses > 0 && (c.uses || 0) >= c.maxUses) return fail(410, "COUPON_USED_UP", "Cupom esgotado");
  const user = applyCoupon(r.user, c, now);
  await store.putUser(user);
  await store.markCouponUsed(code, r.user.sub, now);
  await store.log(r.user.sub, "cupom", { code, type: c.type, days: c.days || 0 });
  return json(200, { ...(await statusPayload(user, r.s.did, body?.appVersion, priv)), couponMessage: c.type === "lifetime" ? "Acesso vitalício liberado" : `+${c.days} dias liberados` });
}

async function routeBilling(body) {
  const { priv, pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const token = String(body?.purchaseToken || "");
  if (!token) return fail(400, "BAD_REQUEST", "Compra sem comprovante");
  const access = await googleAccessToken();
  if (!access || !env.PLAY_PACKAGE_NAME) return fail(503, "BILLING_NOT_READY", "Pagamento ainda não configurado");
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${env.PLAY_PACKAGE_NAME}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`;
  const g = await fetch(url, { headers: { Authorization: `Bearer ${access}` } });
  if (!g.ok) return fail(402, "PURCHASE_INVALID", "O Google não confirmou a compra");
  const sub = await g.json();
  const owner = await store.getPurchaseOwner(token);
  if (owner && owner !== r.user.sub) return fail(409, "PURCHASE_OTHER_ACCOUNT", "Esta compra pertence a outra conta");
  const active = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(sub.subscriptionState);
  const expiry = Math.max(...(sub.lineItems || []).map((li) => Date.parse(li.expiryTime) || 0), 0);
  const user = { ...r.user };
  if (active && expiry > Date.now()) {
    user.paidUntil = Math.max(user.paidUntil || 0, expiry);
    user.plan = (sub.lineItems?.[0]?.productId || "assinatura");
  }
  await store.putUser(user);
  await store.setPurchaseOwner(token, r.user.sub);
  await store.log(r.user.sub, "assinatura", { state: sub.subscriptionState, expiry });
  return json(200, await statusPayload(user, r.s.did, body?.appVersion, priv));
}

async function routeTraffic(body, now = Date.now()) {
  const { pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const st = computeStatus(r.user, now);
  if (st.status !== "trial" && st.status !== "active") return fail(402, "NO_PLAN", "Plano vencido");
  const day = new Date(now - 3 * 3_600_000).toISOString().slice(0, 10);
  const used = await store.incrementQuota(r.user.sub, day);
  const limit = Number(env.TRAFFIC_DAILY_LIMIT) || 300;
  if (used > limit) return fail(429, "QUOTA", "Limite diário de consultas de trânsito atingido");
  const p = (v) => (Array.isArray(v) && v.length === 2 && v.every(Number.isFinite) ? v : null);
  const driver = p(body?.driver), pickup = p(body?.pickup), dropoff = p(body?.dropoff); // [lat, lng]
  if (!driver || !pickup || !dropoff) return fail(400, "BAD_REQUEST", "Coordenadas incompletas");
  const ll = (x) => [x[1], x[0]];
  const ctrl = AbortSignal.timeout(1400);
  const resp = await fetch(`https://routes.geo.sa-east-1.amazonaws.com/v2/routes?key=${encodeURIComponent(env.AWS_ROUTES_KEY || "")}`, {
    method: "POST", signal: ctrl, headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ Origin: ll(driver), Destination: ll(dropoff), Waypoints: [{ Position: ll(pickup) }], TravelMode: "Scooter", DepartNow: true })
  }).catch(() => null);
  if (!resp || !resp.ok) return fail(502, "TRAFFIC_UNAVAILABLE", "Trânsito indisponível");
  const data = await resp.json();
  const summary = data?.Routes?.[0]?.Summary;
  if (!summary) return fail(502, "TRAFFIC_UNAVAILABLE", "Trânsito indisponível");
  return json(200, { ok: true, minutes: summary.Duration / 60, meters: summary.Distance, used, limit });
}

// ---------------------------------------------------------------- administracao
function isAdmin(event) {
  const got = Buffer.from(String(event.headers?.["x-admin-token"] || ""));
  const want = Buffer.from(String(env.ADMIN_TOKEN || ""));
  return want.length >= 24 && got.length === want.length && crypto.timingSafeEqual(got, want);
}

async function routeAdmin(path, body) {
  const now = Date.now();
  switch (path) {
    case "/admin/api/overview": {
      const users = await store.listUsers();
      const rows = users.map((u) => ({ ...u, ...computeStatus(u, now) }));
      const count = (s) => rows.filter((x) => x.status === s).length;
      return json(200, {
        ok: true, totals: { users: rows.length, active: count("active"), trial: count("trial"), expired: count("expired"), blocked: count("blocked") },
        users: rows.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0)),
        devices: await store.listDevices(), coupons: await store.listCoupons(), appConfig: await store.getAppConfig()
      });
    }
    case "/admin/api/user": {
      const u = await store.getUser(body.sub);
      if (!u) return fail(404, "NOT_FOUND", "Usuário não encontrado");
      if (typeof body.blocked === "boolean") { u.blocked = body.blocked; u.blockReason = body.reason || ""; }
      if (Number(body.addDays)) u.paidUntil = Math.max(now, u.paidUntil || 0) + Number(body.addDays) * DAY;
      if (typeof body.lifetime === "boolean") { u.lifetime = body.lifetime; if (body.lifetime) u.plan = "vitalicio"; }
      if (body.resetDevice) u.activeDevice = "";
      await store.putUser(u);
      await store.log(u.sub, "admin", body);
      return json(200, { ok: true, user: { ...u, ...computeStatus(u, now) } });
    }
    case "/admin/api/device": {
      const did = cleanId(body.did);
      const d = (await store.getDevice(did)) || { did, firstSeen: now };
      d.blocked = !!body.blocked; d.blockReason = body.reason || "";
      if (body.resetTrial) { d.trialUsed = false; }
      await store.putDevice(d);
      return json(200, { ok: true, device: d });
    }
    case "/admin/api/coupon": {
      const code = cleanCode(body.code) || crypto.randomBytes(4).toString("hex").toUpperCase();
      const c = {
        code, type: body.type === "lifetime" ? "lifetime" : "days", days: Number(body.days) || 0,
        maxUses: Number(body.maxUses) || 0, uses: 0, active: body.active !== false,
        expiresAt: body.expiresAt ? Date.parse(body.expiresAt) : null, note: String(body.note || ""), createdAt: now
      };
      const existing = await store.getCoupon(code);
      if (body.update) {
        if (!existing) return fail(404, "NOT_FOUND", "Cupom não encontrado");
        existing.active = body.active !== false;
        await store.putCoupon(existing);
        return json(200, { ok: true, coupon: existing });
      }
      if (c.type === "days" && c.days <= 0) return fail(400, "BAD_REQUEST", "Informe os dias do cupom");
      if (existing) return fail(409, "EXISTS", "Já existe um cupom com esse código");
      await store.putCoupon(c);
      return json(200, { ok: true, coupon: c });
    }
    case "/admin/api/app-config": {
      const cfg = {
        minVersionCode: Number(body.minVersionCode) || 0, latestVersionCode: Number(body.latestVersionCode) || 0,
        updateUrl: String(body.updateUrl || ""), message: String(body.message || ""), updatedAt: now
      };
      await store.putAppConfig(cfg);
      return json(200, { ok: true, appConfig: cfg });
    }
    case "/admin/api/logs":
      return json(200, { ok: true, logs: await store.listLogs(body.sub) });
    case "/admin/api/test-login": {
      // So administrador: entra como uma conta de teste (suporte e testes antes do login Google ficar pronto)
      const email = String(body.email || "teste@filtro.local").toLowerCase();
      const sub = "teste-" + crypto.createHash("sha256").update(email).digest("hex").slice(0, 16);
      return finishLogin({ sub, email, name: "Conta de teste" }, cleanId(body.deviceId) || "admin-teste", body, now);
    }
    default:
      return fail(404, "NOT_FOUND", "Rota não encontrada");
  }
}

let adminHtml = null;
function adminPage() {
  if (!adminHtml) adminHtml = readFileSync(new URL("./admin.html", import.meta.url), "utf8");
  return { statusCode: 200, headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }, body: adminHtml };
}

// ---------------------------------------------------------------- entrada
export async function handler(event) {
  const method = event.requestContext?.http?.method || "GET";
  const path = event.rawPath || "/";
  let body = {};
  if (event.body) {
    try { body = JSON.parse(event.isBase64Encoded ? Buffer.from(event.body, "base64").toString("utf8") : event.body); }
    catch { return fail(400, "BAD_JSON", "Corpo inválido"); }
  }
  try {
    if (method === "GET" && (path === "/admin" || path === "/admin/")) return adminPage();
    if (path.startsWith("/admin/api/")) {
      if (!isAdmin(event)) return fail(401, "ADMIN_ONLY", "Senha de administrador inválida");
      return await routeAdmin(path, body);
    }
    if (method === "GET" && path === "/v1/app-config") {
      return json(200, { ok: true, update: versionGate(Number(event.queryStringParameters?.v) || 0, await store.getAppConfig()) });
    }
    if (method !== "POST") return fail(405, "METHOD", "Use POST");
    switch (path) {
      case "/v1/auth/google": return await routeLogin(body);
      case "/v1/license": return await routeLicense(body);
      case "/v1/coupon/redeem": return await routeRedeem(body);
      case "/v1/billing/verify": return await routeBilling(body);
      case "/v1/traffic": return await routeTraffic(body);
      default: return fail(404, "NOT_FOUND", "Rota não encontrada");
    }
  } catch (e) {
    console.error("erro", path, e);
    return fail(500, "SERVER_ERROR", "Erro no servidor, tente de novo");
  }
}
