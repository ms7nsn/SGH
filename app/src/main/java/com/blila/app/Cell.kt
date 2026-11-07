package com.blila.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

const val NA = Int.MAX_VALUE

class Tower(
    val radio: String,
    val used: Boolean,
    val mcc: String?,
    val mnc: String?,
    val area: Int,
    val cid: Long,
    val pci: Int,
    val arfcn: Int,
    val band: String?,
    val dbm: Int,
    val level: Int,
    val ageMs: Long,
    val extras: List<Pair<String, String>>,
    val taMeters: Int
) {
    val key: String get() = "$radio-$cid-$pci-$arfcn-$area"
    val areaLabel: String get() = if (radio == "LTE" || radio == "NR") "TAC" else "LAC"
    val shortId: String
        get() = when {
            cid > 0 -> "$cid"
            pci != NA -> "PCI $pci"
            else -> "?"
        }
    val locatable: Boolean
        get() = mcc != null && mnc != null && area != NA && area > 0 && cid > 0
}

fun dbmColor(dbm: Int): Color = when {
    dbm == NA -> Color(0xFF555555)
    dbm >= -85 -> Color(0xFF00E676)
    dbm >= -100 -> Color(0xFFFFEE58)
    dbm >= -110 -> Color(0xFFFFA726)
    else -> Color(0xFFFF5252)
}

private fun ok(v: Int) = v != NA

@Suppress("DEPRECATION")
private fun parseCell(ci: CellInfo): Tower? {
    val reg = ci.isRegistered
    val age = ((SystemClock.elapsedRealtimeNanos() - ci.timeStamp) / 1_000_000L).coerceAtLeast(0L)

    if (Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr) {
        val id = ci.cellIdentity as CellIdentityNr
        val ss = ci.cellSignalStrength as CellSignalStrengthNr
        val extras = ArrayList<Pair<String, String>>()
        if (ok(ss.ssRsrp)) extras.add("SS-RSRP" to "${ss.ssRsrp} dBm")
        if (ok(ss.ssRsrq)) extras.add("SS-RSRQ" to "${ss.ssRsrq} dB")
        if (ok(ss.ssSinr)) extras.add("SS-SINR" to "${ss.ssSinr} dB")
        val band = if (Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty())
            id.bands.joinToString(", ") { "n$it" } else null
        return Tower(
            "NR", reg, id.mccString, id.mncString, id.tac,
            if (id.nci != Long.MAX_VALUE) id.nci else -1L,
            id.pci, id.nrarfcn, band, ss.dbm, ss.level, age, extras, -1
        )
    }
    if (ci is CellInfoLte) {
        val id = ci.cellIdentity
        val ss = ci.cellSignalStrength
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else if (ok(id.mcc)) id.mcc.toString() else null
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else if (ok(id.mnc)) id.mnc.toString() else null
        val extras = ArrayList<Pair<String, String>>()
        if (ok(ss.rsrp)) extras.add("RSRP" to "${ss.rsrp} dBm")
        if (ok(ss.rsrq)) extras.add("RSRQ" to "${ss.rsrq} dB")
        if (ok(ss.rssnr)) extras.add("SINR" to "${ss.rssnr} dB")
        if (ok(ss.cqi)) extras.add("CQI" to "${ss.cqi}")
        if (Build.VERSION.SDK_INT >= 28 && ok(id.bandwidth)) extras.add("Bandwidth" to "${id.bandwidth / 1000} MHz")
        val band = if (Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty())
            id.bands.joinToString(", ") { "B$it" } else null
        val ta = ss.timingAdvance
        val taM = if (ok(ta) && ta >= 0) (ta * 78.12).toInt() else -1
        return Tower(
            "LTE", reg, mcc, mnc, id.tac, if (ok(id.ci)) id.ci.toLong() else -1L,
            id.pci, id.earfcn, band, ss.dbm, ss.level, age, extras, taM
        )
    }
    if (ci is CellInfoGsm) {
        val id = ci.cellIdentity
        val ss = ci.cellSignalStrength
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else if (ok(id.mcc)) id.mcc.toString() else null
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else if (ok(id.mnc)) id.mnc.toString() else null
        val extras = ArrayList<Pair<String, String>>()
        if (ok(id.bsic)) extras.add("BSIC" to "${id.bsic}")
        val ta = ss.timingAdvance
        val taM = if (ok(ta) && ta >= 0) (ta * 553.5).toInt() else -1
        return Tower(
            "GSM", reg, mcc, mnc, id.lac, if (ok(id.cid)) id.cid.toLong() else -1L,
            id.bsic, id.arfcn, null, ss.dbm, ss.level, age, extras, taM
        )
    }
    if (ci is CellInfoWcdma) {
        val id = ci.cellIdentity
        val ss = ci.cellSignalStrength
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else if (ok(id.mcc)) id.mcc.toString() else null
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else if (ok(id.mnc)) id.mnc.toString() else null
        return Tower(
            "WCDMA", reg, mcc, mnc, id.lac, if (ok(id.cid)) id.cid.toLong() else -1L,
            id.psc, id.uarfcn, null, ss.dbm, ss.level, age, emptyList(), -1
        )
    }
    if (Build.VERSION.SDK_INT >= 29 && ci is CellInfoTdscdma) {
        val id = ci.cellIdentity
        val ss = ci.cellSignalStrength
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else null
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else null
        return Tower(
            "TD-SCDMA", reg, mcc, mnc, id.lac, if (ok(id.cid)) id.cid.toLong() else -1L,
            id.cpid, id.uarfcn, null, ss.dbm, ss.level, age, emptyList(), -1
        )
    }
    if (ci is CellInfoCdma) {
        val id = ci.cellIdentity
        val ss = ci.cellSignalStrength
        val extras = ArrayList<Pair<String, String>>()
        if (ok(id.systemId)) extras.add("System ID" to "${id.systemId}")
        return Tower(
            "CDMA", reg, null, null, id.networkId,
            if (ok(id.basestationId)) id.basestationId.toLong() else -1L,
            NA, NA, null, ss.dbm, ss.level, age, extras, -1
        )
    }
    return null
}

