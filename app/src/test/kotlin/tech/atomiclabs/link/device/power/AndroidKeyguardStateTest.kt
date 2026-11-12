package tech.atomiclabs.link.device.power

import android.app.KeyguardManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidKeyguardStateTest {
    @Test fun readsBothKeyguardFlagsAndDoesNotConvertAReadFailureToUnlocked() = runTest {
        val manager = mockk<KeyguardManager>()
        every { manager.isKeyguardLocked } returns true
        every { manager.isKeyguardSecure } returns false
        val logger = FakeLogger()
        val state = AndroidKeyguardState(manager, logger, StandardTestDispatcher(testScheduler))
        assertEquals(PortResult.Success(KeyguardSnapshot(true, false)), state.read())
        every { manager.isKeyguardSecure } throws IllegalStateException("private-keyguard-fixture")
        assertTrue(state.read() is PortResult.Failure)
        assertEquals("error", logger.entries.single().level)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-keyguard-fixture"))
    }
}
