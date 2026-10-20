package com.theuzxl13.assistente.domain

import java.text.Normalizer

/**
 * Deixa o texto reconhecido num formato fácil de comparar: minúsculas, sem acentos, sem
 * pontuação e com espaços simples. "Bela, LIGUE para o João!" vira "bela ligue para o joao".
 */
object TextNormalizer {

    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonAlphanumeric = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(diacritics, "")
            .replace(nonAlphanumeric, " ")
            .replace(spaces, " ")
            .trim()
    }

    /**
     * Tira o nome da assistente (e saudações antes dele) do começo da frase: "oi bela, que horas
     * são" vira "que horas sao". Recebe texto já normalizado.
     */
    fun stripWakeWord(normalized: String): String {
        return normalized
            .replace(Regex("^(?:(?:oi|ola|ei|e ai|hey|alo)\\s+)?(?:bela|bella)\\b\\s*"), "")
            .trim()
    }
}
