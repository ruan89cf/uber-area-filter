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
    private const val RECHECK_MS = 10 * 60_000L

    /** Resposta de uma acao da conta (login, cadastro, codigo...). */
    data class Result(
        val ok: Boolean,
        val state: AccountState?,
        val error: String?,
        val code: String = "",
        val email: String = "",
        val emailSent: Boolean = false,
        val trialGranted: Boolean = false,
        val trialDays: Int = 0
    )

    fun isLicensed(context: Context): Boolean =
        LicenseVerifier.verify(AccountStore(context).license, DeviceIdentity.id(context)) != null

    fun license(context: Context): License? =
        LicenseVerifier.verify(AccountStore(context).license, DeviceIdentity.id(context))

    fun hasSession(context: Context): Boolean = !AccountStore(context).session.isNullOrBlank()

    fun session(context: Context): String? = AccountStore(context).session

    fun needsRefresh(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val store = AccountStore(context)
        val lic = license(context) ?: return true
        return lic.exp - now < RENEW_BEFORE_MS || now - store.lastRefreshAt > RECHECK_MS
    }

    fun state(context: Context): AccountState? =
        AccountStore(context).stateJson?.let { runCatching { parseState(JSONObject(it), null) }.getOrNull() }

    fun baseBody(context: Context): JSONObject = JSONObject()
        .put("deviceId", DeviceIdentity.id(context))
        .put("deviceModel", DeviceIdentity.model())
        .put("appVersion", BuildConfig.VERSION_CODE)
        .put("cert", DeviceIdentity.signingCert(context))

    private fun parseState(j: JSONObject, message: String?): AccountState {
        val up = j.optJSONObject("update")
        val acc = j.optJSONObject("account")
        val price = j.optJSONObject("pricing")
        return AccountState(
            email = acc?.optString("email").orEmpty(),
            status = j.optString("status", "none"),
            plan = j.optString("plan").takeIf { it.isNotBlank() && it != "null" },
            until = if (j.isNull("until") || !j.has("until")) null else j.optLong("until"),
            mustUpdate = up?.optBoolean("mustUpdate") == true,
            hasUpdate = up?.optBoolean("hasUpdate") == true,
            updateUrl = up?.optString("updateUrl").orEmpty(),
            updateMessage = up?.optString("message").orEmpty(),
            message = message ?: j.optString("couponMessage").takeIf { it.isNotBlank() }
                ?: j.optString("message").takeIf { it.isNotBlank() && !j.optBoolean("ok", true) }
                ?: j.optString("maintenance").takeIf { it.isNotBlank() },
            firstName = acc?.optString("firstName").orEmpty(),
            lastName = acc?.optString("lastName").orEmpty(),
            birthDate = acc?.optString("birthDate").orEmpty(),
            gender = acc?.optString("gender").orEmpty(),
            profileComplete = acc?.optBoolean("profileComplete", true) ?: true,
            hasPassword = acc?.optBoolean("hasPassword") == true,
            latestVersionCode = up?.optInt("latestVersionCode") ?: 0,
            latestVersionName = up?.optString("latestVersionName").orEmpty(),
            updateIsApk = up?.optBoolean("apk") == true,
            updateSha256 = up?.optString("sha256").orEmpty(),
            updateSize = up?.optLong("size") ?: 0L,
            priceMonthly = price?.optDouble("monthly")?.takeIf { !it.isNaN() } ?: 29.9,
            priceYearly = price?.optDouble("yearly")?.takeIf { !it.isNaN() } ?: 299.0,
            trialDays = price?.optInt("trialDays", 7) ?: 7,
            isAdmin = acc?.optBoolean("isAdmin") == true,
            noticeId = j.optJSONObject("notice")?.optString("id").orEmpty(),
            noticeText = j.optJSONObject("notice")?.optString("text").orEmpty(),
            commands = j.optJSONArray("commands")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                    .map { AccountCommand(it.optString("id"), it.optString("type"), it.optString("day")) }
            } ?: emptyList(),
            maintenance = j.optString("maintenance")
        )
    }

    /** Guarda o que o servidor mandou. Erro de conta (bloqueio, outro celular...) apaga a licenca. */
    private fun absorb(context: Context, r: ServerApi.ApiResult): AccountState? {
        val store = AccountStore(context)
        if (r.ok && r.json != null && r.json.has("status")) {
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

    private fun result(context: Context, r: ServerApi.ApiResult): Result {
        val st = absorb(context, r)
        val j = r.json
        return Result(
            ok = r.ok,
            state = st,
            error = if (r.ok) null else r.message,
            code = r.code,
            email = j?.optString("email").orEmpty(),
            emailSent = j?.optBoolean("emailSent") == true,
            trialGranted = j?.optBoolean("trialGranted") == true,
            trialDays = j?.optInt("trialDays") ?: 0
        )
    }

    private suspend fun call(context: Context, path: String, body: JSONObject): Result = withContext(Dispatchers.IO) {
        result(context, ServerApi.post(path, body))
    }

    private fun withSession(context: Context): JSONObject? =
        AccountStore(context).session?.let { baseBody(context).put("session", it) }

    // ------------------------------------------------------------------ entrar / criar conta
    suspend fun loginWithPassword(context: Context, email: String, password: String) =
        call(context, "/v1/auth/login", baseBody(context).put("email", email.trim()).put("password", password))

    suspend fun register(context: Context, email: String, password: String, profile: Profile) =
        call(context, "/v1/auth/register", profile.into(baseBody(context)).put("email", email.trim()).put("password", password))

    suspend fun verifyEmail(context: Context, email: String, code: String) =
        call(context, "/v1/auth/verify", baseBody(context).put("email", email.trim()).put("code", code.trim()))

    suspend fun resendCode(context: Context, email: String, reset: Boolean) =
        call(context, "/v1/auth/resend", baseBody(context).put("email", email.trim()).put("purpose", if (reset) "reset" else "verify"))

    suspend fun forgotPassword(context: Context, email: String) =
        call(context, "/v1/auth/forgot", baseBody(context).put("email", email.trim()))

    suspend fun resetPassword(context: Context, email: String, code: String, password: String) =
        call(context, "/v1/auth/reset", baseBody(context).put("email", email.trim()).put("code", code.trim()).put("password", password))

    suspend fun loginWithGoogle(context: Context, idToken: String) =
        call(context, "/v1/auth/google", baseBody(context).put("idToken", idToken))

    suspend fun loginWithCode(context: Context, code: String) =
        call(context, "/v1/auth/code", baseBody(context).put("code", code.trim()))

    suspend fun updateProfile(context: Context, profile: Profile): Result {
        val body = withSession(context) ?: return Result(false, null, "Faça login primeiro")
        return call(context, "/v1/profile", profile.into(body))
    }

    /** Nome, sobrenome, nascimento (aaaa-mm-dd) e genero (masculino/feminino/outro/nao_informar). */
    data class Profile(val firstName: String, val lastName: String, val birthDate: String, val gender: String) {
        fun into(j: JSONObject): JSONObject = j.put("firstName", firstName.trim()).put("lastName", lastName.trim())
            .put("birthDate", birthDate).put("gender", gender)
    }

    // ------------------------------------------------------------------ plano
    /** Renova a licenca. Sem internet: mantem a atual ate ela vencer. */
    suspend fun refresh(context: Context): AccountState? = withContext(Dispatchers.IO) {
        val body = withSession(context) ?: return@withContext null
        val st = absorb(context, ServerApi.post("/v1/license", body))
        // Comandos do painel (limpar ganhos, pedir registros...) rodam aqui, logo depois de conferir
        if (!st?.commands.isNullOrEmpty()) runCatching { CommandRunner.runPending(context, st) }
        state(context) ?: st
    }

    suspend fun redeemCoupon(context: Context, code: String): Result {
        val body = withSession(context) ?: return Result(false, null, "Faça login primeiro")
        return call(context, "/v1/coupon/redeem", body.put("code", code.trim()))
    }

    suspend fun verifyPurchase(context: Context, purchaseToken: String, productId: String): Result {
        val body = withSession(context) ?: return Result(false, null, "Faça login primeiro")
        return call(context, "/v1/billing/verify", body.put("purchaseToken", purchaseToken).put("productId", productId))
    }

    /** Transito pelo servidor (a chave da AWS fica so no servidor). Devolve minutos ou null. */
    fun trafficRoute(
        context: Context,
        driver: Pair<Double, Double>,
        pickup: Pair<Double, Double>,
        dropoff: Pair<Double, Double>,
        timeoutMs: Int,
        car: Boolean = false
    ): com.ruan.uberareafilter.engine.TrafficCheck.Route? {
        val session = AccountStore(context).session ?: return null
        fun arr(p: Pair<Double, Double>) = org.json.JSONArray().put(p.first).put(p.second)
        val r = ServerApi.post(
            "/v1/traffic",
            JSONObject().put("session", session).put("driver", arr(driver)).put("pickup", arr(pickup)).put("dropoff", arr(dropoff))
                .put("mode", if (car) "car" else "moto").put("cert", DeviceIdentity.signingCert(context)),
            timeoutMs
        )
        if (!r.ok) return null
        val j = r.json ?: return null
        val minutes = j.optDouble("minutes").takeIf { !it.isNaN() } ?: return null
        fun km(k: String) = if (j.isNull(k)) null else j.optDouble(k).takeIf { !it.isNaN() }?.div(1000.0)
        return com.ruan.uberareafilter.engine.TrafficCheck.Route(minutes, km("pickupMeters"), km("rideMeters"))
    }

    /** Avisa o servidor que o comando foi feito (sai da fila). */
    suspend fun commandDone(context: Context, id: String, result: String): Result {
        val body = withSession(context) ?: return Result(false, null, "Faça login primeiro")
        return call(context, "/v1/command/done", body.put("id", id).put("result", result))
    }

    /** Sessao de administrador para abrir o painel dentro do app (so conta e celular do dono). */
    suspend fun adminSession(context: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val body = withSession(context) ?: return@withContext null to "Faça login primeiro"
        val r = ServerApi.post("/v1/admin/session", body)
        if (r.ok) r.json?.optString("session") to null else null to r.message
    }

    fun logout(context: Context) = AccountStore(context).clear()
}
