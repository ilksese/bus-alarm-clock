package com.ilksese.busalarmclock.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private const val API_BASE = "https://cdgj-oms-gateway.cnzhiyuanhui.com"

/** 固定来源适配器；所有入口按请求类别与查询身份共享节流、在途请求及原始获取时间。 */
class BusSourceClient(
  private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
  private val clock: () -> Long = System::currentTimeMillis,
  private val gate: StopRequestGate = StopRequestGate(clock),
) {
  suspend fun resolveStation(gpsNumber: String): StationChoice {
    val mapping = get("/bus/stopMap/getByGpsNum", "gpsNum", gpsNumber).value.jsonObject
    val name = mapping.getValue("stationCode").jsonPrimitive.content
    val defaultCode = mapping.getValue("stopCode").jsonPrimitive.content
    val station = get("/bus/station/detail/station_stop", "stationNo", name)
    val stops = station.value.jsonObject.getValue("stationStop").jsonArray.map { value ->
      val item = value.jsonObject
      StationStop(item.getValue("stopCode").jsonPrimitive.content, item["stationName"]?.jsonPrimitive?.content ?: name)
    }.distinctBy { it.stopCode }
    check(stops.any { it.stopCode == defaultCode }) { "来源站台清单缺少二维码对应站台" }
    return StationChoice(name, defaultCode, stops, station.fetchedAt)
  }

  suspend fun resolveLines(stationName: String, stopCode: String): SourceSnapshot<List<SelectedLine>> {
    val snapshot = get("/bus/station/detail/stop_line", "stopCode", stopCode)
    return SourceSnapshot(parseStopLine(snapshot.value, stopCode).lines, snapshot.fetchedAt, snapshot.nextAllowedAt)
  }

  /** 与 resolveLines 共用同一请求身份，选线核对与等车刷新共享节流、在途请求和原始获取时间。 */
  suspend fun observeStop(stopCode: String): SourceSnapshot<StopLineSnapshot> {
    val snapshot = get("/bus/station/detail/stop_line", "stopCode", stopCode)
    return SourceSnapshot(parseStopLine(snapshot.value, stopCode), snapshot.fetchedAt, snapshot.nextAllowedAt)
  }

  private fun parseStopLine(data: JsonElement, stopCode: String): StopLineSnapshot {
    val lines = mutableListOf<SelectedLine>()
    val arrivals = mutableMapOf<String, LineArrival>()
    data.jsonArray.forEach { value ->
      val line = value.jsonObject
      val parsed = SelectedLine(
        lineNo = line.getValue("lineNo").jsonPrimitive.content,
        upDown = line.getValue("upDown").jsonPrimitive.intOrNull ?: error("线路方向缺失"),
        endStationName = line.getValue("endStationName").jsonPrimitive.content,
        targetSequence = line.getValue("mySequence").jsonPrimitive.intOrNull ?: error("目标站序缺失"),
        lineName = line["lineName"]?.jsonPrimitive?.content ?: line.getValue("lineNo").jsonPrimitive.content,
      )
      lines += parsed
      // 只接受明确指向本次查询站台的车辆对象；整条线路的 cars 不能当作候车站 ETA。
      arrivalOf(line["car"], stopCode)?.let { arrivals[parsed.identity] = it }
    }
    return StopLineSnapshot(lines.distinctBy { it.identity }, arrivals)
  }

  private fun arrivalOf(car: JsonElement?, stopCode: String): LineArrival? {
    val vehicle = car as? JsonObject ?: return null
    if (vehicle["stopCode"]?.jsonPrimitive?.contentOrNull != stopCode) return null
    val seconds = vehicle["arrivalTime"]?.jsonPrimitive?.longOrNull ?: return null
    if (seconds < 0) return null
    val stops = vehicle["stationNum"]?.jsonPrimitive?.intOrNull
    // 来源按秒返回剩余时间，页面按整数分钟展示；不足一分钟按 0 分钟处理，不虚构精度。
    return LineArrival((seconds / 60L).toInt(), stops, stops == 0)
  }

  private suspend fun get(path: String, parameter: String, value: String): SourceSnapshot<JsonElement> {
    val url = "$API_BASE$path?$parameter=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    val snapshot = gate.request("$path:$value") {
      withContext(Dispatchers.IO) {
        val connection = connectionFactory(URI(url).toURL())
        try {
          connection.requestMethod = "GET"
          connection.instanceFollowRedirects = false
          connection.connectTimeout = 25_000
          connection.readTimeout = 25_000
          val status = connection.responseCode
          val retryAfter = retryAfterMillis(connection.getHeaderField("Retry-After"))
          // 认证失败/拒绝/服务终止不能靠重试解决，由调用方直接结束监测。
          if (status in 400..403 || status == 419) throw SourceFailure("公交来源拒绝访问（HTTP $status）", clock() + retryAfter, SourceFailureKind.AUTH)
          if (status == 410) throw SourceFailure("公交来源服务已终止（HTTP $status）", clock() + retryAfter, SourceFailureKind.TERMINATED)
          if (status !in 200..299) throw SourceFailure("公交来源 HTTP $status", clock() + retryAfter)
          val body = connection.inputStream.bufferedReader().use { it.readText() }
          // HTTP 成功不代表业务成功；业务失败和解析失败同样必须计入请求间隔。
          val envelope = Json.parseToJsonElement(body).jsonObject
          val code = envelope["code"]?.jsonPrimitive?.intOrNull
          if (code != 0) {
            val kind = when (code) {
              401, 403, 419 -> SourceFailureKind.AUTH
              410 -> SourceFailureKind.TERMINATED
              else -> SourceFailureKind.TRANSIENT
            }
            throw SourceFailure("公交来源业务失败", clock() + retryAfter, kind)
          }
          check(envelope["data"] != null && envelope["data"] != kotlinx.serialization.json.JsonNull) { "来源数据缺失" }
          HttpPayload(body, retryAfter)
        } finally {
          connection.disconnect()
        }
      }
    }
    return SourceSnapshot(Json.parseToJsonElement(snapshot.value).jsonObject.getValue("data"), snapshot.fetchedAt, snapshot.nextAllowedAt)
  }

  private fun retryAfterMillis(value: String?): Long {
    val seconds = value?.trim()?.toLongOrNull()
    if (seconds != null) return seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
    return runCatching {
      (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock()).coerceAtLeast(0)
    }.getOrDefault(0)
  }
}

data class StationStop(val stopCode: String, val stationName: String)
data class StationChoice(val stationName: String, val defaultStopCode: String, val stops: List<StationStop>, val fetchedAt: Long)

/** 一条线路在本次查询站台的预测；absence 表示来源没有给出该线路的可用车辆。 */
data class LineArrival(val minutes: Int, val stops: Int?, val arriving: Boolean)

/** 同一份站台响应同时给出线路清单与各线路到本次站台的预测，避免两次请求读出不一致。 */
data class StopLineSnapshot(
  val lines: List<SelectedLine>,
  val arrivals: Map<String, LineArrival> = emptyMap(),
) {
  fun arrival(identity: String): LineArrival? = arrivals[identity]
}
