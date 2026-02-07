package cam

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CameraDevice(
  val name: String,
  val xaddr: String,
  val ip: String
)

@Serializable
data class CameraRecord(
  val device: CameraDevice,
  val streamUrl: String? = null,
  val lastSeen: Long = System.currentTimeMillis()
)

class CameraRegistry {
  private val cameras = ConcurrentHashMap<String, CameraRecord>()

  fun upsertDiscovered(device: CameraDevice, streamUrl: String? = null, seenAt: Long = System.currentTimeMillis()): CameraRecord {
    val record = CameraRecord(device = device, streamUrl = streamUrl, lastSeen = seenAt)
    cameras[device.xaddr] = record
    return record
  }

  fun updateStreamUrl(xaddr: String, streamUrl: String?, seenAt: Long = System.currentTimeMillis()): CameraRecord? {
    val existing = cameras[xaddr] ?: return null
    val updated = existing.copy(streamUrl = streamUrl, lastSeen = seenAt)
    cameras[xaddr] = updated
    return updated
  }

  fun list(): List<CameraRecord> = cameras.values.sortedBy { it.device.name }

  fun get(xaddr: String): CameraRecord? = cameras[xaddr]

  fun remove(xaddr: String) {
    cameras.remove(xaddr)
  }

  fun pruneOlderThan(cutoffMs: Long) {
    cameras.entries.removeIf { it.value.lastSeen < cutoffMs }
  }
}
