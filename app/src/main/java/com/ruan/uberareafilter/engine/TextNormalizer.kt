package com.ruan.uberareafilter.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Utilitario para normalizar textos (remove acentos, converte para minusculas e limpa espacos).
 */
object TextNormalizer {
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val whitespaceRegex = Regex("\\s+")

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return diacriticsRegex.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(whitespaceRegex, " ")
            .trim()
    }
}
