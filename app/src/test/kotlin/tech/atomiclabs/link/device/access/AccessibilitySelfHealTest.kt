package tech.atomiclabs.link.device.access

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

class AccessibilitySelfHealTest {
    private val me = "tech.atomiclabs.link/tech.atomiclabs.link.device.access.LinkAccessibilityService"
    private val logger = FakeLogger()
    private var stored: String? = null
    private var master = false

    @Test fun isOnReadsTheCurrentEnabledListWithoutCheckingTheGrantOrWriting() {
        val reader = AccessibilitySelfHeal(logger, { error("A read must not require the grant.") }, { stored },
            { error("A read must not write enabled services.") }, { error("A read must not write the master setting.") }, me)
        assertFalse(reader.isOn())
        stored = "other.pkg/other.Svc:tech.atomiclabs.link/.device.access.LinkAccessibilityService"
        assertTrue(reader.isOn())
        stored = "other.pkg/other.Svc"
        assertFalse(reader.isOn())
    }

    private fun heal(granted: Boolean, writeOk: Boolean = true) = AccessibilitySelfHeal(
        logger, { granted }, { stored },
        { stored = it; writeOk }, { master = true; true }, me,
    ).run()

    @Test fun alreadyOnDoesNothing() {
        stored = "other.pkg/other.Svc:$me"
        assertEquals(AccessibilitySelfHeal.Result.ALREADY_ON, heal(granted = true))
        assertEquals("other.pkg/other.Svc:$me", stored)
        assertFalse(master)
    }

    @Test fun shortFormEntryCountsAsOn() {
        stored = "tech.atomiclabs.link/.device.access.LinkAccessibilityService"
        assertEquals(AccessibilitySelfHeal.Result.ALREADY_ON, heal(granted = true))
    }

    @Test fun offWithGrantReAddsAndKeepsOtherServices() {
        stored = "other.pkg/other.Svc"
        assertEquals(AccessibilitySelfHeal.Result.HEALED, heal(granted = true))
        assertEquals("other.pkg/other.Svc:$me", stored)
        assertTrue(master)
    }

    @Test fun offWithEmptyListWritesJustUs() {
        stored = null
        assertEquals(AccessibilitySelfHeal.Result.HEALED, heal(granted = true))
        assertEquals(me, stored)
    }

    @Test fun offWithoutGrantLeavesSettingsAlone() {
        stored = ""
        assertEquals(AccessibilitySelfHeal.Result.NO_GRANT, heal(granted = false))
        assertEquals("", stored)
        assertFalse(master)
        assertTrue(logger.entries.any { it.context == "state=off grant=missing" })
    }

    @Test fun refusedWriteReportsFailure() {
        stored = ""
        assertEquals(AccessibilitySelfHeal.Result.FAILED, heal(granted = true, writeOk = false))
    }

    private fun disable(granted: Boolean) = AccessibilitySelfHeal(
        logger, { granted }, { stored }, { stored = it; true }, { true }, me,
    ).disable()

    @Test fun disableRemovesOnlyUs() {
        stored = "other.pkg/other.Svc:tech.atomiclabs.link/.device.access.LinkAccessibilityService"
        assertTrue(disable(granted = true))
        assertEquals("other.pkg/other.Svc", stored)
    }

    @Test fun disableWithoutGrantLeavesSettingsAlone() {
        stored = me
        assertFalse(disable(granted = false))
        assertEquals(me, stored)
    }
}
