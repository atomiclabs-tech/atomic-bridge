package tech.atomiclabs.link.core.fakes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class FakeNotificationSourceTest {
    @Test fun subscriptionsStopDeliveryAndDependencyFailuresStayVisible() = runTest {
        val fake = FakeNotificationSource()
        val received = mutableListOf<NotificationEvent>()
        val event = NotificationEvent.Removed("key", "app.example", 123, 7)
        val subscription = (fake.register { received += it } as PortResult.Success).value
        fake.emit(event)
        assertEquals(listOf(event), received)
        subscription.close()
        fake.emit(event)
        assertEquals(1, received.size)
        val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException("fixture"))
        fake.registrationFailure = failure
        fake.enabled = PortResult.Failure(failure)
        assertEquals(PortResult.Failure(failure), fake.register { fail("must not register") })
        assertEquals(PortResult.Failure(failure), fake.isEnabled())
    }
}
