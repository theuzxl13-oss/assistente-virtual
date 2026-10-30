package com.theuzxl13.assistente.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun `numeros brasileiros e internacionais`() {
        assertEquals("5511988881234", PhoneNumbers.forWhatsApp("(11) 98888-1234"))
        assertEquals("5511988881234", PhoneNumbers.forWhatsApp("+55 11 98888-1234"))
        assertEquals("5511988881234", PhoneNumbers.forWhatsApp("0 15 11 98888-1234"))   // com operadora
        assertEquals("5511988881234", PhoneNumbers.forWhatsApp("011 98888-1234"))
        assertEquals("551133334444", PhoneNumbers.forWhatsApp("(11) 3333-4444"))
        assertEquals("351912345678", PhoneNumbers.forWhatsApp("+351 912 345 678"))
        assertEquals("351912345678", PhoneNumbers.forWhatsApp("00351912345678"))
    }

    @Test
    fun `sem ddd nao inventa numero`() {
        assertNull(PhoneNumbers.forWhatsApp("98888-1234"))
    }
}
