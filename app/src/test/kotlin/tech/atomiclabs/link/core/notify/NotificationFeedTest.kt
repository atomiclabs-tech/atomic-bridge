package tech.atomiclabs.link.core.notify

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeNotificationSource
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationFeedTest {
    private val logger = FakeLogger()
    private val privateContent = "private-notification-content"
    private val source = FakeNotificationSource()
    private val feed = NotificationFeed(source, logger)

    @After fun logsContainNoNotificationContentEvenInExceptionGraphs() {
        for (entry in logger.entries) {
            assertFalse((entry.component + entry.operation + entry.context).contains(privateContent))
            assertFalse(entry.cause?.stackTraceToString()?.contains(privateContent) == true)
        }
    }

    @Test fun runningStreamSendsPostedAndRemovedEventsImmediatelyWithTheirPayloads() = runTest {
        assertEquals(PortResult.Success(Unit), feed.start())
        val sent = mutableListOf<Envelope>()
        backgroundScope.launch { feed.run { sent += it; PortResult.Success(Unit) } }
        runCurrent()
        source.emit(posted(1))
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(setOf("key", "package_name", "title", "text", "sub_text", "posted_at", "ongoing", "category"),
            sent.single().payload.keys)
        assertEquals(privateContent, sent.single().payload["text"]!!.jsonPrimitive.content)
        source.emit(NotificationEvent.Removed("key-1", "example.app", 200, 7))
        runCurrent()
        assertEquals(listOf("notification.posted", "notification.removed"), sent.map { it.type })
        assertTrue(sent.all { it.channel == "notifications" })
        assertEquals(2, sent.map { it.id }.toSet().size)
        assertEquals("7", sent.last().payload["reason"]!!.jsonPrimitive.content)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun collectionQueuesWhileStoppedAndFlushesInOrderAcrossStreamRuns() = runTest {
        feed.start()
        feed.start()
        source.emit(posted(1))
        source.emit(NotificationEvent.Removed("key-1", "example.app", 200))
        val first = mutableListOf<Envelope>()
        val run = backgroundScope.launch { feed.run { first += it; PortResult.Success(Unit) } }
        runCurrent()
        assertEquals(listOf("notification.posted", "notification.removed"), first.map { it.type })
        run.cancelAndJoin()
        source.emit(posted(2))
        source.emit(posted(3))
        assertEquals(2, first.size)
        val next = mutableListOf<Envelope>()
        backgroundScope.launch { feed.run { next += it; PortResult.Success(Unit) } }
        runCurrent()
        assertEquals(listOf("key-2", "key-3"), next.map { key(it) })
        assertEquals(2, first.size)
    }

    @Test fun twoHundredAndFirstQueuedEventDropsOnlyTheOldest() = runTest {
        feed.start()
        repeat(201) { source.emit(posted(it)) }
        val sent = mutableListOf<Envelope>()
        backgroundScope.launch { feed.run { sent += it; PortResult.Success(Unit) } }
        runCurrent()
        assertEquals((1..200).map { "key-$it" }, sent.map { key(it) })
        assertEquals("state=buffer_full oldest_dropped=true", logger.entries.single().context)
        assertEquals("warn", logger.entries.single().level)
    }

    @Test fun failedSendRequeuesOnceThenWarnsAndDropsWithoutStoppingOtherEvents() = runTest {
        feed.start()
        source.emit(posted(1)); source.emit(posted(2))
        val attempts = mutableListOf<Envelope>()
        backgroundScope.launch {
            feed.run {
                attempts += it
                if (key(it) == "key-1") failed() else PortResult.Success(Unit)
            }
        }
        runCurrent()
        assertEquals(listOf("key-1", "key-2", "key-1"), attempts.map { key(it) })
        assertEquals(attempts.first(), attempts.last())
        assertEquals(listOf("state=retry_queued", "state=dropped"), logger.entries.map { it.context })
        assertTrue(logger.entries.all { it.level == "warn" })
        source.emit(posted(3)); runCurrent()
        assertEquals("key-3", key(attempts.last()))
        assertEquals(4, attempts.size)
    }

    @Test fun oneFailedSendCanSucceedOnItsOnlyRetry() = runTest {
        feed.start()
        source.emit(posted(1))
        val attempts = mutableListOf<Envelope>()
        backgroundScope.launch {
            feed.run {
                attempts += it
                if (attempts.size == 1) failed() else PortResult.Success(Unit)
            }
        }
        runCurrent()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0], attempts[1])
        assertEquals("state=retry_queued", logger.entries.single().context)
    }

    @Test fun cancellingAnInFlightSendRetainsOneRetryForTheNextConnection() = runTest {
        feed.start()
        source.emit(posted(1))
        val first = mutableListOf<Envelope>()
        val run = backgroundScope.launch { feed.run { first += it; awaitCancellation() } }
        runCurrent()
        run.cancelAndJoin()
        val next = mutableListOf<Envelope>()
        backgroundScope.launch { feed.run { next += it; PortResult.Success(Unit) } }
        runCurrent()
        assertEquals(first, next)
        assertEquals(1, next.size)
        assertEquals("state=retry_queued", logger.entries.single().context)
    }

    @Test fun hangingSendTimesOutRetriesOnceAndDropsUsingVirtualTime() = runTest {
        feed.start()
        source.emit(posted(1))
        var attempts = 0
        backgroundScope.launch { feed.run { attempts++; awaitCancellation() } }
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(2, logger.entries.count { it.level == "error" && it.operation == "send" })
        assertEquals(listOf("state=retry_queued", "state=dropped"),
            logger.entries.filter { it.level == "warn" }.map { it.context })
    }

    @Test fun registrationAndCloseFailuresAreReturnedAndLoggedWithSanitizedCauses() = runTest {
        val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException(privateContent))
        source.registrationFailure = failure
        assertEquals(PortResult.Failure(failure), feed.start())
        source.registrationFailure = null
        assertEquals(PortResult.Success(Unit), feed.start())
        source.closeResult = PortResult.Failure(failure)
        assertEquals(PortResult.Failure(failure), feed.close())
        source.closeResult = PortResult.Success(Unit)
        assertEquals(PortResult.Success(Unit), feed.close())
        assertEquals(PortResult.Success(Unit), feed.close())
        assertEquals(listOf("register", "close"), logger.entries.map { it.operation })
        assertTrue(logger.entries.all { it.level == "error" })
        source.emit(posted(1))
        feed.start()
        val sent = mutableListOf<Envelope>()
        backgroundScope.launch { feed.run { sent += it; PortResult.Success(Unit) } }
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun registrationTimeoutIsReportedWithoutCreatingAUsableStream() = runTest {
        val hanging = object : NotificationSource {
            override suspend fun isEnabled() = PortResult.Success(false)
            override suspend fun register(listener: (NotificationEvent) -> Unit): PortResult<NotificationSubscription> =
                awaitCancellation()
        }
        val feed = NotificationFeed(hanging, logger)
        val result = feed.start() as PortResult.Failure
        assertEquals(FailureKind.TIMED_OUT, result.failure.kind)
        assertEquals(5_000L, currentTime)
        assertEquals("register", logger.entries.single().operation)
        var rejected = false
        try { feed.run { fail("unregistered source must not send"); PortResult.Success(Unit) } }
        catch (cause: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    private fun posted(index: Int) = NotificationEvent.Posted("key-$index", "example.app",
        privateContent, privateContent, privateContent, index.toLong(), false, "msg")

    private fun key(frame: Envelope) = frame.payload["key"]!!.jsonPrimitive.content
    private fun failed() = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException(privateContent)))
}
