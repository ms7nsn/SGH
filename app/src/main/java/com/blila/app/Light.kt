package com.blila.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private class NoopListener : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {}
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/**
 * Keeps the Samsung HRM emitters powered by holding the raw sensors open.
 * The phone sensor only has red and infrared emitters; there is no public API
 * to drive LEDs directly, so this is best effort.
 */
class LightEngine(ctx: Context, private val vitals: VitalsEngine) {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun find(type: Int): Sensor? =
        sm.getDefaultSensor(type) ?: sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type }

    private val sA = find(TYPE_HRM_A)
    private val sB = find(TYPE_HRM_B)

    // Same convention as the vitals engine: A = IR, B = red unless swapped.
    private fun redSensor(): Sensor? = if (!vitals.swap) sB else sA
    private fun irSensor(): Sensor? = if (!vitals.swap) sA else sB

    val hasRed: Boolean get() = redSensor() != null
    val hasIr: Boolean get() = irSensor() != null

    var redOn by mutableStateOf(false)
    var irOn by mutableStateOf(false)
    var message by mutableStateOf("")

    private val redL = NoopListener()
    private val irL = NoopListener()

    val detected: List<String> by lazy {
        val rx = Regex("hrm|ppg|green|blue|led|spo2|heart", RegexOption.IGNORE_CASE)
        sm.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type >= 65536 || it.type == Sensor.TYPE_HEART_RATE || rx.containsMatchIn(it.name) }
            .map { "${it.name} · type ${it.type} · ${it.vendor}" }
    }

    private fun set(on: Boolean, sensor: Sensor?, l: SensorEventListener): Boolean {
        if (sensor == null) {
            message = "This sensor was not found on this device."
            return false
        }
        return try {
            if (on) {
                val okReg = sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (!okReg) message = "The sensor could not be started."
                okReg
            } else {
                sm.unregisterListener(l)
                true
            }
        } catch (e: SecurityException) {
            message = "Body sensors permission is required."
            false
        }
    }

    fun setRed(on: Boolean) {
        message = ""
        redOn = on && set(on, redSensor(), redL)
        if (!on) set(false, redSensor(), redL)
    }

    fun setIr(on: Boolean) {
        message = ""
        irOn = on && set(on, irSensor(), irL)
        if (!on) set(false, irSensor(), irL)
    }

    fun allOff() {
        sm.unregisterListener(redL)
        sm.unregisterListener(irL)
        redOn = false
        irOn = false
    }
}

@Composable
fun LightPanel(l: LightEngine, permOk: Boolean, askPerm: () -> Unit) {
    Panel {
        Text("Sensor light", color = Accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Lights the emitters of the heart-rate sensor (next to the camera flash). Turns off when you leave the app.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        LightRow("Red", Color(0xFFFF5252), l.redOn, l.hasRed) {
            if (!permOk) askPerm() else l.setRed(it)
        }
        LightRow("Infrared (invisible)", Color(0xFFB0B0B0), l.irOn, l.hasIr) {
            if (!permOk) askPerm() else l.setIr(it)
        }
        if (l.message.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(l.message, color = Color(0xFFFFA726), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Android has no direct way to drive these LEDs, so this is best effort. The light may look dim or pulsed.",
            color = Dim, style = MaterialTheme.typography.bodySmall
        )
        if (l.detected.isNotEmpty()) {
            SubTitle("Detected heart-rate sensors")
            for (d in l.detected) Text(d, color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LightRow(name: String, col: Color, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (enabled) col else Color(0xFF444444), modifier = Modifier.padding(end = 10.dp))
        Text(
            name, Modifier.weight(1f),
            color = if (enabled) OnSurf else Dim
        )
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}
