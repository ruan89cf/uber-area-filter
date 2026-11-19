package com.ruan.uberareafilter.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ruan.uberareafilter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualizacao pelo nosso servidor (enquanto o app nao esta na Play Store): baixa o APK que o dono
 * enviou no painel, confere a impressao digital (SHA-256) e abre o instalador do Android.
 */
object UpdateManager {

    private const val DIR = "atualizacao"
    private const val PREFS = "atualizacao"
    private const val REMIND_EVERY_MS = 12 * 3_600_000L

    /** Hora de lembrar da atualizacao opcional? (no maximo a cada 12 h, por versao). */
    fun shouldRemind(context: Context, st: AccountState, now: Long = System.currentTimeMillis()): Boolean {
        if (!st.hasUpdate || st.mustUpdate || st.latestVersionCode <= BuildConfig.VERSION_CODE) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("remind_${st.latestVersionCode}", 0L)
        return now - last > REMIND_EVERY_MS
    }

    fun markReminded(context: Context, st: AccountState, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("remind_${st.latestVersionCode}", now).apply()
    }

    /** O Android precisa da permissao "instalar apps desconhecidos" para este app. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        }
    }

    /**
     * Baixa o APK (mostra o progresso 0..100 em [onProgress]). Devolve o arquivo pronto ou uma mensagem de erro.
     * Pega um link novo no servidor antes (o link do APK vale poucas horas).
     */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): Pair<File?, String?> = withContext(Dispatchers.IO) {
        val st = AccountGate.refresh(context) ?: AccountGate.state(context)
        val url = st?.updateUrl.orEmpty()
        if (st == null || !st.updateIsApk || url.isBlank()) return@withContext null to "Nenhuma atualização para baixar agora"
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "filtro-${st.latestVersionCode}.apk")
        val err = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) return@runCatching "O servidor não entregou o arquivo (${conn.responseCode})"
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: st.updateSize
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { onProgress(pct) } }
                            }
                        }
                    }
                }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (st.updateSha256.isNotBlank() && !hash.equals(st.updateSha256, ignoreCase = true)) {
                    out.delete()
                    return@runCatching "O arquivo chegou corrompido. Tente de novo."
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrElse { "Falha ao baixar: confira a internet" }
        if (err != null) return@withContext null to err
        lastSavedLocation = saveCopyToDownloads(context, out, st.latestVersionName.ifBlank { st.latestVersionCode.toString() })
        out to null
    }

    /** Onde ficou a copia do instalador (ex.: "Download/FiltroInteligente-3.4.apk"); null = nao salvou. */
    @Volatile
    var lastSavedLocation: String? = null
        private set

    /**
     * Guarda uma copia do APK na pasta Download do celular, para a pessoa achar o arquivo depois
     * (Arquivos > Downloads) e instalar de novo se precisar.
     */
    private fun saveCopyToDownloads(context: Context, apk: File, versionName: String): String? = runCatching {
        val name = "FiltroInteligente-$versionName.apk"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Apaga copia antiga com o mesmo nome (o Android criaria "nome (1).apk")
            resolver.delete(collection, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(collection, values) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { o -> apk.inputStream().use { it.copyTo(o) } } ?: return@runCatching null
            "Download/$name"
        } else {
            null
        }
    }.getOrNull()

    /** Abre o instalador do Android com o APK baixado. */
    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.arquivos", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