private fun openRadio(r: String): String = when (r) {
    "WCDMA", "TD-SCDMA" -> "UMTS"
    else -> r
}

private fun netTypeName(t: Int): String = when (t) {
    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
    20 -> "5G NR"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
    TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
    TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
    TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
    16 -> "2G GSM"
    17 -> "3G TD-SCDMA"
    18 -> "IWLAN"
    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "–"
    else -> "Other ($t)"
}

class CellHub(private val ctx: Context) {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val prefs = ctx.getSharedPreferences("blila", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var towers by mutableStateOf<List<Tower>>(emptyList())
    var operatorName by mutableStateOf("")
    var plmn by mutableStateOf("")
    var netType by mutableStateOf("–")
    var roaming by mutableStateOf(false)
    var updated by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var apiKey by mutableStateOf(prefs.getString("ocid_key", "") ?: "")

    val pos = mutableStateMapOf<String, CellPos>()
    val status = mutableStateMapOf<String, String>()
    private val retryAt = HashMap<String, Long>()

    private var running = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 3000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        loop.run()
    }

    fun stop() {
        running = false
        main.removeCallbacks(loop)
    }

    fun setKey(k: String) {
        val v = k.trim()
        apiKey = v
        prefs.edit().putString("ocid_key", v).apply()
        pos.clear()
        status.clear()
        retryAt.clear()
        towers.filter { it.locatable }.take(10).forEach { locate(it) }
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            error = "Location permission is needed to read cell towers."
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(ctx),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            onCells(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            try { onCells(tm.allCellInfo ?: emptyList()) } catch (e: SecurityException) {}
                        }
                    }
                )
            } else {
                onCells(tm.allCellInfo ?: emptyList())
            }
        } catch (e: SecurityException) {
            error = "Permission denied for cell info."
        } catch (e: Exception) {
            error = e.message ?: "Cell info error"
        }
    }

    private fun onCells(list: List<CellInfo>) {
        val t = list.mapNotNull { ci -> try { parseCell(ci) } catch (e: Exception) { null } }
            .sortedWith(
                compareByDescending<Tower> { it.used }
                    .thenByDescending { if (it.dbm == NA) -999 else it.dbm }
            )
        towers = t
        updated = System.currentTimeMillis()
        error = if (t.isEmpty()) "No cell info (no SIM, airplane mode or location off)." else null
        readNetwork()
        if (apiKey.isNotBlank()) t.filter { it.locatable }.take(10).forEach { locate(it) }
    }

    @SuppressLint("MissingPermission")
    private fun readNetwork() {
        try {
            operatorName = tm.networkOperatorName ?: ""
            plmn = tm.networkOperator ?: ""
            roaming = tm.isNetworkRoaming
            netType = try { netTypeName(tm.dataNetworkType) } catch (e: SecurityException) { "–" }
        } catch (e: Exception) {
        }
    }

    fun locate(t: Tower) {
        val k = t.key
        if (!t.locatable || apiKey.isBlank()) return
        if (pos.containsKey(k)) return
        val st = status[k]
        if (st == "loading" || st == "notfound") return
        val now = SystemClock.elapsedRealtime()
        if (now < (retryAt[k] ?: 0L)) return
        status[k] = "loading"
        retryAt[k] = now + 60_000L
        val key = apiKey
        Thread {
            val res = runCatching {
                OpenCellId.locate(key, openRadio(t.radio), t.mcc ?: "", t.mnc ?: "", t.area, t.cid)
            }
            main.post {
                res.onSuccess { p ->
                    if (p != null) {
                        pos[k] = p
                        status[k] = "ok"
                    } else status[k] = "notfound"
                }.onFailure { e ->
                    status[k] = "error: ${e.message ?: "network"}"
                }
            }
        }.start()
    }
}

