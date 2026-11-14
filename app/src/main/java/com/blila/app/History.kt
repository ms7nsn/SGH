package com.blila.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

@Composable
fun SghDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.95f).heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, Modifier.weight(1f),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(onClick = onClose) { Text(t("Close", "Fermer")) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}

/** Strip at the top of a tab: save switch + small chart button. */
@Composable
fun StoreBar(
    tab: Int,
    hint: String,
    onChart: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {}
) {
    val s = App.settings
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.saveOn[tab], onCheckedChange = { s.setSave(tab, it) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Save data", "Enregistrer les donn茅es"), fontWeight = FontWeight.Bold)
                Text(hint, color = Dim, style = MaterialTheme.typography.bodySmall)
            }
            extra()
            IconButton(onClick = onChart) { Text("馃搱", fontSize = 22.sp) }
        }
    }
}

class Spec(
    val kind: String,
    val title: String,
    val unit: String,
    val dayBucket: Long,
    val weekBucket: Long,
    val decimals: Int = 0
)

private fun statsLine(data: List<Agg>, dec: Int, unit: String): String {
    var n = 0
    var sum = 0.0
    var mn = Double.MAX_VALUE
    var mx = -Double.MAX_VALUE
    for (a in data) {
        n += a.n
        sum += a.avg * a.n
        mn = min(mn, a.min)
        mx = max(mx, a.max)
    }
    return t(
        "$n samples 路 avg ${fmt(sum / n, dec)} 路 min ${fmt(mn, dec)} 路 max ${fmt(mx, dec)} $unit",
        "$n mesures 路 moy ${fmt(sum / n, dec)} 路 min ${fmt(mn, dec)} 路 max ${fmt(mx, dec)} $unit"
    )
}

@Composable
fun HistChart(data: List<Agg>, from: Long, to: Long, week: Boolean, bucket: Long, decimals: Int) {
    val line = Accent
    val grid = PanelBorder
    val dimArgb = Dim.toArgb()
    val loc = appLocale()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val lm = 46.dp.toPx()
        val rm = 14.dp.toPx()
        val bm = 22.dp.toPx()
        val tm = 8.dp.toPx()
        val w = size.width - lm - rm
        val h = size.height - bm - tm
        val cv = drawContext.canvas.nativeCanvas
        val pr = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 11.sp.toPx()
            color = dimArgb
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val pc = android.graphics.Paint(pr).apply { textAlign = android.graphics.Paint.Align.CENTER }
        if (data.isEmpty() || to <= from) return@Canvas
        var mn = data.minOf { it.min }
        var mx = data.maxOf { it.max }
        if (mx - mn < 1e-6) {
            mn -= 1.0; mx += 1.0
        } else {
            val pad = (mx - mn) * 0.1
            mn -= pad; mx += pad
        }
        fun xOf(t: Long): Float = lm + ((t - from).toFloat() / (to - from).toFloat()) * w
        fun yOf(v: Double): Float = tm + h - ((v - mn) / (mx - mn)).toFloat() * h

        for (i in 0..2) {
            val v = mn + (mx - mn) * i / 2.0
            val y = yOf(v)
            drawLine(grid, Offset(lm, y), Offset(lm + w, y), 1f)
            cv.drawText(fmt(v, decimals), lm - 6.dp.toPx(), y + 4.dp.toPx(), pr)
        }
        if (week) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val f = SimpleDateFormat("EEE", loc)
            while (cal.timeInMillis <= to) {
                val x = xOf(cal.timeInMillis)
                drawLine(grid, Offset(x, tm), Offset(x, tm + h), 1f)
                cv.drawText(f.format(Date(cal.timeInMillis)), x, size.height - 6.dp.toPx(), pc)
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            val f = SimpleDateFormat("HH:mm", loc)
            for (i in 0..3) {
                val tt = from + (to - from) * i / 4
                val x = xOf(tt)
                drawLine(grid, Offset(x, tm), Offset(x, tm + h), 1f)
                cv.drawText(f.format(Date(tt)), x, size.height - 6.dp.toPx(), pc)
            }
        }
        val shift = if (bucket > 1) bucket / 2 else 0L
        val sparse = data.size <= 60
        for (a in data) {
            val x = xOf(a.t + shift)
            if (a.max - a.min > 1e-9) {
                drawLine(line.copy(alpha = 0.35f), Offset(x, yOf(a.min)), Offset(x, yOf(a.max)), if (sparse) 4f else 2f)
            }
        }
        for (i in 1 until data.size) {
            val p = data[i - 1]
            val q = data[i]
            // do not join points that are far apart in time (gaps with no data)
            if (q.t - p.t <= max(bucket * 3, if (week) DAY_MS else 30 * 60_000L)) {
                drawLine(line, Offset(xOf(p.t + shift), yOf(p.avg)), Offset(xOf(q.t + shift), yOf(q.avg)), 3f)
            }
        }
        if (sparse) {
            for (a in data) drawCircle(line, 5f, Offset(xOf(a.t + shift), yOf(a.avg)))
        }
    }
}

