package com.blila.app

import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colours taken from the current Material You scheme (readable from drawing code too). */
object Pal {
    var primary by mutableStateOf(Color(0xFF4DD9E8))
    var dim by mutableStateOf(Color(0xFFBFC8CA))
    var panel by mutableStateOf(Color(0xFF1B2122))
    var border by mutableStateOf(Color(0xFF3F484A))
    var onSurface by mutableStateOf(Color(0xFFDEE3E5))
    var dark by mutableStateOf(true)
}

val Accent: Color get() = Pal.primary
val Dim: Color get() = Pal.dim
val PanelBg: Color get() = Pal.panel
val PanelBorder: Color get() = Pal.border
val OnSurf: Color get() = Pal.onSurface

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF4DD9E8), onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58), onPrimaryContainer = Color(0xFF97F0FF),
    background = Color(0xFF0E1415), onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1415), onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A), onSurfaceVariant = Color(0xFFBFC8CA),
    outline = Color(0xFF899294), outlineVariant = Color(0xFF3F484A),
    surfaceContainer = Color(0xFF1B2122), surfaceContainerHigh = Color(0xFF252B2D)
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF006874), onPrimary = Color.White,
    primaryContainer = Color(0xFF97F0FF), onPrimaryContainer = Color(0xFF001F24),
    background = Color(0xFFF5FAFB), onBackground = Color(0xFF171D1E),
    surface = Color(0xFFF5FAFB), onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDBE4E6), onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797A), outlineVariant = Color(0xFFBFC8CA),
    surfaceContainer = Color(0xFFE9EFF0), surfaceContainerHigh = Color(0xFFE3E9EA)
)

@Composable
fun SghTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    Pal.primary = scheme.primary
    Pal.dim = scheme.onSurfaceVariant
    Pal.panel = scheme.surfaceContainer
    Pal.border = scheme.outlineVariant
    Pal.onSurface = scheme.onSurface
    Pal.dark = dark
    MaterialTheme(colorScheme = scheme, content = content)
}

/** In-app language (English or French). */
object Lang {
    var fr by mutableStateOf(false)
}

fun t(en: String, fr: String): String = if (Lang.fr) fr else en

fun appLocale(): Locale = if (Lang.fr) Locale.FRENCH else Locale.ENGLISH

/** Drop-in replacement for Material's Text: strings go through the French dictionary when needed. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current
) {
    androidx.compose.material3.Text(
        text = tr(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        maxLines = maxLines,
        style = style
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
