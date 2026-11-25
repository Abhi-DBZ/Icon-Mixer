package com.iconforge.app

import com.iconforge.app.core.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionOrder() {
        assertTrue(Updater.isNewer("1.10", "1.9.1"))
        assertTrue(Updater.isNewer("1.9.2", "1.9.1"))
        assertTrue(Updater.isNewer("2.0", "1.12.3"))
        assertFalse(Updater.isNewer("1.9.1", "1.9.1"))
        assertFalse(Updater.isNewer("1.9", "1.9.1"))
        assertFalse(Updater.isNewer("1.8", "1.10"))
    }
}
