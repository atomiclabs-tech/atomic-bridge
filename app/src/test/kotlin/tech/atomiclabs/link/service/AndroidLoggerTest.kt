package tech.atomiclabs.link.service

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidLoggerTest {
    private val logger = AndroidLogger()

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.isLoggable("AtomicLink", Log.DEBUG) } returns false
        every { Log.d(any(), any()) } returns 1
        every { Log.i(any(), any()) } returns 1
        every { Log.w(any(), any<String>(), matchNullable<Throwable> { true }) } returns 1
        every { Log.e(any(), any<String>(), any<Throwable>()) } returns 1
    }

    @After fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test fun debugIsSuppressedUnlessTheTagIsLoggable() {
        logger.debug("Socket", "connect", "state=starting")
        verify(exactly = 0) { Log.d(any(), any()) }
        every { Log.isLoggable("AtomicLink", Log.DEBUG) } returns true
        logger.debug("Socket", "connect", "state=starting")
        verify(exactly = 1) { Log.d("AtomicLink", "Socket/connect state=starting") }
    }

    @Test fun infoAndWarningKeepTheirContextAndWarningCause() {
        val cause = IllegalStateException("unavailable")
        logger.info("Runtime", "start", "state=ready")
        logger.warn("Power", "wake", "requestId=42", cause)
        logger.warn("Power", "wake", "requestId=43")
        verify { Log.i("AtomicLink", "Runtime/start state=ready") }
        verify { Log.w("AtomicLink", "Power/wake requestId=42", cause) }
        verify { Log.w("AtomicLink", "Power/wake requestId=43", null as Throwable?) }
    }

    @Test fun errorsKeepComponentOperationIdsAndTheOriginalThrowable() {
        val cause = IllegalArgumentException("invalid state")
        logger.error("Router", "dispatch", "requestId=42 path=/command state=failed", cause)
        verify(exactly = 1) {
            Log.e("AtomicLink", "Router/dispatch requestId=42 path=/command state=failed", cause)
        }
    }
}
