package com.ruan.uberareafilter.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
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

    // Card de notificacao profissional no topo
    private var bannerContainer: LinearLayout? = null
    private var cardAccentBar: View? = null
    private var cardAppChip: TextView? = null
    private var cardTitle: TextView? = null
    private var cardStatusPill: TextView? = null
    private var cardPriceRow: LinearLayout? = null
    private var cardPrice: TextView? = null
    private var cardHourly: TextView? = null
    private var cardMetrics: TextView? = null
    private var cardFuelRow: LinearLayout? = null
    private var cardFuel: TextView? = null
    private var cardProfit: TextView? = null
    private var cardDivider: View? = null
    private var cardChecks: TextView? = null
    private var cardDetails: TextView? = null
    private var cardBody: LinearLayout? = null
    private var cardProgress: View? = null
    private var cardProgressAnimator: ObjectAnimator? = null
    private val hideBannerRunnable = Runnable { hideBanner() }
    @Volatile
    private var bannerShowing: Boolean = false

    fun isBannerVisible(): Boolean = bannerShowing && bannerContainer != null

    // Botao flutuante discreto (🟢/⏸️) e chip opcional de trava em corrida (modo simultaneo)
    private var floatingPillView: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var rideLockChipView: TextView? = null
    private var rideLockParams: WindowManager.LayoutParams? = null
    private var currentRideLockReleaseCallback: (() -> Unit)? = null
    @Volatile
    private var ignorePillTouchesUntilMs: Long = 0L

    fun markAutomatedTapInProgress(blockMs: Long = 950L) {
        ignorePillTouchesUntilMs = System.currentTimeMillis() + blockMs
    }

    private fun canRenderOverlay(): Boolean =
        context is AccessibilityService || Settings.canDrawOverlays(context)

    private fun overlayWindowType(): Int =
        if (context is AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    fun suppressOverlaysForTap(durationMs: Long = 260L) {
        val hideAction = {
            bannerContainer?.visibility = View.GONE
            floatingPillView?.visibility = View.GONE
            rideLockChipView?.visibility = View.GONE
            mainHandler.postDelayed({
                bannerContainer?.visibility = View.VISIBLE
                floatingPillView?.visibility = View.VISIBLE
                rideLockChipView?.visibility = View.VISIBLE
            }, durationMs)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideAction()
        } else {
            mainHandler.post { hideAction() }
        }
    }

    // Botao de Coracao (❤️ Favoritar na janela de avaliacao)
    private var heartBannerView: LinearLayout? = null
    private var heartTextView: TextView? = null
    private val hideHeartRunnable = Runnable { hideHeartPrompt() }

    /**
     * Compatibilidade: avisos simples (ex: alerta de Favorito) usando o novo card profissional.
     */
    fun showRideStatus(
        decision: Decision,
        headline: String,
        metricsLine: String? = null,
        detailLine: String,
        autoHideMs: Long = 5000L
    ) {
        showRideCard(
            RideCardModel(
                decision = decision,
                title = headline,
                secondaryMetrics = listOfNotNull(metricsLine?.takeIf { it.isNotBlank() }),
                checklist = detailLine,
                autoHideMs = autoHideMs
            )
        )
    }

    fun showRideCard(model: RideCardModel) {
        if (!canRenderOverlay()) return

        bannerShowing = true
        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            var isNew = false
            val container = bannerContainer ?: createProfessionalCardView().also {
                bannerContainer = it
                isNew = true
                try {
                    windowManager.addView(it, createBannerLayoutParams())
                } catch (_: Exception) {
                    bannerContainer = null
                    bannerShowing = false
                    return@post
                }
            }
            container.visibility = View.VISIBLE
            bindRideCardViews(container, model)

            // ---- Animacao de entrada + barra de tempo restante ----
            if (isNew) {
                container.alpha = 0f
                container.translationY = -dp(14).toFloat()
                container.animate().alpha(1f).translationY(0f).setDuration(190L).start()
            }
            cardProgressAnimator?.cancel()
            cardProgress?.let { bar ->
                bar.pivotX = 0f
                bar.scaleX = 1f
                cardProgressAnimator = ObjectAnimator.ofFloat(bar, View.SCALE_X, 1f, 0f).apply {
                    duration = model.autoHideMs
                    interpolator = LinearInterpolator()
                    start()
                }
            }

            mainHandler.postDelayed(hideBannerRunnable, model.autoHideMs)
        }
    }

    /**
     * Atualiza os dados de texto/metricas de um card ja visivel SEM resetar o temporizador
     * de fechamento nem reiniciar a barra de progresso ou piscar a animacao.
     */
    fun updateRideCardContent(model: RideCardModel) {
        if (!canRenderOverlay() || !bannerShowing) return

        mainHandler.post {
            val container = bannerContainer ?: return@post
            if (!bannerShowing || container.visibility != View.VISIBLE) return@post
            bindRideCardViews(container, model)
        }
    }

    private fun bindRideCardViews(container: LinearLayout, model: RideCardModel) {
        val cfg = runCatching { ConfigRepository(context).load() }.getOrNull()
        val sf = when (cfg?.bannerScale ?: 1) {
            0 -> 0.86f // Compacto
            2 -> 1.2f  // Grande
            else -> 1.0f
        }

        // ---- Paleta por decisao ----
        val accent = accentColorFor(model.decision)
        val bgBase = when (model.decision) {
            Decision.INSIDE_AREA -> parseSafeColor(cfg?.colorInsideHex, "#F20A2E1D")
            Decision.INTER_ZONE -> parseSafeColor(cfg?.colorInterZoneHex, "#F2271252")
            Decision.RETURN_TO_AREA -> parseSafeColor(cfg?.colorYellowReturnHex, "#F23B2806")
            Decision.OUTSIDE_AREA -> parseSafeColor(cfg?.colorOutsideHex, "#F2330A0A")
            Decision.UNKNOWN_KEEP -> Color.parseColor("#F21E293B")
        }
        (container.background as? GradientDrawable)?.apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(bgBase, blend(bgBase, Color.BLACK, 0.38f))
            setStroke(dp(1), withAlpha(accent, 0x88))
        }
        cardAccentBar?.setBackgroundColor(accent)
        cardProgress?.setBackgroundColor(withAlpha(accent, 0xCC))

        // ---- Cabecalho: chip do app + titulo + pilula de status ----
        val app = model.appSource?.uppercase()
        cardAppChip?.apply {
            if (app.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = if (app == "UBER") "Uber" else app
                textSize = 10.5f * sf
                val chipBg = background as? GradientDrawable
                if (app == "UBER") {
                    chipBg?.setColor(Color.BLACK)
                    chipBg?.setStroke(dp(1), Color.parseColor("#E5E7EB"))
                    setTextColor(Color.WHITE)
                } else {
                    chipBg?.setColor(Color.parseColor("#FFD200"))
                    chipBg?.setStroke(0, Color.TRANSPARENT)
                    setTextColor(Color.parseColor("#111111"))
                }
            }
        }
        cardTitle?.apply {
            text = model.title
            textSize = 13.5f * sf
        }
        cardStatusPill?.apply {
            if (model.statusLabel.isBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = "●  ${model.statusLabel.uppercase()}"
                textSize = 10f * sf
                setTextColor(lighten(accent, 0.35f))
                (background as? GradientDrawable)?.apply {
                    setColor(withAlpha(accent, 0x33))
                    setStroke(dp(1), withAlpha(accent, 0x99))
                }
            }
        }

        // ---- Valor principal + R$/h em destaque ----
        val hasPrice = !model.priceText.isNullOrBlank() || !model.hourlyText.isNullOrBlank()
        cardPriceRow?.visibility = if (hasPrice) View.VISIBLE else View.GONE
        cardPrice?.apply {
            text = model.priceText.orEmpty()
            textSize = 26f * sf
        }
        cardHourly?.apply {
            text = model.hourlyText.orEmpty()
            textSize = 19f * sf
            setTextColor(lighten(accent, 0.25f))
        }

        // ---- Metricas secundarias ----
        cardMetrics?.apply {
            if (model.secondaryMetrics.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = model.secondaryMetrics.joinToString("   ·   ")
                textSize = 12.5f * sf
            }
        }

        // ---- Combustivel + Lucro ----
        val hasFuel = !model.fuelText.isNullOrBlank() || !model.profitText.isNullOrBlank()
        cardFuelRow?.visibility = if (hasFuel) View.VISIBLE else View.GONE
        cardFuel?.apply {
            text = model.fuelText?.let { "⛽  $it" }.orEmpty()
            textSize = 12.5f * sf
        }
        cardProfit?.apply {
            text = model.profitText?.let { "💰  $it" }.orEmpty()
            textSize = 12.5f * sf
            setTextColor(Color.parseColor(if (model.profitPositive) "#86EFAC" else "#FCA5A5"))
        }

        // ---- Checklist (✔ / ✘) ----
        val (chips, details) = splitChecklist(model.checklist)
        cardDivider?.visibility = if (chips.isNotEmpty() || details.isNotEmpty()) View.VISIBLE else View.GONE
        cardChecks?.apply {
            if (chips.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = buildChecksSpannable(chips, "     ")
                textSize = 12.5f * sf
            }
        }
        cardDetails?.apply {
            if (details.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = buildChecksSpannable(details, "\n")
                textSize = 12f * sf
            }
        }

        val padH = dp((14 * sf).toInt())
        val padV = dp((11 * sf).toInt())
        cardBody?.setPadding(padH, padV, padH, padV)
    }

    fun showStatus(decision: Decision, message: String, autoHideMs: Long = 4500L) {
        val headline = when (decision) {
            Decision.INSIDE_AREA -> "Dentro da área"
            Decision.INTER_ZONE -> "Corrida entre zonas"
            Decision.RETURN_TO_AREA -> "Fora da área • Emb ≤ 1 km"
            Decision.OUTSIDE_AREA -> "Fora dos critérios"
            Decision.UNKNOWN_KEEP -> "Aviso do filtro"
        }
        showRideStatus(decision, headline, null, message, autoHideMs)
    }

    fun hideBanner() {
        bannerShowing = false
        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            cardProgressAnimator?.cancel()
            cardProgressAnimator = null
            bannerContainer?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            bannerContainer = null
            cardAccentBar = null
            cardAppChip = null
            cardTitle = null
            cardStatusPill = null
            cardPriceRow = null
            cardPrice = null
            cardHourly = null
            cardMetrics = null
            cardFuelRow = null
            cardFuel = null
            cardProfit = null
            cardDivider = null
            cardChecks = null
            cardDetails = null
            cardBody = null
            cardProgress = null
        }
    }

    // ------------------------------------------------------------------
    // Helpers do card profissional
    // ------------------------------------------------------------------

    private fun accentColorFor(decision: Decision): Int = Color.parseColor(
        when (decision) {
            Decision.INSIDE_AREA -> "#22C55E"
            Decision.INTER_ZONE -> "#A855F7"
            Decision.RETURN_TO_AREA -> "#EAB308"
            Decision.OUTSIDE_AREA -> "#EF4444"
            Decision.UNKNOWN_KEEP -> "#94A3B8"
        }
    )

    /**
     * Separa o checklist em "chips" curtos (✔ Nota 4,95 • ✘ R$36/h) e linhas de detalhe (✔ Embarque: Rua X).
     */
    private fun splitChecklist(raw: String): Pair<List<String>, List<String>> {
        val chips = mutableListOf<String>()
        val details = mutableListOf<String>()
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split("  •  ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                chips.addAll(parts)
            } else if (line.length <= 24 && (line.startsWith("✔") || line.startsWith("✘"))) {
                chips.add(line)
            } else {
                details.add(line)
            }
        }
        return chips to details
    }

    private fun buildChecksSpannable(items: List<String>, separator: String): CharSequence {
        val ok = Color.parseColor("#4ADE80")
        val bad = Color.parseColor("#F87171")
        val warn = Color.parseColor("#FACC15")
        val sb = SpannableStringBuilder()
        items.forEachIndexed { idx, rawItem ->
            if (idx > 0) sb.append(separator)
            // Substitui 📍 (par substituto que quebra com StyleSpan em algumas fontes) por ▸ nitido em ciano
            val item = if (rawItem.startsWith("📍")) "▸" + rawItem.removePrefix("📍") else rawItem
            val start = sb.length
            sb.append(item)
            val end = sb.length
            val iconColor = when {
                item.startsWith("✔") || item.startsWith("✅") -> ok
                item.startsWith("✘") || item.startsWith("❌") || item.startsWith("⚠") -> bad
                item.startsWith("🟡") -> warn
                item.startsWith("▸") -> Color.parseColor("#38BDF8")
                item.startsWith("🟣") -> Color.parseColor("#C084FC")
                else -> null
            }
            if (iconColor != null) {
                val cp = item.codePointAt(0)
                val iconEnd = Character.offsetByCodePoints(sb, start, 1).coerceAtMost(end)
                sb.setSpan(ForegroundColorSpan(iconColor), start, iconEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Nunca aplica StyleSpan(BOLD) sobre emojis com par substituto UTF-16 (evita glifo corrompido)
                if (Character.charCount(cp) == 1) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, iconEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // Itens reprovados ficam com texto levemente avermelhado
                if (iconColor == bad) {
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#FECACA")), iconEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            Color.alpha(c1),
            (Color.red(c1) * inv + Color.red(c2) * ratio).toInt(),
            (Color.green(c1) * inv + Color.green(c2) * ratio).toInt(),
            (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
        )
    }

    private fun lighten(color: Int, ratio: Float): Int = blend(color, Color.WHITE, ratio).let {
        Color.argb(255, Color.red(it), Color.green(it), Color.blue(it))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun updateFloatingControl(
        visible: Boolean,
        isFilterActive: Boolean,
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit,
        rideLockBadgeText: String? = null,
        onReleaseRideLock: (() -> Unit)? = null
    ) {
        if (!canRenderOverlay() || !visible) {
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
                pill.text = "ON"
                bg?.setColor(Color.parseColor("#E616A34A"))
                bg?.setStroke(dp(2), Color.parseColor("#86EFAC"))
            } else {
                pill.text = "OFF"
                bg?.setColor(Color.parseColor("#E6DC2626"))
                bg?.setStroke(dp(2), Color.parseColor("#FCA5A5"))
            }

            updateRideLockChipOnMain(rideLockBadgeText, onReleaseRideLock)
        }
    }

    private fun updateRideLockChipOnMain(badgeText: String?, onRelease: (() -> Unit)?) {
        currentRideLockReleaseCallback = onRelease
        if (badgeText.isNullOrBlank() || onRelease == null) {
            rideLockChipView?.let { v ->
                runCatching { windowManager.removeView(v) }
            }
            rideLockChipView = null
            rideLockParams = null
            return
        }

        val chip = rideLockChipView ?: createRideLockChip().also { created ->
            rideLockChipView = created
            val pillParams = floatingParams
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = pillParams?.x ?: dp(10)
                y = (pillParams?.y ?: dp(240)) + dp(44)
            }
            rideLockParams = params
            try {
                windowManager.addView(created, params)
            } catch (_: Exception) {
                rideLockChipView = null
                rideLockParams = null
                return
            }
        }
        chip.text = badgeText
    }

    private fun createRideLockChip(): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#EA1E293B"))
            setStroke(dp(2), Color.parseColor("#F59E0B"))
        }
        return TextView(context).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FDE68A"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bg
            elevation = dp(7).toFloat()
            setOnClickListener {
                if (System.currentTimeMillis() >= ignorePillTouchesUntilMs) {
                    currentRideLockReleaseCallback?.invoke()
                }
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
            rideLockChipView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            rideLockChipView = null
            rideLockParams = null
            currentRideLockReleaseCallback = null
        }
    }

    fun showHeartFavoritePrompt(
        rideSummary: String,
        passengerName: String? = null,
        onSaveFavorite: () -> Unit,
        autoHideMs: Long = 12000L
    ) {
        if (!canRenderOverlay()) return

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

    private fun createProfessionalCardView(): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#EE1E293B"))
            setStroke(dp(1), Color.parseColor("#8822C55E"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = dp(14).toFloat()
            clipToOutline = true
        }

        val middleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val accentBar = View(context).apply {
            setBackgroundColor(Color.parseColor("#22C55E"))
            layoutParams = LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        cardAccentBar = accentBar

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cardBody = body

        // Linha 1: Chip do app + Titulo + Pilula de Status
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appChip = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(7) }
        }
        val titleView = TextView(context).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statusPill = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(99).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        cardAppChip = appChip
        cardTitle = titleView
        cardStatusPill = statusPill
        headerRow.addView(appChip)
        headerRow.addView(titleView)
        headerRow.addView(statusPill)

        // Linha 2: Valor principal + R$/h
        val priceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(4), 0, dp(1))
        }
        val priceView = TextView(context).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hourlyView = TextView(context).apply {
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        cardPriceRow = priceRow
        cardPrice = priceView
        cardHourly = hourlyView
        priceRow.addView(priceView)
        priceRow.addView(hourlyView)

        // Linha 3: Metricas secundarias
        val metricsView = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(2), 0, dp(4))
        }
        cardMetrics = metricsView

        // Linha 4: Combustivel + Lucro liquido
        val fuelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#2A000000"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(4) }
        }
        val fuelView = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FDE68A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val profitView = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        cardFuelRow = fuelRow
        cardFuel = fuelView
        cardProfit = profitView
        fuelRow.addView(fuelView)
        fuelRow.addView(profitView)

        // Divisor + Checklist
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#26FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(4); bottomMargin = dp(6) }
        }
        val checksView = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, dp(3))
        }
        val detailsView = TextView(context).apply {
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.05f)
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        cardDivider = divider
        cardChecks = checksView
        cardDetails = detailsView

        body.addView(headerRow)
        body.addView(priceRow)
        body.addView(metricsView)
        body.addView(fuelRow)
        body.addView(divider)
        body.addView(checksView)
        body.addView(detailsView)

        middleRow.addView(accentBar)
        middleRow.addView(body)

        val progress = View(context).apply {
            setBackgroundColor(Color.parseColor("#CC22C55E"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        }
        cardProgress = progress

        root.addView(middleRow)
        root.addView(progress)
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingPill(
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit
    ): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#E616A34A"))
            setStroke(dp(2), Color.parseColor("#86EFAC"))
        }

        return TextView(context).apply {
            text = "ON"
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
                                rideLockChipView?.let { lockView ->
                                    rideLockParams?.let { lp ->
                                        lp.x = params.x
                                        lp.y = params.y + dp(44)
                                        windowManager.updateViewLayout(lockView, lp)
                                    }
                                }
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
        val maxCardWidth = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
        return WindowManager.LayoutParams(
            maxCardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
    }

    private fun createFloatingPillParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
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
            overlayWindowType(),
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
