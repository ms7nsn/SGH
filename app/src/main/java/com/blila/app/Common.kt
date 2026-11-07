package com.blila.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFF00E5FF)
val Dim = Color(0xFF9E9E9E)
val PanelBg = Color(0xFF0B0B0B)
val PanelBorder = Color(0xFF242424)

@Composable
fun SghTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF121212),
            onSurfaceVariant = Dim,
            outline = Color(0xFF333333)
        ),
        content = content
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        border = BorderStroke(1.dp, PanelBorder)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/**
 * Card that shows a short headline and expands on tap to show the details.
 */
@Composable
fun ExpandPanel(
    title: String,
    summary: String,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        border = BorderStroke(1.dp, PanelBorder)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(
                        summary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Text(if (open) "▴" else "▾", color = Dim, fontSize = 22.sp)
            }
            if (open) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = PanelBorder)
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun KV(k: String, v: String, big: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(k, color = Dim, style = MaterialTheme.typography.bodyMedium)
        Text(
            v,
            fontWeight = FontWeight.Bold,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
        )
    }
}

/** Label above a (possibly long) value, for addresses and similar. */
@Composable
fun Stack(k: String, v: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Dim, style = MaterialTheme.typography.bodySmall)
        Text(v, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SubTitle(t: String) {
    Text(
        t, color = Accent, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

fun fmt(x: Double, d: Int): String = String.format(Locale.US, "%.${d}f", x)
fun fmt(x: Float, d: Int): String = String.format(Locale.US, "%.${d}f", x)

fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
