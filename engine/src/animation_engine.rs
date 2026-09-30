use crate::{
    animation::{self},
    history::History,
    model::*,
    Command, Engine,
};
use serde::{Deserialize, Deserializer};
use serde_json::Value;
use std::{collections::BTreeSet, sync::Arc};

pub struct CommandRequest {
    pub command: Command,
    pub frame_id: Option<u32>,
    pub cel_id: Option<Option<u32>>,
    pub revision: Option<u64>,
    pub target_layer_id: Option<u32>,
}

impl<'de> Deserialize<'de> for CommandRequest {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = Value::deserialize(deserializer)?;
        let frame_id = value
            .get("frame_id")
            .map(|value| serde_json::from_value::<Option<u32>>(value.clone()))
            .transpose()
            .map_err(serde::de::Error::custom)?
            .flatten();
        let cel_id = value
            .get("cel_id")
            .map(|value| serde_json::from_value(value.clone()))
            .transpose()
            .map_err(serde::de::Error::custom)?;
        let revision = value
            .get("revision")
            .map(|value| serde_json::from_value::<Option<u64>>(value.clone()))
            .transpose()
            .map_err(serde::de::Error::custom)?
            .flatten();
        let target_layer_id = value
            .get("target_layer_id")
            .map(|value| serde_json::from_value::<Option<u32>>(value.clone()))
            .transpose()
            .map_err(serde::de::Error::custom)?
            .flatten();
        let command = serde_json::from_value(value).map_err(serde::de::Error::custom)?;
        Ok(Self {
            command,
            frame_id,
            cel_id,
            revision,
            target_layer_id,
        })
    }
}

#[derive(Clone, Deserialize)]
pub struct FrameRenderRequest {
    pub revision: u64,
    pub frame_id: u32,
    pub transparent: bool,
    #[serde(default)]
    pub region: Option<Rect>,
}

#[derive(Clone, Deserialize)]
pub struct FramePreviewsRequest {
    pub revision: u64,
    pub frame_ids: Vec<u32>,
    #[serde(default = "preview_size")]
    pub size: u32,
}
fn preview_size() -> u32 {
    PREVIEW_EDGE
}

pub(crate) fn optional_cel<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<Option<u32>>, D::Error> {
    Option::<u32>::deserialize(deserializer).map(Some)
}

pub fn timeline_revision(command: &Command) -> Option<u64> {
    match command {
        Command::EnableAnimation { revision, .. }
        | Command::AddFrame { revision, .. }
        | Command::DuplicateFrame { revision, .. }
        | Command::DeleteFrame { revision, .. }
        | Command::ReorderFrames { revision, .. }
        | Command::SetFrameDuration { revision, .. }
        | Command::SelectFrame { revision, .. }
        | Command::NewCel { revision, .. }
        | Command::ClearCel { revision, .. }
        | Command::LinkCel { revision, .. }
        | Command::UnlinkCel { revision, .. }
        | Command::AddFrameTag { revision, .. }
        | Command::SetFrameTag { revision, .. }
        | Command::DeleteFrameTag { revision, .. } => Some(*revision),
        _ => None,
    }
}

impl Engine {
    pub fn command_request(&mut self, request: CommandRequest) -> Result<Value, String> {
        if self.document.animation.is_some()
            && timeline_revision(&request.command).is_none()
            && !matches!(
                request.command,
                Command::State
                    | Command::Undo
                    | Command::Redo
                    | Command::New { .. }
                    | Command::NewIndexed { .. }
            )
        {
            self.check_frame_target(
                request.frame_id,
                request.cel_id,
                request.revision,
                request.target_layer_id,
            )?;
        }
        self.command(request.command)
    }

    pub(crate) fn check_frame_target(
        &self,
        frame: Option<u32>,
        cel: Option<Option<u32>>,
        revision: Option<u64>,
        layer: Option<u32>,
    ) -> Result<(), String> {
        if let Some(animation) = &self.document.animation {
            if frame != Some(animation.active_frame)
                || cel != Some(animation.active_cel(self.document.active).map(|cel| cel.id))
                || revision != Some(self.revision)
                || layer != Some(self.document.active)
            {
                return Err("动画帧、Cel 或图层已变化，请重试".into());
            }
        }
        Ok(())
    }

    pub(crate) fn has_live_stroke(&self) -> bool {
        self.stroke.is_some()
            || self
                .frame_edit
                .as_ref()
                .is_some_and(|view| view.stroke.is_some())
    }

