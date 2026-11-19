package com.ruan.uberareafilter.account

import android.content.Context
import com.ruan.uberareafilter.storage.ConfigRepository
import com.ruan.uberareafilter.storage.EarningsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Copia na conta (servidor): zonas do mapa, historico e ganhos, e os registros (logs).
 * Trocou de celular ou reinstalou? Ao entrar na conta, o que estava salvo volta sozinho.
 * Tudo vai comprimido direto para o armazenamento da AWS por um link temporario.
 */
object SyncManager {

    private const val CONFIG_PREFS = "uber_area_filter_prefs"
    private const val STATE_PREFS = "sincronizacao"

    /** O que forma "as zonas": areas do mapa, ruas conhecidas e lugares perigosos. */
    private val ZONE_KEYS = listOf("map_zones_json", "polygon_points", "zone_street_index_json", "trusted_streets", "blocked_places_json")
    /** Historico de corridas (alem do banco de ganhos). */
    private val HISTORY_KEYS = listOf("ride_history_json", "favorite_rides_json")

    private const val ZONES_MIN_GAP_MS = 10 * 60_000L
    private const val EARNINGS_MIN_GAP_MS = 3 * 3_600_000L
    private const val LOGS_MIN_GAP_MS = 2 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Manda o que mudou, sem travar quem chamou (tela fechando, servico a cada hora). */
    fun syncInBackground(context: Context) {
        val app = context.applicationContext
        if (!AccountGate.hasSession(app)) return
        scope.launch { runCatching { syncNow(app, force = false) } }
    }

    /** Envia zonas, ganhos e registros que mudaram. `force` ignora os intervalos minimos. */
    suspend fun syncNow(context: Context, force: Boolean): String = lock.withLock {
        val done = mutableListOf<String>()
        if (backupZones(context, force)) done += "zonas"
        if (backupEarnings(context, force)) done += "ganhos"
        if (uploadLogs(context, force)) done += "registros"
        if (done.isEmpty()) "Nada novo para salvar" else "Salvo na conta: " + done.joinToString(", ")
    }

    // ------------------------------------------------------------------ envio
    private fun state(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun zonesJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val o = JSONObject()
        for (k in ZONE_KEYS) prefs.getString(k, null)?.let { o.put(k, it) }
        return o
    }

    private fun hasZones(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val zones = prefs.getString("map_zones_json", null).orEmpty().trim()
        val poly = prefs.getString("polygon_points", null).orEmpty().trim()
        return (zones.isNotEmpty() && zones != "[]") || (poly.isNotEmpty() && poly != "[]")
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun backupZones(context: Context, force: Boolean): Boolean {
        if (!hasZones(context)) return false
        val st = state(context)
        val now = System.currentTimeMillis()
        val raw = zonesJson(context).toString().toByteArray(Charsets.UTF_8)
        val hash = sha(raw)
        if (!force && (hash == st.getString("zones_hash", "") || now - st.getLong("zones_at", 0L) < ZONES_MIN_GAP_MS)) return false
        val zones = runCatching { org.json.JSONArray(zonesJson(context).optString("map_zones_json", "[]")).length() }.getOrDefault(0)
        if (!upload(context, "zones", "zonas", gzip(raw), "$zones zonas")) return false
        st.edit().putString("zones_hash", hash).putLong("zones_at", now).apply()
        return true
    }

    private fun backupEarnings(context: Context, force: Boolean): Boolean {
        val st = state(context)
        val now = System.currentTimeMillis()
        val store = EarningsStore.get(context)
        val count = store.entryCount()
        val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val history = HISTORY_KEYS.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }
        if (count == 0L && history.all { it.second.length < 5 }) return false
        val dbFile = File(context.cacheDir, "copia-ganhos.db")
        store.exportTo(dbFile)
        val db = dbFile.readBytes()
        dbFile.delete()
        val payload = JSONObject()
            .put("db", android.util.Base64.encodeToString(db, android.util.Base64.NO_WRAP))
            .apply { history.forEach { (k, v) -> put(k, v) } }
            .toString().toByteArray(Charsets.UTF_8)
        val hash = sha(payload)
        if (!force && (hash == st.getString("earn_hash", "") || now - st.getLong("earn_at", 0L) < EARNINGS_MIN_GAP_MS)) return false
        if (!upload(context, "earnings", "ganhos", gzip(payload), "$count lançamentos")) return false
        st.edit().putString("earn_hash", hash).putLong("earn_at", now).apply()
        return true
    }

