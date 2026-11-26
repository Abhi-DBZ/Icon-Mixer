package com.iconforge.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class TourPage(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    TourPage(
        Icons.Outlined.Palette, "Start from an icon pack",
        "Icon Mixer reads the icon packs installed on your phone. Any pack that works with Nova or Lawnchair will do.",
    ),
    TourPage(
        Icons.Outlined.AutoFixHigh, "Make it your theme",
        "A theme takes every icon your pack has, then helps with the rest: pick icons from other packs or your gallery, or let Auto-fill make matching ones.",
    ),
    TourPage(
        Icons.Outlined.InstallMobile, "Export and apply",
        "Export builds your theme into its own icon pack app. Install it, choose it in your launcher, and new apps get icons as you install them.",
    ),
)

/** Three short pages shown on first launch (and again from Settings). */
@Composable
fun TourScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val last = pager.currentPage == pages.lastIndex
    androidx.activity.compose.BackHandler { onDone() }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text("Skip") } else Box(Modifier.height(48.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val p = pages[i]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(132.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(p.icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(60.dp))
                    }
                    Gap(h = 32.dp)
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Gap(h = 12.dp)
                    Text(p.body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { i ->
                        val on = i == pager.currentPage
                        val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dot")
                        val c by animateColorAsState(if (on) cs.primary else cs.outlineVariant, label = "dotColour")
                        Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
                    }
                }
                Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(if (last) "Get started" else "Next")
                }
            }
        }
    }
}
