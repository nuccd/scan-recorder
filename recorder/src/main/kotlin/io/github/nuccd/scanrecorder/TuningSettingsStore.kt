package io.github.nuccd.scanrecorder

import android.content.Context
import androidx.core.content.edit
import io.github.g00fy2.quickie.ScanDecoderMode
import io.github.g00fy2.quickie.ScanFormatScope
import io.github.g00fy2.quickie.ScanResolution
import io.github.g00fy2.quickie.ScanTuningOptions

/** Stores tuning parameters only; invalid or damaged preferences fall back to the baseline as a whole. */
internal class TuningSettingsStore(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("scan_tuning", Context.MODE_PRIVATE)

  fun load(): ScanTuningOptions = runCatching {
    val baseline = ScanTuningOptions()
    ScanTuningOptions(
      decoderMode = ScanDecoderMode.valueOf(preferences.getString("decoder", baseline.decoderMode.name).orEmpty()),
      formatScope = ScanFormatScope.valueOf(preferences.getString("formats", baseline.formatScope.name).orEmpty()),
      resolution = ScanResolution.valueOf(preferences.getString("resolution", baseline.resolution.name).orEmpty()),
      minimumFrames = preferences.getInt("frames", baseline.minimumFrames),
      minimumMillis = preferences.getLong("minimum_ms", baseline.minimumMillis),
      maximumGapMillis = preferences.getLong("gap_ms", baseline.maximumGapMillis),
      maximumObservationMillis = preferences.getLong("observation_ms", baseline.maximumObservationMillis),
      focusWaitMillis = preferences.getLong("focus_ms", baseline.focusWaitMillis),
      nativeTryHarder = preferences.getBoolean("harder", baseline.nativeTryHarder),
      nativeTryRotate = preferences.getBoolean("rotate", baseline.nativeTryRotate),
      nativeTryInvert = preferences.getBoolean("invert", baseline.nativeTryInvert),
      nativeTryDownscale = preferences.getBoolean("downscale", baseline.nativeTryDownscale),
      nativeInvertFallback = preferences.getBoolean("invert_fallback", baseline.nativeInvertFallback),
      nativeMinLineCount = preferences.getInt("lines", baseline.nativeMinLineCount)
    )
  }.getOrElse { ScanTuningOptions() }

  fun save(options: ScanTuningOptions) {
    preferences.edit {
      putString("decoder", options.decoderMode.name)
      putString("formats", options.formatScope.name)
      putString("resolution", options.resolution.name)
      putInt("frames", options.minimumFrames)
      putLong("minimum_ms", options.minimumMillis)
      putLong("gap_ms", options.maximumGapMillis)
      putLong("observation_ms", options.maximumObservationMillis)
      putLong("focus_ms", options.focusWaitMillis)
      putBoolean("harder", options.nativeTryHarder)
      putBoolean("rotate", options.nativeTryRotate)
      putBoolean("invert", options.nativeTryInvert)
      putBoolean("downscale", options.nativeTryDownscale)
      putBoolean("invert_fallback", options.nativeInvertFallback)
      putInt("lines", options.nativeMinLineCount)
    }
  }
}