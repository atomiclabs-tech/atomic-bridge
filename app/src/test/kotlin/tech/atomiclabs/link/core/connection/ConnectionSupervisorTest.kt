package tech.atomiclabs.link.core.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*
import tech.atomiclabs.link.core.setup.StatusPresenter
import tech.atomiclabs.link.core.status.StatusReporter

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {
    @Test fun suppliedCodeIsNormalizedForPairingAndSavedOnlyAfterAuthenticationAccepts() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        val raw = " " + suppliedFixture().lowercase() + " "
        h.start()
        h.supervisor.pair(raw); runCurrent()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        val sent = h.socket.frames.single().payload["pairing_code"]?.jsonPrimitive?.content
        assertTrue("The supplied code must be normalized before authentication.", sent == raw.trim().uppercase())
        assertTrue(h.saves.isEmpty())
        assertSame(ReadValue.Absent, h.stored)
        h.ok()
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        assertEquals(1, h.saves.size)
        assertTrue("Only the accepted session may replace storage.", h.saves.single().sessionToken == h.replacement)
        assertEquals(1, h.reads)
        h.assertPrivate(raw, raw.trim().uppercase())
    }

    @Test fun pairingRejectionDoesNotRegenerateCredentialsOrOpenAnotherSocketOnConnect() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        h.start(); h.supervisor.pair(suppliedFixture()); runCurrent()
        h.reject()
        assertEquals(ConnectionPhase.PAIRING_REJECTED, h.phase)
        assertTrue(h.saves.isEmpty())
        val attempts = h.connectTimes.size
        h.supervisor.connect(); runCurrent()
        assertEquals("A rejected pairing must not create another credential or attempt.", attempts, h.connectTimes.size)
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        assertEquals(1, h.reads)
    }

    @Test fun unpairedConnectWithoutProvidedCredentialsRemainsIdle() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        h.start()
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        h.supervisor.connect(); runCurrent()
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        assertTrue(h.connectTimes.isEmpty())
        assertTrue(h.madeSockets.isEmpty())
        assertTrue(h.saves.isEmpty())
        assertEquals(1, h.reads)
    }

    private fun suppliedFixture(): String = (listOf(65, 66, 67) + listOf(50, 51, 52)).map(Int::toChar).joinToString("")

    @Test fun retryConnectingCarriesTheTransportFailureAfterTheUnchangedBackoff() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-transport"))) }
        h.start()
        val failed = h.supervisor.states.value
        assertEquals(ConnectionPhase.BACKOFF, failed.phase)
        assertNotNull(failed.failure)
        assertEquals(ConnectionFailureOrigin.TRANSPORT, failed.failureOrigin)
        assertTrue(failed.retryScheduled)
        h.onConnect = { awaitCancellation() }
        h.advance(499)
        assertEquals(ConnectionPhase.BACKOFF, h.phase)
        h.advance(1)
        val retry = h.supervisor.states.value
        assertEquals(ConnectionPhase.CONNECTING, retry.phase)
        assertSame(failed.failure, retry.failure)
        assertEquals(ConnectionFailureOrigin.TRANSPORT, retry.failureOrigin)
        assertFalse(retry.retryScheduled)
        val snapshot = (h.supervisor.current() as PortResult.Success).value
        assertSame(retry.failure, snapshot.failure)
        assertEquals(ConnectionFailureOrigin.TRANSPORT, snapshot.failureOrigin)
        assertEquals(listOf(0L, 500L), h.connectTimes)
        assertEquals(1, h.reads)
        assertTrue(h.saves.isEmpty())
        h.assertPrivate("private-transport")
    }

    @Test fun firstConnectingAttemptHasNoFailureOrOriginAndNoScheduledRetry() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { awaitCancellation() }
        h.start()
        val first = h.supervisor.states.value
        assertEquals(ConnectionPhase.CONNECTING, first.phase)
        assertNull(first.failure)
        assertEquals(ConnectionFailureOrigin.NONE, first.failureOrigin)
        assertFalse(first.retryScheduled)
        assertEquals(listOf(0L), h.connectTimes)
    }

    @Test fun reauthRetryConnectingCarriesAnExistingTransportFailureWithoutChangingAuthBudget() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.reject()
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-reauth-transport"))) }
        h.advance(300_000)
        val failed = h.supervisor.states.value
        assertEquals(ConnectionPhase.REAUTH_REQUIRED, failed.phase)
        assertNotNull(failed.failure)
        assertEquals(ConnectionFailureOrigin.TRANSPORT, failed.failureOrigin)
        h.onConnect = { awaitCancellation() }
        h.advance(299_999)
        assertEquals(2, h.connectTimes.size)
        h.advance(1)
        val retry = h.supervisor.states.value
        assertEquals(ConnectionPhase.CONNECTING, retry.phase)
        assertSame(failed.failure, retry.failure)
        assertEquals(ConnectionFailureOrigin.TRANSPORT, retry.failureOrigin)
        assertFalse(retry.retryScheduled)
        assertEquals(2, retry.reauthRetries)
        assertEquals(listOf(0L, 300_000L, 600_000L), h.connectTimes)
        h.assertPrivate("private-reauth-transport")
    }

    @Test fun transportRetryFactsAreTypedAndResetOnRecoveryWithoutChangingRetryTiming() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-refused-host"))) }
        h.start()
        val failed = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.TRANSPORT, failed.failureOrigin)
        assertTrue(failed.retryScheduled)
        h.onConnect = { PortResult.Success(h.newSocket()) }
        h.advance(499); assertEquals(1, h.connectTimes.size)
        h.advance(1); h.ok()
        val online = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.NONE, online.failureOrigin)
        assertFalse(online.retryScheduled)
        assertEquals(1, h.reads)
        h.assertPrivate("private-refused-host")
    }

    @Test fun failedSavedConfigurationHasNoTransportRetryFactsEvenWithAnOldEndpoint() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        h.presenter().saveRelayUrl("https://new.example")
        h.settings.url = ReadValue.Unreadable(PortFailure(FailureKind.IO, IllegalStateException("private-url-read")))
        runCurrent()
        val snapshot = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.CONFIGURATION, snapshot.failureOrigin)
        assertFalse(snapshot.retryScheduled)
        assertEquals("https://relay.example", snapshot.relayUrl)
        h.advance(60_000)
        assertEquals(1, h.connectTimes.size)
        assertEquals(1, h.reads)
    }

    @Test fun invalidSocketDataIsNotReportedAsAnUnreachableHostAndPairClearsFailureFacts() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        h.socket.inbound.send(SocketEvent.Failed(PortFailure(FailureKind.INVALID_DATA,
            IllegalStateException("private-frame"))))
        runCurrent()
        val snapshot = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.INTERNAL, snapshot.failureOrigin)
        assertTrue(snapshot.retryScheduled)
        val presenter = h.presenter(); presenter.refresh()
        assertEquals("Connection details could not be read.", presenter.state.value.error)
        h.supervisor.pair(suppliedFixture()); runCurrent()
        val reset = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.NONE, reset.failureOrigin)
        assertFalse(reset.retryScheduled)
        assertNull(reset.failure)
        h.assertPrivate("private-frame")
    }

    @Test fun invalidSuppliedCodeIsVisibleWithoutOpeningASocketOrChangingStoredCredentials() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        h.start()
        h.supervisor.pair(suppliedFixture().dropLast(1)); runCurrent()
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        val snapshot = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionFailureOrigin.CONFIGURATION, snapshot.failureOrigin)
        assertEquals("The pairing code must be six letters or digits.", snapshot.failure!!.cause.message)
        val presenter = h.presenter(); presenter.refresh()
        assertEquals("The pairing code must be six letters or digits.", presenter.state.value.error)
        assertTrue(h.connectTimes.isEmpty() && h.saves.isEmpty())
        assertSame(ReadValue.Absent, h.stored)
        h.assertPrivate(suppliedFixture(), suppliedFixture().dropLast(1))
    }

    @Test fun cancelledDeepLinkLeavesLoadedOwnerSessionAndRelayAddressUntouched() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        val stored = h.stored
        val saves = h.saves.size
        val url = h.settings.url
        val attempts = h.connectTimes.size
        val presenter = h.presenter(); presenter.refresh()
        presenter.offerPairingLink("atomic://pair?relay=https%3A%2F%2Fother.example&code=${suppliedFixture()}")
        presenter.dismissPairingLink(); runCurrent()
        assertNull(presenter.state.value.pendingPairing)
        assertSame(stored, h.stored)
        assertEquals(url, h.settings.url)
        assertEquals(saves, h.saves.size)
        assertEquals(attempts, h.connectTimes.size)
        assertEquals(1, h.reads)
        h.assertPrivate(suppliedFixture())
    }

    @Test fun sessionSaveDeadlineRetainsSessionOriginAndTheExistingRetryOutcome() = runTest {
        val h = ConnectionHarness(this)
        h.beforeSave = { delay(10_001) }
        h.start(); h.ok(); h.advance(9_999)
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        h.advance(1)
        val snapshot = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionPhase.BACKOFF, snapshot.connection)
        assertEquals(ConnectionFailureOrigin.SESSION, snapshot.failureOrigin)
        assertTrue(snapshot.retryScheduled)
        assertEquals(FailureKind.IO, snapshot.failure!!.kind)
        assertEquals(ReadValue.Present(h.original), h.stored)
        val presenter = h.presenter(); presenter.refresh()
        assertEquals("The stored session could not be read or saved.", presenter.state.value.error)
        h.advance(499); assertEquals(1, h.connectTimes.size)
        h.advance(1); assertEquals(2, h.connectTimes.size)
    }

    @Test fun savingANewEndpointMovesTheOnlineSocketAndKeepsTheLoadedSession() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        val old = h.socket
        val stored = h.stored
        val saves = h.saves.size
        h.presenter().saveRelayUrl("https://new.example/health"); runCurrent()
        assertEquals(1, old.closes)
        assertEquals(listOf("wss://relay.example/ws", "wss://new.example/ws"), h.connectUrls)
        assertEquals("https://new.example", (h.supervisor.current() as PortResult.Success).value.relayUrl)
        assertTrue(h.socket.frames.single().payload["session_token"]!!.jsonPrimitive.content == h.replacement)
        assertSame(stored, h.stored)
        assertEquals(saves, h.saves.size)
        assertEquals(1, h.reads)
        h.assertPrivate(h.original.sessionToken, h.original.refreshToken!!, h.replacement)
    }

    @Test fun savingTheSameNormalizedEndpointTwiceDoesNotCloseConnectOrLog() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        val socket = h.socket
        val logCount = h.logger.entries.size
        val presenter = h.presenter()
        repeat(2) { presenter.saveRelayUrl("https://relay.example/health"); runCurrent() }
        assertSame(socket, h.socket)
        assertEquals(0, socket.closes)
        assertEquals(1, h.connectUrls.size)
        assertTrue(h.logger.entries.drop(logCount).all { it.level == "debug" })
        assertEquals(1, h.reads)
        assertEquals(1, h.saves.size)
    }

    @Test fun idleUnpairedAndRejectedPairingOnlyRetargetWithoutConnecting() = runTest {
        for (rejected in listOf(false, true)) {
            val h = ConnectionHarness(this, ReadValue.Absent)
            h.start()
            if (rejected) { h.supervisor.pair(suppliedFixture()); runCurrent(); h.reject() }
            val attempts = h.connectUrls.size
            h.presenter().saveRelayUrl("https://idle.example"); runCurrent()
            val snapshot = (h.supervisor.current() as PortResult.Success).value
            assertEquals("https://idle.example", snapshot.relayUrl)
            assertEquals(ConnectionPhase.UNPAIRED, snapshot.connection)
            assertEquals(attempts, h.connectUrls.size)
            assertSame(ReadValue.Absent, h.stored)
            assertEquals(1, h.reads)
            assertTrue(h.saves.isEmpty())
            h.job.cancelAndJoin()
        }
    }

    @Test fun activePairingRetargetsFromConnectingAuthenticatingAndBackoff() = runTest {
        for (phase in listOf(ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.BACKOFF)) {
            val h = ConnectionHarness(this, ReadValue.Absent)
            if (phase == ConnectionPhase.CONNECTING) h.onConnect = {
                val socket = h.newSocket()
                check(socket.inbound.tryReceive().isSuccess)
                PortResult.Success(socket)
            }
            h.start(); h.supervisor.pair(suppliedFixture()); runCurrent()
            if (phase == ConnectionPhase.BACKOFF) h.close(1000)
            assertEquals(phase, h.phase)
            h.presenter().saveRelayUrl("https://pairing-new.example"); runCurrent()
            assertEquals(listOf("wss://relay.example/ws", "wss://pairing-new.example/ws"), h.connectUrls)
            if (phase == ConnectionPhase.CONNECTING) { h.socket.inbound.send(SocketEvent.Opened); runCurrent() }
            assertTrue(h.socket.frames.single().payload["pairing_code"]!!.jsonPrimitive.content == suppliedFixture())
            assertEquals(1, h.reads)
            assertTrue(h.saves.isEmpty())
            h.job.cancelAndJoin()
        }
    }

    @Test fun secondSaveWhileConnectingClosesTheFirstAttemptAndStartsOnlyOneReplacement() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = {
            val socket = h.newSocket()
            check(socket.inbound.tryReceive().isSuccess)
            PortResult.Success(socket)
        }
        h.start()
        val old = h.socket
        val presenter = h.presenter()
        presenter.saveRelayUrl("https://first.example"); runCurrent()
        assertEquals(ConnectionPhase.CONNECTING, h.phase)
        val first = h.socket
        presenter.saveRelayUrl("https://second.example"); runCurrent()
        assertEquals(1, old.closes)
        assertEquals(1, first.closes)
        assertEquals(0, h.socket.closes)
        assertEquals(listOf("wss://relay.example/ws", "wss://first.example/ws", "wss://second.example/ws"), h.connectUrls)
        assertEquals("https://second.example", (h.supervisor.current() as PortResult.Success).value.relayUrl)
        old.inbound.send(SocketEvent.Opened); first.inbound.send(SocketEvent.Opened); runCurrent()
        assertEquals(3, h.connectUrls.size)
        assertEquals(1, h.reads)
        assertTrue(h.saves.isEmpty())
    }

    @Test fun unreadableOrInvalidSavedEndpointPublishesBackoffBeforeClosingWithoutTouchingTheSession() = runTest {
        val problem = PortFailure(FailureKind.IO, IllegalStateException("private-url-after-save"))
        for (url in listOf(ReadValue.Unreadable(problem), ReadValue.Present("invalid-private-url"))) {
            val h = ConnectionHarness(this)
            h.start(); h.ok()
            val socket = h.socket
            socket.beforeClose = {
                assertEquals(ConnectionPhase.BACKOFF, h.phase)
                assertNotNull(h.supervisor.states.value.failure)
                assertEquals("https://relay.example", (h.supervisor.current() as PortResult.Success).value.relayUrl)
            }
            val stored = h.stored
            val saves = h.saves.size
            h.presenter().saveRelayUrl("https://saved.example")
            h.settings.url = url
            runCurrent()
            val snapshot = (h.supervisor.current() as PortResult.Success).value
            assertEquals(ConnectionPhase.BACKOFF, snapshot.connection)
            assertNotNull(snapshot.failure)
            assertEquals("https://relay.example", snapshot.relayUrl)
            assertEquals(1, socket.closes)
            assertSame(socket, h.socket)
            assertEquals(1, h.connectUrls.size)
            assertSame(stored, h.stored)
            assertEquals(saves, h.saves.size)
            assertEquals(1, h.reads)
            assertTrue(h.logger.entries.any { it.level == "error" && it.cause != null })
            h.advance(60_000)
            assertEquals(1, h.connectUrls.size)
            h.assertPrivate("private-url-after-save", "invalid-private-url", h.replacement)
            h.job.cancelAndJoin()
        }
    }

    @Test fun savingWhileTheLoopIsStoppedOnlyStoresAndTheNextStartUsesTheSavedEndpoint() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok(); h.job.cancelAndJoin()
        val attempts = h.connectUrls.size
        val reads = h.reads
        val saves = h.saves.size
        val stored = h.stored
        h.presenter().saveRelayUrl("https://stopped.example"); runCurrent()
        assertEquals(ReadValue.Present("https://stopped.example"), h.settings.url)
        assertEquals(attempts, h.connectUrls.size)
        assertEquals(reads, h.reads)
        assertEquals(saves, h.saves.size)
        assertSame(stored, h.stored)
        val restarted = ConnectionHarness(this, h.stored)
        restarted.settings.url = h.settings.url
        restarted.start()
        assertEquals(listOf("wss://stopped.example/ws"), restarted.connectUrls)
        assertTrue(restarted.socket.frames.single().payload["session_token"]!!.jsonPrimitive.content == h.replacement)
    }

    @Test fun streamsStartOnlyAfterAuthStopAtSocketEndAndRestartWithFreshGeneration() = runTest {
        var starts = 0
        var stops = 0
        val sends = mutableListOf<suspend (Envelope) -> PortResult<Unit>>()
        val stream = OutboundStream { send ->
            starts++; sends += send
            try { send(Envelope("notifications", "fixture", "id")); awaitCancellation() }
            finally { stops++ }
        }
        val h = ConnectionHarness(this, streams = listOf(stream))
        h.start()
        assertEquals(0, starts)
        assertEquals(listOf("auth"), h.socket.frames.map { it.type })
        h.ok()
        assertEquals(1, starts)
        val old = h.socket
        assertEquals(1, old.frames.count { it.channel == "notifications" })
        h.close(1000)
        assertEquals(1, stops)
        val oldCount = old.frames.size
        assertTrue(sends.first()(Envelope("notifications", "stale", "id")) is PortResult.Failure)
        assertEquals(oldCount, old.frames.size)
        h.advance(500)
        assertEquals(1, starts)
        h.ok()
        assertEquals(2, starts)
        val newCount = h.socket.frames.size
        assertTrue(sends.first()(Envelope("notifications", "stale", "id")) is PortResult.Failure)
        assertEquals(newCount, h.socket.frames.size)
        assertTrue(sends.last()(Envelope("notifications", "current", "id")) is PortResult.Success)
        h.job.cancelAndJoin()
        assertEquals(2, stops)
    }

    @Test fun aThrowingStreamIsLoggedWithoutStoppingTheConnectionStatusOrOtherStreams() = runTest {
        var otherStarted = false
        val h = ConnectionHarness(this, streams = listOf(
            OutboundStream { throw AssertionError("private-notification-title") },
            OutboundStream { otherStarted = true; awaitCancellation() },
        ))
        h.start(); h.ok()
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        assertTrue(otherStarted)
        assertEquals(1, h.socket.frames.count { it.type == "bridge.status" })
        h.emit(Envelope("system", "ping", "id"))
        assertTrue(h.socket.frames.any { it.type == "pong" })
        h.advance(30_000)
        assertEquals(2, h.socket.frames.count { it.type == "bridge.status" })
        assertTrue(h.logger.entries.any { it.component == "OutboundStreams" && it.operation == "run" && it.cause != null })
        h.assertPrivate("private-notification-title")
    }

    @Test fun firstPairingWaitsForMacCodeAfterRealPresenterSave() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        h.settings.url = ReadValue.Absent
        h.settings.id = ReadValue.Absent
        h.start()
        val presenter = h.presenter()
        backgroundScope.launch { presenter.observe() }
        runCurrent()
        val code = suppliedFixture()
        val id = checkNotNull(presenter.state.value.deviceId)
        assertNotNull(presenter.state.value.codeHint)
        assertNotNull(presenter.state.value.error)
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        assertEquals(ReadValue.Present(id), h.settings.id)
        assertTrue(h.connectTimes.isEmpty())
        presenter.saveRelayUrl("https://pairing.example/health"); runCurrent()
        assertEquals(ReadValue.Present("https://pairing.example"), h.settings.url)
        assertNull(presenter.state.value.error)
        h.networkAvailable(); h.advance(60_000)
        assertTrue(h.connectTimes.isEmpty() && h.saves.isEmpty())
        presenter.pair(code); runCurrent()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        assertEquals(listOf("wss://pairing.example/ws"), h.connectUrls)
        assertTrue(h.socket.frames.single().payload["pairing_code"]!!.jsonPrimitive.content == code)
        assertEquals(id, h.socket.frames.single().payload["device_id"]!!.jsonPrimitive.content)
        assertEquals(1, h.reads)
        assertSame(ReadValue.Absent, h.stored)
        h.assertPrivate(code)
    }

    @Test fun endpointErrorsStillExposeActualSessionOutcomesAndCheckedDeviceMetadata() = runTest {
        val problem = PortFailure(FailureKind.IO, IllegalStateException("private-session-outcome"))
        val urls = listOf(ReadValue.Absent, ReadValue.Present("invalid private-url"),
            ReadValue.Unreadable(PortFailure(FailureKind.PERMISSION_DENIED, IllegalStateException("private-url-read"))))
        for (url in urls) for (initial in listOf<ReadValue<StoredSession>>(
            ReadValue.Absent, ReadValue.Present(originalSession()), ReadValue.Unreadable(problem))) {
            val h = ConnectionHarness(this, initial)
            h.settings.url = url
            h.settings.id = ReadValue.Absent
            h.start()
            val status = (h.supervisor.current() as PortResult.Success).value
            val presenter = h.presenter()
            val observation = backgroundScope.launch { presenter.observe() }
            runCurrent()
            assertNull(status.relayUrl)
            assertEquals((h.settings.id as ReadValue.Present).value, status.deviceId)
            assertEquals(description().appVersion, status.appVersion)
            val statusFailure = checkNotNull(status.failure)
            assertNotNull(presenter.state.value.error)
            assertTrue(h.logger.entries.any { it.level == "error" })
            assertEquals(1, h.reads)
            when (initial) {
                ReadValue.Absent -> {
                    assertEquals(ConnectionPhase.UNPAIRED, status.connection)
                    assertSame(ReadValue.Absent, h.supervisor.token())
                }
                is ReadValue.Present -> {
                    assertEquals(ConnectionPhase.BACKOFF, status.connection)
                    assertEquals(GrantExpiry.Never, status.bridgeGrant)
                    assertTrue((h.supervisor.token() as ReadValue.Present).value == initial.value.sessionToken)
                }
                is ReadValue.Unreadable -> {
                    assertEquals(ConnectionPhase.SESSION_UNREADABLE, status.connection)
                    val failure = (h.supervisor.token() as ReadValue.Unreadable).failure
                    assertEquals(problem.kind, failure.kind)
                    assertArrayEquals(problem.cause.stackTrace, failure.cause.stackTrace)
                    assertEquals(problem.kind, statusFailure.kind)
                    assertArrayEquals(failure.cause.stackTrace, statusFailure.cause.cause!!.stackTrace)
                    assertEquals(1, statusFailure.cause.suppressed.size)
                }
            }
            presenter.connect(); runCurrent()
            h.networkAvailable(); h.advance(60_000)
            assertTrue(h.connectTimes.isEmpty() && h.saves.isEmpty())
            assertTrue(h.stored == initial)
            assertEquals(1, h.reads)
            presenter.saveRelayUrl("https://saved.example"); runCurrent()
            if (initial is ReadValue.Present) assertEquals(1, h.connectTimes.size)
            else {
                assertTrue(h.connectTimes.isEmpty())
                presenter.connect(); runCurrent()
            }
            if (initial !is ReadValue.Present) {
                assertEquals(ConnectionPhase.UNPAIRED, h.phase)
                assertTrue(h.connectTimes.isEmpty())
                val supplied = suppliedFixture()
                presenter.pair(supplied); runCurrent()
                assertTrue(h.socket.frames.single().payload["pairing_code"]!!.jsonPrimitive.content == supplied)
                h.assertPrivate(supplied)
            } else assertFalse(h.socket.frames.single().payload.containsKey("pairing_code"))
            assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
            assertEquals(1, h.reads)
            assertTrue(h.saves.isEmpty() && h.stored == initial)
            h.assertPrivate("private-session-outcome", "invalid private-url", "private-url-read", h.original.sessionToken)
            h.job.cancelAndJoin()
            observation.cancelAndJoin()
        }
    }

    @Test fun aStoredPublicCleartextEndpointSurfacesAnErrorWithoutConnectingOrRewritingTheSession() = runTest {
        for (input in listOf("ws://relay.example.com", "http://phone.local")) {
            val h = ConnectionHarness(this)
            h.settings.url = ReadValue.Present(input)
            h.start()
            val status = (h.supervisor.current() as PortResult.Success).value
            assertEquals(ConnectionPhase.BACKOFF, status.connection)
            assertNull(status.relayUrl)
            assertNotNull(status.failure)
            val presenter = h.presenter()
            val observation = backgroundScope.launch { presenter.observe() }
            runCurrent()
            assertNotNull(presenter.state.value.error)
            h.networkAvailable(); h.advance(60_000)
            assertTrue(h.connectTimes.isEmpty() && h.saves.isEmpty())
            assertEquals(ReadValue.Present(h.original), h.stored)
            assertTrue(h.logger.entries.any { it.operation == "initialize" && it.level == "error" })
            h.assertPrivate(input, "relay.example.com", "phone.local")
            h.job.cancelAndJoin()
            observation.cancelAndJoin()
        }
    }

    @Test fun savingAnUnchangedEndpointDoesNotShortenNetworkOrRateLimitWaits() = runTest {
        for (rateLimited in listOf(false, true)) {
            val h = ConnectionHarness(this)
            val started = h.clock.monotonicMs()
            h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("Transport fixture."),
                httpStatus = if (rateLimited) 429 else null)) }
            h.start(); h.networkAvailable(); h.advance(1_234)
            val presenter = h.presenter()
            presenter.saveRelayUrl("https://relay.example/health"); runCurrent()
            assertEquals(1L, h.policy.attempt)
            assertEquals(ConnectionPhase.BACKOFF, h.phase)
            h.networkAvailable()
            val deadline = if (rateLimited) 300_000L else 10_000L
            h.advance(deadline - 1_235)
            assertEquals(1, h.connectTimes.size)
            h.advance(1)
            assertEquals(listOf(started, started + deadline), h.connectTimes)
            h.job.cancelAndJoin()
        }
    }

    @Test fun savingAnUnchangedEndpointDoesNotResetTheProtectedAuthRetryBudgetOrDeadline() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.reject(); h.advance(300_000); h.reject(); h.advance(1_234)
        h.presenter().saveRelayUrl("https://relay.example/health"); runCurrent()
        assertEquals(ConnectionPhase.REAUTH_REQUIRED, h.phase)
        assertEquals(1, h.supervisor.states.value.reauthRetries)
        assertEquals(0L, h.policy.attempt)
        h.networkAvailable(); h.advance(298_765)
        assertEquals(2, h.connectTimes.size)
        h.advance(1)
        assertEquals(listOf(0L, 300_000L, 600_000L), h.connectTimes)
        assertEquals(2, h.supervisor.states.value.reauthRetries)
    }

    @Test fun connectCannotReuseAnOldEndpointWhenTheSavedSettingBecomesUnreadable() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.close(1000)
        h.settings.url = ReadValue.Unreadable(PortFailure(FailureKind.IO, IllegalStateException("private-later-url")))
        val presenter = h.presenter()
        presenter.connect(); runCurrent()
        assertNull((h.supervisor.current() as PortResult.Success).value.relayUrl)
        assertNotNull(h.supervisor.states.value.failure)
        h.networkAvailable(); h.advance(3_600_000)
        assertEquals(1, h.connectTimes.size)
        assertEquals(1, h.reads)
        presenter.saveRelayUrl("https://replacement.example"); runCurrent()
        assertEquals(2, h.connectTimes.size)
        assertEquals(listOf("wss://relay.example/ws", "wss://replacement.example/ws"), h.connectUrls)
        h.assertPrivate("private-later-url")
    }

    @Test fun unpairedStartupOpensNothingAndExplicitPairSendsPairingAuthFirst() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        h.start()
        assertEquals(ConnectionPhase.UNPAIRED, h.phase)
        assertEquals(0, h.connectTimes.size)
        val code = suppliedFixture()
        h.supervisor.pair(code); runCurrent()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        val frame = h.socket.frames.single()
        assertEquals("auth", frame.type)
        assertTrue(frame.payload["pairing_code"]!!.jsonPrimitive.content == code)
        assertFalse(frame.payload.containsKey("session_token"))
        h.emit(Envelope("system", "ping", "id"))
        h.command("request", "/fixture")
        assertEquals(1, h.socket.frames.size)
        assertEquals(0, h.handlerCalls)
        h.ok()
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        assertEquals(listOf("auth", "bridge.status"), h.socket.frames.map { it.type })
        h.assertPrivate(code, h.replacement)
    }

    @Test fun storedSessionAndRefreshAreSentOnEverySocketWithNoPreAuthNonAuthFrames() = runTest {
        val h = ConnectionHarness(this)
        h.start()
        assertTrue(h.socket.frames.single().payload["session_token"]!!.jsonPrimitive.content == h.original.sessionToken)
        assertTrue(h.socket.frames.single().payload["refresh_token"]!!.jsonPrimitive.content == h.original.refreshToken)
        h.ok()
        h.close(1000)
        assertEquals(ConnectionPhase.BACKOFF, h.phase)
        h.advance(500)
        assertEquals(2, h.connectTimes.size)
        assertEquals(listOf("auth"), h.socket.frames.map { it.type })
        assertTrue(h.socket.frames.single().payload["session_token"]!!.jsonPrimitive.content == h.replacement)
    }

    @Test fun returnedTokenIsSavedBeforeOnlineAndBeforeTheFirstStatus() = runTest {
        val h = ConnectionHarness(this)
        val releaseSave = CompletableDeferred<Unit>()
        h.beforeSave = {
            assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
            releaseSave.await()
        }
        h.start(); h.ok()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        assertEquals(listOf("auth"), h.socket.frames.map { it.type })
        assertTrue(h.stored is ReadValue.Present && (h.stored as ReadValue.Present).value.sessionToken == h.original.sessionToken)
        releaseSave.complete(Unit); runCurrent()
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        val saved = (h.stored as ReadValue.Present).value
        assertTrue(saved.sessionToken == h.replacement)
        assertTrue(saved.refreshToken == h.original.refreshToken)
        assertTrue((h.supervisor.token() as ReadValue.Present).value == h.replacement)
        assertEquals("bridge.status", h.socket.frames.last().type)
    }

    @Test fun failedSaveRetainsOldSessionNeverGoesOnlineAndLogsSanitizedCause() = runTest {
        val h = ConnectionHarness(this)
        h.saveResult = PortResult.Failure(PortFailure(FailureKind.IO,
            IllegalStateException("${h.original.sessionToken} ${h.replacement}")))
        h.start(); h.ok()
        assertEquals(ConnectionPhase.BACKOFF, h.phase)
        assertTrue((h.stored as ReadValue.Present).value.sessionToken == h.original.sessionToken)
        assertEquals(ConnectionFailureOrigin.SESSION, h.supervisor.states.value.failureOrigin)
        assertTrue(h.supervisor.states.value.retryScheduled)
        assertEquals(listOf("auth"), h.socket.frames.map { it.type })
        assertEquals("saveSession", h.logger.entries.last { it.level == "error" }.operation)
        h.assertPrivate(h.original.sessionToken, h.replacement)
    }

    @Test fun transportFailuresRetryPastOneThousandWithoutStoppingOrSlowPolling() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("Transport fixture."))) }
        h.start()
        repeat(1_001) { h.advance(h.policy.delayFor(h.policy.attempt)) }
        assertEquals(1_002, h.connectTimes.size)
        assertEquals(1_002L, h.policy.attempt)
        assertEquals(30_000L, h.connectTimes.last() - h.connectTimes[h.connectTimes.lastIndex - 1])
        assertEquals(ConnectionPhase.BACKOFF, h.phase)
    }

    @Test fun closeCodesAllRetryAndCounterResetsOnlyAfterThirtySecondsOnline() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.close(1000); h.advance(500); h.ok()
        assertEquals(1L, h.policy.attempt)
        h.advance(29_999)
        assertEquals(1L, h.policy.attempt)
        h.close(1001)
        assertEquals(2L, h.policy.attempt)
        h.advance(1_000); h.ok(); h.advance(30_000)
        assertEquals(0L, h.policy.attempt)
        h.close(4001)
        assertEquals(1L, h.policy.attempt)
    }

    @Test fun http429WaitsExactlyFiveMinutesAndNetworkDoesNotShortenIt() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.REJECTED, IllegalStateException("Upgrade rejected."), httpStatus = 429)) }
        h.start()
        h.onConnect = { PortResult.Success(h.newSocket()) }
        h.networkAvailable(); h.advance(10_000); h.networkAvailable(); h.advance(289_999)
        assertEquals(1, h.connectTimes.size)
        h.advance(1)
        assertEquals(2, h.connectTimes.size)
        assertEquals(300_000L, h.connectTimes.last())
    }

    @Test fun ordinaryBackoffNetworkEventWaitsTenSecondsAndDuplicateCallbacksDoNotAddOrDelayAttempts() = runTest {
        val h = ConnectionHarness(this)
        h.onConnect = { PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("Transport fixture."))) }
        h.start()
        h.onConnect = { PortResult.Success(h.newSocket()) }
        h.networkAvailable(); h.advance(500)
        assertEquals(1, h.connectTimes.size)
        h.networkAvailable(); h.advance(9_499)
        assertEquals(1, h.connectTimes.size)
        h.advance(1)
        assertEquals(2, h.connectTimes.size)
        assertEquals(10_000L, h.connectTimes.last())
        h.ok(); h.networkAvailable(); h.networkAvailable(); h.advance(20_000)
        assertEquals(2, h.connectTimes.size)
    }

    @Test fun watchdogBoundsBothStuckConnectingAndAuthenticatingAtThirtySeconds() = runTest {
        for (stuckConnecting in listOf(true, false)) {
            val h = ConnectionHarness(this)
            if (stuckConnecting) h.onConnect = { awaitCancellation() }
            h.start()
            assertEquals(if (stuckConnecting) ConnectionPhase.CONNECTING else ConnectionPhase.AUTHENTICATING, h.phase)
            h.advance(29_999)
            assertEquals(0L, h.policy.attempt)
            h.advance(1)
            assertEquals(ConnectionPhase.BACKOFF, h.phase)
            assertEquals(1L, h.policy.attempt)
            h.job.cancelAndJoin()
        }
    }

    @Test fun authTimeoutUsesOrdinaryBackoffForBothPairingAndStoredSessions() = runTest {
        for (stored in listOf<ReadValue<StoredSession>>(ReadValue.Absent, ReadValue.Present(originalSession()))) {
            val h = ConnectionHarness(this, stored)
            h.start()
            if (stored == ReadValue.Absent) { h.supervisor.pair(suppliedFixture()); runCurrent() }
            val code = h.socket.frames.single().payload["pairing_code"]
            h.reject("Authentication TIMEOUT ${h.original.sessionToken}")
            assertEquals(ConnectionPhase.BACKOFF, h.phase)
            assertEquals(ConnectionFailureOrigin.AUTHENTICATION, h.supervisor.states.value.failureOrigin)
            assertTrue(h.supervisor.states.value.retryScheduled)
            h.networkAvailable(); h.advance(9_999)
            assertEquals(1, h.connectTimes.size)
            h.advance(1)
            assertEquals(2, h.connectTimes.size)
            assertTrue(h.socket.frames.single().payload["pairing_code"] == code)
            h.assertPrivate(h.original.sessionToken, suppliedFixture())
            h.job.cancelAndJoin()
        }
    }

    @Test fun pairingRejectionWaitsForAnotherExplicitSuppliedCode() = runTest {
        val h = ConnectionHarness(this, ReadValue.Absent)
        val oldCode = suppliedFixture()
        h.start(); h.supervisor.pair(oldCode); runCurrent()
        h.reject("Rejected $oldCode")
        assertEquals(ConnectionPhase.PAIRING_REJECTED, h.phase)
        h.networkAvailable(); h.advance(3_600_000)
        assertEquals(1, h.connectTimes.size)
        h.supervisor.connect(); runCurrent()
        assertEquals(1, h.connectTimes.size)
        val newCode = oldCode.reversed()
        h.supervisor.pair(newCode); runCurrent()
        assertEquals(2, h.connectTimes.size)
        assertTrue(h.socket.frames.single().payload["pairing_code"]!!.jsonPrimitive.content == newCode)
        h.assertPrivate(oldCode, newCode)
        assertTrue(h.logger.entries.any { it.level == "error" && it.operation == "authenticate" })
    }

    @Test fun storedAuthFailuresAllowOnlyThreeFiveMinuteRetriesAndUserRetryStartsAFreshBudget() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.reject()
        for (retry in 1..3) {
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, h.phase)
            assertEquals("re-pair needed", h.supervisor.states.value.message)
            h.networkAvailable(); h.advance(299_999)
            assertEquals(retry, h.connectTimes.size)
            h.advance(1)
            assertEquals(retry + 1, h.connectTimes.size)
            assertEquals(retry, h.supervisor.states.value.reauthRetries)
            assertEquals(ConnectionPhase.REAUTH_REQUIRED, (h.supervisor.current() as PortResult.Success).value.connection)
            h.reject()
        }
        h.networkAvailable(); h.advance(3_600_000)
        assertEquals(4, h.connectTimes.size)
        assertEquals(ConnectionPhase.REAUTH_REQUIRED, h.phase)
        assertEquals(ReadValue.Present(h.original), h.stored)
        assertTrue(h.saves.isEmpty())
        assertTrue(h.logger.entries.any { it.level == "error" && it.operation == "authRetriesExhausted" })
        h.supervisor.connect(); runCurrent()
        assertEquals(5, h.connectTimes.size)
        assertEquals(0, h.supervisor.states.value.reauthRetries)
        h.reject(); h.advance(300_000)
        assertEquals(6, h.connectTimes.size)
        assertEquals(1, h.supervisor.states.value.reauthRetries)
        h.assertPrivate(h.original.sessionToken, h.original.refreshToken!!)
    }

    @Test fun pairAndAppRestartBeginFreshAuthRetryBudgetsWithoutDeletingOldSession() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.reject()
        repeat(3) { h.advance(300_000); h.reject() }
        h.supervisor.pair(suppliedFixture()); runCurrent()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        assertEquals(ReadValue.Present(h.original), h.stored)
        assertEquals(0, h.supervisor.states.value.reauthRetries)
        assertTrue(h.socket.frames.single().payload.containsKey("pairing_code"))
        h.job.cancelAndJoin()
        val restarted = ConnectionHarness(this, h.stored)
        restarted.start(); restarted.reject(); restarted.advance(300_000)
        assertEquals(2, restarted.connectTimes.size)
        assertEquals(1, restarted.supervisor.states.value.reauthRetries)
    }

    @Test fun completedUnreadableSessionIsConsumedOnceWithoutSavingDeletingOrConnecting() = runTest {
        val problem = PortFailure(FailureKind.IO, IllegalStateException("private-stored-fixture"))
        val h = ConnectionHarness(this, ReadValue.Unreadable(problem))
        val phases = mutableListOf<ConnectionPhase>()
        backgroundScope.launch { h.supervisor.changes.collect { if (it is PortResult.Success) phases += it.value.connection } }
        h.start()
        assertEquals(ConnectionPhase.SESSION_UNREADABLE, h.phase)
        h.advance(60_000)
        assertEquals(1, h.reads)
        assertTrue(h.stored is ReadValue.Unreadable)
        assertTrue(h.saves.isEmpty())
        assertTrue(h.connectTimes.isEmpty())
        assertEquals(1, h.logger.entries.count { it.operation == "readSession" && it.level == "error" })
        val unreadable = (h.supervisor.token() as ReadValue.Unreadable).failure
        assertEquals(problem.kind, unreadable.kind)
        assertArrayEquals(problem.cause.stackTrace, unreadable.cause.stackTrace)
        val status = (h.supervisor.current() as PortResult.Success).value
        assertEquals(ConnectionPhase.SESSION_UNREADABLE, status.connection)
        assertEquals(ConnectionPhase.SESSION_UNREADABLE, phases.last())
        assertSame(unreadable, status.failure)
        assertEquals(ConnectionFailureOrigin.SESSION, status.failureOrigin)
        assertFalse(status.retryScheduled)
        h.assertPrivate("private-stored-fixture")
        h.supervisor.pair(suppliedFixture()); runCurrent(); h.ok()
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        assertTrue(h.stored is ReadValue.Present)
    }

    @Test fun completedLoadCanOutlastTenSecondsBeforeReturningPresentOrAbsent() = runTest {
        for (initial in listOf(ReadValue.Present(originalSession()), ReadValue.Absent)) {
            val h = ConnectionHarness(this, initial)
            h.beforeRead = { delay(15_000) }
            h.start(); h.advance(14_999)
            assertEquals(1, h.reads)
            assertTrue(h.connectTimes.isEmpty())
            assertTrue(h.logger.entries.none { it.level == "error" })
            h.advance(1)
            assertEquals(if (initial == ReadValue.Absent) ConnectionPhase.UNPAIRED else ConnectionPhase.AUTHENTICATING, h.phase)
            assertEquals(if (initial == ReadValue.Absent) 0 else 1, h.connectTimes.size)
            assertEquals(1, h.reads)
            h.job.cancelAndJoin()
        }
    }

    @Test fun stuckOrThrowingCompletedLoadIsLoudAndDoesNotBecomeAnAbsentSession() = runTest {
        for (stuck in listOf(false, true)) {
            val h = ConnectionHarness(this)
            h.beforeRead = { if (stuck) awaitCancellation() else throw IllegalStateException("private-load-failure") }
            h.start()
            if (stuck) {
                h.advance(59_999)
                assertNull(h.supervisor.states.value.failure)
                h.advance(1)
            }
            assertNotNull(h.supervisor.states.value.failure)
            assertNotNull((h.supervisor.current() as PortResult.Success).value.failure)
            assertEquals(1, h.reads)
            assertEquals(ReadValue.Present(h.original), h.stored)
            assertTrue(h.saves.isEmpty() && h.connectTimes.isEmpty())
            assertEquals("initialize", h.logger.entries.single { it.level == "error" }.operation)
            h.assertPrivate("private-load-failure")
            h.job.cancelAndJoin()
        }
    }

    @Test fun cancellationDuringCompletedLoadPropagatesWithoutInventingAnOutcome() = runTest {
        val h = ConnectionHarness(this)
        var cancelled = false
        h.beforeRead = { try { awaitCancellation() } finally { cancelled = true } }
        h.start(); h.job.cancelAndJoin()
        assertTrue(cancelled && h.job.isCancelled)
        assertEquals(1, h.reads)
        assertNull(h.supervisor.states.value.failure)
        assertTrue(h.saves.isEmpty() && h.connectTimes.isEmpty())
        assertEquals(ReadValue.Present(h.original), h.stored)
        assertTrue(h.logger.entries.none { it.level == "error" })
    }

    @Test fun missingGrantAndExpiredGrantWarnWhileRemainingOnline() = runTest {
        for (grants in listOf(JsonObject(emptyMap()), buildJsonObject { put("bridge", 1) })) {
            val h = ConnectionHarness(this)
            h.start(); h.ok { put("grants", grants) }
            assertEquals(ConnectionPhase.ONLINE, h.phase)
            val warning = h.logger.entries.single { it.operation == "grant" }
            assertEquals("warn", warning.level)
            assertEquals("state=online bridge grant expired", warning.context)
            h.job.cancelAndJoin()
        }
    }

    @Test fun malformedAndUnknownFramesDoNotInterruptAuthOrCommandRoutingAndPingRemainsResponsive() = runTest {
        val h = ConnectionHarness(this)
        h.start()
        h.socket.inbound.send(SocketEvent.Text("{bad json")); runCurrent()
        assertEquals(ConnectionPhase.AUTHENTICATING, h.phase)
        h.ok()
        h.emit(Envelope("pairing", "profiles.updated", "id"))
        h.emit(Envelope("unknown", "future", "id"))
        h.handler = { body -> if (body.string("mode") == "throw") throw AssertionError("private-command-value")
            if (body.string("mode") == "slow") delay(1000)
            CommandResult.Success() }
        h.command("slow", "/fixture", buildJsonObject { put("mode", "slow") })
        h.emit(Envelope("system", "ping", "id"))
        assertTrue(h.socket.frames.any { it.type == "pong" })
        h.command("throw", "/fixture", buildJsonObject { put("mode", "throw") })
        assertEquals(ConnectionPhase.ONLINE, h.phase)
        val failure = h.socket.frames.last { it.type == "bridge.response" }.payload["result"]!!.jsonObject
        assertEquals("internal_error", failure["code"]!!.jsonPrimitive.content)
        h.advance(1_000)
        assertEquals(2, h.socket.frames.count { it.type == "bridge.response" })
        h.assertPrivate("private-command-value")
    }

    @Test fun aFailedResponseSendIsNotRetriedAndARelayRetryReplaysTheFirstCommandResult() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        val firstSocket = h.socket
        firstSocket.beforeSend = { frame -> if (frame.type == "bridge.response") {
            firstSocket.sendResult = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-response")))
        } }
        h.command("repeat", "/fixture")
        assertEquals(1, h.handlerCalls)
        assertEquals(1, firstSocket.frames.count { it.type == "bridge.response" })
        assertEquals(ConnectionPhase.BACKOFF, h.phase)
        h.advance(500); h.ok()
        assertEquals(0, h.socket.frames.count { it.type == "bridge.response" })
        h.command("repeat", "/fixture")
        assertEquals(1, h.handlerCalls)
        assertEquals(1, h.socket.frames.count { it.type == "bridge.response" })
        assertEquals(200, h.socket.frames.last().payload["status"]!!.jsonPrimitive.int)
        h.assertPrivate("private-response")
    }

    @Test fun startupSettingsFailuresAreLoggedAndDeviceIdIsGeneratedAndStoredOnlyWhenAbsent() = runTest {
        val h = ConnectionHarness(this)
        h.settings.url = ReadValue.Unreadable(PortFailure(FailureKind.IO, IllegalStateException("private-setting")))
        h.start()
        assertTrue(h.connectTimes.isEmpty())
        assertNotNull(h.supervisor.states.value.failure)
        h.assertPrivate("private-setting")
        h.settings.url = ReadValue.Present("https://relay.example")
        h.settings.id = ReadValue.Absent
        h.supervisor.connect(); runCurrent()
        val savedId = (h.settings.id as ReadValue.Present).value
        assertEquals(savedId, h.socket.frames.single().payload["device_id"]!!.jsonPrimitive.content)
        h.close(1000); h.advance(500)
        assertEquals(savedId, h.socket.frames.single().payload["device_id"]!!.jsonPrimitive.content)
    }

    @Test fun networkReadAndCloseFailuresAreVisibleAndRemoteErrorsRedactActiveSecrets() = runTest {
        val h = ConnectionHarness(this)
        h.start(); h.ok()
        h.emit(Envelope("system", "error", "id", buildJsonObject {
            put("message", "Remote ${h.original.sessionToken} ${h.original.refreshToken} ${h.replacement}")
        }))
        h.networkChanges.emit(PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-network"))))
        runCurrent()
        h.socket.closeResult = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-close")))
        h.close(1000)
        assertTrue(h.logger.entries.any { it.operation == "networkAvailable" && it.level == "error" })
        assertTrue(h.logger.entries.any { it.operation == "close" && it.level == "error" })
        h.assertPrivate(h.original.sessionToken, h.original.refreshToken!!, h.replacement, "private-network", "private-close")
    }
}

