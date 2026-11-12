package tech.atomiclabs.link.net

import android.net.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNetworkMonitorTest {
    @Test fun collectionOwnsTheCallbackAndEmitsAvailableLostAndUnavailableWithoutDebouncing() = runTest {
        val manager = mockk<ConnectivityManager>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { manager.activeNetwork } returns null
        val logger = FakeLogger()
        val monitor = AndroidNetworkMonitor(manager, logger, StandardTestDispatcher(testScheduler))
        val values = mutableListOf<PortResult<Boolean>>()
        val job = backgroundScope.launch { monitor.changes.collect { values += it } }
        runCurrent()
        val network = mockk<Network>()
        callback.captured.onAvailable(network); runCurrent()
        callback.captured.onLost(network); runCurrent()
        callback.captured.onUnavailable(); runCurrent()
        assertEquals(listOf(false, true, false, false), values.map { (it as PortResult.Success).value })
        job.cancelAndJoin()
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun readsActualInternetCapabilityAndDoesNotTreatMissingCapabilitiesAsOffline() = runTest {
        val manager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        val logger = FakeLogger()
        val monitor = AndroidNetworkMonitor(manager, logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(true), monitor.isAvailable())
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns false
        assertEquals(PortResult.Success(false), monitor.isAvailable())
        every { manager.getNetworkCapabilities(network) } returns null
        assertEquals(FailureKind.PLATFORM, (monitor.isAvailable() as PortResult.Failure).failure.kind)
        assertEquals("isAvailable", logger.entries.single().operation)
    }

    @Test fun registrationFailureIsTypedLoggedAndNotReplacedByFalseAvailability() = runTest {
        val manager = mockk<ConnectivityManager>()
        val cause = SecurityException("private-network-registration")
        every { manager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws cause
        val logger = FakeLogger()
        val monitor = AndroidNetworkMonitor(manager, logger, StandardTestDispatcher(testScheduler))
        val result = monitor.changes.single() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
        assertTrue(generateSequence(result.failure.cause) { it.cause }.any { it.stackTrace.contentEquals(cause.stackTrace) })
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-network-registration"))
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun readAndUnregisterFailuresRetainCauseAndAreSanitized() = runTest {
        val manager = mockk<ConnectivityManager>()
        val cause = SecurityException("private-network-read")
        every { manager.activeNetwork } throws cause
        every { manager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws IllegalStateException("private-unregister")
        val logger = FakeLogger()
        val monitor = AndroidNetworkMonitor(manager, logger, StandardTestDispatcher(testScheduler))
        val values = mutableListOf<PortResult<Boolean>>()
        val job = backgroundScope.launch { monitor.changes.collect { values += it } }
        runCurrent()
        val failed = values.single() as PortResult.Failure
        assertEquals(FailureKind.PERMISSION_DENIED, failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause) { it.cause }.any { it.stackTrace.contentEquals(cause.stackTrace) })
        job.cancelAndJoin()
        assertEquals(setOf("isAvailable", "unregister"), logger.entries.map { it.operation }.toSet())
        assertTrue(logger.entries.all { it.level == "error" && !it.cause!!.stackTraceToString().contains("private-") })
    }
}
