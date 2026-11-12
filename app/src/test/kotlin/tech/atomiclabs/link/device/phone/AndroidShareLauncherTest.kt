package tech.atomiclabs.link.device.phone

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.provider.Telephony
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidShareLauncherTest {
    private val context = mockk<Context>()
    private val packages = mockk<PackageManager>()
    private val clip = mockk<ClipData>(relaxed = true)
    private val chooser = mockk<Intent>(relaxed = true)
    private val logger = FakeLogger()
    private val uris = mutableListOf<Uri>()

    @Before fun setup() {
        mockkConstructor(Intent::class, ClipData.Item::class)
        mockkStatic(Intent::class, Uri::class, ClipData::class, Telephony.Sms::class)
        every { context.packageName } returns "tech.example.atomic"
        every { context.packageManager } returns packages
        every { context.getString(R.string.app_name) } returns "Atomic"
        every { context.startActivity(any()) } just Runs
        every { Telephony.Sms.getDefaultSmsPackage(context) } returns "default.sms"
        every { Uri.parse(any()) } answers {
            mockk<Uri>().also {
                every { it.scheme } returns "content"
                every { it.authority } returns "tech.example.atomic.files"
                uris += it
            }
        }
        every { ClipData.newRawUri("Atomic", any()) } returns clip
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setType(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Parcelable>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putParcelableArrayListExtra(any(), any<ArrayList<Uri>>()) } answers { self as Intent }
        every { anyConstructed<Intent>().clipData = any() } just Runs
        every { anyConstructed<Intent>().resolveActivity(packages) } returns mockk<ComponentName>()
        every { Intent.createChooser(any(), matchNullable<CharSequence> { true }) } returns chooser
    }

    @After fun teardown() {
        unmockkConstructor(Intent::class, ClipData.Item::class)
        unmockkStatic(Intent::class, Uri::class, ClipData::class, Telephony.Sms::class)
    }

    private fun attachment(type: String = "image/png", number: Int = 1) =
        OutboundAttachment("content://tech.example.atomic.files/outbound/$number", type, "private-name")

    @Test fun oneAttachmentUsesSendStreamClipAndGrantsToTheExplicitTarget() = runTest {
        val request = ShareRequest(listOf(attachment()), "private-text", packageName = "target.app")
        assertEquals(ShareLaunchOutcome.Opened("target.app"),
            AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler)).open(request))
        verify { anyConstructed<Intent>().setAction(Intent.ACTION_SEND) }
        verify { anyConstructed<Intent>().setType("image/png") }
        verify { anyConstructed<Intent>().setPackage("target.app") }
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_STREAM, uris.single() as Parcelable) }
        verify { anyConstructed<Intent>().putExtra(Intent.EXTRA_TEXT, "private-text") }
        verify { anyConstructed<Intent>().clipData = clip }
        verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify(exactly = 1) { context.startActivity(any()) }
        verify(exactly = 0) { Intent.createChooser(any(), matchNullable<CharSequence> { true }) }
        assertTrue(logger.entries.isEmpty())
    }

    @Test fun multipleAttachmentsUseSendMultipleAllStreamsAndClipItemsWithReadGrants() = runTest {
        val request = ShareRequest(listOf(attachment("image/jpeg"), attachment("image/png", 2)), packageName = "target.app")
        assertTrue(AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler)).open(request) is ShareLaunchOutcome.Opened)
        verify { anyConstructed<Intent>().setAction(Intent.ACTION_SEND_MULTIPLE) }
        verify { anyConstructed<Intent>().setType("image/*") }
        verify { anyConstructed<Intent>().putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        verify(exactly = 1) { clip.addItem(any()) }
        verify { anyConstructed<Intent>().clipData = clip }
        verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    @Test fun ordinaryShareUsesChooserWithTitleAndGrantsAndReturnsNullPackage() = runTest {
        val request = ShareRequest(listOf(attachment("image/png"), attachment("application/pdf", 2)), title = "Private title")
        assertEquals(ShareLaunchOutcome.Opened(null),
            AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler)).open(request))
        verify { Intent.createChooser(any(), "Private title") }
        verify { anyConstructed<Intent>().setType("*/*") }
        verify { chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        verify { chooser.clipData = clip }
        verify { context.startActivity(chooser) }
    }

    @Test fun mmsResolvesActualDefaultSmsOrUsesExplicitPackageAndPrefillsRecipientAndBody() = runTest {
        val launcher = AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler))
        val request = ShareRequest(listOf(attachment()), "private-body", recipient = "+15550000000")
        assertEquals(ShareLaunchOutcome.Opened("default.sms"), launcher.open(request))
        verify { anyConstructed<Intent>().setPackage("default.sms") }
        verify { anyConstructed<Intent>().putExtra("address", "+15550000000") }
        verify { anyConstructed<Intent>().putExtra("sms_body", "private-body") }
        every { Telephony.Sms.getDefaultSmsPackage(context) } throws AssertionError("Explicit package must not read default SMS.")
        assertEquals(ShareLaunchOutcome.Opened("explicit.sms"), launcher.open(request.copy(packageName = "explicit.sms")))
        verify { anyConstructed<Intent>().setPackage("explicit.sms") }
    }

    @Test fun noDefaultSmsOrNoResolvedHandlerOpensNothingAndNeverFallsBackToChooser() = runTest {
        val launcher = AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler))
        every { Telephony.Sms.getDefaultSmsPackage(context) } returns null
        assertEquals(ShareLaunchOutcome.NoHandler, launcher.open(ShareRequest(listOf(attachment()), recipient = "123")))
        every { anyConstructed<Intent>().resolveActivity(packages) } returns null
        assertEquals(ShareLaunchOutcome.NoHandler, launcher.open(ShareRequest(listOf(attachment()))))
        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 0) { Intent.createChooser(any(), matchNullable<CharSequence> { true }) }
    }

    @Test fun disappearedHandlerIsNoHandlerAndSecurityFailureIsOperationalWithSafeCause() = runTest {
        val launcher = AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler))
        val request = ShareRequest(listOf(attachment()), packageName = "target.app")
        every { context.startActivity(any()) } throws ActivityNotFoundException("private-target")
        assertEquals(ShareLaunchOutcome.NoHandler, launcher.open(request))
        val cause = SecurityException("private-uri-and-body")
        every { context.startActivity(any()) } throws cause
        val failure = launcher.open(request) as ShareLaunchOutcome.Failed
        assertEquals(FailureKind.PERMISSION_DENIED, failure.failure.kind)
        assertTrue(generateSequence(failure.failure.cause) { it.cause }.any { it === cause })
        assertEquals(2, logger.entries.size)
        assertTrue(logger.entries.all { it.level == "error" && it.cause != null })
        assertFalse(logger.entries.joinToString { it.cause!!.stackTraceToString() }.contains("private-"))
    }

    @Test fun platformResolutionFailureIsNotNoHandlerAndForeignProviderNeverLaunches() = runTest {
        val launcher = AndroidShareLauncher(context, logger, StandardTestDispatcher(testScheduler))
        every { anyConstructed<Intent>().resolveActivity(packages) } throws IllegalStateException("private-package")
        assertEquals(FailureKind.PLATFORM,
            (launcher.open(ShareRequest(listOf(attachment()))) as ShareLaunchOutcome.Failed).failure.kind)
        every { Uri.parse(any()) } returns mockk<Uri>().also {
            every { it.scheme } returns "content"
            every { it.authority } returns "foreign.files"
        }
        assertEquals(FailureKind.INVALID_DATA,
            (launcher.open(ShareRequest(listOf(attachment()))) as ShareLaunchOutcome.Failed).failure.kind)
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
