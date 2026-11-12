package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidCallControlTest {
    @Test fun permissionDenialNeverOpensDialerAndGrantedCallUsesActionCallOnly() = runTest {
        val context=mockk<Context>()
        val logger=FakeLogger()
        val adapter=AndroidCallControl(context,logger,StandardTestDispatcher(testScheduler))
        every{context.checkSelfPermission(Manifest.permission.CALL_PHONE)} returns PackageManager.PERMISSION_DENIED
        val denied=adapter.call("private-number") as PortResult.Failure
        assertEquals("android.permission.CALL_PHONE",denied.failure.permission)
        verify(exactly=0){context.startActivity(any())}
        mockkStatic(Uri::class);mockkConstructor(Intent::class)
        try {
            val uri=mockk<Uri>();val pm=mockk<PackageManager>()
            every{context.checkSelfPermission(Manifest.permission.CALL_PHONE)} returns PackageManager.PERMISSION_GRANTED
            every{Uri.fromParts("tel","private-number",null)} returns uri
            every{context.packageManager} returns pm
            every{anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)} answers{self as Intent}
            every{anyConstructed<Intent>().resolveActivity(pm)} returns mockk()
            every{context.startActivity(any())} just Runs
            assertEquals(PortResult.Success(Unit),adapter.call("private-number"))
            verify(exactly=1){context.startActivity(any())}
            verify{anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)}
            every{context.startActivity(any())} throws SecurityException("private-number")
            assertEquals(FailureKind.PERMISSION_DENIED,(adapter.call("private-number") as PortResult.Failure).failure.kind)
            assertTrue(logger.entries.all{!it.cause!!.stackTraceToString().contains("private-number")})
        } finally{unmockkStatic(Uri::class);unmockkConstructor(Intent::class)}
    }
}
