package com.ruan.uberareafilter.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Utilitario para normalizar textos (remove acentos, converte para minusculas e limpa espacos).
 */
object TextNormalizer {
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val whitespaceRegex = Regex("\\s+")

    // Atalho de voz da 99 ("OK, corrida cancelada") fica fixo no mapa durante a viagem e o OCR
    // as vezes junta com a rua do lado ("R OK, corrida cancelada", "Glorin OK, corrida cancelada").
    private val voiceCancelShortcutRegex = Regex("""\bok\b\W{0,3}(corrida|viagem) cancelada""")

    /** Remove o atalho de voz "ok, corrida cancelada" de uma linha ja normalizada. */
    fun withoutVoiceCancelShortcut(norm: String): String = voiceCancelShortcutRegex.replace(norm, " ")

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return diacriticsRegex.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(whitespaceRegex, " ")
            .trim()
    }
}
