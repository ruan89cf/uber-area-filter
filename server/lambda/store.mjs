// Armazenamento: DynamoDB (tabela unica, chaves pk/sk). Com STORE=memory usa um Map (testes locais).
const TABLE = process.env.TABLE_NAME || "filtro-inteligente";

function memoryStore() {
  const m = new Map();
  const k = (pk, sk) => `${pk}|${sk}`;
  const get = async (pk, sk) => (m.has(k(pk, sk)) ? structuredClone(m.get(k(pk, sk))) : null);
  const put = async (pk, sk, item) => { m.set(k(pk, sk), structuredClone({ ...item, pk, sk })); };
  const scan = async (prefix, skEq) => [...m.values()].filter((v) => v.pk.startsWith(prefix) && (!skEq || v.sk === skEq)).map((v) => structuredClone(v));
  const query = async (pk, limit = 50) => [...m.values()].filter((v) => v.pk === pk).sort((a, b) => (a.sk < b.sk ? 1 : -1)).slice(0, limit).map((v) => structuredClone(v));
  const del = async (pk, sk) => { m.delete(k(pk, sk)); };
  return { get, put, scan, query, del, add: async (pk, sk, field, n) => { const it = (await get(pk, sk)) || {}; it[field] = (it[field] || 0) + n; await put(pk, sk, it); return it[field]; }, _m: m };
}

async function dynamoStore() {
  const { DynamoDBClient } = await import("@aws-sdk/client-dynamodb");
  const { DynamoDBDocumentClient, GetCommand, PutCommand, ScanCommand, UpdateCommand, QueryCommand, DeleteCommand } = await import("@aws-sdk/lib-dynamodb");
  const doc = DynamoDBDocumentClient.from(new DynamoDBClient({}), { marshallOptions: { removeUndefinedValues: true } });
  return {
    get: async (pk, sk) => (await doc.send(new GetCommand({ TableName: TABLE, Key: { pk, sk } }))).Item || null,
    put: async (pk, sk, item) => { await doc.send(new PutCommand({ TableName: TABLE, Item: { ...item, pk, sk } })); },
    scan: async (prefix, skEq) => {
      const out = [];
      let start;
      do {
        const r = await doc.send(new ScanCommand({
          TableName: TABLE, ExclusiveStartKey: start,
          FilterExpression: skEq ? "begins_with(pk, :p) AND sk = :s" : "begins_with(pk, :p)",
          ExpressionAttributeValues: skEq ? { ":p": prefix, ":s": skEq } : { ":p": prefix }
        }));
        out.push(...(r.Items || []));
        start = r.LastEvaluatedKey;
      } while (start);
      return out;
    },
    add: async (pk, sk, field, n) => {
      const r = await doc.send(new UpdateCommand({
        TableName: TABLE, Key: { pk, sk }, UpdateExpression: "ADD #f :n SET #t = :ttl",
        ExpressionAttributeNames: { "#f": field, "#t": "ttl" },
        ExpressionAttributeValues: { ":n": n, ":ttl": Math.floor(Date.now() / 1000) + 3 * 86400 }, ReturnValues: "UPDATED_NEW"
      }));
      return r.Attributes[field];
    },
    query: async (pk, limit = 50) => (await doc.send(new QueryCommand({
      TableName: TABLE, KeyConditionExpression: "pk = :p", ExpressionAttributeValues: { ":p": pk }, ScanIndexForward: false, Limit: limit
    }))).Items || [],
    del: async (pk, sk) => { await doc.send(new DeleteCommand({ TableName: TABLE, Key: { pk, sk } })); }
  };
}

let backendPromise = null;
const backend = () => (backendPromise ||= (process.env.STORE === "memory" ? Promise.resolve(memoryStore()) : dynamoStore()));
const strip = (it) => { if (!it) return it; const { pk, sk, ...rest } = it; return rest; };

