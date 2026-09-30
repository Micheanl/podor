mod adjustment_layers;
mod adjustment_preview;
mod adjustments;
pub mod animation;
mod animation_budget;
mod animation_engine;
mod animation_export;
pub mod aseprite;
pub mod assistants;
mod blending;
mod brush_preview;
mod brush_texture;
mod canvas;
mod clipboard;
mod clipping;
mod color_selection;
mod curves;
mod dab;
mod ffi;
mod flood_fill;
mod gradient;
mod gradient_map;
mod groups;
mod history;
mod import_layer;
mod indexed;
mod indexed_geometry;
mod indexed_png;
#[cfg(not(target_os = "ios"))]
mod jni_bridge;
mod lasso_fill;
mod layer_action;
mod layers;
mod line_generator;
mod mask_resample;
mod mask_stack;
mod masks;
pub mod model;
mod move_preview;
mod openraster;
mod palette;
mod pixel;
mod previews;
mod psd;
mod raster;
mod raster_resample;
mod reference;
mod resample;
mod selection;
mod selection_refinement;
mod smudge;
mod stabilizer;
mod storage;
mod transform;
mod translation;
pub mod vector;
mod vector_svg;
pub use adjustment_layers::{AdjustmentEffect, AdjustmentSpec};
pub use adjustment_preview::{AdjustmentKind, AdjustmentRequest, AdjustmentSettings};
pub use animation_engine::{CommandRequest, FramePreviewsRequest, FrameRenderRequest};
pub use animation_export::AnimationExportRequest;
pub use aseprite::AsepriteExportRequest;
pub use brush_preview::render as brush_preview;
pub use clipboard::CopyMode;
pub use color_selection::ColorSelection;
pub use gradient::{Gradient, GradientShape};
pub use gradient_map::{GradientMap, GradientMapStop};
pub use layer_action::{LayerAction, LayerActionRequest};
pub use line_generator::Settings as LineGeneratorSettings;
pub use masks::MaskMode;
pub use resample::ResampleFilter;
pub use selection::{SelectionKind, SelectionMode, SelectionPoint, SelectionSpec};
pub use selection_refinement::SelectionRefinementKind;
pub use storage::{ExportFormat, ExportOptions, IndexedExportPolicy};
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
    EnableAnimation {
        revision: u64,
        duration_ms: u32,
    },
    AddFrame {
        revision: u64,
        index: usize,
        duration_ms: u32,
        #[serde(default = "default_contiguous")]
        select: bool,
    },
    DuplicateFrame {
        revision: u64,
        frame_id: u32,
        index: usize,
        linked: bool,
        #[serde(default = "default_contiguous")]
        select: bool,
    },
    DeleteFrame {
        revision: u64,
        frame_id: u32,
    },
    ReorderFrames {
        revision: u64,
        ids: Vec<u32>,
    },
    SetFrameDuration {
        revision: u64,
        frame_id: u32,
        duration_ms: u32,
    },
    SelectFrame {
        revision: u64,
        frame_id: u32,
    },
    NewCel {
        revision: u64,
        frame_id: u32,
        layer_id: u32,
    },
    ClearCel {
        revision: u64,
        frame_id: u32,
        layer_id: u32,
        cel_id: u32,
    },
    LinkCel {
        revision: u64,
        frame_id: u32,
        layer_id: u32,
        source_frame_id: u32,
    },
    UnlinkCel {
        revision: u64,
        frame_id: u32,
        layer_id: u32,
        cel_id: u32,
    },
    AddFrameTag {
        revision: u64,
        tag: animation::TagSpec,
    },
    SetFrameTag {
        revision: u64,
        id: u32,
        tag: animation::TagSpec,
    },
    DeleteFrameTag {
        revision: u64,
        id: u32,
    },
    GenerateLines {
        id: u32,
        revision: u64,
        selection_id: u64,
        mask_editing: bool,
        #[serde(default)]
        mask_id: Option<u32>,
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        settings: LineGeneratorSettings,
    },
    AddAssistant {
        assistant: assistants::AssistantSpec,
        revision: u64,
    },
    SetAssistant {
        id: u32,
        assistant: assistants::AssistantSpec,
        revision: u64,
    },
    DeleteAssistant {
        id: u32,
        revision: u64,
    },
    SetAssistantSnap {
        id: Option<u32>,
        revision: u64,
    },
    PreviewAssistant {
        assistant: assistants::AssistantSpec,
        origin: assistants::Point,
        point: assistants::Point,
        revision: u64,
    },
    CreateVector {
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        revision: u64,
    },
    AddVectorObject {
        id: u32,
        object: vector::ObjectSpec,
        #[serde(default)]
        index: Option<usize>,
        revision: u64,
    },
    SetVectorObject {
        id: u32,
        object_id: u32,
        object: vector::ObjectSpec,
        revision: u64,
    },
    DeleteVectorObject {
        id: u32,
        object_id: u32,
        revision: u64,
    },
    ReorderVectorObject {
        id: u32,
        object_id: u32,
        index: usize,
        revision: u64,
    },
    RasterizeVector {
        id: u32,
        revision: u64,
    },
    VectorObjects {
        id: u32,
        revision: u64,
    },
    VectorObject {
        id: u32,
        object_id: u32,
        revision: u64,
    },
    PickVectorObject {
        id: u32,
        x: f32,
        y: f32,
        tolerance: f32,
        revision: u64,
    },
    CreateAdjustment {
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        settings: AdjustmentSpec,
        revision: u64,
        selection_id: u64,
    },
    SetAdjustment {
        id: u32,
        settings: AdjustmentSpec,
        revision: u64,
    },
    CreateGroup {
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        #[serde(default)]
        isolation: GroupIsolation,
        revision: u64,
    },
    GroupLayers {
        ids: Vec<u32>,
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        revision: u64,
    },
    MoveNode {
        id: u32,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        revision: u64,
    },
    Ungroup {
        id: u32,
        revision: u64,
    },
    SetGroupIsolation {
        id: u32,
        isolation: GroupIsolation,
        revision: u64,
    },
    SetGroupClosed {
        id: u32,
        closed: bool,
        revision: u64,
    },
    NewIndexed {
        width: u32,
        height: u32,
        palette: IndexedPalette,
    },
    ConvertColorMode {
        mode: ColorMode,
        #[serde(default)]
        palette: Option<IndexedPalette>,
        revision: u64,
    },
    SetPaletteColor {
        index: u8,
        color: [u8; 4],
        revision: u64,
    },
    AddPaletteColor {
        color: [u8; 4],
        revision: u64,
    },
    ReorderPalette {
        order: Vec<u8>,
        revision: u64,
    },
    RemovePaletteColor {
        index: u8,
        replacement: u8,
        revision: u64,
    },
    FillIndexed {
        x: u32,
        y: u32,
        index: u8,
        tolerance: u8,
        #[serde(default = "default_fill_opacity")]
        opacity: f32,
        #[serde(default = "default_contiguous")]
        contiguous: bool,
        #[serde(default)]
        merged: bool,
    },
    GradientMap {
        id: u32,
        revision: u64,
        #[serde(alias = "selectionId")]
        selection_id: u64,
        settings: GradientMap,
    },
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
    ModifySelection {
        kind: SelectionRefinementKind,
        radius: u32,
        revision: u64,
        selection_id: u64,
    },
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
        #[serde(default = "default_contiguous")]
        contiguous: bool,
        #[serde(default)]
        merged: bool,
    },
    FillLasso {
        points: Vec<SelectionPoint>,
        color: [u8; 3],
        opacity: f32,
        eraser: bool,
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
        #[serde(default)]
        assistant: Option<assistants::StrokeBinding>,
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
    AddMask {
        mode: MaskMode,
        #[serde(default)]
        name: Option<String>,
        #[serde(default)]
        revision: Option<u64>,
        #[serde(default)]
        selection_id: Option<u64>,
    },
    SetMask {
        id: u32,
        #[serde(default)]
        mask_id: Option<u32>,
        #[serde(default)]
        name: Option<String>,
        enabled: Option<bool>,
        linked: Option<bool>,
        #[serde(default)]
        revision: Option<u64>,
    },
    InvertMask {
        id: u32,
        #[serde(default)]
        mask_id: Option<u32>,
        #[serde(default)]
        revision: Option<u64>,
    },
    DeleteMask {
        id: u32,
        #[serde(default)]
        mask_id: Option<u32>,
        #[serde(default)]
        revision: Option<u64>,
    },
    ApplyMask {
        id: u32,
        #[serde(default)]
        revision: Option<u64>,
    },
    DuplicateMask {
        id: u32,
        mask_id: u32,
        revision: u64,
    },
    ReorderMask {
        id: u32,
        mask_id: u32,
        index: usize,
        revision: u64,
    },
    SetMaskEditing {
        #[serde(default)]
        id: Option<u32>,
        enabled: bool,
        #[serde(default)]
        mask_id: Option<u32>,
    },
    SetLayer {
        id: u32,
        visible: bool,
        opacity: f32,
        name: String,
    },
    SetClipping {
        id: u32,
        clipping: bool,
        revision: u64,
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
        #[serde(default)]
        mask_id: Option<u32>,
        id: u32,
        dx: i32,
        dy: i32,
    },
    TransformLayer {
        #[serde(default)]
        mask_id: Option<u32>,
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

fn default_contiguous() -> bool {
    true
}

fn default_fill_opacity() -> f32 {
    1.0
}

struct Stroke {
    mask_editing: bool,
    before: Document,
    compositor: raster::StrokeCompositor,
    brush: Brush,
    stabilizer: Stabilizer,
    assistant: Option<assistants::Constraint>,
    last: Option<Sample>,
    origin: Option<Sample>,
    direction: Option<f32>,
    travel: f32,
    travel_base: f32,
    distance: f32,
    changed: bool,
    smudge: Option<smudge::Smudge>,
    pixel: Option<pixel::PixelStroke>,
    modified: bool,
}

impl Stroke {
    fn stamp_pixel(
        &mut self,
        document: &mut Document,
        selection: Option<&Selection>,
        point: Sample,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<bool, String> {
        let (brush, point) = pixel::nib(self.brush, point);
        if self.mask_editing {
            masks::stamp(document, selection, brush, point, dirty)
        } else if document.palette.is_some() {
            indexed::stamp(document, selection, brush, point, dirty)
        } else if let Some(smudge) = &mut self.smudge {
            smudge.stamp(document, selection, brush, point, dirty, remaining)
        } else {
            raster::stamp(document, selection, brush, point, dirty, remaining)
        }
    }

    fn stamp(
        &mut self,
        document: &mut Document,
        selection: Option<&Selection>,
        point: Sample,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<(), String> {
        let brush = self.stamp_brush();
        if self.mask_editing {
            self.modified |= masks::stamp(document, selection, brush, point, dirty)?;
            Ok(())
        } else if let Some(smudge) = &mut self.smudge {
            self.modified |= smudge.stamp(document, selection, brush, point, dirty, remaining)?;
            Ok(())
        } else {
            raster::stamp(document, selection, brush, point, dirty, remaining).map(|_| ())
        }
    }

    fn stamp_brush(&self) -> Brush {
        let mut brush = match self.direction {
            Some(direction) => Brush {
                angle: self.brush.angle + direction,
                ..self.brush
            },
            None if self.brush.follow_direction => Brush {
                tip: BrushTip::Round,
                aspect: 1.0,
                ..self.brush
            },
            None => self.brush,
        };
        if self.brush.follow_direction
            && (self.direction.is_none() || self.brush.tip == BrushTip::Leaf)
        {
            let t = (self.travel / (self.brush.size * 0.7)).clamp(0.0, 1.0);
            let swell = t * t * (3.0 - 2.0 * t);
            let floor = (self.brush.spacing * 2.0).clamp(0.04, 0.4);
            brush.size *= floor + (1.0 - floor) * swell;
        }
        brush
    }

    fn paint(
        &mut self,
        point: Sample,
        document: &mut Document,
        selection: Option<&Selection>,
        dirty: &mut BTreeSet<TileKey>,
        remaining: &mut usize,
    ) -> Result<(), String> {
        if let Some(mut pixel) = self.pixel.take() {
            let result = pixel.paint(self.brush, point, |sample| {
                self.stamp_pixel(document, selection, sample, dirty, remaining)
            });
            self.pixel = Some(pixel);
            let modified = result?;
            self.modified |= modified;
            self.changed |= modified;
            return Ok(());
        }
        if let Some(last) = self.last {
            let dx = point.x - last.x;
            let dy = point.y - last.y;
            let length = dx.hypot(dy);
            if length > f32::EPSILON {
                if self.brush.follow_direction {
                    let settled = self.direction.is_some()
                        || self.origin.is_some_and(|origin| {
                            (point.x - origin.x).hypot(point.y - origin.y)
                                >= (self.brush.size * 0.08).clamp(2.0, 24.0)
                        });
                    if settled {
                        self.direction = Some(dy.atan2(dx).to_degrees());
                    }
                }
                if !self.changed {
                    self.travel = self.travel_base;
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
                    self.travel = self.travel_base + cursor;
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
                self.travel_base += length;
                self.travel = self.travel_base;
                self.distance = (self.distance + length) % spacing;
            }
        } else {
            self.origin = Some(point);
            if !self.brush.follow_direction {
                self.stamp(document, selection, point, dirty, remaining)?;
                self.changed = true;
            }
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
    mask_editing: bool,
    revision: u64,
    content_id: u64,
    selection: Option<Selection>,
    selection_id: u64,
    preview_revision: Option<u64>,
    preview_job: Option<std::thread::JoinHandle<(u64, Vec<u8>)>>,
    indexed_cache: indexed::RgbaCache,
    vector_cache: std::sync::Arc<std::sync::Mutex<vector::RenderCache>>,
    frame_edit: Option<Box<Engine>>,
}

impl Engine {
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        Ok(Self {
            document: Document::new(width, height)?,
            history: History::default(),
            dirty: BTreeSet::new(),
            transparent_frame: false,
            stroke: None,
            mask_editing: false,
            revision: 0,
            content_id: 0,
            selection: None,
            selection_id: 0,
            preview_revision: None,
            preview_job: None,
            indexed_cache: indexed::RgbaCache::default(),
            vector_cache: Default::default(),
            frame_edit: None,
        })
    }

    pub fn command(&mut self, command: Command) -> Result<Value, String> {
        if matches!(command, Command::State) {
            return Ok(self.state());
        }
        if self.has_live_stroke()
            && matches!(
                command,
                Command::Undo | Command::Redo | Command::New { .. } | Command::NewIndexed { .. }
            )
        {
            return Err("请先结束当前笔画".into());
        }
        if animation_engine::timeline_revision(&command).is_some() {
            return self.animation_command(command);
        }
        if self.document.animation.is_some()
            && !matches!(
                command,
                Command::State
                    | Command::Undo
                    | Command::Redo
                    | Command::New { .. }
                    | Command::NewIndexed { .. }
            )
        {
            return self.frame_command(command);
        }
        let command = match command {
            Command::GradientMap {
                id,
                revision,
                selection_id,
                settings,
            } => Command::ApplyAdjustment {
                request: AdjustmentRequest {
                    frame_id: None,
                    cel_id: None,
                    target_layer_id: None,
                    id,
                    revision,
                    selection_id: Some(selection_id),
                    settings: AdjustmentSettings::gradient_map(settings),
                },
            },
            command => command,
        };
        let metadata_restore = match &command {
            Command::Undo => self.history.undo.back(),
            Command::Redo => self.history.redo.last(),
            _ => None,
        }
        .is_some_and(|snapshot| {
            !snapshot.pixels_changed
                && snapshot
                    .document
                    .animation
                    .as_ref()
                    .map(|animation| animation.active_frame)
                    == self
                        .document
                        .animation
                        .as_ref()
                        .map(|animation| animation.active_frame)
        });
        let frame_restore_changed = match &command {
            Command::Undo => self.history.undo.back(),
            Command::Redo => self.history.redo.last(),
            _ => None,
        }
        .is_some_and(|snapshot| {
            snapshot
                .document
                .animation
                .as_ref()
                .map(|animation| animation.active_frame)
                != self
                    .document
                    .animation
                    .as_ref()
                    .map(|animation| animation.active_frame)
        });
        let ending = matches!(command, Command::End);
        let result = self.command_inner(command);
        if result.is_err() && ending {
            self.rollback_mask_stroke();
        }
        if result.is_ok() && (self.document.animation.is_some() || self.frame_edit.is_some()) {
            if frame_restore_changed {
                self.set_selection(None);
                self.mask_editing = false;
            }
            if let (true, Some(view)) = (metadata_restore, self.frame_edit.as_mut()) {
                let animation = self.document.animation.as_ref().unwrap();
                view.document = animation::view(&self.document, animation.active_frame)?;
                view.revision = self.revision;
                view.content_id = self.content_id;
            } else {
                self.reset_frame_context()?;
            }
            return result.map(|value| {
                if value.get("width").is_some() {
                    self.state()
                } else {
                    value
                }
            });
        }
        result
    }

    fn rollback_mask_stroke(&mut self) {
        if self
            .stroke
            .as_ref()
            .is_some_and(|stroke| stroke.mask_editing || stroke.before.palette.is_some())
        {
            self.mark_all();
            let stroke = self.stroke.take().unwrap();
            self.document = stroke.before;
            self.mark_all();
        }
    }

    fn command_inner(&mut self, command: Command) -> Result<Value, String> {
        if let Command::GenerateLines {
            id,
            revision,
            selection_id,
            mask_editing,
            mask_id,
            name,
            parent_id,
            index,
            settings,
        } = command
        {
            if self.stroke.is_some()
                || id != self.document.active
                || revision != self.revision
                || selection_id != self.selection_id
                || mask_editing != self.mask_editing
                || mask_id.is_some()
                || self.mask_editing
                || self.selection.is_some()
            {
                return Err("请结束笔画、取消选区并退出蒙版编辑后重新生成".into());
            }
            let next = line_generator::prepare(&self.document, name, parent_id, index, settings)?;
            let after_index = next
                .layers
                .iter()
                .position(|layer| layer.id == next.active)
                .ok_or("生成图层不存在")?;
            let has_alpha = next.layers[after_index]
                .vector()?
                .objects
                .iter()
                .any(|object| {
                    object.style.fill.is_some_and(|color| color[3] != 0)
                        || object
                            .style
                            .stroke
                            .as_ref()
                            .is_some_and(|stroke| stroke.color[3] != 0)
                });
            let keys = if has_alpha {
                adjustment_layers::affected_keys(&next, after_index)?
            } else {
                std::collections::BTreeSet::new()
            };
            let pixels_changed = !keys.is_empty();
            self.dirty.extend(keys);
            let before = std::mem::replace(&mut self.document, next);
            self.history
                .push(before, self.content_id, &self.document, pixels_changed);
            self.revision += 1;
            self.content_id = self.revision;
            return Ok(self.state());
        }
        if let Some(revision) = match &command {
            Command::AddAssistant { revision, .. }
            | Command::SetAssistant { revision, .. }
            | Command::DeleteAssistant { revision, .. }
            | Command::SetAssistantSnap { revision, .. }
            | Command::PreviewAssistant { revision, .. } => Some(*revision),
            _ => None,
        } {
            if revision != self.revision {
                return Err("工程已变化，请重新编辑绘画助手".into());
            }
            if let Command::PreviewAssistant {
                assistant,
                origin,
                point,
                ..
            } = command
            {
                return assistants::preview(assistant, origin, point, revision);
            }
            if self.stroke.is_some() {
                return Err("请先结束当前笔画".into());
            }
            let edit = match command {
                Command::AddAssistant { assistant, .. } => assistants::Edit::Add(assistant),
                Command::SetAssistant { id, assistant, .. } => assistants::Edit::Set(id, assistant),
                Command::DeleteAssistant { id, .. } => assistants::Edit::Delete(id),
                Command::SetAssistantSnap { id, .. } => assistants::Edit::Snap(id),
                _ => unreachable!(),
            };
            let next = assistants::prepare_edit(&self.document, edit)?;
            self.commit_document(next, false)?;
            return Ok(self.state());
        }
        if let Some(revision) = match &command {
            Command::CreateVector { revision, .. }
            | Command::AddVectorObject { revision, .. }
            | Command::SetVectorObject { revision, .. }
            | Command::DeleteVectorObject { revision, .. }
            | Command::ReorderVectorObject { revision, .. }
            | Command::RasterizeVector { revision, .. }
            | Command::VectorObjects { revision, .. }
            | Command::VectorObject { revision, .. }
            | Command::PickVectorObject { revision, .. } => Some(*revision),
            _ => None,
        } {
            if revision != self.revision || self.stroke.is_some() {
                return Err("图层已变化，请结束笔画后重试".into());
            }
            let find = |id| {
                self.document
                    .layers
                    .iter()
                    .find(|layer| layer.id == id)
                    .ok_or("图层不存在")
            };
            match &command {
                Command::VectorObjects { id, .. } => {
                    let vector = find(*id)?.vector()?;
                    return Ok(
                        json!({"id":id,"revision":revision,"nextObjectId":vector.next_object_id,"objects":vector.objects.iter().map(|object|json!({"id":object.id,"name":object.name,"kind":object.kind(),"visible":object.visible,"bounds":vector::object_bounds(object)})).collect::<Vec<_>>()}),
                    );
                }
                Command::VectorObject { id, object_id, .. } => {
                    let object = find(*id)?
                        .vector()?
                        .objects
                        .iter()
                        .find(|object| object.id == *object_id)
                        .ok_or("矢量对象不存在")?;
                    return Ok(
                        json!({"id":id,"object_id":object_id,"revision":revision,"object":object.spec()}),
                    );
                }
                Command::PickVectorObject {
                    id,
                    x,
                    y,
                    tolerance,
                    ..
                } => {
                    return Ok(
                        json!({"object_id":vector::pick(find(*id)?.vector()?,*x,*y,*tolerance)?}),
                    );
                }
                _ => {}
            }
            if self.mask_editing || self.selection.is_some() {
                return Err("请先退出蒙版编辑并取消像素选区".into());
            }
            let creating = matches!(command, Command::CreateVector { .. });
            let next = match command {
                Command::CreateVector {
                    name,
                    parent_id,
                    index,
                    ..
                } => {
                    if self.document.palette.is_some() {
                        return Err("矢量图层暂仅支持 RGBA 工程".into());
                    }
                    if let Some(parent) = parent_id {
                        groups::check_editable(&self.document, parent, false)?;
                    }
                    let mut next = groups::create(
                        &self.document,
                        name,
                        parent_id,
                        index,
                        GroupIsolation::Isolated,
                    )?;
                    next.active_mut().content =
                        LayerContent::Vector(std::sync::Arc::new(vector::VectorLayer::new()));
                    groups::expand_active_ancestors(&mut next)?;
                    next
                }
                Command::AddVectorObject {
                    id, object, index, ..
                } => {
                    if id != self.document.active {
                        return Err("请先选择矢量图层".into());
                    }
                    vector::prepare_edit(&self.document, id, vector::Edit::Add { object, index })?
                }
                Command::SetVectorObject {
                    id,
                    object_id,
                    object,
                    ..
                } => {
                    if id != self.document.active {
                        return Err("请先选择矢量图层".into());
                    }
                    vector::prepare_edit(
                        &self.document,
                        id,
                        vector::Edit::Set { object_id, object },
                    )?
                }
                Command::DeleteVectorObject { id, object_id, .. } => {
                    if id != self.document.active {
                        return Err("请先选择矢量图层".into());
                    }
                    vector::prepare_edit(&self.document, id, vector::Edit::Delete { object_id })?
                }
                Command::ReorderVectorObject {
                    id,
                    object_id,
                    index,
                    ..
                } => {
                    if id != self.document.active {
                        return Err("请先选择矢量图层".into());
                    }
                    vector::prepare_edit(
                        &self.document,
                        id,
                        vector::Edit::Reorder { object_id, index },
                    )?
                }
                Command::RasterizeVector { id, .. } => vector::rasterize(&self.document, id)?,
                _ => unreachable!(),
            };
            if !creating
                && next
                    .layers
                    .iter()
                    .find(|layer| layer.id == next.active)
                    .is_some_and(Layer::is_vector)
            {
                let index = self.layer_index(next.active)?;
                if next != self.document {
                    masks::check_transaction(&self.document, &next)?;
                    self.dirty.extend(vector::changed_keys(
                        self.document.layers[index].vector()?,
                        next.layers[index].vector()?,
                        self.document.bounds(),
                    ));
                    let before = std::mem::replace(&mut self.document, next);
                    self.history
                        .push(before, self.content_id, &self.document, true);
                    self.revision += 1;
                    self.content_id = self.revision;
                }
            } else {
                self.commit_document(next, true)?;
            }
            if creating {
                self.mask_editing = false;
            }
            return Ok(self.state());
        }
        if let Some(revision) = match &command {
            Command::CreateAdjustment { revision, .. }
            | Command::SetAdjustment { revision, .. } => Some(*revision),
            _ => None,
        } {
            if revision != self.revision || self.stroke.is_some() {
                return Err("图层已变化，请结束笔画后重试".into());
            }
            let creating = matches!(command, Command::CreateAdjustment { .. });
            let next = match command {
                Command::CreateAdjustment {
                    name,
                    parent_id,
                    index,
                    settings,
                    selection_id,
                    ..
                } => {
                    if selection_id != self.selection_id {
                        return Err("选区已变化，请重试".into());
                    }
                    adjustment_layers::create(
                        &self.document,
                        name,
                        parent_id,
                        index,
                        settings.into(),
                        self.selection.as_ref(),
                    )?
                }
                Command::SetAdjustment { id, settings, .. } => {
                    adjustment_layers::set(&self.document, id, settings.into())?
                }
                _ => unreachable!(),
            };
            self.commit_document(next, true)?;
            if creating {
                self.mask_editing = false;
            }
            return Ok(self.state());
        }
        if let Some(revision) = match &command {
            Command::CreateGroup { revision, .. }
            | Command::GroupLayers { revision, .. }
            | Command::MoveNode { revision, .. }
            | Command::Ungroup { revision, .. }
            | Command::SetGroupIsolation { revision, .. }
            | Command::SetGroupClosed { revision, .. } => Some(*revision),
            _ => None,
        } {
            if revision != self.revision || self.stroke.is_some() {
                return Err("图层已变化，请结束笔画后重试".into());
            }
            let pixels_changed = !matches!(command, Command::SetGroupClosed { .. });
            let expand_active = matches!(
                command,
                Command::CreateGroup { .. }
                    | Command::GroupLayers { .. }
                    | Command::MoveNode { .. }
                    | Command::Ungroup { .. }
            );
            let mut next = match command {
                Command::CreateGroup {
                    name,
                    parent_id,
                    index,
                    isolation,
                    ..
                } => groups::create(&self.document, name, parent_id, index, isolation)?,
                Command::GroupLayers {
                    ids,
                    name,
                    parent_id,
                    index,
                    ..
                } => groups::group(&self.document, &ids, name, parent_id, index)?,
                Command::MoveNode {
                    id,
                    parent_id,
                    index,
                    ..
                } => groups::move_node(&self.document, id, parent_id, index)?,
                Command::Ungroup { id, .. } => groups::ungroup(&self.document, id)?,
                Command::SetGroupIsolation { id, isolation, .. } => {
                    let index = self.layer_index(id)?;
                    let mut next = self.document.clone();
                    match &mut next.layers[index].content {
                        LayerContent::Group {
                            isolation: value, ..
                        } => *value = isolation,
                        _ => return Err("请选择图层组".into()),
                    }
                    next
                }
                Command::SetGroupClosed { id, closed, .. } => {
                    let index = self.layer_index(id)?;
                    let mut next = self.document.clone();
                    match &mut next.layers[index].content {
                        LayerContent::Group { closed: value, .. } => *value = closed,
                        _ => return Err("请选择图层组".into()),
                    }
                    next
                }
                _ => unreachable!(),
            };
            if expand_active && next != self.document {
                groups::expand_active_ancestors(&mut next)?;
            }
            let active_changed = next.active != self.document.active;
            self.commit_document(next, pixels_changed)?;
            if active_changed || self.document.active_mut().masks.is_empty() {
                self.mask_editing = false;
            }
            return Ok(self.state());
        }
        if self.document.palette.is_some()
            && matches!(command, Command::ApplyMask { .. } | Command::MergeVisible)
        {
            return Err("索引色图层烘焙尚未支持，请先转换为 RGBA".into());
        }
        if self.document.palette.is_some() && !self.mask_editing {
            match &command {
                Command::Begin { brush, .. } => {
                    if brush.raster == BrushRaster::Antialiased || brush.smudge || brush.mix != 0.0
                    {
                        return Err("索引色模式请使用像素笔；软笔和涂抹请先转换为 RGBA".into());
                    }
                    if !brush.eraser
                        && brush.index.is_none_or(|index| {
                            usize::from(index)
                                >= self.document.palette.as_ref().unwrap().colors.len()
                        })
                    {
                        return Err("请选择有效索引颜色".into());
                    }
                }
                Command::Fill { .. }
                | Command::FillLasso { .. }
                | Command::Tone { .. }
                | Command::Blur { .. }
                | Command::Gradient { .. }
                | Command::ApplyMask { .. }
                | Command::CutSelection { .. }
                | Command::MergeVisible => {
                    return Err("此操作尚未支持索引色，请先转换为 RGBA".into())
                }
                Command::ApplyAdjustment { request }
                    if !matches!(request.settings.kind, AdjustmentKind::LayerBlend) =>
                {
                    return Err("像素调整尚未支持索引色，请先转换为 RGBA".into())
                }
                _ => {}
            }
        }
        match command {
            Command::Begin { brush, assistant } => {
                if self.stroke.is_some() {
                    return Err("已有进行中的笔画".into());
                }
                groups::check_editable(&self.document, self.document.active, !self.mask_editing)?;
                if !self.document.active_mut().visible {
                    return Err("请先显示当前图层".into());
                }
                let layer = self.document.active_mut();
                if layer.locked {
                    return Err("图层已锁定，请先解锁".into());
                }
                if layer.alpha_locked && brush.eraser && !self.mask_editing {
                    return Err("请先解除透明度锁定".into());
                }
                let brush = brush.validate()?;
                if self.mask_editing && brush.smudge {
                    return Err("蒙版不支持涂抹笔".into());
                }
                if self.mask_editing && self.document.active_mut().masks.is_empty() {
                    return Err("当前图层没有蒙版".into());
                }
                let assistant =
                    assistants::bind(&self.document, assistant, self.revision, brush.smudge)?;
                self.stroke = Some(Stroke {
                    mask_editing: self.mask_editing,
                    before: self.document.clone(),
                    compositor: raster::StrokeCompositor::new(
                        &self.document,
                        self.transparent_frame,
                    ),
                    brush,
                    stabilizer: Stabilizer::new(brush.stabilization),
                    assistant,
                    last: None,
                    origin: None,
                    direction: None,
                    travel: 0.0,
                    travel_base: 0.0,
                    distance: 0.0,
                    changed: false,
                    smudge: if brush.smudge {
                        Some(smudge::Smudge::new(brush)?)
                    } else {
                        None
                    },
                    pixel: (brush.raster != BrushRaster::Antialiased)
                        .then(pixel::PixelStroke::default),
                    modified: false,
                });
            }
            Command::End => {
                if let Some(stroke) = self.stroke.as_mut() {
                    let tail = stroke.stabilizer.finish().map(|point| {
                        stroke
                            .assistant
                            .as_ref()
                            .map_or(point, |assistant| assistant.project_final(point))
                    });
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
                    if let Some(mut pixel) = stroke.pixel.take() {
                        let result = pixel.finish(|sample| {
                            stroke.stamp_pixel(
                                &mut self.document,
                                self.selection.as_ref(),
                                sample,
                                &mut self.dirty,
                                &mut remaining,
                            )
                        });
                        stroke.pixel = Some(pixel);
                        let modified = match result {
                            Ok(modified) => modified,
                            Err(error) => {
                                self.rollback_mask_stroke();
                                return Err(error);
                            }
                        };
                        stroke.modified |= modified;
                        stroke.changed |= modified;
                    }
                    if let Some(point) = stroke.last {
                        if !stroke.changed
                            || ((tail.is_some()
                                || stroke.brush.follow_direction
                                || stroke.brush.smudge)
                                && stroke.distance > f32::EPSILON)
                        {
                            stroke.travel = f32::INFINITY;
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
                    if !stroke.mask_editing
                        && stroke.changed
                        && stroke.smudge.is_none()
                        && stroke.brush.tip == BrushTip::Leaf
                        && stroke.brush.follow_direction
                    {
                        if let (Some(direction), Some(last)) = (stroke.direction, stroke.last) {
                            let (sin, cos) = direction.to_radians().sin_cos();
                            let reach = stroke.brush.size_at_pressure(last.pressure) * 0.75;
                            let nib = stroke.stamp_brush();
                            let pressure = last.pressure;
                            let steps = 24;
                            for i in 1..=steps {
                                let t = i as f32 / steps as f32;
                                let size = nib.size * (1.0 - t);
                                if size < 2.0 {
                                    break;
                                }
                                let flick = Brush { size, ..nib };
                                let point = Sample {
                                    x: last.x + cos * reach * t,
                                    y: last.y + sin * reach * t,
                                    pressure,
                                };
                                let point = stroke
                                    .assistant
                                    .as_ref()
                                    .map_or(point, |assistant| assistant.project_final(point));
                                raster::stamp(
                                    &mut self.document,
                                    self.selection.as_ref(),
                                    flick,
                                    point,
                                    &mut self.dirty,
                                    &mut remaining,
                                )?;
                            }
                        }
                    }
                }
                if let Some(palette) = &self.document.palette {
                    let transparent = palette.transparent;
                    if !self.mask_editing {
                        self.document
                            .active_mut()
                            .raster_mut()?
                            .tiles_mut()
                            .retain(|_, tile| tile.iter().any(|&index| index != transparent));
                    }
                }
                if self.document.palette.is_some() {
                    if let Some(stroke) = &self.stroke {
                        if let Err(error) = masks::check_transaction(&stroke.before, &self.document)
                        {
                            self.rollback_mask_stroke();
                            return Err(error);
                        }
                    }
                }
                if let Some(stroke) = self.stroke.take() {
                    let changed = if stroke.mask_editing {
                        stroke.modified
                    } else {
                        stroke.changed
                    };
                    if changed
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
                if self.mask_editing {
                    let mask = &self
                        .document
                        .selected_mask()
                        .ok_or("当前图层没有蒙版")?
                        .plane;
                    let value = mask.sample(x as i32, y as i32);
                    return Ok(json!({"color":[value,value,value]}));
                }
                if let Some(palette) = &self.document.palette {
                    let layer = self
                        .document
                        .layers
                        .iter()
                        .find(|layer| layer.id == self.document.active)
                        .unwrap();
                    let index = if layer.is_group() || layer.is_adjustment() {
                        let tile = raster::composite_tile_background(
                            &self.document,
                            (x / TILE_SIZE, y / TILE_SIZE),
                            true,
                        );
                        let offset = ((y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) * 4) as usize;
                        palette.nearest(tile[offset..offset + 4].try_into().unwrap())
                    } else {
                        indexed::read_index(layer, palette, x, y)
                    };
                    return Ok(
                        json!({"color": &palette.colors[usize::from(index)][..3],"index":index}),
                    );
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
                    Command::Clear
                        if self.document.palette.is_some()
                            && !self.mask_editing
                            && self.document.active_mut().raster()?.tiles().is_empty()
                            && !self.document.active_mut().locked
                            && !self.document.active_mut().alpha_locked =>
                    {
                        return Ok(self.state())
                    }
                    Command::NewIndexed {
                        width,
                        height,
                        palette,
                    } => {
                        let document = indexed::create(width, height, palette)?;
                        self.mark_all();
                        self.document = document;
                        self.mask_editing = false;
                        self.history = History::default();
                        self.selection = None;
                        self.revision += 1;
                        self.content_id = self.revision;
                    }
                    Command::ConvertColorMode {
                        mode,
                        palette,
                        revision,
                    } => {
                        if revision != self.revision {
                            return Err("工程已变化，请重新转换".into());
                        }
                        if mode == ColorMode::Rgba && palette.is_some() {
                            return Err("RGBA 转换不接受索引调色板".into());
                        }
                        let palette = if mode == ColorMode::Indexed {
                            Some(
                                palette
                                    .or_else(|| self.document.palette.clone())
                                    .ok_or("请指定索引调色板")?,
                            )
                        } else {
                            None
                        };
                        if self.document.palette != palette {
                            let next = indexed::convert(&self.document, palette)?;
                            self.commit_document(next, true)?;
                        }
                    }
                    Command::SetPaletteColor {
                        index,
                        color,
                        revision,
                    } => {
                        self.check_palette_revision(revision)?;
                        let mut next = self.document.clone();
                        let palette = next.palette.as_mut().unwrap();
                        let slot = palette
                            .colors
                            .get_mut(usize::from(index))
                            .ok_or("索引颜色不存在")?;
                        if *slot != color {
                            *slot = color;
                            self.commit_document(next, true)?;
                        }
                    }
                    Command::AddPaletteColor { color, revision } => {
                        self.check_palette_revision(revision)?;
                        let mut next = self.document.clone();
                        let palette = next.palette.as_mut().unwrap();
                        if palette.colors.len() >= MAX_INDEXED_COLORS {
                            return Err("调色板已达到 256 色上限".into());
                        }
                        palette.order.push(palette.colors.len() as u8);
                        palette.colors.push(color);
                        if let Some(metadata) = next.aseprite_metadata.as_mut() {
                            std::sync::Arc::make_mut(metadata).indexed_names.push(None);
                        }
                        self.commit_document(next, false)?;
                    }
                    Command::ReorderPalette { order, revision } => {
                        self.check_palette_revision(revision)?;
                        let mut next = self.document.clone();
                        let palette = next.palette.as_mut().unwrap();
                        if palette.order != order {
                            palette.order = order;
                            self.commit_document(next, false)?;
                        }
                    }
                    Command::RemovePaletteColor {
                        index,
                        replacement,
                        revision,
                    } => {
                        self.check_palette_revision(revision)?;
                        let next = indexed::remove_color(&self.document, index, replacement)?;
                        self.commit_document(next, true)?;
                    }
                    Command::FillIndexed {
                        x,
                        y,
                        index,
                        tolerance,
                        opacity,
                        contiguous,
                        merged,
                    } => {
                        if self.mask_editing {
                            return Err("蒙版填色请使用灰度填充".into());
                        }
                        let (tiles, changed) = indexed::fill(
                            &self.document,
                            self.selection.as_ref(),
                            ColorSelection {
                                x,
                                y,
                                tolerance,
                                contiguous,
                                merged,
                            },
                            index,
                            opacity,
                        )?;
                        if !changed.is_empty() {
                            let mut next = self.document.clone();
                            next.active_mut().raster_mut()?.set_tiles(tiles);
                            self.commit_document(next, true)?;
                        }
                    }
                    Command::SetMaskEditing {
                        id,
                        enabled,
                        mask_id,
                    } => {
                        let id = id.unwrap_or(self.document.active);
                        let index = self.layer_index(id)?;
                        if enabled && self.document.layers[index].masks.is_empty() {
                            return Err("当前图层没有蒙版".into());
                        }
                        let target = mask_id
                            .or_else(|| {
                                (id == self.document.active)
                                    .then_some(self.document.active_mask_id)
                                    .flatten()
                            })
                            .or_else(|| {
                                enabled
                                    .then(|| {
                                        self.document.layers[index]
                                            .masks
                                            .first()
                                            .map(|mask| mask.id)
                                    })
                                    .flatten()
                            });
                        if target.is_some_and(|target| {
                            !self.document.layers[index]
                                .masks
                                .iter()
                                .any(|mask| mask.id == target)
                        }) {
                            return Err("蒙版编辑目标已变化".into());
                        }
                        self.document.active = id;
                        self.document.active_mask_id = target;
                        self.mask_editing = enabled;
                    }
                    command @ (Command::AddMask { .. }
                    | Command::SetMask { .. }
                    | Command::InvertMask { .. }
                    | Command::DeleteMask { .. }
                    | Command::ApplyMask { .. }
                    | Command::DuplicateMask { .. }
                    | Command::ReorderMask { .. }) => self.edit_mask(command)?,
                    Command::Fill {
                        x,
                        y,
                        color,
                        tolerance,
                        contiguous,
                        merged,
                    } => {
                        if self.mask_editing {
                            let before = self.document.clone();
                            if masks::fill(
                                &mut self.document,
                                self.selection.as_ref(),
                                ColorSelection {
                                    x,
                                    y,
                                    tolerance,
                                    contiguous,
                                    merged,
                                },
                                color,
                                &mut self.dirty,
                            )? {
                                self.history
                                    .push(before, self.content_id, &self.document, true);
                                self.revision += 1;
                                self.content_id = self.revision;
                            }
                            return Ok(self.state());
                        }
                        let (tiles, changed) = flood_fill::prepare(
                            &self.document,
                            self.selection.as_ref(),
                            ColorSelection {
                                x,
                                y,
                                tolerance,
                                contiguous,
                                merged,
                            },
                            color,
                        )?;
                        if !changed.is_empty() {
                            let before = self.document.clone();
                            self.document.active_mut().raster_mut()?.set_tiles(tiles);
                            self.dirty.extend(changed);
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::FillLasso {
                        points,
                        color,
                        opacity,
                        eraser,
                    } => {
                        if self.mask_editing {
                            let before = self.document.clone();
                            if masks::lasso(
                                &mut self.document,
                                self.selection.as_ref(),
                                points,
                                color,
                                opacity,
                                eraser,
                                &mut self.dirty,
                            )? {
                                self.history
                                    .push(before, self.content_id, &self.document, true);
                                self.revision += 1;
                                self.content_id = self.revision;
                            }
                            return Ok(self.state());
                        }
                        let (tiles, changed) = lasso_fill::prepare(
                            &self.document,
                            self.selection.as_ref(),
                            points,
                            color,
                            opacity,
                            eraser,
                        )?;
                        if !changed.is_empty() {
                            let before = self.document.clone();
                            self.document.active_mut().raster_mut()?.set_tiles(tiles);
                            self.dirty.extend(changed);
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::Gradient {
                        id,
                        revision,
                        settings,
                    } => {
                        if self.mask_editing {
                            return Err("请先切换到图层像素再绘制渐变".into());
                        }
                        if id != self.document.active || revision != self.revision {
                            return Err("画布已改变，请重新绘制渐变".into());
                        }
                        let index = self.layer_index(id)?;
                        let next = layer_action::prepare(
                            &self.document,
                            self.selection.as_ref(),
                            self.mask_editing,
                            id,
                            LayerAction::Gradient { settings },
                        )?;
                        self.commit_layer(next, index)?;
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
                    Command::ModifySelection {
                        kind,
                        radius,
                        revision,
                        selection_id,
                    } => {
                        if revision != self.revision || selection_id != self.selection_id {
                            return Err("选区已变化，请重新调整".into());
                        }
                        let selection = self.selection.as_ref().ok_or("请先创建选区")?;
                        if let Some(refined) = selection_refinement::prepare(
                            selection,
                            self.document.bounds(),
                            kind,
                            radius,
                        )? {
                            self.set_selection(Some(refined));
                        }
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
                        if self.mask_editing {
                            return Err("请先切换到图层像素".into());
                        }
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
                        self.mask_editing = false;
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
                        self.mask_editing = false;
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
                    Command::TranslateLayer {
                        id,
                        dx,
                        dy,
                        mask_id,
                    } => {
                        self.check_mask_target(mask_id)?;
                        let index = self.layer_index(id)?;
                        let next = layer_action::prepare(
                            &self.document,
                            self.selection.as_ref(),
                            self.mask_editing,
                            id,
                            LayerAction::Translate { dx, dy },
                        )?;
                        if self.commit_layer(next, index)? {
                            self.set_selection(None);
                        }
                    }
                    Command::TransformLayer {
                        id,
                        revision,
                        transform,
                        mask_id,
                    } => {
                        self.check_mask_target(mask_id)?;
                        if revision != self.revision || id != self.document.active {
                            return Err("图层已变化，请重新开始变换".into());
                        }
                        let index = self.layer_index(id)?;
                        let next = layer_action::prepare(
                            &self.document,
                            self.selection.as_ref(),
                            self.mask_editing,
                            id,
                            LayerAction::Transform { transform },
                        )?;
                        self.commit_layer(next, index)?;
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
                        if self
                            .document
                            .layers
                            .iter()
                            .any(|layer| layer.is_group() || layer.is_adjustment())
                        {
                            let plan = groups::Hierarchy::new(&self.document)?;
                            if self.document.layers[source].parent_id
                                != self.document.layers[index].parent_id
                            {
                                return Err("跨组排序请使用图层节点移动".into());
                            }
                            let target = plan
                                .siblings(source)
                                .iter()
                                .position(|&node| node == index)
                                .ok_or("排序位置无效")?;
                            let next = groups::move_node(
                                &self.document,
                                id,
                                self.document.layers[source].parent_id,
                                target,
                            )?;
                            self.commit_document(next, true)?;
                            return Ok(self.state());
                        }
                        if source != index {
                            let before = self.document.clone();
                            for layer in
                                &self.document.layers[source.min(index)..=source.max(index)]
                            {
                                self.dirty
                                    .extend(layer.content_keys(self.document.bounds()));
                            }
                            let layer = self.document.layers.remove(source);
                            self.document.layers.insert(index, layer);
                            clipping::normalize_bottom(&mut self.document.layers);
                            if before.layers.iter().any(|layer| layer.clipping) {
                                self.mark_all();
                            }
                            self.document.active = id;
                            self.history
                                .push(before, self.content_id, &self.document, true);
                            self.revision += 1;
                            self.content_id = self.revision;
                        }
                    }
                    Command::SetClipping {
                        id,
                        clipping,
                        revision,
                    } => {
                        if revision != self.revision {
                            return Err("图层已变化，请重新设置剪贴蒙版".into());
                        }
                        let index = self.layer_index(id)?;
                        if clipping
                            && groups::Hierarchy::new(&self.document)?
                                .siblings(index)
                                .first()
                                == Some(&index)
                        {
                            return Err("底层图层不能设置为剪贴蒙版".into());
                        }
                        if self.document.layers[index].clipping != clipping {
                            let mut next = self.document.clone();
                            next.layers[index].clipping = clipping;
                            self.commit_document(next, true)?;
                        }
                    }
                    Command::ApplyAdjustment { request } => {
                        if request
                            .selection_id
                            .is_some_and(|selection_id| selection_id != self.selection_id)
                        {
                            return Err("选区已变化，请重新调整".into());
                        }
                        if self.mask_editing
                            && !matches!(request.settings.kind, AdjustmentKind::LayerBlend)
                        {
                            return Err("请先切换到图层像素再调整颜色".into());
                        }
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
                        if self.mask_editing
                            && matches!(
                                command,
                                Command::Tone { .. }
                                    | Command::Blur { .. }
                                    | Command::Clear
                                    | Command::CutSelection { .. }
                            )
                        {
                            return Err("请先切换到图层像素".into());
                        }
                        if self.selection.as_ref().is_some_and(Selection::is_empty)
                            && matches!(command, Command::Tone { .. } | Command::Blur { .. })
                        {
                            return Ok(self.state());
                        }
                        let pixels_changed = !matches!(command, Command::SetProtection { .. });
                        let before = self.document.clone();
                        if let Err(error) = self.edit_layers(command).and_then(|_| {
                            self.document.normalize_mask_target();
                            masks::check_transaction(&before, &self.document)
                        }) {
                            self.document = before;
                            return Err(error);
                        }
                        if before == self.document {
                            return Ok(self.state());
                        }
                        if pixels_changed {
                            for layer in &before.layers {
                                self.dirty
                                    .extend(layer.content_keys(self.document.bounds()));
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
        self.document.normalize_mask_target();
        if self.document.active_mask_id.is_none() || self.document.active_mut().masks.is_empty() {
            self.mask_editing = false;
        }
        Ok(self.state())
    }

    fn commit_layer(&mut self, mut next: Document, index: usize) -> Result<bool, String> {
        next.normalize_mask_target();
        if next == self.document {
            return Ok(false);
        }
        masks::check_transaction(&self.document, &next)?;
        self.dirty
            .extend(adjustment_layers::affected_keys(&self.document, index)?);
        self.dirty
            .extend(adjustment_layers::affected_keys(&next, index)?);
        let before = std::mem::replace(&mut self.document, next);
        self.history
            .push(before, self.content_id, &self.document, true);
        self.revision += 1;
        self.content_id = self.revision;
        Ok(true)
    }

    fn check_mask_target(&self, mask_id: Option<u32>) -> Result<(), String> {
        if mask_id.is_some_and(|id| {
            !self.mask_editing || self.document.selected_mask().map(|mask| mask.id) != Some(id)
        }) {
            return Err("蒙版编辑目标已变化".into());
        }
        Ok(())
    }

    fn edit_mask(&mut self, command: Command) -> Result<(), String> {
        let (id, target, revision) = match &command {
            Command::AddMask { revision, .. } => (self.document.active, None, *revision),
            Command::SetMask {
                id,
                mask_id,
                revision,
                ..
            }
            | Command::InvertMask {
                id,
                mask_id,
                revision,
            }
            | Command::DeleteMask {
                id,
                mask_id,
                revision,
            } => (*id, *mask_id, *revision),
            Command::ApplyMask { id, revision } => (*id, None, *revision),
            Command::DuplicateMask {
                id,
                mask_id,
                revision,
            }
            | Command::ReorderMask {
                id,
                mask_id,
                revision,
                ..
            } => (*id, Some(*mask_id), Some(*revision)),
            _ => return Err("操作无效".into()),
        };
        if revision.is_some_and(|revision| revision != self.revision) {
            return Err("蒙版已变化，请重试".into());
        }
        let index = self.layer_index(id)?;
        if groups::Hierarchy::new(&self.document)?.locked[index] {
            return Err("图层已锁定，请先解锁".into());
        }
        let source = &self.document.layers[index];
        let target = target
            .or_else(|| {
                (id == self.document.active)
                    .then_some(self.document.active_mask_id)
                    .flatten()
            })
            .or_else(|| source.masks.first().map(|mask| mask.id));
        let position =
            target.and_then(|target| source.masks.iter().position(|mask| mask.id == target));
        let mut next = self.document.clone();
        let mut select = None;
        let pixels_changed = match &command {
            Command::ReorderMask { .. } => false,
            Command::SetMask { enabled, .. } => enabled.is_some_and(|enabled| {
                position.is_some_and(|position| source.masks[position].plane.enabled != enabled)
            }),
            _ => true,
        };
        match command {
            Command::AddMask {
                mode,
                name,
                selection_id,
                ..
            } => {
                if selection_id.is_some_and(|id| id != self.selection_id) {
                    return Err("选区已变化，请重试".into());
                }
                if source.masks.len() >= MAX_LAYER_MASKS {
                    return Err("图层蒙版数量超出限制".into());
                }
                let entry = MaskEntry {
                    id: next.next_mask_id,
                    name: name.unwrap_or_else(|| format!("Mask {}", source.masks.len() + 1)),
                    plane: masks::create(&self.document, self.selection.as_ref(), mode)?,
                };
                next.next_mask_id = next.next_mask_id.checked_add(1).ok_or("蒙版编号超出限制")?;
                select = Some(entry.id);
                next.layers[index].masks.push(entry);
            }
            Command::DuplicateMask { .. } => {
                if source.masks.len() >= MAX_LAYER_MASKS {
                    return Err("图层蒙版数量超出限制".into());
                }
                let position = position.ok_or("蒙版不存在")?;
                let mut entry = source.masks[position].clone();
                entry.id = next.next_mask_id;
                next.next_mask_id = next.next_mask_id.checked_add(1).ok_or("蒙版编号超出限制")?;
                select = Some(entry.id);
                next.layers[index].masks.insert(position + 1, entry);
            }
            Command::ReorderMask {
                index: destination, ..
            } => {
                let position = position.ok_or("蒙版不存在")?;
                if destination >= source.masks.len() {
                    return Err("蒙版排序无效".into());
                }
                let entry = next.layers[index].masks.remove(position);
                next.layers[index].masks.insert(destination, entry);
            }
            Command::SetMask {
                name,
                enabled,
                linked,
                ..
            } => {
                let entry = &mut next.layers[index].masks[position.ok_or("蒙版不存在")?];
                if let Some(name) = name {
                    entry.name = name;
                }
                if let Some(enabled) = enabled {
                    entry.plane.enabled = enabled;
                }
                if let Some(linked) = linked {
                    entry.plane.linked = linked;
                }
            }
            Command::InvertMask { .. } => {
                let mask = &mut next.layers[index].masks[position.ok_or("蒙版不存在")?].plane;
                mask.default = 255 - mask.default;
                for tile in mask.tiles.values_mut() {
                    for value in std::sync::Arc::make_mut(tile) {
                        *value = 255 - *value;
                    }
                }
            }
            Command::DeleteMask { .. } => {
                let position = position.ok_or("蒙版不存在")?;
                let removed = next.layers[index].masks.remove(position);
                if id == next.active && next.active_mask_id == Some(removed.id) {
                    next.active_mask_id = next.layers[index]
                        .masks
                        .get(position.min(next.layers[index].masks.len().saturating_sub(1)))
                        .map(|mask| mask.id);
                }
            }
            Command::ApplyMask { .. } => {
                if source.masks.is_empty() {
                    return Err("当前图层没有蒙版".into());
                }
                if !source.has_enabled_masks() {
                    return Err("请先启用蒙版".into());
                }
                if source.alpha_locked {
                    return Err("请先解除透明度锁定".into());
                }
                let keys: Vec<_> = source
                    .raster()?
                    .tiles()
                    .iter()
                    .filter_map(|(&key, tile)| {
                        tile.as_chunks::<4>()
                            .0
                            .iter()
                            .enumerate()
                            .any(|(pixel_index, pixel)| {
                                let alpha = u32::from(mask_stack::coverage(
                                    source,
                                    (key.0 * TILE_SIZE + pixel_index as u32 % TILE_SIZE) as i32,
                                    (key.1 * TILE_SIZE + pixel_index as u32 / TILE_SIZE) as i32,
                                ));
                                pixel.iter().any(|&value| {
                                    ((u32::from(value) * alpha + 127) / 255) as u8 != value
                                })
                            })
                            .then_some(key)
                    })
                    .collect();
                let mut probe = self.document.clone();
                probe.layers[index].masks.clear();
                probe.normalize_mask_target();
                for key in &keys {
                    probe.layers[index].raster_mut()?.tiles_mut().remove(key);
                }
                masks::check_transaction(&self.document, &probe)?;
                for key in keys {
                    let pixels = raster::masked_tile(source, self.document.palette.as_ref(), key);
                    if pixels.iter().all(|&value| value == 0) {
                        next.layers[index].raster_mut()?.tiles_mut().remove(&key);
                    } else {
                        next.layers[index]
                            .raster_mut()?
                            .tiles_mut()
                            .insert(key, std::sync::Arc::new(pixels));
                    }
                }
                next.layers[index].masks.clear();
            }
            _ => return Err("操作无效".into()),
        }
        if let Some(mask_id) = select {
            next.active = id;
            next.active_mask_id = Some(mask_id);
        }
        next.normalize_mask_target();
        if next == self.document {
            return Ok(());
        }
        masks::check_transaction(&self.document, &next)?;
        if pixels_changed {
            self.dirty
                .extend(adjustment_layers::affected_keys(&self.document, index)?);
            self.dirty
                .extend(adjustment_layers::affected_keys(&next, index)?);
        }
        let before = std::mem::replace(&mut self.document, next);
        self.history
            .push(before, self.content_id, &self.document, pixels_changed);
        self.revision += 1;
        self.content_id = self.revision;
        if select.is_some() {
            self.mask_editing = true;
        } else if self.document.active_mut().masks.is_empty() {
            self.mask_editing = false;
        }
        Ok(())
    }

    fn edit_layers(&mut self, command: Command) -> Result<(), String> {
        let bounds = self.document.bounds();
        let selection = self.selection.as_ref();
        let region = selection.map_or(bounds, Selection::bounds);
        if matches!(
            command,
            Command::Tone { .. } | Command::Blur { .. } | Command::Clear
        ) {
            groups::check_editable(&self.document, self.document.active, true)?;
            let layer = self.document.active_mut();
            if layer.locked {
                return Err("图层已锁定，请先解锁".into());
            }
            if layer.alpha_locked && matches!(command, Command::Clear) {
                return Err("请先解除透明度锁定".into());
            }
        }
        match command {
            Command::Tone { settings } => {
                adjustments::tone(self.document.active_mut(), region, selection, settings)?
            }
            Command::Blur { sigma } => {
                adjustments::blur(self.document.active_mut(), bounds, region, selection, sigma)?
            }
            Command::AddLayer => {
                if self.document.layers.len() >= MAX_LAYER_NODES
                    || self
                        .document
                        .layers
                        .iter()
                        .filter(|layer| layer.raster_opt().is_some())
                        .count()
                        >= MAX_LAYERS
                {
                    return Err("已达到图层上限".into());
                }
                let id = self.document.next_id;
                self.document.next_id = id.checked_add(1).ok_or("图层编号超出限制")?;
                let source = self.layer_index(self.document.active)?;
                let plan = groups::Hierarchy::new(&self.document)?;
                let parent_id = if self.document.layers[source].is_group() {
                    Some(self.document.active)
                } else {
                    self.document.layers[source].parent_id
                };
                if let Some(parent) = parent_id {
                    groups::check_editable(&self.document, parent, false)?;
                }
                let index = plan.end[source];
                self.document
                    .layers
                    .insert(index, Layer::new(id, format!("图层 {id}")));
                if self.document.palette.is_some() {
                    self.document.layers[index].content =
                        LayerContent::Raster(RasterPlane::Indexed(Default::default()));
                }
                self.document.layers[index].parent_id = parent_id;
                self.document.active = id;
                groups::expand_active_ancestors(&mut self.document)?;
            }
            Command::DuplicateLayer { id } => {
                layers::duplicate(&mut self.document, id)?;
                groups::expand_active_ancestors(&mut self.document)?;
            }
            Command::MergeVisible => layers::merge_visible(&mut self.document)?,
            Command::RemoveLayer { id } => {
                if self.document.layers.len() <= 1 {
                    return Err("至少保留一个图层".into());
                }
                let index = self.layer_index(id)?;
                let plan = groups::Hierarchy::new(&self.document)?;
                let end = plan.end[index];
                if self.document.layers.len() == end - index {
                    return Err("至少保留一个图层节点".into());
                }
                if plan.locked[index..end].iter().any(|locked| *locked) {
                    return Err("图层已锁定，请先解锁".into());
                }
                clipping::release_dependents(&mut self.document.layers, index);
                let removing_active = self.document.layers[index..end]
                    .iter()
                    .any(|layer| layer.id == self.document.active);
                self.document.layers.drain(index..end);
                clipping::normalize_bottom(&mut self.document.layers);
                if removing_active {
                    self.document.active = self.document.layers
                        [index.saturating_sub(1).min(self.document.layers.len() - 1)]
                    .id;
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
                let plan = groups::Hierarchy::new(&self.document)?;
                let siblings = plan.siblings(index);
                let position = siblings.iter().position(|&node| node == index).unwrap();
                let target = (position as i64 + i64::from(direction.signum()))
                    .clamp(0, siblings.len() as i64 - 1) as usize;
                self.document = groups::move_node(
                    &self.document,
                    id,
                    self.document.layers[index].parent_id,
                    target,
                )?;
            }
            Command::Clear => self.document.active_mut().raster_mut()?.tiles_mut().clear(),
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

    fn check_palette_revision(&self, revision: u64) -> Result<(), String> {
        if revision != self.revision {
            return Err("调色板已变化，请重试".into());
        }
        if self.document.palette.is_none() {
            return Err("当前工程不是索引色模式".into());
        }
        Ok(())
    }

    fn commit_document(&mut self, mut next: Document, pixels_changed: bool) -> Result<(), String> {
        next.normalize_mask_target();
        if next == self.document {
            return Ok(());
        }
        masks::check_transaction(&self.document, &next)?;
        if pixels_changed {
            self.mark_all();
        }
        let before = std::mem::replace(&mut self.document, next);
        if pixels_changed {
            self.mark_all();
        }
        self.history
            .push(before, self.content_id, &self.document, pixels_changed);
        self.revision += 1;
        self.content_id = self.revision;
        Ok(())
    }
    fn mark_all(&mut self) {
        for layer in &self.document.layers {
            self.dirty
                .extend(layer.content_keys(self.document.bounds()));
        }
    }

    pub fn samples(&mut self, samples: &[Sample]) -> Result<(), String> {
        if self.document.animation.is_some() {
            return self.frame_samples(samples);
        }
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
            let point = stroke
                .assistant
                .as_mut()
                .map_or(point, |assistant| assistant.project(point));
            let filtered = if stroke.pixel.is_some() && stroke.assistant.is_none() {
                point
            } else {
                stroke.stabilizer.push(point)
            };
            let filtered = stroke
                .assistant
                .as_ref()
                .map_or(filtered, |assistant| assistant.project_final(filtered));
            if let Err(error) = stroke.paint(
                filtered,
                &mut self.document,
                self.selection.as_ref(),
                &mut self.dirty,
                &mut remaining,
            ) {
                self.rollback_mask_stroke();
                return Err(error);
            }
        }
        if self.document.palette.is_some() {
            if let Err(error) = masks::check_transaction(&stroke.before, &self.document) {
                self.rollback_mask_stroke();
                return Err(error);
            }
        }
        Ok(())
    }

    pub fn state(&self) -> Value {
        if let Some(view) = &self.frame_edit {
            return self.animation_state(view.state());
        }
        let plan = groups::Hierarchy::new(&self.document).expect("无效的图层层级");
        let layers=self.document.layers.iter().enumerate().map(|(index,l)| {
                let (isolation,closed)=match l.content { LayerContent::Group{isolation,closed}=>(Some(isolation),closed),_ =>(None,false) };
                let kind=if l.is_group(){"group"}else if l.is_adjustment(){"adjustment"}else if l.is_vector(){"vector"}else{"raster"}; let mut info=json!({"id": l.id, "name": l.name, "visible": l.visible, "opacity": l.opacity, "blend": l.blend, "alphaLocked": l.alpha_locked, "locked": l.locked,"clipping":l.clipping,"clippingBase":clipping::base_id(&self.document.layers,index),"masks":l.masks.iter().map(|mask|json!({"id":mask.id,"name":mask.name,"enabled":mask.plane.enabled,"linked":mask.plane.linked})).collect::<Vec<_>>(),"mask":(if l.id==self.document.active {self.document.selected_mask()} else {l.masks.first()}).map(|mask|json!({"id":mask.id,"name":mask.name,"enabled":mask.plane.enabled,"linked":mask.plane.linked})),"kind":kind,"adjustment":match &l.content {LayerContent::Adjustment {settings}=>Some(settings.json()),_=>None},"parentId":l.parent_id,"depth":plan.depth[index],"childCount":plan.children[index].len(),"isolation":isolation,"closed":closed,"effectiveVisible":plan.visible[index],"effectiveLocked":plan.locked[index]}); info["vector"]=l.vector().ok().map(|vector|json!({"objectCount":vector.objects.len(),"nextObjectId":vector.next_object_id})).unwrap_or(Value::Null); info
            }).collect::<Vec<_>>();
        json!({ "width": self.document.width, "height": self.document.height, "active": self.document.active,
            "animation":Value::Null,"maxAnimationFrames":MAX_ANIMATION_FRAMES,"maxAnimationCels":MAX_ANIMATION_CELS,"maxAnimationTags":MAX_ANIMATION_TAGS,"maxFrameThumbnails":MAX_FRAME_THUMBNAILS,
            "animationExport":animation_export::capabilities(),
            "asepriteExport":aseprite::capabilities(&self.document),
            "asepriteMetadata":self.document.aseprite_metadata.as_ref().map(|metadata|metadata.json()),
            "colorMode":if self.document.palette.is_some(){"indexed"}else{"rgba"},
            "indexedPalette":self.document.palette,
            "maskEditing":self.mask_editing,
            "activeMaskId":self.document.active_mask_id,
            "maxLayerMasks":MAX_LAYER_MASKS,
            "maxVectorObjects":MAX_VECTOR_OBJECTS,"maxVectorSegments":MAX_VECTOR_SEGMENTS,
            "maxGeneratedLines":MAX_GENERATED_LINES,
            "assistants":self.document.assistants.json(),
            "maxDrawingAssistants":MAX_DRAWING_ASSISTANTS,
            "maxAssistantCoordinate":MAX_ASSISTANT_COORDINATE,
            "maxAssistantStrokeCoordinate":MAX_ASSISTANT_STROKE_COORDINATE,
            "selectionId":self.selection_id,
            "revision": self.revision, "contentId": self.content_id, "canUndo": !self.history.undo.is_empty(), "canRedo": !self.history.redo.is_empty(), "selection": self.selection, "maxLayers": MAX_LAYERS,
            "maxLayerNodes":MAX_LAYER_NODES,"maxGroupDepth":MAX_GROUP_DEPTH,
            "uiOrder":plan.ui_order().iter().map(|&index|self.document.layers[index].id).collect::<Vec<_>>(),
            "layers":layers })
    }

    pub fn frame(&mut self) -> Vec<u8> {
        self.frame_with_background(false)
    }

    pub fn frame_with_background(&mut self, transparent: bool) -> Vec<u8> {
        if let Some(view) = &mut self.frame_edit {
            self.transparent_frame = transparent;
            return view.frame_with_background(transparent);
        }
        if self.transparent_frame != transparent {
            self.transparent_frame = transparent;
            self.mark_all();
            if let Some(stroke) = self.stroke.as_mut() {
                stroke.compositor = raster::StrokeCompositor::new(&self.document, transparent);
            }
        }
        if let Some(stroke) = self.stroke.as_mut().filter(|stroke| stroke.mask_editing) {
            stroke.compositor.invalidate_mask(self.document.active);
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
        let mut compositor = raster::FrameCompositor::with_vector_cache(
            &self.document,
            transparent,
            self.vector_cache.clone(),
        );
        for key in dirty {
            output.extend(key.0.to_le_bytes());
            output.extend(key.1.to_le_bytes());
            output.extend(if self.document.palette.is_some() {
                compositor.tile_cached(&self.document, key, &mut self.indexed_cache)
            } else if let Some(stroke) = self.stroke.as_mut() {
                stroke.compositor.tile(&self.document, key)
            } else {
                compositor.tile(&self.document, key)
            });
        }
        output
    }

    pub fn save(&self) -> Result<Vec<u8>, String> {
        storage::save(&self.frame_snapshot()?)
    }
    pub fn preview_adjustment(&self, request: AdjustmentRequest) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            self.check_frame_target(
                request.frame_id,
                request.cel_id,
                Some(request.revision),
                request.target_layer_id,
            )?;
            if view.stroke.is_some() {
                return Err("请先结束当前笔画".into());
            }
            let (prepared, _) = adjustment_preview::prepare(
                &view.document,
                view.revision,
                view.selection.as_ref(),
                AdjustmentRequest {
                    frame_id: request.frame_id,
                    cel_id: request.cel_id,
                    target_layer_id: request.target_layer_id,
                    id: request.id,
                    revision: request.revision,
                    selection_id: request.selection_id,
                    settings: request.settings.clone(),
                },
            )?;
            let candidate = animation::merge_view(&self.document, &prepared, false)?;
            masks::check_transaction(&self.document, &candidate)?;
            return view.preview_adjustment(request);
        }
        if self.document.palette.is_some()
            && !matches!(request.settings.kind, AdjustmentKind::LayerBlend)
        {
            return Err("像素调整尚未支持索引色，请先转换为 RGBA".into());
        }
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        if request
            .selection_id
            .is_some_and(|selection_id| selection_id != self.selection_id)
        {
            return Err("选区已变化，请重新调整".into());
        }
        if self.mask_editing && !matches!(request.settings.kind, AdjustmentKind::LayerBlend) {
            return Err("请先切换到图层像素再调整颜色".into());
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
    pub fn preview_layer_action(&self, request: LayerActionRequest) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            self.check_frame_target(
                request.frame_id,
                request.cel_id,
                Some(request.revision),
                request.target_layer_id,
            )?;
            if view.stroke.is_some() {
                return Err("请先结束当前笔画".into());
            }
            let command = match &request.action {
                LayerAction::Translate { dx, dy } => Some(Command::TranslateLayer {
                    mask_id: request.mask_id,
                    id: request.id,
                    dx: *dx,
                    dy: *dy,
                }),
                LayerAction::Transform { transform } => Some(Command::TransformLayer {
                    mask_id: request.mask_id,
                    id: request.id,
                    revision: request.revision,
                    transform: *transform,
                }),
                _ => None,
            };
            let global = command
                .as_ref()
                .map(|command| {
                    animation::global(
                        &self.document,
                        command,
                        self.revision,
                        self.mask_editing,
                        self.selection.is_some(),
                    )
                })
                .transpose()?
                .flatten();
            let candidate = match global {
                Some(candidate) => candidate,
                None => {
                    let next = layer_action::prepare(
                        &view.document,
                        view.selection.as_ref(),
                        view.mask_editing,
                        request.id,
                        request.action.clone(),
                    )?;
                    animation::merge_view(&self.document, &next, false)?
                }
            };
            masks::check_transaction(&self.document, &candidate)?;
            return view.preview_layer_action(request);
        }
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        if request.id != self.document.active
            || request.revision != self.revision
            || request.selection_id != self.selection_id
            || request.mask_editing != self.mask_editing
            || request.mask_id.is_some_and(|id| {
                !self.mask_editing || self.document.selected_mask().map(|mask| mask.id) != Some(id)
            })
        {
            return Err("图层或选区已变化，请重新预览".into());
        }
        let next = layer_action::prepare(
            &self.document,
            self.selection.as_ref(),
            self.mask_editing,
            request.id,
            request.action,
        )?;
        masks::check_transaction(&self.document, &next)?;
        layer_action::frame(
            &self.document,
            &next,
            self.transparent_frame,
            self.vector_cache.clone(),
        )
    }
    pub fn vector_svg(&self, id: u32, revision: u64) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.vector_svg(id, revision);
        }
        if revision != self.revision || self.stroke.is_some() {
            return Err("图层已变化，请重试".into());
        }
        vector_svg::svg(&self.document, id)
    }
    pub fn curve_histogram(&self) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.curve_histogram();
        }
        serde_json::to_vec(&curves::histogram(&self.document, self.selection.as_ref())?)
            .map_err(|_| "直方图编码失败".into())
    }
    pub fn import_layer(&mut self, bytes: &[u8], name: &str) -> Result<(), String> {
        if self.document.animation.is_some() {
            return self.frame_external(|view| view.import_layer(bytes, name));
        }
        if self.document.palette.is_some() {
            return Err("索引色图层导入尚未支持，请先转换为 RGBA".into());
        }
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        let layer = import_layer::prepare(&self.document, bytes, name)?;
        self.insert_image_layer(layer)
    }
    pub fn copy_selection(&self, mode: CopyMode) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.copy_selection(mode);
        }
        if self.document.palette.is_some() {
            return Err("索引色复制尚未支持，请先转换为 RGBA".into());
        }
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        if self.mask_editing {
            return Err("请先切换到图层像素".into());
        }
        clipboard::copy(&self.document, self.selection.as_ref(), mode)
    }
    pub fn paste_image(&mut self, packet: &[u8]) -> Result<(), String> {
        if self.document.animation.is_some() {
            return self.frame_external(|view| view.paste_image(packet));
        }
        if self.document.palette.is_some() {
            return Err("索引色粘贴尚未支持，请先转换为 RGBA".into());
        }
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
        let index = self.layer_index(self.document.active)?;
        let plan = groups::Hierarchy::new(&self.document)?;
        let keys: Vec<_> = layer.raster_keys().collect();
        let mut next = self.document.clone();
        next.active = layer.id;
        next.layers.insert(plan.end[index], layer);
        next.next_id = next_id;
        groups::expand_active_ancestors(&mut next)?;
        next.normalize_mask_target();
        masks::check_transaction(&self.document, &next)?;
        let clipping = self.document.layers.iter().any(|layer| layer.clipping);
        if clipping {
            self.mark_all();
        }
        let before = std::mem::replace(&mut self.document, next);
        if clipping {
            self.mark_all();
        } else {
            self.dirty.extend(keys);
        }
        self.history
            .push(before, self.content_id, &self.document, true);
        self.revision += 1;
        self.content_id = self.revision;
        self.mask_editing = false;
        self.selection = None;
        Ok(())
    }
    pub fn layer_frame(&self) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.layer_frame();
        }
        translation::frame(&self.document)
    }
    pub fn move_layer_frame(&self, selection: bool) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.move_layer_frame(selection);
        }
        if self.stroke.is_some() {
            return Err("请先结束当前笔画".into());
        }
        let selected = if selection {
            self.selection.as_ref()
        } else {
            None
        };
        move_preview::frame(&self.document, selected, self.mask_editing)
    }
    pub fn selection_move_frame(&self) -> Result<Vec<u8>, String> {
        if let Some(view) = &self.frame_edit {
            return view.selection_move_frame();
        }
        translation::selection_frame(
            &self.document,
            self.selection.as_ref().ok_or("请先创建选区")?,
        )
    }
    pub fn selection_frame(&self) -> Vec<u8> {
        if let Some(view) = &self.frame_edit {
            return view.selection_frame();
        }
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
        if let Some(view) = &self.frame_edit {
            return view.selection_outline();
        }
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
    pub fn selection_outline_mask(&self) -> Vec<u8> {
        if let Some(view) = &self.frame_edit {
            return view.selection_outline_mask();
        }
        let mut bytes = 1u32.to_le_bytes().to_vec();
        if let Some(selection) = &self.selection {
            bytes.extend(selection.outline_mask());
        } else {
            bytes.extend(SELECTION_PREVIEW_TILE_SIZE.to_le_bytes());
            bytes.extend(0u32.to_le_bytes());
        }
        bytes
    }
    pub fn layer_bounds(&self) -> Result<Rect, String> {
        if let Some(view) = &self.frame_edit {
            return view.layer_bounds();
        }
        let layer = self
            .document
            .layers
            .iter()
            .find(|layer| layer.id == self.document.active)
            .unwrap();
        if self.mask_editing {
            let bounds = self
                .document
                .selected_mask()
                .ok_or("当前图层没有蒙版")?
                .plane
                .bounds;
            return Ok(Rect {
                left: bounds.left.max(0) as u32,
                top: bounds.top.max(0) as u32,
                right: bounds.right.clamp(0, self.document.width as i32) as u32,
                bottom: bounds.bottom.clamp(0, self.document.height as i32) as u32,
            });
        }
        if layer.is_group() {
            return groups::bounds(&self.document, layer.id);
        }
        transform::bounds(
            layer,
            self.document.bounds(),
            self.document.palette.as_ref(),
        )
        .ok_or_else(|| "当前图层没有可变换的内容".into())
    }
    pub fn layer_transform_bounds(&self) -> Result<MaskBounds, String> {
        if let Some(view) = &self.frame_edit {
            return view.layer_transform_bounds();
        }
        if self.mask_editing {
            let mask = &self
                .document
                .selected_mask()
                .ok_or("当前图层没有蒙版")?
                .plane;
            if mask.bounds.width() == 0 || mask.bounds.height() == 0 {
                return Err("蒙版没有可变换的内容".into());
            }
            return Ok(mask.bounds);
        }
        let bounds = self.layer_bounds()?;
        Ok(MaskBounds {
            left: bounds.left as i32,
            top: bounds.top as i32,
            right: bounds.right as i32,
            bottom: bounds.bottom as i32,
        })
    }
    pub fn export_png(&self) -> Result<Vec<u8>, String> {
        if self.document.animation.is_some() {
            return Err("动画导出必须明确选择帧".into());
        }
        storage::export_png(&self.document)
    }
    pub fn export_image(&self, options: ExportOptions) -> Result<Vec<u8>, String> {
        storage::export_image(&self.frame_snapshot()?, options)
    }
    pub fn thumbnail(&self) -> Vec<u8> {
        if let Some(view) = &self.frame_edit {
            return view.thumbnail();
        }
        previews::thumbnail(&self.document)
    }
    pub fn mask_previews(&self) -> Vec<u8> {
        if let Some(view) = &self.frame_edit {
            return view.mask_previews();
        }
        previews::render_masks(&self.document, self.revision)
    }
    pub fn mask_stack_previews(&self) -> Vec<u8> {
        if let Some(view) = &self.frame_edit {
            return view.mask_stack_previews();
        }
        previews::render_mask_stack(&self.document, self.revision)
    }
    pub fn extract_palette(&self, count: usize) -> Result<Vec<[u8; 3]>, String> {
        if let Some(view) = &self.frame_edit {
            return view.extract_palette(count);
        }
        if self.stroke.is_some() {
            return Err("请先结束笔画".into());
        }
        palette::extract(&self.document, count)
    }
    pub fn previews(&mut self) -> Result<Vec<u8>, String> {
        if let Some(view) = &mut self.frame_edit {
            return view.previews();
        }
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
        self.install_document(doc)
    }
    pub(crate) fn install_document(&mut self, doc: Document) -> Result<(), String> {
        doc.validate()?;
        let old_keys: BTreeSet<_> = self
            .document
            .layers
            .iter()
            .flat_map(|layer| layer.content_keys(self.document.bounds()))
            .chain(self.frame_edit.iter().flat_map(|view| {
                view.document
                    .layers
                    .iter()
                    .flat_map(|layer| layer.content_keys(view.document.bounds()))
            }))
            .collect();
        let context = if let Some(animation) = &doc.animation {
            let mut view = Engine::new(doc.width, doc.height)?;
            view.document = animation::view(&doc, animation.active_frame)?;
            view.revision = self.revision + 1;
            view.content_id = view.revision;
            view.transparent_frame = self.transparent_frame;
            view.dirty.extend(old_keys.iter().copied());
            view.mark_all();
            Some(Box::new(view))
        } else {
            None
        };
        self.mark_all();
        self.dirty.extend(old_keys);
        self.document = doc;
        self.vector_cache = Default::default();
        self.mark_all();
        self.stroke = None;
        self.mask_editing = false;
        self.history = History::default();
        self.selection = None;
        self.revision += 1;
        self.content_id = self.revision;
        self.frame_edit = context;
        Ok(())
    }
}