internal fun description() = DeviceDescription("Fixture phone", "Fixture phone", "Fixture model", "Android fixture", "app.example", "1.0")
internal fun permissions() = PermissionSnapshot(true, true, true, false, true, true, true, true, true)
internal fun originalSession() = StoredSession("initial-session-fixture", "initial-refresh-fixture", null, mapOf("bridge" to null), 0)

private fun ConnectionHarness.presenter() = StatusPresenter(
    supervisor, PermissionState { PortResult.Success(permissions()) },
    RestrictedSettingsState { PortResult.Success(RestrictedSettingsStatus.UNSUPPORTED) }, supervisor,
    object : SetupActions {
        override suspend fun openStatus() = PortResult.Success(Unit)
        override suspend fun openAppInfo() = PortResult.Success(Unit)
        override suspend fun openPermissionSettings(permission: Permission) = PortResult.Success(Unit)
        override suspend fun requestPermission(permission: Permission) = PortResult.Success(Unit)
    }, settings, logger, { RelayUrl.parse(it).httpBase }, { it.toString() },
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConnectionHarness(private val test: TestScope, initial: ReadValue<StoredSession> = ReadValue.Present(originalSession()),
    streams: List<OutboundStream> = emptyList()) {
    val original = originalSession()
    val replacement = "replacement-session-fixture"
    val logger = FakeLogger()
    val clock = FakeClock(test.testScheduler, 1_700_000_000_000)
    val settings = FakeSettingsStore().apply {
        url = ReadValue.Present("wss://relay.example")
        id = ReadValue.Present("11111111-2222-3333-4444-555555555555")
    }
    var stored = initial
    var reads = 0
    var beforeRead: suspend () -> Unit = {}
    val saves = mutableListOf<StoredSession>()
    var beforeSave: suspend () -> Unit = {}
    var saveResult: PortResult<Unit> = PortResult.Success(Unit)
    val store = object : SessionStore {
        override suspend fun read(): ReadValue<StoredSession> { reads++; beforeRead(); return stored }
        override suspend fun save(session: StoredSession): PortResult<Unit> {
            saves += session; beforeSave()
            if (saveResult is PortResult.Success) stored = ReadValue.Present(session)
            return saveResult
        }
    }
    val networkChanges = MutableSharedFlow<PortResult<Boolean>>(extraBufferCapacity = 16)
    private val network = object : NetworkMonitor {
        override val changes = networkChanges
        override suspend fun isAvailable(): PortResult<Boolean> = PortResult.Success(true)
    }
    private var sequence = 0
    private fun newId(): String = "00000000-0000-0000-0000-${(++sequence).toString().padStart(12, '0')}"
    private val identity = DeviceIdentity { PortResult.Success(description()) }
    val codec = EnvelopeCodec(logger)
    val madeSockets = mutableListOf<ScriptedSocket>()
    val socket: ScriptedSocket get() = madeSockets.last()
    fun newSocket(): ScriptedSocket = ScriptedSocket(codec).also { madeSockets += it }
    val connectTimes = mutableListOf<Long>()
    val connectUrls = mutableListOf<String>()
    var onConnect: suspend () -> PortResult<RelaySocket> = { PortResult.Success(newSocket()) }
    private val factory = RelaySocketFactory { url -> connectTimes += clock.monotonicMs(); connectUrls += url; onConnect() }
    val policy = ReconnectPolicy { 0 }
    var handlerCalls = 0
    var handler: suspend (BodyParams) -> CommandResult = { CommandResult.Success() }
    private val router = CommandRouter(listOf(object : RouteGroup {
        override val routes = listOf(Route("POST", "/fixture") { handlerCalls++; handler(it) })
    }), FakeUiTree(), FakeDisplayGate(), logger)
    val reporter = StatusReporter(identity, DeviceStateSource { PortResult.Success(DeviceSnapshot(50, true, "app.example", null)) },
        PermissionState { PortResult.Success(permissions()) }, clock, logger, ::newId)
    val supervisor = ConnectionSupervisor(factory, network, store, settings, identity, clock, router, reporter, logger,
        ::newId, policy, streams)
    lateinit var job: Job
    val phase get() = supervisor.states.value.phase
    fun start() { job = test.backgroundScope.launch { supervisor.run() }; test.runCurrent() }
    fun advance(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
    suspend fun emit(envelope: Envelope) { socket.inbound.send(SocketEvent.Text(codec.encode(envelope))); test.runCurrent() }
    suspend fun ok(extra: JsonObjectBuilder.() -> Unit = {}) = emit(Envelope("system", "auth.ok", "relay", buildJsonObject {
        put("session_token", replacement); put("expires_at", JsonNull)
        put("grants", buildJsonObject { put("bridge", JsonNull) }); extra()
    }))
    suspend fun reject(reason: String = "Authentication rejected.") = emit(Envelope("system", "auth.fail", "relay", buildJsonObject { put("reason", reason) }))
    suspend fun close(code: Int) { socket.inbound.send(SocketEvent.Closed(code, "Remote fixture reason.")); test.runCurrent() }
    suspend fun networkAvailable() { networkChanges.emit(PortResult.Success(true)); test.runCurrent() }
    suspend fun command(id: String, path: String, body: JsonObject = JsonObject(emptyMap())) =
        emit(codec.commandEnvelope(BridgeCommand(id, "POST", path, body = body)))
    fun assertPrivate(vararg values: String) {
        for (entry in logger.entries) {
            val text = "${entry.component} ${entry.operation} ${entry.context} ${entry.cause?.stackTraceToString()}"
            values.forEach { assertFalse("A private fixture entered the log.", text.contains(it)) }
        }
    }
}

internal class ScriptedSocket(private val codec: EnvelopeCodec) : RelaySocket {
    val inbound = Channel<SocketEvent>(Channel.UNLIMITED)
    override val events = inbound.receiveAsFlow()
    val frames = mutableListOf<Envelope>()
    var sendResult: PortResult<Unit> = PortResult.Success(Unit)
    var closeResult: PortResult<Unit> = PortResult.Success(Unit)
    var beforeSend: suspend (Envelope) -> Unit = {}
    var beforeClose: suspend () -> Unit = {}
    var closes = 0
    init { check(inbound.trySend(SocketEvent.Opened).isSuccess) }
    override suspend fun send(frame: String): PortResult<Unit> {
        val envelope = checkNotNull(codec.decode(frame))
        frames += envelope; beforeSend(envelope)
        return sendResult
    }
    override suspend fun close(code: Int, reason: String): PortResult<Unit> { closes++; beforeClose(); return closeResult }
}
