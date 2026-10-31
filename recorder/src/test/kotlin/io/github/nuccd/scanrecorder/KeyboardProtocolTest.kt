package io.github.nuccd.scanrecorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class KeyboardProtocolTest {
  private val id = "12345678-1234-1234-1234-123456789abc"

  @Test
  fun `generic text is encoded verbatim without sequence prefix or appended newline`() {
    assertEquals("DATA $id 3030313233", KeyboardProtocol.frame(id, "00123"))
    assertEquals("DATA $id e4b8ade69687", KeyboardProtocol.frame(id, "中文"))
    assertTrue(KeyboardProtocol.canSend(" https://example.com/a?x=1 "))
    assertTrue(KeyboardProtocol.canSend("😀"))
  }

  @Test
  fun `embedded controls and separators never become keyboard actions`() {
    for (value in listOf("a\nb", "a\rb", "a\tb", "\u0000", "\u001b", "\u007f", "\u0085", "\u2028", "\u2029")) {
      assertFalse(KeyboardProtocol.canSend(value))
      assertEquals(KeyboardDelivery.BLOCKED, KeyboardProtocol.initial(value))
    }
    assertThrows(IllegalArgumentException::class.java) { KeyboardProtocol.frame(id, "a\nb") }
  }

  @Test
  fun `bounds and malformed UTF16 are rejected without truncating the original`() {
    assertFalse(KeyboardProtocol.canSend(""))
    assertTrue(KeyboardProtocol.canSend("a".repeat(4096)))
    assertFalse(KeyboardProtocol.canSend("a".repeat(4097)))
    assertFalse(KeyboardProtocol.canSend("中".repeat(3000)))
    assertFalse(KeyboardProtocol.canSend("\ud800"))
  }

  @Test
  fun `only the matching positive result is submitted and lost results stay uncertain`() {
    assertEquals(KeyboardDelivery.SUBMITTED, KeyboardProtocol.result(id, "RESULT $id OK"))
    assertEquals(KeyboardDelivery.PENDING, KeyboardProtocol.result(id, "RESULT $id PAUSED"))
    assertEquals(KeyboardDelivery.PENDING, KeyboardProtocol.result(id, "RESULT $id NO_TARGET"))
    assertEquals(KeyboardDelivery.BLOCKED, KeyboardProtocol.result(id, "RESULT $id INVALID"))
    assertEquals(KeyboardDelivery.UNCERTAIN, KeyboardProtocol.result(id, "RESULT other OK"))
    assertEquals(KeyboardDelivery.UNCERTAIN, KeyboardProtocol.result(id, "RESULT $id PARTIAL"))
  }
}