package tech.atomiclabs.link.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.mockk.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.port.Permission
import tech.atomiclabs.link.core.port.RestrictedSettingsStatus
import tech.atomiclabs.link.core.setup.*

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewBinderTest {
    private val activity = mockk<Activity>(relaxed = true)
    private val presenter = mockk<StatusPresenter>(relaxed = true)
    private val state = MutableStateFlow(StatusViewState())
    private val views = mutableMapOf<Int, View>()
    private val clicks = mutableMapOf<Int, CapturingSlot<View.OnClickListener>>()
    private val endpointId = R.id::class.java.fields.firstOrNull { it.name == "status_endpoint" }?.getInt(null)
    private val rows = listOf(
        R.id.accessibility_state to R.id.accessibility_action, R.id.battery_state to R.id.battery_action,
        R.id.unused_state to R.id.unused_action, R.id.notifications_state to R.id.notifications_action,
        R.id.sms_state to R.id.sms_action, R.id.call_state to R.id.call_action,
        R.id.contacts_state to R.id.contacts_action, R.id.location_state to R.id.location_action,
        R.id.background_state to R.id.background_action,
        R.id.notification_access_state to R.id.notification_access_action,
        R.id.all_files_state to R.id.all_files_action,
        R.id.microphone_state to R.id.microphone_action,
        R.id.phone_state_state to R.id.phone_state_action,
    )

    private fun binder(scope: CoroutineScope, results: (Int, Array<out String>, IntArray) -> Unit = { _, _, _ -> },
                       link: LinkSwitch? = null, onKilled: () -> Unit = {},
                       dialogs: (Activity) -> AlertDialog.Builder = { error("Unexpected pairing dialog") }) =
        StatusViewBinder(activity, presenter, scope, results, link, onKilled, dialogs).also {
            val textIds = listOf(R.id.connection_state, R.id.pairing_hint, R.id.device_id,
                R.id.grant_expiry, R.id.grant_warning, R.id.encryption_warning, R.id.status_error, R.id.app_version,
                R.id.restricted_settings_state, R.id.restricted_settings_guidance, R.id.status_tone)
            (textIds + rows.map { row -> row.first } + listOfNotNull(endpointId)).forEach { id -> views[id] = mockk<TextView>(relaxed = true) }
            views[R.id.relay_url] = mockk<EditText>(relaxed = true)
            views[R.id.pairing_code_input] = mockk<EditText>(relaxed = true)
            views[R.id.status_led] = mockk<View>(relaxed = true)
            (listOf(R.id.save_url, R.id.connect, R.id.pair, R.id.restricted_settings_action, R.id.kill_switch) +
                rows.map { row -> row.second }).forEach { id ->
                val button = mockk<Button>(relaxed = true)
                val click = slot<View.OnClickListener>()
                every { button.setOnClickListener(capture(click)) } just Runs
                views[id] = button; clicks[id] = click
            }
            every { activity.findViewById<View>(any()) } answers { views.getValue(firstArg()) }
            every { presenter.state } returns state
            coEvery { presenter.observe() } coAnswers { awaitCancellation() }
        }

    @Test fun rendersStateAndNeverOverwritesAnEditingUrl() = runTest {
        val binder = binder(backgroundScope)
        val input = views.getValue(R.id.relay_url) as EditText
        every { input.hasFocus() } returns true
        val marker = "synthetic-view-fixture"
        binder.render(StatusViewState(
            connectionText = "Connected", relayUrl = "https://fixture.invalid", codeHint = marker, error = "Visible error", retry = true,
            permissions = listOf(PermissionRow(Permission.SMS, true, false),
                PermissionRow(Permission.LOCATION, false, false), PermissionRow(Permission.CALL, null, false)),
        ))
        verify { text(R.id.connection_state).text = "Connected" }
        verify { text(R.id.pairing_hint).text = marker }
        verify { text(R.id.status_error).text = "Visible error" }
        verify { text(R.id.pairing_hint).visibility = View.VISIBLE }
        verify { text(R.id.sms_state).setText(R.string.status_granted) }
        verify { text(R.id.location_state).setText(R.string.status_missing) }
        verify { text(R.id.call_state).setText(R.string.status_unknown) }
        verify { (views.getValue(R.id.connect) as Button).setText(R.string.status_retry) }
        verify(exactly = 0) { input.setText(any<String>()) }
        every { input.hasFocus() } returns false
        binder.render(StatusViewState(relayUrl = "https://fixture.invalid"))
        verify { input.setText("https://fixture.invalid") }
        verify { text(R.id.pairing_hint).visibility = View.GONE }
        binder.destroy()
    }

    @Test fun attemptedEndpointHasItsOwnPrivateStatusRowWhileTheInputIsBeingEditedAndIsHiddenWhenKilled() = runTest {
        assertNotNull("The status block needs an endpoint row", endpointId)
        val link = FakeSwitch()
        val binder = binder(backgroundScope, link = link)
        val input = views.getValue(R.id.relay_url) as EditText
        every { input.hasFocus() } returns true
        every { activity.getString(R.string::class.java.getField("status_endpoint").getInt(null), "https://attempted.example") } returns
            "Relay: https://attempted.example"
        val snapshot = StatusViewState(relayUrl = "https://edited.example", attemptedRelayUrl = "https://attempted.example",
            error = "Can't reach https://attempted.example. Retrying.")
        binder.render(snapshot)
        verify { text(endpointId!!).text = "Relay: https://attempted.example" }
        verify { text(endpointId!!).visibility = View.VISIBLE }
        verify(exactly = 0) { input.setText(any<String>()) }
        link.killed = true
        binder.render(snapshot)
        verify { text(endpointId!!).visibility = View.GONE }
        verify { text(R.id.status_error).visibility = View.GONE }
        binder.destroy()
    }

    @Test fun endpointStatusRowIsNotSavedAndIsInsideTheLinkStatusBlock() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/layout/activity_status.xml"))
        val elements = document.getElementsByTagName("TextView")
        val namespace = "http://schemas.android.com/apk/res/android"
        val endpoint = (0 until elements.length).map { elements.item(it) as Element }
            .firstOrNull { it.getAttributeNS(namespace, "id").endsWith("/status_endpoint") }
        assertNotNull("The link status block must display its attempted endpoint", endpoint)
        assertEquals("false", endpoint!!.getAttributeNS(namespace, "saveEnabled"))
        val parent = endpoint.parentNode as Element
        assertEquals("@drawable/ws_live_card", parent.getAttributeNS(namespace, "background"))
    }

    @Test fun allButtonsDispatchExactlyOneMatchingAction() = runTest {
        val binder = binder(backgroundScope)
        val input = views.getValue(R.id.relay_url) as EditText
        val editable = mockk<Editable>()
        every { editable.toString() } returns "https://fixture.invalid"
        every { input.text } returns editable
        binder.create()
        clicks.filterKeys { it != R.id.pair }.values.filter { it.isCaptured }.forEach { it.captured.onClick(null) }
        runCurrent()
        coVerify(exactly = 1) { presenter.saveRelayUrl("https://fixture.invalid") }
        coVerify(exactly = 1) { presenter.connect() }
        coVerify(exactly = 1) { presenter.openAppInfo() }
        Permission.entries.forEach { permission -> coVerify(exactly = 1) { presenter.setup(permission) } }
        verify { activity.setContentView(R.layout.activity_status) }
        val supplied = (0 until 6).map { (65 + it).toChar() }.joinToString("")
        val codeInput = views.getValue(R.id.pairing_code_input) as EditText
        val codeText = mockk<Editable>()
        every { codeText.toString() } returns supplied
        every { codeInput.text } returns codeText
        var pairCalls = 0
        var received: String? = null
        coEvery { presenter.pair(any()) } coAnswers { pairCalls++; received = firstArg() }
        clicks.getValue(R.id.pair).captured.onClick(null); runCurrent()
        assertEquals(1, pairCalls)
        assertTrue("The typed field is forwarded unchanged", received == supplied)
        binder.destroy()
    }

    @Test fun lifecycleObservesOnlyWhileResumedAndForwardsPermissionCallbacks() = runTest {
        var returnedCode = 0
        val binder = binder(backgroundScope, results = { code, _, _ -> returnedCode = code })
        binder.resume(); runCurrent()
        state.value = StatusViewState(connectionText = "Updated")
        runCurrent()
        verify { text(R.id.connection_state).text = "Updated" }
        binder.pause(); runCurrent()
        state.value = StatusViewState(connectionText = "Paused")
        runCurrent()
        verify(exactly = 0) { text(R.id.connection_state).text = "Paused" }
        binder.resume(); runCurrent()
        coVerify(exactly = 2) { presenter.observe() }
        binder.onRequestPermissionsResult(7, arrayOf("fixture.permission"), intArrayOf(0))
        assertEquals(7, returnedCode)
        binder.destroy()
    }

    @Test fun layoutKeepsPairingValuesOutOfSavedInstanceStateAndHasEveryRow() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/layout/activity_status.xml"))
        val elements = document.getElementsByTagName("*")
        val namespace = "http://schemas.android.com/apk/res/android"
        val byId = (0 until elements.length).map { elements.item(it) as Element }
            .associateBy { it.getAttributeNS(namespace, "id").substringAfter('/') }
        val code = byId.getValue("pairing_code_input")
        assertEquals("EditText", code.tagName)
        assertEquals("false", code.getAttributeNS(namespace, "saveEnabled"))
        assertEquals("6", code.getAttributeNS(namespace, "maxLength"))
        assertEquals("textCapCharacters|textNoSuggestions", code.getAttributeNS(namespace, "inputType"))
        assertTrue(byId.containsKey("pair"))
        assertFalse(byId.containsKey("pairing_code") || byId.containsKey("pairing_command") || byId.containsKey("re_pair"))
        for (id in listOf("accessibility", "battery", "unused", "notifications", "sms", "call", "contacts", "location", "background",
            "notification_access", "all_files", "microphone", "phone_state")) {
            assertTrue(byId.containsKey(id + "_state"))
            assertTrue(byId.containsKey(id + "_action"))
        }
        val strings = File("src/main/res/values/strings_status.xml").readText()
        assertTrue(strings.contains("Notifications (optional)"))
        assertTrue(strings.contains("Allow all the time"))
        assertTrue(byId.containsKey("restricted_settings_state"))
        assertTrue(byId.containsKey("restricted_settings_guidance"))
        assertTrue(byId.containsKey("restricted_settings_action"))
        assertTrue(strings.contains("App info for %1\$s → menu → Allow restricted settings"))
        assertTrue(strings.contains("enable or re-enable %1\$s"))
    }

    @Test fun separateRestrictionStateRendersUnknownWithoutHidingGuidanceOrAppInfo() = runTest {
        val binder = binder(backgroundScope)
        every { activity.getString(R.string.app_name) } returns "Fixture label"
        every { activity.getString(R.string.status_restricted_guidance, "Fixture label") } returns "Fixture guidance"
        binder.create()
        verify { text(R.id.restricted_settings_guidance).text = "Fixture guidance" }
        for ((observation, resource) in listOf(
            RestrictedSettingsStatus.ALLOWED to R.string.status_restricted_allowed,
            RestrictedSettingsStatus.RESTRICTED to R.string.status_restricted_blocked,
            RestrictedSettingsStatus.UNSUPPORTED to R.string.status_restricted_unknown,
            null to R.string.status_restricted_unknown,
        )) {
            binder.render(StatusViewState(restrictedSettings = observation))
            verify { text(R.id.restricted_settings_state).setText(resource) }
        }
        verify(exactly = 0) { text(R.id.restricted_settings_guidance).visibility = View.GONE }
        verify(exactly = 0) { views.getValue(R.id.restricted_settings_action).visibility = View.GONE }
        binder.destroy()
    }

    @Test fun pairingDialogShowsTheFullAddressWarnsForCleartextAndDefaultsToCancel() = runTest {
        val dialogs = DialogFixture()
        val binder = binder(backgroundScope, dialogs = { dialogs.builder })
        var confirmations = 0
        var cancellations = 0
        coEvery { presenter.confirmPairingLink() } coAnswers { confirmations++ }
        every { presenter.dismissPairingLink() } answers { cancellations++ }
        val offer = PendingPairing("ws://192.168.20.30:1234/full/path", generatedCode())
        every { activity.getString(R.string.pairing_cleartext) } returns "Connection is not encrypted"
        val offered = StatusViewState(pendingPairing = offer)
        binder.render(offered)
        assertEquals(offer.relayUrl + "\nConnection is not encrypted", dialogs.message)
        assertEquals(R.string.pairing_dialog_title, dialogs.title)
        assertEquals(R.string.pairing_pair, dialogs.positiveLabel)
        assertEquals(R.string.pairing_cancel, dialogs.negativeLabel)
        assertTrue(dialogs.cancelable && dialogs.outside && dialogs.cancelFocused)
        assertEquals(0, confirmations + cancellations)
        binder.render(offered)
        assertEquals(1, dialogs.creations)
        dialogs.negative!!.onClick(dialogs.dialog, DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, cancellations)
        dialogs.cancel!!.onCancel(dialogs.dialog)
        assertEquals(2, cancellations)
        assertEquals(0, confirmations)
        dialogs.positive!!.onClick(dialogs.dialog, DialogInterface.BUTTON_POSITIVE); runCurrent()
        assertEquals(1, confirmations)
        binder.render(StatusViewState())
        assertEquals(1, dialogs.dismissals)
        binder.destroy()
    }

    @Test fun failedConfirmationCanShowTheStillPendingOfferAgain() = runTest {
        val dialogs = DialogFixture()
        val binder = binder(backgroundScope, dialogs = { dialogs.builder })
        val offered = StatusViewState(pendingPairing = PendingPairing("https://fixture.example", generatedCode()))
        binder.render(offered)
        dialogs.positive!!.onClick(dialogs.dialog, DialogInterface.BUTTON_POSITIVE); runCurrent()
        binder.render(offered.copy(error = "The relay URL could not be saved."))
        assertEquals(2, dialogs.creations)
        binder.destroy()
    }

    @Test fun secureOfferHasNoCleartextLineAndOpenLinkOnlyOffersItToThePresenter() = runTest {
        val dialogs = DialogFixture()
        val binder = binder(backgroundScope, dialogs = { dialogs.builder })
        val address = "https://fixture.example:1234/full/path"
        binder.render(StatusViewState(pendingPairing = PendingPairing(address, generatedCode())))
        assertEquals(address, dialogs.message)
        val raw = "atomic://pair?relay=https%3A%2F%2Ffixture.example&code=${generatedCode()}"
        var received: String? = null
        var offers = 0
        every { presenter.offerPairingLink(any()) } answers { offers++; received = firstArg() }
        binder.openLink(raw); runCurrent()
        assertEquals(1, offers)
        assertTrue("The link is forwarded unchanged", received == raw)
        binder.destroy()
    }

    private fun generatedCode() = (0 until 6).map { (65 + it).toChar() }.joinToString("")

    private class DialogFixture {
        val builder = mockk<AlertDialog.Builder>()
        val dialog = mockk<AlertDialog>(relaxed = true)
        private val cancelButton = mockk<Button>(relaxed = true)
        var title = 0; var positiveLabel = 0; var negativeLabel = 0
        var message = ""
        var cancelable = false; var outside = false; var cancelFocused = false
        var creations = 0; var dismissals = 0
        var positive: DialogInterface.OnClickListener? = null
        var negative: DialogInterface.OnClickListener? = null
        var cancel: DialogInterface.OnCancelListener? = null
        private var onShow: DialogInterface.OnShowListener? = null
        init {
            every { builder.setTitle(any<Int>()) } answers { title = firstArg(); builder }
            every { builder.setMessage(any<CharSequence>()) } answers { message = firstArg<CharSequence>().toString(); builder }
            every { builder.setPositiveButton(any<Int>(), any<DialogInterface.OnClickListener>()) } answers {
                positiveLabel = firstArg(); positive = secondArg(); builder
            }
            every { builder.setNegativeButton(any<Int>(), any<DialogInterface.OnClickListener>()) } answers {
                negativeLabel = firstArg(); negative = secondArg(); builder
            }
            every { builder.setOnCancelListener(any()) } answers { cancel = firstArg(); builder }
            every { builder.setCancelable(any()) } answers { cancelable = firstArg(); builder }
            every { builder.create() } answers { creations++; dialog }
            every { dialog.setCanceledOnTouchOutside(any()) } answers { outside = firstArg() }
            every { dialog.setOnShowListener(any()) } answers { onShow = firstArg() }
            every { dialog.getButton(AlertDialog.BUTTON_NEGATIVE) } returns cancelButton
            every { cancelButton.requestFocus() } answers { cancelFocused = true; true }
            every { dialog.show() } answers { onShow!!.onShow(dialog) }
            every { dialog.dismiss() } answers { dismissals++ }
        }
    }

    private fun text(id: Int) = views.getValue(id) as TextView

    private class FakeSwitch(var killed: Boolean = false) : LinkSwitch {
        val calls = mutableListOf<String>()
        override fun isKilled() = killed
        override fun kill() { calls += "kill"; killed = true }
        override fun turnOn() { calls += "on"; killed = false }
    }

    @Test fun toneWordsFollowTheConnectionAndKillOverridesThem() = runTest {
        val link = FakeSwitch()
        val binder = binder(backgroundScope, link = link)
        for ((tone, word) in listOf(StatusTone.ONLINE to R.string.tone_online, StatusTone.WORKING to R.string.tone_working,
            StatusTone.NEEDS_YOU to R.string.tone_needs_you)) {
            binder.render(StatusViewState(connectionText = "Fixture", tone = tone))
            verify { text(R.id.status_tone).setText(word) }
        }
        verify { views.getValue(R.id.kill_switch).visibility = View.VISIBLE }
        verify { views.getValue(R.id.connect).visibility = View.GONE }
        link.killed = true
        binder.render(StatusViewState(connectionText = "Connected", tone = StatusTone.ONLINE))
        verify { text(R.id.status_tone).setText(R.string.tone_off) }
        verify { text(R.id.connection_state).setText(R.string.status_killed_detail) }
        verify { views.getValue(R.id.kill_switch).visibility = View.GONE }
        verify { views.getValue(R.id.connect).visibility = View.VISIBLE }
        binder.destroy()
    }

    @Test fun killButtonKillsThenConnectTurnsBackOn() = runTest {
        val link = FakeSwitch()
        var closed = 0
        val binder = binder(backgroundScope, link = link, onKilled = { closed++ })
        binder.create()
        clicks.getValue(R.id.kill_switch).captured.onClick(null)
        assertEquals(listOf("kill"), link.calls); assertEquals(1, closed)
        clicks.getValue(R.id.connect).captured.onClick(null)
        assertEquals(listOf("kill", "on"), link.calls); assertEquals(1, closed)
        binder.destroy()
    }

    @Test fun grantedRowsHideTheirButtonMissingRowsShowIt() = runTest {
        val binder = binder(backgroundScope)
        binder.render(StatusViewState(permissions = listOf(PermissionRow(Permission.SMS, true, false),
            PermissionRow(Permission.LOCATION, false, false))))
        verify { views.getValue(R.id.sms_action).visibility = View.GONE }
        verify { views.getValue(R.id.location_action).visibility = View.VISIBLE }
        binder.destroy()
    }
}
