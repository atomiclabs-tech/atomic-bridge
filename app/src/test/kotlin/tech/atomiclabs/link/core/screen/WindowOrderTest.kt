package tech.atomiclabs.link.core.screen

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*

class WindowOrderTest {
    @Test fun activeApplicationComesFirstAndRemainingWindowsKeepDescendingLayers() = runTest {
        val app = node(id = 9)
        val system = node(id = 4).also { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        val otherApp = node(id = 3)
        val tree = FakeUiTree().apply { windowResult = PortResult.Success(listOf(
            window(system, 99, type = WindowType.SYSTEM), window(otherApp, 40, active = false), window(app, 1))) }
        val order = WindowOrder(tree, FakeLogger())
        assertEquals(listOf(9, 4, 3), order.snapshot().windows.map { it.properties.windowId })
        assertEquals("example.app", order.foregroundPackage())
        tree.windowResult = PortResult.Success(listOf(window(system, 99, type = WindowType.SYSTEM),
            window(otherApp, 40, active = false), window(app, 1, active = false)))
        assertEquals(3, order.snapshot().windows.first { it.window?.type == WindowType.APPLICATION }.properties.windowId)
        assertEquals("example.app", order.foregroundPackage())
    }

    @Test fun focusedApplicationWinsAndMissingPackageCannotBecomeSystemUi() = runTest {
        val app = node().also { it.properties = it.properties.copy(packageName = null) }
        val system = node(id = 2).also { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        val tree = FakeUiTree().apply {
            windowResult = PortResult.Success(listOf(window(system, 50, type = WindowType.SYSTEM),
                window(app, 1, active = false, focused = true)))
            root = ReadValue.Present(system)
        }
        assertNull(WindowOrder(tree, FakeLogger()).foregroundPackage())
    }

    @Test fun absentWindowsFallBackToRealRootIdWithoutInventingMetadataAndWarnEveryTime() = runTest {
        val root = node(id = 83)
        val logger = FakeLogger()
        val tree = FakeUiTree().apply { this.root = ReadValue.Present(root) }
        val order = WindowOrder(tree, logger)
        val content = ScreenTreeWalker(FakeClock(testScheduler)).read(order.snapshot())
        assertEquals("w83:0", content.nodes.single().nodeId)
        assertNull(content.snapshot.windows.single().window)
        assertEquals("example.app", order.foregroundPackage())
        assertEquals(2, logger.entries.count { it.level == "warn" })
    }

    @Test fun systemOnlyWindowsResolvePackageFromActiveRootAndUnreadableRootIsNotAbsence() = runTest {
        val system = node(id = 2)
        val active = node(id = 10).also { it.properties = it.properties.copy(packageName = "active.pkg") }
        val tree = FakeUiTree().apply {
            windowResult = PortResult.Success(listOf(window(system, type = WindowType.SYSTEM)))
            root = ReadValue.Present(active)
        }
        val order = WindowOrder(tree, FakeLogger())
        assertEquals("active.pkg", order.foregroundPackage())
        val failure = PortFailure(FailureKind.PLATFORM, IllegalStateException("unreadable"))
        tree.root = ReadValue.Unreadable(failure)
        try { order.snapshot(); fail("Unreadable root became success") }
        catch (cause: ScreenReadException) { assertSame(failure, cause.failure) }
        tree.windowResult = PortResult.Failure(failure)
        try { order.snapshot(); fail("Failed windows became success") }
        catch (cause: ScreenReadException) { assertSame(failure, cause.failure) }
    }
}
