package tech.atomiclabs.link.core.status

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.atomiclabs.link.core.port.DeviceDescription
import tech.atomiclabs.link.core.port.DeviceSnapshot
import tech.atomiclabs.link.core.port.PermissionSnapshot

/** Status field equality excludes the transmission timestamp, so only meaningful changes trigger sends. */
data class StatusPayload(
    val deviceId: String,
    val description: DeviceDescription,
    val device: DeviceSnapshot,
    val permissions: PermissionSnapshot,
    val notificationListenerGranted: Boolean = false,
) {
    fun toJson(timestampMs: Long): JsonObject = buildJsonObject {
        put("device_id", deviceId); put("ts", timestampMs)
        put("device", buildJsonObject {
            put("device_id", deviceId); put("name", description.name)
            put("battery_percent", device.batteryPercent); put("screen_on", device.screenOn)
            put("current_app", device.currentPackage)
            put("application_id", description.applicationId); put("app_version", description.appVersion)
        })
        put("bridge", buildJsonObject {
            put("device_control_supported", true); put("master_enabled", true)
            put("accessibility_granted", permissions.accessibility)
            put("screen_capture_granted", permissions.accessibility)
            put("notification_listener_granted", notificationListenerGranted)
        })
        put("permissions", buildJsonObject {
            put("sms", permissions.sms); put("call", permissions.call); put("contacts", permissions.contacts)
            put("location", permissions.location); put("background_location", permissions.backgroundLocation)
            put("notifications", permissions.notifications); put("battery_exempt", permissions.batteryExempt)
            put("all_files", permissions.allFiles); put("microphone", permissions.microphone)
            put("phone_state", permissions.phoneState); put("notification_access", permissions.notificationAccess)
        })
    }
}
