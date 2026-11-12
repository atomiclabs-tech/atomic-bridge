// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Router responses always use transport status 200; command failures belong inside result. */
@Serializable
data class BridgeResponse(
    @SerialName("request_id") val requestId: String,
    val result: JsonObject,
    val status: Int = 200,
)
