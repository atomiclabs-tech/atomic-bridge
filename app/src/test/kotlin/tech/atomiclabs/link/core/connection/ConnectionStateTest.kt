package tech.atomiclabs.link.core.connection

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class ConnectionStateTest {
    @Test fun stateDiagnosticsExcludeNestedFailureMessages() {
        val state = ConnectionState(ConnectionPhase.REAUTH_REQUIRED, 9, 3, true,
            PortFailure(FailureKind.IO, IllegalStateException("private-failure")))
        assertEquals("re-pair needed", state.message)
        assertFalse(state.toString().contains("private-failure"))
        assertTrue(state.toString().contains("REAUTH_REQUIRED"))
        assertNull(ConnectionState().message)
    }
}
