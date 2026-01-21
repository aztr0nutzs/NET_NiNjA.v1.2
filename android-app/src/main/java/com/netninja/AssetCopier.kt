package com.netninja

import android.content.Context
import android.util.Log
import java.io.File

object AssetCopier {
  private const val TAG = "AssetCopier"

  fun copyDir(ctx: Context, assetDir: String, outDir: File) {
    try {
      if (!outDir.exists()) outDir.mkdirs()

      val am = ctx.assets
      val list = am.list(assetDir) ?: return

      // Delete only direct children that no longer exist in assets.
      val existing = outDir.listFiles().orEmpty()
      val assetNames = list.toSet()
      for (f in existing) {
        if (!assetNames.contains(f.name)) {
          runCatching { f.deleteRecursively() }
        }
      }

      for (name in list) {
        val childAssetPath = if (assetDir.isEmpty()) name else "$assetDir/$name"
        val out = File(outDir, name)
        val sub = am.list(childAssetPath)
        if (sub != null && sub.isNotEmpty()) {
          if (!out.exists()) out.mkdirs()
          copyDir(ctx, childAssetPath, out)
        } else {
          val tmp = File(outDir, "$name.tmp")
          runCatching {
            am.open(childAssetPath).use { input ->
              tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
          }.onFailure {
            Log.e(TAG, "Failed to copy asset: $childAssetPath -> ${out.absolutePath}", it)
            runCatching { tmp.delete() }
          }
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "copyDir failed for assetDir=$assetDir outDir=${outDir.absolutePath}", t)
    }
  }
}
