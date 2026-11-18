package com.ruan.uberareafilter

import android.graphics.Rect
import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.model.FilterConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Reprocessa, com o codigo atual, as telas reais gravadas no log do celular (linhas RAIO-X OCR-*).
 * So roda quando a variavel FILTRO_REPLAY_LOG aponta para um all.txt exportado; o relatorio vai
 * para FILTRO_REPLAY_OUT (ou replay.txt ao lado do log). Fora disso o teste e ignorado.
 */
class LogReplayTest {

    private val itemRegex = Regex("""@Y(?:-?\d+%|\?)(?:\[(-?\d+)\.\.(-?\d+)\])?:"(.*?)"(?= \| @Y|\s*$)""")
    private val modeRegex = Regex("""modo=([A-Z0-9_]+)""")
    private val preferRegex = Regex("""prefer=([A-Z0-9/]+)""")
    private val timeRegex = Regex("""^\[(\d{2}:\d{2}:\d{2})]""")

    private fun box(top: Int, bottom: Int) = Rect().apply {
        left = 0; right = 1220; this.top = top; this.bottom = bottom
    }

    private fun configFor(mode: String) = when (mode) {
        "UBER_EXCLUSIVO" -> FilterConfig(nineNineModuleEnabled = false, uberModuleEnabled = true)
        "DUPLO_SIMULTANEO" -> FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
        else -> FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)
    }

    @Test
    fun replayLoggedScreens() {
        val path = System.getenv("FILTRO_REPLAY_LOG")
        assumeTrue("FILTRO_REPLAY_LOG nao definido", path != null && File(path).exists())
        val logFile = File(path!!)
        val outFile = File(System.getenv("FILTRO_REPLAY_OUT") ?: File(logFile.parentFile, "replay.txt").path)

        var mode = "99_EXCLUSIVO"
        var prefer: String? = "99"
        val report = StringBuilder()
        val counts = linkedMapOf<String, Int>()
        fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }

        logFile.forEachLine { line ->
            modeRegex.find(line)?.let { mode = it.groupValues[1] }
            preferRegex.find(line)?.let { m -> prefer = m.groupValues[1].takeIf { it == "99" || it == "UBER" } }
            val kind = when {
                line.contains("[RAIO-X OCR-NAO-CASADO]") -> "NAO-CASADO"
                line.contains("[RAIO-X OCR-OFERTA]") -> "OFERTA"
                line.contains("[RAIO-X OCR-CICLO]") -> "CICLO"
                else -> return@forEachLine
            }
            val time = timeRegex.find(line)?.groupValues?.get(1) ?: "?"
            val body = line.substringAfter("] [RAIO-X OCR-").substringAfter("] ")
            val items = itemRegex.findAll(body).map { m ->
                val top = m.groupValues[1].toIntOrNull()
                val bottom = m.groupValues[2].toIntOrNull()
                m.groupValues[3] to (if (top != null && bottom != null) box(top, bottom) else null)
            }.toList()
            if (items.isEmpty()) return@forEachLine
            // O RAIO-X gravava no maximo 28 linhas (45 a partir da v2.4): nesses casos o card pode ter ficado de fora
            val truncated = items.size == 28 || items.size >= 45

            val parsed = runCatching {
                AppCaptureRouter.parseOrderedLines(items, null, 1220, 2712, configFor(mode), preferredAppSource = prefer)
            }.getOrElse { e ->
                report.append("$time $kind ERRO ${e.javaClass.simpleName}: ${e.message}\n")
                count("$kind -> ERRO")
                return@forEachLine
            }

            val result = when {
                parsed == null -> "NADA"
                parsed.isRideOffer -> "OFERTA"
                parsed.isContinuePrompt -> "CONTINUAR"
                parsed.isRatingScreen -> "AVALIACAO"
                parsed.isCancelledScreen -> "CANCELADA"
                parsed.isInActiveRide -> "EM_VIAGEM"
                parsed.isIdleHomeScreen -> "OCIOSO"
                else -> "OUTRO"
            }
            // Card de corrida de verdade: botao "Aceitar"/"Aceitar por R$X" (nao o nosso "Aceitar se tocar de novo")
            // + algum trecho "N min" + algum valor em R$
            val looksLikeOffer = items.any { (t, _) ->
                val low = t.trim().lowercase(Locale.ROOT)
                low.startsWith("aceitar") && !low.contains("se tocar")
            } && items.any { (t, _) -> Regex("""\d\s*min""", RegexOption.IGNORE_CASE).containsMatchIn(t) } &&
                items.any { (t, _) -> t.contains("R$") }
            val tag = when {
                result == "NADA" && looksLikeOffer -> " [PARECE-OFERTA${if (truncated) ",CORTADO" else ""}]"
                // Era oferta no celular e agora nao e: so aceitavel quando o RAIO-X cortou as linhas do card
                kind == "OFERTA" && result != "OFERTA" -> if (truncated) " [CORTADO]" else " [REGRESSAO?]"
                else -> ""
            }
            count("$kind -> $result$tag")

            report.append("$time $kind ($mode) => $result$tag")
            if (parsed != null && parsed.isRideOffer) {
                report.append(
                    String.format(
                        Locale.US,
                        " | %s R$%s emb=%skm/%smin dest=%skm/%smin R$/h=%s | Emb=%s | Dest=%s",
                        parsed.appSource,
                        parsed.ridePrice?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
                        parsed.pickupDistanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
                        parsed.pickupMinutes?.let { String.format(Locale.US, "%.0f", it) } ?: "?",
                        parsed.dropoffDistanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "?",
                        parsed.dropoffMinutes?.let { String.format(Locale.US, "%.0f", it) } ?: "?",
                        parsed.hourlyRate?.let { String.format(Locale.US, "%.0f", it) } ?: "?",
                        parsed.pickupAddress ?: "?",
                        parsed.dropoffAddress ?: "?"
                    )
                )
                if (parsed.isNegotiable) {
                    report.append(" | NEGOCIA contra=${parsed.counterOfferPrices}")
                }
            } else if (tag.isNotEmpty()) {
                report.append(" | ").append(items.joinToString(" ¦ ") { it.first })
            }
            report.append('\n')
        }

        val summary = buildString {
            append("== Resumo do replay (${logFile.name}) ==\n")
            counts.forEach { (k, v) -> append(String.format(Locale.US, "%5d  %s\n", v, k)) }
            append('\n')
        }
        outFile.writeText(summary + report.toString())
        println(summary)
    }
}
