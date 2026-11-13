package tech.atomiclabs.link.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.*
import tech.atomiclabs.link.R
import tech.atomiclabs.link.core.port.Permission
import tech.atomiclabs.link.core.port.RestrictedSettingsStatus
import tech.atomiclabs.link.core.setup.PendingPairing
import tech.atomiclabs.link.core.setup.StatusPresenter
import tech.atomiclabs.link.core.setup.StatusTone
import tech.atomiclabs.link.core.setup.StatusViewState

class StatusViewBinder(
    private val activity: Activity,
    private val presenter: StatusPresenter,
    parentScope: CoroutineScope,
    private val permissionResults: (Int, Array<out String>, IntArray) -> Unit,
    private val linkSwitch: LinkSwitch? = null,
    private val onKilled: () -> Unit = {},
    private val dialogBuilder: (Activity) -> AlertDialog.Builder = { AlertDialog.Builder(it) },
) {
    fun interface Factory { fun create(activity: Activity): StatusViewBinder }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var observation: Job? = null
    private var dialog: AlertDialog? = null
    private var shownPairing: PendingPairing? = null
    private val rows = mapOf(
        Permission.ACCESSIBILITY to (R.id.accessibility_state to R.id.accessibility_action),
        Permission.BATTERY_EXEMPT to (R.id.battery_state to R.id.battery_action),
        Permission.UNUSED_APP_RESTRICTIONS_DISABLED to (R.id.unused_state to R.id.unused_action),
        Permission.NOTIFICATIONS to (R.id.notifications_state to R.id.notifications_action),
        Permission.SMS to (R.id.sms_state to R.id.sms_action),
        Permission.CALL to (R.id.call_state to R.id.call_action),
        Permission.CONTACTS to (R.id.contacts_state to R.id.contacts_action),
        Permission.LOCATION to (R.id.location_state to R.id.location_action),
        Permission.BACKGROUND_LOCATION to (R.id.background_state to R.id.background_action),
        Permission.NOTIFICATION_ACCESS to (R.id.notification_access_state to R.id.notification_access_action),
        Permission.ALL_FILES to (R.id.all_files_state to R.id.all_files_action),
        Permission.MICROPHONE to (R.id.microphone_state to R.id.microphone_action),
        Permission.PHONE_STATE to (R.id.phone_state_state to R.id.phone_state_action),
    )

    fun create() {
        activity.setContentView(R.layout.activity_status)
        view<Button>(R.id.save_url).setOnClickListener {
            val url = view<EditText>(R.id.relay_url).text.toString()
            scope.launch { presenter.saveRelayUrl(url) }
        }
        view<Button>(R.id.connect).setOnClickListener {
            val link = linkSwitch
            if (link != null && link.isKilled()) { link.turnOn(); render(last) }
            else scope.launch { presenter.connect() }
        }
        view<Button>(R.id.pair).setOnClickListener {
            val code = view<EditText>(R.id.pairing_code_input).text.toString()
            scope.launch { presenter.pair(code) }
        }
        view<Button>(R.id.restricted_settings_action).setOnClickListener { scope.launch { presenter.openAppInfo() } }
        view<TextView>(R.id.restricted_settings_guidance).text =
            activity.getString(R.string.status_restricted_guidance, activity.getString(R.string.app_name))
        rows.forEach { (permission, ids) ->
            view<Button>(ids.second).setOnClickListener { scope.launch { presenter.setup(permission) } }
        }
        linkSwitch?.let { link ->
            view<Button>(R.id.kill_switch).setOnClickListener {
                if (link.isKilled()) { link.turnOn(); render(last) }
                else { onKilled(); link.kill() }
            }
        }
    }

    private var last = StatusViewState()

    fun resume() {
        observation?.cancel()
        observation = scope.launch {
            launch { presenter.state.collect(::render) }
            presenter.observe()
        }
    }

    fun pause() { observation?.cancel(); observation = null }
    fun destroy() { dialog?.dismiss(); scope.cancel() }
    fun openLink(uri: String) { scope.launch { presenter.offerPairingLink(uri) } }
    fun onRequestPermissionsResult(code: Int, names: Array<out String>, results: IntArray) =
        permissionResults(code, names, results)

    fun render(state: StatusViewState) {
        last = state
        val killed = linkSwitch?.isKilled() == true
        val tone = if (killed) StatusTone.OFF else state.tone
        view<TextView>(R.id.status_tone).setText(when (tone) {
            StatusTone.ONLINE -> R.string.tone_online
            StatusTone.WORKING -> R.string.tone_working
            StatusTone.NEEDS_YOU -> R.string.tone_needs_you
            StatusTone.OFF -> R.string.tone_off
        })
        view<View>(R.id.status_led).background.mutate().setTint(activity.getColor(when (tone) {
            StatusTone.ONLINE -> R.color.ws_go
            StatusTone.WORKING -> R.color.ws_amber
            StatusTone.NEEDS_YOU -> R.color.ws_alert
            StatusTone.OFF -> R.color.ws_alert
        }))
        // One slot: Kill while the link is on and healthy; Connect/Retry when killed or stuck.
        val showConnect = linkSwitch == null || killed || state.retry
        view<View>(R.id.connect).visibility = if (showConnect) View.VISIBLE else View.GONE
        view<View>(R.id.kill_switch).visibility = if (showConnect) View.GONE else View.VISIBLE
        if (killed) {
            view<TextView>(R.id.connection_state).setText(if (linkSwitch?.isScreenControlOn() == true)
                R.string.status_killed_screen_control_on else R.string.status_killed_detail)
        } else {
            view<TextView>(R.id.connection_state).text = state.connectionText
        }
        val url = view<EditText>(R.id.relay_url)
        if (!url.hasFocus()) url.setText(state.relayUrl)
        optional(R.id.status_endpoint, if (killed) null else state.attemptedRelayUrl?.let {
            activity.getString(R.string.status_endpoint, it)
        })
        optional(R.id.pairing_hint, state.codeHint)
        renderPairing(state.pendingPairing)
        optional(R.id.device_id, state.deviceId?.let { activity.getString(R.string.status_device_id, it) })
        optional(R.id.grant_expiry, state.grantExpiry?.let { activity.getString(R.string.status_grant, it) })
        optional(R.id.grant_warning, state.grantWarning)
        optional(R.id.encryption_warning, state.encryptionWarning)
        optional(R.id.status_error, if (killed) null else state.error)
        view<TextView>(R.id.app_version).text = activity.getString(R.string.status_version, state.appVersion)
        view<Button>(R.id.connect).setText(if (state.retry) R.string.status_retry else R.string.status_connect)
        view<TextView>(R.id.restricted_settings_state).setText(when (state.restrictedSettings) {
            RestrictedSettingsStatus.ALLOWED -> R.string.status_restricted_allowed
            RestrictedSettingsStatus.RESTRICTED -> R.string.status_restricted_blocked
            RestrictedSettingsStatus.UNSUPPORTED, null -> R.string.status_restricted_unknown
        })
        state.permissions.forEach { row ->
            val id = rows.getValue(row.permission).first
            view<TextView>(id).setText(when (row.granted) {
                true -> R.string.status_granted
                false -> R.string.status_missing
                null -> R.string.status_unknown
            })
            view<TextView>(id).setTextColor(activity.getColor(when (row.granted) {
                true -> R.color.ws_fg4
                false -> if (row.optional) R.color.ws_fg4 else R.color.ws_alert
                null -> R.color.ws_fg5
            }))
            view<View>(rows.getValue(row.permission).second).visibility =
                if (row.granted == true) View.GONE else View.VISIBLE
        }
    }

    private fun renderPairing(pending: PendingPairing?) {
        if (pending == null) { dialog?.dismiss(); dialog = null; shownPairing = null; return }
        if (shownPairing == pending) return
        dialog?.dismiss(); dialog = null; shownPairing = pending
        val cleartext = pending.relayUrl.startsWith("http://", true) || pending.relayUrl.startsWith("ws://", true)
        val message = pending.relayUrl + if (cleartext) "\n" + activity.getString(R.string.pairing_cleartext) else ""
        dialog = dialogBuilder(activity).setTitle(R.string.pairing_dialog_title).setMessage(message)
            .setPositiveButton(R.string.pairing_pair) { _, _ ->
                shownPairing = null // Android dismisses the button's dialog; a failed save must offer it again.
                scope.launch { presenter.confirmPairingLink() }
            }
            .setNegativeButton(R.string.pairing_cancel) { _, _ -> presenter.dismissPairingLink() }
            .setOnCancelListener { presenter.dismissPairingLink() }.setCancelable(true).create().also { offered ->
                offered.setCanceledOnTouchOutside(true)
                offered.setOnShowListener {
                    val cancel = offered.getButton(AlertDialog.BUTTON_NEGATIVE)
                    cancel.isFocusableInTouchMode = true
                    check(cancel.requestFocus()) { "The pairing dialog could not focus Cancel." }
                }
                offered.show()
            }
    }

    private fun optional(id: Int, text: String?) {
        view<TextView>(id).text = text.orEmpty()
        view<TextView>(id).visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun <T : View> view(id: Int): T =
        requireNotNull(activity.findViewById<T>(id)) { "Status layout is missing a required view." }
}
