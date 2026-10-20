package com.theuzxl13.assistente.media

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeTitleTest {

    @Test
    fun `titulo limpo para ouvir`() {
        assertEquals("Gabriela Rocha - Me Atraiu", YouTubeMedia.spokenTitle("Gabriela Rocha - Me Atraiu (Clipe Oficial) [HD]"))
        assertEquals("Lugar Secreto", YouTubeMedia.spokenTitle("Lugar Secreto | Ao Vivo #louvor"))
        assertEquals("Rock & Roll", YouTubeMedia.spokenTitle("Rock &amp; Roll"))
        assertEquals("(Instrumental)", YouTubeMedia.spokenTitle("(Instrumental)"))
    }
}
