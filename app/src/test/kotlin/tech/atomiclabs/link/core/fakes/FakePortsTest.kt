package tech.atomiclabs.link.core.fakes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class FakePortsTest {
    private val failure = PortFailure(FailureKind.IO, IllegalStateException("fake dependency failure"))

    @Test fun virtualClockSeparatesEpochAndMonotonicTimeWithoutRealSleeping() = runTest {
        val clock = FakeClock(testScheduler, 1_000)
        val sleeping = async { clock.sleep(300) }
        runCurrent()
        advanceTimeBy(300); runCurrent()
        sleeping.await()
        assertEquals(1_300, clock.epochMs())
        assertEquals(300, clock.monotonicMs())
    }

    @Test fun failedStoreWritesPreserveUnreadableOrPreviouslyStoredData() = runTest {
        val store = FakeSessionStore()
        store.value = ReadValue.Unreadable(failure)
        store.saveResult = PortResult.Failure(failure)
        val session = StoredSession("fixture", null, null, emptyMap(), 0)
        assertEquals(PortResult.Failure(failure), store.save(session))
        assertEquals(ReadValue.Unreadable(failure), store.read())
        store.saveResult = PortResult.Success(Unit)
        store.save(session)
        assertEquals(ReadValue.Present(session), store.read())
        val settings = FakeSettingsStore()
        settings.id = ReadValue.Present("existing-id")
        settings.writeResult = PortResult.Failure(failure)
        assertEquals(PortResult.Failure(failure), settings.saveDeviceId("new-id"))
        assertEquals(ReadValue.Present("existing-id"), settings.deviceId())
    }

    @Test fun fakeNodesAndSocketsExposeFailuresAndCountAttemptsWithoutRetrying() = runTest {
        val node = FakeUiNode(UiNodeProperties(Bounds(1, 2, 30, 40), windowId = 37))
        val snapshot = node.read() as PortResult.Success
        assertEquals(37, snapshot.value.windowId)
        node.readFailure = failure
        node.actionResult = PortResult.Failure(failure)
        assertEquals(PortResult.Failure(failure), node.read())
        val action = NodeAction.Simple(NodeActionKind.CLICK)
        assertEquals(PortResult.Failure(failure), node.perform(action))
        assertEquals(listOf(action), node.actions)
        val socket = FakeRelaySocket()
        socket.sendResult = PortResult.Failure(failure)
        assertEquals(PortResult.Failure(failure), socket.send("fixture-frame"))
        assertEquals(listOf("fixture-frame"), socket.sendAttempts)
    }
}
