package tech.atomiclabs.link.core.capture

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.core.screen.WindowOrder

class ScreenshotRoutesTest {
    @Test fun screenshotUsesTheSharedRouterAccessibilityDisplayOrderAndReplayContract() = runTest {
        val logger = FakeLogger(); val tree = applicationTree(); val source = CaptureFake(); val media = MediaFake()
        val gate = FakeDisplayGate()
        val command = ScreenshotCommand(source, media, WindowOrder(tree, logger), FakeClock(testScheduler), logger)
        val router = CommandRouter(listOf(ScreenshotRoutes(command) { "runtime-base" }), tree, gate, logger)
        fun request(id: String) = BridgeCommand(id, "GET", "/screenshot")
        tree.connection = PortResult.Success(false)
        assertEquals("accessibility_disabled", router.dispatch(request("disabled")).result["code"]!!.jsonPrimitive.content)
        assertEquals(0, gate.calls); assertTrue(source.targets.isEmpty())
        tree.connection = PortResult.Success(true)
        gate.result = DisplayGateResult.Failed(CommandResult.Failure(ErrorCode.WAKE_FAILED, "The display stayed off."))
        assertEquals("wake_failed", router.dispatch(request("off")).result["code"]!!.jsonPrimitive.content)
        assertTrue(source.targets.isEmpty())
        gate.result = DisplayGateResult.Ready
        val first = router.dispatch(request("success"))
        assertEquals(200, first.status)
        assertEquals("MEDIA:hermes-relay://fixture", first.result["media"]!!.jsonPrimitive.content)
        val targets = source.targets.toList()
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), targets)
        assertSame(first, router.dispatch(request("success")))
        assertEquals(targets, source.targets)
        assertEquals(1, media.uploads.size)
        assertEquals("runtime-base", media.uploads.single().first)
    }

    @Test fun authoritativeSecureRefusalIsAStatus200ObjectAndReplayDoesNotCaptureOrUploadAgain() = runTest {
        val logger = FakeLogger(); val tree = applicationTree(); val source = CaptureFake(); val media = MediaFake()
        source.windowOutcomes += ScreenshotOutcome.SecureWindow(6)
        val command = ScreenshotCommand(source, media, WindowOrder(tree, logger), FakeClock(testScheduler), logger)
        val router = CommandRouter(listOf(ScreenshotRoutes(command) { "runtime-base" }), tree, FakeDisplayGate(), logger)
        val request = BridgeCommand("secure-id", "GET", "/screenshot")
        val response = router.dispatch(request)
        assertEquals(200, response.status)
        assertEquals("secure_window", response.result["code"]!!.jsonPrimitive.content)
        assertFalse(response.result["success"]!!.jsonPrimitive.boolean)
        assertFalse(response.result.containsKey("media"))
        val targets = source.targets.toList()
        assertEquals(listOf(CaptureTarget.DefaultDisplay, CaptureTarget.Window(47)), targets)
        assertSame(response, router.dispatch(request))
        assertEquals(targets, source.targets)
        assertTrue(source.encodings.isEmpty()); assertTrue(media.uploads.isEmpty())
        assertEquals(400, currentTime)
    }
}
