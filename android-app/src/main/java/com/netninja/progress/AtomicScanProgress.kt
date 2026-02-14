package com.netninja.progress

import com.netninja.ScanProgress
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe scan progress updates.
 * Addresses RISK-03: Scan Progress Updates are Not Atomic
 */
class AtomicScanProgress {
  
  private val progress = AtomicReference(ScanProgress())

  /**
   * Update progress atomically using a transform function.
   */
  fun update(transform: (ScanProgress) -> ScanProgress): ScanProgress {
    return progress.updateAndGet(transform)
  }

  /**
   * Set progress atomically.
   */
  fun set(newProgress: ScanProgress) {
    progress.set(newProgress)
  }

  /**
   * Get current progress.
   */
  fun get(): ScanProgress = progress.get()

  /**
   * Update specific fields atomically.
   */
  fun updateFields(
    progress: Int? = null,
    phase: String? = null,
    networks: Int? = null,
    devices: Int? = null
  ): ScanProgress {
    return update { current ->
      current.copy(
        progress = progress ?: current.progress,
        phase = phase ?: current.phase,
        networks = networks ?: current.networks,
        devices = devices ?: current.devices,
        updatedAt = System.currentTimeMillis()
      )
    }
  }

  /**
   * Reset to idle state.
   */
  fun reset() {
    set(ScanProgress())
  }
}
