package tech.atomiclabs.link.core.connection

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.BodyParams
import tech.atomiclabs.link.core.port.DeviceDescription
import tech.atomiclabs.link.core.port.StoredSession
import tech.atomiclabs.link.core.protocol.Envelope

/** Auth wire construction/parsing; failures name fields, never credentials or remote diagnostics. */
class AuthFrames(private val newId: () -> String) {
    fun auth(deviceId: String, device: DeviceDescription, session: StoredSession?, code: String?): Envelope {
        require(session != null || !code.isNullOrBlank()) { "An authentication credential is required." }
        return Envelope("system", "auth", newId(), buildJsonObject {
            if (code != null) put("pairing_code", code)
            else {
                put("session_token", session!!.sessionToken)
                session.refreshToken?.let { put("refresh_token", it) }
            }
            put("device_id", deviceId)
            put("device_name", device.name)
            put("device_hostname", device.hostname)
            put("device_model", device.model)
            put("device_platform", device.platform)
            put("client_surface", "android")
            put("device_form_factor", "phone")
        })
    }

    fun accepted(payload: JsonObject, previous: StoredSession?, savedAtMs: Long): StoredSession {
        val params = BodyParams(payload)
        val token = params.requiredString("session_token")
        require(token.isNotBlank()) { "The session_token field is empty." }
        val refresh = when (val value = payload["refresh_token"]) {
            null, JsonNull -> previous?.refreshToken
            else -> (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw IllegalArgumentException("The refresh_token field is invalid.")
        }
        val grants = when (val value = payload["grants"]) {
            null -> emptyMap()
            is JsonObject -> value.mapValues { (_, expiry) -> epoch(expiry, "grants") }
            else -> throw IllegalArgumentException("The grants field is invalid.")
        }
        return StoredSession(token, refresh, epoch(payload["expires_at"], "expires_at"), grants, savedAtMs)
    }

    fun timedOut(payload: JsonObject): Boolean {
        val reason = BodyParams(payload).requiredString("reason")
        return reason.contains("timeout", ignoreCase = true) || reason.contains("timed out", ignoreCase = true)
    }

    private fun epoch(value: JsonElement?, field: String): Long? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.longOrNull ?: value.doubleOrNull?.takeIf {
            it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble()
        }?.toLong()
            ?: throw IllegalArgumentException("The $field field is invalid.")
        else -> throw IllegalArgumentException("The $field field is invalid.")
    }
}