    pub(crate) fn reset_frame_context(&mut self) -> Result<(), String> {
        let previous = self.frame_edit.take();
        let Some(animation) = &self.document.animation else {
            if let Some(previous) = previous {
                self.dirty.extend(
                    previous
                        .document
                        .layers
                        .iter()
                        .flat_map(|layer| layer.content_keys(previous.document.bounds())),
                );
            }
            return Ok(());
        };
        let mut view = Engine::new(self.document.width, self.document.height)?;
        view.document = animation::view(&self.document, animation.active_frame)?;
        view.revision = self.revision;
        view.content_id = self.content_id;
        view.mask_editing = self.mask_editing && !view.document.active_masks().is_empty();
        view.selection = self.selection.clone();
        view.selection_id = self.selection_id;
        view.transparent_frame = self.transparent_frame;
        view.vector_cache = self.vector_cache.clone();
        if let Some(mut previous) = previous {
            view.dirty.extend(
                previous
                    .document
                    .layers
                    .iter()
                    .flat_map(|layer| layer.content_keys(previous.document.bounds())),
            );
            view.preview_job = previous.preview_job.take();
            view.indexed_cache = std::mem::take(&mut previous.indexed_cache);
        }
        view.mark_all();
        self.mask_editing = view.mask_editing;
        self.frame_edit = Some(Box::new(view));
        Ok(())
    }

    pub(crate) fn animation_state(&self, mut state: Value) -> Value {
        state["asepriteExport"] = crate::aseprite::capabilities(&self.document);
        state["asepriteMetadata"] = self
            .document
            .aseprite_metadata
            .as_ref()
            .map(|metadata| metadata.json())
            .unwrap_or(Value::Null);
        state["revision"] = self.revision.into();
        state["contentId"] = self.content_id.into();
        state["canUndo"] = (!self.history.undo.is_empty()).into();
        state["canRedo"] = (!self.history.redo.is_empty()).into();
        state["animation"] = self
            .document
            .animation
            .as_ref()
            .map_or(Value::Null, |animation| {
                animation.json(self.document.active)
            });
        state["maxAnimationFrames"] = MAX_ANIMATION_FRAMES.into();
        state["maxAnimationCels"] = MAX_ANIMATION_CELS.into();
        state["maxAnimationTags"] = MAX_ANIMATION_TAGS.into();
        state["maxFrameThumbnails"] = MAX_FRAME_THUMBNAILS.into();
        if let Some(layers) = state["layers"].as_array_mut() {
            for layer in layers {
                let id = layer["id"].as_u64().unwrap() as u32;
                let cel = self
                    .document
                    .animation
                    .as_ref()
                    .and_then(|animation| animation.active_cel(id));
                layer["celId"] = cel.map(|cel| cel.id).into();
                layer["hasCel"] = cel.is_some().into();
                layer["maskScope"] = if self.document.layers.iter().any(|layer| {
                    layer.id == id && matches!(layer.content, LayerContent::CelTrack { .. })
                }) {
                    "cel"
                } else {
                    "global"
                }
                .into();
            }
        }
        state
    }

    pub(crate) fn animation_command(&mut self, command: Command) -> Result<Value, String> {
        if timeline_revision(&command) != Some(self.revision) || self.has_live_stroke() {
            return Err("动画已变化或笔画尚未结束".into());
        }
        if let Command::SelectFrame { frame_id, .. } = command {
            let animation = self.document.animation.as_ref().ok_or("请先开启动画")?;
            animation.frame(frame_id)?;
            if animation.active_frame != frame_id {
                Arc::make_mut(self.document.animation.as_mut().unwrap()).active_frame = frame_id;
                self.document.normalize_mask_target();
                self.mask_editing = false;
                self.set_selection(None);
                self.revision += 1;
                self.reset_frame_context()?;
            }
            return Ok(self.state());
        }
        let pixels_changed = !matches!(
            command,
            Command::SetFrameDuration { .. }
                | Command::AddFrameTag { .. }
                | Command::SetFrameTag { .. }
                | Command::DeleteFrameTag { .. }
                | Command::ReorderFrames { .. }
                | Command::EnableAnimation { .. }
        );
        let next = animation::prepare(&self.document, command)?;
        if next != self.document {
            self.commit_document(next, pixels_changed)?;
            if pixels_changed {
                self.set_selection(None);
                self.mask_editing = false;
            }
            if pixels_changed || self.frame_edit.is_none() {
                self.reset_frame_context()?;
            } else if let Some(view) = &mut self.frame_edit {
                view.revision = self.revision;
                view.content_id = self.content_id;
            }
        }
        Ok(self.state())
    }

