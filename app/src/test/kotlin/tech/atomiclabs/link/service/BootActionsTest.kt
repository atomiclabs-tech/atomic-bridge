package tech.atomiclabs.link.service

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger

class BootActionsTest {
    @Test fun bootCompletedStarts() {
        assertTrue(BootActions.shouldStart("android.intent.action.BOOT_COMPLETED"))
    }

    @Test fun packageReplacementStarts() {
        assertTrue(BootActions.shouldStart("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test fun foreignViewActionDoesNotStart() {
        assertFalse(BootActions.shouldStart("android.intent.action.VIEW"))
    }

    @Test fun missingActionDoesNotStart() {
        assertFalse(BootActions.shouldStart(null))
    }

    @Test fun ignoredActionsWarnThroughTheProjectLoggerWithoutStarting() {
        val logger = FakeLogger()
        var starts = 0
        for (action in listOf("android.intent.action.VIEW", null)) {
            BootActions.handle(action, logger) { starts++ }
        }
        assertEquals(0, starts)
        assertEquals(listOf("BootReceiver ignored action=android.intent.action.VIEW", "BootReceiver ignored action=null"),
            logger.entries.map { it.context })
        assertTrue(logger.entries.all { it.level == "warn" && it.component == "BootReceiver" && it.operation == "onReceive" })
        assertTrue(logger.entries.all { it.cause == null })
    }

    @Test fun approvedActionsStartOncePerDeliveryWithoutWarning() {
        val logger = FakeLogger()
        var starts = 0
        for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")) {
            BootActions.handle(action, logger) { starts++ }
        }
        assertEquals(2, starts)
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun aStartupFailureIsNotHidden() {
        val cause = IllegalStateException("Synthetic startup failure.")
        val failure = assertThrows(IllegalStateException::class.java) {
            BootActions.handle("android.intent.action.BOOT_COMPLETED", FakeLogger()) { throw cause }
        }
        assertSame(cause, failure)
    }
}
