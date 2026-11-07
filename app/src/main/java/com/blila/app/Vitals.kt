package com.blila.app

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Samsung vendor sensor types used by the built-in HRM (raw PPG channels).
const val TYPE_HRM_A = 65571
const val TYPE_HRM_B = 65572

enum class Measure { NONE, HR, SPO2 }
enum class Phase { IDLE, WAIT_FINGER, MEASURING, DONE }

private class Series {
    val t = ArrayList<Long>()
    val v = ArrayList<Float>()
    fun add(time: Long, x: Float) {
        t.add(time); v.add(x)
        while (t.size > 1 && time - t[0] > 25_000L) { t.removeAt(0); v.removeAt(0) }
    }
    fun clear() { t.clear(); v.clear() }
    fun slice(from: Long): Pair<LongArray, FloatArray> {
        var i = 0
        while (i < t.size && t[i] < from) i++
        val n = t.size - i
        return Pair(LongArray(n) { t[i + it] }, FloatArray(n) { v[i + it] })
    }
}

private fun movingAvg(x: FloatArray, w: Int): FloatArray {
    val n = x.size
    if (w <= 1 || n == 0) return x.copyOf()
    val pre = DoubleArray(n + 1)
    for (i in 0 until n) pre[i + 1] = pre[i] + x[i]
    val h = w / 2
    return FloatArray(n) { i ->
        val a = max(0, i - h)
        val b = min(n, i + h + 1)
        ((pre[b] - pre[a]) / (b - a)).toFloat()
    }
}

private fun rateOf(t: LongArray): Double =
    if (t.size < 2 || t.last() <= t.first()) 0.0 else (t.size - 1) * 1000.0 / (t.last() - t.first())

/** Rough 0.7-6 Hz band-pass: short smoothing minus slow trend. */
private fun bandpass(t: LongArray, x: FloatArray): FloatArray {
    val rate = rateOf(t)
    if (rate <= 0.0) return FloatArray(x.size)
    val lo = movingAvg(x, max(1, (rate * 0.08).roundToInt()))
    val tr = movingAvg(x, max(3, (rate * 1.2).roundToInt()))
    return FloatArray(x.size) { lo[it] - tr[it] }
}

private fun detectBpm(t: LongArray, bp: FloatArray): Int? {
    val n = bp.size
    if (n < 20 || t.last() - t.first() < 5000) return null
    val mean = bp.average().toFloat()
    var s = 0.0
    for (x in bp) s += ((x - mean) * (x - mean)).toDouble()
    val sd = sqrt(s / n).toFloat()
    if (sd <= 1e-6f) return null
    val peaks = ArrayList<Int>()
    for (i in 1 until n - 1) {
        if (bp[i] > bp[i - 1] && bp[i] >= bp[i + 1] && bp[i] > mean + 0.25f * sd) {
            if (peaks.isNotEmpty() && t[i] - t[peaks.last()] < 330) {
                if (bp[i] > bp[peaks.last()]) peaks[peaks.size - 1] = i
            } else peaks.add(i)
        }
    }
    if (peaks.size < 4) return null
    val iv = ArrayList<Double>()
    for (k in 1 until peaks.size) iv.add((t[peaks[k]] - t[peaks[k - 1]]).toDouble())
    val good0 = iv.filter { it in 300.0..1500.0 }
    if (good0.size < 3) return null
    val med = good0.sorted()[good0.size / 2]
    val good = good0.filter { abs(it - med) <= 0.25 * med }
    if (good.size < 3) return null
    val bpm = 60000.0 / good.average()
    return if (bpm in 40.0..200.0) bpm.roundToInt() else null
}

/** Returns (DC level, AC RMS) of a channel, or null if not enough data. */
private fun acdc(t: LongArray, x: FloatArray): Pair<Double, Double>? {
    if (x.size < 30) return null
    val dc = x.average()
    if (dc <= 0.0) return null
    val bp = bandpass(t, x)
    val m = (rateOf(t) * 0.6).toInt()
    if (bp.size - 2 * m < 10) return null
    var s = 0.0
    for (i in m until bp.size - m) s += bp[i].toDouble() * bp[i]
    return Pair(dc, sqrt(s / (bp.size - 2 * m)))
}

