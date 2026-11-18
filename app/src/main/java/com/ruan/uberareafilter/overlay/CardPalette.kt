package com.ruan.uberareafilter.overlay

import com.ruan.uberareafilter.engine.Decision
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Paleta do card flutuante — Kotlin puro (sem android.graphics), testavel no JVM.
 *
 * A cor que o motorista escolhe vira a IDENTIDADE (o matiz) do card, nao a cor literal:
 *  - o fundo e sempre escuro e pouco saturado (luminancia travada): nunca ofusca a noite
 *    e o texto claro sempre passa de ~12:1 de contraste, mesmo se a escolha for "Laranja" ou "Amarelo";
 *  - o acento (barra lateral, icone, R$/h, pilula) usa o mesmo matiz, clareado ate ter
 *    pelo menos 4,5:1 sobre o fundo;
 *  - cores neutras (preto, branco, cinza) mantem o fundo neutro e usam o acento da propria decisao.
 * De noite o fundo fica ~20% mais escuro e o texto deixa de ser branco puro (#ECE9E2), o que reduz
 * o "halo" de letras brancas sobre fundo escuro.
 */
object CardPalette {

    /**
     * Cores para pintar o card de cada zona. Ficam de fora os matizes ja usados pelas decisoes
     * (verde = dentro, vermelho = fora, laranja = incerto, amarelo = retorno) para nao confundir.
     */
    val ZONE_COLORS: List<Pair<String, String>> = listOf(
        "Roxo" to "#7C3AED",
        "Azul" to "#2563EB",
        "Ciano" to "#06B6D4",
        "Rosa" to "#EC4899",
        "Magenta" to "#C026D3"
    )
    const val DEFAULT_ZONE_COLOR = "#7C3AED"

    data class Colors(
        val bgTop: Int,
        val bgBottom: Int,
        val accent: Int,
        val accentText: Int,
        val pillBg: Int,
        val pillStroke: Int,
        val cardStroke: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val ok: Int,
        val bad: Int,
        val badText: Int,
        val warn: Int,
        val info: Int,
        val purple: Int,
        val progressTrack: Int,
        val progressFill: Int,
        val glyphOnAccent: Int
    )

    /** Matiz semantico de cada decisao (usado quando a cor escolhida e neutra ou nao existe). */
    fun fallbackHue(decision: Decision): Float = when (decision) {
        Decision.INSIDE_AREA -> 145f
        Decision.INTER_ZONE -> 268f
        Decision.RETURN_TO_AREA -> 42f
        Decision.UNCERTAIN -> 25f
        Decision.OUTSIDE_AREA -> 2f
        Decision.UNKNOWN_KEEP -> 215f
    }

    /** Simbolo grande do card: reconhecivel pela forma, nao so pela cor (daltonismo, sol forte). */
    fun glyph(decision: Decision): String = when (decision) {
        Decision.INSIDE_AREA -> "✓"
        Decision.INTER_ZONE -> "⇄"
        Decision.RETURN_TO_AREA -> "↩"
        Decision.UNCERTAIN -> "?"
        Decision.OUTSIDE_AREA -> "✕"
        Decision.UNKNOWN_KEEP -> "i"
    }

    /**
     * @param userArgb cor escolhida pelo motorista (null = sem escolha, usa o matiz da decisao)
     * @param night modo noturno (sistema em tema escuro ou horario noturno)
     */
    fun build(userArgb: Int?, decision: Decision, night: Boolean): Colors {
        val fh = fallbackHue(decision)
        var hue = fh
        var sat = 0.6f
        var neutral = false
        if (userArgb != null) {
            val hsl = rgbToHsl(userArgb)
            hue = hsl[0]
            sat = hsl[1]
            neutral = hsl[1] < 0.12f || hsl[2] < 0.04f || hsl[2] > 0.96f
        }

        val bgHue = if (neutral) fh else hue
        val bgSat = if (neutral) 0.05f else min(sat, 0.45f)
        val bgL = if (night) 0.105f else 0.135f
        val alpha = if (night) 0xF2 else 0xFA
        val bgTopRgb = hslToRgb(bgHue, bgSat, bgL)
        val bgBottomRgb = hslToRgb(bgHue, bgSat, bgL * 0.78f)

        val accHue = if (neutral) fh else hue
        val accSat = if (neutral) 0.62f else sat.coerceIn(0.55f, 0.78f)
        var accL = if (night) 0.60f else 0.62f
        var accentRgb = hslToRgb(accHue, accSat, accL)
        while (contrast(accentRgb, bgTopRgb) < 4.5 && accL < 0.82f) {
            accL += 0.02f
            accentRgb = hslToRgb(accHue, accSat, accL)
        }
        val accentTextRgb = hslToRgb(accHue, accSat * 0.9f, min(accL + 0.12f, 0.86f))

        val textPrimary = if (night) 0xFFECE9E2.toInt() else 0xFFFFFFFF.toInt()
        val textSecondary = blend(textPrimary, opaque(bgTopRgb), 0.30f)
        val textTertiary = blend(textPrimary, opaque(bgTopRgb), 0.45f)

        val ok = opaque(hslToRgb(145f, 0.58f, if (night) 0.60f else 0.64f))
        val bad = opaque(hslToRgb(3f, 0.78f, if (night) 0.68f else 0.70f))
        val warn = opaque(hslToRgb(40f, 0.92f, if (night) 0.60f else 0.62f))
        val info = opaque(hslToRgb(200f, 0.75f, if (night) 0.64f else 0.66f))
        val purple = opaque(hslToRgb(268f, 0.70f, if (night) 0.74f else 0.76f))

        val accent = opaque(accentRgb)
        return Colors(
            bgTop = withAlpha(bgTopRgb, alpha),
            bgBottom = withAlpha(bgBottomRgb, alpha),
            accent = accent,
            accentText = opaque(accentTextRgb),
            pillBg = withAlpha(accentRgb, 0x2E),
            pillStroke = withAlpha(accentRgb, 0x73),
            cardStroke = withAlpha(accentRgb, 0x59),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textTertiary = textTertiary,
            ok = ok,
            bad = bad,
            badText = blend(bad, textPrimary, 0.55f),
            warn = warn,
            info = info,
            purple = purple,
            progressTrack = withAlpha(textPrimary, 0x1F),
            progressFill = withAlpha(accentRgb, 0xD9),
            glyphOnAccent = opaque(bgTopRgb)
        )
    }

    /** Cor de amostra para os botoes de cor do painel (mostra o fundo REAL que o card tera). */
    fun swatch(userArgb: Int?, decision: Decision): Pair<Int, Int> {
        val c = build(userArgb, decision, night = false)
        return opaque(c.bgTop) to c.accent
    }

    // ------------------------------------------------------------------
    // Matematica de cor (sRGB / HSL / contraste WCAG)
    // ------------------------------------------------------------------

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alphaOf(c: Int) = (c ushr 24) and 0xFF

    fun withAlpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
    fun opaque(c: Int): Int = withAlpha(c, 0xFF)

    /** Mistura c1 com c2 (ratio = quanto de c2). Resultado opaco. */
    fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (red(c1) * inv + red(c2) * ratio).roundToInt()
        val g = (green(c1) * inv + green(c2) * ratio).roundToInt()
        val b = (blue(c1) * inv + blue(c2) * ratio).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun rgbToHsl(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        return floatArrayOf(h, s, l)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun relativeLuminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** "#RRGGBB" ou "#AARRGGBB" -> ARGB (null se invalido). */
    fun parseHex(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().removePrefix("#")
        return when (clean.length) {
            6 -> clean.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    fun toHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
}
