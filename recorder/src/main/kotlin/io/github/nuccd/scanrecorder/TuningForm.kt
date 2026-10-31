package io.github.nuccd.scanrecorder

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.google.android.material.textfield.TextInputLayout
import io.github.g00fy2.quickie.ScanDecoderMode
import io.github.g00fy2.quickie.ScanFormatScope
import io.github.g00fy2.quickie.ScanResolution
import io.github.g00fy2.quickie.ScanTuningOptions
import io.github.nuccd.scanrecorder.databinding.ActivityTuningBinding

internal class TuningForm(private val binding: ActivityTuningBinding) {
  private val numbers = listOf(
    NumberField(binding.tuningFrames, 1L..10L),
    NumberField(binding.tuningMinimumMs, 0L..1500L),
    NumberField(binding.tuningGapMs, 0L..1000L),
    NumberField(binding.tuningObservationMs, 200L..3000L),
    NumberField(binding.tuningFocusMs, 0L..1000L),
    NumberField(binding.tuningLines, 1L..5L)
  )
  private val switches = listOf(
    binding.tuningHarder,
    binding.tuningRotate,
    binding.tuningInvert,
    binding.tuningDownscale,
    binding.tuningExplicitInvert
  )
  private val selectors = listOf(binding.tuningDecoder, binding.tuningFormats, binding.tuningResolution)

  init {
    setupSelector(binding.tuningDecoder, R.array.tuning_decoder_values)
    setupSelector(binding.tuningFormats, R.array.tuning_format_values)
    setupSelector(binding.tuningResolution, R.array.tuning_resolution_values)
  }

  // Parameter fields use plain decimal digits for validation.
  @SuppressLint("SetTextI18n")
  fun populate(options: ScanTuningOptions) {
    selectors.zip(listOf(options.decoderMode.ordinal, options.formatScope.ordinal, options.resolution.ordinal))
      .forEach { (selector, value) -> selector.setSelection(value) }
    val values = listOf(
      options.minimumFrames.toLong(),
      options.minimumMillis,
      options.maximumGapMillis,
      options.maximumObservationMillis,
      options.focusWaitMillis,
      options.nativeMinLineCount.toLong()
    )
    numbers.zip(values).forEach { (field, value) ->
      field.layout.editText?.setText(value.toString())
      field.layout.error = null
    }
    val flags = listOf(
      options.nativeTryHarder,
      options.nativeTryRotate,
      options.nativeTryInvert,
      options.nativeTryDownscale,
      options.nativeInvertFallback
    )
    switches.zip(flags).forEach { (control, checked) -> control.isChecked = checked }
    binding.tuningDefaultZoom.editText?.setText(options.defaultZoomRatio.toString())
    binding.tuningDefaultZoom.error = null
    binding.tuningNotice.setText(R.string.tuning_draft_notice)
    binding.tuningError.text = ""
  }

  fun read(): ScanTuningOptions? {
    val values = numbers.map(::readNumber)
    val zoom = readZoomRatio()
    val windowValid = validWindow(values)
    val unsupported = binding.tuningDecoder.selectedItemPosition == ScanDecoderMode.NATIVE.ordinal &&
      binding.tuningFormats.selectedItemPosition == ScanFormatScope.QR.ordinal
    val numericValid = values.none { it == null } && windowValid && zoom != null
    binding.tuningError.text = when {
      unsupported -> binding.root.context.getString(R.string.tuning_native_qr_error)
      !numericValid -> binding.root.context.getString(R.string.tuning_input_error)
      else -> ""
    }
    return if (numericValid && !unsupported) options(values.map { requireNotNull(it) }, requireNotNull(zoom)) else null
  }

  private fun options(values: List<Long>, zoom: Float): ScanTuningOptions = ScanTuningOptions(
    decoderMode = ScanDecoderMode.entries[binding.tuningDecoder.selectedItemPosition],
    formatScope = ScanFormatScope.entries[binding.tuningFormats.selectedItemPosition],
    resolution = ScanResolution.entries[binding.tuningResolution.selectedItemPosition],
    minimumFrames = values[0].toInt(),
    minimumMillis = values[1],
    maximumGapMillis = values[2],
    maximumObservationMillis = values[3],
    focusWaitMillis = values[4],
    nativeMinLineCount = values[5].toInt(),
    nativeTryHarder = binding.tuningHarder.isChecked,
    nativeTryRotate = binding.tuningRotate.isChecked,
    nativeTryInvert = binding.tuningInvert.isChecked,
    nativeTryDownscale = binding.tuningDownscale.isChecked,
    nativeInvertFallback = binding.tuningExplicitInvert.isChecked,
    defaultZoomRatio = zoom
  )

  fun saveDraft(): Bundle = Bundle().apply {
    numbers.forEachIndexed { index, field -> putString("number_$index", field.layout.editText?.text.toString()) }
    switches.forEachIndexed { index, control -> putBoolean("switch_$index", control.isChecked) }
    selectors.forEachIndexed { index, selector -> putInt("selector_$index", selector.selectedItemPosition) }
    putString("notice", binding.tuningNotice.text.toString())
    putString("default_zoom_draft", binding.tuningDefaultZoom.editText?.text.toString())
  }

  fun restoreDraft(state: Bundle) {
    numbers.forEachIndexed { index, field -> field.layout.editText?.setText(state.getString("number_$index")) }
    switches.forEachIndexed { index, control -> control.isChecked = state.getBoolean("switch_$index") }
    selectors.forEachIndexed { index, selector ->
      selector.setSelection(state.getInt("selector_$index").coerceIn(0, selector.count - 1))
    }
    binding.tuningNotice.text = state.getString("notice")
    state.getString("default_zoom_draft")?.let { binding.tuningDefaultZoom.editText?.setText(it) }
  }

  private fun readZoomRatio(): Float? {
    val value = binding.tuningDefaultZoom.editText?.text?.toString()?.trim()?.toFloatOrNull()
    val valid = value != null && value.isFinite() && value in 1f..10f
    binding.tuningDefaultZoom.error = if (valid) null else binding.root.context.getString(R.string.tuning_zoom_error)
    return value.takeIf { valid }
  }

  private fun validWindow(values: List<Long?>): Boolean {
    val minimum = values[1]
    val window = values[3]
    val valid = minimum == null || window == null || window >= minimum
    if (!valid) binding.tuningObservationMs.error = binding.root.context.getString(R.string.tuning_window_error)
    return valid
  }

  private fun readNumber(field: NumberField): Long? {
    val value = field.layout.editText?.text?.toString()?.trim()?.toLongOrNull()
    val valid = value != null && value in field.range
    field.layout.error = if (valid) {
      null
    } else {
      binding.root.context.getString(R.string.tuning_number_error, field.range.first, field.range.last)
    }
    return value.takeIf { valid }
  }

  private fun setupSelector(selector: Spinner, labels: Int) {
    selector.adapter = ArrayAdapter.createFromResource(
      binding.root.context,
      labels,
      android.R.layout.simple_spinner_dropdown_item
    )
  }

  private data class NumberField(val layout: TextInputLayout, val range: LongRange)
}