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
  val nativeFormats: Set<BarcodeReader.Format> =
    if (embedded && options.decoderMode != ScanDecoderMode.ML_KIT) nativeFormatsFor(options.decoderMode) else emptySet()
  val useNative: Boolean get() = nativeFormats.isNotEmpty()

  private fun nativeFormatsFor(mode: ScanDecoderMode): Set<BarcodeReader.Format> = buildSet {
    val all = Barcode.FORMAT_ALL_FORMATS in mlKitFormats
    LINEAR_FORMATS.forEach { (format, native) ->
      if (all || format in mlKitFormats) add(native)
    }
    if (all || Barcode.FORMAT_QR_CODE in mlKitFormats) {
      add(BarcodeReader.Format.MICRO_QR_CODE)
      add(BarcodeReader.Format.RMQR_CODE)
    }
    if (mode == ScanDecoderMode.NATIVE) {
      NATIVE_MATRIX_FORMATS.forEach { (format, native) ->
        if (all || format in mlKitFormats) add(native)
      }
    }
  }

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
    val NATIVE_MATRIX_FORMATS = mapOf(
      Barcode.FORMAT_QR_CODE to BarcodeReader.Format.QR_CODE_MODEL_2,
      Barcode.FORMAT_DATA_MATRIX to BarcodeReader.Format.DATA_MATRIX,
      Barcode.FORMAT_PDF417 to BarcodeReader.Format.PDF_417,
      Barcode.FORMAT_AZTEC to BarcodeReader.Format.AZTEC_CODE
    )
  }
}