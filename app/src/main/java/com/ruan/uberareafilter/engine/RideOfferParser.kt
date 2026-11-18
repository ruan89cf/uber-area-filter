package com.ruan.uberareafilter.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

data class ParsedRideOffer(
    val isRideOffer: Boolean,
    val isContinuePrompt: Boolean,
    val isRatingScreen: Boolean,
    val ratingPassengerName: String?,
    val isInActiveRide: Boolean,
    val hasRiskAreaBadge: Boolean,
    val allTexts: List<String>,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val ridePrice: Double?,
    val pickupDistanceKm: Double?,
    val dropoffDistanceKm: Double?,
    val totalDistanceKm: Double?,
    val pricePerKm: Double?,
    val hourlyRate: Double?,
    val passengerRating: Double?,
    val isNewPassenger: Boolean,
    val addressCandidates: List<String>,
    val rejectNode: AccessibilityNodeInfo?,
    val rejectTapPoint: Pair<Float, Float>?,
    val secondaryRejectTapPoint: Pair<Float, Float>?,
    val rejectTapTargets: List<Pair<Float, Float>>,
    val hasNaoAfetaTaPill: Boolean,
    val acceptNode: AccessibilityNodeInfo?,
    val acceptTapPoint: Pair<Float, Float>?,
    val continueNode: AccessibilityNodeInfo?,
    val continueTapPoint: Pair<Float, Float>?,
    val fingerprint: String,
    val appSource: String = "99",
    val hasMultipleStops: Boolean = false,
    val stopsCount: Int = 0,
    val isCancelledScreen: Boolean = false,
    val isIdleHomeScreen: Boolean = false,
    /** Quando o preco nao foi lido: R$/hora do card x minutos estimados (so para o painel de Ganhos). */
    val estimatedPrice: Double? = null,
    /** Minutos lidos nos trechos do card ("6 min (2,7 km)"), sem correcao. */
    val pickupMinutes: Double? = null,
    val dropoffMinutes: Double? = null,
    /** Corrida "Negocia" da 99: o passageiro propos o valor e o motorista pode pedir mais. */
    val isNegotiable: Boolean = false,
    /** Valores sugeridos pela 99 para a contraproposta (ex: R$22,19 / R$23,24 / R$24,30). */
    val counterOfferPrices: List<Double> = emptyList(),
    /** Onde fica o lapis da Negocia (4o botao da fileira de valores sugeridos). */
    val counterOfferPencilTap: Pair<Float, Float>? = null,
    /** Tela "Sua oferta" (lapis da Negocia aberto, teclado na tela). */
    val isCounterOfferEditor: Boolean = false,
    /** Valor que aparece no campo da tela "Sua oferta" (pode ser so a dica cinza com o valor do passageiro). */
    val editorValue: Double? = null,
    val editorPlusTap: Pair<Float, Float>? = null,
    val editorConfirmTap: Pair<Float, Float>? = null
)

/**
 * Extrai ofertas (em espera ou em sequencia durante navegacao GPS), botao "Continuar aceitando solicitacoes",
 * tela de 5 estrelas ("Como foi sua corrida com Camila?"), etiqueta "Area de risco", R$/km, R$/h, KM total,
 * nota, enderecos limpos e localiza o botao de fechar ("X" circular ou "X Nao afeta a TA") combinando:
 * 1) Deteccao visual direta nos pixels do Bitmap (centro exato do circulo escuro com X branco em 6.3% ou 37.8%)
 * 2) BoundingBox do OCR quando o texto "Nao afeta a TA" esta visivel
 * 3) Lista multi-alvo (varredura topo + meio) para fechar a janela em 100% dos formatos!
 */
object RideOfferParser {

    private val acceptHints = listOf("aceitar", "aceitar corrida", "accept", "confirmar")
    private val continueHints = listOf(
        "continuar aceitando solicitacoes",
        "varias solicitacoes seguidas recusadas"
    )
    internal val COMMON_OWN_OVERLAY_MARKERS = listOf(
        "fora da area",
        "fora dos criterios",
        "dentro da area",
        "corrida entre zonas",
        "recusando",
        "recusa off",
        "aceitando",
        "voce decide",
        "aguardando leitura",
        "continuando online",
        "favoritar ",
        "salvo nos favoritos",
        "embarque fora:",
        "destino fora:",
        "embarque e destino na area",
        "auto-recusa off",
        "zona proibida",
        "km total",
        "alerta de favorito",
        "/km •",
        "de gasolina",
        "de etanol",
        "trava liberada",
        "aceitar se tocar",
        "aceito se tocar",
        "voce liberou",
        "depois da 99",
        "depois da uber",
        "encaixe apos",
        "de gnv",
        "de diesel",
        "de energia",
        "lucro r$",
        "historico recente",
        "print silencioso",
        "limpar imagens",
        "prints salvos",
        "toque no card",
        "toque neste card",
        "emb:",
        "dest:",
        "bloqueado:",
        "permitida ",
        // card da Negocia ("Negocie: peca 24,30", "VOCE NEGOCIA", "NEGOCIANDO") - nunca ler como texto da 99
        "negocie",
        "voce negocia",
        "negociando"
    )
    private val ownOverlayMarkers = COMMON_OWN_OVERLAY_MARKERS
    private val ratingScreenHints = listOf(
        "como foi sua corrida",
        "como foi a corrida",
        "avaliar como anonimo",
        "avaliacao anonima",
        "seu feedback sincero",
        "pode ajudar outros motoristas",
        "avalie",
        "avaliar passageiro",
        "enviar avaliacao",
        "deixe um elogio"
    )
    private val activeRideHints = listOf(
        "cheguei",
        "iniciar corrida",
        "finalizar corrida",
        "ir para o passageiro",
        "em viagem",
        "chegada prevista",
        "a caminho do passageiro",
        "aguardando passageiro",
        // 99 (18/11): card logo depois do aceite e painel "Mais" da corrida em andamento (prints 59133-59135)
        "corrida aceita",
        "cancelar corrida",
        "recusar novas corridas",
        // fim da viagem: "Vanessa chegou ao seu destino?"
        "chegou ao seu destino"
    )
    private val activeRideNegations = listOf(
        "outro motorista",
        "outro parceiro",
        "por outro",
        "mais disponivel",
        "indisponivel"
    )
    private val cancelledHints = listOf(
        "usuario cancelou",
        "passageiro cancelou",
        "corrida cancelada",
        "viagem cancelada",
        "solicitacao cancelada",
        // Cancelamento feito pelo MOTORISTA (17/11 20:36: "Voce cancelou a corrida antes de chegar ao local de embarque")
        "voce cancelou a corrida",
        "voce cancelou a viagem",
        "corrida foi cancelada",
        "viagem foi cancelada"
    )
    private val nineNineIdleHomeHints = listOf(
        "recompensas",
        "ganhos desta",
        "valor da ultima",
        "entrega moto",
        "ta:",
        "tf:",
        "area de alta demanda"
    )

