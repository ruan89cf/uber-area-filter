// Testes do servidor com banco em memoria:  node --test server.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";

process.env.STORE = "memory";
const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
process.env.SIGNING_PRIVATE_KEY_B64 = Buffer.from(privateKey.export({ type: "pkcs8", format: "pem" })).toString("base64");
process.env.SIGNING_PUBLIC_KEY_B64 = Buffer.from(publicKey.export({ type: "spki", format: "pem" })).toString("base64");
process.env.ADMIN_TOKEN = "admin-token-de-teste-com-mais-de-24-chars";

const { handler, verifyToken, trialDecision, computeStatus } = await import("./index.mjs");
const pubPem = publicKey.export({ type: "spki", format: "pem" });

const call = async (path, body, admin = false, method = "POST") => {
  const [rawPath, qs] = path.split("?");
  const r = await handler({
    rawPath, queryStringParameters: qs ? Object.fromEntries(new URLSearchParams(qs)) : undefined,
    requestContext: { http: { method } }, body: body ? JSON.stringify(body) : undefined,
    headers: admin ? { "x-admin-token": process.env.ADMIN_TOKEN } : {}
  });
  return { status: r.statusCode, body: r.headers["Content-Type"].includes("json") ? JSON.parse(r.body) : r.body };
};
const login = (email, deviceId, appVersion = 21) => call("/admin/api/test-login", { email, deviceId, appVersion }, true);

test("conta e celular novos ganham 7 dias; licenca assinada e valida", async () => {
  const r = await login("a@x.com", "cel-A");
  assert.equal(r.status, 200);
  assert.equal(r.body.status, "trial");
  assert.equal(r.body.trialGranted, true);
  const lic = verifyToken(r.body.license, pubPem, "lic");
  assert.equal(lic.st, "trial");
  assert.equal(lic.did, "cel-A");
  assert.ok(lic.exp - Date.now() <= 6 * 3600_000 + 1000);
});

test("regra do teste: conta usada OU celular usado = sem teste", async () => {
  await login("b@x.com", "cel-B");                      // b + cel-B: ganha
  const sameDeviceNewAccount = await login("c@x.com", "cel-B");
  assert.equal(sameDeviceNewAccount.body.trialGranted, false);
  assert.equal(sameDeviceNewAccount.body.status, "expired");
  assert.equal(sameDeviceNewAccount.body.license, null);
  const sameAccountNewDevice = await login("b@x.com", "cel-NOVO");
  assert.equal(sameAccountNewDevice.body.trialGranted, false);
  assert.equal(sameAccountNewDevice.body.status, "trial"); // continua no teste que ja tinha
  const bothNew = await login("d@x.com", "cel-D");
  assert.equal(bothNew.body.trialGranted, true);
  assert.deepEqual(trialDecision({ trialUsed: true }, null).grant, false);
});

test("cupom de dias e vitalicio; nao usa duas vezes; esgota", async () => {
  await call("/admin/api/coupon", { code: "IRMAO", type: "lifetime", note: "irmao" }, true);
  await call("/admin/api/coupon", { code: "MAIS30", type: "days", days: 30, maxUses: 1 }, true);
  const u = await login("c@x.com", "cel-B"); // sem teste
  const r1 = await call("/v1/coupon/redeem", { session: u.body.session, code: "mais30" });
  assert.equal(r1.status, 200);
  assert.equal(r1.body.status, "active");
  assert.ok(r1.body.until > Date.now() + 29 * 86400_000);
  const again = await call("/v1/coupon/redeem", { session: u.body.session, code: "MAIS30" });
  assert.equal(again.body.code, "COUPON_ALREADY");
  const other = await login("e@x.com", "cel-E");
  const usedUp = await call("/v1/coupon/redeem", { session: other.body.session, code: "MAIS30" });
  assert.equal(usedUp.body.code, "COUPON_USED_UP");
  const life = await call("/v1/coupon/redeem", { session: other.body.session, code: "IRMAO" });
  assert.equal(life.body.plan, "vitalicio");
  assert.equal(life.body.until, null);
  const bad = await call("/v1/coupon/redeem", { session: other.body.session, code: "NAOEXISTE" });
  assert.equal(bad.body.code, "COUPON_INVALID");
  // desativar pelo painel (so code + update) e o cupom deixa de valer
  const off = await call("/admin/api/coupon", { code: "IRMAO", update: true, active: false }, true);
  assert.equal(off.body.coupon.active, false);
  const third = await login("l@x.com", "cel-L");
  assert.equal((await call("/v1/coupon/redeem", { session: third.body.session, code: "IRMAO" })).body.code, "COUPON_INVALID");
});

