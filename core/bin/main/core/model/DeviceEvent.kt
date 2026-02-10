
package core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceEvent(
    val deviceId: String,
    val ts: Long,
    val event: String
)
