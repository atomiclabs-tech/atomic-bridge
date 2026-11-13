package tech.atomiclabs.link.core.unlock

import android.view.accessibility.AccessibilityEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.events.EventBuffer
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.BridgeCommand
import tech.atomiclabs.link.device.access.EventFeed
import tech.atomiclabs.link.service.LinkRuntime
import tech.atomiclabs.link.service.runtimeInputs

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockPrivacyTest {
    @Test fun realRuntimeUnlockClicksNeverReachTheSharedRecentBuffer() = runTest {
        val f = UnlockSafetyFixture(this)
        f.events.setStreaming(true)
        f.onDigit = { properties -> f.feed.onEvent(f.event(properties.text ?: properties.contentDescription)) }
        f.onConfirm = { f.showing = false }
        val result = f.runtime.router.dispatch(unlockCommand("privacy", generatedPin())).result
        assertTrue("The real unlock flow must execute its digit clicks.", f.digitActions > 0)
        assertTrue("The unlock must succeed.", result["unlocked"] == JsonPrimitive(true))
        assertTrue("Unlock click metadata must never enter recent().", f.events.recent().entries.isEmpty())
        val wire = f.runtime.router.dispatch(BridgeCommand("recent", "GET", "/events")).result
        assertTrue("The event route must share the protected buffer.", wire["count"] == JsonPrimitive(0))
    }

    @Test fun queuedUnlockEventIsDroppedAfterCompletionButNewUnlockedSystemUiEventsRemain() = runTest {
        val f = UnlockSafetyFixture(this)
        f.events.setStreaming(true)
        var queued: AccessibilityEvent? = null
        f.onDigit = { properties -> if (queued == null) queued = f.event(properties.contentDescription ?: properties.text) }
        f.onConfirm = { f.showing = false }
        f.runtime.router.dispatch(unlockCommand("queued", generatedPin()))
        f.feed.onEvent(checkNotNull(queued))
        assertTrue("A late callback generated during unlock must remain private.", f.events.recent().entries.isEmpty())
        advanceTimeBy(1)
        f.feed.onEvent(f.event("ordinary unlocked event"))
        assertTrue("New unlocked SystemUI events must still be captured.", f.events.recent().entries.size == 1)
    }
}

internal fun generatedPin(length: Int = 6): String = buildString {
    repeat(length) { append(('0'.code + kotlin.random.Random.nextInt(10)).toChar()) }
}

internal fun unlockCommand(id: String, pin: String) = BridgeCommand(id, "POST", "/unlock",
    body = buildJsonObject { put("pin", pin) })

/** Uses the shipped runtime composition and its actual shared event/input routes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class UnlockSafetyFixture(scope: TestScope) {
    val clock = FakeClock(scope.testScheduler)
    val logger = FakeLogger()
    val events = EventBuffer(clock)
    val feed = EventFeed(events, logger)
    val pad = PinPadFixtures.pixel()
    val tree = pad.tree()
    val gate = FakeDisplayGate()
    var showing = true
    var digitActions = 0
    var inputActions = 0
    var beforeWindows: suspend () -> Unit = {}
    var onDigit: suspend (UiNodeProperties) -> Unit = {}
    var onConfirm: suspend () -> Unit = {}
    val runtime: LinkRuntime

    init {
        pad.root.properties = pad.root.properties.copy(viewId = "com.android.systemui:id/keyguard_pin_view")
        pad.digits.values.forEach { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        pad.confirm?.let { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        val decorated = pad.root.nodes.map { node -> object : UiNode by node {
            override suspend fun perform(action: NodeAction): PortResult<Unit> {
                val properties = (node.read() as PortResult.Success).value
                if (properties.viewId?.endsWith("/key_enter") == true) onConfirm()
                else { digitActions++; onDigit(properties) }
                return node.perform(action)
            }
        } }
        pad.root.nodes.clear()
        pad.root.nodes.addAll(decorated)
        val source = object : UiTreeSource by tree {
            override suspend fun windows(): PortResult<List<UiWindow>> { beforeWindows(); return tree.windows() }
        }
        val inputs = runtimeInputs(clock).copy(tree = source, events = events, display = gate,
            keyguard = KeyguardState { PortResult.Success(KeyguardSnapshot(showing, true)) },
            gestures = GestureInput { inputActions++; PortResult.Success(Unit) },
            globals = GlobalKeys { inputActions++; PortResult.Success(Unit) },
            volume = VolumeControl { inputActions++; PortResult.Success(Unit) })
        runtime = LinkRuntime(logger, inputs, scope.backgroundScope, installFailureHandler = {})
    }

    fun event(value: String?, time: Long = clock.monotonicMs()): AccessibilityEvent = mockk<AccessibilityEvent>().also { event ->
        every { event.source } returns null
        every { event.eventType } returns AccessibilityEvent.TYPE_VIEW_CLICKED
        every { event.eventTime } returns time
        every { event.isPassword } returns false
        every { event.packageName } returns "com.android.systemui"
        every { event.className } returns "android.widget.Button"
        every { event.text } returns mutableListOf<CharSequence>().also { if (value != null) it += value }
        every { event.contentDescription } returns value
    }
}
