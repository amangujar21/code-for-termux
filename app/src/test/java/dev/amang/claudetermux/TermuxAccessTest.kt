package dev.amang.claudetermux

import dev.amang.claudetermux.bridge.TermuxAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxAccessTest {
    @Test
    fun addsSettingToEmptyFile() {
        assertEquals("allow-external-apps = true\n", TermuxAccess.withExternalAppsAllowed(""))
    }

    @Test
    fun appendsAfterOtherSettings() {
        assertEquals(
            "extra-keys = [[]]\nallow-external-apps = true\n",
            TermuxAccess.withExternalAppsAllowed("extra-keys = [[]]"),
        )
    }

    @Test
    fun uncommentsDefaultLine() {
        assertEquals(
            "### Allow\nallow-external-apps = true\nbell-character = ignore\n",
            TermuxAccess.withExternalAppsAllowed("### Allow\n# allow-external-apps = true\nbell-character = ignore\n"),
        )
    }

    @Test
    fun replacesFalse() {
        assertEquals("allow-external-apps = true\n", TermuxAccess.withExternalAppsAllowed("allow-external-apps=false\n"))
    }

    @Test
    fun leavesEnabledFileAlone() {
        val text = "allow-external-apps = true\n"
        assertEquals(text, TermuxAccess.withExternalAppsAllowed(text))
    }
}
