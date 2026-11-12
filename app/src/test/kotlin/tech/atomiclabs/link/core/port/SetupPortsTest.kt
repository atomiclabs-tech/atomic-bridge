package tech.atomiclabs.link.core.port

import org.junit.Assert.assertFalse
import org.junit.Test

class SetupPortsTest {
    @Test fun snapshotDiagnosticsExcludeEndpointIdentityPairingAndFailureContents() {
        val privateValue = "synthetic-private-status"
        val snapshot = StatusSnapshot(ConnectionPhase.BACKOFF, "https://$privateValue.example", privateValue,
            GrantExpiry.Never, privateValue,
            PortFailure(FailureKind.IO, IllegalStateException(privateValue)), ConnectionFailureOrigin.TRANSPORT, true)
        assertFalse(snapshot.toString().contains(privateValue))
    }
}
