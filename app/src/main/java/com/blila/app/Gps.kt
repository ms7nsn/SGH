package com.blila.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Sat(
    val svid: Int, val type: Int, val cn0: Float,
    val elev: Float, val azim: Float, val used: Boolean,
    val freqMhz: Float, val eph: Boolean, val alm: Boolean
)

fun conName(t: Int): String = when (t) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "Other"
}

fun conLetter(t: Int): String = when (t) {
    GnssStatus.CONSTELLATION_GPS -> "G"
    GnssStatus.CONSTELLATION_SBAS -> "S"
    GnssStatus.CONSTELLATION_GLONASS -> "R"
    GnssStatus.CONSTELLATION_QZSS -> "J"
    GnssStatus.CONSTELLATION_BEIDOU -> "C"
    GnssStatus.CONSTELLATION_GALILEO -> "E"
    GnssStatus.CONSTELLATION_IRNSS -> "I"
    else -> "?"
}

fun bandName(mhz: Float): String = when {
    mhz <= 0f -> ""
    abs(mhz - 1575.42f) < 2f -> "L1 / E1 / B1C"
    abs(mhz - 1176.45f) < 2f -> "L5 / E5a / B2a"
    abs(mhz - 1227.60f) < 2f -> "L2"
    abs(mhz - 1561.098f) < 2f -> "B1I"
    abs(mhz - 1207.14f) < 2f -> "E5b / B2b"
    abs(mhz - 1191.795f) < 2f -> "E5"
    abs(mhz - 1268.52f) < 2f -> "B3I"
    abs(mhz - 1278.75f) < 2f -> "E6"
    mhz in 1597f..1607f -> "G1"
    mhz in 1242f..1252f -> "G2"
    else -> ""
}

fun snrColor(c: Float): Color = when {
    c <= 0f -> Color(0xFF555555)
    c < 15f -> Color(0xFFFF5252)
    c < 25f -> Color(0xFFFFA726)
    c < 35f -> Color(0xFFFFEE58)
    else -> Color(0xFF00E676)
}

fun dms(v: Double, pos: Char, neg: Char): String {
    val h = if (v >= 0) pos else neg
    val a = abs(v)
    val d = a.toInt()
    val mf = (a - d) * 60.0
    val m = mf.toInt()
    val s = (mf - m) * 60.0
    return String.format(Locale.US, "%d°%02d'%05.2f\" %c", d, m, s, h)
}

fun compassName(deg: Float): String {
    val n = if (Lang.fr) listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    else listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return n[(((deg % 360f) + 22.5f) / 45f).toInt() % 8]
}

