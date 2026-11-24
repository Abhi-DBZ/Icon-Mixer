package com.iconforge.app.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import com.iconforge.app.core.IconShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iconforge.app.app
import com.iconforge.app.core.Coverage
import com.iconforge.app.core.IconSrc
import com.iconforge.app.data.Settings

// ------------------------------------------------------------------ theme

/** App colours offered in Settings (wallpaper colours come first, on Android 12+). */
val APP_COLOURS = listOf(
    "Indigo" to 0xFF3D4FC4, "Blue" to 0xFF1E88E5, "Teal" to 0xFF00897B, "Green" to 0xFF43A047,
    "Lime" to 0xFF7CB342, "Amber" to 0xFFFFB300, "Orange" to 0xFFF4511E, "Red" to 0xFFE53935,
    "Pink" to 0xFFD81B60, "Purple" to 0xFF8E24AA, "Brown" to 0xFF6D4C41, "Slate" to 0xFF546E7A,
).map { (n, c) -> n to c.toInt() }

private fun ColorScheme.pureBlack() = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D), surfaceContainer = Color(0xFF111114),
    surfaceContainerHigh = Color(0xFF18181C), surfaceContainerHighest = Color(0xFF202025),
)

@Composable
fun IconForgeTheme(settings: Settings, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val mode by settings.themeMode.collectAsState()
    val black by settings.pureBlack.collectAsState()
    val dynamic by settings.dynamicColor.collectAsState()
    val seed by settings.seedColor.collectAsState()
    val dark = when (mode) {
        Settings.MODE_LIGHT -> false
        Settings.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    var scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> com.materialkolor.dynamicColorScheme(seedColor = Color(seed), isDark = dark, isAmoled = false)
    }
    if (dark && black) scheme = scheme.pureBlack()
    MaterialTheme(colorScheme = scheme, content = content)
}

// ------------------------------------------------------------------ icons

/** Shape adaptive icons are previewed in (set per screen from the theme being edited). */
val LocalIconShape = staticCompositionLocalOf { IconShape.DEFAULT }

/** Loads an icon asynchronously through the shared cache. */
@Composable
fun IconImage(src: IconSrc?, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false,
              shape: IconShape = LocalIconShape.current) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(24, 384)
    val loader = ctx.app.icons
    val bmp by produceState<ImageBitmap?>(src?.let { loader.peek(it, px, shape)?.asImageBitmap() }, src?.key, px, shape) {
        value = src?.let { loader.peek(it, px, shape)?.asImageBitmap() }
        if (src != null && value == null) value = loader.bitmap(src, px, shape)?.asImageBitmap()
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.size(size),
                alpha = if (dim) 0.45f else 1f)
        } else {
            Box(
                Modifier.size(size * 0.82f).clip(RoundedCornerShape(size * 0.26f))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** Empty slot shown where an app has no icon in the theme. */
@Composable
fun MissingSlot(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).padding(size * 0.09f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold,
            style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------ pieces

@Composable
fun CoverageRing(coverage: Coverage, size: Dp = 92.dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        CircularProgressIndicator(
            progress = { coverage.fraction },
            modifier = Modifier.size(size),
            strokeWidth = if (size < 72.dp) 6.dp else 9.dp,
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text("${coverage.percent}%", style = if (size < 72.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatRow(coverage: Coverage, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("${coverage.total}", "Apps")
        Stat("${coverage.themed}", "Themed")
        Stat("${coverage.missing}", "Missing", if (coverage.missing > 0) MaterialTheme.colorScheme.error else null)
        Stat("${coverage.percent}%", "Coverage")
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

/** Choose how adaptive icons are cut. [sample] is shown in each shape. */
@Composable
fun ShapeDialog(current: IconShape, sample: IconSrc?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Icon shape") },
        text = {
            Column {
                Text(
                    "Applies to adaptive icons (most apps' own icons and Material You style packs). " +
                        "Regular pack icons keep their designed shape. Useful on ColorOS / OxygenOS, whose launchers can't change it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(h = 12.dp)
                IconShape.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (s == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onPick(s.name) }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconImage(sample, 40.dp, shape = s)
                        Gap(w = 14.dp)
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

