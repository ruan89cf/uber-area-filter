// Servidor do Filtro Inteligente (AWS Lambda, Node.js 22, sa-east-1).
// Conta (e-mail e senha, Google ou codigo), teste gratis (conta E celular novos), licenca assinada, cupons, bloqueio,
// atualizacao obrigatoria, transito (Amazon Location) e painel de administracao.
// Sem dependencias externas: crypto/fetch do Node + AWS SDK v3 que ja vem no runtime da Lambda.
import crypto from "node:crypto";
import { readFileSync } from "node:fs";
import { store } from "./store.mjs";
import { presign, headObject, deleteObject } from "./s3.mjs";
import { sendCode } from "./mail.mjs";

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
  if (user.pending) return { status: "none", plan: "aguardando confirmação" };
  if (user.lifetime) return { status: "active", plan: "vitalicio", until: null };
  if ((user.paidUntil || 0) > now) return { status: "active", plan: user.plan || "assinatura", until: user.paidUntil };
  if ((user.trialEndsAt || 0) > now) return { status: "trial", plan: "teste", until: user.trialEndsAt };
  return { status: "expired", plan: user.plan || null, until: Math.max(user.paidUntil || 0, user.trialEndsAt || 0) || null };
}

/** Teste gratis: so para conta que nunca testou E celular que nunca testou. */
export function trialDecision(user, device) {
  if (user?.trialUsed) return { grant: false, why: "conta já usou o teste" };
  if (device?.trialUsed) return { grant: false, why: "celular já usou o teste" };
  return { grant: true };
}

/**
 * Atualizacao: versoes enviadas pelo painel (lista) + valores antigos (minima/mais nova).
 * Obrigatoria = existe versao ativa marcada "obrigatoria" acima da instalada.
 */
