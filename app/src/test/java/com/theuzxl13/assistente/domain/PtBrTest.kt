package com.theuzxl13.assistente.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PtBrTest {

    @Test
    fun `horas faladas`() {
        assertEquals("É uma hora em ponto.", PtBr.timePhrase(1, 0))
        assertEquals("É meio-dia e um minuto.", PtBr.timePhrase(12, 1))
        assertEquals("São 21 horas e 30 minutos.", PtBr.timePhrase(21, 30))
        assertEquals("É meia-noite e 15 minutos.", PtBr.timePhrase(0, 15))
    }

    @Test
    fun `data falada`() {
        assertEquals("Hoje é terça-feira, 20 de outubro de 2026.", PtBr.datePhrase(LocalDate.of(2026, 10, 20)))
        assertEquals("Hoje é sexta-feira, primeiro de maio de 2026.", PtBr.datePhrase(LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun `lista falada`() {
        assertEquals("A, B e C", PtBr.joinSpoken(listOf("A", "B", "C")))
        assertEquals("1 2 3 4", PtBr.spellDigits("1234"))
    }
}