    /** Registro atual (a cada 2 h, se mudou) e o arquivo anterior quando o registro vira (uma vez). */
    private fun uploadLogs(context: Context, force: Boolean): Boolean {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val st = state(context)
        val now = System.currentTimeMillis()
        var any = false
        val prev = File(dir, "filtro_inteligente_logs.1.txt")
        if (prev.exists()) {
            val sig = "${prev.length()}:${prev.lastModified()}"
            if (sig != st.getString("log_prev_sig", "") && upload(context, "log", "anterior", gzip(prev.readBytes()), "")) {
                st.edit().putString("log_prev_sig", sig).apply()
                any = true
            }
        }
        val cur = File(dir, "filtro_inteligente_logs.txt")
        if (cur.exists()) {
            val sig = "${cur.length()}:${cur.lastModified()}"
            val due = force || now - st.getLong("log_cur_at", 0L) >= LOGS_MIN_GAP_MS
            if (due && sig != st.getString("log_cur_sig", "") && upload(context, "log", "atual", gzip(cur.readBytes()), "")) {
                st.edit().putString("log_cur_sig", sig).putLong("log_cur_at", now).apply()
                any = true
            }
        }
        return any
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /** Pede o link ao servidor, envia o arquivo e avisa que chegou. */
    private fun upload(context: Context, kind: String, name: String, data: ByteArray, meta: String): Boolean {
        val session = AccountGate.session(context) ?: return false
        val ask = ServerApi.post("/v1/sync/upload", AccountGate.baseBody(context).put("session", session).put("kind", kind).put("name", name))
        val url = ask.json?.optString("url").orEmpty()
        val key = ask.json?.optString("key").orEmpty()
        if (!ask.ok || url.isBlank()) return false
        val sent = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setFixedLengthStreamingMode(data.size)
            }
            try {
                conn.outputStream.use { it.write(data) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
        if (!sent) return false
        val done = ServerApi.post(
            "/v1/sync/done",
            AccountGate.baseBody(context).put("session", session).put("kind", kind).put("key", key).put("name", name).put("meta", meta)
        )
        return done.ok
    }

    // ------------------------------------------------------------------ volta da conta
    /**
     * Celular novo / app reinstalado: traz da conta as zonas e os ganhos, SO se aqui estiver vazio
     * (nunca apaga o que ja existe no celular). Devolve o que voltou, para mostrar na tela.
     */
    suspend fun restoreIfEmpty(context: Context): String? = lock.withLock {
        val session = AccountGate.session(context) ?: return null
        val info = ServerApi.post("/v1/sync/info", AccountGate.baseBody(context).put("session", session))
        val backups = info.json?.optJSONObject("backups") ?: return null
        val back = mutableListOf<String>()
        backups.optJSONObject("zones")?.optString("url")?.takeIf { it.isNotBlank() && !hasZones(context) }?.let { url ->
            val json = download(url)?.let { runCatching { JSONObject(String(gunzip(it), Charsets.UTF_8)) }.getOrNull() }
            if (json != null) {
                val edit = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE).edit()
                for (k in ZONE_KEYS) if (json.has(k)) edit.putString(k, json.getString(k))
                if (edit.commit()) back += "zonas do mapa"
                state(context).edit().putString("zones_hash", sha(zonesJson(context).toString().toByteArray(Charsets.UTF_8))).apply()
            }
        }
        val store = EarningsStore.get(context)
        backups.optJSONObject("earnings")?.optString("url")?.takeIf { it.isNotBlank() && store.entryCount() == 0L }?.let { url ->
            val json = download(url)?.let { runCatching { JSONObject(String(gunzip(it), Charsets.UTF_8)) }.getOrNull() }
            if (json != null) {
                val tmp = File(context.cacheDir, "volta-ganhos.db")
                runCatching {
                    tmp.writeBytes(android.util.Base64.decode(json.getString("db"), android.util.Base64.NO_WRAP))
                    val n = store.importFrom(tmp)
                    if (n > 0) back += "$n lançamentos de ganhos"
                }
                tmp.delete()
                val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
                val edit = prefs.edit()
                var hist = false
                for (k in HISTORY_KEYS) {
                    val local = prefs.getString(k, null).orEmpty()
                    if (json.has(k) && (local.isBlank() || local == "[]")) { edit.putString(k, json.getString(k)); hist = true }
                }
                if (hist && edit.commit()) back += "histórico de corridas"
            }
        }
        if (back.isEmpty()) null else {
            ConfigRepository.appendLog("[CONTA] Recuperado da conta: ${back.joinToString(", ")}", context)
            "Recuperado da sua conta: " + back.joinToString(", ")
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