export function versionGate(appVersion, cfg, versions = [], downloadUrl = (v) => "") {
  const v = Number(appVersion) || 0;
  const live = versions.filter((x) => x.active && x.uploaded).sort((a, b) => b.versionCode - a.versionCode);
  const newest = live[0];
  const min = Math.max(Number(cfg?.minVersionCode) || 0, ...live.filter((x) => x.mandatory).map((x) => x.versionCode));
  const latest = Math.max(Number(cfg?.latestVersionCode) || 0, newest?.versionCode || 0);
  const useApk = newest && newest.versionCode === latest && v < latest;
  return {
    mustUpdate: min > 0 && v < min,
    hasUpdate: latest > 0 && v < latest,
    minVersionCode: min,
    latestVersionCode: latest,
    latestVersionName: newest?.versionName || "",
    updateUrl: useApk ? downloadUrl(newest) : (cfg?.updateUrl || ""),
    apk: !!useApk,
    sha256: useApk ? newest.sha256 || "" : "",
    size: useApk ? newest.size || 0 : 0,
    message: (useApk ? newest.notes : "") || cfg?.message || ""
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

/** Assinou durante o teste: os dias de teste que sobraram continuam valendo (somados ao fim do plano). */
export function applyPurchase(user, expiry, productId, now = Date.now()) {
  const u = { ...user };
  if (!u.trialBonusMs && !u.trialBonusUsed && (u.trialEndsAt || 0) > now) {
    u.trialBonusMs = u.trialEndsAt - now;
    u.trialBonusUsed = true;
  }
  u.paidUntil = Math.max(u.paidUntil || 0, expiry + (u.trialBonusMs || 0));
  u.plan = productId || "assinatura";
  return u;
}

// ---------------------------------------------------------------- senha e perfil
export function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const h = crypto.scryptSync(String(pw), salt, 32, { N: 16384, r: 8, p: 1 });
  return `s1$${salt.toString("base64")}$${h.toString("base64")}`;
}

export function checkPassword(pw, stored) {
  if (typeof stored !== "string" || !stored.startsWith("s1$")) return false;
  const [, salt, hash] = stored.split("$");
  const h = crypto.scryptSync(String(pw), Buffer.from(salt, "base64"), 32, { N: 16384, r: 8, p: 1 });
  const want = Buffer.from(hash, "base64");
  return want.length === h.length && crypto.timingSafeEqual(want, h);
}

const GENDERS = ["masculino", "feminino", "outro", "nao_informar"];
const cleanEmail = (v) => String(v || "").trim().toLowerCase().slice(0, 120);
const emailOk = (e) => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e);
const cleanName = (v) => String(v || "").replace(/[<>"'`\\]/g, "").replace(/\s+/g, " ").trim().slice(0, 40);

/** Confere nome, sobrenome, nascimento e genero. Devolve { profile } ou { error }. */
export function validateProfile(b, now = Date.now()) {
  const firstName = cleanName(b?.firstName);
  const lastName = cleanName(b?.lastName);
  const birthDate = String(b?.birthDate || "").trim();
  const gender = String(b?.gender || "").trim();
  if (!firstName) return { error: "Informe seu nome" };
  if (!lastName) return { error: "Informe seu sobrenome" };
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(birthDate);
  const t = m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN;
  if (!m || Number.isNaN(t) || new Date(t).getUTCDate() !== +m[3]) return { error: "Data de nascimento inválida" };
  const age = (now - t) / (365.25 * DAY);
  if (age < 18) return { error: "É preciso ter 18 anos ou mais" };
  if (age > 100) return { error: "Data de nascimento inválida" };
  if (!GENDERS.includes(gender)) return { error: "Escolha o gênero" };
  return { profile: { firstName, lastName, birthDate, gender } };
}

const profileComplete = (u) => !!(u?.firstName && u?.lastName && u?.birthDate && u?.gender);

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

/** Valores do plano e dias de teste (definidos no painel, aba Configuracoes). */
export function pricing(cfg) {
  return {
    trialDays: Number(cfg?.trialDays) > 0 ? Number(cfg.trialDays) : TRIAL_DAYS,
    monthly: Number(cfg?.priceMonthly) > 0 ? Number(cfg.priceMonthly) : 29.9,
    yearly: Number(cfg?.priceYearly) > 0 ? Number(cfg.priceYearly) : 299.0
  };
}

const apkUrl = (v) => (v?.s3Key ? presign("GET", v.s3Key, 6 * 3600) : "");

async function updateInfo(appVersion) {
  const [cfg, versions] = await Promise.all([store.getAppConfig(), store.listVersions()]);
  return { cfg, gate: versionGate(appVersion, cfg, versions, apkUrl) };
}

async function statusPayload(user, did, appVersion, priv) {
  const { cfg, gate } = await updateInfo(appVersion);
  const st = computeStatus(user);
  const admin = adminEmails().includes(String(user.email || "").toLowerCase());
  // Modo manutencao: o filtro para para todo mundo (menos o dono) ate desligar no painel
  const maintenance = cfg.maintenance?.on && !admin ? (cfg.maintenance.message || "App em manutenção. Volta em instantes.") : "";
  // Aviso: o da pessoa (mandado pelo painel) tem prioridade sobre o aviso para todos
  const notice = user.notice?.text ? user.notice : (cfg.notice?.text ? cfg.notice : null);
  return {
    ok: true,
    account: {
      email: user.email, name: [user.firstName, user.lastName].filter(Boolean).join(" ") || user.name || "",
      firstName: user.firstName || "", lastName: user.lastName || "", birthDate: user.birthDate || "", gender: user.gender || "",
      hasPassword: !!user.passwordHash, profileComplete: profileComplete(user), isAdmin: admin
    },
    notice: notice ? { id: String(notice.id || notice.at || ""), text: String(notice.text).slice(0, 500) } : null,
    commands: Array.isArray(user.commands) ? user.commands.slice(0, 10) : [],
    maintenance,
    status: st.status,
    plan: st.plan || null,
    until: st.until || null,
    trialUsed: !!user.trialUsed,
    pricing: pricing(cfg),
    update: gate,
    license: gate.mustUpdate || maintenance ? null : issueLicense(user, did, priv),
    message: maintenance || undefined
  };
}

/** APK modificado (assinado por outra chave) nao recebe licenca. So vale com APK_CERT_SHA256 definido. */
function certOk(body) {
  const want = (env.APK_CERT_SHA256 || "").toLowerCase().replace(/[^0-9a-f]/g, "");
  if (!want) return true;
  return String(body?.cert || "").toLowerCase() === want;
}

async function sessionFrom(body, pub) {
  if (!certOk(body)) return { err: fail(403, "APP_MODIFIED", "Este app foi modificado. Baixe a versão oficial.") };
  const s = verifyToken(body?.session, pub, "ses");
  if (!s) return { err: fail(401, "SESSION_INVALID", "Faça login de novo") };
  const user = await store.getUser(s.sub);
  if (!user || user.deleted) return { err: fail(401, "SESSION_INVALID", "Faça login de novo") };
  if ((s.sv || 0) !== (user.sessionVersion || 0)) return { err: fail(401, "SESSION_INVALID", "Sua sessão foi encerrada. Entre de novo.") };
  if (user.blocked) return { err: fail(403, "ACCOUNT_BLOCKED", user.blockReason || "Conta bloqueada") };
  const device = await store.getDevice(s.did);
  if (device?.blocked) return { err: fail(403, "DEVICE_BLOCKED", device.blockReason || "Este celular foi bloqueado") };
  if (user.activeDevice && user.activeDevice !== s.did) {
    return { err: fail(409, "OTHER_DEVICE", "Sua conta entrou em outro celular. Faça login de novo para usar neste.") };
  }
  return { s, user, device };
}

/** Conta ligada ao e-mail (Google, senha e codigo de acesso caem na mesma conta). */
async function subForEmail(email) {
  const sub = await store.subForEmail(email);
  if (sub) return sub;
  // contas antigas (antes do indice de e-mail)
  for (const [prefix, n] of [["code-", 20], ["teste-", 16]]) {
    const legacy = prefix + crypto.createHash("sha256").update(email).digest("hex").slice(0, n);
    if (await store.getUser(legacy)) { await store.linkEmail(email, legacy); return legacy; }
  }
  return null;
}

// ---------------------------------------------------------------- login e cadastro
export async function finishLogin(g, did, body, now = Date.now()) {
  const { priv } = keys();
  if (!certOk(body)) return fail(403, "APP_MODIFIED", "Este app foi modificado. Baixe a versão oficial.");
  let device = await store.getDevice(did);
  if (device?.blocked) return fail(403, "DEVICE_BLOCKED", device.blockReason || "Este celular foi bloqueado");
  const sub = (await subForEmail(g.email)) || g.sub;
  let user = await store.getUser(sub);
  // Conta apagada pelo painel: volta como conta nova, mas SEM novo teste gratis
  if (user?.deleted) user = { sub, createdAt: now, trialUsed: true };
  const isNew = !user;
  user = user || { sub, createdAt: now, trialUsed: false };
  user.email = g.email;
  if (g.name && !user.firstName) {
    const parts = cleanName(g.name).split(" ");
    user.firstName = parts.shift() || "";
    user.lastName = user.lastName || parts.join(" ");
  }
  if (g.google) user.google = true;
  if (user.blocked) return fail(403, "ACCOUNT_BLOCKED", user.blockReason || "Conta bloqueada");

  const cfg = await store.getAppConfig();
  const days = pricing(cfg).trialDays;
  const trial = trialDecision(user, device);
  if (trial.grant) {
    user.trialUsed = true;
    user.trialEndsAt = now + days * DAY;
    device = { ...(device || {}), trialUsed: true, trialBy: sub, trialAt: now };
  }
  user.activeDevice = did;
  user.lastSeenAt = now;
  user.lastAppVersion = Number(body?.appVersion) || 0;
  device = {
    ...(device || {}), did, firstSeen: device?.firstSeen || now, lastSeen: now, lastUser: sub,
    model: String(body?.deviceModel || "").slice(0, 60)
  };
  await store.putUser(user);
  await store.linkEmail(g.email, sub);
  await store.putDevice(device);
  await store.log(sub, isNew ? "conta criada" : "login", { did, via: g.via || "", trial: trial.grant ? `${days} dias` : trial.why });
  const session = signToken({ typ: "ses", sub, did, sv: user.sessionVersion || 0, iat: now, exp: now + SESSION_TTL }, priv);
  return json(200, {
    ...(await statusPayload(user, did, body?.appVersion, priv)), session,
    trialGranted: trial.grant, trialDays: trial.grant ? days : 0, trialNote: trial.grant ? "" : trial.why
  });
}

async function routeGoogle(body, now = Date.now()) {
  const did = cleanId(body?.deviceId);
  if (!did || !body?.idToken) return fail(400, "BAD_REQUEST", "Dados de login incompletos");
  const g = await verifyGoogleIdToken(body.idToken);
  if (!g) return fail(401, "GOOGLE_INVALID", "Não foi possível confirmar sua conta Google");
  return finishLogin({ ...g, google: true, via: "google" }, did, body, now);
}

/** Entrar com codigo de acesso (criado no painel, vale 1 vez, ligado a um e-mail). Testes e suporte. */
async function routeLoginCode(body, now = Date.now()) {
  const did = cleanId(body?.deviceId);
  const code = cleanCode(body?.code);
  if (!did || !code) return fail(400, "BAD_REQUEST", "Digite o código de acesso");
  const lc = await store.getLoginCode(code);
  if (!lc || lc.used || (lc.expiresAt && lc.expiresAt < now)) return fail(401, "CODE_INVALID", "Código inválido ou já usado");
  lc.used = true; lc.usedAt = now; lc.usedDevice = did;
  await store.putLoginCode(lc);
  const sub = "code-" + crypto.createHash("sha256").update(lc.email).digest("hex").slice(0, 20);
  const u = await store.getUser((await subForEmail(lc.email)) || sub);
  if (u?.pending) { u.pending = false; u.emailVerified = true; await store.putUser(u); }
  return finishLogin({ sub, email: lc.email, via: "codigo" }, did, body, now);
}

/** Codigo de 6 digitos por e-mail. Sem SES liberado, fica visivel no painel (aba Codigos). */
async function issueCode(email, purpose, now = Date.now()) {
  const old = await store.getCode(email, purpose);
  if (old && old.createdAt > now - 60_000 && old.exp > now) return { sent: !!old.sent, again: true };
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
  const sent = await sendCode(email, code, purpose);
  await store.putCode(email, purpose, {
    hash: crypto.createHash("sha256").update(`${email}:${code}`).digest("hex"),
    exp: now + 15 * 60_000, attempts: 0, createdAt: now, sent,
    plain: sent ? undefined : code, ttl: Math.floor(now / 1000) + 86400
  });
  return { sent, code: env.STORE === "memory" ? code : undefined };
}

async function checkCode(email, purpose, code, now = Date.now()) {
  const c = await store.getCode(email, purpose);
  if (!c || c.exp < now) return "Código vencido. Peça um novo.";
  if ((c.attempts || 0) >= 5) return "Muitas tentativas. Peça um novo código.";
  const got = crypto.createHash("sha256").update(`${email}:${String(code || "").replace(/\D/g, "")}`).digest("hex");
  if (got !== c.hash) {
    c.attempts = (c.attempts || 0) + 1;
    await store.putCode(email, purpose, c);
    return "Código errado";
  }
  await store.delCode(email, purpose);
  return null;
}

const failKey = (who, now = Date.now()) => `${who}#${new Date(now).toISOString().slice(0, 13)}`;
async function tooManyFailures(who, now = Date.now()) {
  const b = await store._backend();
  const it = await b.get(`FAIL#${failKey(who, now)}`, "N");
  return (it?.n || 0) >= 10;
}

async function routeRegister(body) {
  const email = cleanEmail(body?.email);
  if (!emailOk(email)) return fail(400, "BAD_EMAIL", "E-mail inválido");
  if (String(body?.password || "").length < 8) return fail(400, "WEAK_PASSWORD", "A senha precisa ter 8 caracteres ou mais");
  const v = validateProfile(body);
  if (v.error) return fail(400, "BAD_PROFILE", v.error);
  const existingSub = await subForEmail(email);
  const existing = existingSub ? await store.getUser(existingSub) : null;
  if (existing && !existing.pending) {
    return fail(409, "EMAIL_EXISTS", "Já existe uma conta com este e-mail. Entre com sua senha, com o Google ou use \"Esqueci a senha\".");
  }
  const now = Date.now();
  const user = existing || { sub: "u-" + crypto.randomBytes(8).toString("hex"), createdAt: now, trialUsed: false };
  Object.assign(user, v.profile, { email, pending: true, emailVerified: false, passwordHash: hashPassword(body.password) });
  await store.putUser(user);
  await store.linkEmail(email, user.sub);
  const r = await issueCode(email, "verify");
  await store.log(user.sub, "cadastro", { emailSent: r.sent });
  return json(200, { ok: true, needVerify: true, email, emailSent: r.sent, testCode: r.code });
}

async function routeVerify(body) {
  const email = cleanEmail(body?.email);
  const did = cleanId(body?.deviceId);
  if (!did) return fail(400, "BAD_REQUEST", "Dados incompletos");
  const sub = await subForEmail(email);
  const user = sub ? await store.getUser(sub) : null;
  if (!user) return fail(404, "NOT_FOUND", "Conta não encontrada");
  const err = await checkCode(email, "verify", body?.code);
  if (err) return fail(401, "CODE_INVALID", err);
  user.pending = false; user.emailVerified = true;
  await store.putUser(user);
  return finishLogin({ sub, email, via: "senha" }, did, body);
}

async function routePasswordLogin(body) {
  const email = cleanEmail(body?.email);
  const did = cleanId(body?.deviceId);
  if (!emailOk(email) || !did || !body?.password) return fail(400, "BAD_REQUEST", "Informe e-mail e senha");
  if (await tooManyFailures(email)) return fail(429, "TOO_MANY", "Muitas tentativas. Espere 1 hora ou use \"Esqueci a senha\".");
  const sub = await subForEmail(email);
  const user = sub ? await store.getUser(sub) : null;
  if (!user || !user.passwordHash || !checkPassword(body.password, user.passwordHash)) {
    await store.countFailure(failKey(email));
    const hint = user && !user.passwordHash ? " Sua conta foi criada sem senha: entre com Google ou crie uma senha em \"Esqueci a senha\"." : "";
    return fail(401, "BAD_LOGIN", "E-mail ou senha incorretos." + hint);
  }
  if (user.pending || user.emailVerified === false) {
    const r = await issueCode(email, "verify");
    return json(403, { ok: false, code: "EMAIL_NOT_VERIFIED", message: "Confirme seu e-mail com o código que enviamos.", email, emailSent: r.sent, testCode: r.code });
  }
  return finishLogin({ sub, email, via: "senha" }, did, body);
}

async function routeForgot(body) {
  const email = cleanEmail(body?.email);
  if (!emailOk(email)) return fail(400, "BAD_EMAIL", "E-mail inválido");
  const sub = await subForEmail(email);
  let r = { sent: false };
  if (sub) r = await issueCode(email, "reset");
  return json(200, { ok: true, email, emailSent: r.sent, testCode: r.code, message: "Se houver conta com este e-mail, enviamos um código." });
}

async function routeReset(body) {
  const email = cleanEmail(body?.email);
  const did = cleanId(body?.deviceId);
  if (!did) return fail(400, "BAD_REQUEST", "Dados incompletos");
  if (String(body?.password || "").length < 8) return fail(400, "WEAK_PASSWORD", "A senha precisa ter 8 caracteres ou mais");
  const sub = await subForEmail(email);
  const user = sub ? await store.getUser(sub) : null;
  if (!user) return fail(401, "CODE_INVALID", "Código errado");
  const err = await checkCode(email, "reset", body?.code);
  if (err) return fail(401, "CODE_INVALID", err);
  user.passwordHash = hashPassword(body.password);
  user.pending = false; user.emailVerified = true;
  await store.putUser(user);
  await store.log(sub, "senha trocada", {});
  return finishLogin({ sub, email, via: "senha" }, did, body);
}

async function routeResend(body) {
  const email = cleanEmail(body?.email);
  const purpose = body?.purpose === "reset" ? "reset" : "verify";
  const sub = await subForEmail(email);
  if (!sub) return json(200, { ok: true, emailSent: false });
  const r = await issueCode(email, purpose);
  return json(200, { ok: true, emailSent: r.sent, again: !!r.again, testCode: r.code });
}

async function routeProfile(body) {
  const { priv, pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const v = validateProfile(body);
  if (v.error) return fail(400, "BAD_PROFILE", v.error);
  const user = { ...r.user, ...v.profile };
  await store.putUser(user);
  return json(200, await statusPayload(user, r.s.did, body?.appVersion, priv));
}

// ---------------------------------------------------------------- plano
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
  let user = { ...r.user };
  if (active && expiry > Date.now()) user = applyPurchase(user, expiry, sub.lineItems?.[0]?.productId);
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
  const limit = Number((await store.getAppConfig()).trafficDailyLimit) || Number(env.TRAFFIC_DAILY_LIMIT) || 300;
  if (used > limit) return fail(429, "QUOTA", "Limite diário de consultas de trânsito atingido");
  const p = (v) => (Array.isArray(v) && v.length === 2 && v.every(Number.isFinite) ? v : null);
  const driver = p(body?.driver), pickup = p(body?.pickup), dropoff = p(body?.dropoff); // [lat, lng]
  if (!driver || !pickup || !dropoff) return fail(400, "BAD_REQUEST", "Coordenadas incompletas");
  const mode = body?.mode === "car" ? "Car" : "Scooter";
  const ll = (x) => [x[1], x[0]];
  const resp = await fetch(`https://routes.geo.sa-east-1.amazonaws.com/v2/routes?key=${encodeURIComponent(env.AWS_ROUTES_KEY || "")}`, {
    method: "POST", signal: AbortSignal.timeout(1400), headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ Origin: ll(driver), Destination: ll(dropoff), Waypoints: [{ Position: ll(pickup) }], TravelMode: mode, DepartNow: true, LegAdditionalFeatures: ["Summary"] })
  }).catch(() => null);
  if (!resp || !resp.ok) return fail(502, "TRAFFIC_UNAVAILABLE", "Trânsito indisponível");
  const data = await resp.json();
  const summary = data?.Routes?.[0]?.Summary;
  if (!summary) return fail(502, "TRAFFIC_UNAVAILABLE", "Trânsito indisponível");
  // km de cada trecho: [voce -> embarque, embarque -> destino] (pega passarela / volta que a 99 nao conta)
  const legs = (data.Routes[0].Legs || []).map((l) => l?.VehicleLegDetails?.Summary?.Overview?.Distance).filter(Number.isFinite);
  return json(200, {
    ok: true, minutes: summary.Duration / 60, meters: summary.Distance, mode, used, limit,
    pickupMeters: legs.length === 2 ? legs[0] : null, rideMeters: legs.length === 2 ? legs[1] : null
  });
}

