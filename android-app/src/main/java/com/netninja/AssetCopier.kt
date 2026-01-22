
package com.netninja

import android.content.Context
import java.io.File

object AssetCopier {
  fun copyDir(ctx: Context, assetDir: String, outDir: File) {
    val am = ctx.assets
    val list = am.list(assetDir) ?: return
    val assetNames = list.toSet()
    val existing = outDir.listFiles().orEmpty()
    for (file in existing) {
      if (!assetNames.contains(file.name)) {
        file.deleteRecursively()
      }
    }

    for (name in list) {
      val child = if (assetDir.isEmpty()) name else "$assetDir/$name"
      val out = File(outDir, name)
      val sub = am.list(child)
      if (sub != null && sub.isNotEmpty()) {
        out.mkdirs()
        copyDir(ctx, child, out)
      } else {
        am.open(child).use { input ->
          out.outputStream().use { output -> input.copyTo(output) }
        }
      }
    }
  }
}