private fun distBearing(from: Location, p: CellPos): Pair<Float, Float> {
    val r = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, p.lat, p.lon, r)
    return Pair(r[0], (r[1] + 360f) % 360f)
}

private fun distText(m: Float): String =
    if (m < 1000f) "${fmt(m, 0)} m" else "${fmt(m / 1000f, 2)} km"

@Composable
fun CellTab(c: CellHub, g: GpsHub, locOk: Boolean, askPerm: () -> Unit) {
    DisposableEffect(locOk) {
        g.start(locOk, withNetwork = true)
        c.start()
        onDispose {
            g.stop()
            c.stop()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!locOk) {
            Panel {
                Text("Location permission is needed to read cell towers.", color = Dim)
                Spacer(Modifier.height(8.dp))
                Button(onClick = askPerm) { Text("Grant location permission") }
            }
        }
        ServingPanel(c, g)
        RadarPanel(c, g)
        NearbyPanel(c, g)
        TowerKeyPanel(c)
    }
}

@Composable
fun ServingPanel(c: CellHub, g: GpsHub) {
    val used = c.towers.filter { it.used }
    val summary = if (used.isEmpty()) {
        c.error ?: "No serving cell"
    } else {
        val t = used[0]
        "${t.radio} · ${if (t.dbm != NA) "${t.dbm} dBm" else "–"}"
    }
    ExpandPanel("Serving cell (used)", summary) {
        KV("Operator", c.operatorName.ifEmpty { "–" })
        KV("MCC-MNC", c.plmn.ifEmpty { "–" })
        KV("Network", c.netType)
        KV("Roaming", if (c.roaming) "yes" else "no")
        if (used.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(c.error ?: "No serving cell reported.", color = Dim)
        }
        for (t in used) {
            SubTitle("${t.radio} · ${t.shortId}")
            TowerDetails(t, c, g)
        }
    }
}

