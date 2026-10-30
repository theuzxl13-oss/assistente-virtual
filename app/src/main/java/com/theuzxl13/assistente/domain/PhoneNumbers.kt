package com.theuzxl13.assistente.domain

/** Números de telefone no formato que o WhatsApp usa: só dígitos, com o código do país (55). */
object PhoneNumbers {

    /**
     * "(11) 98888-1234" → "5511988881234"; "+351 912 345 678" → "351912345678";
     * "0 15 11 98888-1234" (com operadora) → "5511988881234".
     * Retorna null quando não dá para saber o número completo (ex.: sem DDD).
     */
    fun forWhatsApp(raw: String, countryCode: String = "55"): String? {
        val trimmed = raw.trim()
        var digits = trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("+")) return digits.takeIf { it.length in 8..15 }
        if (digits.startsWith("00")) return digits.drop(2).takeIf { it.length in 8..15 }
        if (digits.startsWith("0")) {
            digits = when (digits.length) {
                13, 14 -> digits.drop(3)   // 0 + operadora + DDD + número
                11, 12 -> digits.drop(1)   // 0 + DDD + número
                else -> digits
            }
        }
        if (digits.startsWith(countryCode) && digits.length in 12..13) return digits
        return if (digits.length in 10..11) countryCode + digits else null
    }
}
