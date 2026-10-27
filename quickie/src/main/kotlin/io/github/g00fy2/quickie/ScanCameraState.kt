package io.github.g00fy2.quickie

/** Actual CameraX zoom state. Limits can differ between devices and cameras. */
public data class ScanZoomState(public val ratio: Float, public val minRatio: Float, public val maxRatio: Float)

/** Feedback for a tap in [ContinuousScannerView], using coordinates relative to that view. */
public data class ScanFocusState(public val x: Float, public val y: Float, public val status: ScanFocusStatus)

public enum class ScanFocusStatus { FOCUSING, FOCUSED, FAILED, UNSUPPORTED, HIDDEN }