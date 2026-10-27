package io.github.nuccd.scanrecorder

import android.util.Log
import io.github.g00fy2.quickie.ScanFrameDiagnostics

/** Camera diagnostics contain counts and geometry, never decoded text. */
internal object ScanDiagnosticsLog {
  fun write(frame: ScanFrameDiagnostics) {
    val counts = frame.statusCounts.entries.joinToString(",") { (status, count) -> "$status:$count" }
    Log.i(
      "SCAN_DIAG",
      "frame=${frame.width}x${frame.height} rotation=${frame.rotationDegrees} decodeMs=${frame.decodeMillis} " +
        "decoded=${frame.decodedCount} inside=${frame.inWindowCount} unique=${frame.distinctInWindowCount} " +
        "status=${frame.status} total=${frame.totalFrames} counts=[$counts]"
    )
  }
}