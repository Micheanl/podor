package app.podor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podor.domain.*
import app.podor.presentation.BrushPreviewCache
import app.podor.presentation.StudioController
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    val lasso = controller.tool == Tool.LassoFill
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
                            tr(if (lasso) Tool.LassoFill.label else controller.brush.preset.label),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (!lasso) {
                            SymmetryControls(controller)
                            ToolButton(Glyph.Adjustments, "编辑笔刷") { editing = true }
                        }
                    }
                    if (lasso) LassoFillModes(controller)
                    else
                        BrushStrokePreview(
                            controller.brush.preset.copy(
                                size = controller.brush.size,
                                opacity = strength,
                            ),
                            Modifier.fillMaxWidth().height(40.dp),
                            if (smudge) StudioTheme.accent else Color(controller.brush.color),
                        )
                }
                Column {
                    if (!lasso)
                        LabeledSlider(
                            "大小",
                            controller.brush.size,
                            1f..256f,
                            "${controller.brush.size.roundToInt()} px",
                            tint = Color(controller.brush.color),
                            glyph = Glyph.BrushSize,
                        ) {
                            controller.brush =
                                controller.brush.copy(
                                    size =
                                        if (
                                            controller.brush.preset.raster ==
                                                BrushRaster.Antialiased
                                        )
                                            it
                                        else it.roundToInt().toFloat()
                                )
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
                    if (!lasso && controller.brush.preset.raster == BrushRaster.Antialiased)
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
                    StudioTheme.text.copy(alpha = if (selected) 1f else 0.82f),
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
    val image by
        produceState<ImageBitmap?>(null, preset) {
            delay(StudioDefaults.brushPreviewDebounceMillis)
            value = BrushPreviewCache.get(preset)
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        image?.let {
            Image(
                it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tint),
                filterQuality =
                    if (preset.raster == BrushRaster.Antialiased) FilterQuality.Low
                    else FilterQuality.None,
            )
        }
    }
}

@Composable
private fun BrushEditor(controller: StudioController, onDismiss: () -> Unit) {
    var pressure by remember { mutableStateOf(false) }
    var materials by remember { mutableStateOf(false) }
    var rasterModes by remember { mutableStateOf(false) }
    var saveCopy by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
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
                BrushStrokePreview(
                    preset.copy(size = controller.brush.size, opacity = controller.brush.opacity),
                    Modifier.fillMaxWidth().height(StudioTheme.brushEditorPreviewHeight),
                    Color(controller.brush.color),
                )
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
                            confirmingDelete = false
                        }
                    if (custom)
                        ToolButton(
                            Glyph.Trash,
                            if (confirmingDelete) "确认删除笔刷" else "删除笔刷",
                            selected = confirmingDelete,
                        ) {
                            if (confirmingDelete) {
                                controller.deleteBrush(preset.id)
                                onDismiss()
                            } else {
                                confirmingDelete = true
                                saveCopy = false
                            }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("笔触"), Modifier.weight(1f), fontSize = 13.sp)
                        Box {
                            TextButton(onClick = { rasterModes = !rasterModes }) {
                                Text(tr(preset.raster.label))
                                Spacer(Modifier.width(8.dp))
                                StudioIcon(Glyph.Chevron, modifier = Modifier.size(14.dp))
                            }
                            StudioDropdownMenu(rasterModes, { rasterModes = false }) {
                                BrushRaster.entries.forEach { raster ->
                                    DropdownMenuItem(
                                        text = { Text(tr(raster.label)) },
                                        trailingIcon = {
                                            if (raster == preset.raster) StudioIcon(Glyph.Check)
                                        },
                                        onClick = {
                                            update(preset.copy(raster = raster))
                                            if (raster != BrushRaster.Antialiased)
                                                controller.tool = Tool.Brush
                                            rasterModes = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (preset.raster == BrushRaster.Antialiased) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tr("材质"), Modifier.weight(1f), fontSize = 13.sp)
                            Box {
                                TextButton(onClick = { materials = !materials }) {
                                    Text(tr(preset.texture.label))
                                    Spacer(Modifier.width(8.dp))
                                    StudioIcon(Glyph.Chevron, modifier = Modifier.size(14.dp))
                                }
                                StudioDropdownMenu(materials, { materials = false }) {
                                    BrushTexture.entries.forEach { texture ->
                                        DropdownMenuItem(
                                            text = { Text(tr(texture.label)) },
                                            trailingIcon = {
                                                if (texture == preset.texture)
                                                    StudioIcon(Glyph.Check)
                                            },
                                            onClick = {
                                                update(preset.copy(texture = texture))
                                                materials = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrushTip.entries.forEach { tip ->
                                FilterChip(
                                    preset.tip == tip,
                                    { update(preset.copy(tip = tip)) },
                                    label = {
                                        Text(
                                            tr(
                                                when (tip) {
                                                    BrushTip.Round -> "圆形"
                                                    BrushTip.Flat -> "扁平"
                                                    BrushTip.Leaf -> "柳叶"
                                                    BrushTip.Comb -> "排齿"
                                                }
                                            )
                                        )
                                    },
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
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                            "纸纹",
                            preset.paper,
                            0f..1f,
                            "${(preset.paper*100).roundToInt()}%",
                        ) {
                            update(preset.copy(paper = it))
                        }
                        LabeledSlider(
                            "调色混合",
                            preset.mix,
                            0f..1f,
                            "${(preset.mix*100).roundToInt()}%",
                        ) {
                            update(preset.copy(mix = it))
                        }
                        LabeledSlider(
                            "间距",
                            preset.spacing,
                            0.02f..1f,
                            "${(preset.spacing*100).roundToInt()}%",
                        ) {
                            update(preset.copy(spacing = it))
                        }
                    } else if (preset.raster == BrushRaster.PixelPerfect) {
                        Text(
                            tr("1 px 笔径使用像素完美线条"),
                            color = StudioTheme.muted,
                            fontSize = StudioTheme.brushLibraryCaptionSize,
                        )
                    }
                }
            }
        },
    )
}
