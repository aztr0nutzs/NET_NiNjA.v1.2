
package core.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val os: String? = null,
    val vendor: String? = null,
    val online: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val openPorts: List<Int> = emptyList(),
    val banners: Map<Int, String> = emptyMap()
)
