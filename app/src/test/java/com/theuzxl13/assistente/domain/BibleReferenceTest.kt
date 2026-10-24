package com.theuzxl13.assistente.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibleReferenceTest {

    private fun ref(text: String) = BibleReferenceParser.parse(text)?.let { listOf(it.book.id, it.chapter, it.verseStart, it.verseEnd) }

    @Test
    fun `referencias faladas e escritas`() {
        assertEquals(listOf("1PE", 5, 7, null), ref("Bela, leia 1 Pedro 5,7"))
        assertEquals(listOf("1PE", 5, 7, null), ref("primeira de Pedro capítulo cinco versículo sete"))
        assertEquals(listOf("1PE", 5, 7, null), ref("1Pedro 5 7"))
        assertEquals(listOf("JHN", 3, 16, null), ref("João 3:16"))
        assertEquals(listOf("1JN", 4, 8, null), ref("primeira carta de João 4 8"))
        assertEquals(listOf("PSA", 23, null, null), ref("leia o salmo vinte e três"))
        assertEquals(listOf("PSA", 119, 105, null), ref("salmo cento e dezenove versículo cento e cinco"))
        assertEquals(listOf("MAT", 5, 3, 10), ref("Mateus 5 do 3 ao 10"))
        assertEquals(listOf("1CO", 13, null, null), ref("I Coríntios 13"))
        assertEquals(listOf("2CO", 5, 17, null), ref("segunda carta aos coríntios 5 17"))
        assertEquals(listOf("JOB", 1, 21, null), ref("jó 1 21"))
        assertEquals(listOf("SNG", 2, null, null), ref("cantares 2"))
    }

    @Test
    fun `nao inventa referencia`() {
        assertNull(ref("quem foi Daniel"))
        assertNull(ref("ligue para o João"))
        assertNull(ref("Apocalipse 30"))       // só tem 22 capítulos
    }

    @Test
    fun `referencia falada`() {
        assertEquals("Primeira carta de Pedro, capítulo 5, versículo 7", BibleReferenceParser.parse("1 Pedro 5,7")!!.spoken())
        assertEquals("Salmo 23", BibleReferenceParser.parse("salmo 23")!!.spoken())
        assertEquals("Mateus, capítulo 5, versículos 3 a 10", BibleReferenceParser.parse("Mateus 5 do 3 ao 10")!!.spoken())
    }

    @Test
    fun `grafia de 1911 para a fala`() {
        assertEquals(
            "No principio criou Deus os céus. E Cristo disse a eles: nele está o fruto do Salmo; o profeta Tiago comigo, aquela terra do Egito, pacto.",
            OldSpelling.forSpeech("No principio creou Deus os céus. E Christo disse a elles: n’elle está o fructo do Psalmo; o propheta Thiago commigo, aquella terra do Egypto, pacto.")
        )
    }

    @Test
    fun `numeros por extenso`() {
        assertEquals("salmo 23", BibleReferenceParser.wordsToNumbers("salmo 23"))
        assertEquals("salmo 23", BibleReferenceParser.wordsToNumbers("salmo vinte e tres"))
        assertEquals("capitulo 5 e versiculo 7", BibleReferenceParser.wordsToNumbers("capitulo cinco e versiculo sete"))
        assertEquals("123", BibleReferenceParser.wordsToNumbers("cento e vinte e tres"))
    }
}
