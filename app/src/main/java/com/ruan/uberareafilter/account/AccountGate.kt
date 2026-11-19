package com.ruan.uberareafilter.account

import android.content.Context
import com.ruan.uberareafilter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Porta de entrada do app: sem licenca valida (assinada pelo servidor, deste celular, dentro do prazo)
 * nada funciona. A licenca vale ~6 h; o app renova sozinho com internet.
 */
object AccountGate {

    /** Renova quando faltar menos que isso para a licenca vencer (ou se passou 1 h da ultima conferida). */
    private const val RENEW_BEFORE_MS = 2 * 3_600_000L
    private const val RECHECK_MS = 3_600_000L

    fun isLicensed(context: Context): Boolean =
        LicenseVerifier.verify(AccountStore(context).license, DeviceIdentity.id(context)) != null

    fun license(context: Context): License? =
        LicenseVerifier.verify(AccountStore(context).license, DeviceIdentity.id(context))

    fun hasSession(context: Context): Boolean = !AccountStore(context).session.isNullOrBlank()

    fun needsRefresh(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val store = AccountStore(context)
        val lic = license(context) ?: return true
        return lic.exp - now < RENEW_BEFORE_MS || now - store.lastRefreshAt > RECHECK_MS
    }

    fun state(context: Context): AccountState? =
        AccountStore(context).stateJson?.let { runCatching { parseState(JSONObject(it), null) }.getOrNull() }

    private fun baseBody(context: Context) = JSONObject()
        .put("deviceId", DeviceIdentity.id(context))
        .put("deviceModel", DeviceIdentity.model())
        .put("appVersion", BuildConfig.VERSION_CODE)
        .put("cert", DeviceIdentity.signingCert(context))

    private fun parseState(j: JSONObject, message: String?): AccountState {
        val up = j.optJSONObject("update")
        return AccountState(
            email = j.optJSONObject("account")?.optString("email").orEmpty(),
            status = j.optString("status", "none"),
            plan = j.optString("plan").takeIf { it.isNotBlank() && it != "null" },
            until = if (j.isNull("until") || !j.has("until")) null else j.optLong("until"),
            mustUpdate = up?.optBoolean("mustUpdate") == true,
            hasUpdate = up?.optBoolean("hasUpdate") == true,
            updateUrl = up?.optString("updateUrl").orEmpty(),
            updateMessage = up?.optString("message").orEmpty(),
            message = message ?: j.optString("couponMessage").takeIf { it.isNotBlank() }
                ?: j.optString("message").takeIf { it.isNotBlank() && !j.optBoolean("ok", true) }
        )
    }

    /** Guarda o que o servidor mandou. Erro de conta (bloqueio, outro celular...) apaga a licenca. */
    private fun absorb(context: Context, r: ServerApi.ApiResult): AccountState? {
        val store = AccountStore(context)
        if (r.ok && r.json != null) {
            r.json.optString("session").takeIf { it.isNotBlank() }?.let { store.session = it }
            store.license = r.json.optString("license").takeIf { it.isNotBlank() && it != "null" }
            store.lastRefreshAt = System.currentTimeMillis()
            store.stateJson = r.json.toString()
            return parseState(r.json, null)
        }
        if (r.httpCode in 400..499) {
            val fatal = r.code in setOf("SESSION_INVALID", "ACCOUNT_BLOCKED", "DEVICE_BLOCKED", "OTHER_DEVICE", "APP_MODIFIED")
            if (fatal) {
                store.license = null
                if (r.code == "SESSION_INVALID" || r.code == "OTHER_DEVICE") store.session = null
                val blocked = r.code == "ACCOUNT_BLOCKED" || r.code == "DEVICE_BLOCKED" || r.code == "APP_MODIFIED"
                val st = AccountState("", if (blocked) "blocked" else "none", null, null, false, false, "", "", r.message)
                store.stateJson = JSONObject().put("ok", false).put("status", st.status).put("message", r.message).toString()
                return st
            }
        }
        return state(context)?.copy(message = r.message)
    }

    suspend fun loginWithGoogle(context: Context, idToken: String): Pair<AccountState?, String?> = withContext(Dispatchers.IO) {
        val r = ServerApi.post("/v1/auth/google", baseBody(context).put("idToken", idToken))
        val st = absorb(context, r)
        st to (if (r.ok) null else r.message)
    }

    suspend fun loginWithCode(context: Context, code: String): Pair<AccountState?, String?> = withContext(Dispatchers.IO) {
        val r = ServerApi.post("/v1/auth/code", baseBody(context).put("code", code.trim()))
        absorb(context, r) to (if (r.ok) null else r.message)
    }

    /** Renova a licenca. Sem internet: mantem a atual ate ela vencer. */
    suspend fun refresh(context: Context): AccountState? = withContext(Dispatchers.IO) {
        val session = AccountStore(context).session ?: return@withContext null
        absorb(context, ServerApi.post("/v1/license", baseBody(context).put("session", session)))
    }

    suspend fun redeemCoupon(context: Context, code: String): Pair<AccountState?, String?> = withContext(Dispatchers.IO) {
        val session = AccountStore(context).session ?: return@withContext null to "Faça login primeiro"
        val r = ServerApi.post("/v1/coupon/redeem", baseBody(context).put("session", session).put("code", code))
        absorb(context, r) to (if (r.ok) null else r.message)
    }

    suspend fun verifyPurchase(context: Context, purchaseToken: String, productId: String): Pair<AccountState?, String?> =
        withContext(Dispatchers.IO) {
            val session = AccountStore(context).session ?: return@withContext null to "Faça login primeiro"
            val r = ServerApi.post(
                "/v1/billing/verify",
                baseBody(context).put("session", session).put("purchaseToken", purchaseToken).put("productId", productId)
            )
            absorb(context, r) to (if (r.ok) null else r.message)
        }

    /** Transito pelo servidor (a chave da AWS fica so no servidor). Devolve minutos ou null. */
    fun trafficMinutes(context: Context, driver: Pair<Double, Double>, pickup: Pair<Double, Double>, dropoff: Pair<Double, Double>, timeoutMs: Int): Double? {
        val session = AccountStore(context).session ?: return null
        fun arr(p: Pair<Double, Double>) = org.json.JSONArray().put(p.first).put(p.second)
        val r = ServerApi.post(
            "/v1/traffic",
            JSONObject().put("session", session).put("driver", arr(driver)).put("pickup", arr(pickup)).put("dropoff", arr(dropoff)),
            timeoutMs
        )
        return if (r.ok) r.json?.optDouble("minutes")?.takeIf { !it.isNaN() } else null
    }

    fun logout(context: Context) = AccountStore(context).clear()
}
