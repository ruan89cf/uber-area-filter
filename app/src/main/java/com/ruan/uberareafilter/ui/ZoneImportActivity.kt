package com.ruan.uberareafilter.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruan.uberareafilter.storage.ConfigRepository

/**
 * Abre quando a pessoa toca no arquivo de zonas no WhatsApp (ou usa "Compartilhar" com o app):
 * mostra as zonas recebidas, pergunta se junta ou substitui e depois abre o app na tela principal.
 */
class ZoneImportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val repository = ConfigRepository(this)
        val done: (Boolean) -> Unit = { imported ->
            if (imported) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            finish()
        }
        val uri = incomingUri(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uri != null -> ZoneShareUi.importFromUri(this, repository, uri, done)
            !text.isNullOrBlank() -> ZoneShareUi.importFromText(this, repository, text, done)
            else -> finish()
        }
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> null
    }
}
