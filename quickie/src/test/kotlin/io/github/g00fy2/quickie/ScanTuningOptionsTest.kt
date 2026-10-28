package io.github.g00fy2.quickie

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ScanTuningOptionsTest {
  @Test
  fun `default options preserve the stable evidence and analysis defaults`() {
    val options = ScanTuningOptions()
    assertEquals(3, options.minimumFrames)
    assertEquals(200L, options.minimumMillis)
    assertEquals(150L, options.maximumGapMillis)
    assertEquals(600L, options.maximumObservationMillis)
    assertEquals(300L, options.focusWaitMillis)
    assertEquals(1280, options.resolution.width)
    assertEquals(720, options.resolution.height)
  }

  @Test
  fun `engine choices route only to the requested decoders`() {
    for (mode in ScanDecoderMode.entries) {
      val plan = ScanDecodePlan(ScanTuningOptions(decoderMode = mode), intArrayOf(Barcode.FORMAT_ALL_FORMATS))
      assertEquals(mode != ScanDecoderMode.NATIVE, plan.useMlKit)
      assertEquals(mode != ScanDecoderMode.ML_KIT, plan.useNative)
    }
    val legacy = ScanDecodePlan(ScanTuningOptions(), intArrayOf(Barcode.FORMAT_QR_CODE), embedded = false)
    assertTrue(legacy.useMlKit)
    assertFalse(legacy.useNative)
  }

  @Test
  fun `linear scope excludes matrix codes and preserves an explicit linear restriction`() {
    val all = ScanDecodePlan(
      ScanTuningOptions(formatScope = ScanFormatScope.LINEAR),
      intArrayOf(Barcode.FORMAT_ALL_FORMATS)
    )
    assertEquals(9, all.formats.size)
    assertFalse(Barcode.FORMAT_QR_CODE in all.formats)
    assertFalse(Barcode.FORMAT_DATA_MATRIX in all.formats)
    val restricted = ScanDecodePlan(
      ScanTuningOptions(formatScope = ScanFormatScope.LINEAR),
      intArrayOf(Barcode.FORMAT_CODE_128)
    )
    assertEquals(listOf(Barcode.FORMAT_CODE_128), restricted.formats.toList())
  }

  @Test
  fun `QR scope cannot accidentally invoke the native linear engine`() {
    val plan = ScanDecodePlan(
      ScanTuningOptions(formatScope = ScanFormatScope.QR),
      intArrayOf(Barcode.FORMAT_ALL_FORMATS)
    )
    assertEquals(listOf(Barcode.FORMAT_QR_CODE), plan.formats.toList())
    assertTrue(plan.useMlKit)
    assertFalse(plan.useNative)
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
      { ScanTuningOptions(nativeMinLineCount = 6) }
    )
    invalid.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    assertThrows(IllegalArgumentException::class.java) {
      ScanDecodePlan(ScanTuningOptions(formatScope = ScanFormatScope.LINEAR), intArrayOf(Barcode.FORMAT_QR_CODE))
    }
  }
}