package tech.atomiclabs.link.core.phone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand

class CallSmsRoutesTest {
    private val logger = FakeLogger()
    private val gate = FakeDisplayGate()
    private var calls = 0; private var sends = 0
    private var callResult: PortResult<Unit> = PortResult.Success(Unit)
    private var smsResult: SmsOutcome = SmsOutcome.Sent(2)
    private val group = CallSmsRoutes(CallControl { calls++;callResult }, SmsSender{_,_->sends++;smsResult},logger)
    private val router = CommandRouter(listOf(group), FakeUiTree().apply { connection=PortResult.Success(false) },gate,logger)
    private fun command(id:String,path:String) = BridgeCommand(id,"POST",path,body=if(path=="/call")
        buildJsonObject{put("number","private-number")} else buildJsonObject{put("to","private-number");put("body","private-body")})
    @Test fun directCallAndRadioConfirmedMultipartShapesHaveOnlyCallDisplayAndReplayOnce() = runTest {
        val call = router.dispatch(command("call","/call"))
        assertEquals(buildJsonObject{put("number","private-number");put("mode","auto_dial");put("success",true)},call.result)
        assertEquals(call,router.dispatch(command("call","/call")))
        val sms = router.dispatch(command("sms","/send_sms"))
        assertEquals(buildJsonObject{put("status","sent");put("to","private-number");put("length",12);put("parts",2);put("success",true)},sms.result)
        assertEquals(sms,router.dispatch(command("sms","/send_sms")))
        assertEquals(1,calls);assertEquals(1,sends);assertEquals(1,gate.calls)
    }
    @Test fun exactPermissionsRadioFailureAndUnconfirmedAreNeverSuccessOrRetried() = runTest {
        val denied = PortFailure(FailureKind.PERMISSION_DENIED,SecurityException("private-body"),"android.permission.CALL_PHONE")
        callResult=PortResult.Failure(denied)
        val call=router.dispatch(command("call","/call")).result
        assertEquals("permission_denied",call["code"]!!.jsonPrimitive.content)
        assertEquals("android.permission.CALL_PHONE",call["permission"]!!.jsonPrimitive.content)
        smsResult=SmsOutcome.Failed(denied.copy(permission="android.permission.SEND_SMS"))
        val sms=router.dispatch(command("sms","/send_sms")).result
        assertEquals("permission_denied",sms["code"]!!.jsonPrimitive.content)
        assertEquals("android.permission.SEND_SMS",sms["permission"]!!.jsonPrimitive.content)
        smsResult=SmsOutcome.RadioFailure(4,PortFailure(FailureKind.PLATFORM,IllegalStateException("private-body")))
        val radio=router.dispatch(command("radio","/send_sms")).result
        assertEquals("sms_failed",radio["code"]!!.jsonPrimitive.content)
        assertEquals(4,radio["android_result"]!!.jsonPrimitive.int)
        smsResult=SmsOutcome.Unconfirmed(2)
        val unconfirmed=router.dispatch(command("unknown","/send_sms"))
        assertEquals("sms_unconfirmed",unconfirmed.result["code"]!!.jsonPrimitive.content)
        assertEquals(unconfirmed,router.dispatch(command("unknown","/send_sms")))
        assertEquals(3,sends)
        assertTrue(logger.entries.all{it.level=="error"&&!it.cause!!.stackTraceToString().contains("private-")})
    }
    @Test fun missingOrEmptyParametersNeverAct() = runTest {
        for(path in listOf("/call","/send_sms")) {
            assertEquals("bad_request",router.dispatch(BridgeCommand(path,"POST",path)).result["code"]!!.jsonPrimitive.content)
        }
        assertEquals(0,calls);assertEquals(0,sends)
    }
}