    pub(crate) fn frame_command(&mut self, command: Command) -> Result<Value, String> {
        if self.frame_edit.is_none() {
            self.reset_frame_context()?;
        }
        if let Command::TranslateLayer { mask_id, .. } | Command::TransformLayer { mask_id, .. } =
            &command
        {
            self.check_mask_target(*mask_id)?;
        }
        if matches!(&command,Command::TransformLayer{id,..} if *id!=self.document.active) {
            return Err("动画当前图层已变化".into());
        }
        if self.has_live_stroke()
            && matches!(
                command,
                Command::ResizeCanvas { .. }
                    | Command::ResizeImage { .. }
                    | Command::ConvertColorMode { .. }
                    | Command::RemovePaletteColor { .. }
                    | Command::RasterizeVector { .. }
                    | Command::DuplicateLayer { .. }
                    | Command::TranslateLayer { .. }
                    | Command::TransformLayer { .. }
            )
        {
            return Err("请先结束当前笔画".into());
        }
        if let Some(next) = animation::global(
            &self.document,
            &command,
            self.revision,
            self.mask_editing,
            self.selection.is_some(),
        )? {
            if self.has_live_stroke() {
                return Err("请先结束笔画".into());
            }
            if next == self.document {
                return Ok(self.state());
            }
            self.commit_document(next, true)?;
            self.set_selection(None);
            self.reset_frame_context()?;
            return Ok(self.state());
        }
        if matches!(command, Command::MergeVisible) {
            return Err("跨帧轨道合并尚未支持，请先逐帧导出副本".into());
        }
        let mut view = self.frame_edit.take().ok_or("动画编辑上下文不可用")?;
        let dirty_before = view.dirty.clone();
        let had_stroke = view.stroke.is_some();
        view.revision = self.revision;
        view.content_id = self.content_id;
        view.history = History::default();
        let response = match view.command(command) {
            Ok(response) => response,
            Err(error) => {
                self.frame_edit = Some(view);
                return Err(error);
            }
        };
        if let Some(event) = view.history.undo.back() {
            let next = match animation::merge_view(&self.document, &view.document, false) {
                Ok(next) => next,
                Err(error) => {
                    self.restore_frame_failure(view, dirty_before, had_stroke)?;
                    return Err(error);
                }
            };
            if let Err(error) = crate::masks::check_transaction(&self.document, &next) {
                self.restore_frame_failure(view, dirty_before, had_stroke)?;
                return Err(error);
            }
            if next != self.document {
                let before = std::mem::replace(&mut self.document, next);
                self.history.push(
                    before,
                    self.content_id,
                    &self.document,
                    event.pixels_changed,
                );
                self.revision = view.revision;
                self.content_id = self.revision;
            }
        } else {
            self.document.active = view.document.active;
            self.document.active_mask_id = view.document.active_mask_id;
        }
        self.mask_editing = view.mask_editing;
        self.selection = view.selection.clone();
        self.selection_id = view.selection_id;
        view.revision = self.revision;
        view.content_id = self.content_id;
        view.history = History::default();
        self.frame_edit = Some(view);
        Ok(if response.get("width").is_some() {
            self.state()
        } else {
            response
        })
    }

    pub(crate) fn frame_samples(&mut self, samples: &[Sample]) -> Result<(), String> {
        let mut view = self.frame_edit.take().ok_or("尚未开始动画笔画")?;
        let result = view
            .samples(samples)
            .and_then(|_| animation::stroke_candidate(&self.document, &view.document).map(|_| ()));
        if let Err(error) = result {
            self.frame_edit = Some(view);
            self.reset_frame_context()?;
            return Err(error);
        }
        self.frame_edit = Some(view);
        Ok(())
    }

    pub(crate) fn frame_external(
        &mut self,
        operation: impl FnOnce(&mut Engine) -> Result<(), String>,
    ) -> Result<(), String> {
        let mut view = self.frame_edit.take().ok_or("动画编辑上下文不可用")?;
        let dirty_before = view.dirty.clone();
        let had_stroke = view.stroke.is_some();
        view.revision = self.revision;
        view.content_id = self.content_id;
        view.history = History::default();
        if let Err(error) = operation(&mut view) {
            self.frame_edit = Some(view);
            return Err(error);
        }
        if let Some(event) = view.history.undo.back() {
            let next = match animation::merge_view(&self.document, &view.document, false).and_then(
                |next| crate::masks::check_transaction(&self.document, &next).map(|_| next),
            ) {
                Ok(next) => next,
                Err(error) => {
                    self.restore_frame_failure(view, dirty_before, had_stroke)?;
                    return Err(error);
                }
            };
            if next != self.document {
                let before = std::mem::replace(&mut self.document, next);
                self.history.push(
                    before,
                    self.content_id,
                    &self.document,
                    event.pixels_changed,
                );
                self.revision = view.revision;
                self.content_id = self.revision;
            }
        }
        self.mask_editing = view.mask_editing;
        self.selection = view.selection.clone();
        self.selection_id = view.selection_id;
        view.history = History::default();
        view.revision = self.revision;
        view.content_id = self.content_id;
        self.frame_edit = Some(view);
        Ok(())
    }

