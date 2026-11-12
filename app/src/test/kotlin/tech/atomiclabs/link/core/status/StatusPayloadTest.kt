package tech.atomiclabs.link.core.status

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.connection.description
import tech.atomiclabs.link.core.connection.permissions
import tech.atomiclabs.link.core.port.DeviceSnapshot

class StatusPayloadTest {
    @Test fun buildsExactStatusShapeWithUnconditionalCapabilityFlagsAndNullableCurrentApp() {
        for (accessibility in listOf(false, true)) {
            val payload = StatusPayload("device", description(), DeviceSnapshot(87, true, null, null),
                permissions().copy(accessibility = accessibility)).toJson(999)
            assertEquals(setOf("device_id", "ts", "device", "bridge", "permissions"), payload.keys)
            assertEquals(999L, payload["ts"]!!.jsonPrimitive.long)
            val device = payload["device"]!!.jsonObject
            assertEquals(JsonNull, device["current_app"])
            assertEquals(87, device["battery_percent"]!!.jsonPrimitive.int)
            assertEquals(description().applicationId, device["application_id"]!!.jsonPrimitive.content)
            val bridge = payload["bridge"]!!.jsonObject
            assertTrue(bridge["master_enabled"]!!.jsonPrimitive.boolean)
            assertTrue(bridge["device_control_supported"]!!.jsonPrimitive.boolean)
            assertFalse(bridge["notification_listener_granted"]!!.jsonPrimitive.boolean)
            assertEquals(accessibility, bridge["accessibility_granted"]!!.jsonPrimitive.boolean)
            assertEquals(accessibility, bridge["screen_capture_granted"]!!.jsonPrimitive.boolean)
            assertEquals(setOf("sms", "call", "contacts", "location", "background_location", "notifications", "battery_exempt",
                "all_files", "microphone", "phone_state", "notification_access"),
                payload["permissions"]!!.jsonObject.keys)
        }
    }

    @Test fun newPermissionsAndConnectedListenerAreIndependentPayloadInputs() {
        val payload = StatusPayload("device", description(), DeviceSnapshot(50, true, null, null),
            permissions().copy(allFiles = true, microphone = true, phoneState = true, notificationAccess = true), true).toJson(1)
        assertTrue(payload["bridge"]!!.jsonObject["notification_listener_granted"]!!.jsonPrimitive.boolean)
        for (field in listOf("all_files", "microphone", "phone_state", "notification_access")) {
            assertTrue(payload["permissions"]!!.jsonObject[field]!!.jsonPrimitive.boolean)
        }
    }
}