export const store = {
  async getUser(sub) { return strip(await (await backend()).get(`USER#${sub}`, "PROFILE")); },
  async putUser(u) { await (await backend()).put(`USER#${u.sub}`, "PROFILE", u); },
  async listUsers() { return (await (await backend()).scan("USER#", "PROFILE")).map(strip); },
  async getDevice(did) { return did ? strip(await (await backend()).get(`DEVICE#${did}`, "META")) : null; },
  async putDevice(d) { await (await backend()).put(`DEVICE#${d.did}`, "META", d); },
  async listDevices() { return (await (await backend()).scan("DEVICE#", "META")).map(strip); },
  async getCoupon(code) { return strip(await (await backend()).get(`COUPON#${code}`, "META")); },
  async putCoupon(c) { await (await backend()).put(`COUPON#${c.code}`, "META", c); },
  async listCoupons() { return (await (await backend()).scan("COUPON#", "META")).map(strip); },
  async couponUsedBy(code, sub) { return !!(await (await backend()).get(`COUPON#${code}`, `USED#${sub}`)); },
  async markCouponUsed(code, sub, at) {
    const b = await backend();
    await b.put(`COUPON#${code}`, `USED#${sub}`, { at });
    await b.add(`COUPON#${code}`, "META", "uses", 1);
  },
  async getLoginCode(code) { return strip(await (await backend()).get(`LOGINCODE#${code}`, "META")); },
  async putLoginCode(lc) { await (await backend()).put(`LOGINCODE#${lc.code}`, "META", lc); },
  async getAppConfig() { return strip(await (await backend()).get("CONFIG", "APP")) || {}; },
  async putAppConfig(cfg) { await (await backend()).put("CONFIG", "APP", cfg); },
  async getPurchaseOwner(token) { return (await (await backend()).get(`PURCHASE#${token.slice(0, 200)}`, "META"))?.sub || null; },
  async setPurchaseOwner(token, sub) { await (await backend()).put(`PURCHASE#${token.slice(0, 200)}`, "META", { sub }); },
  async incrementQuota(sub, day) { return (await backend()).add(`QUOTA#${sub}#${day}`, "TRAFFIC", "n", 1); },
  async log(sub, what, data) {
    const at = Date.now();
    await (await backend()).put(`LOG#${sub}`, `${at}`, { what, data: JSON.stringify(data || {}).slice(0, 500), at });
  },
  async listLogs(sub) {
    const b = await backend();
    if (b.query) return (await b.query(`LOG#${sub}`)).map(strip);
    return (await b.scan(`LOG#${sub}`)).map(strip).sort((x, y) => y.at - x.at).slice(0, 50);
  },
  // conta por e-mail: indice e-mail -> sub (Google, senha e codigo caem na mesma conta)
  async subForEmail(email) { return (await (await backend()).get(`EMAIL#${email}`, "META"))?.sub || null; },
  async linkEmail(email, sub) { await (await backend()).put(`EMAIL#${email}`, "META", { sub }); },
  // codigos de 6 digitos (confirmar e-mail / trocar senha)
  async getCode(email, purpose) { return strip(await (await backend()).get(`CODE#${email}`, purpose)); },
  async putCode(email, purpose, c) { await (await backend()).put(`CODE#${email}`, purpose, c); },
  async delCode(email, purpose) { await (await backend()).put(`CODE#${email}`, purpose, { exp: 0, used: true, ttl: Math.floor(Date.now() / 1000) + 3600 }); },
  async listCodes() { return (await (await backend()).scan("CODE#")).map((x) => ({ ...strip(x), email: x.pk.slice(5), purpose: x.sk })); },
  async countFailure(key) { return (await backend()).add(`FAIL#${key}`, "N", "n", 1); },
  // versoes do app (APK no S3)
  async getVersion(code) { return strip(await (await backend()).get(`VERSION#${code}`, "META")); },
  async putVersion(v) { await (await backend()).put(`VERSION#${v.versionCode}`, "META", v); },
  async delVersion(code) { await (await backend()).del(`VERSION#${code}`, "META"); },
  async listVersions() { return (await (await backend()).scan("VERSION#", "META")).map(strip).sort((a, b) => b.versionCode - a.versionCode); },
  // copias de seguranca da conta (zonas, ganhos) e registros enviados
  async getBackup(sub, kind) { return strip(await (await backend()).get(`BACKUP#${sub}`, kind)); },
  async putBackup(sub, kind, b) { await (await backend()).put(`BACKUP#${sub}`, kind, b); },
  async listBackups(sub) { return (await (await backend()).query(`BACKUP#${sub}`)).map((x) => ({ ...strip(x), kind: x.sk })); },
  async addLogFile(sub, f) { await (await backend()).put(`LOGFILE#${sub}`, `${f.at}`, f); },
  async listLogFiles(sub, limit = 60) { return (await (await backend()).query(`LOGFILE#${sub}`, limit)).map(strip); },
  async listLoginCodes() { return (await (await backend()).scan("LOGINCODE#", "META")).map(strip); },
  async listCouponUses(code) {
    return (await (await backend()).query(`COUPON#${code}`, 500)).filter((x) => String(x.sk).startsWith("USED#"))
      .map((x) => ({ sub: x.sk.slice(5), at: x.at }));
  },
  /** Consultas de transito do dia (QUOTA#<sub>#<dia>). */
  async listQuotas(day) {
    return (await (await backend()).scan("QUOTA#")).filter((x) => x.pk.endsWith("#" + day))
      .map((x) => ({ sub: x.pk.slice(6, -(day.length + 1)), n: Number(x.n) || 0 }));
  },
  _backend: backend
};