@Composable
fun TowerDetails(t: Tower, c: CellHub, g: GpsHub) {
    val ctx = LocalContext.current
    KV("Technology", t.radio)
    KV("Cell ID", if (t.cid > 0) "${t.cid}" else "–")
    KV(t.areaLabel, if (t.area != NA && t.area >= 0) "${t.area}" else "–")
    if (t.pci != NA) KV(if (t.radio == "GSM") "BSIC" else if (t.radio == "WCDMA") "PSC" else "PCI", "${t.pci}")
    if (t.arfcn != NA) KV("Channel (ARFCN)", "${t.arfcn}")
    t.band?.let { KV("Band", it) }
    if (t.mcc != null && t.mnc != null) KV("MCC-MNC", "${t.mcc}-${t.mnc}")
    KV("Signal", if (t.dbm != NA) "${t.dbm} dBm (level ${t.level}/4)" else "–")
    for ((k, v) in t.extras) KV(k, v)
    if (t.taMeters >= 0) KV("Timing advance", "≈ ${t.taMeters} m from the tower")
    KV("Age of reading", "${fmt(t.ageMs / 1000.0, 1)} s")

    SubTitle("Location (online)")
    val st = c.status[t.key]
    val p = c.pos[t.key]
    val me = g.location ?: g.coarseLoc
    when {
        !t.locatable -> Text("This cell has no complete identity, so it cannot be looked up.", color = Dim, style = MaterialTheme.typography.bodySmall)
        c.apiKey.isBlank() -> Text("Add an OpenCelliD API key (Tower location card) to locate this tower.", color = Dim, style = MaterialTheme.typography.bodySmall)
        p != null -> {
            KV("Tower position", "${fmt(p.lat, 5)}°, ${fmt(p.lon, 5)}°")
            if (p.range > 0) KV("Coverage radius", "≈ ${p.range} m")
            if (p.samples > 0) KV("Database samples", "${p.samples}")
            if (me != null) {
                val (d, b) = distBearing(me, p)
                KV("Distance from you", distText(d), big = true)
                KV("Direction", "${fmt(b, 0)}° ${compassName(b)}")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}"))
                    )
                } catch (e: Exception) {
                }
            }) { Text("Open in maps") }
        }
        st == "loading" -> Text("Looking up…", color = Dim)
        st == "notfound" -> Text("Not in the OpenCelliD database.", color = Dim)
        st != null && st.startsWith("error") -> Text(st, color = Dim, style = MaterialTheme.typography.bodySmall)
        else -> Text("Waiting for lookup…", color = Dim)
    }
}

