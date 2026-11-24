package com.iconforge.app

import com.iconforge.app.core.IconNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconNamesTest {
    @Test
    fun readableNames() {
        assertEquals("aapdfhd pdf reader", IconNames.pretty("com_aapdfhd_pdf_reader"))
        assertEquals("pdf extra", IconNames.pretty("pdf_extra"))
        assertEquals("whatsapp w4b", IconNames.pretty("com_whatsapp_w4b"))
        assertEquals("dgtle", IconNames.pretty("dgtle"))
    }

    @Test
    fun prefersShortHumanName() {
        assertTrue(IconNames.better("dgtle", "com_dgtle_android"))
        assertFalse(IconNames.better("com_dgtle_android", "dgtle"))
        assertTrue(IconNames.better("fyers", "fyers_new"))
    }

    @Test
    fun searchIgnoresSeparators() {
        assertTrue(IconNames.squash("pdf_extra").contains(IconNames.squash("PDF extra")))
        assertTrue(IconNames.squash("pdf_extra").contains(IconNames.squash("pdfextra")))
    }
}
