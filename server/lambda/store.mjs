// Armazenamento: DynamoDB (tabela unica, chaves pk/sk). Com STORE=memory usa um Map (testes locais).
const TABLE = process.env.TABLE_NAME || "filtro-inteligente";

function memoryStore() {
  const m = new Map();
  const k = (pk, sk) => `${pk}|${sk}`;
  const get = async (pk, sk) => (m.has(k(pk, sk)) ? structuredClone(m.get(k(pk, sk))) : null);
  const put = async (pk, sk, item) => { m.set(k(pk, sk), structuredClone({ ...item, pk, sk })); };
  const scan = async (prefix, skEq) => [...m.values()].filter((v) => v.pk.startsWith(prefix) && (!skEq || v.sk === skEq)).map((v) => structuredClone(v));
  return { get, put, scan, add: async (pk, sk, field, n) => { const it = (await get(pk, sk)) || {}; it[field] = (it[field] || 0) + n; await put(pk, sk, it); return it[field]; }, _m: m };
}

async function dynamoStore() {
  const { DynamoDBClient } = await import("@aws-sdk/client-dynamodb");
  const { DynamoDBDocumentClient, GetCommand, PutCommand, ScanCommand, UpdateCommand, QueryCommand } = await import("@aws-sdk/lib-dynamodb");
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
    query: async (pk) => (await doc.send(new QueryCommand({
      TableName: TABLE, KeyConditionExpression: "pk = :p", ExpressionAttributeValues: { ":p": pk }, ScanIndexForward: false, Limit: 50
    }))).Items || []
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
  _backend: backend
};
