package io.github.nuccd.scanrecorder

import java.io.Writer

/** Line-oriented text, with no row numbers, headers, or summaries. */
internal object RecordExport {
  fun line(value: String): String = buildString {
    for (character in value) {
      append(
        when (character) {
        '\\' -> "\\\\"
        '\r' -> "\\r"
        '\n' -> "\\n"
        else -> character.toString()
      }
    )
    }
  }

  fun write(values: List<String>, writer: Writer) {
    for (value in values) {
      writer.write(line(value))
      writer.write("\r\n")
    }
  }
}