class GpsHub(ctx: Context) : SensorEventListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val hasBaro: Boolean get() = baro != null
    val hasCompass: Boolean get() = rot != null || mag != null

    val web = WebInfo()

    var location by mutableStateOf<Location?>(null)
    /** Network-based position, only filled when started with withNetwork = true. */
    var coarseLoc by mutableStateOf<Location?>(null)
    var sats by mutableStateOf<List<Sat>>(emptyList())
    var ttff by mutableIntStateOf(-1)
    var gpsEnabled by mutableStateOf(true)
    var pressure by mutableFloatStateOf(0f)
    var seaLevel by mutableFloatStateOf(1013.25f)
    var azimuth by mutableFloatStateOf(-1f)
    var magAccuracy by mutableIntStateOf(-1)
    var magStrength by mutableFloatStateOf(0f)
    var declination by mutableFloatStateOf(0f)
    private var started = false
    private var azSmooth = -1f
    private var smoothP = 0f
    private var lastAz = 0L
    private var lastP = 0L
    private var lastMag = 0L

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            location = l
            declination = GeomagneticField(
                l.latitude.toFloat(), l.longitude.toFloat(),
                l.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        }
        override fun onProviderEnabled(provider: String) { gpsEnabled = true }
        override fun onProviderDisabled(provider: String) { gpsEnabled = false }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { coarseLoc = l }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = ArrayList<Sat>()
            for (i in 0 until status.satelliteCount) {
                val freq = if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i))
                    status.getCarrierFrequencyHz(i) / 1_000_000f else -1f
                list.add(
                    Sat(
                        status.getSvid(i), status.getConstellationType(i), status.getCn0DbHz(i),
                        status.getElevationDegrees(i), status.getAzimuthDegrees(i), status.usedInFix(i),
                        freq, status.hasEphemerisData(i), status.hasAlmanacData(i)
                    )
                )
            }
            sats = list
        }
        override fun onFirstFix(ttffMillis: Int) { ttff = ttffMillis }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(locOk: Boolean, withNetwork: Boolean = false) {
        stop()
        started = true
        baro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (locOk) {
            try {
                gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
                lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { locListener.onLocationChanged(it) }
            } catch (e: Exception) {
                // permission revoked or provider missing
            }
            if (withNetwork) {
                try {
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, netListener)
                        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { coarseLoc = it }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sm.unregisterListener(this)
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
        try { lm.removeUpdates(netListener) } catch (e: Exception) {}
        try { lm.unregisterGnssStatusCallback(gnss) } catch (e: Exception) {}
    }

    fun gpsAltitude(): Double? {
        val l = location ?: return null
        if (!l.hasAltitude()) return null
        return if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else l.altitude
    }

    fun baroAltitude(): Float =
        SensorManager.getAltitude(seaLevel, pressure)

    private fun calibrateToAltitude(alt: Double): Boolean {
        if (pressure <= 0f) return false
        seaLevel = (pressure / (1.0 - alt / 44330.0).pow(5.255)).toFloat()
        return true
    }

    fun calibrateToGps(): Boolean {
        val alt = gpsAltitude() ?: return false
        return calibrateToAltitude(alt)
    }

    fun calibrateToTerrain(): Boolean {
        val alt = web.elevation ?: return false
        return calibrateToAltitude(alt)
    }

    fun calibrateToWeather(): Boolean {
        val p = web.pressureMsl ?: return false
        seaLevel = p.toFloat()
        return true
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {
        if (s.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = a
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                smoothP = if (smoothP == 0f) p else smoothP * 0.85f + p * 0.15f
                val nowMs = SystemClock.uptimeMillis()
                if (pressure == 0f || (nowMs - lastP >= 500 && abs(smoothP - pressure) >= 0.02f)) {
                    pressure = smoothP
                    lastP = nowMs
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                val r2 = FloatArray(9)
                val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                val m = if (abs(r[8]) < 0.5f) {
                    SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
                    r2
                } else r
                SensorManager.getOrientation(m, o)
                var az = Math.toDegrees(o[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                val next = if (azSmooth < 0f) az else {
                    val diff = ((az - azSmooth + 540f) % 360f) - 180f
                    (azSmooth + diff * 0.25f + 360f) % 360f
                }
                azSmooth = next
                val nowMs = SystemClock.uptimeMillis()
                if (azimuth < 0f ||
                    (nowMs - lastAz >= 80 && abs(((next - azimuth + 540f) % 360f) - 180f) >= 1f)
                ) {
                    azimuth = next
                    lastAz = nowMs
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val m = sqrt(
                    e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]
                )
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastMag >= 500 && abs(m - magStrength) >= 0.5f) {
                    magStrength = m
                    lastMag = nowMs
                }
                if (magAccuracy != e.accuracy) magAccuracy = e.accuracy
            }
        }
    }
}

@Composable
fun GpsTab(h: GpsHub, locOk: Boolean, askPerm: () -> Unit) {
    DisposableEffect(locOk) {
        h.start(locOk)
        onDispose { h.stop() }
    }
    var showLocs by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var lastSaved by remember { mutableLongStateOf(0L) }
    var lastLoc by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(h) {
        snapshotFlow { h.location }.collect { here ->
            if (here != null && App.settings.saveOn[1]) {
                val now = System.currentTimeMillis()
                val prev = lastLoc
                val moved = prev == null || here.distanceTo(prev) >= 15f
                if (now - lastSaved >= 30_000L && (moved || now - lastSaved >= 300_000L)) {
                    lastSaved = now
                    lastLoc = here
                    App.store.add(
                        "gps", "loc", here.latitude, here.longitude,
                        if (here.hasAltitude()) here.altitude else null,
                        if (here.hasAccuracy()) here.accuracy.toDouble() else null
                    )
                }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StoreBar(
            1,
            t(
                "Logs your position every 30 s (when you moved) while this tab is open",
                "Mémorise ta position toutes les 30 s (si tu as bougé) tant que cet onglet est ouvert"
            ),
            onChart = { showLocs = true }
        ) {
            OutlinedButton(onClick = { name = ""; askName = true }, enabled = h.location != null) {
                Text(t("Save here", "Enregistrer ici"), maxLines = 1)
            }
        }
        if (!locOk) {
            Panel {
                Text("Location permission is needed for GPS data.", color = Dim)
                Spacer(Modifier.height(8.dp))
                Button(onClick = askPerm) { Text("Grant location permission") }
            }
        }
        FixPanel(h)
        SkyPanel(h)
        PositionPanel(h)
        SpeedPanel(h)
        AltitudePanel(h)
        CompassPanel(h)
        SatListPanel(h)
        WeatherPanel(h)
    }
    if (showLocs) LocationsDialog(onClose = { showLocs = false })
    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text(t("Save this location", "Enregistrer cette position")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(t("Name (optional)", "Nom (facultatif)")) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val l = h.location
                    if (l != null) {
                        App.store.add(
                            "gps", "loc", l.latitude, l.longitude,
                            if (l.hasAltitude()) l.altitude else null,
                            if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                            name.trim().ifEmpty { t("Saved place", "Lieu enregistré") }
                        )
                    }
                    askName = false
                }) { Text(t("Save", "Enregistrer")) }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text(t("Cancel", "Annuler")) } }
        )
    }
}

