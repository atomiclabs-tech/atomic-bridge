package tech.atomiclabs.link.service

import android.content.Context
import android.content.ContentResolver
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidDeviceIdentityTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val packages = mockk<PackageManager>()
    private val info = mockk<PackageInfo>()
    private val logger = FakeLogger()
    @Before fun setup() {
        mockkStatic(Settings.Global::class)
        every { context.contentResolver } returns resolver
        every { context.packageName } returns "fixture.app"
        every { context.packageManager } returns packages
        every { packages.getPackageInfo("fixture.app", 0) } returns info
        info.versionName = "8.2"
    }
    @After fun cleanup() { unmockkStatic(Settings.Global::class) }

    @Test fun readsActualMetadataAndWarnsOnEveryNamedModelFallback() = runTest {
        every { Settings.Global.getString(resolver, "device_name") } returns "Owner device"
        val adapter = AndroidDeviceIdentity(context, logger, StandardTestDispatcher(testScheduler), { "Actual model" }, { "16" })
        assertEquals(PortResult.Success(DeviceDescription("Owner device", "Owner device", "Actual model", "Android 16",
            "fixture.app", "8.2")), adapter.read())
        every { Settings.Global.getString(resolver, "device_name") } returns null
        repeat(2) { assertEquals("Actual model", (adapter.read() as PortResult.Success).value.name) }
        assertEquals(2, logger.entries.count { it.operation == "deviceNameFallback" && it.level == "warn" })
    }

    @Test fun settingsAndPackageFailuresStayTypedAndNeverBecomeFallbackSuccess() = runTest {
        every { Settings.Global.getString(resolver, "device_name") } throws SecurityException("private-setting")
        val adapter = AndroidDeviceIdentity(context, logger, StandardTestDispatcher(testScheduler), { "Model" }, { "16" })
        assertEquals(FailureKind.PERMISSION_DENIED, (adapter.read() as PortResult.Failure).failure.kind)
        assertFalse(logger.entries.any { it.level == "warn" })
        every { Settings.Global.getString(resolver, "device_name") } returns "Device"
        info.versionName = null
        assertTrue(adapter.read() is PortResult.Failure)
        assertFalse(logger.entries.any { it.cause!!.stackTraceToString().contains("private-setting") })
    }
}
