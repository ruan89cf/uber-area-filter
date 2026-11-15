package com.ruan.uberareafilter.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.storage.ConfigRepository
import kotlin.math.abs

/**
 * Gerencia:
 * 1) Card moderno de notificacao no topo (Verde com R$/km + R$/h + Nota ou Vermelho de recusa)
 * 2) Botao flutuante discreto e arrastavel (🟢 ON / ⏸️ PAUSA) sobre a 99
 * 3) Botao flutuante de Coracao (❤️ Favoritar Passageiro/Horario) na tela de avaliacao
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Card de notificacao no topo
    private var bannerContainer: LinearLayout? = null
    private var bannerTitleView: TextView? = null
    private var bannerMetricsView: TextView? = null
    private var bannerSubView: TextView? = null
    private val hideBannerRunnable = Runnable { hideBanner() }

    // Botao flutuante discreto (🟢/⏸️)
    private var floatingPillView: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    @Volatile
    private var ignorePillTouchesUntilMs: Long = 0L

    fun markAutomatedTapInProgress(blockMs: Long = 950L) {
        ignorePillTouchesUntilMs = System.currentTimeMillis() + blockMs
    }

    // Botao de Coracao (❤️ Favoritar na janela de avaliacao)
    private var heartBannerView: LinearLayout? = null
    private var heartTextView: TextView? = null
    private val hideHeartRunnable = Runnable { hideHeartPrompt() }

    fun showRideStatus(
        decision: Decision,
        headline: String,
        metricsLine: String? = null,
        detailLine: String,
        autoHideMs: Long = 5000L
    ) {
        if (!Settings.canDrawOverlays(context)) return

        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            val container = bannerContainer ?: createModernBannerView().also {
                bannerContainer = it
                try {
                    windowManager.addView(it, createBannerLayoutParams())
                } catch (_: Exception) {
                    bannerContainer = null
                    return@post
                }
            }

            val cfg = runCatching { ConfigRepository(context).load() }.getOrNull()
            val scale = cfg?.bannerScale ?: 1
            val scaleFactor = when (scale) {
                0 -> 0.85f // Compacto
                2 -> 1.22f // Grande
                else -> 1.0f // Normal
            }

            bannerTitleView?.textSize = 13f * scaleFactor
            bannerMetricsView?.textSize = 16f * scaleFactor
            bannerSubView?.textSize = 12.5f * scaleFactor
            val padH = dp((16 * scaleFactor).toInt())
            val padV = dp((10 * scaleFactor).toInt())
            container.setPadding(padH, padV, padH, padV)

            val bg = container.background as? GradientDrawable
            when (decision) {
                Decision.INSIDE_AREA -> {
                    val customBg = parseSafeColor(cfg?.colorInsideHex, "#F20A2E1D")
                    bg?.setColor(customBg)
                    bg?.setStroke(dp(2), Color.parseColor("#00E676"))
                    bannerTitleView?.setTextColor(Color.parseColor("#69F0AE"))
                }

                Decision.INTER_ZONE -> {
                    val customBg = parseSafeColor(cfg?.colorInterZoneHex, "#F2271252")
                    bg?.setColor(customBg)
                    bg?.setStroke(dp(2), Color.parseColor("#B388FF"))
                    bannerTitleView?.setTextColor(Color.parseColor("#E040FB"))
                }

                Decision.RETURN_TO_AREA -> {
                    val customBg = parseSafeColor(cfg?.colorYellowReturnHex, "#F23B2806")
                    bg?.setColor(customBg)
                    bg?.setStroke(dp(2), Color.parseColor("#FFD600"))
                    bannerTitleView?.setTextColor(Color.parseColor("#FFEA00"))
                }

                Decision.OUTSIDE_AREA -> {
                    val customBg = parseSafeColor(cfg?.colorOutsideHex, "#F2330A0A")
                    bg?.setColor(customBg)
                    bg?.setStroke(dp(2), Color.parseColor("#FF5252"))
                    bannerTitleView?.setTextColor(Color.parseColor("#FF8A80"))
                }

                Decision.UNKNOWN_KEEP -> {
                    bg?.setColor(Color.parseColor("#F21E293B"))
                    bg?.setStroke(dp(2), Color.parseColor("#FFD54F"))
                    bannerTitleView?.setTextColor(Color.parseColor("#FFE082"))
                }
            }

            bannerTitleView?.text = headline
            if (!metricsLine.isNullOrBlank()) {
                bannerMetricsView?.visibility = View.VISIBLE
                bannerMetricsView?.text = metricsLine
            } else {
                bannerMetricsView?.visibility = View.GONE
            }
            bannerSubView?.text = detailLine

            mainHandler.postDelayed(hideBannerRunnable, autoHideMs)
        }
    }

    fun showStatus(decision: Decision, message: String, autoHideMs: Long = 4500L) {
        val headline = when (decision) {
            Decision.INSIDE_AREA -> "✅ DENTRO DA ÁREA • VOCÊ DECIDE"
            Decision.INTER_ZONE -> "🟣 CORRIDA ENTRE ZONAS • VOCÊ DECIDE"
            Decision.RETURN_TO_AREA -> "🟡 FORA DA ÁREA (EMB ≤ 1KM) • VOCÊ DECIDE"
            Decision.OUTSIDE_AREA -> "❌ FORA DOS CRITÉRIOS"
            Decision.UNKNOWN_KEEP -> "ℹ️ AVISO DO FILTRO"
        }
        showRideStatus(decision, headline, null, message, autoHideMs)
    }

    fun hideBanner() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            bannerContainer?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            bannerContainer = null
            bannerTitleView = null
            bannerMetricsView = null
            bannerSubView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun updateFloatingControl(
        visible: Boolean,
        isFilterActive: Boolean,
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit
    ) {
        if (!Settings.canDrawOverlays(context) || !visible) {
            hideFloatingControl()
            return
        }

        mainHandler.post {
            val pill = floatingPillView ?: createFloatingPill(onToggleFilter, onLongPressFavorite).also {
                floatingPillView = it
                val params = createFloatingPillParams()
                floatingParams = params
                try {
                    windowManager.addView(it, params)
                } catch (_: Exception) {
                    floatingPillView = null
                    return@post
                }
            }

            val bg = pill.background as? GradientDrawable
            if (isFilterActive) {
                pill.text = "🟢 ON"
                bg?.setColor(Color.parseColor("#DD1B5E20"))
                bg?.setStroke(dp(2), Color.parseColor("#69F0AE"))
            } else {
                pill.text = "⏸️ PAUSA"
                bg?.setColor(Color.parseColor("#DD424242"))
                bg?.setStroke(dp(2), Color.parseColor("#BDBDBD"))
            }
        }
    }

    fun hideFloatingControl() {
        mainHandler.post {
            floatingPillView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            floatingPillView = null
            floatingParams = null
        }
    }

    fun showHeartFavoritePrompt(
        rideSummary: String,
        passengerName: String? = null,
        onSaveFavorite: () -> Unit,
        autoHideMs: Long = 12000L
    ) {
        if (!Settings.canDrawOverlays(context)) return

        mainHandler.post {
            mainHandler.removeCallbacks(hideHeartRunnable)
            if (heartBannerView == null) {
                val created = createHeartPromptView(onSaveFavorite)
                heartBannerView = created
                try {
                    windowManager.addView(created, createHeartLayoutParams())
                } catch (_: Exception) {
                    heartBannerView = null
                    return@post
                }
            }

            val title = if (!passengerName.isNullOrBlank()) {
                "❤️ Favoritar $passengerName (Toque p/ salvar)"
            } else {
                "❤️ Favoritar Passageiro/Horário (Toque p/ salvar)"
            }
            heartTextView?.text = "$title\n$rideSummary"
            mainHandler.postDelayed(hideHeartRunnable, autoHideMs)
        }
    }

    fun hideHeartPrompt() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideHeartRunnable)
            heartBannerView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            heartBannerView = null
            heartTextView = null
        }
    }

    fun hideAll() {
        hideBanner()
        hideFloatingControl()
        hideHeartPrompt()
    }

    private fun createModernBannerView(): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#EE1E293B"))
            setStroke(dp(2), Color.parseColor("#00E676"))
        }

        val title = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#69F0AE"))
        }

        val metrics = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(2))
        }

        val sub = TextView(context).apply {
            textSize = 12.5f
            setLineSpacing(dp(2).toFloat(), 1.05f)
            setTextColor(Color.parseColor("#F1F5F9"))
        }

        bannerTitleView = title
        bannerMetricsView = metrics
        bannerSubView = sub

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = bg
            elevation = dp(10).toFloat()
            addView(title)
            addView(metrics)
            addView(sub)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingPill(
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit
    ): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#DD1B5E20"))
            setStroke(dp(2), Color.parseColor("#69F0AE"))
        }

        return TextView(context).apply {
            text = "🟢 ON"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = bg
            elevation = dp(8).toFloat()

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longPressTriggered = false
            val longPressRunnable = Runnable {
                if (!moved) {
                    longPressTriggered = true
                    onLongPressFavorite()
                }
            }

            setOnTouchListener { _, event ->
                val params = floatingParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        longPressTriggered = false
                        mainHandler.postDelayed(longPressRunnable, 650L)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                            moved = true
                            mainHandler.removeCallbacks(longPressRunnable)
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(this, params)
                            } catch (_: Exception) {}
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (!moved &&
                            !longPressTriggered &&
                            event.action == MotionEvent.ACTION_UP &&
                            System.currentTimeMillis() >= ignorePillTouchesUntilMs
                        ) {
                            onToggleFilter()
                        }
                        true
                    }

                    else -> false
                }
            }
        }
    }

    private fun createHeartPromptView(onSaveFavorite: () -> Unit): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#EE880E4F"))
            setStroke(dp(2), Color.parseColor("#FF80AB"))
        }

        val txt = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        heartTextView = txt

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = bg
            elevation = dp(10).toFloat()
            addView(txt)
            setOnClickListener {
                onSaveFavorite()
                txt.text = "💖 Salvo nos Favoritos!"
                mainHandler.removeCallbacks(hideHeartRunnable)
                mainHandler.postDelayed(hideHeartRunnable, 1800L)
            }
        }
    }

    private fun createBannerLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(78)
        }
    }

    private fun createFloatingPillParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(240)
        }
    }

    private fun createHeartLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(330)
        }
    }

    private fun parseSafeColor(rawHex: String?, fallbackHex: String): Int {
        if (!rawHex.isNullOrBlank()) {
            val clean = rawHex.trim().removePrefix("#")
            val withAlpha = when (clean.length) {
                6 -> "#F2$clean"
                8 -> "#$clean"
                else -> rawHex.trim()
            }
            try {
                return Color.parseColor(withAlpha)
            } catch (_: Exception) {}
        }
        return Color.parseColor(fallbackHex)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