/** Status line + refresh button for the online data. Loads once when first shown. */
@Composable
fun WebBar(h: GpsHub) {
    val w = h.web
    val l = h.location
    LaunchedEffect(l != null) {
        if (l != null && w.updated == 0L && !w.loading) w.refresh(l.latitude, l.longitude)
    }
    val err = w.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                w.loading -> "Fetching online info..."
                l == null -> "Online info needs a position fix"
                err != null -> err
                w.updated > 0L -> "Online info · ${clock(w.updated)}"
                else -> "Online info"
            },
            color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { if (l != null) w.refresh(l.latitude, l.longitude) },
            enabled = l != null && !w.loading
        ) { Text("Refresh") }
    }
}

@Composable
fun FixPanel(h: GpsHub) {
    val used = h.sats.count { it.used }
    val fix = when {
        !h.gpsEnabled -> "GPS is off"
        used >= 4 -> "3D fix"
        used == 3 -> "2D fix"
        else -> "No fix"
    }
    ExpandPanel("GNSS status", "$fix · $used / ${h.sats.size} satellites") {
        val loc = h.location
        KV("Satellites in view / used", "${h.sats.size} / $used")
        if (h.sats.isNotEmpty()) {
            Stack(
                "Per system (used / in view)",
                h.sats.groupBy { conName(it.type) }
                    .entries.joinToString("   ") { "${it.key} ${it.value.count { s -> s.used }}/${it.value.size}" }
            )
        }
        KV("Time to first fix", if (h.ttff >= 0) "${fmt(h.ttff / 1000.0, 1)} s" else "-")
        if (loc != null) {
            if (loc.hasAccuracy()) KV("Horizontal accuracy", "±${fmt(loc.accuracy, 1)} m")
            if (loc.hasVerticalAccuracy()) KV("Vertical accuracy", "±${fmt(loc.verticalAccuracyMeters, 1)} m")
            if (loc.hasSpeedAccuracy()) KV("Speed accuracy", "±${fmt(loc.speedAccuracyMetersPerSecond, 2)} m/s")
            if (loc.hasBearingAccuracy()) KV("Heading accuracy", "±${fmt(loc.bearingAccuracyDegrees, 0)}°")
            val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000.0
            KV("Fix age", "${fmt(age, 1)} s")
            KV("Provider", loc.provider ?: "gps")
        }
    }
}

