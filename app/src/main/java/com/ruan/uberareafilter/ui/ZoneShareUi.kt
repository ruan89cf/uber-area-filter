package com.ruan.uberareafilter.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruan.uberareafilter.R
import com.ruan.uberareafilter.engine.ZoneShare
import com.ruan.uberareafilter.storage.ConfigRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enviar as suas zonas para outro motorista (WhatsApp, Telegram, e-mail...) e receber as dele.
 * O arquivo e um .json com as zonas desenhadas no mapa; quem recebe toca no arquivo no WhatsApp,
 * escolhe "Filtro Inteligente" e decide se JUNTA com as suas ou SUBSTITUI.
 */
object ZoneShareUi {

    private const val MAX_FILE_BYTES = 3_000_000

    /** Gera o arquivo e abre o "Compartilhar" do Android. */
    fun shareMyZones(activity: Activity, repository: ConfigRepository) {
        val zones = repository.load().mapZones
        if (zones.isEmpty()) {
            Toast.makeText(activity, "Você ainda não desenhou zonas no mapa para enviar.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val dir = File(activity.cacheDir, "compartilhar").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val file = File(dir, "zonas_filtro_$stamp.json")
            file.writeText(repository.exportZonesJson())
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.arquivos", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Minhas zonas do Filtro Inteligente (${zones.size}). Para usar: toque no arquivo e abra com o Filtro Inteligente."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Enviar minhas zonas"))
            ConfigRepository.appendLog(
                "[${now()}] [ZONAS] Enviei ${zones.size} zona(s) no arquivo ${file.name}",
                activity
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "Não consegui criar o arquivo das zonas: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Le o arquivo escolhido/aberto e pergunta o que fazer. */
    fun importFromUri(activity: Activity, repository: ConfigRepository, uri: Uri, onFinished: (Boolean) -> Unit) {
        val text = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_FILE_BYTES) break
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            notZones(activity, "Não consegui abrir esse arquivo.", onFinished)
            return
        }
        importFromText(activity, repository, text, onFinished)
    }

    fun importFromText(activity: Activity, repository: ConfigRepository, text: String, onFinished: (Boolean) -> Unit) {
        val zones = repository.parseZonesFile(text)
        if (zones == null) {
            notZones(activity, "Esse arquivo não tem zonas do Filtro Inteligente.", onFinished)
            return
        }
        val mine = repository.load().mapZones
        val list = ZoneShare.describe(zones)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Receber ${zones.size} zona(s)")
            .setIcon(UiPolish.icon(activity, R.drawable.ic_map, R.color.acc_blue_on))
            .setOnCancelListener { onFinished(false) }
        if (mine.isEmpty()) {
            builder
                .setMessage("$list\n\nVocê ainda não tem zonas: estas passam a ser as suas.")
                .setPositiveButton("Usar estas zonas") { _, _ -> apply(activity, repository, zones, replace = true, onFinished) }
                .setNegativeButton("Cancelar") { _, _ -> onFinished(false) }
        } else {
            builder
                .setMessage(
                    "$list\n\nVocê tem ${mine.size} zona(s) hoje.\n\n" +
                        "• Juntar: as suas continuam (a sua Zona 1 segue sendo a principal) e as recebidas entram junto.\n" +
                        "• Substituir: apaga as suas e fica só com as recebidas."
                )
                .setPositiveButton("Juntar") { _, _ -> apply(activity, repository, zones, replace = false, onFinished) }
                .setNeutralButton("Substituir") { _, _ ->
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Apagar as suas ${mine.size} zona(s)?")
                        .setMessage("As suas zonas serão trocadas pelas ${zones.size} recebidas. Não dá para desfazer.")
                        .setPositiveButton("Substituir") { _, _ -> apply(activity, repository, zones, replace = true, onFinished) }
                        .setNegativeButton("Voltar") { _, _ -> onFinished(false) }
                        .setOnCancelListener { onFinished(false) }
                        .show()
                }
                .setNegativeButton("Cancelar") { _, _ -> onFinished(false) }
        }
        builder.show()
    }

    private fun apply(
        activity: Activity,
        repository: ConfigRepository,
        zones: List<com.ruan.uberareafilter.model.MapZone>,
        replace: Boolean,
        onFinished: (Boolean) -> Unit
    ) {
        val result = repository.importZones(zones, replace)
        ConfigRepository.appendLog(
            "[${now()}] [ZONAS] Recebi ${zones.size} zona(s) (${if (replace) "substituir" else "juntar"}): " +
                "${result.added} entraram, ${result.skippedDuplicates} já existiam • agora são ${result.zones.size}",
            activity
        )
        val extra = if (result.skippedDuplicates > 0) " (${result.skippedDuplicates} já existiam)" else ""
        Toast.makeText(
            activity,
            "Zonas salvas: ${result.added} nova(s)$extra. O app vai conferir as ruas delas (precisa de internet).",
            Toast.LENGTH_LONG
        ).show()
        onFinished(true)
    }

    private fun notZones(activity: Activity, message: String, onFinished: (Boolean) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Arquivo não reconhecido")
            .setMessage("$message\n\nPeça para a pessoa enviar de novo pelo botão “Enviar minhas zonas”, na aba Zonas do app.")
            .setPositiveButton("Ok") { _, _ -> onFinished(false) }
            .setOnCancelListener { onFinished(false) }
            .show()
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
