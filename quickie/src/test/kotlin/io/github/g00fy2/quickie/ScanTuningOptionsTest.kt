package io.github.g00fy2.quickie

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zxingcpp.BarcodeReader

internal class ScanTuningOptionsTest {
  @Test
  fun `default options preserve the stable evidence and analysis defaults`() {
    val options = ScanTuningOptions()
    assertEquals(2.3f, options.defaultZoomRatio)
    assertEquals(3, options.minimumFrames)
    assertEquals(200L, options.minimumMillis)
    assertEquals(150L, options.maximumGapMillis)
    assertEquals(600L, options.maximumObservationMillis)
    assertEquals(300L, options.focusWaitMillis)
    assertEquals(1280, options.resolution.width)
    assertEquals(720, options.resolution.height)
  }

  @Test
  fun `all formats keep native decoding limited to linear codes`() {
    for (mode in ScanDecoderMode.entries) {
      val plan = ScanDecodePlan(ScanTuningOptions(decoderMode = mode), intArrayOf(Barcode.FORMAT_ALL_FORMATS))
      assertEquals(mode != ScanDecoderMode.NATIVE, plan.useMlKit)
      assertEquals(if (mode == ScanDecoderMode.ML_KIT) 0 else 9, plan.nativeFormats.size)
      assertFalse(BarcodeReader.Format.MICRO_QR_CODE in plan.nativeFormats)
    }
  }

  @Test
  fun `linear scope excludes matrix codes and preserves an explicit linear restriction`() {
    val all = ScanDecodePlan(
      ScanTuningOptions(formatScope = ScanFormatScope.LINEAR),
      intArrayOf(Barcode.FORMAT_ALL_FORMATS)
    )
    assertEquals(9, all.mlKitFormats.size)
    assertFalse(Barcode.FORMAT_QR_CODE in all.mlKitFormats)
    assertFalse(Barcode.FORMAT_DATA_MATRIX in all.mlKitFormats)
    assertFalse(BarcodeReader.Format.MICRO_QR_CODE in all.nativeFormats)
    val restricted = ScanDecodePlan(
      ScanTuningOptions(formatScope = ScanFormatScope.LINEAR),
      intArrayOf(Barcode.FORMAT_CODE_128)
    )
    assertEquals(listOf(Barcode.FORMAT_CODE_128), restricted.mlKitFormats.toList())
    assertEquals(setOf(BarcodeReader.Format.CODE_128), restricted.nativeFormats)
  }

  @Test
  fun `embedded dual engine QR scope routes standard QR to ML Kit and Micro QR to native decoding`() {
    val options = ScanTuningOptions(formatScope = ScanFormatScope.QR)
    val formats = intArrayOf(Barcode.FORMAT_ALL_FORMATS)
    val plan = ScanDecodePlan(options, formats)
    assertEquals(listOf(Barcode.FORMAT_QR_CODE), plan.mlKitFormats.toList())
    assertTrue(plan.useMlKit)
    assertEquals(setOf(BarcodeReader.Format.MICRO_QR_CODE), plan.nativeFormats)
    assertFalse(ScanDecodePlan(options.copy(decoderMode = ScanDecoderMode.ML_KIT), formats).useNative)
    val legacy = ScanDecodePlan(options, formats, embedded = false)
    assertTrue(legacy.useMlKit)
    assertFalse(legacy.useNative)
  }

  @Test
  fun `native only mode cannot select QR scope`() {
    assertThrows(IllegalArgumentException::class.java) {
      ScanTuningOptions(decoderMode = ScanDecoderMode.NATIVE, formatScope = ScanFormatScope.QR)
    }
  }

  @Test
  fun `invalid tuning ranges and empty format intersections fail explicitly`() {
    val invalid = listOf<() -> ScanTuningOptions>(
      { ScanTuningOptions(minimumFrames = 0) },
      { ScanTuningOptions(minimumMillis = -1) },
      { ScanTuningOptions(maximumGapMillis = 1001) },
      { ScanTuningOptions(maximumObservationMillis = 199) },
      { ScanTuningOptions(minimumMillis = 700, maximumObservationMillis = 600) },
      { ScanTuningOptions(focusWaitMillis = -1) },
      { ScanTuningOptions(nativeMinLineCount = 6) },
      { ScanTuningOptions(defaultZoomRatio = 0.9f) },
      { ScanTuningOptions(defaultZoomRatio = 10.1f) },
      { ScanTuningOptions(defaultZoomRatio = Float.NaN) },
      { ScanTuningOptions(defaultZoomRatio = Float.POSITIVE_INFINITY) }
    )
    invalid.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    assertThrows(IllegalArgumentException::class.java) {
      ScanDecodePlan(ScanTuningOptions(formatScope = ScanFormatScope.LINEAR), intArrayOf(Barcode.FORMAT_QR_CODE))
    }
  }
}