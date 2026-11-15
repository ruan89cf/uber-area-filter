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
    val fingerprint: String
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
    private val ownOverlayMarkers = listOf(
        "fora da area",
        "fora dos criterios",
        "dentro da area",
        "corrida entre zonas",
        "recusando...",
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
        "/km •"
    )
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
        "chegada prevista"
    )

    private val currencyRegex = Regex("""(r\$|rs|\$)\s*(\d+([.,]\d{1,2})?)(\s*/\s*h(ora)?)?""", RegexOption.IGNORE_CASE)
    private val hourlyRateRegex = Regex("""(r\$|rs|\$)\s*(\d+([.,]\d{1,2})?)\s*/\s*h(ora)?""", RegexOption.IGNORE_CASE)
    private val distanceOrTimeRegex = Regex("""\b\d+([.,]\d+)?\s*(km|m|min|h)\b""", RegexOption.IGNORE_CASE)
    private val navBannerDistRegex = Regex("""^\d+([.,]\d+)?\s*(m|km)$""", RegexOption.IGNORE_CASE)
    private val legDistRegex = Regex("""\(\s*(\d+([.,]\d+)?)\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)
    private val legMinutesRegex = Regex("""\b(\d+)\s*min\b""", RegexOption.IGNORE_CASE)
    private val surgeRegex = Regex("""\b\d+([.,]\d+)?\s*x\b""", RegexOption.IGNORE_CASE)
    private val ratingRegex = Regex("""\b([345][.,]\d{2})\b""")
    private val tripLegHeaderRegex = Regex(""".*\b\d+\s*min\s*\(.*""", RegexOption.IGNORE_CASE)
    private val riskBadgeCleanRegex = Regex("""([o0!•]\s*)?\b[aá]rea\s+de\s+risco\b""", RegexOption.IGNORE_CASE)
    private val passengerNameRegex = Regex("""como\s+foi\s+(?:sua\s+|a\s+)?corrida\s+com\s+([^?.\n!]+)""", RegexOption.IGNORE_CASE)

    fun parseFromOcr(
        visionText: Text,
        bitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        rejectHints: List<String>
    ): ParsedRideOffer {
        val normalizedRejectHints = rejectHints.map { TextNormalizer.normalize(it) }

        val sortedLines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))

        val texts = mutableListOf<String>()
        var hasAcceptButton = false
        var hasPriceOrDistance = false
        var hasNaoAfetaTa = false
        var naoAfetaBox: Rect? = null
        var hasTopNavigationText = false
        var ocrAcceptCenter: Pair<Float, Float>? = null
        var ocrContinueCenter: Pair<Float, Float>? = null
        var continueDetected = false

        for (lineObj in sortedLines) {
            val raw = lineObj.text.trim()
            if (raw.isEmpty()) continue

            val norm = TextNormalizer.normalize(raw)
            if (raw.contains("✔") ||
                raw.contains("✘") ||
                norm.contains("/km") ||
                ownOverlayMarkers.any { norm.contains(it) } ||
                norm == "on" ||
                norm == "pausa"
            ) {
                continue
            }

            val box = lineObj.boundingBox

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
                continue
            }

            texts.add(raw)

            if (currencyRegex.containsMatchIn(raw) || distanceOrTimeRegex.containsMatchIn(raw)) {
                hasPriceOrDistance = true
            }

            if (norm.contains("varias solicitacoes seguidas recusadas")) {
                continueDetected = true
            } else if (norm.contains("continuar aceitando solicitacoes") && (box == null || box.top > screenHeight * 0.65f)) {
                continueDetected = true
                if (box != null) {
                    ocrContinueCenter = box.exactCenterX() to box.exactCenterY()
                }
            }

            if (acceptHints.any { norm == it || norm.startsWith("aceitar") } && !norm.contains("continuar")) {
                if (box == null || box.top > screenHeight * 0.72f) {
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

        return buildOfferResult(
            texts = texts,
            hasAcceptButton = hasAcceptButton,
            hasPriceOrDistance = hasPriceOrDistance,
            continueDetected = continueDetected,
            hasNaoAfetaTa = hasNaoAfetaTa,
            normalizedRejectHints = normalizedRejectHints,
            rejectNode = null,
            rejectTapPoint = primaryRejectTap,
            secondaryRejectTapPoint = secondaryRejectTap,
            rejectTapTargets = rejectTargets,
            acceptNode = null,
            acceptTapPoint = ocrAcceptCenter ?: fallbackAcceptTap,
            continueNode = null,
            continueTapPoint = ocrContinueCenter ?: fallbackContinueTap
        )
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

            // 2. Se NAO ha placa preta do GPS no topo, localiza o centro exato do botao escuro X no canto superior direito
            if (!isGpsNavTopBar) {
                findDarkButtonCentroid(
                    bitmap = bitmap,
                    xMin = (w * 0.87f).toInt(),
                    xMax = (w * 0.975f).toInt(),
                    yMin = (h * 0.038f).toInt(),
                    yMax = (h * 0.092f).toInt()
                )?.let { (cx, cy) ->
                    topCircleX = cx
                    topCircleY = cy
                }
            } else {
                // 3. Com placa preta do GPS no topo, localiza o centro do botao escuro na regiao acima do card (y: 34%..41%)
                findDarkButtonCentroid(
                    bitmap = bitmap,
                    xMin = (w * 0.86f).toInt(),
                    xMax = (w * 0.975f).toInt(),
                    yMin = (h * 0.34f).toInt(),
                    yMax = (h * 0.41f).toInt()
                )?.let { (cx, cy) ->
                    midCircleX = cx
                    midCircleY = cy
                }
            }
        }

        // Se o OCR leu diretamente "Nao afeta a TA", usa a altura Y exata da pilula, mas SEMPRE na direita (x ~ 91.5%)
        // onde tanto a pilula expandida (57%..97%) quanto o botao circular X (88%..96%) ficam, longe do botao de saldo!
        if (naoAfetaBox != null) {
            val exactY = naoAfetaBox.exactCenterY()
            val safeRightX = if (exactY > h * 0.20f) midCircleX else topCircleX
            return listOf(safeRightX to exactY)
        }

        return if (!isGpsNavTopBar) {
            listOf(topCircleX to topCircleY)
        } else {
            listOf(midCircleX to midCircleY)
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
        normalizedRejectHints: List<String>,
        rejectNode: AccessibilityNodeInfo?,
        rejectTapPoint: Pair<Float, Float>?,
        secondaryRejectTapPoint: Pair<Float, Float>?,
        rejectTapTargets: List<Pair<Float, Float>>,
        acceptNode: AccessibilityNodeInfo?,
        acceptTapPoint: Pair<Float, Float>?,
        continueNode: AccessibilityNodeInfo?,
        continueTapPoint: Pair<Float, Float>?
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
        val ridePrice = extractMainRidePrice(texts)

        // KM Total = KM de Buscar (pickupDistKm) + KM de Levar (dropoffDistKm)
        val totalDistKm = if (pickupDistKm != null && dropoffDistKm != null) {
            pickupDistKm + dropoffDistKm
        } else {
            dropoffDistKm ?: pickupDistKm
        }

        val pricePerKm = if (ridePrice != null && totalDistKm != null && totalDistKm > 0.1) {
            ridePrice / totalDistKm
        } else {
            null
        }

        // Calculo Real de R$/h = Valor / ((Tempo de Buscar + Tempo de Levar + 2 min de espera do passageiro) / 60)
        val waitMinutes = 2.0
        val hourlyRate = if (ridePrice != null && ridePrice > 0.0) {
            when {
                pickupMinutes != null && dropoffMinutes != null && (pickupMinutes + dropoffMinutes) > 0.0 -> {
                    val totalMinWithWait = pickupMinutes + dropoffMinutes + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                rawCardHourlyRate != null && rawCardHourlyRate > 0.0 -> {
                    val baseMin = (ridePrice / rawCardHourlyRate) * 60.0
                    val totalMinWithWait = baseMin + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                totalDistKm != null && totalDistKm > 0.1 -> {
                    val estDrivingMin = totalDistKm * 2.4 // ~25 km/h media urbana (buscar + levar)
                    val totalMinWithWait = estDrivingMin + waitMinutes
                    (ridePrice / totalMinWithWait) * 60.0
                }
                else -> rawCardHourlyRate
            }
        } else {
            rawCardHourlyRate
        }

        val normAll = texts.map { TextNormalizer.normalize(it) }
        val isTripRadarList = normAll.any { it.contains("escolha uma") || it.contains("99 abastece") }
        val hasRealCardEvidence = (pickup != null && dropoff != null) ||
            (rawCardHourlyRate != null && ridePrice != null) ||
            (hasNaoAfetaTa && ridePrice != null)
        val isOffer = hasAcceptButton && hasRealCardEvidence && !isTripRadarList && hasPriceOrDistance

        val isRatingScreen = !isOffer && !continueDetected && normAll.any { line ->
            ratingScreenHints.any { hint -> line.contains(hint) }
        }
        val ratingPassengerName = if (isRatingScreen) {
            extractRatingPassengerName(texts)
        } else {
            null
        }

        val isInActiveRide = !isOffer && !continueDetected && !isRatingScreen && normAll.any { line ->
            activeRideHints.any { hint -> line.contains(hint) }
        }

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
            fingerprint = fingerprint
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

    private fun extractMainRidePrice(texts: List<String>): Double? {
        for (line in texts) {
            val norm = TextNormalizer.normalize(line)
            if (norm.contains("/h") || norm.contains("tarifa") || norm.contains("taxa")) continue
            val match = currencyRegex.find(line) ?: continue
            val valNum = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (valNum != null && valNum >= 3.0) {
                return valNum
            }
        }
        return null
    }

    private fun extractHourlyRate(texts: List<String>): Double? {
        for (line in texts) {
            val match = hourlyRateRegex.find(line) ?: continue
            val value = match.groupValues[2].replace(",", ".").toDoubleOrNull()
            if (value != null && value > 0.0) {
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

    private fun extractLegsInfo(texts: List<String>): LegsExtractResult {
        val legAddresses = mutableListOf<String>()
        val legDistances = mutableListOf<Double?>()
        val legMinutes = mutableListOf<Double?>()
        var hasRiskBadge = false

        for (t in texts) {
            if (TextNormalizer.normalize(t).contains("area de risco")) {
                hasRiskBadge = true
            }
        }

        var i = 0
        while (i < texts.size - 1) {
            val line = texts[i].trim()
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
                while (j < texts.size && addrParts.size < 2) {
                    val rawCandidate = texts[j].trim()
                    val norm = TextNormalizer.normalize(rawCandidate)
                    if (tripLegHeaderRegex.matches(rawCandidate) ||
                        acceptHints.any { norm == it || norm.startsWith("aceitar") }
                    ) {
                        break
                    }
                    val cleaned = rawCandidate.replace(riskBadgeCleanRegex, "").trim()
                    if (cleaned.length >= 4) {
                        addrParts.add(cleaned)
                    }
                    j++
                }
                if (addrParts.isNotEmpty()) {
                    legAddresses.add(addrParts.joinToString(" "))
                }
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
