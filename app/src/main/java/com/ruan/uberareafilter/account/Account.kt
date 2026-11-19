package com.ruan.uberareafilter.account

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Conta do motorista: login Google obrigatorio, licenca assinada pelo servidor (vale poucas horas e
 * so neste celular), teste de 7 dias, cupons e atualizacao obrigatoria. Sem licenca valida o app
 * e o leitor de tela nao funcionam.
 */
object ServerConfig {
    const val API = "https://d07z412uu9.execute-api.sa-east-1.amazonaws.com"

    /** Chave PUBLICA do servidor: so confere licencas (nao cria). */
    const val LICENSE_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5MyomzLN3yzzmYHvK8PzTNW4+m59lNmMx9dEBhwXnF8dViUOUoG68bHOSAHYcj3mfymyyyxyA/VZjnFBTiZwHQ=="

    /** ID do cliente Web do Google (Google Cloud > Credenciais). Vazio = login Google ainda nao configurado. */
    val GOOGLE_WEB_CLIENT_ID: String get() = com.ruan.uberareafilter.BuildConfig.GOOGLE_WEB_CLIENT_ID
}

/** Identidade do celular: ANDROID_ID (muda so com reset de fabrica) passado por hash. */
object DeviceIdentity {
    @SuppressLint("HardwareIds")
    fun id(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val h = MessageDigest.getInstance("SHA-256").digest("filtro:$raw".toByteArray())
        return "d" + h.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)

    /** SHA-256 do certificado que assinou o APK (APK modificado = outro certificado). */
    fun signingCert(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching ""
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

/** Licenca recebida do servidor (texto JSON + assinatura ECDSA). */
data class License(
    val sub: String,
    val did: String,
    val status: String,
    val plan: String?,
    val until: Long?,
    val exp: Long
)

object LicenseVerifier {
    private val publicKey by lazy {
        val der = Base64.decode(ServerConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** null = assinatura falsa, vencida, de outro celular ou corrompida. */
    fun verify(token: String?, deviceId: String, now: Long = System.currentTimeMillis()): License? {
        if (token.isNullOrBlank() || !token.contains('.')) return null
        val (body, sig) = token.split('.', limit = 2)
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(body.toByteArray(Charsets.UTF_8))
                verify(Base64.decode(sig, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            }
        }.getOrDefault(false)
        if (!ok) return null
        val json = runCatching {
            JSONObject(String(Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (json.optString("typ") != "lic") return null
        val exp = json.optLong("exp", 0L)
        if (exp < now) return null
        if (json.optString("did") != deviceId) return null
        val st = json.optString("st")
        if (st != "trial" && st != "active") return null
        return License(
            sub = json.optString("sub"),
            did = json.optString("did"),
            status = st,
            plan = json.optString("plan").ifBlank { null },
            until = if (json.isNull("until")) null else json.optLong("until"),
            exp = exp
        )
    }
}

/** O que o servidor respondeu sobre a conta (guardado para mostrar no painel do plano). */
data class AccountState(
    val email: String,
    val status: String,          // trial | active | expired | blocked | none
    val plan: String?,
    val until: Long?,
    val mustUpdate: Boolean,
    val hasUpdate: Boolean,
    val updateUrl: String,
    val updateMessage: String,
    val message: String?         // erro/aviso para mostrar ("Conta bloqueada", "outro celular"...)
)

class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("conta", Context.MODE_PRIVATE)

    var session: String?
        get() = prefs.getString("session", null)
        set(v) { prefs.edit().putString("session", v).apply() }
    var license: String?
        get() = prefs.getString("license", null)
        set(v) { prefs.edit().putString("license", v).apply() }
    var lastRefreshAt: Long
        get() = prefs.getLong("lastRefreshAt", 0L)
        set(v) { prefs.edit().putLong("lastRefreshAt", v).apply() }
    var stateJson: String?
        get() = prefs.getString("state", null)
        set(v) { prefs.edit().putString("state", v).apply() }

    fun clear() = prefs.edit().clear().apply()
}

/** Chamadas HTTPS ao servidor. Erros viram ApiResult com codigo (nunca derruba o app). */
object ServerApi {
    data class ApiResult(val ok: Boolean, val httpCode: Int, val json: JSONObject?) {
        val code: String get() = json?.optString("code").orEmpty()
        val message: String get() = json?.optString("message").orEmpty().ifBlank { "Sem conexão com o servidor" }
    }

    fun post(path: String, body: JSONObject, timeoutMs: Int = 8000): ApiResult = runCatching {
        val conn = (URL(ServerConfig.API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            ApiResult(code in 200..299 && json?.optBoolean("ok") == true, code, json)
        } finally {
            conn.disconnect()
        }
    }.getOrElse { ApiResult(false, 0, null) }
}
