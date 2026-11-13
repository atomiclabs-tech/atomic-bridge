package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

class PinPadSecurityTest {
    @Test fun anApplicationWindowCannotSpoofTheSystemUiBouncer() = runTest {
        val pad = trustedPad()
        val tree = pad.tree()
        val window = (tree.windowResult as PortResult.Success).value.single()
        tree.windowResult = PortResult.Success(listOf(window.copy(type = WindowType.APPLICATION)))
        assertTrue("App-declared package and resource identifiers cannot establish a trusted window.",
            PinPad(WindowOrder(tree, FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
        assertTrue(pad.clicks.isEmpty())
    }

    @Test fun anActiveRootWithoutWindowMetadataCannotEstablishBouncerTrust() = runTest {
        val pad = trustedPad()
        val tree = FakeUiTree().apply { root = ReadValue.Present(pad.root) }
        assertTrue("Missing window metadata must refuse even a complete SystemUI-looking pad.",
            PinPad(WindowOrder(tree, FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
        assertTrue(pad.clicks.isEmpty())
    }

    @Test fun noSimCarrierTextOutsideTheBouncerDoesNotRefuseTheScreenPad() = runTest {
        for (description in listOf(false, true)) {
            val pad = trustedPad()
            val bouncer = FakeUiNode(pad.root.properties)
            bouncer.nodes.addAll(pad.root.nodes)
            bouncer.parentNode = ReadValue.Present(pad.root)
            pad.root.nodes.clear()
            pad.root.nodes += bouncer
            pad.root.properties = pad.root.properties.copy(viewId = "com.android.systemui:id/notification_panel")
            pad.digits.values.forEach { it.parentNode = ReadValue.Present(bouncer) }
            pad.confirm!!.parentNode = ReadValue.Present(bouncer)
            pad.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1,
                packageName = "com.android.systemui", text = if (description) null else "No SIM card",
                contentDescription = if (description) "No SIM card" else null)).apply {
                    parentNode = ReadValue.Present(pad.root)
                }
            val keys = PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
            assertTrue("Carrier status outside the bouncer must not block a complete screen-lock pad.", keys != null)
            assertTrue(pad.clicks.isEmpty())
        }
    }

    @Test fun anOverlayApplicationWithACompletePadIsRefused() = runTest {
        val pad = trustedPad()
        pad.root.properties = pad.root.properties.copy(packageName = "example.overlay")
        pad.digits.values.forEach { it.properties = it.properties.copy(packageName = "example.overlay") }
        pad.confirm!!.properties = pad.confirm.properties.copy(packageName = "example.overlay")
        assertTrue("A complete overlay keypad must never receive the screen credential.",
            PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
    }

    @Test fun simPinAndPukContainersAfterTheCompleteDigitSetAreRefused() = runTest {
        for (suffix in listOf("keyguard_sim_pin_view", "keyguard_sim_puk_view", "simPinEntry")) {
            val pad = trustedPad()
            pad.root.properties = pad.root.properties.copy(viewId = "com.android.systemui:id/keyguard_pin_view")
            val sim = FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1,
                packageName = "com.android.systemui", viewId = "com.android.systemui:id/$suffix"))
            pad.root.nodes += sim
            assertTrue("SIM credential containers must refuse the complete screen pad.",
                PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
        }
    }

    @Test fun digitsFromTwoWindowsCannotBeCombinedIntoAPad() = runTest {
        val a = trustedPad(missing = '9')
        val b = trustedPad(missing = '8')
        b.root.properties = b.root.properties.copy(windowId = 2)
        b.digits.values.forEach { it.properties = it.properties.copy(windowId = 2) }
        val tree = a.tree()
        val first = (tree.windowResult as PortResult.Success).value.single()
        tree.windowResult = PortResult.Success(listOf(first, first.copy(id = 2, root = b.root)))
        assertTrue("Keys may not be mixed across keyguard windows.",
            PinPad(WindowOrder(tree, FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
    }

    @Test fun unclickableLabelsCannotRedirectEntryToAnUnrelatedAncestor() = runTest {
        val pad = trustedPad()
        pad.digits.values.first().properties = pad.digits.values.first().properties.copy(clickable = false)
        pad.root.properties = pad.root.properties.copy(clickable = true)
        assertTrue("Only directly clickable trusted keys may make a complete pad.",
            PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
    }
    @Test fun untrustedConfirmCannotBeBorrowedByTheTrustedDigitPad() = runTest {
        val pad = trustedPad()
        pad.confirm!!.properties = pad.confirm.properties.copy(packageName = "example.overlay")
        val keys = PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("A foreign confirm must never be clicked.", keys != null && keys.confirm == null)
    }

    @Test fun aSystemUiNumericPadWithoutBouncerOwnershipIsRefused() = runTest {
        val pad = trustedPad()
        pad.root.properties = pad.root.properties.copy(viewId = null)
        assertTrue("A SystemUI keypad must still prove screen-lock bouncer ownership.",
            PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
    }

    @Test fun simInstructionLabelRefusesEvenWithoutTheKnownContainerId() = runTest {
        for (label in listOf("Enter SIM PIN", "Enter SIM PUK")) {
            for (description in listOf(false, true)) {
                val pad = trustedPad()
                pad.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1,
                    packageName = "com.android.systemui", text = if (description) null else label,
                    contentDescription = if (description) label else null)).apply {
                        parentNode = ReadValue.Present(pad.root)
                    }
                assertTrue("A SIM instruction inside the bouncer must refuse the screen credential.",
                    PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
            }
        }
    }

    @Test fun simClassEvidenceOutsideTheBouncerStillRefusesTheScreenPad() = runTest {
        val pad = trustedPad()
        pad.root.nodes += FakeUiNode(UiNodeProperties(Bounds(0, 0, 100, 100), 1,
            packageName = "com.android.systemui", className = "com.android.keyguard.KeyguardSimPukView"))
        assertTrue("SIM class evidence must remain a refusal independently of label ancestry.",
            PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find() == null)
    }

    @Test fun realSystemUiBouncerIsAcceptedWithoutAnyInput() = runTest {
        val pad = trustedPad()
        val keys = PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("A complete trusted bouncer must still be accepted.", keys != null && keys.confirm != null)
        assertTrue(pad.clicks.isEmpty())
    }

    @Test fun bouncerOwnershipCanBeProvidedByANonEmittingNestedContainer() = runTest {
        val pad = trustedPad()
        val container = FakeUiNode(pad.root.properties)
        container.nodes.addAll(pad.root.nodes)
        pad.root.nodes.clear()
        pad.root.nodes += container
        pad.root.properties = pad.root.properties.copy(viewId = null)
        pad.digits.values.forEach { it.parentNode = ReadValue.Present(container) }
        pad.confirm!!.parentNode = ReadValue.Present(container)
        container.parentNode = ReadValue.Present(pad.root)
        val keys = PinPad(WindowOrder(pad.tree(), FakeLogger()), ScreenTreeWalker(FakeClock(testScheduler))).find()
        assertTrue("Nested bouncer containers must retain valid digit-label layouts.", keys != null && keys.confirm != null)
    }

    private fun trustedPad(missing: Char? = null): PinPadFixtures.Pad {
        val pad = if (missing == null) PinPadFixtures.pixel() else PinPadFixtures.oneUi(missing)
        pad.root.properties = pad.root.properties.copy(viewId = "com.android.systemui:id/keyguard_pin_view")
        pad.digits.values.forEach { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        pad.confirm?.let { it.properties = it.properties.copy(packageName = "com.android.systemui") }
        return pad
    }

}
