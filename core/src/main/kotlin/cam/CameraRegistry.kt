package cam

import java.util.concurrent.ConcurrentHashMap

data class CameraRecord(
  val name: String,
  val xaddr: String,
  val ip: String,
  val streamUrl: String? = null,
  val lastSeen: Long = System.currentTimeMillis()
)

class CameraRegistry {
  private val cameras = ConcurrentHashMap<String, CameraRecord>()

  fun upsertDiscovery(name: String, xaddr: String, ip: String, seenAt: Long = System.currentTimeMillis()) {
    val existing = cameras[xaddr]
    cameras[xaddr] = CameraRecord(
      name = name,
      xaddr = xaddr,
      ip = ip,
      streamUrl = existing?.streamUrl,
      lastSeen = seenAt
    )
  }

  fun updateStreamUrl(xaddr: String, streamUrl: String, seenAt: Long = System.currentTimeMillis()) {
    val existing = cameras[xaddr] ?: return
    cameras[xaddr] = existing.copy(streamUrl = streamUrl, lastSeen = seenAt)
  }

  fun list(): List<CameraRecord> = cameras.values.sortedBy { it.name }

  fun get(xaddr: String): CameraRecord? = cameras[xaddr]

  fun count(): Int = cameras.size
}
