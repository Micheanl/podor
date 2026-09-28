package app.podor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushControls(controller: StudioController) {
    var editing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(controller.brushLibraryQuery.isNotEmpty()) }
    var collections by remember { mutableStateOf(false) }
    val allBrushes = controller.brushes
    val favorites = controller.preferences.favoriteBrushes
    val language = LocalLanguage.current
    val query = controller.brushLibraryQuery.trim()
    val group = controller.brushCollection
    val customs =
        remember(controller.preferences.brushes) {
            controller.preferences.brushes.map { it.id }.toSet()
        }
    val brushes =
        remember(allBrushes, favorites, language, query, group, customs) {
            allBrushes.filter { preset ->
                val matchesGroup =
                    when (group) {
                        BrushCollection.All -> true
                        BrushCollection.Favorites -> preset.id in favorites
                        BrushCollection.Custom -> preset.id in customs
                        BrushCollection.Extensions -> preset.id.startsWith("plugin:")
                    }
                matchesGroup &&
                    (query.isEmpty() ||
                        preset.label.contains(query, ignoreCase = true) ||
                        trValue(preset.label, language).contains(query, ignoreCase = true))
            }
        }
    val smudge = controller.tool == Tool.Smudge
    val strength = if (smudge) controller.smudgeStrength else controller.brush.opacity
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(StudioTheme.cardShape)
                        .background(StudioTheme.background)
                        .border(
                            1.dp,
                            StudioTheme.border.copy(alpha = 0.6f),
                            StudioTheme.cardShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr(controller.brush.preset.label),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        SymmetryControls(controller)
                        ToolButton(Glyph.Adjustments, "编辑笔刷") { editing = true }
                    }
                    BrushStrokePreview(
                        controller.brush.preset.copy(
                            size = controller.brush.size,
                            opacity = 1f,
                        ),
                        Modifier.fillMaxWidth().height(40.dp).graphicsLayer {
                            alpha = strength
                        },
                        if (smudge) StudioTheme.accent else Color(controller.brush.color),
                    )
                }
                Column {
                    LabeledSlider(
                        "大小",
                        controller.brush.size,
                        1f..256f,
                        "${controller.brush.size.roundToInt()} px",
                        tint = Color(controller.brush.color),
                        glyph = Glyph.BrushSize,
                    ) {
                        controller.brush = controller.brush.copy(size = it)
                    }
                    LabeledSlider(
                        if (smudge) "涂抹强度" else "不透明度",
                        strength,
                        0.01f..1f,
                        "${(strength*100).roundToInt()}%",
                        tint = Color(controller.brush.color),
                        glyph = if (smudge) Glyph.Smudge else Glyph.Opacity,
                    ) {
                        if (smudge) controller.smudgeStrength = it
                        else controller.brush = controller.brush.copy(opacity = it)
                    }
                    LabeledSlider(
                        "稳笔",
                        controller.brush.preset.stabilization,
                        0f..1f,
                        "${(controller.brush.preset.stabilization * 100).roundToInt()}%",
                        tint = Color(controller.brush.color),
                        glyph = Glyph.Stabilize,
                    ) {
                        controller.brush =
                            controller.brush.copy(
                                preset = controller.brush.preset.copy(stabilization = it)
                            )
                    }
                }
                HorizontalDivider(color = StudioTheme.border.copy(alpha = 0.5f))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTheme.brushLibraryGap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SectionLabel("笔刷库", "${brushes.size}/${allBrushes.size}")
                    }
                    ToolButton(Glyph.Search, "搜索笔刷", selected = searching, plain = true) {
                        searching = !searching
                        if (!searching) controller.brushLibraryQuery = ""
                    }
                    Box {
                        fun glyph(value: BrushCollection) =
                            when (value) {
                                BrushCollection.All -> Glyph.Folder
                                BrushCollection.Favorites -> Glyph.Favorite
                                BrushCollection.Custom -> Glyph.Brush
                                BrushCollection.Extensions -> Glyph.Plugin
                            }
                        ToolButton(
                            glyph(group),
                            group.label,
                            selected = group != BrushCollection.All,
                            plain = true,
                        ) {
                            collections = !collections
                        }
                        StudioDropdownMenu(
                            collections,
                            { collections = false },
                        ) {
                            BrushCollection.entries.forEach { collection ->
                                DropdownMenuItem(
                                    text = { Text(tr(collection.label)) },
                                    leadingIcon = { StudioIcon(glyph(collection)) },
                                    trailingIcon = {
                                        if (collection == group) StudioIcon(Glyph.Check)
                                    },
                                    onClick = {
                                        controller.brushCollection = collection
                                        collections = false
                                    },
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    searching,
                    enter =
                        expandVertically(
                            tween(StudioMotion.panelMillis, easing = StudioMotion.easing)
                        ) + fadeIn(tween(StudioMotion.feedbackMillis)),
                    exit =
                        shrinkVertically(
                            tween(StudioMotion.dismissMillis, easing = StudioMotion.exitEasing)
                        ) + fadeOut(tween(StudioMotion.feedbackMillis)),
                ) {
                    OutlinedTextField(
                        controller.brushLibraryQuery,
                        {
                            controller.brushLibraryQuery = it.take(StudioDefaults.brushSearchLength)
                        },
                        Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(tr("搜索笔刷"), fontSize = StudioTheme.brushLibraryCaptionSize)
                        },
                        singleLine = true,
                        trailingIcon = {
                            if (controller.brushLibraryQuery.isNotEmpty())
                                ToolButton(Glyph.Close, "清除搜索") {
                                    controller.brushLibraryQuery = ""
                                }
                        },
                    )
                }
            }
        }
        if (brushes.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    tr(
                        if (group == BrushCollection.Favorites && query.isEmpty()) "点亮星标，收藏常用笔刷"
                        else "没有匹配的笔刷"
                    ),
                    Modifier.padding(vertical = StudioTheme.brushLibraryGap),
                    color = StudioTheme.muted,
                    fontSize = StudioTheme.brushLibraryCaptionSize,
                )
            }
        items(brushes, key = { it.id }) { preset ->
            val selected = controller.brush.preset.id == preset.id
            ChoiceSurface(selected, { controller.selectPreset(preset) }) {
                BrushStrokePreview(
                    preset,
                    Modifier.fillMaxWidth().height(42.dp),
                    if (selected) StudioTheme.accent else StudioTheme.muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(preset.label),
                        fontSize = 11.sp,
                        color = if (selected) StudioTheme.accent else StudioTheme.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val favorite = preset.id in favorites
                    val favoriteLabel =
                        tr(if (favorite) "取消收藏笔刷" else "收藏笔刷") + " · " + tr(preset.label)
                    IconToggleButton(
                        favorite,
                        { controller.toggleBrushFavorite(preset.id) },
                        Modifier.size(StudioTheme.brushLibraryFavoriteSize).semantics {
                            contentDescription = favoriteLabel
                        },
                    ) {
                        StudioIcon(
                            if (favorite) Glyph.FavoriteFilled else Glyph.Favorite,
                            if (favorite) StudioTheme.accent
                            else StudioTheme.muted.copy(alpha = 0.45f),
                            Modifier.size(StudioTheme.brushLibraryFavoriteIconSize),
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(Glyph.Hand, StudioTheme.muted, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("手指绘画"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Switch(controller.fingerDrawing, { controller.fingerDrawing = it })
                }
            }
        }
    }
    if (editing) BrushEditor(controller) { editing = false }
}

@Composable
fun BrushStrokePreview(
    preset: BrushPreset,
    modifier: Modifier = Modifier,
    tint: Color = StudioTheme.accent,
) {
    Box(
        modifier.drawWithCache {
            val inset = 8.dp.toPx()
            val width = (size.width - inset * 2).coerceAtLeast(1f)
            val thickness = (preset.size * 0.16f).coerceIn(1.5f, 12f).dp.toPx()
            fun point(t: Float) =
                Offset(inset + width * t, size.height * (0.5f - sin(t * 6.283f) * 0.2f))
            val path =
                Path().apply {
                    val start = point(0f)
                    moveTo(start.x, start.y)
                    for (i in 1..48) {
                        val p = point(i / 48f)
                        lineTo(p.x, p.y)
                    }
                }
            val stroke =
                Stroke(
                    thickness,
                    cap = if (preset.tip == BrushTip.Flat) StrokeCap.Butt else StrokeCap.Round,
                    pathEffect =
                        if (preset.spacing >= 0.5f)
                            PathEffect.dashPathEffect(
                                if (preset.tip == BrushTip.Flat)
                                    floatArrayOf(
                                        thickness * preset.aspect,
                                        thickness * preset.spacing,
                                    )
                                else floatArrayOf(0.1f, thickness * 2.5f)
                            )
                        else null,
                )
            val random = Random(17)
            val grain =
                List((preset.grain * 100).toInt()) {
                    val p = point(random.nextFloat())
                    Offset(p.x, p.y + (random.nextFloat() - 0.5f) * thickness)
                }
            onDrawBehind {
                if (preset.hardness < 0.5f) {
                    drawPath(
                        path,
                        tint.copy(alpha = 0.07f),
                        style = Stroke(thickness * 1.7f, cap = StrokeCap.Round),
                    )
                    drawPath(
                        path,
                        tint.copy(alpha = 0.13f),
                        style = Stroke(thickness * 1.3f, cap = StrokeCap.Round),
                    )
                }
                drawPath(path, tint.copy(alpha = 0.4f + preset.opacity * 0.5f), style = stroke)
                grain.forEach {
                    drawCircle(StudioTheme.panel.copy(alpha = 0.65f), 0.65.dp.toPx(), it)
                }
            }
        }
    )
}

@Composable
private fun BrushEditor(controller: StudioController, onDismiss: () -> Unit) {
    var pressure by remember { mutableStateOf(false) }
    var saveCopy by remember { mutableStateOf(false) }
    val custom = controller.preferences.brushes.any { it.id == controller.brush.preset.id }
    val replace = custom && !saveCopy
    var name by remember {
        mutableStateOf(trValue(controller.brush.preset.label, controller.preferences.language))
    }
    val preset = controller.brush.preset
    fun update(value: BrushPreset) {
        controller.brush = controller.brush.copy(preset = value)
    }
    StudioAlertDialog(
        onDismissRequest = onDismiss,
        title = "编辑笔刷",
        glyph = Glyph.Brush,
        confirmLabel = if (replace) "保存笔刷" else "保存为新笔刷",
        cancelLabel = "完成",
        enabled =
            name.isNotBlank() &&
                (replace || controller.preferences.brushes.size < StudioDefaults.maxCustomBrushes),
        onConfirm = { controller.saveBrush(name, replace = replace) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushLibraryGap),
                ) {
                    OutlinedTextField(
                        name,
                        { name = it.take(60) },
                        Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(tr("名称")) },
                    )
                    if (custom)
                        ToolButton(Glyph.Copy, "另存笔刷副本", selected = saveCopy) {
                            saveCopy = !saveCopy
                        }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTheme.brushSettingsGap)) {
                    listOf(false to "笔尖", true to "压感").forEach { (tab, label) ->
                        FilterChip(pressure == tab, { pressure = tab }, label = { Text(tr(label)) })
                    }
                }
                if (pressure) {
                    BrushPressureControls(
                        preset,
                        smudge = controller.tool == Tool.Smudge,
                        onChange = ::update,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrushTip.entries.forEach { tip ->
                            FilterChip(
                                preset.tip == tip,
                                { update(preset.copy(tip = tip)) },
                                label = { Text(tr(if (tip == BrushTip.Round) "圆形" else "扁平")) },
                            )
                        }
                    }
                    LabeledSlider(
                        "硬度",
                        preset.hardness,
                        0f..1f,
                        "${(preset.hardness*100).roundToInt()}%",
                    ) {
                        update(preset.copy(hardness = it))
                    }
                    LabeledSlider(
                        "笔尖比例",
                        preset.aspect,
                        0.1f..1f,
                        "${(preset.aspect*100).roundToInt()}%",
                    ) {
                        update(preset.copy(aspect = it))
                    }
                    LabeledSlider(
                        "角度",
                        preset.angle,
                        -180f..180f,
                        "${preset.angle.roundToInt()}°",
                    ) {
                        update(preset.copy(angle = it))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("跟随笔画方向"), Modifier.weight(1f), fontSize = 13.sp)
                        Switch(
                            preset.followDirection,
                            { update(preset.copy(followDirection = it)) },
                        )
                    }
                    LabeledSlider(
                        "颗粒",
                        preset.grain,
                        0f..1f,
                        "${(preset.grain*100).roundToInt()}%",
                    ) {
                        update(preset.copy(grain = it))
                    }
                    LabeledSlider(
                        "间距",
                        preset.spacing,
                        0.02f..1f,
                        "${(preset.spacing*100).roundToInt()}%",
                    ) {
                        update(preset.copy(spacing = it))
                    }
                }
            }
        },
    )
}
