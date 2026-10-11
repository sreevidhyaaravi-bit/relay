package com.relay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What RELAY QR codes contain, and rejecting anything else. */
class ContactCodeTest {
    @Test
    fun `round trip keeps id and name, including spaces and symbols`() {
        val code = ContactCode.encode("RLY-1A2B3C4D", "Priya's phone & co")
        assertEquals(ContactCode.Contact("RLY-1A2B3C4D", "Priya's phone & co"), ContactCode.parse(code))
    }

    @Test
    fun `other QR codes are rejected`() {
        assertNull(ContactCode.parse("https://example.com"))
        assertNull(ContactCode.parse("relay://add?name=NoId"))
        assertNull(ContactCode.parse("relay://add?id=bad id!&name=x"))
        assertNull(ContactCode.parse("relay://add?id=*&name=everyone"))
        assertNull(ContactCode.parse(null))
    }

    @Test
    fun `missing name falls back to the id`() {
        assertEquals("RLY-ABCD1234", ContactCode.parse("relay://add?id=RLY-ABCD1234")!!.name)
    }
}
