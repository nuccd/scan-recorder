package io.github.g00fy2.quickie

/** Decoders used by the embedded scanner. Native decoding supports linear barcodes only. */
public enum class ScanDecoderMode { COMBINED, ML_KIT, NATIVE }

/** Restricts decoding formats independently of the visible scan-frame shape. */
public enum class ScanFormatScope { ALL, LINEAR, QR }

/** Requested analysis size; CameraX negotiates the actual device-supported dimensions. */
public enum class ScanResolution(public val width: Int, public val height: Int) {
  HD(1280, 720),
  FULL_HD(1920, 1080)
}

/** Experimental parameters; defaults reproduce the 0.4.1 baseline and never bypass result confirmation. */
@Suppress("LongParameterList")
public data class ScanTuningOptions(
  public val decoderMode: ScanDecoderMode = ScanDecoderMode.COMBINED,
  public val formatScope: ScanFormatScope = ScanFormatScope.ALL,
  public val resolution: ScanResolution = ScanResolution.HD,
  public val minimumFrames: Int = 3,
  public val minimumMillis: Long = 200L,
  public val maximumGapMillis: Long = 150L,
  public val maximumObservationMillis: Long = 600L,
  public val focusWaitMillis: Long = 300L,
  public val nativeTryHarder: Boolean = true,
  public val nativeTryRotate: Boolean = true,
  public val nativeTryInvert: Boolean = true,
  public val nativeTryDownscale: Boolean = false,
  public val nativeInvertFallback: Boolean = true,
  public val nativeMinLineCount: Int = 2
) {
  init {
    require(minimumFrames in 1..10) { "minimumFrames must be 1..10" }
    require(minimumMillis in 0L..1500L) { "minimumMillis must be 0..1500" }
    require(maximumGapMillis in 0L..1000L) { "maximumGapMillis must be 0..1000" }
    require(maximumObservationMillis in 200L..3000L && maximumObservationMillis >= minimumMillis) {
      "maximumObservationMillis must be 200..3000 and at least minimumMillis"
    }
    require(focusWaitMillis in 0L..1000L) { "focusWaitMillis must be 0..1000" }
    require(nativeMinLineCount in 1..5) { "nativeMinLineCount must be 1..5" }
    require(decoderMode != ScanDecoderMode.NATIVE || formatScope != ScanFormatScope.QR) {
      "Native decoding does not support QR codes"
    }
  }
}