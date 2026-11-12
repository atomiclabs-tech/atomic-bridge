// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Complete permission/checklist snapshot; read errors are explicit instead of silently denied grants. */
fun interface PermissionState { suspend fun read(): PortResult<PermissionSnapshot> }

/** Physical/status state including the shared foreground resolver and last window-state activity. */
fun interface DeviceStateSource { suspend fun read(): PortResult<DeviceSnapshot> }

enum class Permission {
    ACCESSIBILITY, BATTERY_EXEMPT, UNUSED_APP_RESTRICTIONS_DISABLED, NOTIFICATIONS,
    SMS, CALL, CONTACTS, LOCATION, BACKGROUND_LOCATION,
    NOTIFICATION_ACCESS, ALL_FILES, MICROPHONE, PHONE_STATE,
}

data class PermissionSnapshot(
    val accessibility: Boolean,
    val batteryExempt: Boolean,
    val unusedAppRestrictionsDisabled: Boolean,
    val notifications: Boolean,
    val sms: Boolean,
    val call: Boolean,
    val contacts: Boolean,
    val location: Boolean,
    val backgroundLocation: Boolean,
    val allFiles: Boolean = false,
    val microphone: Boolean = false,
    val phoneState: Boolean = false,
    val notificationAccess: Boolean = false,
)

data class DeviceSnapshot(
    val batteryPercent: Int,
    val screenOn: Boolean,
    val currentPackage: String?,
    val currentActivity: String?,
)
