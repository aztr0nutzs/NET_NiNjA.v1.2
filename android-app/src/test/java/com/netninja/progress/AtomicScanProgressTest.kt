package com.netninja.progress

import com.netninja.ScanProgress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicScanProgressTest {

  private lateinit var progress: AtomicScanProgress

  @Before
  fun setup() {
    progress = AtomicScanProgress()
  }

  @Test
  fun initialState_isIdle() {
    val current = progress.get()
    assertEquals(0, current.progress)
    assertEquals("IDLE", current.phase)
  }

  @Test
  fun set_updatesProgress() {
    val newProgress = ScanProgress(progress = 50, phase = "SCANNING")
    progress.set(newProgress)
    
    val current = progress.get()
    assertEquals(50, current.progress)
    assertEquals("SCANNING", current.phase)
  }

  @Test
  fun update_transformsProgress() {
    val result = progress.update { current ->
      current.copy(progress = 75, phase = "COMPLETE")
    }
    
    assertEquals(75, result.progress)
    assertEquals("COMPLETE", result.phase)
  }

  @Test
  fun updateFields_updatesSpecificFields() {
    progress.updateFields(progress = 25, phase = "DISCOVERY")
    
    val current = progress.get()
    assertEquals(25, current.progress)
    assertEquals("DISCOVERY", current.phase)
  }

  @Test
  fun updateFields_preservesUnchangedFields() {
    progress.set(ScanProgress(progress = 10, phase = "INIT", devices = 5))
    progress.updateFields(progress = 20)
    
    val current = progress.get()
    assertEquals(20, current.progress)
    assertEquals("INIT", current.phase)
    assertEquals(5, current.devices)
  }

  @Test
  fun reset_returnsToIdle() {
    progress.set(ScanProgress(progress = 100, phase = "COMPLETE"))
    progress.reset()
    
    val current = progress.get()
    assertEquals(0, current.progress)
    assertEquals("IDLE", current.phase)
  }

  @Test
  fun concurrentUpdates_areThreadSafe() {
    val executor = Executors.newFixedThreadPool(10)
    val latch = CountDownLatch(100)
    
    repeat(100) { i ->
      executor.submit {
        progress.update { current ->
          current.copy(progress = i, devices = current.devices + 1)
        }
        latch.countDown()
      }
    }
    
    assertTrue(latch.await(5, TimeUnit.SECONDS))
    
    val final = progress.get()
    assertEquals(100, final.devices)
    
    executor.shutdown()
  }

  @Test
  fun updateFields_updatesTimestamp() {
    val before = System.currentTimeMillis()
    Thread.sleep(10)
    
    progress.updateFields(progress = 50)
    
    val current = progress.get()
    assertTrue(current.updatedAt > before)
  }
}
