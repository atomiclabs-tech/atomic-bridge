package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

class PinPadTest {
    @Test fun recordedPixelIdentifierShapeIsAcceptedInASystemWindowWithoutConfirm() = runTest {
        // Identifier shape recorded in the device report; window type is supplied by this fixture only.
        val pad = PinPadFixtures.pixel(confirm = false)
        pad.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1,
            packageName = "com.android.systemui", viewId = "com.android.systemui:id/pinEntry",
            className = "android.widget.EditText", password = true, editable = true)).apply {
                parentNode = ReadValue.Present(pad.root)
            }
        for ((digit, key) in pad.digits) {
            key.properties = key.properties.copy(className = "android.view.ViewGroup")
            key.nodes += FakeUiNode(UiNodeProperties(Bounds(20, 20, 120, 120), 1,
                packageName = "com.android.systemui", viewId = "com.android.systemui:id/digit_text",
                className = "android.widget.TextView", text = digit.toString())).apply {
                    parentNode = ReadValue.Present(key)
                }
        }
        val keys = PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("The recorded Pixel identifiers must resolve the direct keypad controls.",
            keys != null && keys.confirm == null &&
                ('0'..'9').all { keys.digits[it]?.properties == pad.digits.getValue(it).properties })
        assertTrue(pad.clicks.isEmpty())
    }

    @Test fun everyDigitMatchesByTextDescriptionAndViewIdWithoutClicking() = runTest {
        for (kind in listOf("text", "description", "viewId")) {
            val layout = if (kind == "text") PinPadFixtures.oneUi() else PinPadFixtures.pixel()
            if (kind == "description") layout.digits.values.forEach { it.properties = it.properties.copy(viewId = null) }
            if (kind == "viewId") layout.digits.values.forEach { it.properties = it.properties.copy(contentDescription = null) }
            val keys = PinPad(WindowOrder(layout.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
            assertTrue("Every declared digit form must produce a complete pad.", keys != null)
            assertTrue("Digit keys must resolve to the corresponding fixture nodes.",
                ('0'..'9').all { keys!!.digits[it]?.properties == layout.digits.getValue(it).properties })
            assertTrue(layout.clicks.isEmpty())
        }
    }

    @Test fun allApprovedConfirmLabelsMatchEitherTextFieldAndNormalize() = runTest {
        for (label in listOf("OK", "Enter", "Done", "Confirm", "✓", "✔")) {
            for (description in listOf(false, true)) {
                val layout = PinPadFixtures.oneUi()
                val key = checkNotNull(layout.confirm)
                key.properties = key.properties.copy(text = if (description) null else " $label ",
                    contentDescription = if (description) " $label " else null)
                val keys = PinPad(WindowOrder(layout.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
                assertTrue("An approved confirm label must resolve.", keys?.confirm?.properties == key.properties)
            }
        }
    }

    @Test fun enterViewIdWorksWithoutALabelAndUnrelatedKeysAreNeverConfirm() = runTest {
        val pixel = PinPadFixtures.pixel()
        pixel.confirm!!.properties = pixel.confirm.properties.copy(contentDescription = null)
        val keys = PinPad(WindowOrder(pixel.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("The enter resource suffix must resolve without a label.", keys?.confirm != null)
        val without = PinPadFixtures.pixel(confirm = false)
        val absent = PinPad(WindowOrder(without.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("Delete and Cancel cannot become confirm keys.", absent != null && absent.confirm == null)
    }

    @Test fun everyMissingDigitMakesThePadIncomplete() = runTest {
        for (missing in '0'..'9') {
            val layout = PinPadFixtures.oneUi(missing)
            val keys = PinPad(WindowOrder(layout.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
            assertTrue("A missing digit must refuse the pad.", keys == null)
            assertTrue(layout.clicks.isEmpty())
        }
    }

    @Test fun aSingleBoundedWalkDoesNotDiscoverKeysBeyondTheVisitBudget() = runTest {
        val layout = PinPadFixtures.oneUi()
        val decoys = List(MAX_SEARCH_VISITS) { FakeUiNode(UiNodeProperties(Bounds(0, 0, 1, 1), 1, clickable = true)) }
        layout.root.nodes.addAll(0, decoys)
        var snapshots = 0
        val tree = layout.tree()
        val source = object : UiTreeSource by tree {
            override suspend fun windows(): PortResult<List<UiWindow>> { snapshots++; return tree.windows() }
        }
        val keys = PinPad(WindowOrder(source, FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("A truncated search must not invent the missing pad.", keys == null)
        assertEquals(1, snapshots)
    }

    @Test fun snapshotFailuresKeepTheirCauseInsteadOfBecomingAnAbsentPad() = runTest {
        val cause = IllegalStateException("unreadable fixture tree")
        val tree = FakeUiTree().apply { windowResult = PortResult.Failure(PortFailure(FailureKind.PLATFORM, cause)) }
        val pads = PinPad(WindowOrder(tree, FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler)))
        try {
            pads.find()
            fail("A failed tree read must escape to the route failure boundary.")
        } catch (failure: ScreenReadException) { assertSame(cause, failure.cause) }
    }
}
