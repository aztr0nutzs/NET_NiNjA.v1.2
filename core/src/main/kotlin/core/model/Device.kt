
package core.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val ip: String,
    val name: String? = null,
    val mac: String? = null,
    val hostname: String? = null,
    val os: String? = null,
    val vendor: String? = null,
    val online: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val openPorts: List<Int> = emptyList(),
    val banners: Map<Int, String> = emptyMap(),
    val owner: String? = null,
    val room: String? = null,
    val note: String? = null,
    val trust: String? = null,
    val type: String? = null,
    val status: String? = null,
    val via: String? = null,
    val signal: String? = null,
    val activityToday: String? = null,
    val traffic: String? = null
)