test("bloqueio de conta e de celular", async () => {
  const u = await login("f@x.com", "cel-F");
  const sub = (await call("/admin/api/overview", {}, true)).body.users.find((x) => x.email === "f@x.com").sub;
  await call("/admin/api/user", { sub, blocked: true, reason: "pagamento contestado" }, true);
  const lic = await call("/v1/license", { session: u.body.session, appVersion: 21 });
  assert.equal(lic.status, 403);
  assert.equal(lic.body.message, "pagamento contestado");
  await call("/admin/api/device", { did: "cel-G", blocked: true, reason: "celular banido" }, true);
  const g = await login("g@x.com", "cel-G");
  assert.equal(g.body.code, "DEVICE_BLOCKED");
});

test("um celular por conta: login em outro celular derruba o anterior", async () => {
  const first = await login("h@x.com", "cel-H1");
  await login("h@x.com", "cel-H2");
  const old = await call("/v1/license", { session: first.body.session, appVersion: 21 });
  assert.equal(old.body.code, "OTHER_DEVICE");
});

test("atualizacao obrigatoria trava a licenca; opcional so avisa", async () => {
  await call("/admin/api/app-config", { minVersionCode: 22, latestVersionCode: 23, updateUrl: "https://exemplo", message: "Nova versão" }, true);
  const old = await login("i@x.com", "cel-I", 21);
  assert.equal(old.body.update.mustUpdate, true);
  assert.equal(old.body.license, null);
  const mid = await call("/v1/license", { session: old.body.session, appVersion: 22 });
  assert.equal(mid.body.update.mustUpdate, false);
  assert.equal(mid.body.update.hasUpdate, true);
  assert.ok(mid.body.license);
  const pub = await call("/v1/app-config?v=21", null, false, "GET");
  assert.equal(pub.status, 200);
  await call("/admin/api/app-config", {}, true);
});

test("sessao falsa ou adulterada e recusada; admin sem senha nao entra", async () => {
  const u = await login("j@x.com", "cel-J");
  const [body, sig] = u.body.session.split(".");
  const forged = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(body, "base64url")), sub: "outro" })).toString("base64url") + "." + sig;
  assert.equal((await call("/v1/license", { session: forged })).status, 401);
  assert.equal((await call("/admin/api/overview", {})).status, 401);
  assert.equal(computeStatus({ trialEndsAt: Date.now() - 1 }).status, "expired");
});

test("ids estranhos sao limpos antes de ir para o painel", async () => {
  const r = await login("k@x.com", "cel'><script>alert(1)</script>");
  assert.equal(r.status, 200);
  const devices = (await call("/admin/api/overview", {}, true)).body.devices;
  assert.ok(devices.every((d) => /^[A-Za-z0-9_-]+$/.test(d.did)));
});

test("codigo de acesso: entra uma vez so, ligado ao e-mail", async () => {
  const c = await call("/admin/api/login-code", { email: "Irmao@Gmail.com" }, true);
  assert.equal(c.status, 200);
  const code = c.body.loginCode.code;
  const r = await call("/v1/auth/code", { code: code.toLowerCase(), deviceId: "cel-code", appVersion: 21 });
  assert.equal(r.status, 200);
  assert.equal(r.body.account.email, "irmao@gmail.com");
  assert.equal(r.body.status, "trial");
  const again = await call("/v1/auth/code", { code, deviceId: "cel-code2", appVersion: 21 });
  assert.equal(again.body.code, "CODE_INVALID");
});

test("APK modificado nao recebe licenca quando o certificado esta configurado", async () => {
  process.env.APK_CERT_SHA256 = "ab".repeat(32);
  const bad = await call("/admin/api/test-login", { email: "m@x.com", deviceId: "cel-M", cert: "00".repeat(32) }, true);
  assert.equal(bad.body.code, "APP_MODIFIED");
  const good = await call("/admin/api/test-login", { email: "m@x.com", deviceId: "cel-M", cert: "AB".repeat(32) }, true);
  assert.equal(good.status, 200);
  delete process.env.APK_CERT_SHA256;
});

const profile = { firstName: "Ana", lastName: "Souza", birthDate: "1990-05-20", gender: "feminino" };

