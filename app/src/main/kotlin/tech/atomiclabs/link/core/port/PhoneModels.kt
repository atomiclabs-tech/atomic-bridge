// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

data class LaunchableApp(val packageName: String, val label: String)
sealed interface AppLaunchOutcome {
    data object Launched : AppLaunchOutcome
    data object NotFound : AppLaunchOutcome
    data class Failed(val failure: PortFailure) : AppLaunchOutcome
}

sealed interface ActivityOutcome {
    data object Started : ActivityOutcome
    data object NoHandler : ActivityOutcome
    data class Failed(val failure: PortFailure) : ActivityOutcome
}

data class IntentComponent(val packageName: String, val className: String)
data class IntentSpec(
    val action: String,
    val data: String? = null,
    val packageName: String? = null,
    val component: IntentComponent? = null,
    val extras: Map<String, IntentExtra> = emptyMap(),
    val category: String? = null,
)

sealed interface IntentExtra {
    data class Text(val value: String) : IntentExtra
    data class Integer(val value: Long) : IntentExtra
    data class Decimal(val value: Double) : IntentExtra
    data class Flag(val value: Boolean) : IntentExtra
}

enum class MediaAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS }
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val provider: String,
    val timestampEpochMs: Long,
    val altitude: Double? = null,
)

data class Contact(val id: String, val name: String, val phones: List<ContactPhone>)
data class ContactPhone(val number: String, val type: Int, val label: String?)
sealed interface SmsOutcome {
    data class Sent(val parts: Int) : SmsOutcome
    data class RadioFailure(val androidResult: Int, val failure: PortFailure) : SmsOutcome
    data class Unconfirmed(val parts: Int) : SmsOutcome
    data class Failed(val failure: PortFailure) : SmsOutcome
}

data class OutboundAttachment(val uri: String, val contentType: String, val fileName: String)
data class ShareRequest(
    val attachments: List<OutboundAttachment>,
    val text: String? = null,
    val title: String? = null,
    val packageName: String? = null,
    val recipient: String? = null,
)

/** A missing compose handler is distinct from an operational launch failure. */
sealed interface ShareLaunchOutcome {
    /** Actual resolved explicit/default-SMS package; null only when a chooser was opened. */
    data class Opened(val packageName: String?) : ShareLaunchOutcome
    data object NoHandler : ShareLaunchOutcome
    data class Failed(val failure: PortFailure) : ShareLaunchOutcome
}