class VitalsEngine(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs: SharedPreferences = ctx.getSharedPreferences("blila", Context.MODE_PRIVATE)

    private fun find(type: Int): Sensor? =
        sm.getDefaultSensor(type) ?: sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type }

    private val sA = find(TYPE_HRM_A)
    private val sB = find(TYPE_HRM_B)
    private val sStd = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    val hasRaw: Boolean = sA != null && sB != null
    val hasAny: Boolean = hasRaw || sStd != null

    // settings (persisted)
    var swap by mutableStateOf(prefs.getBoolean("swap", false))
    var spo2Offset by mutableFloatStateOf(prefs.getFloat("offset", 0f))
    var formula by mutableIntStateOf(prefs.getInt("formula", 0))
    var fingerMin by mutableFloatStateOf(prefs.getFloat("fingerMin", 3000f))
    fun save() {
        prefs.edit().putBoolean("swap", swap).putFloat("offset", spo2Offset)
            .putInt("formula", formula).putFloat("fingerMin", fingerMin).apply()
    }

    // live state
    var rawA by mutableFloatStateOf(0f)
    var rawB by mutableFloatStateOf(0f)
    var fingerOn by mutableStateOf(false)
    var measure by mutableStateOf(Measure.NONE)
    var phase by mutableStateOf(Phase.IDLE)
    var progress by mutableFloatStateOf(0f)
    var liveBpm by mutableIntStateOf(0)
    var liveSpo2 by mutableIntStateOf(0)
    var bpmResult by mutableIntStateOf(0)
    var spo2Result by mutableIntStateOf(0)
    var perfusion by mutableFloatStateOf(0f)
    var message by mutableStateOf("")
    var tick by mutableIntStateOf(0)

    private val serA = Series()
    private val serB = Series()
    private val hist = ArrayList<Float>()
    private var t0 = 0L
    private var lastTick = 0L
    private var lastProc = 0L
    private var aboveSince = 0L
    private var belowSince = 0L
    private var stdValue = 0f
    private var lastStd = 0L
    private var registered = false

    private fun register(): Boolean {
        return try {
            registered = true
            if (hasRaw) {
                val a = sm.registerListener(this, sA, SensorManager.SENSOR_DELAY_FASTEST)
                val b = sm.registerListener(this, sB, SensorManager.SENSOR_DELAY_FASTEST)
                a && b
            } else {
                sm.registerListener(this, sStd, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: SecurityException) {
            false
        }
    }

    private fun unregister() {
        if (registered) sm.unregisterListener(this)
        registered = false
    }

    fun start(m: Measure) {
        if (phase == Phase.WAIT_FINGER || phase == Phase.MEASURING) return
        measure = m
        message = ""
        if (!hasAny) { message = "No heart-rate sensor found on this device."; phase = Phase.DONE; return }
        if (m == Measure.SPO2 && !hasRaw) {
            message = "SpO₂ needs the Samsung raw red/IR sensors, which were not found on this device."
            phase = Phase.DONE; return
        }
        progress = 0f; liveBpm = 0; liveSpo2 = 0; fingerOn = false
        aboveSince = 0L; belowSince = 0L; lastStd = 0L; stdValue = 0f
        hist.clear(); serA.clear(); serB.clear()
        if (register()) phase = Phase.WAIT_FINGER
        else {
            unregister()
            message = "Error when accessing the heart-rate sensor. Check the Body sensors permission."
            phase = Phase.DONE
        }
    }

    fun cancel() {
        if (phase == Phase.WAIT_FINGER || phase == Phase.MEASURING) {
            unregister()
            phase = Phase.IDLE
            progress = 0f
            fingerOn = false
            liveBpm = 0; liveSpo2 = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        when (e.sensor.type) {
            TYPE_HRM_A -> { rawA = e.values[0]; serA.add(now, rawA) }
            TYPE_HRM_B -> { rawB = e.values[0]; serB.add(now, rawB) }
            Sensor.TYPE_HEART_RATE -> { stdValue = e.values[0]; if (stdValue > 0f) lastStd = now }
            else -> return
        }
        step(now)
    }

    private fun updateFinger(now: Long) {
        val gate = if (!swap) rawB else rawA   // the "red" channel gates finger presence
        if (gate > fingerMin) {
            belowSince = 0L
            if (aboveSince == 0L) aboveSince = now
            if (!fingerOn && now - aboveSince >= 600) fingerOn = true
        } else if (gate < fingerMin * 0.8f) {
            aboveSince = 0L
            if (belowSince == 0L) belowSince = now
            if (fingerOn && now - belowSince >= 300) fingerOn = false
        }
    }

    private fun step(now: Long) {
        if (now - lastTick >= 40) { lastTick = now; tick++ }
        if (hasRaw) updateFinger(now) else fingerOn = lastStd != 0L && now - lastStd < 3000
        if (now - lastProc < 250) return
        lastProc = now
        when (phase) {
            Phase.WAIT_FINGER -> if (fingerOn) {
                serA.clear(); serB.clear(); hist.clear()
                t0 = now; progress = 0f; liveBpm = 0; liveSpo2 = 0
                phase = Phase.MEASURING
            }
            Phase.MEASURING -> {
                if (!fingerOn) {
                    phase = Phase.WAIT_FINGER; progress = 0f; liveBpm = 0; liveSpo2 = 0
                    return
                }
                val dur = if (measure == Measure.HR) 15_000L else 20_000L
                progress = ((now - t0).toFloat() / dur).coerceIn(0f, 1f)
                estimate(now)
                if (now - t0 >= dur) finish()
            }
            else -> {}
        }
    }

    private fun estimate(now: Long) {
        if (!hasRaw) {
            if (stdValue > 0f) {
                liveBpm = stdValue.roundToInt()
                if (now - t0 > 3000) hist.add(stdValue)
            }
            return
        }
        val from = max(t0 + 1500, now - 10_000)
        val irS = if (!swap) serA else serB
        val rdS = if (!swap) serB else serA
        val (ti, vi) = irS.slice(from)
        if (measure == Measure.HR) {
            val b = detectBpm(ti, bandpass(ti, vi))
            if (b != null) { liveBpm = b; hist.add(b.toFloat()) }
        } else {
            val (tr, vr) = rdS.slice(from)
            val a = acdc(ti, vi)
            val r = acdc(tr, vr)
            if (a != null && r != null && a.second > 0.0 && r.second > 0.0) {
                val piIr = a.second / a.first
                perfusion = (piIr * 100.0).toFloat()
                if (piIr < 0.1) {
                    val ratio = (r.second / r.first) / piIr
                    val ca = if (formula == 0) 110.0 else 104.0
                    val cb = if (formula == 0) 25.0 else 17.0
                    val s = (ca - cb * ratio + spo2Offset).coerceIn(70.0, 100.0)
                    liveSpo2 = s.roundToInt()
                    hist.add(s.toFloat())
                }
            }
        }
    }

    private fun finish() {
        unregister()
        phase = Phase.DONE
        progress = 1f
        fingerOn = false
        if (hist.size < 4) {
            message = "Signal too weak or unstable. Press gently but firmly, keep still, and try again."
            return
        }
        val tail = hist.takeLast(max(3, hist.size / 2)).sorted()
        val med = tail[tail.size / 2].roundToInt()
        if (measure == Measure.HR) bpmResult = med else spo2Result = med
        message = "Done. Estimate only, not a medical measurement."
    }

    /** Band-passed IR waveform of the last ~5 s, for the live graph. */
    fun wave(): FloatArray {
        val s = if (!swap) serA else serB
        val (t, v) = s.slice(SystemClock.elapsedRealtime() - 5000)
        if (t.size < 10) return FloatArray(0)
        return bandpass(t, v)
    }
}

@Composable
fun VitalsTab(e: VitalsEngine, light: LightEngine, permOk: Boolean, askPerm: () -> Unit) {
    DisposableEffect(Unit) { onDispose { e.cancel() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!e.hasAny) {
            Panel { Text("No heart-rate sensor was found on this device.", color = Dim) }
        }
        PpgPanel(e)
        VitalCard("Heart rate", "BPM", e, Measure.HR, e.bpmResult, e.liveBpm, permOk, askPerm)
        VitalCard("Blood oxygen (SpO₂)", "%", e, Measure.SPO2, e.spo2Result, e.liveSpo2, permOk, askPerm)
        LightPanel(light, permOk, askPerm)
        CalibrationPanel(e)
        Text(
            "Estimates from the phone's built-in sensor. Not a medical device and not for diagnosis.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun PpgPanel(e: VitalsEngine) {
    Panel {
        Text("Live pulse signal", color = Accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            e.tick // redraw on new samples
            val w = e.wave()
            val mid = size.height / 2f
            drawLine(Color(0xFF222222), Offset(0f, mid), Offset(size.width, mid), 1f)
            if (w.size > 2) {
                var peak = 1e-6f
                for (x in w) peak = max(peak, abs(x))
                val p = Path()
                for (i in w.indices) {
                    val px = i.toFloat() / (w.size - 1) * size.width
                    val py = mid - w[i] / peak * (mid * 0.9f)
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                drawPath(p, Color(0xFFFF5252), style = Stroke(width = 3f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val ir = if (!e.swap) e.rawA else e.rawB
        val red = if (!e.swap) e.rawB else e.rawA
        Text(
            "IR ${ir.roundToInt()}  ·  Red ${red.roundToInt()}  ·  Finger: ${if (e.fingerOn) "yes" else "no"}" +
                if (e.measure == Measure.SPO2 && e.perfusion > 0f) "  ·  PI ${fmt(e.perfusion, 2)}%" else "",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun VitalCard(
    title: String, unit: String, e: VitalsEngine, m: Measure,
    result: Int, live: Int, permOk: Boolean, askPerm: () -> Unit
) {
    val busy = e.phase == Phase.WAIT_FINGER || e.phase == Phase.MEASURING
    val active = busy && e.measure == m
    Panel {
        Text(title, color = Accent, style = MaterialTheme.typography.titleMedium)
        val shown = if (active && live > 0) live else result
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (shown > 0) "$shown" else "--", fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(unit, color = Dim, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (active) {
            Text(
                if (e.phase == Phase.WAIT_FINGER)
                    "Place your fingertip on the sensor next to the camera flash and hold still…"
                else "Measuring… keep your finger still",
                color = Dim, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
        } else if (e.measure == m && e.phase == Phase.DONE && e.message.isNotEmpty()) {
            Text(e.message, color = Dim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { if (!permOk) askPerm() else if (active) e.cancel() else e.start(m) },
            enabled = active || !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (!permOk) "Grant sensor permission" else if (active) "Cancel" else "Measure")
        }
    }
}

@Composable
fun CalibrationPanel(e: VitalsEngine) {
    Panel {
        Text("Calibration", color = Accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Swap IR / Red channels", Modifier.weight(1f))
            Switch(checked = e.swap, onCheckedChange = { e.swap = it; e.save() })
        }
        Spacer(Modifier.height(6.dp))
        Text("SpO₂ offset: ${fmt(e.spo2Offset, 1)} %", color = Dim)
        Slider(
            value = e.spo2Offset,
            onValueChange = { e.spo2Offset = (it * 2f).roundToInt() / 2f },
            onValueChangeFinished = { e.save() },
            valueRange = -10f..10f
        )
        Text("Finger threshold: ${e.fingerMin.roundToInt()}", color = Dim)
        Slider(
            value = e.fingerMin,
            onValueChange = { e.fingerMin = it },
            onValueChangeFinished = { e.save() },
            valueRange = 500f..20000f
        )
        Text("SpO₂ formula", color = Dim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = e.formula == 0, onClick = { e.formula = 0; e.save() })
            Text("110 − 25·R")
            Spacer(Modifier.width(16.dp))
            RadioButton(selected = e.formula == 1, onClick = { e.formula = 1; e.save() })
            Text("104 − 17·R")
        }
    }
}
