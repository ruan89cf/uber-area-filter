package com.ruan.uberareafilter.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.MaterialColors
import com.ruan.uberareafilter.R
import com.ruan.uberareafilter.databinding.ActivityWhatsNewBinding
import com.ruan.uberareafilter.storage.ConfigRepository

/**
 * Novidades: abre sozinha uma vez a cada atualizacao (versionCode novo), mostra a lista do que mudou
 * e some ao tocar em Continuar. Pelo botao "Novidades" do painel mostra o historico inteiro.
 */
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
        val current = currentVersionCode(this)
        val lastSeen = repository.whatsNewSeenVersion()
        val releases = if (fromMain) Changelog.releases
        else Changelog.newerThan(lastSeen).ifEmpty { Changelog.releases.take(1) }

        val top = Changelog.releases.firstOrNull()
        binding.txtVersionPill.text = if (fromMain) "Histórico de atualizações"
        else "Atualizado para a versão ${top?.versionName ?: ""}"
        releases.forEachIndexed { i, r -> addRelease(r, first = i == 0) }

        if (fromMain) binding.btnProceedToMain.text = "Voltar"
        binding.btnProceedToMain.setOnClickListener {
            repository.markWhatsNewSeen(current)
            if (!fromMain && isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addRelease(r: Changelog.Release, first: Boolean) {
        val container = binding.releasesContainer
        val onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVar = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerLow, MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurface))
        val outline = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutlineVariant)
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)

        container.addView(TextView(this).apply {
            text = "Versão ${r.versionName}" + if (r.date.isNotBlank()) " · ${r.date}" else ""
            setTextColor(primary)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            letterSpacing = 0.06f
            isAccessibilityHeading = true
            setPadding(dp(4), dp(if (first) 22 else 30), 0, dp(10))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(surface)
                setStroke(dp(1), outline)
            }
        }
        r.items.forEachIndexed { idx, item ->
            if (idx > 0) card.addView(android.view.View(this).apply {
                setBackgroundColor(outline)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(64) }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val (iconRes, accent) = iconFor(item.emoji)
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(UiPolish.color(this@WhatsNewActivity, accent.on))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(UiPolish.color(this@WhatsNewActivity, accent.container))
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            }
            texts.addView(TextView(this).apply {
                text = item.title
                setTextColor(onSurface)
                textSize = 15.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            texts.addView(TextView(this).apply {
                text = item.body
                setTextColor(onSurfaceVar)
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(texts)
            card.addView(row)
        }
        container.addView(card)
    }

    /** Cada novidade ganha um icone de verdade com a cor do assunto (o Changelog guarda so a "etiqueta"). */
    private fun iconFor(tag: String): Pair<Int, HelpCatalog.Accent> = when (tag) {
        "💰" -> R.drawable.ic_wallet to HelpCatalog.Accent.AMBER
        "🧾" -> R.drawable.ic_money to HelpCatalog.Accent.ORANGE
        "✅" -> R.drawable.ic_check_circle to HelpCatalog.Accent.TEAL
        "🚗" -> R.drawable.ic_route to HelpCatalog.Accent.BLUE
        "⏯️" -> R.drawable.ic_pause_circle to HelpCatalog.Accent.CYAN
        "⏱️" -> R.drawable.ic_power to HelpCatalog.Accent.RED
        "🧩" -> R.drawable.ic_layers to HelpCatalog.Accent.SLATE
        "🗺️" -> R.drawable.ic_map to HelpCatalog.Accent.BLUE
        "🎨" -> R.drawable.ic_palette to HelpCatalog.Accent.ROSE
        "📏" -> R.drawable.ic_route to HelpCatalog.Accent.AMBER
        "🔁" -> R.drawable.ic_touch to HelpCatalog.Accent.CYAN
        "⛽" -> R.drawable.ic_fuel to HelpCatalog.Accent.ORANGE
        "🛠️" -> R.drawable.ic_settings to HelpCatalog.Accent.SLATE
        "📋" -> R.drawable.ic_code to HelpCatalog.Accent.SLATE
        "🧭" -> R.drawable.ic_place to HelpCatalog.Accent.VIOLET
        "📱" -> R.drawable.ic_phone to HelpCatalog.Accent.BLUE
        "🔒" -> R.drawable.ic_lock to HelpCatalog.Accent.VIOLET
        "📸" -> R.drawable.ic_camera to HelpCatalog.Accent.CYAN
        "❤️" -> R.drawable.ic_favorite to HelpCatalog.Accent.ROSE
        "🧲" -> R.drawable.ic_place to HelpCatalog.Accent.VIOLET
        "🏠" -> R.drawable.ic_home to HelpCatalog.Accent.TEAL
        "🟣" -> R.drawable.ic_swap to HelpCatalog.Accent.VIOLET
        "🃏" -> R.drawable.ic_layers to HelpCatalog.Accent.ROSE
        "❓" -> R.drawable.ic_help to HelpCatalog.Accent.TEAL
        "✨" -> R.drawable.ic_sparkle to HelpCatalog.Accent.VIOLET
        "🚦" -> R.drawable.ic_check_circle to HelpCatalog.Accent.TEAL
        "🔤" -> R.drawable.ic_chat to HelpCatalog.Accent.BLUE
        "🎬" -> R.drawable.ic_play_circle to HelpCatalog.Accent.CYAN
        else -> R.drawable.ic_info to HelpCatalog.Accent.TEAL
    }

    companion object {
        const val EXTRA_FROM_MAIN = "extra_from_main"

        fun currentVersionCode(context: Context): Int = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)

        /** Abre uma vez por atualizacao (versionCode maior que o ultimo visto). */
        fun shouldShowWhatsNew(context: Context): Boolean {
            val repo = ConfigRepository(context)
            return repo.whatsNewSeenVersion() < currentVersionCode(context)
        }
    }
}
