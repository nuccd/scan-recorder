package io.github.g00fy2.quickie

import android.graphics.RectF

/** Text and bounds in the upright, complete analysis image, independent of the decoding engine. */
internal data class DecodedBarcode(val rawValue: String, val bounds: RectF)