    fn restore_frame_failure(
        &mut self,
        view: Box<Engine>,
        dirty: BTreeSet<TileKey>,
        had_stroke: bool,
    ) -> Result<(), String> {
        self.frame_edit = Some(view);
        self.reset_frame_context()?;
        if !had_stroke {
            self.frame_edit.as_mut().unwrap().dirty = dirty;
        }
        Ok(())
    }

    pub(crate) fn frame_snapshot(&self) -> Result<Document, String> {
        if let Some(view) = self
            .frame_edit
            .as_ref()
            .filter(|view| view.stroke.is_some())
        {
            animation::stroke_candidate(&self.document, &view.document)
        } else {
            Ok(self.document.clone())
        }
    }

    pub fn render_animation_frame(&self, request: FrameRenderRequest) -> Result<Vec<u8>, String> {
        if request.revision != self.revision {
            return Err("动画已变化，请重新请求帧".into());
        }
        render_frame(&self.frame_snapshot()?, request, self.vector_cache.clone())
    }
    pub fn animation_previews(&self, request: FramePreviewsRequest) -> Result<Vec<u8>, String> {
        if request.revision != self.revision {
            return Err("动画缩略图请求已过期".into());
        }
        frame_previews(&self.frame_snapshot()?, request, self.vector_cache.clone())
    }
}

pub(crate) fn frame_previews(
    document: &Document,
    request: FramePreviewsRequest,
    cache: Arc<std::sync::Mutex<crate::vector::RenderCache>>,
) -> Result<Vec<u8>, String> {
    let animation = document.animation.as_ref().ok_or("工程没有动画")?;
    if request.size != PREVIEW_EDGE
        || request.frame_ids.is_empty()
        || request.frame_ids.len() > MAX_FRAME_THUMBNAILS
        || request
            .frame_ids
            .iter()
            .copied()
            .collect::<BTreeSet<_>>()
            .len()
            != request.frame_ids.len()
    {
        return Err("动画缩略图数量、尺寸或帧编号无效".into());
    }
    for &id in &request.frame_ids {
        animation.frame(id)?;
    }
    let mut output = Vec::with_capacity(
        16 + request.frame_ids.len() * (4 + (PREVIEW_EDGE * PREVIEW_EDGE * 4) as usize),
    );
    output.extend(request.revision.to_le_bytes());
    output.extend(PREVIEW_EDGE.to_le_bytes());
    output.extend((request.frame_ids.len() as u32).to_le_bytes());
    let mut images = std::collections::BTreeMap::new();
    for id in request.frame_ids {
        let frame = animation.frame(id)?;
        let signature: Vec<_> = frame
            .exposures
            .iter()
            .map(|(&layer, &cel)| (layer, cel))
            .collect();
        let pixels = images.entry(signature).or_insert_with(|| {
            let view = animation::view(document, id).expect("已验证的动画帧");
            crate::previews::frame_thumbnail(&view, PREVIEW_EDGE, cache.clone())
        });
        output.extend(id.to_le_bytes());
        output.extend_from_slice(pixels);
    }
    Ok(output)
}

pub(crate) fn render_frame(
    document: &Document,
    request: FrameRenderRequest,
    cache: Arc<std::sync::Mutex<crate::vector::RenderCache>>,
) -> Result<Vec<u8>, String> {
    let doc = animation::view(document, request.frame_id)?;
    let bounds = doc.bounds();
    let region = match request.region {
        Some(region) => region.intersect(bounds).ok_or("帧渲染范围无效")?,
        None => bounds,
    };
    let mut keys: BTreeSet<_> = if request.transparent {
        doc.layers
            .iter()
            .flat_map(|layer| layer.content_keys(bounds))
            .collect()
    } else {
        (region.top / TILE_SIZE..=(region.bottom - 1) / TILE_SIZE)
            .flat_map(|y| {
                (region.left / TILE_SIZE..=(region.right - 1) / TILE_SIZE).map(move |x| (x, y))
            })
            .collect()
    };
    keys.retain(|&(x, y)| {
        Rect {
            left: x * TILE_SIZE,
            top: y * TILE_SIZE,
            right: (x + 1) * TILE_SIZE,
            bottom: (y + 1) * TILE_SIZE,
        }
        .intersect(region)
        .is_some()
    });
    let mut compositor =
        crate::raster::FrameCompositor::with_vector_cache(&doc, request.transparent, cache);
    let mut output = Vec::with_capacity(16 + keys.len() * (TILE_BYTES + 8));
    for value in [doc.width, doc.height, TILE_SIZE, keys.len() as u32] {
        output.extend(value.to_le_bytes());
    }
    for key in keys {
        output.extend(key.0.to_le_bytes());
        output.extend(key.1.to_le_bytes());
        output.extend(compositor.tile(&doc, key));
    }
    Ok(output)
}
