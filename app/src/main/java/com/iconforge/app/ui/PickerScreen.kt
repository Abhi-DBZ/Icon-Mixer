package com.iconforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.iconforge.app.core.IconShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.iconforge.app.app
import com.iconforge.app.core.IconSrc

private data class Candidate(val pack: String, val packLabel: String, val drawable: String)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PickerScreen(main: MainViewModel, nav: NavHostController, themeId: Long, keys: List<String>) {
    val ctx = LocalContext.current
    val vm: PickerViewModel = viewModel(key = "picker-$themeId-${keys.hashCode()}") { PickerViewModel(ctx.app, themeId, keys) }
    val targets by vm.targets.collectAsState()
    val mapping by vm.mapping.collectAsState()
    val packs by vm.packs.collectAsState()
    val selected by vm.selectedPack.collectAsState()
    val query by vm.query.collectAsState()
    val icons by vm.icons.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val exact by vm.exactMatches.collectAsState()
    val neighbours by vm.neighbours.collectAsState()
    val category by vm.category.collectAsState()
    val loadedPack by vm.loaded.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pickerTheme by vm.theme.collectAsState()
    val shape = IconShape.of(pickerTheme?.iconShape)
    var preview by remember { mutableStateOf<Candidate?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    val app = targets.firstOrNull()
    val done: () -> Unit = { nav.popBackStack() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.useImage(uri) { ok -> if (ok) done() else main.say("Couldn't read that image") }
    }
    val packLabel = packs.firstOrNull { it.packageName == selected }?.label ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = done) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (app != null) Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconImage(IconSrc.original(app), 32.dp)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(horizontal = 4.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (mapping == null) MissingSlot(32.dp) else IconImage(IconSrc.of(mapping, app), 32.dp)
                    }
                },
                title = {
                    Column {
                        Text("Choose icon")
                        Text(
                            if (targets.size > 1) "for ${targets.size} apps" else app?.label ?: "",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { pad ->
        val gridState = rememberLazyGridState()
        var searchFocused by remember { mutableStateOf(false) }
        // While searching, the header collapses so results sit right under the search box.
        val searching = searchFocused || query.isNotBlank()
        LaunchedEffect(query, selected) { if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0) }

        // imePadding: the grid ends at the top of the keyboard instead of running under it.
      CompositionLocalProvider(LocalIconShape provides shape) {
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            // One row of actions (icon + short label) instead of chips that wrap onto several lines.
            AnimatedVisibility(visible = !searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PickerAction(Icons.Outlined.AutoFixHigh, "Generate", enabled = packs.isNotEmpty()) { vm.generate(done) }
                    PickerAction(Icons.Outlined.Tune, "Adjust", enabled = mapping != null) { adjusting = true }
                    PickerAction(Icons.Outlined.Restore, "Original") { vm.keepOriginal(done) }
                    PickerAction(Icons.Outlined.Image, "Image") {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    PickerAction(Icons.Outlined.RemoveCircleOutline, "Remove", enabled = mapping != null) { vm.clear(done) }
                }
            }

            // Pinned pack chooser + search: always visible above the results.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                packs.forEach { p ->
                    FilterChip(selected == p.packageName, { vm.selectedPack.value = p.packageName; vm.category.value = null }, { Text(p.label) })
                }
            }
            SearchField(
                query, { vm.query.value = it }, "Search icons in $packLabel",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).onFocusChanged { searchFocused = it.hasFocus },
            )
            // The pack's own groups (e.g. "System", "Google"), when it defines them.
            val cats = loadedPack?.categories?.keys?.toList().orEmpty()
            if (cats.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(category == null, { vm.category.value = null }, { Text("All") })
                    cats.forEach { c -> FilterChip(category == c, { vm.category.value = if (category == c) null else c }, { Text(c) }) }
                }
            }

            val pack = selected
            val showExact = exact.isNotEmpty() && !searching
            val showSuggest = pack != null && query.isBlank() && suggestions.isNotEmpty()
            // A–Z sections (only when browsing; search results stay one list).
            val sections = remember(icons, query) {
                if (query.isNotBlank()) emptyList()
                else icons.groupBy { com.iconforge.app.core.IconNames.pretty(it).firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }
                    .toList().sortedBy { (k, _) -> if (k == '#') '~' else k }
            }
            val headerIndex = remember(sections, showExact, showSuggest, exact.size, suggestions.size) {
                var i = (if (showExact) 1 + exact.size else 0) + (if (showSuggest) 1 + suggestions.size else 0) + 1
                sections.associate { (letter, list) -> letter to i.also { i += 1 + list.size } }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(68.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = if (sections.size > 1) 28.dp else 12.dp, bottom = 24.dp),
                ) {
                    // Icons other packs have for exactly this app
                    if (showExact) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Made for this app") }
                        items(exact, key = { "x:${it.pack.packageName}:${it.drawable}" }) { m ->
                            IconCell(IconSrc.Pack(m.pack.packageName, m.drawable), m.pack.label) {
                                preview = Candidate(m.pack.packageName, m.pack.label, m.drawable)
                            }
                        }
                    }
                    if (showSuggest && pack != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Suggested") }
                        items(suggestions, key = { "s:$pack:$it" }) { d ->
                            IconCell(IconSrc.Pack(pack, d), com.iconforge.app.core.IconNames.pretty(d)) { preview = Candidate(pack, packLabel, d) }
                        }
                    }
                    if (pack != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle(if (query.isBlank()) "All icons · ${icons.size}" else "${icons.size} results")
                        }
                        if (sections.isEmpty()) {
                            items(icons, key = { "a:$pack:$it" }) { d ->
                                IconCell(IconSrc.Pack(pack, d), com.iconforge.app.core.IconNames.pretty(d)) { preview = Candidate(pack, packLabel, d) }
                            }
                        } else sections.forEach { (letter, list) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h:$pack:$letter") {
                                Text(letter.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp))
                            }
                            items(list, key = { "a:$pack:$it" }) { d ->
                                IconCell(IconSrc.Pack(pack, d), com.iconforge.app.core.IconNames.pretty(d)) { preview = Candidate(pack, packLabel, d) }
                            }
                        }
                    }
                }
                if (sections.size > 1) {
                    // Letter under the top of the list.
                    val first by remember { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex } }
                    val current = headerIndex.entries.lastOrNull { it.value <= first + 1 }?.key ?: sections.first().first
                    val letters = sections.map { it.first }
                    var touchY by remember { mutableStateOf<Float?>(null) }        // finger on the rail (rail px)
                    var touchLetter by remember { mutableStateOf<Char?>(null) }
                    var railTop by remember { mutableStateOf(0f) }
                    var railSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                    LetterRail(
                        letters = letters,
                        current = touchLetter ?: current,
                        touched = touchLetter,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
                            .onPlaced { railTop = it.positionInParent().y; railSize = it.size },
                        onTouch = { y, l -> touchY = y; touchLetter = l },
                    ) { letter ->
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        headerIndex[letter]?.let { idx -> scope.launch { gridState.scrollToItem(idx) } }
                    }
                    // Drop-shaped bubble next to the rail: follows the finger, or the current
                    // letter's place on the rail while the list itself is scrolling.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val bubble = 64.dp
                    val y = touchY ?: ((letters.indexOf(current).coerceAtLeast(0) + 0.5f) / letters.size * railSize.height)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = touchY != null || gridState.isScrollInProgress,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
                        exit = androidx.compose.animation.fadeOut(),
                        modifier = Modifier.align(Alignment.TopEnd).offset {
                            val b = with(density) { bubble.roundToPx() }
                            val gap = with(density) { 10.dp.roundToPx() }
                            androidx.compose.ui.unit.IntOffset(-(railSize.width + gap), (railTop + y - b).toInt().coerceAtLeast(0))
                        },
                    ) {
                        // Round on three corners, pointed at the bottom-right (towards the finger).
                        Surface(
                            shape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 0, bottomStartPercent = 50),
                            color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp, modifier = Modifier.size(bubble),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text((touchLetter ?: current).toString(), style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
      }
    }

    val m = mapping
    if (adjusting && m != null && app != null) {
        val baseSrc = IconSrc.of(m.copy(scale = 1f, bgColor = null), app)
        AdjustSheet(baseSrc, m.scale, m.bgColor, shape, onDismiss = { adjusting = false }) { sc, bg ->
            vm.setAdjust(sc, bg); adjusting = false
        }
    }

    preview?.let { c ->
        // Fully expanded + compact, so the action buttons are always on screen.
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { preview = null }, sheetState = sheet) {
          CompositionLocalProvider(LocalIconShape provides shape) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconImage(IconSrc.Pack(c.pack, c.drawable), 72.dp)
                    Gap(w = 14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.drawable.replace('_', ' '), style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(c.packLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Gap(h = 12.dp)
                // Next to icons already in the theme, as on a home screen
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        neighbours.take(2).forEach { n -> HomeIcon(IconSrc.of(n, null), "") }
                        HomeIcon(IconSrc.Pack(c.pack, c.drawable), app?.label ?: "")
                        neighbours.drop(2).forEach { n -> HomeIcon(IconSrc.of(n, null), "") }
                    }
                }
                Gap(h = 14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { vm.apply(c.pack, c.drawable) { preview = null; done() } }, modifier = Modifier.weight(1f)) {
                        Text(if (targets.size > 1) "Use for ${targets.size}" else "Use icon", maxLines = 1)
                    }
                }
                Gap(h = 12.dp)
            }
          }
        }
    }
}

