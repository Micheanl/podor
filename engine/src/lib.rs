mod adjustment_preview;
mod adjustments;
mod blending;
mod canvas;
mod clipboard;
mod color_selection;
mod dab;
mod ffi;
mod gradient;
mod history;
mod import_layer;
#[cfg(not(target_os = "ios"))]
mod jni_bridge;
mod layers;
pub mod model;
mod openraster;
mod palette;
mod previews;
mod psd;
mod raster;
mod resample;
mod selection;
mod smudge;
mod stabilizer;
mod storage;
mod transform;
mod translation;
pub use adjustment_preview::{AdjustmentKind, AdjustmentRequest, AdjustmentSettings};
pub use clipboard::CopyMode;
pub use color_selection::ColorSelection;
pub use gradient::{Gradient, GradientShape};
pub use resample::ResampleFilter;
pub use selection::{SelectionKind, SelectionMode, SelectionPoint, SelectionSpec};
pub use storage::{ExportFormat, ExportOptions};
pub use transform::LayerTransform;

use history::{History, Snapshot};
use model::*;
use selection::Selection;
use serde::Deserialize;
use serde_json::{json, Value};
use stabilizer::Stabilizer;
use std::collections::BTreeSet;

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Command {
    Gradient {
        id: u32,
        revision: u64,
        settings: Gradient,
    },
    Select {
        rect: Option<Rect>,
    },
    SelectShape {
        selection: SelectionSpec,
    },
    CombineSelection {
        selection: SelectionSpec,
        mode: SelectionMode,
    },
    InvertSelection,
    SelectColor {
        settings: ColorSelection,
        mode: SelectionMode,
    },
    CutSelection {
        revision: u64,
    },
    Fill {
        x: u32,
        y: u32,
        color: [u8; 4],
        tolerance: u8,
    },
    Tone {
        settings: adjustments::Tone,
    },
    Blur {
        sigma: f32,
    },
    ApplyAdjustment {
        request: AdjustmentRequest,
    },
    Begin {
        brush: Brush,
    },
    End,
    Cancel,
    Undo,
    Redo,
    AddLayer,
    DuplicateLayer {
        id: u32,
    },
    MergeVisible,
    RemoveLayer {
        id: u32,
    },
    SelectLayer {
        id: u32,
    },
    SetLayer {
        id: u32,
        visible: bool,
        opacity: f32,
        name: String,
    },
    SetBlend {
        id: u32,
        mode: BlendMode,
    },
    SetProtection {
        id: u32,
        alpha_locked: Option<bool>,
        locked: Option<bool>,
    },
    MoveLayer {
        id: u32,
        direction: i32,
    },
    ReorderLayer {
        id: u32,
        index: usize,
        revision: u64,
    },
    TranslateLayer {
        id: u32,
        dx: i32,
        dy: i32,
    },
    TransformLayer {
        id: u32,
        revision: u64,
        transform: LayerTransform,
    },
    Clear,
    Pick {
        x: u32,
        y: u32,
    },
    New {
        width: u32,
        height: u32,
    },
    ResizeCanvas {
        width: u32,
        height: u32,
        anchor: u8,
        revision: u64,
    },
    ResizeImage {
        width: u32,
        height: u32,
        #[serde(default)]
        filter: ResampleFilter,
        revision: u64,
    },
    State,
}

struct Stroke {
    before: Document,
    compositor: raster::StrokeCompositor,
    brush: Brush,
    stabilizer: Stabilizer,
    last: Option<Sample>,
    direction: f32,
    distance: f32,
    changed: bool,
    smudge: Option<smudge::Smudge>,
    modified: bool,
}

