package com.theuzxl13.assistente.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Frases em português brasileiro montadas para serem faladas (não lidas). */
object PtBr {

    private val locale = Locale("pt", "BR")
    private val ordinals = listOf("primeiro", "segundo", "terceiro", "quarto", "quinto")

    fun ordinal(index: Int): String = ordinals.getOrElse(index) { "${index + 1}º" }

    fun timePhrase(hour: Int, minute: Int): String {
        val hours = when (hour) {
            0 -> "meia-noite"
            1 -> "uma hora"
            12 -> "meio-dia"
            else -> "$hour horas"
        }
        val verb = if (hour == 0 || hour == 1 || hour == 12) "É" else "São"
        return when (minute) {
            0 -> "$verb $hours em ponto."
            1 -> "$verb $hours e um minuto."
            else -> "$verb $hours e $minute minutos."
        }
    }

    fun datePhrase(date: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", locale)
        val text = date.format(formatter)
        // "1 de maio" se fala "primeiro de maio".
        val spoken = if (date.dayOfMonth == 1) text.replaceFirst(", 1 de", ", primeiro de") else text
        return "Hoje é $spoken."
    }

    /** "1234" vira "1 2 3 4", para o sintetizador falar dígito por dígito. */
    fun spellDigits(digits: String): String = digits.toCharArray().joinToString(" ")

    /** Lista falada: "X, Y e Z". */
    fun joinSpoken(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
    }
}
