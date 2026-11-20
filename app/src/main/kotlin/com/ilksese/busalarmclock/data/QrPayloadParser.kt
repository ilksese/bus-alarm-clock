package com.ilksese.busalarmclock.data

import java.net.URI
import java.net.URLDecoder

/** 仅解析已确认的成都公交二维码来源，不打开二维码中的任意网址。 */
object QrPayloadParser {
  private const val SUPPORTED_HOST = "cdgj-custombus.cnzhiyuanhui.com"
  private const val SUPPORTED_PATH = "/pages_common/pages/realTimeBus/station/index"

  fun parseGpsNumber(rawValue: String): String? {
    val value = rawValue.trim()
    if (value.matches(Regex("\\d{1,10}"))) return value

    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != SUPPORTED_HOST || uri.userInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    if (uri.rawPath != "/" || uri.rawQuery != null) return null
    val route = uri.rawFragment?.substringBefore('?') ?: return null
    if (route != SUPPORTED_PATH) return null
    val query = uri.rawFragment.substringAfter('?', missingDelimiterValue = "")
    return runCatching { query.split('&').mapNotNull(::parsePair).singleOrNull() }.getOrNull()
  }

  private fun parsePair(part: String): String? {
    val separator = part.indexOf('=')
    if (separator < 0) return null
    val key = URLDecoder.decode(part.substring(0, separator), Charsets.UTF_8)
    val queryValue = URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8)
    return queryValue.takeIf { key == "gpsNum" && it.matches(Regex("\\d{1,10}")) }
  }
}