@Composable
fun PositionPanel(h: GpsHub) {
    val l = h.location
    val summary = if (l == null) "Waiting for a fix..." else "${fmt(l.latitude, 5)}°, ${fmt(l.longitude, 5)}°"
    ExpandPanel("Position", summary) {
        if (l == null) {
            Text("Waiting for a location fix... go outside or near a window.", color = Dim)
        } else {
            val ctx = LocalContext.current
            val clip = LocalClipboardManager.current
            KV("Latitude", fmt(l.latitude, 6) + "°")
            KV("", dms(l.latitude, 'N', 'S'))
            KV("Longitude", fmt(l.longitude, 6) + "°")
            KV("", dms(l.longitude, 'E', 'W'))
            if (l.hasAccuracy()) KV("Accuracy", "±${fmt(l.accuracy, 1)} m")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clip.setText(AnnotatedString("${fmt(l.latitude, 6)}, ${fmt(l.longitude, 6)}"))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}")
                            )
                        )
                    } catch (e: Exception) {
                    }
                }) { Text("Open in maps") }
            }
            SubTitle("Online")
            WebBar(h)
            val w = h.web
            w.address?.let { Stack("Address", it) }
            val place = listOfNotNull(w.city, w.country).joinToString(", ")
            if (place.isNotEmpty()) KV("Place", place)
            w.timezone?.let { KV("Time zone", it) }
        }
    }
}

@Composable
fun SpeedPanel(h: GpsHub) {
    val l = h.location
    val kmh = (l?.speed ?: 0f) * 3.6f
    val headTxt = if (l != null && l.hasBearing()) "${fmt(l.bearing, 0)}° ${compassName(l.bearing)}" else "-"
    val summary = if (l != null && l.hasSpeed()) "${fmt(kmh, 1)} km/h · $headTxt" else "- km/h · $headTxt"
    ExpandPanel("Speed & heading", summary) {
        if (l == null) {
            Text("Waiting for a location fix...", color = Dim)
        } else {
            if (l.hasSpeed()) {
                KV("Speed", "${fmt(kmh, 1)} km/h", big = true)
                KV("", "${fmt(l.speed.toDouble(), 2)} m/s · ${fmt(kmh * 0.621371, 1)} mph · ${fmt(l.speed * 1.943844, 1)} kn")
            } else KV("Speed", "-")
            KV(
                "Heading (GPS)",
                if (l.hasBearing()) "${fmt(l.bearing, 0)}° ${compassName(l.bearing)}" else "- (move to get a heading)",
                big = true
            )
            if (l.hasBearingAccuracy()) KV("Heading accuracy", "±${fmt(l.bearingAccuracyDegrees, 0)}°")
            val w = h.web
            if (w.windKmh != null) {
                SubTitle("Wind here (online)")
                KV("Wind", "${fmt(w.windKmh ?: 0.0, 0)} km/h from ${w.windDir?.let { "${fmt(it, 0)}° ${compassName(it.toFloat())}" } ?: "-"}")
            }
        }
    }
}

