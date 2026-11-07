package com.blila.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var vitals: VitalsEngine
    private lateinit var light: LightEngine
    private lateinit var gps: GpsHub
    private lateinit var cell: CellHub
    private val permTick = mutableIntStateOf(0)

    private val launcher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permTick.intValue++
        }

    private val wanted = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val missing = wanted.filter { !granted(it) }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vitals = VitalsEngine(this)
        light = LightEngine(this, vitals)
        gps = GpsHub(this)
        cell = CellHub(this)
        val prefs = getSharedPreferences("blila", Context.MODE_PRIVATE)
        askPermissions()
        setContent {
            SghTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    val tickRead = permTick.intValue
                    val bodyOk = tickRead >= 0 && granted(Manifest.permission.BODY_SENSORS)
                    val locOk = tickRead >= 0 && granted(Manifest.permission.ACCESS_FINE_LOCATION)
                    var tab by rememberSaveable { mutableIntStateOf(0) }

                    // Keep the screen awake while the switch is on.
                    var keepOn by remember { mutableStateOf(prefs.getBoolean("keepOn", false)) }
                    val view = LocalView.current
                    DisposableEffect(keepOn) {
                        view.keepScreenOn = keepOn
                        onDispose { view.keepScreenOn = false }
                    }

                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "🛰  SGH",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "Screen on",
                                color = if (keepOn) Accent else Dim,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = keepOn,
                                onCheckedChange = {
                                    keepOn = it
                                    prefs.edit().putBoolean("keepOn", it).apply()
                                }
                            )
                        }
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Black,
                            contentColor = Accent
                        ) {
                            val names = listOf("Vitals", "GPS", "Cells", "Motion")
                            names.forEachIndexed { i, n ->
                                Tab(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    text = { Text(n) },
                                    selectedContentColor = Accent,
                                    unselectedContentColor = Dim
                                )
                            }
                        }
                        when (tab) {
                            0 -> VitalsTab(vitals, light, bodyOk) { askPermissions() }
                            1 -> GpsTab(gps, locOk) { askPermissions() }
                            2 -> CellTab(cell, gps, locOk) { askPermissions() }
                            else -> MotionTab()
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vitals.cancel()
        light.allOff()
    }
}
