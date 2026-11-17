package com.ruan.uberareafilter.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ruan.uberareafilter.databinding.ActivityWhatsNewBinding
import com.ruan.uberareafilter.storage.ConfigRepository

class WhatsNewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhatsNewBinding
    private lateinit var repository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        repository = ConfigRepository(this)
        val darkTheme = repository.load().darkThemeEnabled
        val mode = if (darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityWhatsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fromMain = intent.getBooleanExtra(EXTRA_FROM_MAIN, false)
        if (fromMain) {
            binding.btnProceedToMain.text = "✓ Voltar ao Aplicativo"
        }

        binding.btnProceedToMain.setOnClickListener {
            repository.markWhatsNewSeen(CURRENT_WHATS_NEW_VERSION)
            if (fromMain) {
                finish()
            } else {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_FROM_MAIN = "extra_from_main"
        const val CURRENT_WHATS_NEW_VERSION = 3

        fun shouldShowWhatsNew(context: Context): Boolean {
            val repo = ConfigRepository(context)
            return !repo.isWhatsNewSeen(CURRENT_WHATS_NEW_VERSION)
        }
    }
}