@Composable
private fun HomeIcon(src: IconSrc?, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 56.dp, height = 62.dp)) {
        IconImage(src, 44.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconCell(src: IconSrc, label: String, onClick: () -> Unit) {
    Column(
        Modifier.padding(2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconImage(src, 48.dp)
        Text(label.replace('_', ' '), style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Resize an icon and/or put it on a coloured plate (to blend icons from different packs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustSheet(base: IconSrc?, scale0: Float, bg0: Int?, shape: IconShape, onDismiss: () -> Unit, onApply: (Float, Int?) -> Unit) {
    var scale by remember { mutableStateOf(scale0) }
    var bg by remember { mutableStateOf(bg0) }
    val cs = MaterialTheme.colorScheme
    val swatches = listOf(
        cs.primaryContainer, cs.secondaryContainer, cs.tertiaryContainer, cs.primary, cs.surfaceContainerHighest,
        androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFF202124), androidx.compose.ui.graphics.Color.Black,
        androidx.compose.ui.graphics.Color(0xFFE8F0FE), androidx.compose.ui.graphics.Color(0xFFFCE8E6), androidx.compose.ui.graphics.Color(0xFFE6F4EA),
    ).map { it.toArgb() }.distinct()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconImage(base?.let { IconSrc.Adjusted(it, scale, bg) }, 80.dp, shape = shape)
                Gap(w = 16.dp)
                Column(Modifier.weight(1f)) {
                    Text("Adjust icon", style = MaterialTheme.typography.titleMedium)
                    Text("Make icons from different packs look alike.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Gap(h = 12.dp)
            Text("Size · ${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(value = scale, onValueChange = { scale = (it * 20).toInt() / 20f }, valueRange = 0.5f..1.3f)
            Text("Background plate", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .border(2.dp, if (bg == null) cs.primary else cs.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                        .clickable { bg = null },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Block, "None", Modifier.size(18.dp)) }
                swatches.forEach { c ->
                    Box(
                        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color(c))
                            .border(if (bg == c) 3.dp else 1.dp, if (bg == c) cs.primary else cs.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                            .clickable { bg = c },
                    )
                }
            }
            Gap(h = 8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { scale = 1f; bg = null }, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = { onApply(scale, bg) }, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
            Gap(h = 12.dp)
        }
    }
}

/**
 * A–Z index on the right edge: touch or slide along it to jump to a letter. Reports the
 * finger's position ([onTouch], rail px + letter; null on release) for the drop bubble.
 */
@Composable
private fun LetterRail(
    letters: List<Char>, current: Char, touched: Char?, modifier: Modifier = Modifier,
    onTouch: (Float?, Char?) -> Unit, onLetter: (Char) -> Unit,
) {
    var height by remember { mutableStateOf(1) }
    var last by remember { mutableStateOf<Char?>(null) }
    fun touch(y: Float) {
        val l = letters[(y / height * letters.size).toInt().coerceIn(0, letters.size - 1)]
        onTouch(y.coerceIn(0f, height.toFloat()), l)
        if (l != last) { last = l; onLetter(l) }
    }
    Column(
        modifier
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                // One gesture handler for press + slide, so the bubble doesn't flicker
                // when a tap turns into a drag.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume(); touch(down.position.y)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume(); touch(ch.position.y)
                    }
                    last = null; onTouch(null, null)
                }
            }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { l ->
            val on = l == current
            val big = l == touched
            // Scaled in a layer, so the rail's height (and the letters under the finger) never move.
            Text(l.toString(), style = MaterialTheme.typography.labelSmall,
                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp).graphicsLayer { val k = if (big) 1.5f else 1f; scaleX = k; scaleY = k })
        }
    }
}


/** Small round action button with its label underneath (Choose icon header). */
@Composable
private fun PickerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
