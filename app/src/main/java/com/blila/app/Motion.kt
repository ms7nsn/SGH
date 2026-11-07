package com.blila.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.Locale

private val ColX = Color(0xFFFF5252)
private val ColY = Color(0xFF69F0AE)
private val ColZ = Color(0xFF40C4FF)

class Ring(private val n: Int) {
    val a = FloatArray(n)
    val b = FloatArray(n)
    val c = FloatArray(n)
    var count = 0
    private var head = 0
    val capacity: Int get() = n
    fun add(x: Float, y: Float, z: Float) {
        a[head] = x; b[head] = y; c[head] = z
        head = (head + 1) % n
        if (count < n) count++
    }
    fun idx(i: Int): Int = (head - count + i + n) % n
}

class MotionChannel(
    val title: String, val type: Int, val unit: String, private val scale: Float
) : SensorEventListener {
    val ring = Ring(300)
    var paused by mutableStateOf(false)
    var tick by mutableIntStateOf(0)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var z by mutableFloatStateOf(0f)
    private var n = 0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 3) return
        val a = e.values[0] * scale
        val b = e.values[1] * scale
        val c = e.values[2] * scale
        ring.add(a, b, c)
        n++
        if (n % 3 == 0) { x = a; y = b; z = c; tick++ }
    }
}

