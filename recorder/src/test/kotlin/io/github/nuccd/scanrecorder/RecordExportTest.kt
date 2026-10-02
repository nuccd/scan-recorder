package io.github.nuccd.scanrecorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter

internal class RecordExportTest {
  @Test
  fun `export contains contents in list order and no sequence numbers or metadata`() {
    val output = StringWriter()
    RecordExport.write(listOf("001234567890123", "BOX-A", "中文二维码"), output)
    assertEquals("001234567890123\r\nBOX-A\r\n中文二维码\r\n", output.toString())
  }

  @Test
  fun `newlines and backslashes are escaped so each record occupies one physical line`() {
    val output = StringWriter()
    RecordExport.write(listOf("first\r\nsecond", "literal\\n", "last\nline"), output)
    assertEquals("first\\r\\nsecond\r\nliteral\\\\n\r\nlast\\nline\r\n", output.toString())
  }

  @Test
  fun `empty export has no header or trailing record`() {
    val output = StringWriter()
    RecordExport.write(emptyList(), output)
    assertEquals("", output.toString())
  }
}