package com.ruan.uberareafilter.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ruan.uberareafilter.storage.ConfigRepository
import com.ruan.uberareafilter.storage.EarningsStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Painel de Ganhos: lucro bruto/liquido, gastos, horas trabalhadas, semanas (seg-dom) e balanco do mes.
 * A tela e uma pagina local (assets/earnings.html) que conversa com o banco pelo objeto "Ledger".
 */
class EarningsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var store: EarningsStore
    private lateinit var repo: ConfigRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EarningsStore.get(this)
        repo = ConfigRepository(this)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Ledger")
        val dark = repo.load().darkThemeEnabled
        web.loadUrl("file:///android_asset/earnings.html?theme=${if (dark) "dark" else "light"}")
        log("Painel de ganhos aberto")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // A pagina fecha o formulario aberto primeiro; se nao houver, sai da tela
                web.evaluateJavascript("window.handleBack ? handleBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.reload && reload()", null)
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ConfigRepository.appendLog("[$time] [PAINEL] $msg", applicationContext)
    }

    inner class Bridge {
        @JavascriptInterface
        fun getData(): String {
            val now = System.currentTimeMillis()
            val from = now - 430L * 86_400_000L
            val data = store.toJson(from, EarningsStore.dayKey(from))
            val cfg = repo.load()
            data.put("settings", JSONObject(prefs().getString(KEY_SETTINGS, "{}") ?: "{}"))
            data.put("fuel", JSONObject().apply {
                put("enabled", cfg.fuelCostEnabled)
                put("price", cfg.fuelPricePerUnit)
                put("kmPerUnit", cfg.kmPerUnit)
                put("type", cfg.fuelType.toString())
            })
            data.put("minHourly", cfg.minHourlyRate)
            data.put("now", now)
            return data.toString()
        }

        @JavascriptInterface
        fun saveEntry(json: String): String = runCatching {
            val o = JSONObject(json)
            val e = EarningsStore.Entry(
                id = o.optLong("id", 0L),
                ts = o.optLong("ts", System.currentTimeMillis()),
                kind = o.optString("kind", EarningsStore.KIND_EXPENSE),
                app = o.optString("app", ""),
                amount = o.optDouble("amount", 0.0),
                category = o.optString("category", ""),
                note = o.optString("note", ""),
                km = o.optDouble("km", 0.0),
                pickupKm = o.optDouble("pickupKm", 0.0),
                minutes = o.optDouble("minutes", 0.0),
                fuelCost = o.optDouble("fuelCost", 0.0),
                status = o.optString("status", EarningsStore.STATUS_DONE),
                source = o.optString("source", EarningsStore.SOURCE_USER),
                recurring = o.optBoolean("recurring", false),
                pickup = o.optString("pickup", ""),
                dropoff = o.optString("dropoff", "")
            )
            val id = if (e.id > 0L) {
                store.update(e); e.id
            } else {
                store.insert(e)
            }
            log("Lançamento ${if (e.id > 0L) "editado" else "novo"} #$id ${e.kind} ${e.category} R$ ${"%.2f".format(e.amount)} ${if (e.recurring) "(mensal)" else ""} ${e.note}".trim())
            id.toString()
        }.getOrElse {
            log("⚠ Falha ao salvar lançamento: ${it.message}")
            "0"
        }

        @JavascriptInterface
        fun deleteEntry(id: String) {
            val e = id.toLongOrNull()?.let { store.get(it) }
            store.delete(id.toLongOrNull() ?: return)
            log("Lançamento #$id apagado (${e?.kind} ${e?.category} R$ ${e?.amount})")
        }

        @JavascriptInterface
        fun setStatus(id: String, status: String) {
            store.setStatus(id.toLongOrNull() ?: return, status)
            log("Corrida #$id marcada como $status pelo motorista")
        }

        @JavascriptInterface
        fun setDayMinutes(day: String, minutes: Int) {
            store.setManualMinutes(day, minutes)
            log("Horas do dia $day ${if (minutes < 0) "voltaram para o medido" else "ajustadas para ${minutes / 60}h${minutes % 60}"}")
        }

        @JavascriptInterface
        fun saveSettings(json: String) {
            prefs().edit().putString(KEY_SETTINGS, json).apply()
            log("Ajustes do painel: $json")
        }

        @JavascriptInterface
        fun share(text: String) {
            runOnUiThread {
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Compartilhar"
                        )
                    )
                }
            }
            log("Relatório compartilhado (${text.length} caracteres)")
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@EarningsActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun log(msg: String) = this@EarningsActivity.log(msg)
    }

    private fun prefs() = getSharedPreferences("earnings_panel", MODE_PRIVATE)

    companion object {
        private const val KEY_SETTINGS = "settings_json"
    }
}