@Composable
fun AltitudePanel(h: GpsHub) {
    val alt = h.gpsAltitude()
    val baroAlt = if (h.hasBaro && h.pressure > 0f) h.baroAltitude() else null
    val altTxt = when {
        alt != null -> "${fmt(alt, 0)} m"
        baroAlt != null -> "${fmt(baroAlt, 0)} m (baro)"
        else -> "-"
    }
    val pTxt = if (h.pressure > 0f) " · ${fmt(h.pressure, 1)} hPa" else ""
    ExpandPanel("Altitude & pressure", altTxt + pTxt) {
        val l = h.location
        if (alt != null) KV("Altitude (GPS)", "${fmt(alt, 1)} m", big = true)
        if (l != null && l.hasAltitude() && Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) {
            KV("Ellipsoid altitude (WGS84)", "${fmt(l.altitude, 1)} m")
        }
        if (!h.hasBaro) {
            Text("No barometer on this device.", color = Dim)
        } else if (h.pressure <= 0f) {
            Text("Barometer: waiting for data...", color = Dim)
        } else {
            val p = h.pressure
            KV("Altitude (barometric)", "${fmt(h.baroAltitude(), 1)} m", big = true)
            KV("Pressure", "${fmt(p, 2)} hPa", big = true)
            KV("", "${fmt(p * 0.750062f, 1)} mmHg · ${fmt(p * 0.02953f, 2)} inHg")
            KV("Sea-level reference", "${fmt(h.seaLevel, 2)} hPa")
        }
        SubTitle("Online")
        WebBar(h)
        val w = h.web
        w.elevation?.let { KV("Terrain elevation", "${fmt(it, 0)} m") }
        w.pressureMsl?.let { KV("Sea-level pressure (weather)", "${fmt(it, 1)} hPa") }
        if (h.hasBaro && h.pressure > 0f) {
            SubTitle("Calibrate barometer")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { h.calibrateToGps() },
                    enabled = alt != null,
                    modifier = Modifier.weight(1f)
                ) { Text("To GPS", maxLines = 1) }
                OutlinedButton(
                    onClick = { h.calibrateToTerrain() },
                    enabled = w.elevation != null,
                    modifier = Modifier.weight(1f)
                ) { Text("To terrain", maxLines = 1) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { h.calibrateToWeather() },
                    enabled = w.pressureMsl != null,
                    modifier = Modifier.weight(1f)
                ) { Text("To weather", maxLines = 1) }
                OutlinedButton(
                    onClick = { h.seaLevel = 1013.25f },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset", maxLines = 1) }
            }
        }
    }
}

@Composable
fun CompassPanel(h: GpsHub) {
    val az = if (h.azimuth < 0f) 0f else h.azimuth
    val summary = if (!h.hasCompass) "No sensor" else "${fmt(az, 0)}° ${compassName(az)}"
    ExpandPanel("Compass", summary) {
        if (!h.hasCompass) {
            Text("No compass sensor on this device.", color = Dim)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CompassDial(az, Modifier.size(210.dp))
            }
            Spacer(Modifier.height(6.dp))
            val trueH = (az + h.declination + 360f) % 360f
            KV("Magnetic heading", "${fmt(az, 0)}° ${compassName(az)}", big = true)
            KV("True heading", "${fmt(trueH, 0)}° ${compassName(trueH)}")
            KV("Declination", "${fmt(h.declination, 1)}°")
            val (calText, calFrac) = when (h.magAccuracy) {
                3 -> Pair("High (3/3)", 1f)
                2 -> Pair("Medium (2/3)", 0.66f)
                1 -> Pair("Low (1/3)", 0.33f)
                0 -> Pair("Unreliable (0/3)", 0.05f)
                else -> Pair("-", 0f)
            }
            KV("Calibration", calText, big = true)
            LinearProgressIndicator(progress = { calFrac }, modifier = Modifier.fillMaxWidth())
            if (h.magAccuracy in 0..2) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Wave the phone in a figure-8 motion to calibrate the compass.",
                    color = Dim, style = MaterialTheme.typography.bodySmall
                )
            }
            KV("Magnetic field", if (h.magStrength > 0f) "${fmt(h.magStrength, 1)} µT" else "-")
        }
    }
}

@Composable
fun CompassDial(az: Float, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 8f
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 34f
            color = OnSurf.toArgb()
        }
        rotate(-az, c) {
            drawCircle(PanelBorder, r, c, style = Stroke(3f))
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0)
                val len = if (i % 9 == 0) 22f else if (i % 3 == 0) 14f else 8f
                val s = Offset(c.x + (r - len) * sin(a).toFloat(), c.y - (r - len) * cos(a).toFloat())
                val e = Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
                drawLine(Dim, s, e, 2f)
            }
            val labels = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, (if (Lang.fr) "O" else "W") to 270.0)
            for ((t, deg) in labels) {
                val a = Math.toRadians(deg)
                val rr = r - 48f
                val x = c.x + rr * sin(a).toFloat()
                val y = c.y - rr * cos(a).toFloat() + 12f
                paint.color = if (t == "N") android.graphics.Color.rgb(255, 82, 82) else OnSurf.toArgb()
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(deg.toFloat(), x, y - 12f)
                drawContext.canvas.nativeCanvas.drawText(t, x, y, paint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
        // fixed pointer
        drawLine(Accent, Offset(c.x, c.y + 12f), Offset(c.x, c.y - r + 4f), 6f)
        drawCircle(Accent, 8f, c)
    }
}

