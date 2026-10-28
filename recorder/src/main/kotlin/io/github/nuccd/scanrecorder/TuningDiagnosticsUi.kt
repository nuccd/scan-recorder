package io.github.nuccd.scanrecorder

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.g00fy2.quickie.ScanDecoderMode
import io.github.g00fy2.quickie.ScanFormatScope
import io.github.g00fy2.quickie.ScanFrameDiagnostics
import io.github.g00fy2.quickie.ScanFrameStatus
import io.github.g00fy2.quickie.ScanTuningOptions
import io.github.nuccd.scanrecorder.databinding.ActivityMainBinding

/** Tuning-only metadata. Reports never access scanned values or the records database. */
internal class TuningDiagnosticsUi(
  private val activity: AppCompatActivity,
  private val binding: ActivityMainBinding
) {
  private var enabled = false
  private var options = ScanTuningOptions()
  private var latest: ScanFrameDiagnostics? = null

  fun configure(enabled: Boolean, onOpenSettings: () -> Unit, onReset: () -> Unit) {
    this.enabled = enabled
    val visibility = if (enabled) View.VISIBLE else View.GONE
    binding.tuningButton.visibility = visibility
    binding.tuningPanel.visibility = visibility
    binding.tuningButton.setOnClickListener { onOpenSettings() }
    binding.tuningCopyButton.setOnClickListener { copyReport() }
    binding.tuningResetButton.setOnClickListener {
      onReset()
      reset()
    }
    reset()
  }

  fun setOptions(options: ScanTuningOptions) {
    if (this.options != options) reset()
    this.options = options
    latest?.let(::render)
  }

  fun onFrame(frame: ScanFrameDiagnostics) {
    if (enabled) {
      latest = frame
      render(frame)
    }
  }

  fun reset() {
    latest = null
    binding.tuningDiagnostics.setText(R.string.tuning_diagnostics_waiting)
  }

  private fun render(frame: ScanFrameDiagnostics) {
    val mlKit = if (options.decoderMode == ScanDecoderMode.NATIVE) {
      activity.getString(R.string.tuning_engine_disabled)
    } else {
      activity.getString(R.string.tuning_engine_measurement, frame.mlKitMillis, frame.mlKitCount)
    }
    val native = nativeMeasurement(frame)
    binding.tuningDiagnostics.text = activity.getString(
      R.string.tuning_diagnostics_compact,
      frame.width,
      frame.height,
      frame.rotationDegrees,
      frame.decodeMillis,
      frame.averageDecodeMillis,
      frame.maxDecodeMillis,
      mlKit,
      native,
      activity.getString(statusLabel(frame.status)),
      frame.totalFrames
    )
  }

  private fun nativeMeasurement(frame: ScanFrameDiagnostics): String = when {
    options.decoderMode == ScanDecoderMode.ML_KIT || options.formatScope == ScanFormatScope.QR ->
      activity.getString(R.string.tuning_engine_disabled)
    !frame.nativeAvailable -> activity.getString(R.string.tuning_engine_unavailable)
    else -> activity.getString(R.string.tuning_engine_measurement, frame.nativeMillis, frame.nativeCount)
  }

  private fun copyReport() {
    val clipboard = activity.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.tuning_report_title), report()))
    Toast.makeText(activity, R.string.tuning_report_copied, Toast.LENGTH_SHORT).show()
  }

  private fun report(): String = buildString {
    appendLine(activity.getString(R.string.tuning_report_title))
    appendLine("version=${BuildConfig.VERSION_NAME} SDK=${Build.VERSION.SDK_INT}")
    appendLine("model=${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("zoom=${binding.zoomValue.text}")
    appendLine("options=$options")
    appendLine(activity.getString(R.string.tuning_report_timing_note))
    val frame = latest
    if (frame == null) appendLine(activity.getString(R.string.tuning_diagnostics_waiting)) else appendFrame(frame)
    appendLine("statusCounts:")
    ScanFrameStatus.entries.forEach { status ->
      appendLine("${status.name}=${frame?.statusCounts?.get(status) ?: 0L}")
    }
  }

  private fun StringBuilder.appendFrame(frame: ScanFrameDiagnostics) {
    appendLine("image=${frame.width}x${frame.height} rotation=${frame.rotationDegrees}")
    appendLine("frameDecodeMs=${frame.decodeMillis} average=${frame.averageDecodeMillis} max=${frame.maxDecodeMillis}")
    appendLine("mlKitMs=${frame.mlKitMillis} mlKitCount=${frame.mlKitCount}")
    appendLine("nativeMs=${frame.nativeMillis} nativeCount=${frame.nativeCount}")
    appendLine("nativeAvailable=${frame.nativeAvailable}")
    appendLine("decoded=${frame.decodedCount} inWindow=${frame.inWindowCount}")
    appendLine("distinctInWindow=${frame.distinctInWindowCount}")
    appendLine("status=${frame.status} totalFrames=${frame.totalFrames} sessionMs=${frame.sessionMillis}")
  }

  private fun statusLabel(status: ScanFrameStatus): Int = when (status) {
    ScanFrameStatus.TRANSFORM_UNAVAILABLE -> R.string.tuning_stage_transform
    ScanFrameStatus.NO_DECODE -> R.string.tuning_stage_no_decode
    ScanFrameStatus.OUTSIDE_WINDOW -> R.string.tuning_stage_outside
    ScanFrameStatus.MULTIPLE_VALUES -> R.string.tuning_stage_multiple
    ScanFrameStatus.WAITING_FOR_STABILITY -> R.string.tuning_stage_stability
    ScanFrameStatus.ALREADY_REPORTED -> R.string.tuning_stage_reported
    ScanFrameStatus.REVIEW_PAUSED -> R.string.tuning_stage_review
    ScanFrameStatus.DELIVERED -> R.string.tuning_stage_delivered
    ScanFrameStatus.CAMERA_ADJUSTING -> R.string.tuning_stage_adjusting
  }
}