use crate::{model::*, vector::VectorLayer};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    sync::Arc,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum CelKind {
    Raster,
    Vector,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum CelSource {
    Raster(Arc<RasterPlane>),
    Vector(Arc<VectorLayer>),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Cel {
    pub id: u32,
    pub layer_id: u32,
    pub source: CelSource,
    pub masks: Vec<MaskEntry>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Frame {
    pub id: u32,
    pub duration_ms: u32,
    pub exposures: BTreeMap<u32, u32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TagDirection {
    Forward,
    Reverse,
    PingPong,
    PingPongReverse,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FrameTag {
    pub id: u32,
    pub name: String,
    pub color: [u8; 4],
    pub from_frame: u32,
    pub to_frame: u32,
    pub direction: TagDirection,
    pub repeat: u16,
}

#[derive(Clone, Debug, Deserialize)]
pub struct TagSpec {
    pub name: String,
    pub color: [u8; 4],
    pub from_frame: u32,
    pub to_frame: u32,
    pub direction: TagDirection,
    pub repeat: u16,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AnimationSet {
    pub frames: Vec<Frame>,
    pub cels: BTreeMap<u32, Arc<Cel>>,
    pub active_frame: u32,
    pub next_frame_id: u32,
    pub next_cel_id: u32,
    pub next_tag_id: u32,
    pub tags: Vec<FrameTag>,
}

impl Cel {
    pub fn kind(&self) -> CelKind {
        match self.source {
            CelSource::Raster(_) => CelKind::Raster,
            CelSource::Vector(_) => CelKind::Vector,
        }
    }
    pub fn buffers(&self) -> impl Iterator<Item = &Tile> {
        let raster = match &self.source {
            CelSource::Raster(raster) => Some(raster.as_ref()),
            _ => None,
        };
        raster
            .into_iter()
            .flat_map(|raster| raster.tiles().values())
            .chain(self.masks.iter().flat_map(|mask| mask.plane.tiles.values()))
    }
    pub fn vector_resources(&self) -> impl Iterator<Item = (usize, usize)> + '_ {
        let vector = match &self.source {
            CelSource::Vector(vector) => Some(vector),
            _ => None,
        };
        let raster = match &self.source {
            CelSource::Raster(raster) => Some((
                Arc::as_ptr(raster) as usize,
                std::mem::size_of::<RasterPlane>() + raster.tiles().len() * 64,
            )),
            _ => None,
        };
        std::iter::once((
            self as *const Self as usize,
            std::mem::size_of::<Self>()
                + self
                    .masks
                    .iter()
                    .map(|mask| mask.name.len() + mask.plane.tiles.len() * 64)
                    .sum::<usize>(),
        ))
        .chain(vector.into_iter().flat_map(|vector| {
            std::iter::once((Arc::as_ptr(vector) as usize, vector.own_bytes())).chain(
                vector
                    .objects
                    .iter()
                    .map(|object| (Arc::as_ptr(object) as usize, object.bytes())),
            )
        }))
        .chain(raster)
    }
}

impl AnimationSet {
    pub fn frame(&self, id: u32) -> Result<&Frame, String> {
        self.frames
            .iter()
            .find(|frame| frame.id == id)
            .ok_or_else(|| "动画帧不存在".into())
    }
    pub fn active_cel(&self, layer_id: u32) -> Option<&Arc<Cel>> {
        self.frame(self.active_frame)
            .ok()?
            .exposures
            .get(&layer_id)
            .and_then(|id| self.cels.get(id))
    }
    pub fn metadata_bytes(&self) -> usize {
        let mut rasters = HashSet::new();
        let sources = self
            .cels
            .values()
            .map(|cel| {
                std::mem::size_of::<Cel>()
                    + cel
                        .masks
                        .iter()
                        .map(|mask| mask.name.len() + mask.plane.tiles.len() * 64)
                        .sum::<usize>()
                    + match &cel.source {
                        CelSource::Raster(source)
                            if rasters.insert(Arc::as_ptr(source) as usize) =>
                        {
                            std::mem::size_of::<RasterPlane>() + source.tiles().len() * 64
                        }
                        _ => 0,
                    }
            })
            .sum::<usize>();
        std::mem::size_of::<Self>()
            + self
                .frames
                .iter()
                .map(|frame| std::mem::size_of::<Frame>() + frame.exposures.len() * 32)
                .sum::<usize>()
            + self.cels.len() * 32
            + self
                .tags
                .iter()
                .map(|tag| std::mem::size_of::<FrameTag>() + tag.name.len())
                .sum::<usize>()
            + sources
    }
    pub fn json(&self, active: u32) -> Value {
        json!({"enabled":true,"activeFrameId":self.active_frame,"activeCelId":self.active_cel(active).map(|cel|cel.id),"maxFrames":MAX_ANIMATION_FRAMES,"maxCels":MAX_ANIMATION_CELS,"maxTags":MAX_ANIMATION_TAGS,
        "frames":self.frames.iter().map(|frame|json!({"id":frame.id,"durationMs":frame.duration_ms,"cels":frame.exposures.iter().map(|(&layer,&cel)|json!({"layerId":layer,"celId":cel})).collect::<Vec<_>>()})).collect::<Vec<_>>(),
        "tags":self.tags.iter().map(|tag|json!({"id":tag.id,"name":tag.name,"fromFrame":tag.from_frame,"toFrame":tag.to_frame,"direction":tag.direction,"repeat":tag.repeat,"color":tag.color})).collect::<Vec<_>>()})
    }
    pub fn collect_orphans(&mut self) {
        let used: BTreeSet<_> = self
            .frames
            .iter()
            .flat_map(|frame| frame.exposures.values().copied())
            .collect();
        self.cels.retain(|id, _| used.contains(id));
    }
}

fn next(counter: &mut u32) -> Result<u32, String> {
    let id = *counter;
    *counter = counter.checked_add(1).ok_or("动画编号超出限制")?;
    Ok(id)
}

fn blank(kind: CelKind, indexed: bool) -> CelSource {
    match kind {
        CelKind::Raster => CelSource::Raster(Arc::new(if indexed {
            RasterPlane::Indexed(BTreeMap::new())
        } else {
            RasterPlane::Rgba(BTreeMap::new())
        })),
        CelKind::Vector => CelSource::Vector(Arc::new(VectorLayer {
            objects: Vec::new(),
            next_object_id: 1,
        })),
    }
}

pub fn view(document: &Document, frame_id: u32) -> Result<Document, String> {
    let animation = document.animation.as_ref().ok_or("工程没有动画")?;
    let frame = animation.frame(frame_id)?;
    let mut result = document.clone();
    result.animation = None;
    for layer in &mut result.layers {
        if let LayerContent::CelTrack { kind } = layer.content {
            let cel = frame
                .exposures
                .get(&layer.id)
                .and_then(|id| animation.cels.get(id));
            let source = cel.map_or_else(
                || blank(kind, document.palette.is_some()),
                |cel| cel.source.clone(),
            );
            layer.content = match source {
                CelSource::Raster(source) => LayerContent::Raster((*source).clone()),
                CelSource::Vector(source) => LayerContent::Vector(source),
            };
            layer.masks = cel.map_or_else(Vec::new, |cel| cel.masks.clone());
        }
    }
    result.normalize_mask_target();
    Ok(result)
}

pub fn enable(document: &Document, duration_ms: u32) -> Result<Document, String> {
    duration(duration_ms)?;
    if document.animation.is_some() {
        return Err("动画已经开启".into());
    }
    let mut result = document.clone();
    let mut animation = AnimationSet {
        frames: vec![Frame {
            id: 1,
            duration_ms,
            exposures: BTreeMap::new(),
        }],
        cels: BTreeMap::new(),
        active_frame: 1,
        next_frame_id: 2,
        next_cel_id: 1,
        next_tag_id: 1,
        tags: Vec::new(),
    };
    for layer in &mut result.layers {
        let source = match &layer.content {
            LayerContent::Raster(source) => CelSource::Raster(Arc::new(source.clone())),
            LayerContent::Vector(source) => CelSource::Vector(source.clone()),
            _ => continue,
        };
        let id = next(&mut animation.next_cel_id)?;
        let cel = Cel {
            id,
            layer_id: layer.id,
            source,
            masks: std::mem::take(&mut layer.masks),
        };
        layer.content = LayerContent::CelTrack { kind: cel.kind() };
        animation.frames[0].exposures.insert(layer.id, id);
        animation.cels.insert(id, Arc::new(cel));
    }
    result.animation = Some(Arc::new(animation));
    result.validate()?;
    Ok(result)
}

pub fn duration(value: u32) -> Result<(), String> {
    if value == 0 || value > MAX_FRAME_DURATION_MS {
        Err("动画帧时长无效".into())
    } else {
        Ok(())
    }
}

pub fn validate(document: &Document) -> Result<(), String> {
    crate::aseprite::validate_metadata(document)?;
    let animation = document.animation.as_ref().ok_or("工程没有动画")?;
    if animation.frames.is_empty()
        || animation.frames.len() > MAX_ANIMATION_FRAMES
        || animation.cels.len() > MAX_ANIMATION_CELS
        || animation.tags.len() > MAX_ANIMATION_TAGS
        || animation.metadata_bytes() > MAX_ANIMATION_METADATA_BYTES
        || animation.next_frame_id == 0
        || animation.next_cel_id == 0
        || animation.next_tag_id == 0
    {
        return Err("动画数据超过限制".into());
    }
    let mut frame_ids = BTreeSet::new();
    let mut used = BTreeSet::new();
    let mut masks = BTreeSet::new();
    let mut vectors = HashSet::new();
    let mut geometry_bytes = 0;
    let mut segments = 0;
    for layer in &document.layers {
        match layer.content {
            LayerContent::CelTrack { .. } if layer.masks.is_empty() => {}
            LayerContent::Group { .. } | LayerContent::Adjustment { .. } => {
                for mask in &layer.masks {
                    masks.insert(mask.id);
                }
            }
            _ => return Err("动画轨道源必须位于 Cel".into()),
        }
    }
    for frame in &animation.frames {
        duration(frame.duration_ms)?;
        if frame.id == 0 || frame.id >= animation.next_frame_id || !frame_ids.insert(frame.id) {
            return Err("动画帧编号无效".into());
        }
        for (&layer_id, &cel_id) in &frame.exposures {
            let cel = animation.cels.get(&cel_id).ok_or("动画 Cel 引用无效")?;
            let layer = document
                .layers
                .iter()
                .find(|layer| layer.id == layer_id)
                .ok_or("动画轨道不存在")?;
            if !matches!(layer.content, LayerContent::CelTrack { kind } if kind == cel.kind())
                || cel.layer_id != layer_id
            {
                return Err("动画 Cel 轨道不匹配".into());
            }
            used.insert(cel_id);
        }
    }
    if !frame_ids.contains(&animation.active_frame) || used.len() != animation.cels.len() {
        return Err("动画活动帧或孤立 Cel 无效".into());
    }
    for (&id, cel) in &animation.cels {
        if id == 0
            || id >= animation.next_cel_id
            || id != cel.id
            || cel.masks.len() > MAX_LAYER_MASKS
        {
            return Err("动画 Cel 属性无效".into());
        }
        for mask in &cel.masks {
            mask.plane.validate()?;
            if mask.id == 0
                || mask.id >= document.next_mask_id
                || !masks.insert(mask.id)
                || mask.name.is_empty()
                || mask.name.len() > MAX_LAYER_NAME_BYTES
            {
                return Err("动画蒙版编号无效".into());
            }
        }
        match &cel.source {
            CelSource::Raster(raster) => {
                if raster.is_indexed() != document.palette.is_some() {
                    return Err("动画 Cel 颜色模式不一致".into());
                }
                for (&(x, y), tile) in raster.tiles() {
                    if x >= document.width.div_ceil(TILE_SIZE)
                        || y >= document.height.div_ceil(TILE_SIZE)
                        || tile.len() != raster.tile_bytes()
                        || (raster.is_indexed()
                            && tile.iter().any(|&index| {
                                usize::from(index)
                                    >= document.palette.as_ref().unwrap().colors.len()
                            }))
                    {
                        return Err("动画 Cel 像素无效".into());
                    }
                }
            }
            CelSource::Vector(vector) => {
                if document.palette.is_some() {
                    return Err("索引色不支持矢量轨道".into());
                }
                if vectors.insert(Arc::as_ptr(vector) as usize) {
                    vector.validate()?;
                    geometry_bytes += vector.bytes();
                    segments += vector.segment_count();
                }
            }
        }
    }
    if geometry_bytes > MAX_VECTOR_GEOMETRY_BYTES
        || segments > MAX_DOCUMENT_VECTOR_SEGMENTS
        || document.pixel_bytes() > MAX_DOCUMENT_BYTES
    {
        return Err("动画源超过内存限制".into());
    }
    let mut tag_ids = BTreeSet::new();
    for tag in &animation.tags {
        if tag.id == 0
            || tag.id >= animation.next_tag_id
            || !tag_ids.insert(tag.id)
            || tag.name.is_empty()
            || tag.name.len() > MAX_LAYER_NAME_BYTES
            || !frame_ids.contains(&tag.from_frame)
            || !frame_ids.contains(&tag.to_frame)
        {
            return Err("动画标签无效".into());
        }
        let from = animation
            .frames
            .iter()
            .position(|frame| frame.id == tag.from_frame)
            .unwrap();
        let to = animation
            .frames
            .iter()
            .position(|frame| frame.id == tag.to_frame)
            .unwrap();
        if from > to {
            return Err("动画标签范围无效".into());
        }
    }
    view(document, animation.active_frame)?.validate()?;
    Ok(())
}

fn same_raster(a: &RasterPlane, b: &RasterPlane) -> bool {
    a.is_indexed() == b.is_indexed()
        && a.tiles().len() == b.tiles().len()
        && a.tiles().iter().all(|(key, tile)| {
            b.tiles()
                .get(key)
                .is_some_and(|other| Arc::ptr_eq(tile, other))
        })
}
fn same_masks(a: &[MaskEntry], b: &[MaskEntry]) -> bool {
    a.len() == b.len()
        && a.iter().zip(b).all(|(a, b)| {
            a.id == b.id
                && a.name == b.name
                && a.plane.bounds == b.plane.bounds
                && a.plane.default == b.plane.default
                && a.plane.enabled == b.plane.enabled
                && a.plane.linked == b.plane.linked
                && a.plane.tiles.len() == b.plane.tiles.len()
                && a.plane.tiles.iter().all(|(key, tile)| {
                    b.plane
                        .tiles
                        .get(key)
                        .is_some_and(|other| Arc::ptr_eq(tile, other))
                })
        })
}

pub fn merge_view(document: &Document, edited: &Document, force: bool) -> Result<Document, String> {
    merge(document, edited, force, true)
}

pub(crate) fn stroke_candidate(document: &Document, edited: &Document) -> Result<Document, String> {
    merge(document, edited, false, false)
}

fn merge(
    document: &Document,
    edited: &Document,
    force: bool,
    validate: bool,
) -> Result<Document, String> {
    let mut result = edited.clone();
    let mut animation = document
        .animation
        .as_ref()
        .ok_or("工程没有动画")?
        .as_ref()
        .clone();
    let frame_index = animation
        .frames
        .iter()
        .position(|frame| frame.id == animation.active_frame)
        .ok_or("动画帧不存在")?;
    let layer_ids: BTreeSet<_> = edited.layers.iter().map(|layer| layer.id).collect();
    for frame in &mut animation.frames {
        frame.exposures.retain(|layer, _| layer_ids.contains(layer));
    }
    for layer in &mut result.layers {
        let (kind, source) = match &layer.content {
            LayerContent::Raster(source) => {
                let mut source = source.clone();
                if let RasterPlane::Rgba(tiles) = &mut source {
                    tiles.retain(|_, tile| tile.iter().any(|&byte| byte != 0));
                }
                (CelKind::Raster, CelSource::Raster(Arc::new(source)))
            }
            LayerContent::Vector(source) => (CelKind::Vector, CelSource::Vector(source.clone())),
            _ => continue,
        };
        let cel_id = animation.frames[frame_index]
            .exposures
            .get(&layer.id)
            .copied();
        let old = cel_id.and_then(|id| animation.cels.get(&id));
        let source_changed = old.is_none_or(|cel| match (&cel.source, &source) {
            (CelSource::Raster(a), CelSource::Raster(b)) => !same_raster(a, b),
            (CelSource::Vector(a), CelSource::Vector(b)) => !Arc::ptr_eq(a, b),
            _ => true,
        });
        let has_content = match &source {
            CelSource::Raster(source) => !source.tiles().is_empty(),
            CelSource::Vector(source) => !source.objects.is_empty(),
        } || !layer.masks.is_empty();
        let mask_changed = old.is_none_or(|cel| !same_masks(&cel.masks, &layer.masks));
        if (old.is_some() || has_content || force) && (source_changed || mask_changed) {
            let id = match cel_id {
                Some(id) => id,
                None => next(&mut animation.next_cel_id)?,
            };
            let source = if source_changed {
                source
            } else {
                old.unwrap().source.clone()
            };
            animation.cels.insert(
                id,
                Arc::new(Cel {
                    id,
                    layer_id: layer.id,
                    source,
                    masks: layer.masks.clone(),
                }),
            );
            animation.frames[frame_index].exposures.insert(layer.id, id);
        }
        layer.content = LayerContent::CelTrack { kind };
        layer.masks.clear();
    }
    animation.collect_orphans();
    result.animation = Some(Arc::new(animation));
    result.normalize_mask_target();
    if validate {
        result.validate()?;
    } else if result.pixel_bytes() > MAX_DOCUMENT_BYTES
        || result.animation.as_ref().unwrap().metadata_bytes() > MAX_ANIMATION_METADATA_BYTES
    {
        return Err("动画笔画超过内存限制".into());
    }
    Ok(result)
}

pub fn duplicate_cel(document: &mut Document, source: &Arc<Cel>) -> Result<Arc<Cel>, String> {
    let animation = Arc::make_mut(document.animation.as_mut().ok_or("工程没有动画")?);
    let mut cel = source.as_ref().clone();
    cel.id = next(&mut animation.next_cel_id)?;
    for mask in &mut cel.masks {
        mask.id = next(&mut document.next_mask_id)?;
    }
    Ok(Arc::new(cel))
}

pub fn prepare(document: &Document, command: crate::Command) -> Result<Document, String> {
    use crate::Command;
    if let Command::EnableAnimation { duration_ms, .. } = command {
        return enable(document, duration_ms);
    }
    if let Command::NewCel { layer_id, .. }
    | Command::ClearCel { layer_id, .. }
    | Command::LinkCel { layer_id, .. }
    | Command::UnlinkCel { layer_id, .. } = &command
    {
        crate::groups::check_editable(document, *layer_id, false)?;
    }
    let mut result = document.clone();
    let animation = Arc::make_mut(result.animation.as_mut().ok_or("请先开启动画")?);
    match command {
        Command::AddFrame {
            index,
            duration_ms,
            select,
            ..
        } => {
            duration(duration_ms)?;
            if index > animation.frames.len() {
                return Err("动画帧插入位置无效".into());
            }
            let id = next(&mut animation.next_frame_id)?;
            animation.frames.insert(
                index,
                Frame {
                    id,
                    duration_ms,
                    exposures: BTreeMap::new(),
                },
            );
            if select {
                animation.active_frame = id;
            }
        }
        Command::DuplicateFrame {
            frame_id,
            index,
            linked,
            select,
            ..
        } => {
            if index > animation.frames.len() {
                return Err("动画帧插入位置无效".into());
            }
            let mut frame = animation.frame(frame_id)?.clone();
            frame.id = next(&mut animation.next_frame_id)?;
            if !linked {
                for cel_id in frame.exposures.values_mut() {
                    let source = animation.cels.get(cel_id).ok_or("动画 Cel 不存在")?.clone();
                    let mut cel = source.as_ref().clone();
                    cel.id = next(&mut animation.next_cel_id)?;
                    for mask in &mut cel.masks {
                        mask.id = next(&mut result.next_mask_id)?;
                    }
                    *cel_id = cel.id;
                    animation.cels.insert(cel.id, Arc::new(cel));
                }
            }
            if select {
                animation.active_frame = frame.id;
            }
            animation.frames.insert(index, frame);
        }
        Command::DeleteFrame { frame_id, .. } => {
            if animation.frames.len() == 1 {
                return Err("不能删除最后一帧".into());
            }
            let index = animation
                .frames
                .iter()
                .position(|frame| frame.id == frame_id)
                .ok_or("动画帧不存在")?;
            for tag in &mut animation.tags {
                let from = animation
                    .frames
                    .iter()
                    .position(|frame| frame.id == tag.from_frame)
                    .unwrap();
                let to = animation
                    .frames
                    .iter()
                    .position(|frame| frame.id == tag.to_frame)
                    .unwrap();
                let surviving: Vec<_> = animation.frames[from..=to]
                    .iter()
                    .filter(|frame| frame.id != frame_id)
                    .map(|frame| frame.id)
                    .collect();
                if let (Some(&first), Some(&last)) = (surviving.first(), surviving.last()) {
                    tag.from_frame = first;
                    tag.to_frame = last;
                } else {
                    tag.from_frame = 0;
                }
            }
            animation.tags.retain(|tag| tag.from_frame != 0);
            animation.frames.remove(index);
            if animation.active_frame == frame_id {
                animation.active_frame = animation.frames[index.min(animation.frames.len() - 1)].id;
            }
            animation.collect_orphans();
        }
        Command::ReorderFrames { ids, .. } => {
            if ids.len() != animation.frames.len()
                || ids.iter().copied().collect::<BTreeSet<_>>().len() != ids.len()
            {
                return Err("动画帧排序无效".into());
            }
            animation.frames = ids
                .into_iter()
                .map(|id| animation.frame(id).cloned())
                .collect::<Result<_, _>>()?;
            for tag in &mut animation.tags {
                let a = animation
                    .frames
                    .iter()
                    .position(|frame| frame.id == tag.from_frame)
                    .unwrap();
                let b = animation
                    .frames
                    .iter()
                    .position(|frame| frame.id == tag.to_frame)
                    .unwrap();
                if a > b {
                    std::mem::swap(&mut tag.from_frame, &mut tag.to_frame);
                }
            }
        }
        Command::SetFrameDuration {
            frame_id,
            duration_ms,
            ..
        } => {
            duration(duration_ms)?;
            animation
                .frames
                .iter_mut()
                .find(|frame| frame.id == frame_id)
                .ok_or("动画帧不存在")?
                .duration_ms = duration_ms;
        }
        Command::NewCel {
            frame_id, layer_id, ..
        } => {
            let layer = result
                .layers
                .iter()
                .find(|layer| layer.id == layer_id)
                .ok_or("动画轨道不存在")?;
            let LayerContent::CelTrack { kind } = layer.content else {
                return Err("请选择动画绘图轨道".into());
            };
            crate::groups::check_editable(&result, layer_id, false)?;
            let animation = Arc::make_mut(result.animation.as_mut().unwrap());
            let frame_index = animation
                .frames
                .iter()
                .position(|frame| frame.id == frame_id)
                .ok_or("动画帧不存在")?;
            if animation.frames[frame_index]
                .exposures
                .contains_key(&layer_id)
            {
                return Err("此帧已有 Cel".into());
            }
            let id = next(&mut animation.next_cel_id)?;
            animation.cels.insert(
                id,
                Arc::new(Cel {
                    id,
                    layer_id,
                    source: blank(kind, result.palette.is_some()),
                    masks: Vec::new(),
                }),
            );
            animation.frames[frame_index].exposures.insert(layer_id, id);
        }
        Command::ClearCel {
            frame_id,
            layer_id,
            cel_id,
            ..
        } => {
            let frame = animation
                .frames
                .iter_mut()
                .find(|frame| frame.id == frame_id)
                .ok_or("动画帧不存在")?;
            if frame.exposures.get(&layer_id) != Some(&cel_id) {
                return Err("动画 Cel 已变化".into());
            }
            frame.exposures.remove(&layer_id);
            animation.collect_orphans();
        }
        Command::LinkCel {
            frame_id,
            layer_id,
            source_frame_id,
            ..
        } => {
            let cel = *animation
                .frame(source_frame_id)?
                .exposures
                .get(&layer_id)
                .ok_or("源帧没有 Cel")?;
            animation
                .frames
                .iter_mut()
                .find(|frame| frame.id == frame_id)
                .ok_or("动画帧不存在")?
                .exposures
                .insert(layer_id, cel);
            animation.collect_orphans();
        }
        Command::UnlinkCel {
            frame_id,
            layer_id,
            cel_id,
            ..
        } => {
            if animation.frame(frame_id)?.exposures.get(&layer_id) != Some(&cel_id) {
                return Err("动画 Cel 已变化".into());
            }
            let uses = animation
                .frames
                .iter()
                .filter(|frame| frame.exposures.get(&layer_id) == Some(&cel_id))
                .count();
            if uses > 1 {
                let mut cel = animation
                    .cels
                    .get(&cel_id)
                    .ok_or("动画 Cel 不存在")?
                    .as_ref()
                    .clone();
                cel.id = next(&mut animation.next_cel_id)?;
                for mask in &mut cel.masks {
                    mask.id = next(&mut result.next_mask_id)?;
                }
                animation
                    .frames
                    .iter_mut()
                    .find(|frame| frame.id == frame_id)
                    .unwrap()
                    .exposures
                    .insert(layer_id, cel.id);
                animation.cels.insert(cel.id, Arc::new(cel));
            }
        }
        Command::AddFrameTag { tag, .. } => {
            let id = next(&mut animation.next_tag_id)?;
            animation.tags.push(FrameTag {
                id,
                name: tag.name,
                color: tag.color,
                from_frame: tag.from_frame,
                to_frame: tag.to_frame,
                direction: tag.direction,
                repeat: tag.repeat,
            });
        }
        Command::SetFrameTag { id, tag, .. } => {
            *animation
                .tags
                .iter_mut()
                .find(|tag| tag.id == id)
                .ok_or("动画标签不存在")? = FrameTag {
                id,
                name: tag.name,
                color: tag.color,
                from_frame: tag.from_frame,
                to_frame: tag.to_frame,
                direction: tag.direction,
                repeat: tag.repeat,
            };
        }
        Command::DeleteFrameTag { id, .. } => {
            let index = animation
                .tags
                .iter()
                .position(|tag| tag.id == id)
                .ok_or("动画标签不存在")?;
            animation.tags.remove(index);
        }
        _ => return Err("动画命令无效".into()),
    }
    result.normalize_mask_target();
    result.validate()?;
    Ok(result)
}

pub fn global(
    document: &Document,
    command: &crate::Command,
    revision: u64,
    mask_editing: bool,
    has_selection: bool,
) -> Result<Option<Document>, String> {
    use crate::Command;
    let animation = document.animation.as_ref().ok_or("工程没有动画")?;
    if let Command::DuplicateLayer { id } = command {
        let plan = crate::groups::Hierarchy::new(document)?;
        let start = document
            .layers
            .iter()
            .position(|layer| layer.id == *id)
            .ok_or("图层不存在")?;
        let mapping: BTreeMap<_, _> = document.layers[start..plan.end[start]]
            .iter()
            .enumerate()
            .map(|(offset, layer)| (layer.id, document.next_id + offset as u32))
            .collect();
        let mut result = document.clone();
        crate::layers::duplicate(&mut result, *id)?;
        let target = Arc::make_mut(result.animation.as_mut().unwrap());
        let mut cel_mapping = BTreeMap::new();
        for cel in animation
            .cels
            .values()
            .filter(|cel| mapping.contains_key(&cel.layer_id))
        {
            let mut copy = cel.as_ref().clone();
            copy.id = next(&mut target.next_cel_id)?;
            copy.layer_id = mapping[&cel.layer_id];
            for mask in &mut copy.masks {
                mask.id = next(&mut result.next_mask_id)?;
            }
            cel_mapping.insert(cel.id, copy.id);
            target.cels.insert(copy.id, Arc::new(copy));
        }
        for frame in &mut target.frames {
            let added: Vec<_> = frame
                .exposures
                .iter()
                .filter_map(|(layer, cel)| {
                    mapping.get(layer).map(|mapped| (*mapped, cel_mapping[cel]))
                })
                .collect();
            frame.exposures.extend(added);
        }
        crate::groups::expand_active_ancestors(&mut result)?;
        result.normalize_mask_target();
        crate::masks::check_transaction(document, &result)?;
        return Ok(Some(result));
    }
    crate::animation_budget::reserve(document, command, mask_editing)?;
    let current = view(document, animation.active_frame)?;
    let mut next_document = None;
    let mut affected: Option<BTreeSet<u32>> = None;
    let mut group_action = None;
    match command {
        Command::ResizeCanvas {
            width,
            height,
            anchor,
            revision: expected,
        } => {
            if *expected != revision {
                return Err("动画画布已变化".into());
            }
            if *anchor >= 9 {
                return Err("画布定位无效".into());
            }
            if (*width, *height) == (document.width, document.height) {
                return Ok(Some(document.clone()));
            }
            next_document = Some(crate::canvas::resize(&current, *width, *height, *anchor)?);
        }
        Command::ResizeImage {
            width,
            height,
            filter,
            revision: expected,
        } => {
            if *expected != revision {
                return Err("动画画布已变化".into());
            }
            if (*width, *height) == (document.width, document.height) {
                return Ok(Some(document.clone()));
            }
            next_document = Some(crate::resample::resize(&current, *width, *height, *filter)?);
        }
        Command::ConvertColorMode {
            mode,
            palette,
            revision: expected,
        } => {
            if *expected != revision {
                return Err("动画调色板已变化".into());
            }
            if *mode == ColorMode::Rgba && palette.is_some() {
                return Err("RGBA 转换不接受索引调色板".into());
            }
            let palette = match mode {
                ColorMode::Rgba => None,
                ColorMode::Indexed => Some(
                    palette
                        .clone()
                        .or_else(|| document.palette.clone())
                        .ok_or("转换索引色需要调色板")?,
                ),
            };
            if palette == document.palette {
                return Ok(Some(document.clone()));
            }
            conversion_budget(document, palette.is_some())?;
            next_document = Some(crate::indexed::convert(&current, palette)?);
        }
        Command::RemovePaletteColor {
            index,
            replacement,
            revision: expected,
        } => {
            if *expected != revision {
                return Err("动画调色板已变化".into());
            }
            next_document = Some(crate::indexed::remove_color(
                &current,
                *index,
                *replacement,
            )?);
        }
        Command::RasterizeVector {
            id,
            revision: expected,
        } => {
            if *expected != revision {
                return Err("动画矢量轨道已变化".into());
            }
            if mask_editing || has_selection {
                return Err("请先退出蒙版编辑并取消像素选区".into());
            }
            next_document = Some(crate::vector::rasterize(&current, *id)?);
            affected = Some(BTreeSet::from([*id]));
        }
        Command::TranslateLayer { id, dx, dy, .. }
            if !mask_editing
                && current
                    .layers
                    .iter()
                    .any(|layer| layer.id == *id && layer.is_group()) =>
        {
            group_action = Some((*id, crate::LayerAction::Translate { dx: *dx, dy: *dy }));
        }
        Command::TransformLayer {
            id,
            revision: expected,
            transform,
            ..
        } if !mask_editing
            && current
                .layers
                .iter()
                .any(|layer| layer.id == *id && layer.is_group()) =>
        {
            if *expected != revision {
                return Err("动画图层组已变化".into());
            }
            group_action = Some((
                *id,
                crate::LayerAction::Transform {
                    transform: *transform,
                },
            ));
        }
        _ => {}
    }
    let mut group_bounds = None;
    if let Some((id, action)) = &group_action {
        if has_selection {
            return Err("请取消选区再变换动画图层组".into());
        }
        let plan = crate::groups::Hierarchy::new(&current)?;
        let index = current
            .layers
            .iter()
            .position(|layer| layer.id == *id)
            .ok_or("图层组不存在")?;
        affected = Some(
            current.layers[index..plan.end[index]]
                .iter()
                .map(|layer| layer.id)
                .collect(),
        );
        if matches!(action, crate::LayerAction::Transform { .. }) {
            group_bounds = Some(crate::groups::bounds(&current, *id)?);
        }
        next_document = Some(crate::groups::geometry(&current, *id, action.clone())?);
    }
    let Some(prepared) = next_document else {
        return Ok(None);
    };
    let mut result = prepared.clone();
    result.animation = document.animation.clone();
    for layer in &mut result.layers {
        if let Some(old) = document.layers.iter().find(|old| old.id == layer.id) {
            if matches!(old.content, LayerContent::CelTrack { .. }) {
                let kind = if layer.is_vector() {
                    CelKind::Vector
                } else {
                    CelKind::Raster
                };
                layer.content = LayerContent::CelTrack { kind };
                layer.masks.clear();
            }
        }
    }
    let mut source_cache: BTreeMap<(usize, u8), CelSource> = BTreeMap::new();
    for (&layer_id, &cel_id) in &animation.frame(animation.active_frame)?.exposures {
        if affected
            .as_ref()
            .is_some_and(|ids| !ids.contains(&layer_id))
        {
            continue;
        }
        let cel = &animation.cels[&cel_id];
        let layer = prepared
            .layers
            .iter()
            .find(|layer| layer.id == layer_id)
            .ok_or("动画轨道不存在")?;
        let source = match &layer.content {
            LayerContent::Raster(source) => CelSource::Raster(Arc::new(source.clone())),
            LayerContent::Vector(source) => CelSource::Vector(source.clone()),
            _ => continue,
        };
        source_cache.insert(source_key(&cel.source), source);
    }
    let cells: Vec<_> = animation.cels.values().cloned().collect();
    let mut budget = ResourceBudget::new(&result);
    let initial_metadata = animation.metadata_bytes();
    let target = Arc::make_mut(result.animation.as_mut().unwrap());
    for cel in cells {
        if affected
            .as_ref()
            .is_some_and(|ids| !ids.contains(&cel.layer_id))
        {
            continue;
        }
        let mut one = Document::new(document.width, document.height)?;
        one.palette = document.palette.clone();
        one.next_mask_id = document.next_mask_id;
        one.layers[0].masks = cel.masks.clone();
        one.layers[0].content = match &cel.source {
            CelSource::Raster(source) => LayerContent::Raster(source.as_ref().clone()),
            CelSource::Vector(source) => LayerContent::Vector(source.clone()),
        };
        let source_key = source_key(&cel.source);
        let transformed = if let Some(source) = source_cache.get(&source_key) {
            one.layers[0].content = match source {
                CelSource::Raster(source) => LayerContent::Raster(source.as_ref().clone()),
                CelSource::Vector(source) => LayerContent::Vector(source.clone()),
            };
            transform_masks(
                &mut one.layers[0].masks,
                document,
                command,
                group_action.as_ref().map(|(_, action)| action),
                group_bounds,
            )?;
            one
        } else {
            match command {
                Command::ResizeCanvas {
                    width,
                    height,
                    anchor,
                    ..
                } => crate::canvas::resize(&one, *width, *height, *anchor)?,
                Command::ResizeImage {
                    width,
                    height,
                    filter,
                    ..
                } => crate::resample::resize(&one, *width, *height, *filter)?,
                Command::ConvertColorMode { .. } => {
                    crate::indexed::convert(&one, result.palette.clone())?
                }
                Command::RemovePaletteColor {
                    index, replacement, ..
                } => crate::indexed::remove_color(&one, *index, *replacement)?,
                Command::RasterizeVector { .. } => crate::vector::rasterize(&one, 1)?,
                _ => {
                    let action = &group_action.as_ref().ok_or("动画全局操作无效")?.1;
                    let mut transformed = one.clone();
                    match &cel.source {
                        CelSource::Vector(source) => {
                            let matrix = match action {
                                crate::LayerAction::Translate { dx, dy } => {
                                    tiny_skia::Transform::from_translate(*dx as f32, *dy as f32)
                                }
                                crate::LayerAction::Transform { transform } => {
                                    crate::vector::transform_matrix(
                                        group_bounds.unwrap(),
                                        *transform,
                                    )?
                                }
                                _ => unreachable!(),
                            };
                            transformed.layers[0].content =
                                LayerContent::Vector(crate::vector::transformed(source, matrix)?);
                        }
                        CelSource::Raster(_) => {
                            let tiles = match action {
                                crate::LayerAction::Translate { dx, dy } => {
                                    crate::translation::translate(&one, 1, *dx, *dy, None)?
                                }
                                crate::LayerAction::Transform { transform } => {
                                    crate::transform::prepare_with_bounds(
                                        &one,
                                        1,
                                        *transform,
                                        group_bounds,
                                    )?
                                }
                                _ => unreachable!(),
                            };
                            transformed.layers[0].raster_mut()?.set_tiles(tiles);
                        }
                    }
                    for mask in &mut transformed.layers[0].masks {
                        if mask.plane.linked {
                            mask.plane = match action {
                                crate::LayerAction::Translate { dx, dy } => {
                                    crate::masks::offset(&mask.plane, *dx, *dy)?
                                }
                                crate::LayerAction::Transform { transform } => {
                                    let bounds = group_bounds.unwrap();
                                    crate::masks::affine(
                                        &mask.plane,
                                        MaskBounds {
                                            left: bounds.left as i32,
                                            top: bounds.top as i32,
                                            right: bounds.right as i32,
                                            bottom: bounds.bottom as i32,
                                        },
                                        *transform,
                                    )?
                                }
                                _ => unreachable!(),
                            };
                        }
                    }
                    transformed
                }
            }
        };
        let source = source_cache
            .entry(source_key)
            .or_insert_with(|| match &transformed.layers[0].content {
                LayerContent::Raster(source) => CelSource::Raster(Arc::new(source.clone())),
                LayerContent::Vector(source) => CelSource::Vector(source.clone()),
                _ => unreachable!(),
            })
            .clone();
        let edited = Arc::new(Cel {
            id: cel.id,
            layer_id: cel.layer_id,
            source,
            masks: transformed.layers[0].masks.clone(),
        });
        for resource in cel
            .buffers()
            .map(|tile| (Arc::as_ptr(tile) as usize, tile.len()))
            .chain(cel.vector_resources())
        {
            budget.remove(resource);
        }
        for resource in edited
            .buffers()
            .map(|tile| (Arc::as_ptr(tile) as usize, tile.len()))
            .chain(edited.vector_resources())
        {
            budget.add(resource);
        }
        if budget.bytes > MAX_DOCUMENT_BYTES {
            return Err("所有动画 Cel 的结果超过内存限制".into());
        }
        target.cels.insert(cel.id, edited);
    }
    if budget.bytes.saturating_sub(initial_metadata) + target.metadata_bytes() > MAX_DOCUMENT_BYTES
    {
        return Err("所有动画 Cel 的结果超过内存限制".into());
    }
    result.normalize_mask_target();
    crate::masks::check_transaction(document, &result)?;
    Ok(Some(result))
}

fn source_key(source: &CelSource) -> (usize, u8) {
    match source {
        CelSource::Raster(source) => (Arc::as_ptr(source) as usize, 0),
        CelSource::Vector(source) => (Arc::as_ptr(source) as usize, 1),
    }
}

fn transform_masks(
    masks: &mut [MaskEntry],
    doc: &Document,
    command: &crate::Command,
    group: Option<&crate::LayerAction>,
    area: Option<Rect>,
) -> Result<(), String> {
    for mask in masks {
        let transform = match command {
            crate::Command::ResizeCanvas {
                width,
                height,
                anchor,
                ..
            } if mask.plane.linked => Some(crate::LayerAction::Translate {
                dx: (*width as i32 - doc.width as i32) * i32::from(anchor % 3) / 2,
                dy: (*height as i32 - doc.height as i32) * i32::from(anchor / 3) / 2,
            }),
            crate::Command::ResizeImage {
                width,
                height,
                filter,
                ..
            } => Some(crate::LayerAction::Transform {
                transform: crate::LayerTransform {
                    width: *width,
                    height: *height,
                    dx: (f64::from(*width) - f64::from(doc.width)) / 2.0,
                    dy: (f64::from(*height) - f64::from(doc.height)) / 2.0,
                    angle: 0.0,
                    flip_x: false,
                    flip_y: false,
                    filter: *filter,
                },
            }),
            _ if mask.plane.linked => group.cloned(),
            _ => None,
        };
        if let Some(action) = transform {
            mask.plane = match action {
                crate::LayerAction::Translate { dx, dy } => {
                    crate::masks::offset(&mask.plane, dx, dy)?
                }
                crate::LayerAction::Transform { transform } => {
                    let bounds = area.unwrap_or(doc.bounds());
                    crate::masks::affine(
                        &mask.plane,
                        MaskBounds {
                            left: bounds.left as i32,
                            top: bounds.top as i32,
                            right: bounds.right as i32,
                            bottom: bounds.bottom as i32,
                        },
                        transform,
                    )?
                }
                _ => return Err("动画蒙版变换无效".into()),
            };
        }
    }
    Ok(())
}

struct ResourceBudget {
    refs: std::collections::HashMap<usize, (usize, usize)>,
    bytes: usize,
}
impl ResourceBudget {
    fn new(doc: &Document) -> Self {
        let mut budget = Self {
            refs: Default::default(),
            bytes: 0,
        };
        for resource in doc.resources() {
            budget.add(resource);
        }
        budget
    }
    fn add(&mut self, (id, bytes): (usize, usize)) {
        let entry = self.refs.entry(id).or_insert((0, bytes));
        if entry.0 == 0 {
            self.bytes += bytes;
        }
        entry.0 += 1;
    }
    fn remove(&mut self, (id, _): (usize, usize)) {
        if let Some(entry) = self.refs.get_mut(&id) {
            entry.0 -= 1;
            if entry.0 == 0 {
                self.bytes -= entry.1;
            }
        }
    }
}

fn conversion_budget(document: &Document, indexed: bool) -> Result<(), String> {
    let animation = document.animation.as_ref().unwrap();
    let bytes = if indexed {
        INDEX_TILE_BYTES
    } else {
        TILE_BYTES
    };
    let mut sources = HashSet::new();
    let mut masks = HashSet::new();
    let mut future = animation.metadata_bytes()
        + document.assistants.bytes()
        + document
            .aseprite_metadata
            .as_ref()
            .map_or(0, |metadata| metadata.bytes());
    for cel in animation.cels.values() {
        if let CelSource::Raster(source) = &cel.source {
            if sources.insert(Arc::as_ptr(source) as usize) {
                future = future
                    .checked_add(
                        source
                            .tiles()
                            .len()
                            .checked_mul(bytes)
                            .ok_or("转换像素大小超出限制")?,
                    )
                    .ok_or("转换像素大小超出限制")?;
            }
        } else if indexed {
            return Err("请先显式栅格化所有矢量轨道".into());
        }
        for mask in &cel.masks {
            for tile in mask.plane.tiles.values() {
                if masks.insert(Arc::as_ptr(tile) as usize) {
                    future = future
                        .checked_add(tile.len())
                        .ok_or("转换像素大小超出限制")?;
                }
            }
        }
    }
    for tile in document.layers.iter().flat_map(Layer::mask_buffers) {
        if masks.insert(Arc::as_ptr(tile) as usize) {
            future = future
                .checked_add(tile.len())
                .ok_or("转换像素大小超出限制")?;
        }
    }
    if future > MAX_DOCUMENT_BYTES {
        return Err("转换后的所有动画 Cel 超出像素内存限制".into());
    }
    let retained: std::collections::HashMap<_, _> = animation
        .cels
        .values()
        .flat_map(|cel| {
            match &cel.source {
                CelSource::Raster(source) => Some(source),
                _ => None,
            }
            .into_iter()
            .flat_map(|source| source.tiles().values())
        })
        .filter(|tile| !masks.contains(&(Arc::as_ptr(tile) as usize)))
        .map(|tile| (Arc::as_ptr(tile) as usize, tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("转换所有动画 Cel 会超出撤销内存限制".into());
    }
    Ok(())
}

#[cfg(test)]
mod aseprite_grid_tests {
    use super::*;
    use crate::{
        aseprite::{GridMetadata, ProjectMetadata},
        resample::ResampleFilter,
    };

    fn document(indexed: bool) -> Document {
        let mut document = Document::new(4, 6).unwrap();
        if indexed {
            document.palette = Some(IndexedPalette {
                colors: vec![[120, 80, 20, 255], [0; 4]],
                transparent: 1,
                order: vec![1, 0],
            });
            document.layers[0].content =
                LayerContent::Raster(RasterPlane::Indexed(BTreeMap::new()));
        }
        document.aseprite_metadata = Some(Arc::new(ProjectMetadata {
            companion_palette: None,
            indexed_names: if indexed { vec![None; 2] } else { Vec::new() },
            grid: GridMetadata {
                x: -3,
                y: 5,
                width: 3,
                height: 4,
            },
            srgb: true,
        }));
        document.validate().unwrap();
        document
    }

    fn grid(document: &Document) -> (i16, i16, u16, u16) {
        let grid = &document.aseprite_metadata.as_ref().unwrap().grid;
        (grid.x, grid.y, grid.width, grid.height)
    }

    fn animated(indexed: bool) -> Document {
        let mut document = enable(&document(indexed), 100).unwrap();
        let animation = Arc::make_mut(document.animation.as_mut().unwrap());
        let mut independent = animation.cels[&1].as_ref().clone();
        independent.id = 2;
        let CelSource::Raster(source) = &independent.source else {
            unreachable!()
        };
        independent.source = CelSource::Raster(Arc::new(source.as_ref().clone()));
        animation.cels.insert(2, Arc::new(independent));
        animation.frames.extend([
            Frame {
                id: 2,
                duration_ms: 120,
                exposures: BTreeMap::from([(1, 1)]),
            },
            Frame {
                id: 3,
                duration_ms: 250,
                exposures: BTreeMap::from([(1, 2)]),
            },
        ]);
        animation.next_frame_id = 4;
        animation.next_cel_id = 3;
        document.validate().unwrap();
        document
    }

    #[test]
    fn canvas_anchor_offsets_follow_pixels_for_rgba_and_indexed_metadata() {
        for indexed in [false, true] {
            let document = document(indexed);
            for (width, height) in [(7, 9), (1, 3)] {
                for anchor in 0..9 {
                    let resized = crate::canvas::resize(&document, width, height, anchor).unwrap();
                    assert_eq!(
                        grid(&resized),
                        (
                            -3 + i16::from(anchor % 3) * (width as i16 - 4) / 2,
                            5 + i16::from(anchor / 3) * (height as i16 - 6) / 2,
                            3,
                            4
                        )
                    );
                    resized.validate().unwrap();
                    assert_eq!(grid(&document), (-3, 5, 3, 4));
                }
            }
        }
    }

    #[test]
    fn image_grid_rounds_signed_origins_preserves_no_grid_and_keeps_nonzero_cells() {
        for indexed in [false, true] {
            let mut document = document(indexed);
            let resized =
                crate::resample::resize(&document, 6, 3, ResampleFilter::Nearest).unwrap();
            assert_eq!(grid(&resized), (-5, 3, 5, 2));
            resized.validate().unwrap();
            assert_eq!(grid(&document), (-3, 5, 3, 4));
            let tiny = crate::resample::resize(&document, 1, 1, ResampleFilter::Nearest).unwrap();
            assert_eq!(grid(&tiny), (-1, 1, 1, 1));
            let metadata = Arc::make_mut(document.aseprite_metadata.as_mut().unwrap());
            metadata.grid.width = 0;
            metadata.grid.height = 0;
            let no_grid =
                crate::resample::resize(&document, 6, 3, ResampleFilter::Nearest).unwrap();
            assert_eq!(grid(&no_grid), (-5, 3, 0, 0));
        }
    }

    #[test]
    fn grid_coordinate_and_cell_size_overflow_rejects_geometry_atomically() {
        let mut document = document(false);
        Arc::make_mut(document.aseprite_metadata.as_mut().unwrap())
            .grid
            .x = i16::MAX;
        let original = document.clone();
        assert!(crate::canvas::resize(&document, 5, 6, 8).is_err());
        assert!(crate::resample::resize(&document, 8, 6, ResampleFilter::Nearest).is_err());
        assert!(document == original);
        let metadata = Arc::make_mut(document.aseprite_metadata.as_mut().unwrap());
        metadata.grid.x = i16::MIN;
        let original = document.clone();
        assert!(crate::canvas::resize(&document, 1, 6, 8).is_err());
        assert!(document == original);
        let metadata = Arc::make_mut(document.aseprite_metadata.as_mut().unwrap());
        metadata.grid.x = 0;
        metadata.grid.width = 40_000;
        let original = document.clone();
        assert!(crate::resample::resize(&document, 8, 6, ResampleFilter::Nearest).is_err());
        assert!(document == original);
    }

    #[test]
    fn animated_geometry_updates_project_grid_once_for_linked_and_independent_cels() {
        for indexed in [false, true] {
            let document = animated(indexed);
            let canvas = global(
                &document,
                &crate::Command::ResizeCanvas {
                    width: 8,
                    height: 10,
                    anchor: 8,
                    revision: 1,
                },
                1,
                false,
                false,
            )
            .unwrap()
            .unwrap();
            assert_eq!(grid(&canvas), (1, 9, 3, 4));
            canvas.validate().unwrap();
            let resized = global(
                &canvas,
                &crate::Command::ResizeImage {
                    width: 16,
                    height: 5,
                    filter: ResampleFilter::Nearest,
                    revision: 2,
                },
                2,
                false,
                false,
            )
            .unwrap()
            .unwrap();
            assert_eq!(grid(&resized), (2, 5, 6, 2));
            resized.validate().unwrap();
            for frame in &resized.animation.as_ref().unwrap().frames {
                assert_eq!(grid(&view(&resized, frame.id).unwrap()), (2, 5, 6, 2));
            }
            assert_eq!(resized.animation.as_ref().unwrap().cels.len(), 2);
            assert_eq!(grid(&document), (-3, 5, 3, 4));
        }
    }

    #[test]
    fn animated_validation_and_overflow_reject_invalid_project_metadata() {
        let mut document = animated(false);
        Arc::make_mut(document.aseprite_metadata.as_mut().unwrap())
            .indexed_names
            .push(Some("Foreign slot".into()));
        assert!(validate(&document).is_err());
        let metadata = Arc::make_mut(document.aseprite_metadata.as_mut().unwrap());
        metadata.indexed_names.clear();
        metadata.grid.x = i16::MAX;
        document.validate().unwrap();
        let original = document.clone();
        assert!(global(
            &document,
            &crate::Command::ResizeCanvas {
                width: 5,
                height: 6,
                anchor: 8,
                revision: 1,
            },
            1,
            false,
            false
        )
        .is_err());
        assert!(document == original);
    }

    #[test]
    fn grid_geometry_commands_save_reload_and_restore_with_undo_redo() {
        for indexed in [false, true] {
            for animation in [false, true] {
                let document = if animation {
                    animated(indexed)
                } else {
                    document(indexed)
                };
                let mut engine = crate::Engine::new(1, 1).unwrap();
                engine
                    .load(&crate::storage::save(&document).unwrap())
                    .unwrap();
                let initial = engine.save().unwrap();
                engine
                    .command(crate::Command::ResizeCanvas {
                        width: 8,
                        height: 10,
                        anchor: 8,
                        revision: engine.state()["revision"].as_u64().unwrap(),
                    })
                    .unwrap();
                assert_eq!(grid(&engine.document), (1, 9, 3, 4));
                let canvas = engine.save().unwrap();
                engine
                    .command(crate::Command::ResizeImage {
                        width: 16,
                        height: 5,
                        filter: ResampleFilter::Nearest,
                        revision: engine.state()["revision"].as_u64().unwrap(),
                    })
                    .unwrap();
                assert_eq!(grid(&engine.document), (2, 5, 6, 2));
                let image = engine.save().unwrap();
                engine.command(crate::Command::Undo).unwrap();
                assert!(engine.save().unwrap() == canvas);
                engine.command(crate::Command::Undo).unwrap();
                assert!(engine.save().unwrap() == initial);
                engine.command(crate::Command::Redo).unwrap();
                assert!(engine.save().unwrap() == canvas);
                engine.command(crate::Command::Redo).unwrap();
                assert!(engine.save().unwrap() == image);
                let mut reopened = crate::Engine::new(1, 1).unwrap();
                reopened.load(&image).unwrap();
                assert_eq!(grid(&reopened.document), (2, 5, 6, 2));
                assert!(reopened.save().unwrap() == image);
            }
        }
    }
}