/** Sky view that turns with the compass: the top of the circle is where the phone points. */
@Composable
fun SkyPanel(h: GpsHub) {
    Panel {
        Text("Satellite finder", color = Accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (h.hasCompass) "Turns with the compass: a satellite at the top is straight ahead of the phone."
            else "No compass sensor: north is at the top.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val az = if (h.azimuth < 0f) 0f else h.azimuth
            val c = Offset(size.width / 2f, size.height / 2f)
            val rad = size.minDimension / 2f - 34f
            val grid = PanelBorder
            fun pt(azimDeg: Float, r: Float): Offset {
                val a = Math.toRadians((azimDeg - az).toDouble())
                return Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
            }
            drawCircle(grid, rad, c, style = Stroke(2f))
            drawCircle(grid, rad * 2f / 3f, c, style = Stroke(1f))
            drawCircle(grid, rad / 3f, c, style = Stroke(1f))
            for (d in 0 until 360 step 30) {
                drawLine(grid, pt(d.toFloat(), rad - 10f), pt(d.toFloat(), rad), 2f)
            }
            drawLine(grid, pt(0f, rad), pt(180f, rad), 1f)
            drawLine(grid, pt(90f, rad), pt(270f, rad), 1f)

            val txt = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 28f
                color = Dim.toArgb()
            }
            val cv = drawContext.canvas.nativeCanvas
            for ((t, deg) in listOf("N" to 0f, "E" to 90f, "S" to 180f, (if (Lang.fr) "O" else "W") to 270f)) {
                val p = pt(deg, rad + 20f)
                txt.color = if (t == "N") android.graphics.Color.rgb(255, 82, 82) else Dim.toArgb()
                cv.drawText(t, p.x, p.y + 10f, txt)
            }

            val lab = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 22f
                color = OnSurf.toArgb()
            }
            for (s in h.sats) {
                val rr = rad * (90f - s.elev.coerceIn(0f, 90f)) / 90f
                val p = pt(s.azim, rr)
                val col = snrColor(s.cn0)
                if (s.used) drawCircle(col, 15f, p) else drawCircle(col, 15f, p, style = Stroke(3f))
                cv.drawText("${conLetter(s.type)}${s.svid}", p.x, p.y - 22f, lab)
            }

            // fixed heading arrow: where the phone is pointing
            val tip = Offset(c.x, c.y - rad - 4f)
            drawLine(Accent, c, tip, 3f)
            drawLine(Accent, tip, Offset(tip.x - 12f, tip.y + 20f), 4f)
            drawLine(Accent, tip, Offset(tip.x + 12f, tip.y + 20f), 4f)
            drawCircle(Accent, 6f, c)
        }
        Spacer(Modifier.height(4.dp))
        HeadingLine(h)
    }
}

@Composable
fun HeadingLine(h: GpsHub) {
    val az = if (h.azimuth < 0f) 0f else h.azimuth
    Text(
        "Heading ${fmt(az, 0)}° ${compassName(az)} · filled = used in the fix · outline = in view · colour = signal",
        color = Dim, style = MaterialTheme.typography.bodySmall
    )
}

