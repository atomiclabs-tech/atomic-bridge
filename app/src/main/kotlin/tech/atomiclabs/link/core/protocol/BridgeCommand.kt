// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Arguments come from body, including string-valued GET arguments copied by the relay. */
@Serializable
data class BridgeCommand(
    @SerialName("request_id") val requestId: String,
    val method: String,
    val path: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val body: JsonObject = JsonObject(emptyMap()),
)