test("cadastro por e-mail: confirma com codigo, ganha teste; senha errada recusa", async () => {
  const bad = await call("/v1/auth/register", { email: "nova@x.com", password: "123", ...profile });
  assert.equal(bad.body.code, "WEAK_PASSWORD");
  const young = await call("/v1/auth/register", { email: "nova@x.com", password: "senha-forte", ...profile, birthDate: "2015-01-01" });
  assert.equal(young.body.code, "BAD_PROFILE");
  const reg = await call("/v1/auth/register", { email: "Nova@X.com", password: "senha-forte", ...profile });
  assert.equal(reg.status, 200);
  assert.equal(reg.body.needVerify, true);
  // antes de confirmar nao entra
  const early = await call("/v1/auth/login", { email: "nova@x.com", password: "senha-forte", deviceId: "cel-N" });
  assert.equal(early.body.code, "EMAIL_NOT_VERIFIED");
  const wrong = await call("/v1/auth/verify", { email: "nova@x.com", code: "000000", deviceId: "cel-N" });
  assert.equal(wrong.body.code, "CODE_INVALID");
  const ok = await call("/v1/auth/verify", { email: "nova@x.com", code: reg.body.testCode, deviceId: "cel-N", appVersion: 23 });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.status, "trial");
  assert.equal(ok.body.trialGranted, true);
  assert.equal(ok.body.account.firstName, "Ana");
  assert.equal(ok.body.account.profileComplete, true);
  const pw = await call("/v1/auth/login", { email: "nova@x.com", password: "senha-forte", deviceId: "cel-N" });
  assert.equal(pw.status, 200);
  const pwBad = await call("/v1/auth/login", { email: "nova@x.com", password: "errada123", deviceId: "cel-N" });
  assert.equal(pwBad.body.code, "BAD_LOGIN");
  const dup = await call("/v1/auth/register", { email: "nova@x.com", password: "outra-senha", ...profile });
  assert.equal(dup.body.code, "EMAIL_EXISTS");
});

test("esqueci a senha: codigo troca a senha; conta do codigo de acesso e a mesma do e-mail", async () => {
  const c = await call("/admin/api/login-code", { email: "irmao2@x.com" }, true);
  const first = await call("/v1/auth/code", { code: c.body.loginCode.code, deviceId: "cel-R" });
  assert.equal(first.status, 200);
  const f = await call("/v1/auth/forgot", { email: "irmao2@x.com" });
  assert.ok(f.body.testCode);
  const r = await call("/v1/auth/reset", { email: "irmao2@x.com", code: f.body.testCode, password: "nova-senha-1", deviceId: "cel-R" });
  assert.equal(r.status, 200);
  assert.equal(r.body.account.hasPassword, true);
  assert.equal(r.body.trialGranted, false);   // mesma conta, teste ja usado
  assert.equal(r.body.status, "trial");
  assert.equal(r.body.account.profileComplete, false);
  const p = await call("/v1/profile", { session: r.body.session, ...profile });
  assert.equal(p.body.account.profileComplete, true);
});

test("dias de teste e precos vem do painel; assinar no teste guarda os dias que sobraram", async () => {
  await call("/admin/api/settings", { trialDays: 10, priceMonthly: 27.9 }, true);
  const u = await login("p@x.com", "cel-P");
  assert.equal(u.body.trialDays, 10);
  assert.equal(u.body.pricing.monthly, 27.9);
  assert.ok(u.body.until > Date.now() + 9.9 * 86400_000);
  await call("/admin/api/settings", { trialDays: 7 }, true);
  const { applyPurchase } = await import("./index.mjs");
  const now = Date.now();
  const paid = applyPurchase({ trialEndsAt: now + 5 * 86400_000 }, now + 30 * 86400_000, "plano_mensal", now);
  assert.equal(Math.round((paid.paidUntil - now) / 86400_000), 35);
  const renewed = applyPurchase(paid, now + 60 * 86400_000, "plano_mensal", now + 30 * 86400_000);
  assert.equal(Math.round((renewed.paidUntil - now) / 86400_000), 65);
});

test("versoes: obrigatoria trava, opcional so avisa", async () => {
  const { versionGate } = await import("./index.mjs");
  const versions = [
    { versionCode: 23, active: true, uploaded: true, mandatory: false, notes: "melhorias", s3Key: "a" },
    { versionCode: 22, active: true, uploaded: true, mandatory: true, s3Key: "b" },
    { versionCode: 24, active: false, uploaded: true, mandatory: true, s3Key: "c" }
  ];
  const old = versionGate(21, {}, versions, () => "url");
  assert.equal(old.mustUpdate, true);
  assert.equal(old.latestVersionCode, 23);
  assert.equal(old.updateUrl, "url");
  const mid = versionGate(22, {}, versions, () => "url");
  assert.equal(mid.mustUpdate, false);
  assert.equal(mid.hasUpdate, true);
  assert.equal(mid.message, "melhorias");
  assert.equal(versionGate(23, {}, versions).hasUpdate, false);
});

