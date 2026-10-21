package com.theuzxl13.assistente.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `tira o comando e acha o assunto`() {
        val moises = SearchQuery.from("Bela, pesquise quem foi Moisés.")
        assertEquals("quem foi Moisés", moises.question)
        assertEquals("Moisés", moises.topic)
        assertFalse(moises.isNews)

        val whatsapp = SearchQuery.from("Pesquise no Google como usar o WhatsApp")
        assertEquals("como usar o WhatsApp", whatsapp.question)

        assertEquals("uma receita de bolo", SearchQuery.from("Pesquise uma receita de bolo").question)
        assertEquals("qual a capital da França", SearchQuery.from("qual a capital da França?").question)
    }

    @Test
    fun `noticias`() {
        val today = SearchQuery.from("Pesquise notícias de hoje")
        assertTrue(today.isNews)
        assertEquals("", today.newsTopic)

        val topic = SearchQuery.from("Bela, procure as últimas notícias sobre o Corinthians")
        assertTrue(topic.isNews)
        assertEquals("o Corinthians", topic.newsTopic)
    }
}