@Composable
fun RadarPanel(c: CellHub, g: GpsHub) {
    Panel {
        Text("Tower radar", color = Accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turns with the compass: a tower at the top is straight ahead. Only towers found in the online database are shown.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        val me = g.location ?: g.coarseLoc
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val az = if (g.azimuth < 0f) 0f else g.azimuth
            val ctr = Offset(size.width / 2f, size.height / 2f)
            val rad = size.minDimension / 2f - 34f
            val grid = Color(0xFF2A2A2A)
            drawCircle(grid, rad, ctr, style = Stroke(2f))
            drawCircle(grid, rad * 2f / 3f, ctr, style = Stroke(1f))
            drawCircle(grid, rad / 3f, ctr, style = Stroke(1f))

            fun pt(bearing: Float, r: Float): Offset {
                val a = Math.toRadians((bearing - az).toDouble())
                return Offset(ctr.x + r * sin(a).toFloat(), ctr.y - r * cos(a).toFloat())
            }

            val txt = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 28f
                color = android.graphics.Color.GRAY
            }
            val cv = drawContext.canvas.nativeCanvas
            for ((t, deg) in listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)) {
                val p = pt(deg, rad + 20f)
                txt.color = if (t == "N") android.graphics.Color.rgb(255, 82, 82) else android.graphics.Color.GRAY
                cv.drawText(t, p.x, p.y + 10f, txt)
            }

            if (me != null) {
                val shown = c.towers.mapNotNull { t -> c.pos[t.key]?.let { Triple(t, it, distBearing(me, it)) } }
                val farthest = shown.maxOfOrNull { it.third.first } ?: 0f
                val maxD = max(1000f, farthest * 1.15f)
                val lab = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 22f
                    color = android.graphics.Color.WHITE
                }
                for ((t, _, db) in shown) {
                    val p = pt(db.second, rad * (db.first / maxD).coerceAtMost(1f))
                    val col = dbmColor(t.dbm)
                    if (t.used) drawCircle(col, 15f, p) else drawCircle(col, 15f, p, style = Stroke(3f))
                    cv.drawText(t.shortId, p.x, p.y - 22f, lab)
                }
                val scale = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.LEFT
                    textSize = 22f
                    color = android.graphics.Color.GRAY
                }
                cv.drawText("outer ring = ${distText(maxD)}", 8f, size.height - 8f, scale)
            }

            val tip = Offset(ctr.x, ctr.y - rad - 4f)
            drawLine(Accent, ctr, tip, 3f)
            drawLine(Accent, tip, Offset(tip.x - 12f, tip.y + 20f), 4f)
            drawLine(Accent, tip, Offset(tip.x + 12f, tip.y + 20f), 4f)
            drawCircle(Accent, 6f, ctr)
        }
        Spacer(Modifier.height(4.dp))
        val located = c.towers.count { c.pos.containsKey(it.key) }
        Text(
            when {
                me == null -> "Waiting for your position…"
                c.apiKey.isBlank() -> "Add an OpenCelliD key below to place towers on the radar."
                located == 0 -> "No tower located yet."
                else -> "$located tower(s) located · filled = used · outline = neighbour · colour = signal"
            },
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun NearbyPanel(c: CellHub, g: GpsHub) {
    val n = c.towers.size
    val u = c.towers.count { it.used }
    ExpandPanel("Nearby cells", "$n visible · $u used") {
        if (c.towers.isEmpty()) {
            Text(c.error ?: "No cells reported yet.", color = Dim)
        } else {
            Text("Tap a cell for details.", color = Dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            for (t in c.towers) TowerRow(t, c, g)
        }
    }
}

@Composable
fun TowerRow(t: Tower, c: CellHub, g: GpsHub) {
    var open by remember(t.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBars(t.level, dbmColor(t.dbm))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${t.radio} · ${t.shortId}" + if (t.used) " · used" else "",
                    color = if (t.used) Accent else Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    buildList {
                        if (t.pci != NA) add("PCI ${t.pci}")
                        if (t.arfcn != NA) add("ch ${t.arfcn}")
                        t.band?.let { add(it) }
                    }.joinToString(" · ").ifEmpty { "–" },
                    color = Dim, style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                if (t.dbm != NA) "${t.dbm} dBm" else "–",
                color = dbmColor(t.dbm), style = MaterialTheme.typography.bodyMedium
            )
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp, top = 6.dp)) { TowerDetails(t, c, g) }
        }
    }
    HorizontalDivider(color = PanelBorder)
}

@Composable
fun SignalBars(level: Int, col: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier.width(5.dp).height((4 + i * 4).dp)
                    .background(if (i <= level) col else Color(0xFF2A2A2A))
            )
        }
    }
}

@Composable
fun TowerKeyPanel(c: CellHub) {
    var text by remember { mutableStateOf(c.apiKey) }
    val summary = if (c.apiKey.isBlank()) "API key needed" else "${c.pos.size} located"
    ExpandPanel("Tower location (online)", summary, startOpen = c.apiKey.isBlank()) {
        Text(
            "Android does not give the position of a tower, only its identity. SGH looks each cell up in OpenCelliD, " +
                "a free crowd-sourced database. Create a free account on opencellid.org, copy your API access token and paste it here. " +
                "Coverage varies by region and operator.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("OpenCelliD API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.setKey(text) }) { Text("Save and locate") }
            if (c.apiKey.isNotBlank()) {
                OutlinedButton(onClick = { text = ""; c.setKey("") }) { Text("Remove key") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Cell identities and your key are sent to opencellid.org for each lookup.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
    }
}
