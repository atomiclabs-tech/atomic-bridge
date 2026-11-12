package tech.atomiclabs.link.device.phone
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.telephony.SmsManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSmsSenderTest {
    private val context=mockk<Context>()
    private val manager=mockk<SmsManager>()
    private val logger=FakeLogger()
    private val callbacks=listOf(mockk<PendingIntent>(),mockk<PendingIntent>())
    private lateinit var receiver:BroadcastReceiver
    @Before fun setup() {
        mockkStatic(PendingIntent::class);mockkConstructor(Intent::class)
        every{context.packageName} returns "fixture.app"
        every{context.checkSelfPermission(Manifest.permission.SEND_SMS)} returns PackageManager.PERMISSION_GRANTED
        every{context.getSystemService(SmsManager::class.java)} returns manager
        every{manager.divideMessage("private-body")} returns arrayListOf("private-part1","private-part2")
        every{context.registerReceiver(any<BroadcastReceiver>(),any<IntentFilter>(),Context.RECEIVER_NOT_EXPORTED)} answers {
            receiver=spyk(firstArg<BroadcastReceiver>());null
        }
        every{context.unregisterReceiver(any())} just Runs
        every{anyConstructed<Intent>().setPackage("fixture.app")} answers{self as Intent}
        every{anyConstructed<Intent>().putExtra("atomic.sms.part",any<Int>())} answers{self as Intent}
        every{PendingIntent.getBroadcast(context,any(),any(),PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)} answers{callbacks[secondArg<Int>()]}
        callbacks.forEach{every{it.cancel()} just Runs}
        every{manager.sendMultipartTextMessage("private-number",null,any(),any(),null)} just Runs
    }
    @After fun cleanup(){unmockkStatic(PendingIntent::class);unmockkConstructor(Intent::class)}
    private fun adapter(test:TestScope)=AndroidSmsSender(context,logger,StandardTestDispatcher(test.testScheduler)){"fixture"}
    private fun radio(index:Int,code:Int) {
        every{receiver.resultCode} returns code
        val intent=mockk<Intent>{
            every{action} returns "fixture.app.SMS_SENT.fixture"
            every{hasExtra("atomic.sms.part")} returns true
            every{getIntExtra("atomic.sms.part",-1)} returns index
        }
        receiver.onReceive(context,intent)
    }
    private fun verifyCleanup() {
        verify(exactly=1){context.unregisterReceiver(any())}
        callbacks.forEach{verify(exactly=1){it.cancel()}}
    }
    @Test fun divideAndSubmitOnceThenRequireEveryDistinctPartBeforeSent()=runTest {
        val pending=async{adapter(this@runTest).send("private-number","private-body")}
        runCurrent();assertFalse(pending.isCompleted)
        radio(0,Activity.RESULT_OK);radio(0,Activity.RESULT_OK);runCurrent()
        assertFalse(pending.isCompleted)
        radio(1,Activity.RESULT_OK);runCurrent()
        assertEquals(SmsOutcome.Sent(2),pending.await())
        verify(exactly=1){manager.divideMessage("private-body")}
        verify(exactly=1){manager.sendMultipartTextMessage("private-number",null,eq(arrayListOf("private-part1","private-part2")),any(),null)}
        verifyCleanup()
        assertEquals("warn",logger.entries.single().level)
    }
    @Test fun radioErrorReturnsActualCodeAndNeverRetries()=runTest {
        every{manager.sendMultipartTextMessage(any(),null,any(),any(),null)} answers{radio(0,SmsManager.RESULT_ERROR_RADIO_OFF)}
        val outcome=adapter(this).send("private-number","private-body") as SmsOutcome.RadioFailure
        assertEquals(SmsManager.RESULT_ERROR_RADIO_OFF,outcome.androidResult)
        verify(exactly=1){manager.sendMultipartTextMessage(any(),null,any(),any(),null)}
        verifyCleanup()
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-"))
    }
    @Test fun missingRadioResultIsUnconfirmedAtFifteenSecondsAndCleansCallbacks()=runTest {
        val pending=async{adapter(this@runTest).send("private-number","private-body")}
        runCurrent();advanceTimeBy(14_999);runCurrent();assertFalse(pending.isCompleted)
        advanceTimeBy(1);runCurrent()
        assertEquals(SmsOutcome.Unconfirmed(2),pending.await())
        verify(exactly=1){manager.sendMultipartTextMessage(any(),null,any(),any(),null)}
        verifyCleanup()
        assertEquals("error",logger.entries.single().level)
    }
    @Test fun deniedPermissionDoesNotDivideRegisterOrSendAndHasExactName()=runTest {
        every{context.checkSelfPermission(Manifest.permission.SEND_SMS)} returns PackageManager.PERMISSION_DENIED
        val failed=adapter(this).send("private-number","private-body") as SmsOutcome.Failed
        assertEquals("android.permission.SEND_SMS",failed.failure.permission)
        verify(exactly=0){manager.divideMessage(any());manager.sendMultipartTextMessage(any(),any(),any(),any(),any())}
        verify(exactly=0){context.registerReceiver(any<BroadcastReceiver>(),any<IntentFilter>(),any<Int>())}
    }
    @Test fun thrownSendIsTypedPrivateSafeAndReleasesResources()=runTest {
        val cause=SecurityException("private-number private-body")
        every{manager.sendMultipartTextMessage(any(),null,any(),any(),null)} throws cause
        val failed=adapter(this).send("private-number","private-body") as SmsOutcome.Failed
        assertEquals(FailureKind.PERMISSION_DENIED,failed.failure.kind)
        assertTrue(generateSequence(failed.failure.cause){it.cause}.any{it===cause})
        verifyCleanup()
        assertTrue(logger.entries.all{!it.cause!!.stackTraceToString().contains("private-")})
    }
    @Test fun cancellationUnregistersAndCancelsEveryPendingIntentWithoutRetry()=runTest {
        val pending=launch{adapter(this@runTest).send("private-number","private-body")}
        runCurrent();pending.cancelAndJoin()
        verifyCleanup()
        verify(exactly=1){manager.sendMultipartTextMessage(any(),null,any(),any(),null)}
        assertTrue(logger.entries.isEmpty())
    }
    @Test fun segmentationFailureHasNoSilentFallbackOrSend()=runTest {
        every{manager.divideMessage(any())} throws IllegalStateException("private-body")
        assertTrue(adapter(this).send("private-number","private-body") is SmsOutcome.Failed)
        verify(exactly=0){manager.sendMultipartTextMessage(any(),any(),any(),any(),any())}
        assertFalse(logger.entries.single().cause!!.stackTraceToString().contains("private-body"))
    }
    @Test fun malformedCallbackCannotClaimSentAndStillReleasesEveryCallback()=runTest {
        every{manager.sendMultipartTextMessage(any(),null,any(),any(),null)} answers{radio(9,Activity.RESULT_OK)}
        val failed=adapter(this).send("private-number","private-body") as SmsOutcome.Failed
        assertEquals(FailureKind.PLATFORM,failed.failure.kind)
        verifyCleanup()
        assertTrue(logger.entries.any{it.operation=="radioCallback"})
        assertTrue(logger.entries.all{!it.cause!!.stackTraceToString().contains("private-")})
    }
    @Test fun cleanupFailuresStayLoudAndCannotSkipLaterPendingIntentCancellation()=runTest {
        every{manager.sendMultipartTextMessage(any(),null,any(),any(),null)} answers {
            radio(0,Activity.RESULT_OK);radio(1,Activity.RESULT_OK)
        }
        every{context.unregisterReceiver(any())} throws IllegalStateException("private-receiver")
        every{callbacks[0].cancel()} throws IllegalStateException("private-callback")
        val failed=adapter(this).send("private-number","private-body") as SmsOutcome.Failed
        assertEquals(FailureKind.PLATFORM,failed.failure.kind)
        verifyCleanup()
        assertEquals(2,logger.entries.count{it.operation=="cleanup"})
        assertTrue(logger.entries.all{!it.cause!!.stackTraceToString().contains("private-")})
    }
}