@Composable
fun MotionTab() {
    val ctx = LocalContext.current
    val channels = remember {
        listOf(
            MotionChannel("Accelerometer", Sensor.TYPE_ACCELEROMETER, "m/s²", 1f),
            MotionChannel("Gyroscope", Sensor.TYPE_GYROSCOPE, "°/s", 57.29578f),
            MotionChannel("Magnetometer", Sensor.TYPE_MAGNETIC_FIELD, "µT", 1f)
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (ch in channels) MotionCard(ctx, ch)
        GForceCard(ctx)
    }
}

@Composable
fun MotionCard(ctx: Context, ch: MotionChannel) {
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(ch.type) }
    DisposableEffect(ch.paused) {
        if (!ch.paused && sensor != null) sm.registerListener(ch, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(ch) }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ch.title} (${ch.unit})", color = Accent,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            if (sensor != null) {
                OutlinedButton(onClick = { ch.paused = !ch.paused }) {
                    Text(if (ch.paused) "Resume" else "Pause")
                }
            }
        }
        if (sensor == null) {
            Text("Not available on this device.", color = Dim)
            return@Panel
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("X ${fmt(ch.x, 2)}", color = ColX, fontWeight = FontWeight.Bold)
            Text("Y ${fmt(ch.y, 2)}", color = ColY, fontWeight = FontWeight.Bold)
            Text("Z ${fmt(ch.z, 2)}", color = ColZ, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Graph(ch, Modifier.fillMaxWidth().height(150.dp))
        if (ch.paused) {
            Spacer(Modifier.height(4.dp))
            Text("Paused", color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Graph(ch: MotionChannel, modifier: Modifier) {
    Canvas(modifier) {
        ch.tick // redraw on new samples
        val r = ch.ring
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF060606))
        if (r.count < 2) return@Canvas
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until r.count) {
            val k = r.idx(i)
            mn = min(mn, min(r.a[k], min(r.b[k], r.c[k])))
            mx = max(mx, max(r.a[k], max(r.b[k], r.c[k])))
        }
        if (mx - mn < 0.5f) { val m = (mx + mn) / 2f; mn = m - 0.25f; mx = m + 0.25f }
        val pad = (mx - mn) * 0.1f
        mn -= pad; mx += pad
        fun yOf(v: Float) = h - (v - mn) / (mx - mn) * h
        if (mn < 0f && mx > 0f) drawLine(Color(0xFF2A2A2A), Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1f)
        val step = w / (r.capacity - 1)
        fun line(arr: FloatArray, col: Color) {
            val p = Path()
            for (i in 0 until r.count) {
                val px = w - (r.count - 1 - i) * step
                val py = yOf(arr[r.idx(i)])
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            drawPath(p, col, style = Stroke(width = 2.5f))
        }
        line(r.a, ColX); line(r.b, ColY); line(r.c, ColZ)
    }
}


// ---------------------------------------------------------------------------
// G-force
// ---------------------------------------------------------------------------

private const val G0 = 9.80665f

class RawRow(val tMs: Long, val x: Float, val y: Float, val z: Float)

class GForceEngine(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var paused by mutableStateOf(false)
    var withGravity by mutableStateOf(true)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var z by mutableFloatStateOf(0f)
    var g by mutableFloatStateOf(0f)
    var peak by mutableFloatStateOf(0f)
    var hz by mutableFloatStateOf(0f)
    var accuracy by mutableIntStateOf(-1)
    var tsNanos by mutableLongStateOf(0L)
    var tick by mutableIntStateOf(0)
    var rows by mutableStateOf<List<RawRow>>(emptyList())

    val hist = FloatArray(250)
    private var head = 0
    var count = 0
        private set
    fun histAt(i: Int): Float = hist[(head - count + i + hist.size) % hist.size]

    private val recent = ArrayDeque<RawRow>()
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gInit = false
    private var cnt = 0
    private var winStart = 0L
    private var lastUi = 0L

    fun start() {
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    fun resetPeak() { peak = 0f }

    override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 3) return
        val ax = e.values[0]
        val ay = e.values[1]
        val az = e.values[2]
        if (!gInit) {
            gx = ax; gy = ay; gz = az; gInit = true
        } else {
            gx = 0.9f * gx + 0.1f * ax
            gy = 0.9f * gy + 0.1f * ay
            gz = 0.9f * gz + 0.1f * az
        }
        val m = if (withGravity) sqrt(ax * ax + ay * ay + az * az)
        else sqrt((ax - gx) * (ax - gx) + (ay - gy) * (ay - gy) + (az - gz) * (az - gz))
        val gv = m / G0
        hist[head] = gv
        head = (head + 1) % hist.size
        if (count < hist.size) count++
        if (gv > peak) peak = gv

        val now = SystemClock.elapsedRealtime()
        cnt++
        recent.addLast(RawRow(now, ax, ay, az))
        if (recent.size > 12) recent.removeFirst()
        if (now - lastUi >= 80) {
            lastUi = now
            x = ax; y = ay; z = az; g = gv
            tsNanos = e.timestamp
            tick++
            rows = recent.toList()
            if (winStart == 0L) {
                winStart = now; cnt = 0
            } else if (now - winStart >= 1000) {
                hz = cnt * 1000f / (now - winStart)
                cnt = 0
                winStart = now
            }
        }
    }
}

private fun accText(a: Int): String = when (a) {
    3 -> "High (3/3)"
    2 -> "Medium (2/3)"
    1 -> "Low (1/3)"
    0 -> "Unreliable (0/3)"
    else -> "–"
}

@Composable
fun GForceCard(ctx: Context) {
    val eng = remember { GForceEngine(ctx) }
    DisposableEffect(eng.paused) {
        if (!eng.paused) eng.start()
        onDispose { eng.stop() }
    }
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "G-Force", color = Accent,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            if (eng.sensor != null) {
                OutlinedButton(onClick = { eng.paused = !eng.paused }) {
                    Text(if (eng.paused) "Resume" else "Pause")
                }
            }
        }
        if (eng.sensor == null) {
            Text("Accelerometer not available on this device.", color = Dim)
            return@Panel
        }
        TabRow(selectedTabIndex = sub, containerColor = PanelBg, contentColor = Accent) {
            Tab(
                selected = sub == 0, onClick = { sub = 0 }, text = { Text("Overview") },
                selectedContentColor = Accent, unselectedContentColor = Dim
            )
            Tab(
                selected = sub == 1, onClick = { sub = 1 }, text = { Text("Raw data") },
                selectedContentColor = Accent, unselectedContentColor = Dim
            )
        }
        Spacer(Modifier.height(8.dp))
        if (sub == 0) GForceOverview(eng) else GForceRaw(eng)
        if (eng.paused) {
            Spacer(Modifier.height(4.dp))
            Text("Paused", color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GForceOverview(e: GForceEngine) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(fmt(e.g, 2), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("g", color = Dim, modifier = Modifier.padding(bottom = 10.dp))
    }
    LinearProgressIndicator(progress = { (e.g / 4f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    KV("Peak", "${fmt(e.peak, 2)} g", big = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("X ${fmt(e.x / G0, 2)} g", color = ColX, fontWeight = FontWeight.Bold)
        Text("Y ${fmt(e.y / G0, 2)} g", color = ColY, fontWeight = FontWeight.Bold)
        Text("Z ${fmt(e.z / G0, 2)} g", color = ColZ, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        e.tick // redraw on new samples
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF060606))
        if (e.count < 2) return@Canvas
        var mx = 2f
        for (i in 0 until e.count) mx = max(mx, e.histAt(i) * 1.1f)
        fun yOf(v: Float) = h - (v / mx) * h
        if (e.withGravity) drawLine(Color(0xFF2A2A2A), Offset(0f, yOf(1f)), Offset(w, yOf(1f)), 1f)
        val step = w / (e.hist.size - 1)
        val p = Path()
        for (i in 0 until e.count) {
            val px = w - (e.count - 1 - i) * step
            val py = yOf(e.histAt(i))
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, Accent, style = Stroke(width = 2.5f))
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = e.withGravity,
            onCheckedChange = { e.withGravity = it; e.resetPeak() }
        )
        Spacer(Modifier.width(8.dp))
        Text("Include gravity", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { e.resetPeak() }) { Text("Reset peak") }
    }
    Text(
        if (e.withGravity) "At rest the value is about 1 g." else "Gravity removed: at rest the value is about 0 g.",
        color = Dim, style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun GForceRaw(e: GForceEngine) {
    val s = e.sensor ?: return
    val mono = FontFamily.Monospace
    SubTitle("Latest sample")
    KV("X", "${fmt(e.x, 4)} m/s²")
    KV("Y", "${fmt(e.y, 4)} m/s²")
    KV("Z", "${fmt(e.z, 4)} m/s²")
    KV("Magnitude", "${fmt(sqrt(e.x * e.x + e.y * e.y + e.z * e.z), 4)} m/s²")
    KV("Event timestamp", "${e.tsNanos} ns")
    KV("Sample rate", "${fmt(e.hz, 1)} Hz")
    KV("Accuracy", accText(e.accuracy))

    SubTitle("Last 12 samples")
    Row(Modifier.fillMaxWidth()) {
        Text("t (s)", Modifier.weight(1.1f), color = Dim, fontSize = 11.sp, fontFamily = mono)
        Text("x", Modifier.weight(1f), color = ColX, fontSize = 11.sp, fontFamily = mono)
        Text("y", Modifier.weight(1f), color = ColY, fontSize = 11.sp, fontFamily = mono)
        Text("z", Modifier.weight(1f), color = ColZ, fontSize = 11.sp, fontFamily = mono)
    }
    e.tick // refresh the table with new samples
    for (r in e.rows.asReversed()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                String.format(Locale.US, "%.3f", (r.tMs % 1_000_000L) / 1000.0),
                Modifier.weight(1.1f), fontSize = 11.sp, fontFamily = mono
            )
            Text(String.format(Locale.US, "%+.3f", r.x), Modifier.weight(1f), fontSize = 11.sp, fontFamily = mono)
            Text(String.format(Locale.US, "%+.3f", r.y), Modifier.weight(1f), fontSize = 11.sp, fontFamily = mono)
            Text(String.format(Locale.US, "%+.3f", r.z), Modifier.weight(1f), fontSize = 11.sp, fontFamily = mono)
        }
    }

    SubTitle("Sensor")
    KV("Name", s.name)
    KV("Vendor", s.vendor)
    KV("Max range", "${fmt(s.maximumRange, 2)} m/s²")
    KV("Resolution", "${fmt(s.resolution, 5)} m/s²")
    KV("Fastest rate", if (s.minDelay > 0) "${fmt(1_000_000.0 / s.minDelay, 0)} Hz" else "–")
    KV("Power", "${fmt(s.power, 2)} mA")
}