@Composable
fun HistoryDialog(
    title: String,
    tabKey: String,
    specs: List<Spec>,
    onClose: () -> Unit,
    extra: @Composable ColumnScope.(since: Long) -> Unit = {}
) {
    var week by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val now = remember(week, nonce) { System.currentTimeMillis() }
    val since = now - if (week) 7 * DAY_MS else DAY_MS
    val off = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }

    SghDialog(title, onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !week, onClick = { week = false }, label = { Text(t("Day", "Jour")) })
            FilterChip(selected = week, onClick = { week = true }, label = { Text(t("Week", "Semaine")) })
        }
        Text(
            if (week) t("Last 7 days", "7 derniers jours") else t("Last 24 hours", "24 derni猫res heures"),
            color = Dim, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        for (sp in specs) {
            val bucket = if (week) sp.weekBucket else sp.dayBucket
            val data = remember(week, nonce, sp.kind) {
                App.store.agg(tabKey, sp.kind, since, bucket, if (bucket >= HOUR_MS) off else 0L)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "${sp.title} (${sp.unit})", color = Accent,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            if (data.isEmpty()) {
                Text(
                    t(
                        "No data in this period. Turn on Save data in the tab.",
                        "Aucune donn茅e sur cette p茅riode. Active Enregistrer les donn茅es dans l'onglet."
                    ),
                    color = Dim, style = MaterialTheme.typography.bodySmall
                )
            } else {
                HistChart(data, since, now, week, bucket, sp.decimals)
                Text(statsLine(data, sp.decimals, sp.unit), style = MaterialTheme.typography.bodySmall)
                val last = data.last()
                Text(
                    t(
                        "Latest: ${fmt(last.avg, sp.decimals)} ${sp.unit} at " +
                            SimpleDateFormat("dd/MM HH:mm", appLocale()).format(Date(last.t)),
                        "Dernier : ${fmt(last.avg, sp.decimals)} ${sp.unit} 脿 " +
                            SimpleDateFormat("dd/MM HH:mm", appLocale()).format(Date(last.t))
                    ),
                    color = Dim, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        extra(since)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { confirm = true }) {
            Text(t("Delete saved data of this tab", "Supprimer les donn茅es enregistr茅es de cet onglet"))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(t("Delete data?", "Supprimer les donn茅es ?")) },
            text = { Text(t("All saved data of this tab will be deleted.", "Toutes les donn茅es enregistr茅es de cet onglet seront supprim茅es.")) },
            confirmButton = {
                TextButton(onClick = { App.store.clear(tabKey); confirm = false; nonce++ }) {
                    Text(t("Delete", "Supprimer"))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Cancel", "Annuler")) } }
        )
    }
}

private fun spanText(m: Double): String =
    if (m < 1000) "${fmt(m, 0)} m" else "${fmt(m / 1000, 1)} km"

@Composable
fun LocScatter(rows: List<Rec>) {
    val acc = Accent
    val dim = Dim
    val on = OnSurf
    val grid = PanelBorder
    Canvas(Modifier.fillMaxWidth().height(240.dp)) {
        val pts = rows.filter { it.a != null && it.b != null }
        if (pts.isEmpty()) return@Canvas
        val latMin = pts.minOf { it.a!! }
        val latMax = pts.maxOf { it.a!! }
        val lonMin = pts.minOf { it.b!! }
        val lonMax = pts.maxOf { it.b!! }
        val ky = 111_320.0
        val kx = cos(Math.toRadians((latMin + latMax) / 2.0))
        val xr = max((lonMax - lonMin) * kx, 1e-7)
        val yr = max(latMax - latMin, 1e-7)
        val pad = 30.dp.toPx()
        val sc = min((size.width - 2 * pad) / xr, (size.height - 2 * pad) / yr).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val mx = (lonMin + lonMax) / 2.0
        val my = (latMin + latMax) / 2.0
        fun px(p: Rec): Offset = Offset(
            cx + ((p.b!! - mx) * kx * sc).toFloat(),
            cy - ((p.a!! - my) * sc).toFloat()
        )
        drawRect(grid, style = Stroke(1f))
        val ordered = pts.reversed()
        for (i in 1 until ordered.size) drawLine(dim.copy(alpha = 0.5f), px(ordered[i - 1]), px(ordered[i]), 2f)
        val cv = drawContext.canvas.nativeCanvas
        val lab = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12.sp.toPx()
            color = on.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (p in pts) {
            val o = px(p)
            if (p.label.isNotEmpty()) {
                drawCircle(acc, 7.dp.toPx(), o)
                cv.drawText(p.label, o.x, o.y - 12.dp.toPx(), lab)
            } else {
                drawCircle(dim, 3.5f.dp.toPx(), o)
            }
        }
        drawCircle(acc, 10.dp.toPx(), px(pts.first()), style = Stroke(3f))
        lab.textAlign = android.graphics.Paint.Align.LEFT
        lab.color = dim.toArgb()
        cv.drawText("N 鈫�", 8.dp.toPx(), 18.dp.toPx(), lab)
        cv.drawText(
            t("Span 鈮� ", "脡tendue 鈮� ") + spanText(max(xr, yr) * ky),
            8.dp.toPx(), size.height - 8.dp.toPx(), lab
        )
    }
}

private fun openMaps(ctx: Context, lat: Double, lon: Double) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
    } catch (e: Exception) {
    }
}

@Composable
fun LocationsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var nonce by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val rows = remember(nonce) { App.store.rows("gps", "loc", 300) }
    SghDialog(t("Saved locations", "Positions enregistr茅es"), onClose) {
        if (rows.isEmpty()) {
            Text(
                t(
                    "No saved location yet. Use Save here, or turn on Save data to log your position while this tab is open.",
                    "Aucune position enregistr茅e. Utilise Enregistrer ici, ou active Enregistrer les donn茅es pour m茅moriser ta position tant que cet onglet est ouvert."
                ),
                color = Dim
            )
        } else {
            Text(
                t("${rows.size} saved position(s). Named points are big, the ring is the latest.",
                    "${rows.size} position(s) enregistr茅e(s). Les points nomm茅s sont gros, l'anneau est le plus r茅cent."),
                color = Dim, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            LocScatter(rows)
            Spacer(Modifier.height(8.dp))
            val f = SimpleDateFormat("dd/MM/yyyy HH:mm", appLocale())
            for (r in rows.take(100)) {
                val lat = r.a ?: continue
                val lon = r.b ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.label.ifEmpty { t("Logged position", "Position relev茅e") },
                            fontWeight = FontWeight.Bold
                        )
                        Text("${fmt(lat, 5)}掳, ${fmt(lon, 5)}掳", style = MaterialTheme.typography.bodySmall)
                        Text(
                            f.format(Date(r.ts)) +
                                (r.c?.let { " 路 ${fmt(it, 0)} m" } ?: "") +
                                (r.d?.let { " 路 卤${fmt(it, 0)} m" } ?: ""),
                            color = Dim, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { openMaps(ctx, lat, lon) }) { Text(t("Map", "Carte")) }
                    TextButton(onClick = { App.store.delete(r.id); nonce++ }) { Text(t("Delete", "Suppr.")) }
                }
                HorizontalDivider(color = PanelBorder)
            }
            if (rows.size > 100) {
                Text(
                    t("Showing the latest 100 in the list.", "Les 100 plus r茅centes sont list茅es."),
                    color = Dim, style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirm = true }) { Text(t("Delete all locations", "Supprimer toutes les positions")) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(t("Delete all locations?", "Supprimer toutes les positions ?")) },
            confirmButton = {
                TextButton(onClick = { App.store.clear("gps", "loc"); confirm = false; nonce++ }) {
                    Text(t("Delete", "Supprimer"))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Cancel", "Annuler")) } }
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s = App.settings
    var clearTab by remember { mutableIntStateOf(-1) }
    var nonce by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("鈫�  " + t("Back", "Retour")) }
            Text(
                t("Settings", "R茅glages"), Modifier.weight(1f),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge
            )
        }
        Panel {
            Text(t("Tabs", "Onglets"), color = Accent, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            for (i in TabIds) {
                val only = s.tabOn[i] && s.tabOn.count { it } == 1
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tabName(i), Modifier.weight(1f))
                    Switch(checked = s.tabOn[i], enabled = !only, onCheckedChange = { s.setTab(i, it) })
                }
            }
            Text(
                t("At least one tab stays on.", "Au moins un onglet reste activ茅."),
                color = Dim, style = MaterialTheme.typography.bodySmall
            )
        }
        Panel {
            Text(t("Theme", "Th猫me"), color = Accent, style = MaterialTheme.typography.labelLarge)
            val opts = listOf(
                0 to t("System", "Syst猫me"),
                1 to t("Light", "Clair"),
                2 to t("Dark", "Sombre")
            )
            for ((v, name) in opts) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.theme == v, onClick = { s.updateTheme(v) })
                    Text(name)
                }
            }
            Text(
                t("Colours follow your wallpaper (Material You) on Android 12 and later.",
                    "Les couleurs suivent ton fond d'茅cran (Material You) sur Android 12 et plus."),
                color = Dim, style = MaterialTheme.typography.bodySmall
            )
        }
        Panel {
            Text(t("Language", "Langue"), color = Accent, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !Lang.fr, onClick = { s.setLang(false) })
                Text("English")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = Lang.fr, onClick = { s.setLang(true) })
                Text("Fran莽ais")
            }
        }
        Panel {
            Text(t("Saved data", "Donn茅es enregistr茅es"), color = Accent, style = MaterialTheme.typography.labelLarge)
            for (i in TabIds) {
                val n = remember(nonce) { App.store.count(TabKeys[i]) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tabName(i)} 路 $n", Modifier.weight(1f))
                    TextButton(onClick = { clearTab = i }, enabled = n > 0) { Text(t("Clear", "Effacer")) }
                }
            }
            Text(
                t("Data older than 60 days is removed automatically.", "Les donn茅es de plus de 60 jours sont supprim茅es automatiquement."),
                color = Dim, style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (clearTab >= 0) {
        AlertDialog(
            onDismissRequest = { clearTab = -1 },
            title = { Text(t("Delete data?", "Supprimer les donn茅es ?")) },
            text = { Text(tabName(clearTab)) },
            confirmButton = {
                TextButton(onClick = { App.store.clear(TabKeys[clearTab]); clearTab = -1; nonce++ }) {
                    Text(t("Delete", "Supprimer"))
                }
            },
            dismissButton = { TextButton(onClick = { clearTab = -1 }) { Text(t("Cancel", "Annuler")) } }
        )
    }
}
