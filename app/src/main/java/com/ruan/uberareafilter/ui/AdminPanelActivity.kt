package com.ruan.uberareafilter.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ruan.uberareafilter.account.AccountGate
import com.ruan.uberareafilter.account.ServerConfig
import kotlinx.coroutines.launch

/**
 * Painel do dono dentro do app (o mesmo do site), so para a conta e o celular do dono:
 * o servidor confere os dois antes de liberar. Cupons, usuarios, atualizacoes, avisos...
 */
class AdminPanelActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AccountUi(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(ui.pageBg)
            webViewClient = object : WebViewClient() {
                // Links de fora (baixar APK, registros) abrem no navegador; o painel fica aqui
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == Uri.parse(ServerConfig.API).host && url.path?.startsWith("/admin") == true) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
            }
        }
        val spinner = ProgressBar(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ui.pageBg)
            addView(web)
            addView(spinner, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), android.view.Gravity.CENTER))
        })
        lifecycleScope.launch {
            val (session, err) = AccountGate.adminSession(this@AdminPanelActivity)
            spinner.visibility = View.GONE
            if (session == null) {
                Toast.makeText(this@AdminPanelActivity, err ?: "Painel indisponível", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            // A sessao vai no "#": o navegador nao manda essa parte pela internet, so o painel le
            web.loadUrl("${ServerConfig.API}/admin#s=${Uri.encode(session)}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Voltar fecha a ficha aberta no painel; se nao tiver, sai do painel
        web.evaluateJavascript("(function(){var s=document.getElementById('sheet');if(s&&s.classList.contains('on')){s.classList.remove('on');return 1}return 0})()") { r ->
            if (r != "1") finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
