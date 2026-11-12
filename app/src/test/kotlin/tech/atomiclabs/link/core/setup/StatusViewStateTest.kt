package tech.atomiclabs.link.core.setup

import org.junit.Assert.*
import org.junit.Test

class StatusViewStateTest {
    @Test fun diagnosticsNeverExposePrivateDisplayValues() {
        val marker = "private-view-fixture"
        val state = StatusViewState(relayUrl = marker, attemptedRelayUrl = marker,
            pendingPairing = PendingPairing(marker, marker), codeHint = marker, error = marker)
        assertFalse(state.toString().contains(marker))
        assertFalse(state.pendingPairing.toString().contains(marker))
    }
}