test("painel entra com e-mail e senha so para administradores", async () => {
  process.env.ADMIN_EMAILS = "dono@x.com";
  const reg = await call("/v1/auth/register", { email: "dono@x.com", password: "senha-do-dono", ...profile });
  await call("/v1/auth/verify", { email: "dono@x.com", code: reg.body.testCode, deviceId: "cel-DONO" });
  const bad = await call("/admin/api/login", { email: "dono@x.com", password: "errada" });
  assert.equal(bad.status, 401);
  const ok = await call("/admin/api/login", { email: "dono@x.com", password: "senha-do-dono" });
  assert.equal(ok.status, 200);
  const r = await handler({ rawPath: "/admin/api/overview", requestContext: { http: { method: "POST" } }, body: "{}", headers: { "x-admin-session": ok.body.session } });
  assert.equal(r.statusCode, 200);
  assert.ok(!JSON.parse(r.body).users.some((u) => u.passwordHash));
  const notAdmin = await call("/admin/api/login", { email: "nova@x.com", password: "senha-forte" });
  assert.equal(notAdmin.status, 401);
});

test("link assinado do S3 tem formato SigV4", async () => {
  const { presign } = await import("./s3.mjs");
  process.env.FILES_BUCKET = "balde";
  const url = presign("GET", "users/x/a b.gz", 60, new Date("2026-01-01T00:00:00Z"), { AWS_ACCESS_KEY_ID: "AKID", AWS_SECRET_ACCESS_KEY: "seg" });
  assert.match(url, /^https:\/\/balde\.s3\.sa-east-1\.amazonaws\.com\/users\/x\/a%20b\.gz\?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKID%2F20260101%2Fsa-east-1%2Fs3%2Faws4_request/);
  assert.match(url, /X-Amz-Signature=[0-9a-f]{64}$/);
  delete process.env.FILES_BUCKET;
});

test("painel: data exata, aviso, comando, encerrar sessao e apagar conta", async () => {
  const u = await login("q@x.com", "cel-Q");
  const sub = (await call("/admin/api/overview", {}, true)).body.users.find((x) => x.email === "q@x.com").sub;
  const until = await call("/admin/api/user", { sub, setUntil: "2030-01-31" }, true);
  assert.equal(new Date(until.body.user.until).getUTCFullYear(), 2030);
  await call("/admin/api/user", { sub, notice: "Oi! Teste de aviso" }, true);
  const cmd = await call("/admin/api/user", { sub, command: { type: "wipe_earnings_before", day: "2026-11-19" } }, true);
  const lic = await call("/v1/license", { session: u.body.session, appVersion: 26 });
  assert.equal(lic.body.notice.text, "Oi! Teste de aviso");
  assert.equal(lic.body.commands[0].type, "wipe_earnings_before");
  const done = await call("/v1/command/done", { session: u.body.session, id: cmd.body.command.id, result: "3 apagados" });
  assert.equal(done.body.commands.length, 0);
  const reset = await call("/admin/api/user", { sub, sendReset: true }, true);
  assert.match(reset.body.resetCode, /^\d{6}$/);
  await call("/admin/api/user", { sub, revokeSessions: true }, true);
  assert.equal((await call("/v1/license", { session: u.body.session })).body.code, "SESSION_INVALID");
  await call("/admin/api/user", { sub, deleteAccount: true }, true);
  const back = await login("q@x.com", "cel-Q-novo");
  assert.equal(back.body.trialGranted, false);   // apagou e voltou: sem novo teste
  const audit = await call("/admin/api/audit", {}, true);
  assert.ok(audit.body.events.some((e) => e.what === "user"));
});

test("manutencao trava todos menos o dono; painel pelo app so para o dono", async () => {
  process.env.ADMIN_EMAILS = "dono@x.com";
  const owner = await call("/v1/auth/login", { email: "dono@x.com", password: "senha-do-dono", deviceId: "cel-DONO" });
  assert.equal(owner.status, 200);
  const other = await login("r@x.com", "cel-R2");
  await call("/admin/api/settings", { maintenance: { on: true, message: "Manutenção rápida" } }, true);
  const o = await call("/v1/license", { session: other.body.session });
  assert.equal(o.body.license, null);
  assert.equal(o.body.maintenance, "Manutenção rápida");
  const d = await call("/v1/license", { session: owner.body.session });
  assert.ok(d.body.license);
  assert.equal(d.body.account.isAdmin, true);
  await call("/admin/api/settings", { maintenance: { on: false } }, true);
  const adm = await call("/v1/admin/session", { session: owner.body.session });
  assert.equal(adm.status, 200);
  const r = await handler({ rawPath: "/admin/api/overview", requestContext: { http: { method: "POST" } }, body: "{}", headers: { "x-admin-session": adm.body.session } });
  assert.equal(r.statusCode, 200);
  assert.ok(JSON.parse(r.body).stats.signups);
  assert.equal((await call("/v1/admin/session", { session: other.body.session })).status, 403);
  process.env.ADMIN_DEVICES = "outro-celular";
  assert.equal((await call("/v1/admin/session", { session: owner.body.session })).status, 403);
  delete process.env.ADMIN_DEVICES;
});
