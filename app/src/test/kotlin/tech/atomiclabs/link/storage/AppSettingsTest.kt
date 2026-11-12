package tech.atomiclabs.link.storage

import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.connection.assertSafe
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AppSettingsTest {
    @Test fun absentUrlStaysAbsentAndDeviceUuidIsGeneratedOnceAcrossCallsAndInstances() = runTest {
        val fixture = PreferencesFixture()
        val logger = FakeLogger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var generations = 0
        val id = "11111111-2222-3333-4444-555555555555"
        val settings = AppSettings(fixture.preferences, logger, dispatcher) { generations++; id }
        assertEquals(ReadValue.Absent, settings.relayUrl())
        val results = List(20) { async { settings.deviceId() } }.awaitAll()
        assertTrue(results.all { it == ReadValue.Present(id) })
        assertEquals(1, generations)
        val reopened = AppSettings(fixture.preferences, logger, dispatcher) { error("Must not regenerate.") }
        assertEquals(ReadValue.Present(id), reopened.deviceId())
        assertEquals(ReadValue.Absent, reopened.relayUrl())
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun checkedSettingsWritesPersistTheUrlAndExplicitDeviceId() = runTest {
        val fixture = PreferencesFixture()
        val settings = AppSettings(fixture.preferences, FakeLogger(), StandardTestDispatcher(testScheduler))
        val id = "11111111-2222-3333-4444-555555555555"
        assertTrue(settings.saveRelayUrl("https://relay.example") is PortResult.Success)
        assertTrue(settings.saveDeviceId(id) is PortResult.Success)
        assertEquals(ReadValue.Present("https://relay.example"), settings.relayUrl())
        assertEquals(ReadValue.Present(id), settings.deviceId())
    }

    @Test fun failedCommitsAndThrowsStayTypedAndDoNotBecomeDefaultsOrNewIds() = runTest {
        val fixture = PreferencesFixture()
        val logger = FakeLogger()
        val settings = AppSettings(fixture.preferences, logger, StandardTestDispatcher(testScheduler)) {
            "11111111-2222-3333-4444-555555555555"
        }
        every { fixture.editor.commit() } returns false
        assertTrue(settings.saveRelayUrl("https://relay.example") is PortResult.Failure)
        assertTrue(settings.deviceId() is ReadValue.Unreadable)
        assertFalse(fixture.values.containsKey("device_id"))
        every { fixture.preferences.getString("relay_url", null) } throws IllegalStateException("private-preference")
        val result = settings.relayUrl() as ReadValue.Unreadable
        assertSafe(logger, result.failure.cause, "private-preference")
        assertEquals(3, logger.entries.count { it.level == "error" })
    }

    @Test fun invalidSavedOrGeneratedIdsAreErrorsAndNeverSilentlyReplaced() = runTest {
        val fixture = PreferencesFixture()
        fixture.values["device_id"] = "private-invalid-id"
        val logger = FakeLogger()
        val settings = AppSettings(fixture.preferences, logger, StandardTestDispatcher(testScheduler)) {
            error("Must not replace invalid saved data.")
        }
        val result = settings.deviceId() as ReadValue.Unreadable
        assertEquals("private-invalid-id", fixture.values["device_id"])
        assertSafe(logger, result.failure.cause, "private-invalid-id")
        fixture.values.clear()
        val generated = AppSettings(fixture.preferences, logger, StandardTestDispatcher(testScheduler)) { "private-generated-id" }
        assertTrue(generated.deviceId() is ReadValue.Unreadable)
        assertTrue(fixture.values.isEmpty())
        assertSafe(logger, null, "private-generated-id")
    }
}

private class PreferencesFixture {
    val preferences = mockk<SharedPreferences>()
    val editor = mockk<SharedPreferences.Editor>()
    val values = mutableMapOf<String, String>()
    private val pending = mutableMapOf<String, String>()
    init {
        every { preferences.getString(any(), null) } answers { values[firstArg()] }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers { pending[firstArg()] = secondArg(); editor }
        every { editor.commit() } answers { values.putAll(pending); pending.clear(); true }
    }
}