    private val currencyRegex = Regex("""(r\$|rs|\$)\s*(\d+([.,]\d{1,2})?)(\s*/\s*h(ora)?)?""", RegexOption.IGNORE_CASE)
    private val hourlyRateRegex = Regex("""(r\$|rs|\$)\s*(\d+([.,]\d{1,2})?)\s*/\s*h(?:ora)?(?:\b|$)""", RegexOption.IGNORE_CASE)
    private val distanceOrTimeRegex = Regex("""\b\d+([.,]\d+)?\s*(km|m|min|h)\b""", RegexOption.IGNORE_CASE)
    private val navBannerDistRegex = Regex("""^\d+([.,]\d+)?\s*(m|km)$""", RegexOption.IGNORE_CASE)
    private val legDistRegex = Regex("""\(\s*(\d+([.,]\d+)?)\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)
    private val legMinutesRegex = Regex("""\b(\d+)\s*min(?:uto[s]?)?\b""", RegexOption.IGNORE_CASE)
    private val minutesOnlyRegex = Regex("""^\s*(\d{1,3})\s*min(?:uto[s]?)?\s*$""", RegexOption.IGNORE_CASE)
    private val distanceOnlyRegex = Regex("""^\s*\(?\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)?\s*$""", RegexOption.IGNORE_CASE)
    private val surgeRegex = Regex("""\b\d+([.,]\d+)?\s*x\b""", RegexOption.IGNORE_CASE)
    private val ratingRegex = Regex("""\b([345][.,]\d{2})\b""")
    private val tripLegHeaderRegex = Regex(""".*\b\d+\s*min(?:uto[s]?)?\s*\(.*""", RegexOption.IGNORE_CASE)
    private val riskBadgeCleanRegex = Regex("""([o0!•]\s*)?\b[aá]rea\s+de\s+risco\b""", RegexOption.IGNORE_CASE)
    private val passengerNameRegex = Regex("""como\s+foi\s+(?:sua\s+|a\s+)?corrida\s+com\s+([^?.\n!]+)""", RegexOption.IGNORE_CASE)
    private val uberEstBannerRegex = Regex("""\best\.?\s*(?:de\s*)?(?:r\$|rs|\$)""", RegexOption.IGNORE_CASE)
    private val uberRatingWithCountRegex = Regex("""\b[345][.,]\d{2}\s*\(\s*\d+\s*\)""")
    private val nineNineLegCountRegex = Regex("""\b\d+\s*corridas?\b""", RegexOption.IGNORE_CASE)

    fun parseFromOcr(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        rejectHints: List<String>,
        strictSingleApp: Boolean = false
    ): ParsedRideOffer {
        val sortedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { it.text to it.boundingBox }

        return parseFromOrderedLines(
            sortedLines = sortedLines,
            bitmap = bitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rejectHints = rejectHints,
            strictSingleApp = strictSingleApp
        )
    }

    /** Titulo do aviso da 99, tolerante a ruido de OCR ("SeaUIdas", "solicitacÕes"). */
    internal fun isContinuePromptTitle(norm: String): Boolean =
        norm.contains("solicitac") && norm.contains("recusadas") &&
            (norm.contains("varias") || norm.contains("seguidas"))

    /** Botao "Continuar aceitando solicitacoes" (OCR as vezes corta o fim da linha). */
    internal fun isContinueButtonText(norm: String): Boolean =
        norm.contains("continuar aceitando") ||
            (norm.startsWith("continuar") && norm.contains("solicitac"))

    internal fun parseFromOrderedLines(
        sortedLines: List<Pair<String, Rect?>>,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        rejectHints: List<String>,
        strictSingleApp: Boolean = false
    ): ParsedRideOffer {
        val normalizedRejectHints = rejectHints.map { TextNormalizer.normalize(it) }

        val texts = mutableListOf<String>()
        var hasAcceptButton = false
        var hasPriceOrDistance = false
        var hasNaoAfetaTa = false
        var naoAfetaBox: Rect? = null
        var hasTopNavigationText = false
        var ocrAcceptCenter: Pair<Float, Float>? = null
        var ocrContinueCenter: Pair<Float, Float>? = null
        var continueDetected = false
        var hasPreFilteredUberBanner = false
        // Negocia: botao "Aceitar por R$X", fileira de valores sugeridos (lapis no fim) e a tela "Sua oferta"
        var acceptByBox: Rect? = null
        var counterRowBox: Rect? = null
        var editorPlusBox: Rect? = null
        var editorConfirmBox: Rect? = null
        val topTexts = mutableListOf<String>()

        for ((rawText, box) in sortedLines) {
            val raw = rawText.trim()
            if (raw.isEmpty()) continue

            val norm = TextNormalizer.normalize(raw)
            if ((box == null || box.top >= screenHeight * 0.30f) &&
                (uberEstBannerRegex.containsMatchIn(norm) || uberRatingWithCountRegex.containsMatchIn(raw))
            ) {
                hasPreFilteredUberBanner = true
            }
            // Aviso "Varias solicitacoes seguidas recusadas" / botao "Continuar aceitando solicitacoes":
            // checado ANTES do filtro do nosso card (o marcador "aceitando" do selo ACEITANDO descartava
            // a linha do botao) e antes do corte de topo (o titulo do aviso fica em ~11% da altura).
            if (isContinuePromptTitle(norm)) {
                continueDetected = true
                continue
            }
            if (isContinueButtonText(norm) && (box == null || box.top > screenHeight * 0.55f)) {
                continueDetected = true
                if (box != null) {
                    ocrContinueCenter = box.exactCenterX() to box.exactCenterY()
                }
                continue
            }
            if (raw.contains("✔") ||
                raw.contains("✘") ||
                norm.contains("/km") ||
                norm.contains("xrecorder") ||
                norm.contains("gravado por") ||
                ownOverlayMarkers.any { norm.contains(it) } ||
                norm == "on" ||
                norm == "pausa"
            ) {
                continue
            }

            // 1. Detecta se existe a caixa preta de navegacao GPS no topo (ex: "289 m", "43 m")
            if (box != null && box.top < screenHeight * 0.22f && navBannerDistRegex.matches(norm)) {
                hasTopNavigationText = true
            }

            // 2. Detecta a pilula "X Nao afeta a TA" em qualquer altura (topo 6.3% ou meio 37.8%)
            if (norm.contains("nao afeta") || norm.contains("afeta a ta")) {
                hasNaoAfetaTa = true
                if (box != null) {
                    naoAfetaBox = box
                }
            }

            // 3. Ignora o topo da tela (< 30%) para leitura de precos/notas/enderecos do card:
            // Isso impede 100% que o OCR leia o botao de saldo "R$ 0,00", nosso proprio card de status no topo
            // ou o nome da rua da barra preta de navegacao GPS como se fossem dados da corrida!
            if (box != null && box.top < screenHeight * 0.30f) {
                // O topo nao entra na leitura da oferta, mas as telas de fim ficam la em cima
                // ("Voce cancelou a corrida" a 8% da altura): guarda so para reconhecer a etapa
                topTexts.add(raw)
                continue
            }

            texts.add(raw)

            if (box != null) {
                if (acceptByBox == null && norm.startsWith("aceitar por")) {
                    acceptByBox = box
                } else if (acceptByBox != null && counterRowBox == null && box.top >= acceptByBox.bottom &&
                    currencyRegex.containsMatchIn(raw)
                ) {
                    counterRowBox = box
                }
                if (editorPlusBox == null && editorStepPlusRegex.matches(norm)) editorPlusBox = box
                if (editorConfirmBox == null && norm.contains("confirmar oferta")) editorConfirmBox = box
            }

            if (currencyRegex.containsMatchIn(raw) || distanceOrTimeRegex.containsMatchIn(raw)) {
                hasPriceOrDistance = true
            }

            if (acceptHints.any { norm == it || norm.startsWith("aceitar") } && !norm.contains("continuar")) {
                if (box == null || box.top > screenHeight * 0.68f) {
                    hasAcceptButton = true
                    if (box != null && ocrAcceptCenter == null) {
                        ocrAcceptCenter = box.exactCenterX() to box.exactCenterY()
                    }
                }
            }
        }

        // Calcula O UNICO ponto exato de toque para fechar a oferta (sem cliques extras que atinjam a tela de fundo!)
        val rejectTargets = computeRobustRejectTargets(
            bitmap = bitmap,
            w = screenWidth,
            h = screenHeight,
            naoAfetaBox = naoAfetaBox,
            hasTopNavText = hasTopNavigationText
        )

        val primaryRejectTap = rejectTargets.firstOrNull() ?: ((screenWidth * 0.922f) to (screenHeight * 0.063f))
        val secondaryRejectTap = null
        val fallbackAcceptTap = (screenWidth * 0.50f) to (screenHeight * 0.89f)
        val fallbackContinueTap = (screenWidth * 0.50f) to (screenHeight * 0.835f)

        val result = buildOfferResult(
            texts = texts,
            hasAcceptButton = hasAcceptButton,
            hasPriceOrDistance = hasPriceOrDistance,
            continueDetected = continueDetected,
            hasNaoAfetaTa = hasNaoAfetaTa,
            hasPreFilteredUberBanner = hasPreFilteredUberBanner,
            strictSingleApp = strictSingleApp,
            normalizedRejectHints = normalizedRejectHints,
            rejectNode = null,
            rejectTapPoint = primaryRejectTap,
            secondaryRejectTapPoint = secondaryRejectTap,
            rejectTapTargets = rejectTargets,
            acceptNode = null,
            acceptTapPoint = ocrAcceptCenter ?: fallbackAcceptTap,
            continueNode = null,
            continueTapPoint = ocrContinueCenter ?: fallbackContinueTap,
            topTexts = topTexts
        )

        return when {
            result.isCounterOfferEditor -> result.copy(
                editorValue = readEditorValue(texts),
                editorPlusTap = editorPlusBox?.let { centerOf(it) },
                editorConfirmTap = editorConfirmBox?.let { centerOf(it) }
            )
            // Lapis = 4o botao da fileira de valores (prints 59192/59196: X = 87,7% da largura).
            // Sem a fileira lida, ela fica 6,3% da altura abaixo do centro do "Aceitar por R$X".
            result.isNegotiable -> {
                val row = counterRowBox
                val acc = acceptByBox
                val pencil = when {
                    row != null -> (screenWidth * PENCIL_X_FRACTION) to ((row.top + row.bottom) / 2f)
                    acc != null -> (screenWidth * PENCIL_X_FRACTION) to ((acc.top + acc.bottom) / 2f + screenHeight * 0.063f)
                    else -> null
                }
                result.copy(counterOfferPencilTap = pencil?.takeIf { it.second < screenHeight * 0.97f })
            }
            else -> result
        }
    }

    internal const val PENCIL_X_FRACTION = 0.877f

    // "+0,5" da tela "Sua oferta" (o OCR as vezes le "+O,5")
    private val editorStepPlusRegex = Regex("""^\+\s*[0oO]\s*[,.]\s*5$""")
    // Campo do valor: "R$23,9", "R$ 23,60", "R$|23,60" (cursor) ou so "23,60"
    private val editorFieldRegex = Regex(
        """^\s*(?:(?:r\$|rs|\$)\s*)?[|lI!]?\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*[|lI!]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private fun centerOf(box: Rect): Pair<Float, Float> = ((box.left + box.right) / 2f) to ((box.top + box.bottom) / 2f)

    /** Valor do campo da tela "Sua oferta": a linha so com o valor, entre o titulo e "Confirmar oferta". */
    internal fun readEditorValue(texts: List<String>): Double? {
        val norms = texts.map { TextNormalizer.normalize(it) }
        val start = norms.indexOfFirst { it.startsWith("sua oferta") || it.contains("oferta feita pelo passageiro") }
        if (start < 0) return null
        val end = norms.indexOfFirst { it.contains("confirmar oferta") }.let { if (it < 0) texts.size else it }
        for (i in (start + 1) until end) {
            val line = fixCurrencyOcr(texts[i]).trim()
            if (line.startsWith("+") || line.startsWith("-") || line.startsWith("–")) continue
            val m = editorFieldRegex.find(line) ?: continue
            val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: continue
            if (v >= 2.0) return v
        }
        return null
    }

    /**
     * Inspeciona os pixels reais do Bitmap + OCR para determinar O UNICO ponto cirurgico de fechamento:
     * - Sem barra preta do GPS no topo (modo espera): X = 92.2%, Y = 6.3% (jamais toca em 62.8% onde fica o saldo R$ 0,00
     *   e jamais toca no meio da tela apos o card fechar).
     * - Com barra preta do GPS no topo (em corrida ativa): X = 91.5%, Y = 37.8% acima do card (jamais toca em 6.3% no topo
     *   para NUNCA puxar/descer a barra preta de navegacao GPS!).
     */
    private fun computeRobustRejectTargets(
        bitmap: Bitmap?,
        w: Int,
        h: Int,
        naoAfetaBox: Rect?,
        hasTopNavText: Boolean
    ): List<Pair<Float, Float>> {
        var topCircleX = w * 0.922f
        var topCircleY = h * 0.063f
        var midCircleX = w * 0.915f
        var midCircleY = h * 0.378f
        var isGpsNavTopBar = hasTopNavText

        var topCircleFound: Pair<Float, Float>? = null
        var midCircleFound: Pair<Float, Float>? = null

        if (bitmap != null && !bitmap.isRecycled && bitmap.width == w && bitmap.height == h) {
            // 1. Verifica se o topo da tela (x: 10%..45%, y: 5%..11%) tem a placa preta do GPS
            var darkTopPixels = 0
            var totalTopSampled = 0
            val yStartTop = (h * 0.05f).toInt().coerceIn(0, h - 1)
            val yEndTop = (h * 0.11f).toInt().coerceIn(0, h - 1)
            val xStartTop = (w * 0.10f).toInt().coerceIn(0, w - 1)
            val xEndTop = (w * 0.45f).toInt().coerceIn(0, w - 1)

            var y = yStartTop
            while (y < yEndTop) {
                var x = xStartTop
                while (x < xEndTop) {
                    val c = bitmap.getPixel(x, y)
                    totalTopSampled++
                    if (Color.red(c) < 45 && Color.green(c) < 45 && Color.blue(c) < 45) {
                        darkTopPixels++
                    }
                    x += 6
                }
                y += 6
            }
            if (totalTopSampled > 0) {
                val ratio = darkTopPixels.toFloat() / totalTopSampled.toFloat()
                isGpsNavTopBar = ratio > 0.45f || hasTopNavText
            }

            // 2. Se NAO ha placa preta do GPS no topo, procura o circulo escuro X no canto superior direito
            if (!isGpsNavTopBar) {
                topCircleFound = findDarkButtonCentroid(
                    bitmap = bitmap,
                    xMin = (w * 0.86f).toInt(),
                    xMax = (w * 0.975f).toInt(),
                    yMin = (h * 0.035f).toInt(),
                    yMax = (h * 0.095f).toInt()
                )
                if (topCircleFound != null) {
                    topCircleX = topCircleFound.first
                    topCircleY = topCircleFound.second
                }
            }

            // 3. Se ha placa GPS OU se o X do topo nao foi encontrado (card popup com altura variavel por endereco),
            // detecta a borda superior real do card escuro da 99 e procura o botao X / pilula logo acima dele (28%..50%)!
            if (isGpsNavTopBar || naoAfetaBox != null || topCircleFound == null) {
                val detectedCardTop = findNineNineDarkCardTopEdge(bitmap, w, h)
                val bandMinY = if (detectedCardTop != null) {
                    (detectedCardTop - h * 0.085f).coerceIn(h * 0.25f, h * 0.48f).toInt()
                } else {
                    (h * 0.29f).toInt()
                }
                val bandMaxY = if (detectedCardTop != null) {
                    (detectedCardTop - h * 0.004f).coerceIn(h * 0.28f, h * 0.52f).toInt()
                } else {
                    (h * 0.48f).toInt()
                }
                midCircleFound = findDarkButtonCentroid(
                    bitmap = bitmap,
                    xMin = (w * 0.84f).toInt(),
                    xMax = (w * 0.975f).toInt(),
                    yMin = bandMinY,
                    yMax = bandMaxY
                ) ?: findDarkButtonCentroid(
                    bitmap = bitmap,
                    xMin = (w * 0.85f).toInt(),
                    xMax = (w * 0.975f).toInt(),
                    yMin = (h * 0.28f).toInt(),
                    yMax = (h * 0.49f).toInt()
                )
                if (midCircleFound != null) {
                    midCircleX = midCircleFound.first
                    midCircleY = midCircleFound.second
                } else if (detectedCardTop != null) {
                    midCircleY = (detectedCardTop - h * 0.030f).coerceIn(h * 0.29f, h * 0.47f)
                }
            }
        }

        // Se o OCR leu diretamente "Nao afeta a TA", usa a altura Y exata da pilula, sempre na direita segura
        if (naoAfetaBox != null) {
            val exactY = naoAfetaBox.exactCenterY()
            val safeRightX = if (exactY > h * 0.20f) midCircleX else topCircleX
            return listOf(safeRightX to exactY)
        }

        if (!isGpsNavTopBar) {
            if (topCircleFound != null) {
                return listOf(topCircleX to topCircleY)
            }
            if (midCircleFound != null) {
                return listOf(midCircleX to midCircleY)
            }
            return listOf(topCircleX to topCircleY)
        }

        return listOf(midCircleX to midCircleY)
    }

    /**
     * Detecta dinamicamente a borda superior do card escuro da 99 (#242830..#3A3F4A),
     * que sobe/desce conforme a quantidade de linhas do endereco (ex: 58407 vs 58409).
     */
    private fun findNineNineDarkCardTopEdge(bitmap: Bitmap, w: Int, h: Int): Float? {
        return try {
            val sampleXs = listOf(0.18f, 0.25f, 0.32f, 0.40f).map { (w * it).toInt().coerceIn(0, w - 1) }
            val startY = (h * 0.54f).toInt().coerceIn(0, h - 1)
            val endY = (h * 0.26f).toInt().coerceIn(0, h - 1)
            if (startY <= endY) return null

            fun isNineNineDarkCard(c: Int): Boolean {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                return r in 26..75 && g in 28..80 && b in 34..90 && (maxC - minC) <= 24
            }

            // Confirma que a amostra inferior esta dentro do card escuro da 99
            val insideDarkCount = sampleXs.count { x -> isNineNineDarkCard(bitmap.getPixel(x, startY)) }
            if (insideDarkCount < 3) return null

            var y = startY
            while (y >= endY) {
                val nonDarkCount = sampleXs.count { x ->
                    val c = bitmap.getPixel(x, y)
                    val brightness = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                    brightness >= 115 || !isNineNineDarkCard(c)
                }
                if (nonDarkCount >= 3) {
                    val verifyAbove = (y - (h * 0.012f).toInt()).coerceAtLeast(endY)
                    val stillNonDark = sampleXs.count { x ->
                        val c = bitmap.getPixel(x, verifyAbove)
                        (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 >= 115 || !isNineNineDarkCard(c)
                    }
                    if (stillNonDark >= 3) {
                        return y.toFloat()
                    }
                }
                y -= 4
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encontra o centro geometrico (centroide) dos pixels cinza-escuro/preto (#22252A) do botao X da 99.
     */
    private fun findDarkButtonCentroid(
        bitmap: Bitmap,
        xMin: Int,
        xMax: Int,
        yMin: Int,
        yMax: Int
    ): Pair<Float, Float>? {
        val w = bitmap.width
        val h = bitmap.height
        val x0 = xMin.coerceIn(0, w - 1)
        val x1 = xMax.coerceIn(0, w - 1)
        val y0 = yMin.coerceIn(0, h - 1)
        val y1 = yMax.coerceIn(0, h - 1)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // Cor exata do circulo/pilula escura da 99 (#22252A -> R:20..58, G:20..60, B:24..66)
                if (r in 20..58 && g in 20..60 && b in 24..66 && abs(r - g) <= 10) {
                    sumX += x
                    sumY += y
                    count++
                }
                x += 3
            }
            y += 3
        }

        // Precisa de pelo menos 45 pixels amostrados para confirmar que o botao escuro esta ali
        if (count >= 45) {
            return (sumX.toFloat() / count) to (sumY.toFloat() / count)
        }
        return null
    }

    fun parse(root: AccessibilityNodeInfo?, rejectHints: List<String>): ParsedRideOffer {
        if (root == null) {
            return emptyOffer()
        }

        val texts = mutableListOf<String>()
        var hasAcceptButton = false
        var hasPriceOrDistance = false
        var continueDetected = false
        var rejectCandidate: AccessibilityNodeInfo? = null
        var acceptCandidate: AccessibilityNodeInfo? = null
        var continueCandidate: AccessibilityNodeInfo? = null

        val normalizedRejectHints = rejectHints.map { TextNormalizer.normalize(it) }

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return

            val rawText = node.text?.toString()?.trim().orEmpty()
            val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = if (rawText.isNotEmpty()) rawText else rawDesc

            if (combined.isNotEmpty()) {
                texts.add(combined)
                if (currencyRegex.containsMatchIn(combined) || distanceOrTimeRegex.containsMatchIn(combined)) {
                    hasPriceOrDistance = true
                }
            }

            val normText = TextNormalizer.normalize(rawText)
            val normDesc = TextNormalizer.normalize(rawDesc)

            if (continueHints.any { normText.contains(it) || normDesc.contains(it) }) {
                continueDetected = true
                if (continueCandidate == null && (normText.contains("continuar") || normDesc.contains("continuar"))) {
                    continueCandidate = findClickableSelfOrParent(node)
                }
            }

            if (acceptHints.any { normText == it || normDesc == it || normText.startsWith("aceitar ") || normDesc.startsWith("aceitar ") } &&
                !normText.contains("continuar")
            ) {
                hasAcceptButton = true
                if (acceptCandidate == null) {
                    acceptCandidate = findClickableSelfOrParent(node)
                }
            }

            if (rejectCandidate == null && isRejectNode(normText, normDesc, normalizedRejectHints)) {
                rejectCandidate = findClickableSelfOrParent(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)

        if (hasAcceptButton && rejectCandidate == null) {
            rejectCandidate = findTopRightCloseButton(root)
        }

        return buildOfferResult(
            texts = texts,
            hasAcceptButton = hasAcceptButton,
            hasPriceOrDistance = hasPriceOrDistance,
            continueDetected = continueDetected,
            hasNaoAfetaTa = false,
            hasPreFilteredUberBanner = false,
            strictSingleApp = false,
            normalizedRejectHints = normalizedRejectHints,
            rejectNode = rejectCandidate,
            rejectTapPoint = null,
            secondaryRejectTapPoint = null,
            rejectTapTargets = emptyList(),
            acceptNode = acceptCandidate,
            acceptTapPoint = null,
            continueNode = continueCandidate,
            continueTapPoint = null
        )
    }

    private fun buildOfferResult(
        texts: List<String>,
        hasAcceptButton: Boolean,
        hasPriceOrDistance: Boolean,
        continueDetected: Boolean,
        hasNaoAfetaTa: Boolean,
        hasPreFilteredUberBanner: Boolean,
        strictSingleApp: Boolean,
        normalizedRejectHints: List<String>,
        rejectNode: AccessibilityNodeInfo?,
        rejectTapPoint: Pair<Float, Float>?,
        secondaryRejectTapPoint: Pair<Float, Float>?,
        rejectTapTargets: List<Pair<Float, Float>>,
        acceptNode: AccessibilityNodeInfo?,
        acceptTapPoint: Pair<Float, Float>?,
        continueNode: AccessibilityNodeInfo?,
        continueTapPoint: Pair<Float, Float>?,
        topTexts: List<String> = emptyList()
    ): ParsedRideOffer {
        val legs = extractLegsInfo(texts)
        val pickup = legs.pickup
        val dropoff = legs.dropoff
        val pickupDistKm = legs.pickupDistKm
        val dropoffDistKm = legs.dropoffDistKm
        val pickupMinutes = legs.pickupMinutes
        val dropoffMinutes = legs.dropoffMinutes
        val hasRiskBadge = legs.hasRiskBadge

        val (rating, isNewPassenger) = extractPassengerRating(texts)
        val rawCardHourlyRate = extractHourlyRate(texts)
        val rawPrice = extractMainRidePriceInfo(texts)
        val initialPrice = rawPrice?.first

        // KM Total = KM de Buscar (pickupDistKm) + KM de Levar (dropoffDistKm)
        val totalDistKm = if (pickupDistKm != null && dropoffDistKm != null) {
            pickupDistKm + dropoffDistKm
        } else {
            dropoffDistKm ?: pickupDistKm
        }

        // Calculo Real de R$/h com piso fisico minimo por KM (evita "8 min para 12 km" ou R$/h < 8 lido errado)
        val waitMinutes = 2.0
        val sanitizedRawHourly = rawCardHourlyRate?.takeIf { it in 10.0..250.0 }
        // Descarta minutos obviamente corrompidos pelo OCR (ex: "2 min" lido como "200 min" para 1,6 km -> R$2/h)
        val validPickupMin = pickupMinutes?.takeIf { mins ->
            val maxReasonable = kotlin.math.max(35.0, (pickupDistKm ?: 2.0) * 12.0)
            mins in 0.5..maxReasonable
        }
        val validDropoffMin = dropoffMinutes?.takeIf { mins ->
            val maxReasonable = kotlin.math.max(70.0, (dropoffDistKm ?: 4.0) * 12.0)
            mins in 0.5..maxReasonable
        }
        val effPickupMin = when {
            validPickupMin != null && pickupDistKm != null -> kotlin.math.max(validPickupMin, pickupDistKm * 1.4)
            validPickupMin != null -> validPickupMin
            pickupDistKm != null && sanitizedRawHourly == null -> pickupDistKm * 1.8
            else -> null
        }
        val effDropoffMin = when {
            validDropoffMin != null && dropoffDistKm != null -> kotlin.math.max(validDropoffMin, dropoffDistKm * 1.4)
            validDropoffMin != null -> validDropoffMin
            dropoffDistKm != null && sanitizedRawHourly == null -> dropoffDistKm * 1.8
            else -> null
        }

        // Corrige R$ sem virgula quando o OCR perde o separador decimal ("R$ 5,40" -> "R$ 540")
        val estMinutesForPriceCheck = when {
            effPickupMin != null && effDropoffMin != null -> effPickupMin + effDropoffMin + waitMinutes
            totalDistKm != null && totalDistKm > 0.2 -> totalDistKm * 2.2 + waitMinutes
            else -> null
        }
        val expectedPrice = if (sanitizedRawHourly != null && estMinutesForPriceCheck != null) {
            sanitizedRawHourly * (estMinutesForPriceCheck / 60.0)
        } else {
            null
        }
        val ridePrice = recoverMissingDecimalPrice(
            rawValue = initialPrice,
            hasDecimal = rawPrice?.second ?: true,
            totalDistKm = totalDistKm,
            expectedPrice = expectedPrice
        )

        val pricePerKm = if (ridePrice != null && totalDistKm != null && totalDistKm > 0.1) {
            ridePrice / totalDistKm
        } else {
            null
        }

        val hourlyRate = if (ridePrice != null && ridePrice > 0.0) {
            when {
                effPickupMin != null && effDropoffMin != null && (effPickupMin + effDropoffMin) > 0.0 -> {
                    val totalMinWithWait = effPickupMin + effDropoffMin + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                sanitizedRawHourly != null -> {
                    val baseMin = (ridePrice / sanitizedRawHourly) * 60.0
                    val totalMinWithWait = baseMin + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                totalDistKm != null && totalDistKm > 0.1 -> {
                    val estDrivingMin = totalDistKm * 2.4 // ~25 km/h media urbana (buscar + levar)
                    val totalMinWithWait = estDrivingMin + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                else -> sanitizedRawHourly
            }
        } else {
            sanitizedRawHourly
        }

        val normAll = texts.map { TextNormalizer.normalize(it) }
        val isTripRadarList = normAll.any {
            it == "escolher" || it.contains("escolha uma") || it.contains("99 abastece")
        }
        val hasStrongNineNineEvidence = normAll.any {
            it == "escolher" ||
                it.contains("100% dos ganhos") ||
                it.contains("nao afeta a ta") ||
                it.contains("taxa99") ||
                it.contains("taxa 99") ||
                it.contains("/hora") ||
                it.contains("tarifa base dinamica") ||
                it.contains("perfil essencial") ||
                it.contains("cpf e cartao") ||
                nineNineLegCountRegex.containsMatchIn(it)
        }
        val hasExclusivoBadge = normAll.any { it == "exclusivo" }
        val hasVerificadoBadge = normAll.any { it == "verificado" }
        val hasUberExclusiveMarker = !hasStrongNineNineEvidence &&
            (hasPreFilteredUberBanner ||
                normAll.any { line ->
                    uberEstBannerRegex.containsMatchIn(line) || line == "tenho interesse"
                } || (!strictSingleApp && hasExclusivoBadge && hasVerificadoBadge))
        val hasRealCardEvidence = (pickup != null && dropoff != null) ||
            (sanitizedRawHourly != null && ridePrice != null && pickup != null)
        val hasAcceptedOrActiveBanner = normAll.any {
            it.contains("corrida aceita") || it.contains("cheguei no embarque") ||
                it.contains("finalizar corrida") || it.contains("iniciar corrida")
        }
        // Corrida "Negocia" da 99: botao "Aceitar por R$X" e, embaixo dele, os valores sugeridos
        // para pedir mais ("R$22,19 R$23,24" + "R$24,30"; o lapis abre o teclado para digitar outro).
        val acceptByIdx = normAll.indexOfFirst { it.startsWith("aceitar por") }
        val isNegotiable = acceptByIdx >= 0 || normAll.any { it.startsWith("moto negocia") }
        val counterOfferPrices = if (acceptByIdx >= 0) {
            texts.drop(acceptByIdx + 1)
                .flatMap { line ->
                    currencyRegex.findAll(fixCurrencyOcr(line))
                        .mapNotNull { it.groupValues[2].replace(",", ".").toDoubleOrNull() }
                        .toList()
                }
                .filter { v -> ridePrice == null || (v > ridePrice + 0.009 && v <= ridePrice * 2.0) }
                .distinct()
                .sorted()
                .take(3)
        } else {
            emptyList()
        }
        // Tela "Sua oferta / Confirmar oferta" (lapis da Negocia, teclado aberto): nunca e oferta para tocar
        val isCounterOfferEditor = normAll.any {
            it.contains("confirmar oferta") || it.startsWith("sua oferta") || it.contains("oferta feita pelo passageiro")
        }
        val isOffer = !hasAcceptedOrActiveBanner &&
            !isCounterOfferEditor &&
            hasAcceptButton &&
            hasRealCardEvidence &&
            !isTripRadarList &&
            !hasUberExclusiveMarker &&
            hasPriceOrDistance

        // Etapas da corrida olham tambem o topo da tela (titulos de "cancelada" / avaliacao / em viagem)
        val normStage = normAll + topTexts.map { TextNormalizer.normalize(it) }
        val isRatingScreen = !isOffer && !continueDetected && normStage.any { line ->
            ratingScreenHints.any { hint -> line.contains(hint) }
        }
        val ratingPassengerName = if (isRatingScreen) {
            extractRatingPassengerName(texts + topTexts)
        } else {
            null
        }

        // "OK, corrida cancelada" fica fixo no mapa da 99 durante a viagem (atalho de voz): nao e cancelamento
        val isCancelledScreen = !isOffer && !continueDetected && normStage.any { line ->
            val clean = TextNormalizer.withoutVoiceCancelShortcut(line)
            cancelledHints.any { hint -> clean.contains(hint) }
        }

        val isInActiveRide = !isOffer && !continueDetected && !isRatingScreen && !isCancelledScreen && normStage.any { line ->
            activeRideHints.any { hint -> line.contains(hint) } &&
                // "Corrida aceita por outro motorista" / "nao esta mais disponivel" nao e corrida sua
                activeRideNegations.none { line.contains(it) }
        }

        val isIdleHomeScreen = !isOffer &&
            !continueDetected &&
            !isRatingScreen &&
            !isCancelledScreen &&
            !isInActiveRide &&
            normAll.any { line -> nineNineIdleHomeHints.any { hint -> line.contains(hint) } }

        val addressCandidates = buildList {
            if (!dropoff.isNullOrBlank()) add(dropoff)
            if (!pickup.isNullOrBlank()) add(pickup)
            texts.distinct().forEach { line ->
                val norm = TextNormalizer.normalize(line)
                if (line.length >= 6 &&
                    line != dropoff &&
                    line != pickup &&
                    !acceptHints.any { norm == it } &&
                    !normalizedRejectHints.any { norm == it } &&
                    !currencyRegex.matches(line.trim())
                ) {
                    add(line)
                }
            }
        }

        val fingerprint = listOfNotNull(
            ridePrice?.toString(),
            rating?.toString(),
            if (isNewPassenger) "new" else null,
            pickup,
            dropoff
        ).joinToString("|").ifEmpty { texts.take(6).joinToString("|") }

        return ParsedRideOffer(
            isRideOffer = isOffer,
            isContinuePrompt = continueDetected,
            isRatingScreen = isRatingScreen,
            ratingPassengerName = ratingPassengerName,
            isInActiveRide = isInActiveRide,
            hasRiskAreaBadge = hasRiskBadge,
            allTexts = texts,
            pickupAddress = pickup,
            dropoffAddress = dropoff,
            ridePrice = ridePrice,
            pickupDistanceKm = pickupDistKm,
            dropoffDistanceKm = dropoffDistKm,
            totalDistanceKm = totalDistKm,
            pricePerKm = pricePerKm,
            hourlyRate = hourlyRate,
            passengerRating = rating,
            isNewPassenger = isNewPassenger,
            addressCandidates = addressCandidates,
            rejectNode = rejectNode,
            rejectTapPoint = rejectTapPoint,
            secondaryRejectTapPoint = secondaryRejectTapPoint,
            rejectTapTargets = rejectTapTargets,
            hasNaoAfetaTaPill = hasNaoAfetaTa,
            acceptNode = acceptNode,
            acceptTapPoint = acceptTapPoint,
            continueNode = continueNode,
            continueTapPoint = continueTapPoint,
            fingerprint = fingerprint,
            isCancelledScreen = isCancelledScreen,
            isIdleHomeScreen = isIdleHomeScreen,
            estimatedPrice = if (ridePrice == null) expectedPrice?.takeIf { it in 3.0..400.0 } else null,
            pickupMinutes = pickupMinutes,
            dropoffMinutes = dropoffMinutes,
            isNegotiable = isOffer && isNegotiable,
            counterOfferPrices = if (isOffer) counterOfferPrices else emptyList(),
            isCounterOfferEditor = isCounterOfferEditor && !continueDetected
        )
    }

    private fun extractRatingPassengerName(texts: List<String>): String? {
        for (line in texts) {
            val match = passengerNameRegex.find(line)
            if (match != null) {
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty() && name.length <= 30) {
                    return name
                }
            }
        }
        return null
    }

    private val decimalPartRegex = Regex("""[.,]\d{1,2}\b""")

    /**
     * Recupera o separador decimal perdido pelo OCR em valores grandes sem virgula/ponto
     * (ex: "R$ 750" quando Est. R$3,41/km * 2,2km = R$7,50; ou "R$ 540" quando 2,1km = R$5,40).
     */
    internal fun recoverMissingDecimalPrice(
        rawValue: Double?,
        hasDecimal: Boolean,
        totalDistKm: Double?,
        expectedPrice: Double?
    ): Double? {
        val price = rawValue ?: return null
        if (hasDecimal || price < 100.0) return price
        val divided = kotlin.math.round((price / 100.0) * 100.0) / 100.0
        if (expectedPrice != null && expectedPrice > 1.0) {
            if (price > expectedPrice * 4.5 && divided in (expectedPrice * 0.45)..(expectedPrice * 2.2)) {
                return divided
            }
        }
        if (totalDistKm != null && totalDistKm > 0.2) {
            val rawPerKm = price / totalDistKm
            val divPerKm = divided / totalDistKm
            if (rawPerKm > 30.0 && divPerKm in 0.45..15.0) {
                return divided
            }
        }
        return price
    }

    private val currencyOcrRegex = Regex("""((?:r\$|rs|\$)\s*)([0-9Oo]{1,4}(?:[.,][0-9Oo]{1,2})?)""", RegexOption.IGNORE_CASE)

    /** "R$ 5O,00" -> "R$ 50,00": O lido no lugar do zero dentro de um valor (so quando ha digitos junto). */
    internal fun fixCurrencyOcr(line: String): String = currencyOcrRegex.replace(line) { m ->
        val num = m.groupValues[2]
        if (num.any { it.isDigit() }) m.groupValues[1] + num.replace('O', '0').replace('o', '0') else m.value
    }

    private fun extractMainRidePriceInfo(texts: List<String>): Pair<Double, Boolean>? {
        for (rawLine in texts) {
            val line = fixCurrencyOcr(rawLine)
            val norm = TextNormalizer.normalize(line)
            if (norm.contains("/h") || norm.contains("tarifa") || norm.contains("taxa") ||
                norm.contains("liq") || norm.contains("lucro") || line.contains("•") || line.contains("★")
            ) {
                continue
            }
            val match = currencyRegex.find(line) ?: continue
            val valNum = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (valNum != null && valNum >= 3.0) {
                return valNum to decimalPartRegex.containsMatchIn(match.groupValues[2])
            }
        }
        return null
    }

    private fun extractHourlyRate(texts: List<String>): Double? {
        for (line in texts) {
            val match = hourlyRateRegex.find(line) ?: continue
            val value = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (value != null && value in 10.0..250.0) {
                return value
            }
        }
        return null
    }

    private fun extractPassengerRating(texts: List<String>): Pair<Double?, Boolean> {
        var isNew = false
        for (line in texts) {
            val norm = TextNormalizer.normalize(line)
            if (norm.contains("passageiro novo")) {
                isNew = true
            }
            val cleaned = line
                .replace(currencyRegex, " ")
                .replace(surgeRegex, " ")
                .replace(distanceOrTimeRegex, " ")

            val match = ratingRegex.find(cleaned)
            if (match != null) {
                val value = match.groupValues[1].replace(",", ".").toDoubleOrNull()
                if (value != null && value in 3.0..5.0) {
                    return value to isNew
                }
            }
        }
        return null to isNew
    }

    private data class LegsExtractResult(
        val pickup: String?,
        val dropoff: String?,
        val pickupDistKm: Double?,
        val dropoffDistKm: Double?,
        val pickupMinutes: Double?,
        val dropoffMinutes: Double?,
        val hasRiskBadge: Boolean
    )

    // Icone redondo do trecho (verde = embarque, laranja = destino) que o OCR le como letra/simbolo
    // logo antes dos minutos. Visual novo da 99 (18/11): "O6 min (2,7 km)", "O 4 min", "O6min".
    private val legIconGluedRegex = Regex("""^\s*[OoQ](?=\d{1,3}\s*min)""", RegexOption.IGNORE_CASE)
    private val legIconLooseRegex = Regex("""^\s*(?:[A-Za-z]|[^\w\s(]{1,2})\s+(?=\d{1,3}\s*min)""", RegexOption.IGNORE_CASE)

    /** Tira o icone do trecho lido como texto ("O6 min (2,7 km)" -> "6 min (2,7 km)"). */
    internal fun stripLegIconNoise(raw: String): String {
        var s = raw.trim()
        s = legIconGluedRegex.replace(s, "")
        s = legIconLooseRegex.replace(s, "")
        return s
    }

    private val legFiveAsSRegex = Regex("""^\s*[Ss]\s*(min(?:uto[s]?)?\s*\()""", RegexOption.IGNORE_CASE)
    // Minutos ilegiveis mas km lido ("J min (1,4 km)", "min (2,9 km)"): vira "0 min" = tempo desconhecido
    // (minutos 0 sao descartados no R$/h, que passa a usar o km). Sem isso a corrida inteira era ignorada.
    private val legUnreadableMinutesRegex = Regex("""^\s*(?:[A-Za-z]{1,2}\s*)?(min(?:uto[s]?)?\s*\(\s*\d)""", RegexOption.IGNORE_CASE)

    private fun normalizeLegOcrNoise(raw: String): String {
        var s = stripLegIconNoise(raw)
        // "S min (3,1 km)": o 5 lido como S (so quando e o trecho inteiro "N min (X km)")
        s = legFiveAsSRegex.replace(s) { m -> "5 ${m.groupValues[1]}" }
        s = legUnreadableMinutesRegex.replace(s) { m -> "0 ${m.groupValues[1]}" }
        s = s.replace(Regex("""(\d)\s*([.,])\s*(\d)"""), "$1$2$3")
        // Digitos trocados por letras parecidas antes de "min": I/l/|/! -> 1 e O/o -> 0 ("1O min", "l2 min")
        s = s.replace(Regex("""\b([0-9Il|!Oo]{1,3})\s*(min(?:uto[s]?)?)""", RegexOption.IGNORE_CASE)) { m ->
            val rawNum = m.groupValues[1]
            if (rawNum.any { it.isDigit() } || rawNum.all { it in "Il|!" }) {
                val digits = rawNum.replace(Regex("""[Il|!]"""), "1").replace(Regex("""[Oo]"""), "0")
                "$digits ${m.groupValues[2]}"
            } else {
                m.value
            }
        }
        s = s.replace(Regex("""\(\s*([0-9Il|!Oo]+(?:[.,][0-9Il|!Oo]+)?)\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)) { m ->
            val rawNum = m.groupValues[1]
            if (rawNum.any { it.isDigit() }) {
                val fixedNum = rawNum.replace(Regex("""[Il|!]"""), "1").replace(Regex("""[Oo]"""), "0")
                "($fixedNum ${m.groupValues[2]})"
            } else {
                m.value
            }
        }
        return s
    }

    private fun extractLegsInfo(texts: List<String>): LegsExtractResult {
        val legAddresses = mutableListOf<String?>()
        val legDistances = mutableListOf<Double?>()
        val legMinutes = mutableListOf<Double?>()
        var hasRiskBadge = false

        for (t in texts) {
            // "Área de risco" (o OCR as vezes le "9ÁTea de risco")
            if (TextNormalizer.normalize(t).contains("ea de risco")) {
                hasRiskBadge = true
            }
        }

        // Combina cabecalhos quebrados pelo OCR ("2 min" seguido de "(584 m)")
        val normalizedTexts = mutableListOf<String>()
        var idx = 0
        while (idx < texts.size) {
            val cur = normalizeLegOcrNoise(texts[idx])
            val next = if (idx + 1 < texts.size) normalizeLegOcrNoise(texts[idx + 1]) else null
            if (minutesOnlyRegex.matches(cur) && next != null && distanceOnlyRegex.matches(next)) {
                normalizedTexts.add("$cur $next")
                idx += 2
            } else {
                normalizedTexts.add(cur)
                idx++
            }
        }

        var i = 0
        while (i < normalizedTexts.size) {
            val line = normalizedTexts[i].trim()
            if (tripLegHeaderRegex.matches(line)) {
                var parsedDist: Double? = null
                val distMatch = legDistRegex.find(line)
                if (distMatch != null) {
                    val rawNum = distMatch.groupValues[1].replace(",", ".").toDoubleOrNull()
                    val unit = distMatch.groupValues[3].lowercase()
                    if (rawNum != null) {
                        parsedDist = if (unit == "m") rawNum / 1000.0 else rawNum
                    }
                }
                legDistances.add(parsedDist)

                val minMatch = legMinutesRegex.find(line)
                val parsedMin = minMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                legMinutes.add(parsedMin)

                val addrParts = mutableListOf<String>()
                var j = i + 1
                while (j < normalizedTexts.size && addrParts.size < 3) {
                    val rawCandidate = normalizedTexts[j].trim()
                    val norm = TextNormalizer.normalize(rawCandidate)
                    if (tripLegHeaderRegex.matches(rawCandidate) ||
                        acceptHints.any { norm == it || norm.startsWith("aceitar") } ||
                        norm.contains("taxa99") ||
                        norm.contains("taxa 99") ||
                        norm.contains("perfil essencial") ||
                        norm.contains("cpf e cartao")
                    ) {
                        break
                    }
                    val cleaned = rawCandidate.replace(riskBadgeCleanRegex, "").trim()
                    if (cleaned.length >= 4) {
                        addrParts.add(cleaned)
                    }
                    j++
                }
                legAddresses.add(if (addrParts.isNotEmpty()) addrParts.joinToString(" ") else null)
                i = j
            } else {
                i++
            }
        }
        return LegsExtractResult(
            pickup = legAddresses.getOrNull(0),
            dropoff = legAddresses.getOrNull(1),
            pickupDistKm = legDistances.getOrNull(0),
            dropoffDistKm = legDistances.getOrNull(1),
            pickupMinutes = legMinutes.getOrNull(0),
            dropoffMinutes = legMinutes.getOrNull(1),
            hasRiskBadge = hasRiskBadge
        )
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            if (current == null) return node
            if (current!!.isClickable) return current!!
            current = current!!.parent
        }
        return node
    }

    private fun isRejectNode(
        normText: String,
        normDesc: String,
        rejectHints: List<String>
    ): Boolean {
        return rejectHints.any { hint ->
            normText == hint ||
                normDesc == hint ||
                (normText.isNotEmpty() && normText.contains(hint)) ||
                (normDesc.isNotEmpty() && normDesc.contains(hint))
        }
    }

    private fun findTopRightCloseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val inTopQuarter = rect.top < rootRect.top + (rootRect.height() * 0.25f)
                val onRightHalf = rect.right > rootRect.centerX()
                if (inTopQuarter && onRightHalf && rect.width() in 30..650 && rect.height() in 30..260) {
                    candidates.add(node to rect)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, depth + 1)
            }
        }

        collect(root, 0)
        return candidates.minByOrNull { (_, rect) -> rect.top }?.first
    }

    private fun emptyOffer() = ParsedRideOffer(
        isRideOffer = false,
        isContinuePrompt = false,
        isRatingScreen = false,
        ratingPassengerName = null,
        isInActiveRide = false,
        hasRiskAreaBadge = false,
        allTexts = emptyList(),
        pickupAddress = null,
        dropoffAddress = null,
        ridePrice = null,
        pickupDistanceKm = null,
        dropoffDistanceKm = null,
        totalDistanceKm = null,
        pricePerKm = null,
        hourlyRate = null,
        passengerRating = null,
        isNewPassenger = false,
        addressCandidates = emptyList(),
        rejectNode = null,
        rejectTapPoint = null,
        secondaryRejectTapPoint = null,
        rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false,
        acceptNode = null,
        acceptTapPoint = null,
        continueNode = null,
        continueTapPoint = null,
        fingerprint = ""
    )
}
