package io.github.g00fy2.quickie

import com.google.mlkit.vision.barcode.common.Barcode
import zxingcpp.BarcodeReader

/** Restricts the actual decoders, independently of the visible frame shape. */
internal class ScanDecodePlan(options: ScanTuningOptions, requestedFormats: IntArray, embedded: Boolean = true) {
  val mlKitFormats: IntArray = when (options.formatScope) {
    ScanFormatScope.ALL -> requestedFormats
    ScanFormatScope.LINEAR -> LINEAR_FORMATS.keys.filter {
      Barcode.FORMAT_ALL_FORMATS in requestedFormats || it in requestedFormats
    }.toIntArray()
    ScanFormatScope.QR -> intArrayOf(Barcode.FORMAT_QR_CODE).filter {
      Barcode.FORMAT_ALL_FORMATS in requestedFormats || it in requestedFormats
    }.toIntArray()
  }.also { require(it.isNotEmpty()) { "No requested formats match the selected scope" } }
  val useMlKit: Boolean = !embedded || options.decoderMode != ScanDecoderMode.NATIVE
  val nativeFormats: Set<BarcodeReader.Format> = when {
    !embedded || options.decoderMode == ScanDecoderMode.ML_KIT -> emptySet()
    options.formatScope == ScanFormatScope.QR -> setOf(BarcodeReader.Format.MICRO_QR_CODE)
    else -> LINEAR_FORMATS.filterKeys {
      Barcode.FORMAT_ALL_FORMATS in mlKitFormats || it in mlKitFormats
    }.values.toSet()
  }
  val useNative: Boolean get() = nativeFormats.isNotEmpty()

  private companion object {
    val LINEAR_FORMATS = mapOf(
      Barcode.FORMAT_CODABAR to BarcodeReader.Format.CODABAR,
      Barcode.FORMAT_CODE_39 to BarcodeReader.Format.CODE_39,
      Barcode.FORMAT_CODE_93 to BarcodeReader.Format.CODE_93,
      Barcode.FORMAT_CODE_128 to BarcodeReader.Format.CODE_128,
      Barcode.FORMAT_EAN_13 to BarcodeReader.Format.EAN_13,
      Barcode.FORMAT_EAN_8 to BarcodeReader.Format.EAN_8,
      Barcode.FORMAT_ITF to BarcodeReader.Format.ITF,
      Barcode.FORMAT_UPC_A to BarcodeReader.Format.UPC_A,
      Barcode.FORMAT_UPC_E to BarcodeReader.Format.UPC_E
    )
  }
}