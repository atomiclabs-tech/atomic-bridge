package tech.atomiclabs.link.service

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

class KillSwitchTest {
    private var killed = false
    private val calls = mutableListOf<String>()
    private val sw = KillSwitch(FakeLogger(), { killed }, { killed = it },
        { calls += "a11y-off"; true }, { false }, { calls += "a11y-on" }, { calls += "stop" }, { calls += "start" })

    @Test fun refusedScreenControlDisableStillPersistsTheKillWarnsAndStops() {
        val logger = FakeLogger()
        val link = KillSwitch(logger, { killed }, { killed = it; calls += "saved" },
            { assertTrue(killed); calls += "a11y-kept"; false }, { true },
            { calls += "a11y-on" }, { calls += "stop" }, { calls += "start" })
        link.kill()
        assertTrue(link.isKilled())
        assertEquals(listOf("saved", "a11y-kept", "stop"), calls)
        val warning = logger.entries.single()
        assertEquals("warn", warning.level)
        assertEquals("KillSwitch", warning.component)
        assertEquals("kill", warning.operation)
        assertEquals("state=screen_control_kept grant=missing", warning.context)
    }

    @Test fun screenControlStatusReadsTheCurrentInjectedState() {
        var enabled = false
        val link = KillSwitch(FakeLogger(), { killed }, { killed = it }, { true }, { enabled }, {}, {}, {})
        assertFalse(link.isScreenControlOn())
        enabled = true
        assertTrue(link.isScreenControlOn())
    }

    @Test fun normalStartHealsScreenControlAndAllowsConnect() {
        assertFalse(sw.onProcessStart())
        assertEquals(listOf("a11y-on"), calls)
    }

    @Test fun killTurnsScreenControlOffThenStopsTheProcess() {
        sw.kill()
        assertTrue(sw.isKilled())
        assertEquals(listOf("a11y-off", "stop"), calls)
    }

    @Test fun killedStaysDownAcrossRestartsWithoutHealing() {
        sw.kill(); calls.clear()
        assertTrue(sw.onProcessStart())
        assertTrue(sw.onProcessStart())
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun turnOnHealsAndConnects() {
        sw.kill(); calls.clear()
        sw.turnOn()
        assertFalse(sw.isKilled())
        assertEquals(listOf("a11y-on", "start"), calls)
        assertFalse(sw.onProcessStart())
    }
}