impl Stroke {
    fn stamp(
        &mut self,
        document: &mut Document,
        selection: Option<&Selection>,
        point: Sample,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<(), String> {
        let brush = self.stamp_brush();
        if let Some(smudge) = &mut self.smudge {
            self.modified |= smudge.stamp(document, selection, brush, point, dirty, remaining)?;
            Ok(())
        } else {
            raster::stamp(document, selection, brush, point, dirty, remaining)
        }
    }

    fn stamp_brush(&self) -> Brush {
        Brush {
            angle: self.brush.angle + self.direction,
            ..self.brush
        }
    }

    fn paint(
        &mut self,
        point: Sample,
        document: &mut Document,
        selection: Option<&Selection>,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<(), String> {
        if let Some(last) = self.last {
            let dx = point.x - last.x;
            let dy = point.y - last.y;
            let length = dx.hypot(dy);
            if length > f32::EPSILON {
                if self.brush.follow_direction {
                    self.direction = dy.atan2(dx).to_degrees();
                }
                if !self.changed {
                    self.stamp(document, selection, last, dirty, remaining)?;
                    self.changed = true;
                }
                let spacing = (self
                    .brush
                    .size_at_pressure(last.pressure.min(point.pressure))
                    * self.brush.spacing)
                    .max(0.5);
                let mut cursor = spacing - self.distance.min(spacing);
                while cursor <= length {
                    let t = cursor / length;
                    self.stamp(
                        document,
                        selection,
                        Sample {
                            x: last.x + dx * t,
                            y: last.y + dy * t,
                            pressure: last.pressure + (point.pressure - last.pressure) * t,
                        },
                        dirty,
                        remaining,
                    )?;
                    self.changed = true;
                    cursor += spacing;
                }
                self.distance = (self.distance + length) % spacing;
            }
        } else if !self.brush.follow_direction {
            self.stamp(document, selection, point, dirty, remaining)?;
            self.changed = true;
        }
        self.last = Some(point);
        Ok(())
    }
}

pub struct Engine {
    pub document: Document,
    history: History,
    dirty: BTreeSet<TileKey>,
    transparent_frame: bool,
    stroke: Option<Stroke>,
    revision: u64,
    content_id: u64,
    selection: Option<Selection>,
    selection_id: u64,
    preview_revision: Option<u64>,
    preview_job: Option<std::thread::JoinHandle<(u64, Vec<u8>)>>,
}

impl Engine {
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        Ok(Self {
            document: Document::new(width, height)?,
            history: History::default(),
            dirty: BTreeSet::new(),
            transparent_frame: false,
            stroke: None,
            revision: 0,
            content_id: 0,
            selection: None,
            selection_id: 0,
            preview_revision: None,
            preview_job: None,
        })
    }

    pub fn command(&mut self, command: Command) -> Result<Value, String> {
        match command {
            Command::Begin { brush } => {
                if self.stroke.is_some() {
                    return Err("已有进行中的笔画".into());
                }
                if !self.document.active_mut().visible {
                    return Err("请先显示当前图层".into());
                }
                let layer = self.document.active_mut();
                if layer.locked {
                    return Err("图层已锁定，请先解锁".into());
                }
                if layer.alpha_locked && brush.eraser {
                    return Err("请先解除透明度锁定".into());
                }
                let brush = brush.validate()?;
                self.stroke = Some(Stroke {
                    before: self.document.clone(),
                    compositor: raster::StrokeCompositor::new(
                        &self.document,
                        self.transparent_frame,
                    ),
                    brush,
                    stabilizer: Stabilizer::new(brush.stabilization),
                    last: None,
                    direction: 0.0,
                    distance: 0.0,
                    changed: false,
                    smudge: if brush.smudge {
                        Some(smudge::Smudge::new(brush)?)
                    } else {
                        None
                    },
                    modified: false,
                });
            }
            Command::End => {
                if let Some(stroke) = self.stroke.as_mut() {
                    let tail = stroke.stabilizer.finish();
                    let mut remaining = (MAX_DOCUMENT_BYTES / TILE_BYTES)
                        .saturating_sub(self.document.tile_count());
                    if let Some(point) = tail {
                        stroke.paint(
                            point,
                            &mut self.document,
                            self.selection.as_ref(),
                            &mut self.dirty,
                            &mut remaining,
                        )?;
                    }
                    if let Some(point) = stroke.last {
                        if !stroke.changed
                            || ((tail.is_some()
                                || stroke.brush.follow_direction
                                || stroke.brush.smudge)
                                && stroke.distance > f32::EPSILON)
                        {
                            stroke.stamp(
                                &mut self.document,
                                self.selection.as_ref(),
                                point,
                                &mut self.dirty,
                                &mut remaining,
                            )?;
                            stroke.changed = true;
                        }
                    }
                }
                if let Some(stroke) = self.stroke.take() {
                    if stroke.changed
                        && (stroke.smudge.is_none() || stroke.modified)
                        && !self.selection.as_ref().is_some_and(Selection::is_empty)
                    {
                        self.history
                            .push(stroke.before, self.content_id, &self.document, true);
                        self.revision += 1;
                        self.content_id = self.revision;
                    }
                }
            }
            Command::Cancel => {
                if let Some(stroke) = self.stroke.take() {
                    self.mark_all();
                    self.document = stroke.before;
                    self.mark_all();
                }
            }
            Command::State => {}
            Command::Pick { x, y } => {
                if x >= self.document.width || y >= self.document.height {
                    return Err("取色位置超出画布".into());
                }
                let tile = raster::composite_tile(&self.document, (x / TILE_SIZE, y / TILE_SIZE));
                let i = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                return Ok(json!({"color": &tile[i..i+3]}));
            }
            command => {
                if self.stroke.is_some() {
                    return Err("请先结束当前笔画".into());
                }
                match command {
                    Command::Gradient {
                        id,
                        revision,
                        settings,
                    } => {
                        if id != self.document.active || revision != self.revision {
                            return Err("画布已改变，请重新绘制渐变".into());
                        }
                        let tiles =
                            gradient::prepare(&self.document, self.selection.as_ref(), settings)?;
                        let index = self.layer_index(id)?;
                        let changed: Vec<_> = tiles
                            .iter()
                            .filter_map(|(key, tile)| {
                                self.document.layers[index]
                                    .tiles
                                    .get(key)
                                    .is_none_or(|old| !std::sync::Arc::ptr_eq(old, tile))
                                    .then_some(*key)
                            })
                            .collect();
                        if !changed.is_empty() {
                            let before = self.document.clone();
                            self.dirty.extend(changed);
                            self.document.layers[index].tiles = tiles;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::Select { rect } => {
                        let selection = rect
                            .map(|rect| Selection::rectangle(rect, self.document.bounds()))
                            .transpose()?;
                        self.set_selection(selection);
                    }
                    Command::SelectShape { selection } => {
                        self.set_selection(Some(Selection::new(
                            selection,
                            self.document.bounds(),
                        )?));
                    }
                    Command::CombineSelection { selection, mode } => {
                        let next = Selection::new(selection, self.document.bounds())?;
                        self.set_selection(Some(Selection::combine(
                            self.selection.as_ref(),
                            next,
                            mode,
                        )?));
                    }
                    Command::InvertSelection => {
                        let selection = self.selection.as_ref().ok_or("请先创建选区")?;
                        self.set_selection(Some(selection.invert(self.document.bounds())));
                    }
                    Command::SelectColor { settings, mode } => {
                        let next = color_selection::select(&self.document, settings)?;
                        self.set_selection(Some(Selection::combine(
                            self.selection.as_ref(),
                            next,
                            mode,
                        )?));
                    }
                    Command::CutSelection { revision } => {
                        if revision != self.revision {
                            return Err("画布已改变，请重试".into());
                        }
                        let (layer, dirty) =
                            clipboard::cut(&self.document, self.selection.as_ref())?;
                        if !dirty.is_empty() {
                            let before = self.document.clone();
                            *self.document.active_mut() = layer;
                            self.dirty.extend(dirty);
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::New { width, height } => {
                        let document = Document::new(width, height)?;
                        self.mark_all();
                        self.document = document;
                        self.history = History::default();
                        self.selection = None;
                        self.revision += 1;
                        self.content_id = self.revision;
                    }
                    Command::Undo => {
                        if let Some(previous) = self.history.undo.pop_back() {
                            if (previous.document.width, previous.document.height)
                                != (self.document.width, self.document.height)
                            {
                                self.selection = None;
                            }
                            if previous.pixels_changed {
                                self.mark_all();
                            }
                            self.history.redo.push(Snapshot {
                                document: std::mem::replace(&mut self.document, previous.document),
                                content_id: self.content_id,
                                pixels_changed: previous.pixels_changed,
                            });
                            self.content_id = previous.content_id;
                            if previous.pixels_changed {
                                self.mark_all();
                            }
                            self.revision += 1;
                        }
                    }
                    Command::Redo => {
                        if let Some(next) = self.history.redo.pop() {
                            if (next.document.width, next.document.height)
                                != (self.document.width, self.document.height)
                            {
                                self.selection = None;
                            }
                            if next.pixels_changed {
                                self.mark_all();
                            }
                            self.history.undo.push_back(Snapshot {
                                document: std::mem::replace(&mut self.document, next.document),
                                content_id: self.content_id,
                                pixels_changed: next.pixels_changed,
                            });
                            self.content_id = next.content_id;
                            if next.pixels_changed {
                                self.mark_all();
                            }
                            self.revision += 1;
                        }
                    }
                    Command::SelectLayer { id } => {
                        if !self.document.layers.iter().any(|layer| layer.id == id) {
                            return Err("图层不存在".into());
                        }
                        self.document.active = id;
                    }
                    Command::ResizeCanvas {
                        width,
                        height,
                        anchor,
                        revision,
                    } => {
                        if revision != self.revision {
                            return Err("画布已变化，请重新调整尺寸".into());
                        }
                        if anchor >= 9 {
                            return Err("画布定位无效".into());
                        }
                        if (width, height) != (self.document.width, self.document.height) {
                            let resized = canvas::resize(&self.document, width, height, anchor)?;
                            let before = std::mem::replace(&mut self.document, resized);
                            self.dirty.clear();
                            self.mark_all();
                            self.selection = None;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::ResizeImage {
                        width,
                        height,
                        filter,
                        revision,
                    } => {
                        if revision != self.revision {
                            return Err("画布已变化，请重新调整尺寸".into());
                        }
                        if (width, height) != (self.document.width, self.document.height) {
                            let resized = resample::resize(&self.document, width, height, filter)?;
                            let before = std::mem::replace(&mut self.document, resized);
                            self.dirty.clear();
                            self.mark_all();
                            self.selection = None;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::TranslateLayer { id, dx, dy } => {
                        if self.selection.is_some() {
                            return Err("请先取消选区，再移动图层".into());
                        }
                        let tiles = translation::translate(&self.document, id, dx, dy)?;
                        let index = self.layer_index(id)?;
                        if tiles != self.document.layers[index].tiles {
                            let before = self.document.clone();
                            self.dirty.extend(self.document.layers[index].tiles.keys());
                            self.dirty.extend(tiles.keys());
                            self.document.layers[index].tiles = tiles;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::TransformLayer {
                        id,
                        revision,
                        transform,
                    } => {
                        if revision != self.revision || id != self.document.active {
                            return Err("图层已变化，请重新开始变换".into());
                        }
                        if self.selection.is_some() {
                            return Err("请先取消选区，再变换图层".into());
                        }
                        let tiles = transform::prepare(&self.document, id, transform)?;
                        let index = self.layer_index(id)?;
                        if tiles != self.document.layers[index].tiles {
                            let before = self.document.clone();
                            self.dirty.extend(self.document.layers[index].tiles.keys());
                            self.dirty.extend(tiles.keys());
                            self.document.layers[index].tiles = tiles;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::ReorderLayer {
                        id,
                        index,
                        revision,
                    } => {
                        if revision != self.revision || index >= self.document.layers.len() {
                            return Err("图层已变化，请重新排序".into());
                        }
                        let source = self.layer_index(id)?;
                        if source != index {
                            let before = self.document.clone();
                            for layer in
                                &self.document.layers[source.min(index)..=source.max(index)]
                            {
                                self.dirty.extend(layer.tiles.keys());
                            }
                            let layer = self.document.layers.remove(source);
                            self.document.layers.insert(index, layer);
                            self.document.active = id;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::ApplyAdjustment { request } => {
                        let (document, dirty) = adjustment_preview::prepare(
                            &self.document,
                            self.revision,
                            self.selection.as_ref(),
                            request,
                        )?;
                        let before_layer = self.document.active_mut();
                        let after_layer = document
                            .layers
                            .iter()
                            .find(|layer| layer.id == document.active)
                            .unwrap();
                        if !dirty.is_empty()
                            || before_layer.opacity != after_layer.opacity
                            || before_layer.blend != after_layer.blend
                        {
                            let before = std::mem::replace(&mut self.document, document);
                            self.dirty.extend(dirty);
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    command => {
                        if self.selection.as_ref().is_some_and(Selection::is_empty)
                            && matches!(
                                command,
                                Command::Fill { .. } | Command::Tone { .. } | Command::Blur { .. }
                            )
                        {
                            return Ok(self.state());
                        }
                        let pixels_changed = !matches!(command, Command::SetProtection { .. });
                        let before = self.document.clone();
                        if let Err(error) = self
                            .edit_layers(command)
                            .and_then(|_| self.document.validate())
                        {
                            self.document = before;
                            return Err(error);
                        }
                        if pixels_changed {
                            for layer in &before.layers {
                                self.dirty.extend(layer.tiles.keys());
                            }
                            self.mark_all();
                        }
                        self.history
                            .push(before, self.content_id, &self.document, pixels_changed);
                        self.revision += 1;
                        self.content_id = self.revision;
                    }
                }
            }
        }
        Ok(self.state())
    }

    fn edit_layers(&mut self, command: Command) -> Result<(), String> {
        let bounds = self.document.bounds();
        let selection = self.selection.as_ref();
        let region = selection.map_or(bounds, Selection::bounds);
        if matches!(
            command,
            Command::Fill { .. } | Command::Tone { .. } | Command::Blur { .. } | Command::Clear
        ) {
            let layer = self.document.active_mut();
            if layer.locked {
                return Err("图层已锁定，请先解锁".into());
            }
            if layer.alpha_locked && matches!(command, Command::Clear) {
                return Err("请先解除透明度锁定".into());
            }
        }
        match command {
            Command::Fill {
                x,
                y,
                color,
                tolerance,
            } => adjustments::fill(
                self.document.active_mut(),
                region,
                selection,
                x,
                y,
                color,
                tolerance,
            )?,
            Command::Tone { settings } => {
                adjustments::tone(self.document.active_mut(), region, selection, settings)?
            }
            Command::Blur { sigma } => {
                adjustments::blur(self.document.active_mut(), bounds, region, selection, sigma)?
            }
            Command::AddLayer => {
                if self.document.layers.len() >= MAX_LAYERS {
                    return Err("已达到图层上限".into());
                }
                let id = self.document.next_id;
                self.document.next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
                let index = self
                    .document
                    .layers
                    .iter()
                    .position(|layer| layer.id == self.document.active)
                    .unwrap()
                    + 1;
                self.document
                    .layers
                    .insert(index, Layer::new(id, format!("图层 {id}")));
                self.document.active = id;
            }
            Command::DuplicateLayer { id } => layers::duplicate(&mut self.document, id)?,
            Command::MergeVisible => layers::merge_visible(&mut self.document)?,
            Command::RemoveLayer { id } => {
                if self.document.layers.len() <= 1 {
                    return Err("至少保留一个图层".into());
                }
                let index = self.layer_index(id)?;
                if self.document.layers[index].locked {
                    return Err("图层已锁定，请先解锁".into());
                }
                self.document.layers.remove(index);
                if self.document.active == id {
                    self.document.active = self.document.layers[index.saturating_sub(1)].id;
                }
            }
            Command::SetLayer {
                id,
                visible,
                opacity,
                name,
            } => {
                if !opacity.is_finite()
                    || !(0.0..=1.0).contains(&opacity)
                    || name.is_empty()
                    || name.len() > MAX_LAYER_NAME_BYTES
                {
                    return Err("图层属性无效".into());
                }
                let index = self.layer_index(id)?;
                let layer = &mut self.document.layers[index];
                layer.visible = visible;
                layer.opacity = opacity;
                layer.name = name;
            }
            Command::SetBlend { id, mode } => {
                let index = self.layer_index(id)?;
                self.document.layers[index].blend = mode;
            }
            Command::SetProtection {
                id,
                alpha_locked,
                locked,
            } => {
                let index = self.layer_index(id)?;
                let layer = &mut self.document.layers[index];
                if let Some(value) = alpha_locked {
                    layer.alpha_locked = value;
                }
                if let Some(value) = locked {
                    layer.locked = value;
                }
            }
            Command::MoveLayer { id, direction } => {
                let index = self.layer_index(id)?;
                let target = (index as i64 + i64::from(direction.signum()))
                    .clamp(0, self.document.layers.len() as i64 - 1)
                    as usize;
                self.document.layers.swap(index, target);
            }
            Command::Clear => self.document.active_mut().tiles.clear(),
            _ => return Err("操作无效".into()),
        }
        Ok(())
    }

    fn layer_index(&self, id: u32) -> Result<usize, String> {
        self.document
            .layers
            .iter()
            .position(|layer| layer.id == id)
            .ok_or("图层不存在".into())
    }
    fn mark_all(&mut self) {
        for layer in &self.document.layers {
            self.dirty.extend(layer.tiles.keys());
        }
    }

    pub fn samples(&mut self, samples: &[Sample]) -> Result<(), String> {
        if samples.iter().any(|p| {
            !p.x.is_finite()
                || !p.y.is_finite()
                || !p.pressure.is_finite()
                || p.x.abs() > MAX_DIMENSION as f32 * 2.0
                || p.y.abs() > MAX_DIMENSION as f32 * 2.0
        }) {
            return Err("输入坐标无效".into());
        }
        let mut remaining =
            (MAX_DOCUMENT_BYTES / TILE_BYTES).saturating_sub(self.document.tile_count());
        let stroke = self.stroke.as_mut().ok_or("尚未开始笔画")?;
        for &point in samples {
            let filtered = stroke.stabilizer.push(point);
            stroke.paint(
                filtered,
                &mut self.document,
                self.selection.as_ref(),
                &mut self.dirty,
                &mut remaining,
            )?;
        }
        Ok(())
    }

    pub fn state(&self) -> Value {
        json!({ "width": self.document.width, "height": self.document.height, "active": self.document.active,
            "revision": self.revision, "contentId": self.content_id, "canUndo": !self.history.undo.is_empty(), "canRedo": !self.history.redo.is_empty(), "selection": self.selection, "maxLayers": MAX_LAYERS,
            "layers": self.document.layers.iter().map(|l| json!({"id": l.id, "name": l.name, "visible": l.visible, "opacity": l.opacity, "blend": l.blend, "alphaLocked": l.alpha_locked, "locked": l.locked})).collect::<Vec<_>>() })
    }

    pub fn frame(&mut self) -> Vec<u8> {
        self.frame_with_background(false)
    }

    pub fn frame_with_background(&mut self, transparent: bool) -> Vec<u8> {
        if self.transparent_frame != transparent {
            self.transparent_frame = transparent;
            self.mark_all();
            if let Some(stroke) = self.stroke.as_mut() {
                stroke.compositor = raster::StrokeCompositor::new(&self.document, transparent);
            }
        }
        let mut dirty = std::mem::take(&mut self.dirty);
        dirty.retain(|&(x, y)| {
            x < self.document.width.div_ceil(TILE_SIZE)
                && y < self.document.height.div_ceil(TILE_SIZE)
        });
        let mut output = Vec::with_capacity(16 + dirty.len() * (8 + TILE_BYTES));
        for value in [
            self.document.width,
            self.document.height,
            TILE_SIZE,
            dirty.len() as u32,
        ] {
            output.extend(value.to_le_bytes());
        }
        for key in dirty {
            output.extend(key.0.to_le_bytes());
            output.extend(key.1.to_le_bytes());
            output.extend(if let Some(stroke) = self.stroke.as_mut() {
                stroke.compositor.tile(&self.document, key)
            } else {
                raster::composite_tile_background(&self.document, key, transparent)
            });
        }
        output
    }

    pub fn save(&self) -> Result<Vec<u8>, String> {
        storage::save(&self.document)
    }
    pub fn preview_adjustment(&self, request: AdjustmentRequest) -> Result<Vec<u8>, String> {
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        let (document, keys) = adjustment_preview::prepare(
            &self.document,
            self.revision,
            self.selection.as_ref(),
            request,
        )?;
        Ok(adjustment_preview::frame(
            &document,
            &keys,
            self.transparent_frame,
        ))
    }
    pub fn import_layer(&mut self, bytes: &[u8], name: &str) -> Result<(), String> {
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        let layer = import_layer::prepare(&self.document, bytes, name)?;
        self.insert_image_layer(layer)
    }
    pub fn copy_selection(&self, mode: CopyMode) -> Result<Vec<u8>, String> {
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        clipboard::copy(&self.document, self.selection.as_ref(), mode)
    }
    pub fn paste_image(&mut self, packet: &[u8]) -> Result<(), String> {
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        let layer = clipboard::paste(&self.document, packet)?;
        self.insert_image_layer(layer)
    }
    fn insert_image_layer(&mut self, layer: Layer) -> Result<(), String> {
        let next_id = self
            .document
            .next_id
            .checked_add(1)
            .ok_or("图层编号超出限制")?;
        let before = self.document.clone();
        let index = self.layer_index(self.document.active)?;
        self.dirty.extend(layer.tiles.keys());
        self.document.active = layer.id;
        self.document.layers.insert(index + 1, layer);
        self.document.next_id = next_id;
        self.selection = None;
        self.history
            .push(before, self.content_id, &self.document, true);
        self.revision += 1;
        self.content_id = self.revision;
        Ok(())
    }
    pub fn layer_frame(&self) -> Vec<u8> {
        translation::frame(&self.document)
    }
    pub fn selection_frame(&self) -> Vec<u8> {
        gradient::selection_frame(self.selection.as_ref())
    }
    fn set_selection(&mut self, mut selection: Option<Selection>) {
        self.selection_id += 1;
        if let Some(selection) = selection.as_mut() {
            selection.identify(self.selection_id);
        }
        self.selection = selection;
    }
    pub fn selection_outline(&self) -> Vec<u8> {
        let Some(selection) = &self.selection else {
            return 0u32.to_le_bytes().to_vec();
        };
        match selection.outline() {
            Some(lines) => {
                let mut bytes = 0u32.to_le_bytes().to_vec();
                for line in lines {
                    for value in line {
                        bytes.extend(value.to_le_bytes());
                    }
                }
                bytes
            }
            None => {
                let mut bytes = 1u32.to_le_bytes().to_vec();
                bytes.extend(selection.outline_mask());
                bytes
            }
        }
    }
    pub fn layer_bounds(&self) -> Result<Rect, String> {
        let layer = self
            .document
            .layers
            .iter()
            .find(|layer| layer.id == self.document.active)
            .unwrap();
        transform::bounds(layer, self.document.bounds())
            .ok_or_else(|| "当前图层没有可变换的内容".into())
    }
    pub fn export_png(&self) -> Result<Vec<u8>, String> {
        storage::export_png(&self.document)
    }
    pub fn export_image(&self, options: ExportOptions) -> Result<Vec<u8>, String> {
        storage::export_image(&self.document, options)
    }
    pub fn thumbnail(&self) -> Vec<u8> {
        previews::thumbnail(&self.document)
    }
    pub fn extract_palette(&self, count: usize) -> Result<Vec<[u8; 3]>, String> {
        if self.stroke.is_some() {
            return Err("请先结束笔画".into());
        }
        palette::extract(&self.document, count)
    }
    pub fn previews(&mut self) -> Result<Vec<u8>, String> {
        if self
            .preview_job
            .as_ref()
            .is_some_and(|job| job.is_finished())
        {
            let (revision, bytes) = self
                .preview_job
                .take()
                .unwrap()
                .join()
                .map_err(|_| "缩略图生成失败")?;
            if revision == self.revision {
                self.preview_revision = Some(revision);
                return Ok(bytes);
            }
        }
        if self.stroke.is_none()
            && self.preview_job.is_none()
            && self.preview_revision != Some(self.revision)
        {
            let document = self.document.clone();
            let revision = self.revision;
            self.preview_job = Some(std::thread::spawn(move || {
                (revision, previews::render(&document, revision))
            }));
        }
        Ok(Vec::new())
    }
    pub fn load(&mut self, bytes: &[u8]) -> Result<(), String> {
        let doc = storage::load(bytes)?;
        self.mark_all();
        self.document = doc;
        self.mark_all();
        self.stroke = None;
        self.history = History::default();
        self.selection = None;
        self.revision += 1;
        self.content_id = self.revision;
        Ok(())
    }
}
