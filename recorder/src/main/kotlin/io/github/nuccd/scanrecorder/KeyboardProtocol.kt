package io.github.nuccd.scanrecorder

internal enum class KeyboardDelivery(val label: Int) {
  NONE(0),
  PENDING(R.string.keyboard_pending),
  SENDING(R.string.keyboard_sending),
  SUBMITTED(R.string.keyboard_submitted),
  UNCERTAIN(R.string.keyboard_uncertain),
  BLOCKED(R.string.keyboard_blocked)
}

internal object KeyboardProtocol {
  const val PORT = 28461
  const val MAX_BYTES = 8192
  const val MAX_UNITS = 4096
  const val HELLO = "SCANRECORDER/1"
  const val READY = "READY/1"

  fun canSend(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_UNITS &&
    value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && value.none {
      it.code < 32 || it.code in 127..159 || it == '\u2028' || it == '\u2029'
    } && Charsets.UTF_8.newEncoder().canEncode(value)

  fun initial(value: String): KeyboardDelivery =
    if (canSend(value)) KeyboardDelivery.PENDING else KeyboardDelivery.BLOCKED

  fun frame(id: String, value: String): String {
    require(id.matches(Regex("[a-f0-9-]{36}")) && canSend(value))
    val hex = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
      val number = byte.toInt() and 255
      "0123456789abcdef"[number / 16].toString() + "0123456789abcdef"[number % 16]
    }
    return "DATA $id $hex"
  }

  fun result(id: String, reply: String): KeyboardDelivery = when (reply) {
    "RESULT $id OK" -> KeyboardDelivery.SUBMITTED
    "RESULT $id PAUSED", "RESULT $id NO_TARGET" -> KeyboardDelivery.PENDING
    "RESULT $id INVALID" -> KeyboardDelivery.BLOCKED
    else -> KeyboardDelivery.UNCERTAIN
  }
}