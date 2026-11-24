package com.iconforge.app.ui

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.iconforge.app.app
import com.iconforge.app.core.IconShape
import com.iconforge.app.core.IconSrc
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The theme as a home screen. Android doesn't let apps read the wallpaper image any more,
 * so the background is a gradient of the wallpaper's colours, or a photo you pick.
 */
@Composable
fun PreviewScreen(main: MainViewModel, nav: NavHostController, themeId: Long) {
    val ctx = LocalContext.current
    val a = ctx.app
    val apps by main.apps.collectAsState()
    val themes by main.themes.collectAsState()
    val theme = themes.firstOrNull { it.id == themeId }
    val maps by a.db.mappings().observe(themeId).collectAsState(initial = emptyList())
    val byKey = remember(maps) { maps.associateBy { it.componentKey } }
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var highlight by rememberSaveable { mutableStateOf(false) }
    var bgVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val bgFile = remember { File(ctx.filesDir, "preview_background.jpg") }

    val photo by produceState<ImageBitmap?>(null, bgVersion) {
        value = withContext(Dispatchers.IO) {
            if (!bgFile.exists()) null else runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(bgFile.path, o)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val wallColors = remember {
        if (Build.VERSION.SDK_INT >= 27) runCatching {
            WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { wc ->
                listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor).map { Color(it.toArgb()) }
            }
        }.getOrNull() else null
    }
    val gradient = (wallColors?.takeIf { it.isNotEmpty() } ?: listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))
        .let { if (it.size == 1) it + it else it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> bgFile.outputStream().use { i.copyTo(it) } } }
            }
            bgVersion++
        }
    }

    val shape = IconShape.of(theme?.iconShape)
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    val dock = remember(sorted, byKey) { sorted.filter { it.componentKey in byKey }.take(columns) }
    val label = TextStyle(color = Color.White, fontSize = 11.sp, shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f))

    CompositionLocalProvider(LocalIconShape provides shape) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(gradient))) {
            photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                    Text(theme?.name ?: "", style = MaterialTheme.typography.titleMedium.merge(label.copy(fontSize = 18.sp)),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { highlight = !highlight }) {
                        Icon(Icons.Outlined.ReportProblem, "Highlight missing", tint = if (highlight) Color(0xFFFFB4AB) else Color.White)
                    }
                    IconButton(onClick = { columns = if (columns == 4) 5 else 4 }) { Icon(Icons.Outlined.GridView, "Columns", tint = Color.White) }
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.Image, "Background photo", tint = Color.White)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(sorted, key = { it.componentKey }) { app ->
                        val m = byKey[app.componentKey]
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                // An app without an icon shows its normal icon, as on the real home screen.
                                IconImage(IconSrc.of(m, app) ?: IconSrc.original(app), if (columns == 4) 56.dp else 48.dp,
                                    shape = if (m == null) IconShape.DEFAULT else shape)
                                if (highlight && m == null) Box(
                                    Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Color(0xFFE53935)),
                                )
                            }
                            Text(app.label, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                        }
                    }
                }
                // Dock
                Row(
                    Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.18f)).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    dock.forEach { app -> IconImage(IconSrc.of(byKey[app.componentKey], app), 52.dp) }
                }
            }
        }
    }
}
