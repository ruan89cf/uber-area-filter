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
