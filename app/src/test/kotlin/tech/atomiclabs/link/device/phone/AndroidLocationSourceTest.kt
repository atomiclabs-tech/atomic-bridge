package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.CancellationSignal
import java.util.concurrent.Executor
import java.util.function.Consumer
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLocationSourceTest {
    private val context=mockk<Context>()
    private val manager=mockk<LocationManager>()
    private val logger=FakeLogger()
    private fun adapter(test:TestScope):AndroidLocationSource {
        every{context.checkSelfPermission(any())} returns PackageManager.PERMISSION_GRANTED
        every{context.getSystemService(LocationManager::class.java)} returns manager
        every{manager.getProviders(true)} returns listOf("network","gps")
        return AndroidLocationSource(context,logger,StandardTestDispatcher(test.testScheduler))
    }
    private fun location()=mockk<Location>{
        every{latitude} returns 1.0;every{longitude} returns 2.0;every{accuracy} returns 3f
        every{provider} returns "gps";every{time} returns 100
        every{hasAltitude()} returns true;every{altitude} returns 4.0
    }
    @Test fun exactFineAndBackgroundChecksPrecedeAllProviderCalls()=runTest {
        val adapter=adapter(this)
        every{context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)} returns PackageManager.PERMISSION_DENIED
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION,(adapter.lastKnown() as PortResult.Failure).failure.permission)
        every{context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)} returns PackageManager.PERMISSION_GRANTED
        every{context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)} returns PackageManager.PERMISSION_DENIED
        assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION,(adapter.current(10_000) as ReadValue.Unreadable).failure.permission)
        verify(exactly=0){manager.getProviders(any<Boolean>())}
        assertEquals(2,logger.entries.size)
    }
    @Test fun collectsActualKnownFixesAndDoesNotHideProviderOrMetadataFailures()=runTest {
        val adapter=adapter(this)
        every{manager.getLastKnownLocation("network")} returns null
        every{manager.getLastKnownLocation("gps")} returns location()
        assertEquals(PortResult.Success(listOf(LocationFix(1.0,2.0,3f,"gps",100,4.0))),adapter.lastKnown())
        every{manager.getLastKnownLocation("network")} throws IllegalStateException("private-location")
        assertTrue(adapter.lastKnown() is PortResult.Failure)
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-location"))
    }
    @Test fun oneCurrentRequestCleansSignalForSuccessAbsenceTimeoutAndCancellation()=runTest {
        val adapter=adapter(this)
        mockkConstructor(CancellationSignal::class)
        try {
            every{anyConstructed<CancellationSignal>().cancel()} just Runs
            every{manager.getCurrentLocation("gps",any(),any<Executor>(),any<Consumer<Location>>())} answers {
                lastArg<Consumer<Location>>().accept(location())
            }
            assertTrue(adapter.current(10_000) is ReadValue.Present)
            every{manager.getCurrentLocation("gps",any(),any<Executor>(),any<Consumer<Location>>())} answers {
                lastArg<Consumer<Location?>>().accept(null)
            }
            assertEquals(ReadValue.Absent,adapter.current(10_000))
            every{manager.getCurrentLocation("gps",any(),any<Executor>(),any<Consumer<Location>>())} just Runs
            val waiting=async{adapter.current(10_000)}
            advanceTimeBy(10_000);runCurrent()
            assertEquals(FailureKind.TIMED_OUT,(waiting.await() as ReadValue.Unreadable).failure.kind)
            val cancelled=launch{adapter.current(10_000)}
            runCurrent();cancelled.cancelAndJoin()
            verify(exactly=4){manager.getCurrentLocation("gps",any(),any<Executor>(),any<Consumer<Location>>())}
            verify(exactly=4){anyConstructed<CancellationSignal>().cancel()}
            assertEquals(1,logger.entries.size)
        } finally{unmockkConstructor(CancellationSignal::class)}
    }
}
