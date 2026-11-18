package com.ruan.uberareafilter.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.storage.ConfigRepository
import java.util.Calendar
import kotlin.math.abs

/**
 * Gerencia:
 * 1) Card de notificacao no topo (decisao + valores + checklist), em 3 niveis de informacao
 * 2) Botao flutuante discreto e arrastavel (ON / PAUSA) sobre a 99/Uber
 * 3) Botao flutuante de Coracao (Favoritar Passageiro/Horario) na tela de avaliacao
 *
 * Desenho pensado para leitura em menos de 1 segundo no transito:
 *  - ordem fixa de leitura: simbolo + cor da decisao -> valor e R$/h -> o que esta errado;
 *  - numeros em sans-serif medium com algarismos de largura fixa ("tnum"); "R$" e "/h" menores;
 *  - cor escolhida pelo motorista passa pelo CardPalette (fundo escuro travado, acento com contraste);
 *  - de noite (tema escuro do sistema ou 19h-6h) o card fica mais escuro e o texto nao e branco puro.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Card de notificacao no topo
    private var bannerContainer: LinearLayout? = null
    private var cardAccentBar: View? = null
    private var cardBadge: TextView? = null
    private var cardAppChip: TextView? = null
    private var cardTitle: TextView? = null
    private var cardStatusPill: TextView? = null
    private var cardPriceRow: LinearLayout? = null
    private var cardPrice: TextView? = null
    private var cardHourly: TextView? = null
    private var cardReason: TextView? = null
    private var cardMetrics: TextView? = null
    private var cardFuelRow: LinearLayout? = null
    private var cardFuel: TextView? = null
    private var cardProfit: TextView? = null
    private var cardDivider: View? = null
    private var cardChecks: TextView? = null
    private var cardDetails: LinearLayout? = null
    private var cardBody: LinearLayout? = null
    private var cardProgressTrack: View? = null
    private var cardProgress: View? = null
    private var cardProgressAnimator: ObjectAnimator? = null
    private val hideBannerRunnable = Runnable { hideBanner() }
    @Volatile
    private var bannerShowing: Boolean = false
    private var bannerParams: WindowManager.LayoutParams? = null

    // Botao discreto grudado no card ("Aceitar se tocar de novo"): janela propria e tocavel,
    // porque o card deixa os toques passarem para a 99/Uber.
    private var quickView: TextView? = null
    private var quickParams: WindowManager.LayoutParams? = null
    private var quickCallback: (() -> Unit)? = null
    private val removeQuickRunnable = Runnable { removeQuickView() }

    private val typefaceMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val typefaceRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun isBannerVisible(): Boolean = bannerShowing && bannerContainer != null

    // Botao flutuante discreto (ON/PAUSA) e chip opcional de trava em corrida (modo simultaneo)
    // Ampulheta: em cima o icone do app (abre o app), embaixo o estado ON/PAUSA. Uma janela so: arrasta junto.
    private var floatingRootView: LinearLayout? = null
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
            floatingRootView?.visibility = View.GONE
            rideLockChipView?.visibility = View.GONE
            quickView?.visibility = View.GONE
            mainHandler.postDelayed({
                bannerContainer?.visibility = View.VISIBLE
                floatingRootView?.visibility = View.VISIBLE
                rideLockChipView?.visibility = View.VISIBLE
                quickView?.visibility = View.VISIBLE
            }, durationMs)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideAction()
        } else {
            mainHandler.post { hideAction() }
        }
    }

    // Botao de Coracao (Favoritar na janela de avaliacao)
    private var heartBannerView: LinearLayout? = null
    private var heartTextView: TextView? = null
    private val hideHeartRunnable = Runnable { hideHeartPrompt() }

    /**
     * Compatibilidade: avisos simples (ex: alerta de Favorito) usando o card.
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

    fun showRideCard(model: RideCardModel, onQuickAction: (() -> Unit)? = null) {
        if (!canRenderOverlay()) return

        bannerShowing = true
        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            var isNew = false
            val container = bannerContainer ?: createProfessionalCardView().also {
                bannerContainer = it
                isNew = true
                val lp = createBannerLayoutParams()
                try {
                    windowManager.addView(it, lp)
                    bannerParams = lp
                } catch (_: Exception) {
                    bannerContainer = null
                    bannerShowing = false
                    return@post
                }
            }
            container.visibility = View.VISIBLE
            bindRideCardViews(container, model)

            // Botao discreto grudado embaixo do card (posicionado depois que o card mede a altura)
            val quickLabel = model.quickActionLabel
            if (quickLabel != null && onQuickAction != null) {
                container.postDelayed({ showQuickButton(container, quickLabel, onQuickAction, model.autoHideMs) }, 120L)
            } else {
                removeQuickView()
            }

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

    // ------------------------------------------------------------------
    // Niveis de informacao por tamanho do card
    // ------------------------------------------------------------------

    private enum class CardLevel(val scale: Float) {
        COMPACT(0.86f), // so decide: simbolo, valores e o motivo principal
        NORMAL(1.0f),   // + metricas, combustivel/lucro, falhas do checklist e as 2 ruas
        LARGE(1.18f)    // tudo, letras maiores, cada item do checklist e enderecos completos
    }

    private enum class Mark(val glyph: String, val priority: Int) {
        LOCK("■", 0),
        BAD("✕", 1),
        WARN("!", 2),
        ZONE("⇄", 3),
        INFO("▸", 4),
        NEUTRAL("", 5),
        OK("✓", 6)
    }

    private data class CheckItem(val mark: Mark, val text: String)

    private fun isNightNow(): Boolean {
        val ui = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (ui == Configuration.UI_MODE_NIGHT_YES) return true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 19 || hour < 6
    }

    private fun userColorFor(decision: Decision, cfg: com.ruan.uberareafilter.model.FilterConfig?): Int? {
        if (cfg == null) return null
        val hex = when (decision) {
            Decision.INSIDE_AREA -> cfg.colorInsideHex
            Decision.INTER_ZONE -> cfg.colorInterZoneHex
            Decision.RETURN_TO_AREA -> cfg.colorYellowReturnHex
            Decision.OUTSIDE_AREA -> cfg.colorOutsideHex
            Decision.UNCERTAIN -> null
            Decision.UNKNOWN_KEEP -> null
        }
        return CardPalette.parseHex(hex)
    }

    private fun bindRideCardViews(container: LinearLayout, model: RideCardModel) {
        val cfg = runCatching { ConfigRepository(context).load() }.getOrNull()
        val level = when (cfg?.bannerScale ?: 1) {
            0 -> CardLevel.COMPACT
            2 -> CardLevel.LARGE
            else -> CardLevel.NORMAL
        }
        val sf = level.scale
        val zoneColor = model.zoneColorHex?.takeIf { it.isNotBlank() }?.let { CardPalette.parseHex(it) }
        val pal = CardPalette.build(zoneColor ?: userColorFor(model.decision, cfg), model.decision, isNightNow())

        // ---- Fundo, borda, barra lateral e barra de tempo ----
        (container.background as? GradientDrawable)?.apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(pal.bgTop, pal.bgBottom)
            setStroke(dp(1), pal.cardStroke)
        }
        cardAccentBar?.setBackgroundColor(pal.accent)
        cardProgressTrack?.setBackgroundColor(pal.progressTrack)
        cardProgress?.setBackgroundColor(pal.progressFill)

        // ---- Cabecalho: simbolo da decisao + titulo + app + status ----
        cardBadge?.apply {
            text = CardPalette.glyph(model.decision)
            textSize = 13f * sf
            setTextColor(pal.glyphOnAccent)
            val size = dp((24 * sf).toInt())
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = size
                height = size
            }
            (background as? GradientDrawable)?.setColor(pal.accent)
        }
        cardTitle?.apply {
            text = model.title
            textSize = 15f * sf
            setTextColor(pal.textPrimary)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.46f).toInt()
        }
        val app = model.appSource?.uppercase()
        cardAppChip?.apply {
            if (app.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = if (app == "UBER") "Uber" else app
                textSize = 10f * sf
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
        cardStatusPill?.apply {
            if (model.statusLabel.isBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = model.statusLabel.uppercase()
                textSize = 10f * sf
                setTextColor(pal.accentText)
                (background as? GradientDrawable)?.apply {
                    setColor(pal.pillBg)
                    setStroke(dp(1), pal.pillStroke)
                }
            }
        }

        // ---- Valores: corrida (texto) e R$/h (acento), "R$" e "/h" pequenos ----
        val heroSp = when (level) {
            CardLevel.COMPACT -> 22f
            CardLevel.NORMAL -> 28f
            CardLevel.LARGE -> 28f * 1.18f
        }
        val hasPrice = !model.priceText.isNullOrBlank() || !model.hourlyText.isNullOrBlank()
        cardPriceRow?.visibility = if (hasPrice) View.VISIBLE else View.GONE
        cardPrice?.apply {
            text = moneySpan(model.priceText.orEmpty(), pal.textPrimary, pal.textSecondary)
            textSize = heroSp
        }
        cardHourly?.apply {
            text = moneySpan(model.hourlyText.orEmpty(), pal.accentText, pal.textSecondary)
            textSize = heroSp
        }

        // ---- Checklist: problemas primeiro, acertos resumidos ----
        val (rawChips, rawDetails) = splitChecklist(model.checklist)
        val chips = rawChips.map { parseMark(it) }.sortedBy { it.mark.priority }
        val details = rawDetails.map { parseMark(it) }

        // ---- COMPACTO: uma unica linha com o motivo principal ----
        cardReason?.apply {
            if (level != CardLevel.COMPACT) {
                visibility = View.GONE
            } else {
                val problem = (chips + details)
                    .filter { it.mark.priority <= Mark.ZONE.priority }
                    .minByOrNull { it.mark.priority }
                val item = problem ?: CheckItem(Mark.OK, "Tudo dentro dos critérios")
                visibility = View.VISIBLE
                text = buildChecksSpannable(listOf(item), "", pal)
                textSize = 14f * sf
                maxLines = 1
            }
        }

        // ---- Metricas secundarias (R$/km, km, nota, paradas) ----
        cardMetrics?.apply {
            if (level == CardLevel.COMPACT || model.secondaryMetrics.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = model.secondaryMetrics.joinToString("  ·  ")
                textSize = 13f * sf
                setTextColor(pal.textSecondary)
                maxLines = if (level == CardLevel.LARGE) 2 else 1
            }
        }

        // ---- Combustivel + Lucro (icone + rotulo discreto, valor colorido) ----
        val hasFuel = level != CardLevel.COMPACT && (!model.fuelText.isNullOrBlank() || !model.profitText.isNullOrBlank())
        cardFuelRow?.visibility = if (hasFuel) View.VISIBLE else View.GONE
        cardFuel?.apply {
            // "R$ 1,20 de gasolina": valor em ambar, "de gasolina" discreto
            val fuelIcon = if (cfg?.fuelType == com.ruan.uberareafilter.model.FilterConfig.FUEL_ELETRICO) "⚡" else "⛽"
            text = model.fuelText?.let {
                SpannableStringBuilder("$fuelIcon ").append(splitValue(it, " de ", pal.warn, pal.textTertiary, valueFirst = true))
            } ?: ""
            textSize = 12.5f * sf
        }
        cardProfit?.apply {
            // "Lucro R$ 17,70 · R$ 39/h líq.": rotulo discreto, valor verde (ou vermelho se prejuizo)
            val profitColor = if (model.profitPositive) pal.ok else pal.bad
            text = model.profitText?.let {
                SpannableStringBuilder("💰 ").append(splitValue(it, "Lucro ", profitColor, pal.textTertiary, valueFirst = false))
            } ?: ""
            textSize = 12.5f * sf
        }

        // ---- Chips do checklist ----
        val shownChips = when (level) {
            CardLevel.COMPACT -> emptyList()
            CardLevel.LARGE -> chips
            CardLevel.NORMAL -> {
                val problems = chips.filter { it.mark != Mark.OK }
                val okCount = chips.count { it.mark == Mark.OK }
                if (okCount > 0) problems + CheckItem(Mark.OK, "$okCount ok") else problems
            }
        }
        cardChecks?.apply {
            if (shownChips.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = buildChecksSpannable(shownChips, "     ", pal)
                textSize = 12.5f * sf
            }
        }

        // ---- Linhas de detalhe (Embarque / Destino / avisos) ----
        cardDetails?.let { box ->
            box.removeAllViews()
            if (level == CardLevel.COMPACT || details.isEmpty()) {
                box.visibility = View.GONE
            } else {
                box.visibility = View.VISIBLE
                val lines = if (level == CardLevel.LARGE) details else details.take(3)
                lines.forEach { item ->
                    box.addView(TextView(context).apply {
                        text = buildChecksSpannable(listOf(item), "", pal)
                        textSize = 13f * sf
                        typeface = typefaceRegular
                        maxLines = if (level == CardLevel.LARGE) 2 else 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(1), 0, dp(1))
                    })
                }
            }
        }

        cardDivider?.apply {
            setBackgroundColor(CardPalette.withAlpha(pal.textPrimary, 0x17))
            visibility = if (level != CardLevel.COMPACT && (shownChips.isNotEmpty() || details.isNotEmpty())) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val padH = dp((14 * sf).toInt())
        cardBody?.setPadding(padH, dp((11 * sf).toInt()), padH, dp((10 * sf).toInt()))
    }

    fun showStatus(decision: Decision, message: String, autoHideMs: Long = 4500L) {
        val headline = when (decision) {
            Decision.INSIDE_AREA -> "Dentro da área"
            Decision.INTER_ZONE -> "Corrida entre zonas"
            Decision.RETURN_TO_AREA -> "Fora da área • Emb ≤ 1 km"
            Decision.OUTSIDE_AREA -> "Fora dos critérios"
            Decision.UNCERTAIN -> "Local incerto — confira"
            Decision.UNKNOWN_KEEP -> "Aviso do filtro"
        }
        showRideStatus(decision, headline, null, message, autoHideMs)
    }

    // ------------------------------------------------------------------
    // Botao rapido grudado no card
    // ------------------------------------------------------------------

    private fun showQuickButton(card: View, label: String, onTap: () -> Unit, autoHideMs: Long, attempt: Int = 0) {
        if (!bannerShowing || bannerContainer !== card) return
        if (card.height <= 0) {
            // Card ainda nao mediu a altura (primeiro desenho): tenta de novo logo depois
            if (attempt < 5) card.postDelayed({ showQuickButton(card, label, onTap, autoHideMs, attempt + 1) }, 100L)
            return
        }
        quickCallback = onTap
        val screenW = context.resources.displayMetrics.widthPixels
        val sideMargin = ((screenW - card.width) / 2).coerceAtLeast(0)
        val y = (bannerParams?.y ?: dp(72)) + card.height + dp(6)
        val view = quickView ?: TextView(context).apply {
            typeface = typefaceMedium
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DFFBEA"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minHeight = dp(36)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E6111111"))
                setStroke(dp(1), Color.parseColor("#A606C167"))
            }
            elevation = dp(6).toFloat()
            contentDescription = label
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                val cb = quickCallback
                quickCallback = null
                cb?.invoke()
            }
        }.also { v ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = sideMargin + dp(6)
                this.y = y
            }
            try {
                windowManager.addView(v, lp)
                quickView = v
                quickParams = lp
            } catch (_: Exception) {
                return
            }
        }
        view.text = label
        quickParams?.let { lp ->
            if (lp.y != y) {
                lp.y = y
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
        view.visibility = View.VISIBLE
        mainHandler.removeCallbacks(removeQuickRunnable)
        mainHandler.postDelayed(removeQuickRunnable, autoHideMs + 1500L)
    }

    /** Troca o texto do botao rapido (ex.: "✓ Vou aceitar se tocar de novo") e some logo depois. */
    fun confirmQuickAction(text: String, hideAfterMs: Long = 2200L) {
        mainHandler.post {
            val v = quickView ?: return@post
            v.text = text
            (v.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor("#FF06C167"))
            mainHandler.removeCallbacks(removeQuickRunnable)
            mainHandler.postDelayed(removeQuickRunnable, hideAfterMs)
        }
    }

    private fun removeQuickView() {
        mainHandler.removeCallbacks(removeQuickRunnable)
        quickCallback = null
        quickView?.let { v -> runCatching { windowManager.removeView(v) } }
        quickView = null
        quickParams = null
    }

    /**
     * Desenha o card que esta na tela numa imagem (para juntar com a foto da corrida).
     * Espera o card terminar de desenhar; devolve null se ele nao estiver visivel.
     */
    fun captureRideCardBitmap(delayMs: Long = 380L, onResult: (Bitmap?) -> Unit) {
        mainHandler.postDelayed({
            val v = bannerContainer
            val bmp = if (v != null && bannerShowing && v.width > 0 && v.height > 0) {
                runCatching {
                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> v.draw(Canvas(b)) }
                }.getOrNull()
            } else {
                null
            }
            onResult(bmp)
        }, delayMs)
    }

    fun hideBanner() {
        bannerShowing = false
        mainHandler.post {
            removeQuickView()
            mainHandler.removeCallbacks(hideBannerRunnable)
            cardProgressAnimator?.cancel()
            cardProgressAnimator = null
            bannerContainer?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            bannerContainer = null
            bannerParams = null
            cardAccentBar = null
            cardBadge = null
            cardAppChip = null
            cardTitle = null
            cardStatusPill = null
            cardPriceRow = null
            cardPrice = null
            cardHourly = null
            cardReason = null
            cardMetrics = null
            cardFuelRow = null
            cardFuel = null
            cardProfit = null
            cardDivider = null
            cardChecks = null
            cardDetails = null
            cardBody = null
            cardProgressTrack = null
            cardProgress = null
        }
    }

    // ------------------------------------------------------------------
    // Helpers do card
    // ------------------------------------------------------------------

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

    /** Le o simbolo do inicio do item (✔ ✘ ⚠ 🟡 🟣 📍 🔒...) e devolve categoria + texto limpo. */
    private fun parseMark(raw: String): CheckItem {
        val t = raw.trim()
        val prefixes = listOf(
            "✔️" to Mark.OK, "✔" to Mark.OK, "✅" to Mark.OK,
            "✘" to Mark.BAD, "❌" to Mark.BAD,
            "⚠️" to Mark.WARN, "⚠" to Mark.WARN, "🟡" to Mark.WARN,
            "🟣" to Mark.ZONE,
            "📍" to Mark.INFO, "▸" to Mark.INFO,
            "🔒" to Mark.LOCK
        )
        for ((p, m) in prefixes) {
            if (t.startsWith(p)) {
                return CheckItem(m, t.removePrefix(p).removePrefix("️").trim())
            }
        }
        return CheckItem(Mark.NEUTRAL, t)
    }

    private fun markColor(mark: Mark, pal: CardPalette.Colors): Int? = when (mark) {
        Mark.OK -> pal.ok
        Mark.BAD -> pal.bad
        Mark.WARN, Mark.LOCK -> pal.warn
        Mark.ZONE -> pal.purple
        Mark.INFO -> pal.info
        Mark.NEUTRAL -> null
    }

    /**
     * Itens com simbolo colorido; "Embarque:" / "Destino:" ficam discretos e o endereco em destaque.
     * Itens reprovados ganham texto levemente avermelhado.
     */
    private fun buildChecksSpannable(items: List<CheckItem>, separator: String, pal: CardPalette.Colors): CharSequence {
        val sb = SpannableStringBuilder()
        items.forEachIndexed { idx, item ->
            if (idx > 0) sb.append(separator)
            val iconColor = markColor(item.mark, pal)
            if (iconColor != null && item.mark.glyph.isNotEmpty()) {
                val s = sb.length
                sb.append(item.mark.glyph)
                sb.setSpan(ForegroundColorSpan(iconColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("  ")
            }
            val body = item.text
            val textColor = if (item.mark == Mark.BAD) pal.badText else pal.textPrimary
            val colon = body.indexOf(':')
            if (colon in 1..18) {
                val s = sb.length
                sb.append(body.substring(0, colon + 1))
                sb.setSpan(ForegroundColorSpan(pal.textTertiary), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s2 = sb.length
                sb.append(body.substring(colon + 1))
                sb.setSpan(ForegroundColorSpan(textColor), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val s = sb.length
                sb.append(body)
                sb.setSpan(ForegroundColorSpan(textColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** "R$ 18,90" / "R$ 42/h líq." -> numero grande, "R$" e "/h líq." menores e discretos. */
    private fun moneySpan(raw: String, numberColor: Int, unitColor: Int): CharSequence {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val sb = SpannableStringBuilder()
        var body = text
        if (body.startsWith("R$")) {
            val s = sb.length
            sb.append("R$ ")
            sb.setSpan(RelativeSizeSpan(0.5f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(unitColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body = body.removePrefix("R$").trim()
        }
        val slash = body.indexOf('/')
        val number = if (slash > 0) body.substring(0, slash).trim() else body
        val suffix = if (slash > 0) body.substring(slash) else ""
        val ns = sb.length
        sb.append(number)
        sb.setSpan(ForegroundColorSpan(numberColor), ns, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (suffix.isNotEmpty()) {
            val s = sb.length
            sb.append(suffix)
            sb.setSpan(RelativeSizeSpan(0.5f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(unitColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /**
     * Divide um texto em parte "valor" (colorida) e parte "rotulo" (discreta):
     *  - valueFirst = true:  "R$ 1,20| de gasolina"   (corta antes do marcador)
     *  - valueFirst = false: "Lucro |R$ 17,70"         (marcador e o rotulo do inicio)
     */
    private fun splitValue(text: String, marker: String, valueColor: Int, labelColor: Int, valueFirst: Boolean): CharSequence {
        val sb = SpannableStringBuilder(text)
        val idx = text.indexOf(marker)
        if (idx < 0) {
            sb.setSpan(ForegroundColorSpan(valueColor), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        if (valueFirst) {
            sb.setSpan(ForegroundColorSpan(valueColor), 0, idx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(labelColor), idx, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            val end = idx + marker.length
            sb.setSpan(ForegroundColorSpan(labelColor), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(valueColor), end, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /**
     * Fundo do botao flutuante com ANEL DUPLO: anel escuro por fora (aparece em mapa claro)
     * e anel claro por dentro (aparece em mapa escuro).
     */
    private fun ringedPillBackground(fill: Int, innerRing: Int, radiusDp: Int): LayerDrawable {
        val outer = GradientDrawable().apply {
            cornerRadius = dp(radiusDp + 2).toFloat()
            setColor(Color.parseColor("#9E000000"))
        }
        val inner = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(2), innerRing)
        }
        return LayerDrawable(arrayOf<Drawable>(outer, inner)).apply {
            val inset = dp(2)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun pillLabel(dotColor: Int, label: String): CharSequence {
        val sb = SpannableStringBuilder("●  ")
        sb.setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(label)
        return sb
    }

    @SuppressLint("ClickableViewAccessibility")
    fun updateFloatingControl(
        visible: Boolean,
        runState: com.ruan.uberareafilter.model.RunState,
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit,
        rideLockBadgeText: String? = null,
        onReleaseRideLock: (() -> Unit)? = null,
        /** App na frente ("99" ou "UBER"): muda as cores do botao. */
        brand: String? = null,
        /** Toque no icone de cima: abre o app. */
        onOpenApp: (() -> Unit)? = null
    ) {
        if (!canRenderOverlay() || !visible) {
            hideFloatingControl()
            return
        }

        mainHandler.post {
            if (floatingRootView == null) {
                val root = createFloatingControl(onToggleFilter, onLongPressFavorite, onOpenApp)
                val params = createFloatingPillParams()
                try {
                    windowManager.addView(root, params)
                } catch (_: Exception) {
                    floatingPillView = null
                    return@post
                }
                floatingRootView = root
                floatingParams = params
            }
            val pill = floatingPillView ?: return@post

            // Na 99: ON amarelo (estilo 99) e PAUSA preto. Na Uber: ON preto e PAUSA ambar. OFF sempre vermelho.
            val is99 = brand == "99"
            when (runState) {
                com.ruan.uberareafilter.model.RunState.ON -> {
                    if (is99) {
                        pill.text = pillLabel(Color.parseColor("#0A7A3C"), "ON")
                        pill.setTextColor(Color.parseColor("#111111"))
                        pill.background = ringedPillBackground(Color.parseColor("#F5FFD400"), Color.parseColor("#B8111111"), 24)
                    } else {
                        pill.text = pillLabel(Color.parseColor("#06C167"), "ON")
                        pill.setTextColor(Color.WHITE)
                        pill.background = ringedPillBackground(Color.parseColor("#F2111111"), Color.parseColor("#B8FFFFFF"), 24)
                    }
                    pill.contentDescription = "Filtro ligado. Toque para pausar (só leitura)."
                }
                com.ruan.uberareafilter.model.RunState.PAUSED -> {
                    // Continua lendo e mostrando o card, mas nao toca em nada
                    pill.text = pillLabel(Color.parseColor("#FFDD00"), "PAUSA")
                    pill.setTextColor(Color.WHITE)
                    pill.background = if (is99) {
                        ringedPillBackground(Color.parseColor("#F2111111"), Color.parseColor("#B8FFD400"), 24)
                    } else {
                        ringedPillBackground(Color.parseColor("#F27A5800"), Color.parseColor("#B8FFDD00"), 24)
                    }
                    pill.contentDescription = "Pausado: só lê as corridas. Toque para desligar a leitura de tela."
                }
                com.ruan.uberareafilter.model.RunState.OFF -> {
                    pill.text = pillLabel(Color.parseColor("#FFDAD5"), "OFF")
                    pill.setTextColor(Color.WHITE)
                    pill.background = ringedPillBackground(Color.parseColor("#F2D0101F"), Color.parseColor("#B8FFFFFF"), 24)
                    pill.contentDescription = "Desligado."
                }
            }
            pill.setPadding(dp(18), dp(10), dp(18), dp(10))

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
                y = (pillParams?.y ?: dp(240)) + lockChipOffsetPx()
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
        return TextView(context).apply {
            textSize = 12f
            typeface = typefaceMedium
            setTextColor(Color.parseColor("#FDE68A"))
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            background = ringedPillBackground(Color.parseColor("#F2262523"), Color.parseColor("#E6F59E0B"), 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(7).toFloat()
            setOnClickListener {
                if (System.currentTimeMillis() >= ignorePillTouchesUntilMs) {
                    currentRideLockReleaseCallback?.invoke()
                }
            }
        }
    }

    /** Posicao atual do botao flutuante (para o botao OFF aparecer no mesmo lugar). */
    fun floatingPillPosition(): Pair<Int, Int>? = floatingParams?.let { it.x to it.y }

    /** A etiqueta da trava 99/Uber fica logo abaixo da ampulheta. */
    private fun lockChipOffsetPx(): Int = floatingRootView?.height?.takeIf { it > 0 }?.plus(dp(6)) ?: dp(112)

    fun hideFloatingControl() {
        mainHandler.post {
            floatingRootView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            floatingRootView = null
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
        autoHideMs: Long = 12000L,
        onMarkProblem: (() -> Unit)? = null
    ) {
        if (!canRenderOverlay()) return

        mainHandler.post {
            mainHandler.removeCallbacks(hideHeartRunnable)
            if (heartBannerView == null) {
                val created = createHeartPromptView(onSaveFavorite, onMarkProblem)
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
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#F2201F1D"))
            setStroke(dp(1), Color.parseColor("#5957DB9D"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = dp(12).toFloat()
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
            layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        cardAccentBar = accentBar

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cardBody = body

        fun rowParams(topDp: Int) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }

        // Linha 1: simbolo + titulo + chip do app + (espaco) + pilula de status
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowParams(0)
        }
        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val titleView = TextView(context).apply {
            typeface = typefaceMedium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val appChip = TextView(context).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(5).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val statusPill = TextView(context).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            maxLines = 1
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(99).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        cardBadge = badge
        cardTitle = titleView
        cardAppChip = appChip
        cardStatusPill = statusPill
        headerRow.addView(badge)
        headerRow.addView(titleView)
        headerRow.addView(appChip)
        headerRow.addView(spacer)
        headerRow.addView(statusPill)

        // Linha 2: valor da corrida (esquerda) + R$/h (direita), alinhados pela base
        val priceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            layoutParams = rowParams(6)
        }
        val priceView = TextView(context).apply {
            typeface = typefaceMedium
            fontFeatureSettings = "tnum"
            letterSpacing = -0.01f
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hourlyView = TextView(context).apply {
            typeface = typefaceMedium
            fontFeatureSettings = "tnum"
            letterSpacing = -0.01f
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.END
        }
        cardPriceRow = priceRow
        cardPrice = priceView
        cardHourly = hourlyView
        priceRow.addView(priceView)
        priceRow.addView(hourlyView)

        // Linha 3 (so no Compacto): o motivo principal em uma linha
        val reasonView = TextView(context).apply {
            typeface = typefaceRegular
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = rowParams(6)
        }
        cardReason = reasonView

        // Linha 3: metricas secundarias
        val metricsView = TextView(context).apply {
            typeface = typefaceRegular
            fontFeatureSettings = "tnum"
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = rowParams(6)
        }
        cardMetrics = metricsView

        // Linha 4: combustivel (esq.) + lucro (dir.)
        val fuelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = rowParams(4)
        }
        val fuelView = TextView(context).apply {
            typeface = typefaceMedium
            fontFeatureSettings = "tnum"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val profitView = TextView(context).apply {
            typeface = typefaceMedium
            fontFeatureSettings = "tnum"
            maxLines = 1
            gravity = Gravity.END
        }
        cardFuelRow = fuelRow
        cardFuel = fuelView
        cardProfit = profitView
        fuelRow.addView(fuelView)
        fuelRow.addView(profitView)

        // Divisor + chips do checklist + linhas de detalhe
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(6) }
        }
        val checksView = TextView(context).apply {
            typeface = typefaceRegular
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(3))
        }
        val detailsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = rowParams(2)
        }
        cardDivider = divider
        cardChecks = checksView
        cardDetails = detailsBox

        body.addView(headerRow)
        body.addView(priceRow)
        body.addView(reasonView)
        body.addView(metricsView)
        body.addView(fuelRow)
        body.addView(divider)
        body.addView(checksView)
        body.addView(detailsBox)

        middleRow.addView(accentBar)
        middleRow.addView(body)

        // Barra de tempo restante: trilho discreto + preenchimento na cor da decisao
        val progressFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        }
        val track = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val progress = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        progressFrame.addView(track)
        progressFrame.addView(progress)
        cardProgressTrack = track
        cardProgress = progress

        root.addView(middleRow)
        root.addView(progressFrame)
        return root
    }

    /**
     * Ampulheta flutuante: icone do app em cima (toque = abre o app), "pescoco" e o estado embaixo
     * (toque = ON -> PAUSA -> OFF). Segurar = "🐞 Algo deu errado?". Arrastar move tudo junto.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingControl(
        onToggleFilter: () -> Unit,
        onLongPressFavorite: () -> Unit,
        onOpenApp: (() -> Unit)?
    ): LinearLayout {
        val pill = TextView(context).apply {
            text = pillLabel(Color.parseColor("#06C167"), "ON")
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(44)
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = ringedPillBackground(Color.parseColor("#F2111111"), Color.parseColor("#B8FFFFFF"), 24)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = dp(8).toFloat()
        }
        floatingPillView = pill
        val logo = android.widget.ImageView(context).apply {
            setImageResource(com.ruan.uberareafilter.R.drawable.ic_logo_mark)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6121212"))
                setStroke(dp(2), Color.parseColor("#B8FFFFFF"))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = dp(8).toFloat()
            contentDescription = "Abrir o Filtro Inteligente"
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        }
        val neck = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#B8FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(6))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo)
            addView(neck)
            addView(pill)

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
                                        lp.y = params.y + lockChipOffsetPx()
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
                            // Metade de cima (icone + pescoco) abre o app; o resto liga/pausa
                            val onLogo = event.y < logo.bottom + dp(3)
                            if (onLogo && onOpenApp != null) {
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                onOpenApp()
                            } else {
                                onToggleFilter()
                            }
                        }
                        true
                    }

                    else -> false
                }
            }
        }
    }

    private fun createHeartPromptView(onSaveFavorite: () -> Unit, onMarkProblem: (() -> Unit)? = null): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#F2242320"))
            setStroke(dp(2), Color.parseColor("#F48FB1"))
        }

        val txt = TextView(context).apply {
            setTextColor(Color.parseColor("#F3F1EC"))
            textSize = 14f
            typeface = typefaceMedium
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        heartTextView = txt

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = bg
            elevation = dp(10).toFloat()
            addView(txt)
            setOnClickListener {
                onSaveFavorite()
                txt.text = "💖 Salvo nos Favoritos!"
                mainHandler.removeCallbacks(hideHeartRunnable)
                mainHandler.postDelayed(hideHeartRunnable, 1800L)
            }
            // Marca no log o momento em que algo deu errado (para achar depois e corrigir)
            if (onMarkProblem != null) {
                addView(View(context).apply {
                    setBackgroundColor(Color.parseColor("#33FFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(10)
                    }
                })
                addView(TextView(context).apply {
                    text = "🐞 Algo deu errado agora? Toque aqui para marcar"
                    setTextColor(Color.parseColor("#FFD8A8"))
                    textSize = 13.5f
                    typeface = typefaceMedium
                    minHeight = dp(40)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        onMarkProblem()
                        text = "✓ Marcado! Depois conte o que aconteceu."
                        mainHandler.removeCallbacks(hideHeartRunnable)
                        mainHandler.postDelayed(hideHeartRunnable, 2200L)
                    }
                })
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

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
