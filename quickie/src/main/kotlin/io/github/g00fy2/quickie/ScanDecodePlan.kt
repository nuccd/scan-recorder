package io.github.g00fy2.quickie

import com.google.mlkit.vision.barcode.common.Barcode

/** Restricts the actual decoders, independently of the visible frame shape. */
internal class ScanDecodePlan(options: ScanTuningOptions, requestedFormats: IntArray, embedded: Boolean = true) {
  val formats: IntArray = when (options.formatScope) {
    ScanFormatScope.ALL -> requestedFormats
    ScanFormatScope.LINEAR -> LINEAR_FORMATS.filter {
      Barcode.FORMAT_ALL_FORMATS in requestedFormats || it in requestedFormats
    }.toIntArray()
    ScanFormatScope.QR -> intArrayOf(Barcode.FORMAT_QR_CODE).filter {
      Barcode.FORMAT_ALL_FORMATS in requestedFormats || it in requestedFormats
    }.toIntArray()
  }.also { require(it.isNotEmpty()) { "No requested formats match the selected scope" } }
  val useMlKit: Boolean = !embedded || options.decoderMode != ScanDecoderMode.NATIVE
  val useNative: Boolean = embedded && options.decoderMode != ScanDecoderMode.ML_KIT &&
    formats.any { it == Barcode.FORMAT_ALL_FORMATS || it in LINEAR_FORMATS }

  private companion object {
    val LINEAR_FORMATS = setOf(
      Barcode.FORMAT_CODABAR, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODE_128,
      Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_ITF, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
    )
  }
}