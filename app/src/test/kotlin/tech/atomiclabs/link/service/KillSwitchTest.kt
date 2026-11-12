package tech.atomiclabs.link.service

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

class KillSwitchTest {
    private var killed = false
    private val calls = mutableListOf<String>()
    private val sw = KillSwitch(FakeLogger(), { killed }, { killed = it },
        { calls += "a11y-off" }, { calls += "a11y-on" }, { calls += "stop" }, { calls += "start" })

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
