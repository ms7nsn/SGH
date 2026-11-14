package com.blila.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

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
        App.settings = Settings(this)
        App.store = Store(this)
        App.store.prune()
        vitals = VitalsEngine(this)
        vitals.onResult = { kind, v ->
            if (App.settings.saveOn[0]) App.store.add("vitals", kind, v)
        }
        light = LightEngine(this, vitals)
        gps = GpsHub(this)
        cell = CellHub(this)
        val prefs = getSharedPreferences("blila", Context.MODE_PRIVATE)
        askPermissions()
        setContent {
            val settings = App.settings
            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            SghTheme(dark) {
                val bg = MaterialTheme.colorScheme.background
                SideEffect {
                    window.statusBarColor = bg.toArgb()
                    window.navigationBarColor = bg.toArgb()
                    val ic = WindowCompat.getInsetsController(window, window.decorView)
                    ic.isAppearanceLightStatusBars = !dark
                    ic.isAppearanceLightNavigationBars = !dark
                }
                Surface(Modifier.fillMaxSize(), color = bg) {
                    val tickRead = permTick.intValue
                    val bodyOk = tickRead >= 0 && granted(Manifest.permission.BODY_SENSORS)
                    val locOk = tickRead >= 0 && granted(Manifest.permission.ACCESS_FINE_LOCATION)
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var menu by remember { mutableStateOf(false) }

                    // Keep the screen awake while the switch is on.
                    var keepOn by remember { mutableStateOf(prefs.getBoolean("keepOn", false)) }
                    val view = LocalView.current
                    DisposableEffect(keepOn) {
                        view.keepScreenOn = keepOn
                        onDispose { view.keepScreenOn = false }
                    }

                    if (showSettings) {
                        SettingsScreen { showSettings = false }
                    } else {
                        val enabled = TabIds.filter { settings.tabOn[it] }.ifEmpty { listOf(0) }
                        val cur = if (tab in enabled) tab else enabled.first()
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "🛰  SGH",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Box {
                                    IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 24.sp) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(t("Settings", "Réglages")) },
                                            onClick = { menu = false; showSettings = true }
                                        )
                                    }
                                }
                                Text(
                                    t("Screen on", "Écran allumé"),
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
                                Spacer(Modifier.width(4.dp))
                            }
                            TabRow(
                                selectedTabIndex = enabled.indexOf(cur),
                                containerColor = bg,
                                contentColor = Accent
                            ) {
                                for (id in enabled) {
                                    Tab(
                                        selected = cur == id,
                                        onClick = { tab = id },
                                        text = { Text(tabName(id)) },
                                        selectedContentColor = Accent,
                                        unselectedContentColor = Dim
                                    )
                                }
                            }
                            when (cur) {
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
    }

    override fun onStop() {
        super.onStop()
        vitals.cancel()
        light.allOff()
    }
}
