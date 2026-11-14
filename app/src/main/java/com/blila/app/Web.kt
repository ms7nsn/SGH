package com.blila.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HttpError(val code: Int, val body: String) : IOException("HTTP $code")

object Http {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "SGH-Android/1.1")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "en")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, body)
            return body
        } finally {
            c.disconnect()
        }
    }
}

private fun num(o: JSONObject?, k: String): Double? =
    if (o != null && o.has(k) && !o.isNull(k)) o.optDouble(k) else null

private fun str(o: JSONObject?, k: String): String? {
    if (o == null || !o.has(k) || o.isNull(k)) return null
    val s = o.optString(k)
    return if (s.isEmpty()) null else s
}

/** Online data for the current position (address, terrain elevation, weather). */
class WebInfo {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var updated by mutableLongStateOf(0L)

    var address by mutableStateOf<String?>(null)
    var city by mutableStateOf<String?>(null)
    var country by mutableStateOf<String?>(null)
    var elevation by mutableStateOf<Double?>(null)
    var temp by mutableStateOf<Double?>(null)
    var feels by mutableStateOf<Double?>(null)
    var humidity by mutableStateOf<Double?>(null)
    var pressureMsl by mutableStateOf<Double?>(null)
    var windKmh by mutableStateOf<Double?>(null)
    var windDir by mutableStateOf<Double?>(null)
    var cloud by mutableStateOf<Double?>(null)
    var sunrise by mutableStateOf<String?>(null)
    var sunset by mutableStateOf<String?>(null)
    var timezone by mutableStateOf<String?>(null)

    fun refresh(lat: Double, lon: Double) {
        if (loading) return
        loading = true
        error = null
        val la = String.format(Locale.US, "%.5f", lat)
        val lo = String.format(Locale.US, "%.5f", lon)
        Thread {
            var failed = 0
            var addr: String? = null
            var cityV: String? = null
            var countryV: String? = null
            var elev: Double? = null
            var t: Double? = null
            var fl: Double? = null
            var hum: Double? = null
            var pmsl: Double? = null
            var wk: Double? = null
            var wd: Double? = null
            var cl: Double? = null
            var sr: String? = null
            var ss: String? = null
            var tz: String? = null

            try {
                val j = JSONObject(
                    Http.get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=$la&lon=$lo")
                )
                addr = str(j, "display_name")
                val a = j.optJSONObject("address")
                if (a != null) {
                    cityV = listOf("city", "town", "village", "municipality", "county")
                        .firstNotNullOfOrNull { k -> str(a, k) }
                    countryV = str(a, "country")
                }
            } catch (e: Exception) {
                failed++
            }

            try {
                val j = JSONObject(Http.get("https://api.open-meteo.com/v1/elevation?latitude=$la&longitude=$lo"))
                val arr = j.optJSONArray("elevation")
                if (arr != null && arr.length() > 0) elev = arr.optDouble(0)
            } catch (e: Exception) {
                failed++
            }

            try {
                val j = JSONObject(
                    Http.get(
                        "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,pressure_msl," +
                            "wind_speed_10m,wind_direction_10m,cloud_cover" +
                            "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
                    )
                )
                val cur = j.optJSONObject("current")
                t = num(cur, "temperature_2m")
                fl = num(cur, "apparent_temperature")
                hum = num(cur, "relative_humidity_2m")
                pmsl = num(cur, "pressure_msl")
                wk = num(cur, "wind_speed_10m")
                wd = num(cur, "wind_direction_10m")
                cl = num(cur, "cloud_cover")
                val daily = j.optJSONObject("daily")
                sr = daily?.optJSONArray("sunrise")?.optString(0)?.substringAfter("T")
                ss = daily?.optJSONArray("sunset")?.optString(0)?.substringAfter("T")
                tz = str(j, "timezone")
            } catch (e: Exception) {
                failed++
            }

            Handler(Looper.getMainLooper()).post {
                if (failed < 3) {
                    address = addr; city = cityV; country = countryV
                    elevation = elev
                    temp = t; feels = fl; humidity = hum; pressureMsl = pmsl
                    windKmh = wk; windDir = wd; cloud = cl
                    sunrise = sr; sunset = ss; timezone = tz
                    updated = System.currentTimeMillis()
                }
                error = when (failed) {
                    0 -> null
                    3 -> "No internet connection or services unavailable"
                    else -> "Some online data could not be loaded"
                }
                loading = false
            }
        }.start()
    }
}

class CellPos(val lat: Double, val lon: Double, val range: Int, val samples: Int)

/** Tower lookup through OpenCelliD (needs a free API key). */
object OpenCellId {
    fun locate(key: String, radio: String, mcc: String, mnc: String, area: Int, cid: Long): CellPos? {
        val mccI = mcc.toIntOrNull() ?: return null
        val mncI = mnc.toIntOrNull() ?: return null
        val url = "https://opencellid.org/cell/get?key=$key&mcc=$mccI&mnc=$mncI" +
            "&lac=$area&cellid=$cid&radio=$radio&format=json"
        val body = try {
            Http.get(url)
        } catch (e: HttpError) {
            if (e.code == 404) return null
            if (e.code == 401 || e.code == 403) throw IOException("API key rejected")
            throw e
        }
        val j = JSONObject(body)
        if (j.has("lat") && j.has("lon")) {
            return CellPos(
                j.optDouble("lat"), j.optDouble("lon"),
                j.optInt("range", 0), j.optInt("samples", 0)
            )
        }
        val msg = j.optString("error", "")
        if (msg.contains("key", true) || msg.contains("token", true)) throw IOException("API key rejected")
        return null
    }
}
