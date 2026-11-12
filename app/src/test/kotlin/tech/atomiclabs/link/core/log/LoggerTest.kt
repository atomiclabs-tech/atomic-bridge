package tech.atomiclabs.link.core.log

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class LoggerTest {
    @Test fun contractCarriesNonSensitiveContextAndOriginalCausesAtEveryLevel() {
        val logger: Logger = mockk(relaxed = true)
        val cause = IllegalStateException("unavailable")
        logger.debug("Runtime", "start", "state=starting")
        logger.info("Runtime", "start", "state=ready")
        logger.warn("Socket", "close", "requestId=42", cause)
        logger.error("Socket", "connect", "requestId=43 state=failed", cause)
        verify { logger.debug("Runtime", "start", "state=starting") }
        verify { logger.info("Runtime", "start", "state=ready") }
        verify { logger.warn("Socket", "close", "requestId=42", cause) }
        verify { logger.error("Socket", "connect", "requestId=43 state=failed", cause) }
    }
}
