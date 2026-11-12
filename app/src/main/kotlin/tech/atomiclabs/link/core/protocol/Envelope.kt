// Ported from upstream network/relay/models/Envelope.kt at commit da782a8.
// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Relay frame; callers supply an id, and unrelated top-level fields are ignored by the codec. */
@Serializable
data class Envelope(
    val channel: String,
    val type: String,
    val id: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)
