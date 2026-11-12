package tech.atomiclabs.link.device.phone

import android.content.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidClipboardTest {
    private val context = mockk<Context>()
    private val trampoline = mockk<Trampoline>()
    private val logger = FakeLogger()

    @Test fun readsOnlyThroughTrampolineAndPreservesEmptySuccessAndTypedFailure() = runTest {
        val adapter = AndroidClipboard(context, trampoline, logger, StandardTestDispatcher(testScheduler))
        coEvery { trampoline.readClipboard() } returns PortResult.Success("")
        assertEquals(PortResult.Success(""), adapter.read())
        val cause = SecurityException("private-clipboard")
        val denied = PortResult.Failure(PortFailure(FailureKind.PERMISSION_DENIED, cause))
        coEvery { trampoline.readClipboard() } returns denied
        assertEquals(denied, adapter.read())
        coVerify(exactly = 2) { trampoline.readClipboard() }
        verify { context wasNot Called }
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-clipboard"))
    }

    @Test fun writesEmptyTextWithSharedAppLabelAndChecksActualPlatformRefusal() = runTest {
        val adapter = AndroidClipboard(context, trampoline, logger, StandardTestDispatcher(testScheduler))
        val clipboard = mockk<ClipboardManager>()
        val clip = mockk<ClipData>()
        mockkStatic(ClipData::class)
        try {
            every { context.getSystemService(ClipboardManager::class.java) } returns clipboard
            every { context.getString(R.string.app_name) } returns "Fixture label"
            every { ClipData.newPlainText("Fixture label", any<String>()) } returns clip
            every { clipboard.setPrimaryClip(clip) } just Runs
            assertEquals(PortResult.Success(Unit), adapter.write(""))
            verify { ClipData.newPlainText("Fixture label", "") }
            verify(exactly = 1) { clipboard.setPrimaryClip(clip) }
            every { clipboard.setPrimaryClip(clip) } throws SecurityException("private-clipboard")
            val result = adapter.write("private-clipboard") as PortResult.Failure
            assertEquals(FailureKind.PERMISSION_DENIED, result.failure.kind)
            assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-clipboard"))
            coVerify(exactly = 0) { trampoline.readClipboard() }
        } finally { unmockkStatic(ClipData::class) }
    }

    @Test fun missingClipboardServiceIsFailureAndWaitingTrampolineHasBoundedDeadline() = runTest {
        val adapter = AndroidClipboard(context, trampoline, logger, StandardTestDispatcher(testScheduler))
        every { context.getSystemService(ClipboardManager::class.java) } returns null
        assertTrue(adapter.write("") is PortResult.Failure)
        coEvery { trampoline.readClipboard() } coAnswers { awaitCancellation() }
        val pending = async { adapter.read() }
        advanceTimeBy(4_000); runCurrent()
        assertEquals(FailureKind.TIMED_OUT, (pending.await() as PortResult.Failure).failure.kind)
        coVerify(exactly = 1) { trampoline.readClipboard() }
        assertEquals(2, logger.entries.size)
    }

    @Test fun cancellationDoesNotBecomeClipboardSuccessOrAnErrorLog() = runTest {
        val adapter = AndroidClipboard(context, trampoline, logger, StandardTestDispatcher(testScheduler))
        coEvery { trampoline.readClipboard() } coAnswers { awaitCancellation() }
        val pending = launch { adapter.read() }
        runCurrent(); pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(logger.entries.isEmpty())
    }
}
