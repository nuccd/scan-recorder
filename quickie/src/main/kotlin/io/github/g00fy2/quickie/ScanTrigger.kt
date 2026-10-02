package io.github.g00fy2.quickie

/** Only a decoded absence rearms a value; a slow frame alone must never do so. */
internal class ScanTrigger(private val absenceMillis: Long = 500L) {
  private val latched = mutableSetOf<String>()
  private val missingSince = mutableMapOf<String, Long>()

  fun next(visibleValues: Set<String>, target: String?, nowMillis: Long): String? {
    for (value in latched.toList()) {
      val since = missingSince[value]
      if (value in visibleValues) {
        if (since != null && nowMillis - since >= absenceMillis) latched.remove(value)
        missingSince.remove(value)
      } else {
        val absentSince = missingSince.getOrPut(value) { nowMillis }
        if (nowMillis - absentSince >= absenceMillis) {
          latched.remove(value)
          missingSince.remove(value)
        }
      }
    }
    return target?.takeIf { it in visibleValues && latched.add(it) }
  }
}