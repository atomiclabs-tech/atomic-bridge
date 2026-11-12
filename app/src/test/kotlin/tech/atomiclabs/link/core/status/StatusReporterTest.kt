package tech.atomiclabs.link.core.status

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.connection.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

@OptIn(ExperimentalCoroutinesApi::class)
class StatusReporterTest {
    @Test fun listenerConnectionChangesTriggerStatusAndSourceFailureIsLoud() = runTest {
        val source = FakeNotificationSource()
        val logger = FakeLogger()
        val reporter = StatusReporter(DeviceIdentity { PortResult.Success(description()) },
            DeviceStateSource { PortResult.Success(DeviceSnapshot(50, true, null, null)) },
            PermissionState { PortResult.Success(permissions()) }, FakeClock(testScheduler), logger, source) { "id" }
        val sent = mutableListOf<Envelope>()
        var failure: Throwable? = null
        backgroundScope.launch {
            try { reporter.run("device") { sent += it; PortResult.Success(Unit) } }
            catch (cause: IllegalStateException) { failure = cause }
        }
        runCurrent()
        assertFalse(sent.last().payload["bridge"]!!.jsonObject["notification_listener_granted"]!!.jsonPrimitive.boolean)
        source.enabled = PortResult.Success(true)
        reporter.refresh(); runCurrent()
        assertTrue(sent.last().payload["bridge"]!!.jsonObject["notification_listener_granted"]!!.jsonPrimitive.boolean)
        assertEquals(2, sent.size)
        source.enabled = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-listener-data")))
        reporter.refresh(); runCurrent()
        assertNotNull(failure)
        assertEquals("notifications", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-listener-data"))
        assertEquals(2, sent.size)
    }

    @Test fun sendsImmediatelyAtThirtySecondsAndImmediatelyOnSignalledFieldChange() = runTest {
        val logger = FakeLogger()
        var snapshot = DeviceSnapshot(50, true, "app.example", null)
        val reporter = StatusReporter(DeviceIdentity { PortResult.Success(description()) },
            DeviceStateSource { PortResult.Success(snapshot) }, PermissionState { PortResult.Success(permissions()) },
            FakeClock(testScheduler), logger) { "id" }
        val sent = mutableListOf<Envelope>()
        backgroundScope.launch { reporter.run("device") { sent += it; PortResult.Success(Unit) } }
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, sent.size)
        snapshot = snapshot.copy(batteryPercent = 49)
        reporter.refresh(); runCurrent()
        assertEquals(3, sent.size)
        reporter.refresh(); runCurrent()
        assertEquals(3, sent.size)
        snapshot = snapshot.copy(screenOn = false)
        advanceTimeBy(250); runCurrent()
        assertEquals(4, sent.size)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun failingOutsideReadsOrSendAreLoggedAndNeverBecomeStatusSuccess() = runTest {
        for (failed in listOf("identity", "device", "permissions", "send")) {
            val logger = FakeLogger()
            val error = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-status-value")))
            val reporter = StatusReporter(
                DeviceIdentity { if (failed == "identity") error else PortResult.Success(description()) },
                DeviceStateSource { if (failed == "device") error else PortResult.Success(DeviceSnapshot(50, true, null, null)) },
                PermissionState { if (failed == "permissions") error else PortResult.Success(permissions()) },
                FakeClock(testScheduler), logger) { "id" }
            var sendCalls = 0
            var thrown: IllegalStateException? = null
            try { reporter.run("device") { sendCalls++; if (failed == "send") error else PortResult.Success(Unit) } }
            catch (cause: IllegalStateException) { thrown = cause }
            assertNotNull(thrown)
            assertEquals(if (failed == "send") 1 else 0, sendCalls)
            val entry = logger.entries.single()
            assertEquals(failed, entry.operation)
            assertFalse(entry.cause!!.stackTraceToString().contains("private-status-value"))
            assertFalse(thrown!!.stackTraceToString().contains("private-status-value"))
        }
    }

    @Test fun missingPlatformCallbackIsBounded() = runTest {
        val logger = FakeLogger()
        val reporter = StatusReporter(DeviceIdentity { PortResult.Success(description()) },
            DeviceStateSource { awaitCancellation() }, PermissionState { PortResult.Success(permissions()) },
            FakeClock(testScheduler), logger) { "id" }
        var failed = false
        try { reporter.run("device") { fail("send must not run"); PortResult.Success(Unit) } }
        catch (cause: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(10_000L, currentTime)
        assertEquals("device", logger.entries.single().operation)
    }
}