@Composable
fun SatListPanel(h: GpsHub) {
    val list = h.sats.sortedWith(compareBy({ it.type }, { it.svid }))
    val used = list.count { it.used }
    ExpandPanel("Satellites", "${list.size} in view · $used used") {
        if (list.isEmpty()) {
            Text("No satellites yet.", color = Dim)
        } else {
            Text("Signal strength (C/N0 dB-Hz)", color = Dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Bottom) {
                for (s in list) {
                    Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (s.cn0 > 0f) "${s.cn0.toInt()}" else "-",
                            fontSize = 11.sp, color = OnSurf, textAlign = TextAlign.Center
                        )
                        Box(
                            Modifier.width(20.dp)
                                .height((4f + s.cn0.coerceIn(0f, 55f) / 55f * 110f).dp)
                                .background(snrColor(s.cn0))
                        )
                        Text(
                            "${conLetter(s.type)}${s.svid}",
                            fontSize = 11.sp,
                            fontWeight = if (s.used) FontWeight.Bold else FontWeight.Normal,
                            color = if (s.used) Accent else Dim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Cyan label = used in the fix. Above 40 is very good. Tap a satellite for details.",
                color = Dim, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                for ((t, w) in listOf("ID" to 1f, "System" to 1.4f, "SNR" to 1f, "Elev" to 1f, "Azim" to 1f, "Fix" to 0.8f)) {
                    Text(t, Modifier.weight(w), color = Dim, fontSize = 12.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = PanelBorder)
            for (s in list) SatRow(s)
        }
    }
}

@Composable
fun SatRow(s: Sat) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("${conLetter(s.type)}${s.svid}", Modifier.weight(1f), fontSize = 13.sp)
            Text(conName(s.type), Modifier.weight(1.4f), fontSize = 13.sp)
            Text(fmt(s.cn0, 0), Modifier.weight(1f), fontSize = 13.sp, color = snrColor(s.cn0))
            Text("${s.elev.toInt()}°", Modifier.weight(1f), fontSize = 13.sp)
            Text("${s.azim.toInt()}°", Modifier.weight(1f), fontSize = 13.sp)
            Text(
                if (s.used) "yes" else "no", Modifier.weight(0.8f), fontSize = 13.sp,
                color = if (s.used) Accent else Dim
            )
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                KV("System", "${conName(s.type)} (PRN ${s.svid})")
                KV("Carrier frequency", if (s.freqMhz > 0f) "${fmt(s.freqMhz, 2)} MHz ${bandName(s.freqMhz)}" else "-")
                KV("Signal (C/N0)", "${fmt(s.cn0, 1)} dB-Hz")
                KV("Elevation / azimuth", "${fmt(s.elev, 1)}° / ${fmt(s.azim, 1)}° ${compassName(s.azim)}")
                KV("Used in fix", if (s.used) "yes" else "no")
                KV("Ephemeris / almanac", "${if (s.eph) "yes" else "no"} / ${if (s.alm) "yes" else "no"}")
            }
        }
    }
    HorizontalDivider(color = PanelBorder)
}

@Composable
fun WeatherPanel(h: GpsHub) {
    val w = h.web
    val summary = when {
        w.updated == 0L -> "Tap to load"
        else -> listOfNotNull(
            w.temp?.let { "${fmt(it, 0)} °C" },
            w.city
        ).joinToString(" · ").ifEmpty { "Loaded" }
    }
    ExpandPanel("Place & weather (online)", summary) {
        WebBar(h)
        w.address?.let { Stack("Address", it) }
        w.temp?.let { KV("Temperature", "${fmt(it, 1)} °C", big = true) }
        w.feels?.let { KV("Feels like", "${fmt(it, 1)} °C") }
        w.humidity?.let { KV("Humidity", "${fmt(it, 0)} %") }
        w.pressureMsl?.let { KV("Pressure (sea level)", "${fmt(it, 1)} hPa") }
        w.windKmh?.let {
            KV("Wind", "${fmt(it, 0)} km/h" + (w.windDir?.let { d -> " from ${fmt(d, 0)}° ${compassName(d.toFloat())}" } ?: ""))
        }
        w.cloud?.let { KV("Cloud cover", "${fmt(it, 0)} %") }
        w.sunrise?.let { KV("Sunrise", it) }
        w.sunset?.let { KV("Sunset", it) }
        w.elevation?.let { KV("Terrain elevation", "${fmt(it, 0)} m") }
        w.timezone?.let { KV("Time zone", it) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Your coordinates are sent to OpenStreetMap Nominatim and Open-Meteo to get this data.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
    }
}
