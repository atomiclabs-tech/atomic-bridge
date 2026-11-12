package tech.atomiclabs.link.core.setup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.fakes.FakeConnectionControl
import tech.atomiclabs.link.core.fakes.FakeSettingsStore
import tech.atomiclabs.link.core.connection.RelayUrl
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class StatusPresenterTest {
    @Test fun connectingTransportFailureKeepsTheRetryMessageUntilOnlineAndPreservesWorkingTone() = runTest {
        val address = "https://attempted.example"
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.CONNECTING, relayUrl = address,
            failure = failure().failure, failureOrigin = ConnectionFailureOrigin.TRANSPORT, retryScheduled = false)
        presenter.refresh()
        assertEquals("Can't reach $address. Retrying.", presenter.state.value.error)
        assertEquals("Connecting", presenter.state.value.connectionText)
        assertEquals(StatusTone.WORKING, presenter.state.value.tone)
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.ONLINE, failure = null,
            failureOrigin = ConnectionFailureOrigin.NONE)
        presenter.refresh()
        assertNull(presenter.state.value.error)
        assertEquals(StatusTone.ONLINE, presenter.state.value.tone)
        assertTrue(logger.entries.all { !(it.context + it.cause?.stackTraceToString()).contains(address) })
    }

    @Test fun onlyTransportFailuresWithAScheduledRetryUseUnreachableRetryWordingAndRecoveryClearsIt() = runTest {
        val address = "https://attempted.example"
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.BACKOFF, relayUrl = address,
            failure = failure().failure, failureOrigin = ConnectionFailureOrigin.TRANSPORT, retryScheduled = true)
        presenter.refresh()
        assertEquals("Can't reach $address. Retrying.", presenter.state.value.error)
        assertEquals(address, presenter.state.value.attemptedRelayUrl)
        assertFalse(status.snapshot.toString().contains(address))
        status.snapshot = status.snapshot.copy(retryScheduled = false)
        presenter.refresh()
        assertEquals("The relay connection failed.", presenter.state.value.error)
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.ONLINE, failure = null,
            failureOrigin = ConnectionFailureOrigin.NONE, retryScheduled = false)
        presenter.refresh()
        assertNull(presenter.state.value.error)
        assertTrue(logger.entries.all { !(it.context + it.cause?.stackTraceToString()).contains(address) })
        assertFalse(presenter.state.value.toString().contains(address))
    }

    @Test fun configurationSessionAuthenticationAndStatusReadFailuresAreNotCalledUnreachableRetries() = runTest {
        val messages = mapOf(
            ConnectionFailureOrigin.CONFIGURATION to "The relay address or connection settings could not be read.",
            ConnectionFailureOrigin.SESSION to "The stored session could not be read or saved.",
            ConnectionFailureOrigin.AUTHENTICATION to "Sign-in failed. Retrying.",
            ConnectionFailureOrigin.INTERNAL to "Connection details could not be read.",
        )
        for ((origin, expected) in messages) {
            status.snapshot = status.snapshot.copy(connection = ConnectionPhase.BACKOFF, relayUrl = "https://attempted.example",
                failure = failure().failure, failureOrigin = origin, retryScheduled = origin == ConnectionFailureOrigin.AUTHENTICATION)
            presenter.refresh()
            assertEquals(expected, presenter.state.value.error)
        }
        status.result = failure()
        presenter.refresh()
        assertEquals("Connection state could not be read.", presenter.state.value.error)
    }

    @Test fun savingAnEditedAddressDoesNotPretendThatItIsAlreadyTheAttemptedEndpoint() = runTest {
        status.snapshot = status.snapshot.copy(relayUrl = "https://current.example")
        presenter.refresh()
        presenter.saveRelayUrl("https://edited.example")
        assertEquals("https://edited.example", presenter.state.value.relayUrl)
        assertEquals("https://current.example", presenter.state.value.attemptedRelayUrl)
    }

    private val logger = FakeLogger()
    private val status = Source()
    private val permissions = Permissions()
    private val restrictedSettings = Restrictions()
    private val control = FakeConnectionControl()
    private val actions = Actions()
    private val settings = FakeSettingsStore()
    private val formattedEpochs = mutableListOf<Long>()
    private var parser: (String) -> String = { it }
    private val presenter = StatusPresenter(
        status, permissions, restrictedSettings, control, actions, settings, logger, { parser(it) },
        { formattedEpochs += it; "2030-01-02 UTC" },
    )

    @Test fun everyConnectionStateHasDistinctWordingAndRecoveryInstructions() = runTest {
        val wording = ConnectionPhase.entries.map {
            status.snapshot = status.snapshot.copy(connection = it)
            presenter.refresh()
            presenter.state.value.connectionText
        }
        assertEquals(8, wording.toSet().size)
        assertEquals("re-pair needed", wording[ConnectionPhase.REAUTH_REQUIRED.ordinal])
        assertTrue(wording.last().contains("stored session cannot be read"))
        assertTrue(wording.last().contains("code from your Mac"))
    }

    @Test fun insecureSchemesWarnAndSecureSchemesDoNot() = runTest {
        for (scheme in listOf("ws", "http", "wss", "https")) {
            status.snapshot = status.snapshot.copy(relayUrl = "$scheme://fixture.invalid")
            presenter.refresh()
            assertEquals(scheme in listOf("ws", "http"), presenter.state.value.encryptionWarning != null)
        }
        assertEquals(0, control.connects)
    }

    private fun suppliedCode() = (0 until 6).map { (65 + it).toChar() }.joinToString("")
    private fun offeredLink() = "atomic://pair?relay=http%3A%2F%2F192.168.20.30%3A1234&code=${suppliedCode().lowercase()}"

    @Test fun typedCodeIsForwardedOnceAndRejectedAndInvalidCodeHaveClearInstructions() = runTest {
        val raw = "  " + suppliedCode().lowercase() + "  "
        presenter.pair(raw)
        assertTrue(control.suppliedCodes.single() == raw)
        assertEquals(0, control.connects)
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.PAIRING_REJECTED)
        presenter.refresh()
        assertEquals("Pairing rejected. Check the code and try again.", presenter.state.value.connectionText)
        assertNotNull(presenter.state.value.codeHint)
        val message = "The pairing code must be six letters or digits."
        status.snapshot = status.snapshot.copy(connection = ConnectionPhase.UNPAIRED,
            failure = PortFailure(FailureKind.INVALID_DATA, IllegalArgumentException(message)),
            failureOrigin = ConnectionFailureOrigin.CONFIGURATION)
        presenter.refresh()
        assertEquals(message, presenter.state.value.error)
    }

    @Test fun offerAndCancelDoNotSaveOrConnectAndConfirmSavesThenPairsExactlyOnce() = runTest {
        settings.url = ReadValue.Present("https://existing.example")
        presenter.refresh()
        val before = presenter.state.value
        presenter.offerPairingLink(offeredLink())
        assertNotNull(presenter.state.value.pendingPairing)
        assertEquals(before.relayUrl, presenter.state.value.relayUrl)
        assertTrue(settings.savedRelayUrls.isEmpty() && control.suppliedCodes.isEmpty())
        presenter.dismissPairingLink()
        assertNull(presenter.state.value.pendingPairing)
        assertEquals(before, presenter.state.value)
        assertEquals(ReadValue.Present("https://existing.example"), settings.url)
        presenter.confirmPairingLink()
        assertTrue(settings.savedRelayUrls.isEmpty() && control.suppliedCodes.isEmpty())
        presenter.offerPairingLink(offeredLink())
        control.pairAction = {
            assertEquals(ReadValue.Present("http://192.168.20.30:1234"), settings.url)
            assertNull(presenter.state.value.pendingPairing)
            PortResult.Success(Unit)
        }
        presenter.confirmPairingLink(); presenter.confirmPairingLink()
        assertEquals(listOf("http://192.168.20.30:1234"), settings.savedRelayUrls)
        assertTrue(control.suppliedCodes.single() == suppliedCode())
        assertEquals("http://192.168.20.30:1234", presenter.state.value.relayUrl)
        assertEquals("Connection is not encrypted", presenter.state.value.encryptionWarning)
        assertEquals(0, control.connects + control.relayUrlApplications)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun failedLinkSaveKeepsOfferAndPairFailureIsVisibleWithoutRetryOrCredentialLogs() = runTest {
        presenter.offerPairingLink(offeredLink())
        settings.writeResult = failure()
        presenter.confirmPairingLink()
        assertNotNull(presenter.state.value.pendingPairing)
        assertTrue(control.suppliedCodes.isEmpty())
        assertEquals("The relay URL could not be saved.", presenter.state.value.error)
        settings.writeResult = PortResult.Success(Unit)
        control.pairAction = { failure() }
        presenter.confirmPairingLink()
        assertEquals(1, control.suppliedCodes.size)
        assertNull(presenter.state.value.pendingPairing)
        assertEquals("Could not begin pairing.", presenter.state.value.error)
        presenter.confirmPairingLink()
        assertEquals(1, control.suppliedCodes.size)
        assertTrue(logger.entries.all { !(it.context + it.cause?.stackTraceToString()).contains(suppliedCode()) })
    }

    @Test fun invalidLinkIsReportedSafelyAndNeverChangesSettingsOrPairs() = runTest {
        val raw = offeredLink().replace("atomic:", "https:")
        presenter.offerPairingLink(raw)
        assertNull(presenter.state.value.pendingPairing)
        assertEquals("The pairing link is invalid.", presenter.state.value.error)
        assertTrue(settings.savedRelayUrls.isEmpty() && control.suppliedCodes.isEmpty())
        val diagnostic = logger.entries.single().cause!!.stackTraceToString()
        assertFalse(diagnostic.contains(raw) || diagnostic.contains(suppliedCode()))
    }

    @Test fun neverDateAndMissingGrantsAreDistinctAndOnlyMissingWarns() = runTest {
        status.snapshot = status.snapshot.copy(bridgeGrant = GrantExpiry.Never)
        presenter.refresh()
        assertEquals("never", presenter.state.value.grantExpiry)
        assertNull(presenter.state.value.grantWarning)
        status.snapshot = status.snapshot.copy(bridgeGrant = GrantExpiry.At(123))
        presenter.refresh()
        assertEquals("2030-01-02 UTC", presenter.state.value.grantExpiry)
        assertEquals(listOf(123L), formattedEpochs)
        assertNull(presenter.state.value.grantWarning)
        status.snapshot = status.snapshot.copy(bridgeGrant = GrantExpiry.Missing)
        presenter.refresh()
        assertNull(presenter.state.value.grantExpiry)
        assertEquals("Grant missing; re-pair.", presenter.state.value.grantWarning)
    }

    @Test fun thirteenButtonsCallTheirMatchingSetupMethodAndDeniedNotificationsRemainOptional() = runTest {
        presenter.refresh()
        assertEquals(13, presenter.state.value.permissions.size)
        val optional = presenter.state.value.permissions.single { it.optional }
        assertEquals(Permission.NOTIFICATIONS, optional.permission)
        assertEquals(false, optional.granted)
        Permission.entries.forEach { presenter.setup(it) }
        assertEquals(listOf(Permission.ACCESSIBILITY, Permission.BATTERY_EXEMPT,
            Permission.UNUSED_APP_RESTRICTIONS_DISABLED, Permission.BACKGROUND_LOCATION,
            Permission.NOTIFICATION_ACCESS, Permission.ALL_FILES), actions.opened)
        assertEquals(listOf(Permission.NOTIFICATIONS, Permission.SMS, Permission.CALL,
            Permission.CONTACTS, Permission.LOCATION, Permission.MICROPHONE, Permission.PHONE_STATE), actions.requested)
    }

    @Test fun invalidUrlDisplaysTheParserMessageWithoutSavingConnectingOrLoggingPrivateData() = runTest {
        parser = { throw IllegalArgumentException("The URL contains disallowed credentials.") }
        presenter.saveRelayUrl("private-input-fixture")
        assertEquals("The URL contains disallowed credentials.", presenter.state.value.error)
        assertSame(ReadValue.Absent, settings.url)
        assertEquals(0, control.connects)
        assertEquals("parseRelayUrl", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("disallowed credentials"))
    }

    @Test fun publicCleartextSaveShowsTheRealPolicyMessageWithoutPersistingConnectingOrLoggingTheInput() = runTest {
        parser = { RelayUrl.parse(it).httpBase }
        val inputs = listOf("ws://relay.example.com/private-input-fixture", "ws://203.0.113.5", "http://phone.local")
        for (input in inputs) {
            presenter.saveRelayUrl(input)
            assertEquals("Unencrypted relay addresses are allowed only for private network addresses. Use wss:// or https://.",
                presenter.state.value.error)
            assertSame(ReadValue.Absent, settings.url)
            assertEquals(0, control.connects)
        }
        assertEquals(3, logger.entries.size)
        for (entry in logger.entries) {
            assertEquals("parseRelayUrl", entry.operation)
            val logged = "${entry.context} ${entry.cause!!.stackTraceToString()}"
            inputs.forEach { assertFalse(logged.contains(it)) }
            assertFalse(logged.contains("relay.example.com"))
        }
    }

    @Test fun validUrlSavesCanonicalValueAndPersistenceFailuresStayVisible() = runTest {
        parser = { "https://normalized.invalid" }
        presenter.saveRelayUrl("fixture-input")
        assertEquals(ReadValue.Present("https://normalized.invalid"), settings.url)
        assertEquals("https://normalized.invalid", presenter.state.value.relayUrl)
        settings.writeResult = failure()
        presenter.saveRelayUrl("fixture-input")
        assertNotNull(presenter.state.value.error)
        assertEquals(0, control.connects)
    }

    @Test fun savedUrlIsAppliedOnceAfterPersistenceWithoutCallingConnect() = runTest {
        parser = { RelayUrl.parse(it).httpBase }
        control.applyRelayUrlAction = {
            assertEquals(ReadValue.Present("https://saved.example"), settings.url)
            PortResult.Success(Unit)
        }
        presenter.saveRelayUrl("https://saved.example/health")
        assertEquals(1, control.relayUrlApplications)
        assertEquals(0, control.connects)
        assertEquals("https://saved.example", presenter.state.value.relayUrl)
        assertNull(presenter.state.value.error)
        settings.writeResult = failure()
        presenter.saveRelayUrl("https://other.example")
        assertEquals(1, control.relayUrlApplications)
    }

    @Test fun failedOrThrownApplyKeepsTheSavedUrlAndShowsTheSwitchFailure() = runTest {
        for (thrown in listOf(false, true)) {
            control.applyRelayUrlAction = {
                if (thrown) throw IllegalStateException("private-apply-fixture")
                failure()
            }
            presenter.saveRelayUrl("https://saved.example")
            assertEquals("The relay URL was saved but the link could not switch to it.", presenter.state.value.error)
            assertEquals(ReadValue.Present("https://saved.example"), settings.url)
            assertEquals("https://saved.example", presenter.state.value.relayUrl)
            assertEquals(0, control.connects)
            val entry = logger.entries.last()
            assertEquals("applyRelayUrl", entry.operation)
            assertNotNull(entry.cause)
            assertFalse(entry.cause!!.stackTraceToString().contains("private-"))
        }
        assertEquals(2, control.relayUrlApplications)
    }

    @Test fun thrownSetupFailureIsSanitizedShownAndDoesNotCrash() = runTest {
        actions.thrown = IllegalStateException("private-action-fixture", Exception("private-cause-fixture"))
        presenter.setup(Permission.ACCESSIBILITY)
        assertEquals("Could not open the requested settings.", presenter.state.value.error)
        val entry = logger.entries.single()
        assertEquals("error", entry.level)
        assertFalse(entry.cause!!.stackTraceToString().contains("private-"))
        assertTrue(entry.cause.stackTrace.isNotEmpty())
        actions.thrown = null
        presenter.connect()
        assertEquals(1, control.connects)
        assertNull(presenter.state.value.error)
    }

    @Test fun statusAndPermissionFailuresAreNeverShownAsFalseGrantsOrSuccess() = runTest {
        presenter.refresh()
        assertTrue(presenter.state.value.permissions.any { it.granted == true })
        status.result = failure()
        permissions.result = failure()
        presenter.refresh()
        assertTrue(presenter.state.value.permissions.all { it.granted == null })
        assertNotNull(presenter.state.value.error)
        assertEquals(2, logger.entries.size)
    }

    @Test fun observesConnectionChangesAndCancellationDoesNotBecomeAnError() = runTest {
        val job = backgroundScope.launch { presenter.observe() }
        runCurrent()
        status.changes.emit(PortResult.Success(status.snapshot.copy(connection = ConnectionPhase.PAIRING_REJECTED)))
        runCurrent()
        assertEquals("Pairing rejected. Check the code and try again.", presenter.state.value.connectionText)
        job.cancel()
        runCurrent()
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun actionsAreBoundedAndNotRetried() = runTest {
        control.connectAction = { awaitCancellation() }
        val pending = async { presenter.connect() }
        advanceTimeBy(25_000); runCurrent()
        pending.await()
        assertEquals(1, control.connects)
        assertEquals("The setup operation timed out.", presenter.state.value.error)
    }

    @Test fun restrictionObservationsAreIndependentOfAllThirteenGrantRows() = runTest {
        val original = PermissionRow.from((permissions.result as PortResult.Success).value)
        for (observation in RestrictedSettingsStatus.entries) {
            restrictedSettings.result = PortResult.Success(observation)
            presenter.refresh()
            assertEquals(observation, presenter.state.value.restrictedSettings)
            assertEquals(original, presenter.state.value.permissions)
            assertEquals(13, presenter.state.value.permissions.size)
        }
        assertTrue(actions.opened.isEmpty())
        assertEquals(0, control.connects)
    }

    @Test fun failedRestrictionReadClearsPriorAllowedAndPreservesUnknownWithVisibleSafeError() = runTest {
        presenter.refresh()
        assertEquals(RestrictedSettingsStatus.ALLOWED, presenter.state.value.restrictedSettings)
        restrictedSettings.result = failure()
        presenter.refresh()
        assertNull(presenter.state.value.restrictedSettings)
        assertEquals("Restricted settings could not be read.", presenter.state.value.error)
        assertEquals("restrictedSettings", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-fixture"))
    }

    @Test fun thrownAndTimedOutRestrictionReadsNeverLeaveAPreviousAllowedObservation() = runTest {
        presenter.refresh()
        restrictedSettings.readAction = { throw IllegalStateException("private-read-fixture") }
        presenter.refresh()
        assertNull(presenter.state.value.restrictedSettings)
        assertNotNull(presenter.state.value.error)
        assertFalse(logger.entries.last().cause!!.stackTraceToString().contains("private-read-fixture"))
        restrictedSettings.readAction = { awaitCancellation() }
        val pending = async { presenter.refresh() }
        advanceTimeBy(25_000); runCurrent(); pending.await()
        assertNull(presenter.state.value.restrictedSettings)
        assertEquals("The setup operation timed out.", presenter.state.value.error)
    }

    @Test fun appInfoHasItsOwnActionAndDoesNotReplaceOrdinaryAccessibilitySetup() = runTest {
        presenter.openAppInfo()
        assertEquals(1, actions.appInfoOpens)
        assertTrue(actions.opened.isEmpty())
        assertTrue(actions.requested.isEmpty())
        presenter.setup(Permission.ACCESSIBILITY)
        assertEquals(listOf(Permission.ACCESSIBILITY), actions.opened)
        assertEquals(1, actions.appInfoOpens)
        assertEquals(0, control.connects)
    }

    @Test fun appInfoRefusalIsVisibleLoggedAndNotRetried() = runTest {
        actions.appInfoResult = failure()
        presenter.openAppInfo()
        assertEquals(1, actions.appInfoOpens)
        assertEquals("Could not open App info.", presenter.state.value.error)
        assertEquals("openAppInfo", logger.entries.single().operation)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-fixture"))
    }

    private fun failure() = PortResult.Failure(PortFailure(FailureKind.PLATFORM, IllegalStateException("private-fixture")))
    private class Source : StatusSource {
        var snapshot = StatusSnapshot(ConnectionPhase.UNPAIRED, null, null, GrantExpiry.Never, "1.0.0")
        var result: PortResult<StatusSnapshot>? = null
        override val changes = MutableSharedFlow<PortResult<StatusSnapshot>>()
        override suspend fun current() = result ?: PortResult.Success(snapshot)
    }
    private class Permissions : PermissionState {
        var result: PortResult<PermissionSnapshot> =
            PortResult.Success(PermissionSnapshot(true, false, true, false, true, false, true, false, true))
        override suspend fun read() = result
    }
    private class Restrictions : RestrictedSettingsState {
        var result: PortResult<RestrictedSettingsStatus> = PortResult.Success(RestrictedSettingsStatus.ALLOWED)
        var readAction: suspend () -> Unit = {}
        override suspend fun read(): PortResult<RestrictedSettingsStatus> { readAction(); return result }
    }
    private class Actions : SetupActions {
        val opened = mutableListOf<Permission>()
        val requested = mutableListOf<Permission>()
        var thrown: Exception? = null
        var appInfoOpens = 0
        var appInfoResult: PortResult<Unit> = PortResult.Success(Unit)
        override suspend fun openStatus() = PortResult.Success(Unit)
        override suspend fun openAppInfo(): PortResult<Unit> { appInfoOpens++; return appInfoResult }
        override suspend fun openPermissionSettings(permission: Permission): PortResult<Unit> {
            thrown?.let { throw it }; opened += permission; return PortResult.Success(Unit)
        }
        override suspend fun requestPermission(permission: Permission): PortResult<Unit> {
            thrown?.let { throw it }; requested += permission; return PortResult.Success(Unit)
        }
    }
}