// ---------------------------------------------------------------- copia na conta (zonas, ganhos, registros)
const SYNC_KINDS = ["zones", "earnings", "log"];

export function syncKey(sub, kind, name, now = Date.now()) {
  if (kind === "log") {
    const stamp = new Date(now - 3 * 3_600_000).toISOString().slice(0, 19).replace(/[-:]/g, "").replace("T", "_");
    return `users/${sub}/logs/${stamp}-${cleanId(name) || "log"}.txt.gz`;
  }
  return `users/${sub}/backup/${kind === "zones" ? "zones.json.gz" : "earnings.db.gz"}`;
}

async function routeSyncUpload(body) {
  const { pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const kind = String(body?.kind || "");
  if (!SYNC_KINDS.includes(kind)) return fail(400, "BAD_REQUEST", "Tipo inválido");
  const key = syncKey(r.user.sub, kind, body?.name);
  const url = presign("PUT", key, 900);
  if (!url) return fail(503, "FILES_NOT_READY", "Armazenamento indisponível");
  return json(200, { ok: true, url, key });
}

async function routeSyncDone(body, now = Date.now()) {
  const { pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const kind = String(body?.kind || "");
  const key = String(body?.key || "");
  if (!SYNC_KINDS.includes(kind) || !key.startsWith(`users/${r.user.sub}/`) || key.includes("..")) return fail(400, "BAD_REQUEST", "Arquivo inválido");
  const head = await headObject(key);
  if (!head) return fail(404, "NOT_FOUND", "Arquivo não chegou");
  const meta = String(body?.meta || "").slice(0, 200);
  if (kind === "log") await store.addLogFile(r.user.sub, { at: now, key, size: head.size, name: cleanId(body?.name), meta });
  else await store.putBackup(r.user.sub, kind, { key, at: now, size: head.size, meta, appVersion: Number(body?.appVersion) || 0 });
  return json(200, { ok: true, at: now });
}

async function routeSyncInfo(body) {
  const { pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const list = await store.listBackups(r.user.sub);
  const out = {};
  for (const b of list) out[b.kind] = { at: b.at, size: b.size, meta: b.meta || "", url: presign("GET", b.key, 900) };
  return json(200, { ok: true, backups: out });
}

// ---------------------------------------------------------------- administracao
const adminEmails = () => (env.ADMIN_EMAILS || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);

function isAdmin(event) {
  const h = event.headers || {};
  const got = Buffer.from(String(h["x-admin-token"] || ""));
  const want = Buffer.from(String(env.ADMIN_TOKEN || ""));
  if (want.length >= 24 && got.length === want.length && crypto.timingSafeEqual(got, want)) return true;
  const s = verifyToken(String(h["x-admin-session"] || ""), keys().pub, "adm");
  return !!s && adminEmails().includes(s.email);
}

/** Painel: entra com e-mail e senha de uma conta do app que esteja na lista ADMIN_EMAILS. */
async function routeAdminLogin(body, now = Date.now()) {
  const email = cleanEmail(body?.email);
  if (!adminEmails().includes(email)) return fail(401, "ADMIN_ONLY", "E-mail ou senha incorretos");
  if (await tooManyFailures("admin:" + email, now)) return fail(429, "TOO_MANY", "Muitas tentativas. Espere 1 hora.");
  const sub = await subForEmail(email);
  const user = sub ? await store.getUser(sub) : null;
  if (!user?.passwordHash || !checkPassword(body?.password, user.passwordHash)) {
    await store.countFailure(failKey("admin:" + email, now));
    return fail(401, "ADMIN_ONLY", "E-mail ou senha incorretos");
  }
  const session = signToken({ typ: "adm", email, iat: now, exp: now + 12 * 3_600_000 }, keys().priv);
  return json(200, { ok: true, session, email });
}

const safeUser = (u) => { const { passwordHash, ...rest } = u; return { ...rest, hasPassword: !!passwordHash }; };

const ADMIN_READ_ONLY = new Set(["/admin/api/overview", "/admin/api/user-detail", "/admin/api/user-files", "/admin/api/logs", "/admin/api/audit", "/admin/api/coupon-uses"]);

async function routeAdmin(path, body, who = "admin") {
  if (!ADMIN_READ_ONLY.has(path)) {
    const { password, sendReset, ...safe } = body || {};
    await store.log("ADMIN", path.replace("/admin/api/", ""), { who, ...safe });
  }
  return routeAdminInner(path, body);
}

async function routeAdminInner(path, body) {
  const now = Date.now();
  switch (path) {
    case "/admin/api/overview": {
      const rows = (await store.listUsers()).filter((u) => !u.deleted).map((u) => ({ ...safeUser(u), ...computeStatus(u, now) }));
      const count = (s) => rows.filter((x) => x.status === s).length;
      const cfg = await store.getAppConfig();
      const price = pricing(cfg);
      // Numeros do negocio
      const dayKey = (t) => new Date(t - 3 * 3_600_000).toISOString().slice(0, 10);
      const signups = {};
      for (let i = 13; i >= 0; i--) signups[dayKey(now - i * DAY)] = 0;
      rows.forEach((u) => { const k = u.createdAt && dayKey(u.createdAt); if (k in signups) signups[k]++; });
      const versions = {};
      rows.forEach((u) => { if (u.lastAppVersion) versions[u.lastAppVersion] = (versions[u.lastAppVersion] || 0) + 1; });
      const paying = rows.filter((u) => u.status === "active" && u.plan && !["vitalicio", "cupom"].includes(u.plan));
      const today = dayKey(now);
      const traffic = (await store.listQuotas(today)).map((q) => ({ sub: q.sub, n: q.n, email: rows.find((u) => u.sub === q.sub)?.email || q.sub }));
      return json(200, {
        ok: true,
        totals: { users: rows.length, active: count("active"), trial: count("trial"), expired: count("expired"), blocked: count("blocked"), pending: rows.filter((x) => x.pending).length },
        stats: {
          seen24h: rows.filter((u) => (u.lastSeenAt || 0) > now - DAY).length,
          new7d: rows.filter((u) => (u.createdAt || 0) > now - 7 * DAY).length,
          trialsEnding3d: rows.filter((u) => u.status === "trial" && u.until && u.until < now + 3 * DAY).length,
          paying: paying.length, lifetime: rows.filter((u) => u.lifetime).length,
          mrr: Math.round(paying.reduce((s, u) => s + (u.plan === "plano_anual" ? price.yearly / 12 : price.monthly), 0) * 100) / 100,
          signups, versions, trafficToday: traffic.reduce((s, t) => s + t.n, 0), traffic
        },
        users: rows.sort((a, b) => (b.lastSeenAt || b.createdAt || 0) - (a.lastSeenAt || a.createdAt || 0)),
        devices: await store.listDevices(), coupons: await store.listCoupons(), appConfig: cfg, pricing: price,
        versions: (await store.listVersions()).map((v) => ({ ...v, url: v.uploaded ? apkUrl(v) : "" })),
        codes: (await store.listCodes()).filter((c) => c.plain && c.exp > now).map(({ hash, ...c }) => c),
        loginCodes: (await store.listLoginCodes()).filter((c) => !c.used && (c.expiresAt || 0) > now)
      });
    }
    case "/admin/api/user-detail": {
      const u = await store.getUser(String(body.sub || ""));
      if (!u) return fail(404, "NOT_FOUND", "Usuário não encontrado");
      const devices = (await store.listDevices()).filter((d) => d.lastUser === u.sub || d.trialBy === u.sub || d.did === u.activeDevice);
      const coupons = [];
      for (const c of await store.listCoupons()) if (await store.couponUsedBy(c.code, u.sub)) coupons.push(c.code);
      return json(200, {
        ok: true, user: { ...safeUser(u), ...computeStatus(u, now) }, devices, coupons,
        events: await store.listLogs(u.sub),
        backups: (await store.listBackups(u.sub)).map((b) => ({ ...b, url: presign("GET", b.key, 3600) })),
        logs: (await store.listLogFiles(u.sub, 50)).map((f) => ({ ...f, url: presign("GET", f.key, 3600) }))
      });
    }
    case "/admin/api/user": {
      const u = await store.getUser(body.sub);
      if (!u) return fail(404, "NOT_FOUND", "Usuário não encontrado");
      const out = {};
      if (typeof body.blocked === "boolean") { u.blocked = body.blocked; u.blockReason = body.reason || ""; }
      if (Number(body.addDays)) u.paidUntil = Math.max(now, u.paidUntil || 0) + Number(body.addDays) * DAY;
      if (body.setUntil) {
        // "Liberar ate dd/mm": define o fim do plano numa data exata (fim do dia, horario de Brasilia)
        const t = Date.parse(String(body.setUntil) + "T23:59:59-03:00");
        if (Number.isNaN(t)) return fail(400, "BAD_REQUEST", "Data inválida");
        u.paidUntil = t; if (!u.plan || u.plan === "teste") u.plan = "cortesia";
      }
      if (body.endPlan) { u.paidUntil = now - 1; u.trialEndsAt = Math.min(u.trialEndsAt || 0, now - 1); u.lifetime = false; }
      if (typeof body.lifetime === "boolean") { u.lifetime = body.lifetime; if (body.lifetime) u.plan = "vitalicio"; }
      if (body.resetDevice) u.activeDevice = "";
      if (body.confirmEmail) { u.pending = false; u.emailVerified = true; }
      if (typeof body.note === "string") u.adminNote = body.note.slice(0, 1000);
      if (typeof body.notice === "string") u.notice = body.notice.trim() ? { id: String(now), text: body.notice.trim().slice(0, 500), at: now } : null;
      if (body.revokeSessions) { u.sessionVersion = (u.sessionVersion || 0) + 1; u.activeDevice = ""; }
      if (body.command) {
        // Comando que o app da pessoa executa na proxima conferencia (ex.: limpar ganhos antigos)
        const type = String(body.command.type || "");
        if (!["wipe_earnings_before", "sync_now"].includes(type)) return fail(400, "BAD_REQUEST", "Comando desconhecido");
        const cmd = { id: crypto.randomBytes(4).toString("hex"), type, day: String(body.command.day || "").slice(0, 10), at: now };
        u.commands = [...(Array.isArray(u.commands) ? u.commands : []), cmd].slice(-10);
        out.command = cmd;
      }
      if (body.sendReset && u.email) {
        // Codigo para criar senha nova (o painel mostra, voce manda para a pessoa)
        const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
        await store.putCode(u.email, "reset", {
          hash: crypto.createHash("sha256").update(`${u.email}:${code}`).digest("hex"),
          exp: now + 60 * 60_000, attempts: 0, createdAt: now, sent: false, plain: code, ttl: Math.floor(now / 1000) + 86400
        });
        out.resetCode = code;
      }
      if (body.deleteAccount) {
        // Apaga a conta (dados de acesso). Se voltar, entra como conta nova, mas sem teste gratis.
        Object.assign(u, { deleted: true, deletedAt: now, blocked: true, blockReason: "Conta apagada", activeDevice: "", sessionVersion: (u.sessionVersion || 0) + 1, passwordHash: undefined, commands: [] });
      }
      await store.putUser(u);
      const { sendReset, ...logged } = body;
      await store.log(u.sub, "admin", logged);
      return json(200, { ok: true, user: { ...safeUser(u), ...computeStatus(u, now) }, ...out });
    }
    case "/admin/api/coupon-uses": {
      const code = cleanCode(body.code);
      const users = await store.listUsers();
      const uses = (await store.listCouponUses(code)).map((x) => ({ ...x, email: users.find((u) => u.sub === x.sub)?.email || x.sub }));
      return json(200, { ok: true, uses });
    }
    case "/admin/api/audit":
      return json(200, { ok: true, events: await store.listLogs("ADMIN") });
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
        expiresAt: body.expiresAt ? Date.parse(String(body.expiresAt) + "T23:59:59-03:00") || null : null, note: String(body.note || ""), createdAt: now
      };
      const existing = await store.getCoupon(code);
      if (body.update) {
        if (!existing) return fail(404, "NOT_FOUND", "Cupom não encontrado");
        if ("active" in body) existing.active = body.active !== false;
        if ("maxUses" in body) existing.maxUses = Number(body.maxUses) || 0;
        if ("expiresAt" in body) existing.expiresAt = body.expiresAt ? Date.parse(String(body.expiresAt) + "T23:59:59-03:00") : null;
        if ("note" in body) existing.note = String(body.note || "");
        if ("days" in body && existing.type === "days" && Number(body.days) > 0) existing.days = Number(body.days);
        await store.putCoupon(existing);
        return json(200, { ok: true, coupon: existing });
      }
      if (c.type === "days" && c.days <= 0) return fail(400, "BAD_REQUEST", "Informe os dias do cupom");
      if (existing) return fail(409, "EXISTS", "Já existe um cupom com esse código");
      await store.putCoupon(c);
      return json(200, { ok: true, coupon: c });
    }
    case "/admin/api/app-config": {
      const cfg = { ...(await store.getAppConfig()) };
      if (!Object.keys(body).length) Object.assign(cfg, { minVersionCode: 0, latestVersionCode: 0, updateUrl: "", message: "" });
      for (const k of ["minVersionCode", "latestVersionCode"]) if (k in body) cfg[k] = Number(body[k]) || 0;
      for (const k of ["updateUrl", "message"]) if (k in body) cfg[k] = String(body[k] || "");
      cfg.updatedAt = now;
      await store.putAppConfig(cfg);
      return json(200, { ok: true, appConfig: cfg });
    }
    case "/admin/api/settings": {
      const cfg = { ...(await store.getAppConfig()) };
      const n = (v, min, max) => Math.min(max, Math.max(min, Number(v) || 0));
      if (body.trialDays !== undefined) cfg.trialDays = n(body.trialDays, 0, 90);
      if (body.priceMonthly !== undefined) cfg.priceMonthly = n(body.priceMonthly, 0, 9999);
      if (body.priceYearly !== undefined) cfg.priceYearly = n(body.priceYearly, 0, 99999);
      if (body.trafficDailyLimit !== undefined) cfg.trafficDailyLimit = n(body.trafficDailyLimit, 0, 5000);
      if (typeof body.notice === "string") cfg.notice = body.notice.trim() ? { id: String(now), text: body.notice.trim().slice(0, 500), at: now } : null;
      if (body.maintenance && typeof body.maintenance === "object") {
        cfg.maintenance = { on: !!body.maintenance.on, message: String(body.maintenance.message || "").slice(0, 300) };
      }
      cfg.updatedAt = now;
      await store.putAppConfig(cfg);
      return json(200, { ok: true, appConfig: cfg, pricing: pricing(cfg) });
    }
    case "/admin/api/version-new": {
      const versionCode = Number(body.versionCode) || 0;
      if (versionCode <= 0) return fail(400, "BAD_REQUEST", "Informe o número da versão (versionCode)");
      const old = await store.getVersion(versionCode);
      if (old?.uploaded && !body.replace) return fail(409, "EXISTS", "Essa versão já foi enviada");
      const s3Key = `apks/filtro-${versionCode}-${crypto.randomBytes(4).toString("hex")}.apk`;
      const v = {
        versionCode, versionName: String(body.versionName || "").slice(0, 20), notes: String(body.notes || "").slice(0, 500),
        mandatory: !!body.mandatory, active: false, uploaded: false, s3Key,
        sha256: String(body.sha256 || "").toLowerCase().replace(/[^0-9a-f]/g, ""), createdAt: now
      };
      await store.putVersion(v);
      return json(200, { ok: true, version: v, uploadUrl: presign("PUT", s3Key, 1800) });
    }
    case "/admin/api/version-done": {
      const v = await store.getVersion(Number(body.versionCode));
      if (!v) return fail(404, "NOT_FOUND", "Versão não encontrada");
      const head = await headObject(v.s3Key);
      if (!head) return fail(404, "NOT_FOUND", "O APK não chegou no servidor");
      if (body.sha256) v.sha256 = String(body.sha256).toLowerCase().replace(/[^0-9a-f]/g, "");
      Object.assign(v, { uploaded: true, active: body.active !== false, size: head.size, uploadedAt: now });
      await store.putVersion(v);
      return json(200, { ok: true, version: v });
    }
    case "/admin/api/version": {
      const v = await store.getVersion(Number(body.versionCode));
      if (!v) return fail(404, "NOT_FOUND", "Versão não encontrada");
      if (body.remove) {
        await deleteObject(v.s3Key);
        await store.delVersion(v.versionCode);
        return json(200, { ok: true, removed: true });
      }
      if (typeof body.mandatory === "boolean") v.mandatory = body.mandatory;
      if (typeof body.active === "boolean") v.active = body.active;
      if (typeof body.notes === "string") v.notes = body.notes.slice(0, 500);
      await store.putVersion(v);
      return json(200, { ok: true, version: v });
    }
    case "/admin/api/user-files": {
      const sub = String(body.sub || "");
      const backups = (await store.listBackups(sub)).map((b) => ({ ...b, url: presign("GET", b.key, 3600) }));
      const logs = (await store.listLogFiles(sub, 100)).map((f) => ({ ...f, url: presign("GET", f.key, 3600) }));
      return json(200, { ok: true, backups, logs });
    }
    case "/admin/api/login-code": {
      const email = cleanEmail(body.email);
      if (!/^[^@\s]+@[^@\s]+$/.test(email)) return fail(400, "BAD_REQUEST", "Informe um e-mail válido");
      const code = crypto.randomBytes(5).toString("hex").toUpperCase();
      const lc = { code, email, used: false, createdAt: now, expiresAt: now + 7 * DAY };
      await store.putLoginCode(lc);
      return json(200, { ok: true, loginCode: lc });
    }
    case "/admin/api/logs":
      return json(200, { ok: true, logs: await store.listLogs(body.sub) });
    case "/admin/api/test-login": {
      // So administrador: entra como uma conta de teste (suporte e testes)
      const email = cleanEmail(body.email || "teste@filtro.local");
      const sub = "teste-" + crypto.createHash("sha256").update(email).digest("hex").slice(0, 16);
      return finishLogin({ sub, email, name: "Conta de teste" }, cleanId(body.deviceId) || "admin-teste", body, now);
    }
    default:
      return fail(404, "NOT_FOUND", "Rota não encontrada");
  }
}

// ---------------------------------------------------------------- comandos e painel pelo app
/** O app avisa que executou um comando do painel (ex.: limpou os ganhos): sai da fila. */
async function routeCommandDone(body) {
  const { priv, pub } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const id = String(body?.id || "");
  const user = { ...r.user, commands: (r.user.commands || []).filter((c) => c.id !== id) };
  await store.putUser(user);
  await store.log(user.sub, "comando feito", { id, result: String(body?.result || "").slice(0, 200) });
  return json(200, await statusPayload(user, r.s.did, body?.appVersion, priv));
}

/**
 * Painel dentro do app: so para conta do dono (ADMIN_EMAILS) e, se ADMIN_DEVICES estiver definido,
 * so no celular do dono. Devolve uma sessao de administrador de 12 h.
 */
async function routeAdminFromApp(body, now = Date.now()) {
  const { pub, priv } = keys();
  const r = await sessionFrom(body, pub);
  if (r.err) return r.err;
  const email = String(r.user.email || "").toLowerCase();
  const devices = (env.ADMIN_DEVICES || "").split(",").map((x) => x.trim()).filter(Boolean);
  if (!adminEmails().includes(email) || (devices.length && !devices.includes(r.s.did))) {
    await store.log("ADMIN", "painel negado", { email, did: r.s.did });
    return fail(403, "ADMIN_ONLY", "Painel só para o dono do app");
  }
  const session = signToken({ typ: "adm", email, did: r.s.did, iat: now, exp: now + 12 * 3_600_000 }, priv);
  return json(200, { ok: true, session, url: "/admin" });
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
    // Link publico e fixo do APK: sempre a versao liberada mais nova (o app continua exigindo conta)
    if (method === "GET" && (path === "/download" || path === "/baixar")) {
      const live = (await store.listVersions()).filter((v) => v.active && v.uploaded).sort((a, b) => b.versionCode - a.versionCode)[0];
      if (!live) return { statusCode: 404, headers: { "Content-Type": "text/plain; charset=utf-8" }, body: "Nenhuma versão disponível" };
      return { statusCode: 302, headers: { Location: presign("GET", live.s3Key, 600), "Cache-Control": "no-store" }, body: "" };
    }
    if (path === "/admin/api/login" && method === "POST") return await routeAdminLogin(body);
    if (path.startsWith("/admin/api/")) {
      if (!isAdmin(event)) return fail(401, "ADMIN_ONLY", "Entre de novo no painel");
      const adm = verifyToken(String(event.headers?.["x-admin-session"] || ""), keys().pub, "adm");
      return await routeAdmin(path, body, adm ? `${adm.email}${adm.did ? " (app)" : ""}` : "senha mestra");
    }
    if (method === "GET" && path === "/v1/app-config") {
      const { cfg, gate } = await updateInfo(Number(event.queryStringParameters?.v) || 0);
      return json(200, { ok: true, update: { ...gate, updateUrl: gate.apk ? "" : gate.updateUrl }, pricing: pricing(cfg) });
    }
    if (method !== "POST") return fail(405, "METHOD", "Use POST");
    switch (path) {
      case "/v1/auth/google": return await routeGoogle(body);
      case "/v1/auth/code": return await routeLoginCode(body);
      case "/v1/auth/register": return await routeRegister(body);
      case "/v1/auth/verify": return await routeVerify(body);
      case "/v1/auth/login": return await routePasswordLogin(body);
      case "/v1/auth/forgot": return await routeForgot(body);
      case "/v1/auth/reset": return await routeReset(body);
      case "/v1/auth/resend": return await routeResend(body);
      case "/v1/profile": return await routeProfile(body);
      case "/v1/license": return await routeLicense(body);
      case "/v1/coupon/redeem": return await routeRedeem(body);
      case "/v1/billing/verify": return await routeBilling(body);
      case "/v1/traffic": return await routeTraffic(body);
      case "/v1/sync/upload": return await routeSyncUpload(body);
      case "/v1/sync/done": return await routeSyncDone(body);
      case "/v1/sync/info": return await routeSyncInfo(body);
      case "/v1/command/done": return await routeCommandDone(body);
      case "/v1/admin/session": return await routeAdminFromApp(body);
      default: return fail(404, "NOT_FOUND", "Rota não encontrada");
    }
  } catch (e) {
    console.error("erro", path, e);
    return fail(500, "SERVER_ERROR", "Erro no servidor, tente de novo");
  }
}
