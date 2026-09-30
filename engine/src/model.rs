use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, sync::Arc};

pub const TILE_SIZE: u32 = 128;
pub const TILE_BYTES: usize = (TILE_SIZE * TILE_SIZE * 4) as usize;
pub const MASK_TILE_BYTES: usize = (TILE_SIZE * TILE_SIZE) as usize;
pub const MAX_LAYER_MASKS: usize = 16;
pub const MAX_MASK_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_DRAWING_ASSISTANTS: usize = 16;
pub const MAX_ASSISTANT_GEOMETRY_BYTES: usize = 16 * 1024;
pub const MAX_ASSISTANT_COORDINATE: f64 = MAX_DIMENSION as f64 * 128.0;
pub const MAX_ASSISTANT_STROKE_COORDINATE: f64 = MAX_DIMENSION as f64 * 2.0;
pub const MIN_ASSISTANT_DIRECTION_DISTANCE: f64 = 1e-6;
pub const MAX_VECTOR_OBJECTS: usize = 1024;
pub const MAX_GENERATED_LINES: usize = 256;
pub const MAX_ANIMATION_FRAMES: usize = 256;
pub const MAX_ANIMATION_CELS: usize = 8192;
pub const MAX_ANIMATION_TAGS: usize = 64;
pub const MAX_ASEPRITE_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_ASEPRITE_DECODED_BYTES: u64 = 512 * 1024 * 1024;
pub const MAX_ASEPRITE_SCRATCH_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_ASEPRITE_METADATA_BYTES: usize = 128 * 1024;
pub const MAX_ASEPRITE_NAME_BYTES: usize = 256;
pub const MAX_ASEPRITE_FRAME_CHUNKS: usize = 256;
pub const ASEPRITE_STILL_DURATION_MS: u32 = 100;
pub const MAX_ANIMATION_METADATA_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_FRAME_THUMBNAILS: usize = 32;
pub const MAX_FRAME_DURATION_MS: u32 = 60_000;
pub const MAX_ANIMATION_EXPORT_SEQUENCE: usize = MAX_ANIMATION_FRAMES * 2 - 2;
pub const MAX_ANIMATION_EXPORT_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_ANIMATION_EXPORT_SCRATCH_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_ANIMATION_EXPORT_PIXEL_VISITS: u64 = 268_435_456;
pub const MAX_ATLAS_METADATA_BYTES: usize = 256 * 1024;
pub const MAX_ATLAS_PADDING: u32 = 32;
pub const MAX_GIF_SAMPLES: usize = 262_144;
pub const MAX_VECTOR_SEGMENTS: usize = 4096;
pub const MAX_DOCUMENT_VECTOR_SEGMENTS: usize = 65_536;
pub const MAX_VECTOR_GEOMETRY_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_VECTOR_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_VECTOR_COMMAND_BYTES: usize = 1024 * 1024;
pub const VECTOR_FLATTEN_TOLERANCE: f64 = 1.0 / 64.0;
pub const VECTOR_MAX_RENDER_EDGE: f64 = 16.0;
pub const MAX_VECTOR_RENDER_SEGMENTS: usize = MAX_VECTOR_CACHE_BYTES / 32;
pub const INDEX_TILE_BYTES: usize = MASK_TILE_BYTES;
pub const MAX_INDEXED_COLORS: usize = 256;
pub const MAX_INDEXED_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_INDEXED_EXPORT_CACHE_ENTRIES: usize = 4096;
pub const MAX_PALETTE_COMMAND_BYTES: usize = 16 * 1024;
pub const MAX_LAYER_PREVIEW_BYTES: usize = MAX_DOCUMENT_BYTES + 1024 * 1024;
pub const MAX_DIMENSION: u32 = 8192;
pub const MAX_GRADIENT_COORDINATE: f64 = MAX_DIMENSION as f64 * 2.0;
pub const MAX_PIXELS: u64 = 16_777_216;
pub const MAX_LAYERS: usize = 32;
pub const MAX_LAYER_NODES: usize = 64;
pub const MAX_GROUP_DEPTH: usize = 16;
pub const MAX_PSD_RECORDS: usize = MAX_LAYER_NODES * 2;
pub const MAX_LAYER_NAME_BYTES: usize = 256;
pub const MAX_HISTORY_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_DOCUMENT_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_STROKE_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SMUDGE_CACHE_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_RESAMPLE_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SELECTION_POINTS: usize = 4096;
pub const MAX_SELECTION_FLOOD_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SELECTION_OUTLINE_SEGMENTS: usize = 32_768;
pub const MAX_SELECTION_OUTLINE_LENGTH: u64 = 65_536;
pub const SELECTION_PREVIEW_TILE_SIZE: u32 = 512;
pub const MAX_PALETTE_COLORS: usize = 24;
pub const MAX_REFERENCE_EDGE: u32 = 2048;
pub const MAX_CURVE_POINTS: usize = 16;
pub const MAX_GRADIENT_MAP_STOPS: usize = 16;
pub const MAX_COMMAND_BYTES: usize = 4096;
pub const MAX_SELECTION_COMMAND_BYTES: usize = MAX_SELECTION_POINTS * 64 + 1024;
pub const MAX_CLIPBOARD_BYTES: usize = MAX_PIXELS as usize * 4 + 1024 * 1024;
pub const MAX_TRANSFORM_OFFSET: f64 = MAX_DIMENSION as f64 * 2.0;
pub const MAX_HISTORY_ENTRIES: usize = 60;
pub const MAX_ORA_ENTRIES: usize = 256;
pub const MAX_ORA_METADATA_BYTES: usize = 256 * 1024;
pub const MAX_ORA_XML_NODES: u32 = 1024;
pub const MAX_PSD_DECODED_BYTES: u64 = MAX_DOCUMENT_BYTES as u64 * 4;
pub const PREVIEW_EDGE: u32 = 96;
pub const EXPORT_THUMBNAIL_EDGE: u32 = 256;
pub const DEFAULT_EXPORT_QUALITY: u8 = 90;
pub const BRUSH_SPACING_RATIO: f32 = 0.08;
pub const BRUSH_TEXTURE_EDGE: usize = 128;
pub const BRUSH_PREVIEW_WIDTH: u32 = 320;
pub const BRUSH_PREVIEW_HEIGHT: u32 = 96;
pub const MAX_STABILIZER_DISTANCE: f32 = 40.0;
pub type TileKey = (u32, u32);
pub type Tile = Arc<Vec<u8>>;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Rect {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

impl Rect {
    pub fn contains(self, x: u32, y: u32) -> bool {
        x >= self.left && x < self.right && y >= self.top && y < self.bottom
    }

    pub fn intersect(self, other: Self) -> Option<Self> {
        let result = Self {
            left: self.left.max(other.left),
            top: self.top.max(other.top),
            right: self.right.min(other.right),
            bottom: self.bottom.min(other.bottom),
        };
        (result.left < result.right && result.top < result.bottom).then_some(result)
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
pub struct Layer {
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub opacity: f32,
    pub content: LayerContent,
    pub parent_id: Option<u32>,
    pub blend: BlendMode,
    pub alpha_locked: bool,
    pub locked: bool,
    pub masks: Vec<MaskEntry>,
    pub clipping: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum LayerContent {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: crate::adjustment_layers::AdjustmentEffect,
    },
    Vector(Arc<crate::vector::VectorLayer>),
    CelTrack {
        kind: crate::animation::CelKind,
    },
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GroupIsolation {
    #[default]
    Isolated,
    PassThrough,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum RasterPlane {
    Rgba(BTreeMap<TileKey, Tile>),
    Indexed(BTreeMap<TileKey, Tile>),
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ColorMode {
    Rgba,
    Indexed,
}

impl RasterPlane {
    pub fn tiles(&self) -> &BTreeMap<TileKey, Tile> {
        match self {
            Self::Rgba(tiles) | Self::Indexed(tiles) => tiles,
        }
    }

    pub fn tiles_mut(&mut self) -> &mut BTreeMap<TileKey, Tile> {
        match self {
            Self::Rgba(tiles) | Self::Indexed(tiles) => tiles,
        }
    }

    pub fn set_tiles(&mut self, tiles: BTreeMap<TileKey, Tile>) {
        *self.tiles_mut() = tiles;
    }

    pub fn is_indexed(&self) -> bool {
        matches!(self, Self::Indexed(_))
    }

    pub fn tile_bytes(&self) -> usize {
        if self.is_indexed() {
            INDEX_TILE_BYTES
        } else {
            TILE_BYTES
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IndexedPalette {
    pub colors: Vec<[u8; 4]>,
    pub transparent: u8,
    pub order: Vec<u8>,
}

impl IndexedPalette {
    pub fn validate(&self) -> Result<(), String> {
        if !(2..=MAX_INDEXED_COLORS).contains(&self.colors.len())
            || usize::from(self.transparent) >= self.colors.len()
            || self.colors[usize::from(self.transparent)][3] != 0
            || self.order.len() != self.colors.len()
        {
            return Err("索引调色板数据无效".into());
        }
        let mut seen = [false; MAX_INDEXED_COLORS];
        for &index in &self.order {
            if usize::from(index) >= self.colors.len() || seen[usize::from(index)] {
                return Err("索引调色板顺序无效".into());
            }
            seen[usize::from(index)] = true;
        }
        Ok(())
    }

    pub fn premultiplied(&self, index: u8) -> [u8; 4] {
        let mut color = self.colors[usize::from(index)];
        let alpha = u32::from(color[3]);
        for value in &mut color[..3] {
            *value = ((u32::from(*value) * alpha + 127) / 255) as u8;
        }
        color
    }

    pub fn nearest(&self, pixel: [u8; 4]) -> u8 {
        if pixel[3] == 0 {
            self.transparent
        } else {
            crate::indexed::nearest_color(&self.colors, pixel)
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct MaskBounds {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl MaskBounds {
    pub fn width(self) -> u32 {
        (i64::from(self.right) - i64::from(self.left)).max(0) as u32
    }

    pub fn height(self) -> u32 {
        (i64::from(self.bottom) - i64::from(self.top)).max(0) as u32
    }

    pub fn validate(self) -> Result<(), String> {
        if [self.left, self.top, self.right, self.bottom]
            .iter()
            .any(|coordinate| coordinate.unsigned_abs() > MAX_DIMENSION * 2)
            || self.right < self.left
            || self.bottom < self.top
            || self.width() > MAX_DIMENSION
            || self.height() > MAX_DIMENSION
            || u64::from(self.width()) * u64::from(self.height()) > MAX_PIXELS
        {
            return Err("蒙版尺寸超出限制".into());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct MaskEntry {
    pub id: u32,
    pub name: String,
    pub plane: LayerMask,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LayerMask {
    pub bounds: MaskBounds,
    pub default: u8,
    pub enabled: bool,
    pub linked: bool,
    pub tiles: BTreeMap<TileKey, Tile>,
}

impl LayerMask {
    pub fn new(bounds: MaskBounds, default: u8) -> Self {
        Self {
            bounds,
            default,
            enabled: true,
            linked: true,
            tiles: BTreeMap::new(),
        }
    }

    pub fn sample(&self, x: i32, y: i32) -> u8 {
        if x < self.bounds.left
            || x >= self.bounds.right
            || y < self.bounds.top
            || y >= self.bounds.bottom
        {
            return self.default;
        }
        let x = (x - self.bounds.left) as u32;
        let y = (y - self.bounds.top) as u32;
        self.tiles
            .get(&(x / TILE_SIZE, y / TILE_SIZE))
            .map_or(self.default, |tile| {
                tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize]
            })
    }

    pub fn validate(&self) -> Result<(), String> {
        self.bounds.validate()?;
        if ((self.bounds.width() == 0 || self.bounds.height() == 0) && !self.tiles.is_empty())
            || !matches!(self.default, 0 | 255)
            || self.tiles.iter().any(|(&(x, y), tile)| {
                x >= self.bounds.width().div_ceil(TILE_SIZE)
                    || y >= self.bounds.height().div_ceil(TILE_SIZE)
                    || tile.len() != MASK_TILE_BYTES
            })
        {
            return Err("蒙版数据无效".into());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
// 新模式必须追加，bincode 使用枚举序号保存工程。
pub enum BlendMode {
    #[default]
    Normal,
    Multiply,
    Screen,
    Overlay,
    SoftLight,
    Darken,
    Lighten,
    Difference,
}

impl Layer {
    pub fn new(id: u32, name: String) -> Self {
        Self {
            id,
            name,
            visible: true,
            opacity: 1.0,
            content: LayerContent::Raster(RasterPlane::Rgba(BTreeMap::new())),
            parent_id: None,
            blend: BlendMode::Normal,
            alpha_locked: false,
            locked: false,
            masks: Vec::new(),
            clipping: false,
        }
    }

    pub fn group(id: u32, name: String, isolation: GroupIsolation) -> Self {
        let mut layer = Self::new(id, name);
        layer.content = LayerContent::Group {
            isolation,
            closed: false,
        };
        layer
    }

    pub fn is_group(&self) -> bool {
        matches!(self.content, LayerContent::Group { .. })
    }

    pub fn first_mask(&self) -> Option<&LayerMask> {
        self.masks.first().map(|mask| &mask.plane)
    }

    pub fn first_mask_mut(&mut self) -> Option<&mut LayerMask> {
        self.masks.first_mut().map(|mask| &mut mask.plane)
    }

    pub fn set_first_mask(&mut self, mask: Option<LayerMask>) {
        self.masks = mask
            .into_iter()
            .map(|plane| MaskEntry {
                id: 0,
                name: "Mask".into(),
                plane,
            })
            .collect();
    }

    pub fn has_enabled_masks(&self) -> bool {
        self.masks.iter().any(|mask| mask.plane.enabled)
    }

    pub fn mask_bytes(&self) -> usize {
        self.masks
            .iter()
            .map(|mask| mask.plane.tiles.len() * MASK_TILE_BYTES)
            .sum()
    }

    pub fn mask_buffers(&self) -> impl Iterator<Item = &Tile> {
        self.masks.iter().flat_map(|mask| mask.plane.tiles.values())
    }

    pub fn is_adjustment(&self) -> bool {
        matches!(self.content, LayerContent::Adjustment { .. })
    }

    pub fn raster_opt(&self) -> Option<&RasterPlane> {
        match &self.content {
            LayerContent::Raster(raster) => Some(raster),
            LayerContent::Group { .. }
            | LayerContent::Adjustment { .. }
            | LayerContent::Vector(_)
            | LayerContent::CelTrack { .. } => None,
        }
    }

    pub fn raster(&self) -> Result<&RasterPlane, String> {
        self.raster_opt()
            .ok_or_else(|| "请先选择组内的像素图层".into())
    }

    pub fn raster_mut(&mut self) -> Result<&mut RasterPlane, String> {
        match &mut self.content {
            LayerContent::Raster(raster) => Ok(raster),
            LayerContent::Group { .. }
            | LayerContent::Adjustment { .. }
            | LayerContent::Vector(_)
            | LayerContent::CelTrack { .. } => Err("请先选择像素图层".into()),
        }
    }

    pub fn raster_keys(&self) -> impl Iterator<Item = TileKey> + '_ {
        self.raster_opt()
            .into_iter()
            .flat_map(|raster| raster.tiles().keys().copied())
    }

    pub fn is_vector(&self) -> bool {
        matches!(
            self.content,
            LayerContent::Vector(_)
                | LayerContent::CelTrack {
                    kind: crate::animation::CelKind::Vector
                }
        )
    }

    pub fn vector(&self) -> Result<&Arc<crate::vector::VectorLayer>, String> {
        match &self.content {
            LayerContent::Vector(vector) => Ok(vector),
            _ => Err("请先选择矢量图层".into()),
        }
    }

    pub fn content_keys(&self, canvas: Rect) -> std::collections::BTreeSet<TileKey> {
        match &self.content {
            LayerContent::Vector(vector) => crate::vector::keys(vector, canvas),
            _ => self.raster_keys().collect(),
        }
    }

    pub fn raster_buffers(&self) -> impl Iterator<Item = &Tile> {
        self.raster_opt()
            .into_iter()
            .flat_map(|raster| raster.tiles().values())
    }

    pub fn rgba_tile<'a>(
        &'a self,
        palette: Option<&IndexedPalette>,
        key: TileKey,
    ) -> Option<std::borrow::Cow<'a, [u8]>> {
        let raster = self.raster_opt()?;
        let tile = raster.tiles().get(&key)?;
        Some(match raster {
            RasterPlane::Rgba(_) => std::borrow::Cow::Borrowed(tile),
            RasterPlane::Indexed(_) => {
                let palette = palette.expect("索引图层缺少调色板");
                let pixels = tile
                    .iter()
                    .flat_map(|&index| palette.premultiplied(index))
                    .collect();
                std::borrow::Cow::Owned(pixels)
            }
        })
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
pub struct Document {
    pub width: u32,
    pub height: u32,
    pub layers: Vec<Layer>,
    pub active: u32,
    pub next_id: u32,
    pub palette: Option<IndexedPalette>,
    pub next_mask_id: u32,
    pub active_mask_id: Option<u32>,
    pub assistants: Arc<crate::assistants::AssistantSet>,
    pub animation: Option<Arc<crate::animation::AnimationSet>>,
    pub aseprite_metadata: Option<Arc<crate::aseprite::ProjectMetadata>>,
}

impl Document {
    pub fn bounds(&self) -> Rect {
        Rect {
            left: 0,
            top: 0,
            right: self.width,
            bottom: self.height,
        }
    }

    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        if width == 0
            || height == 0
            || width > MAX_DIMENSION
            || height > MAX_DIMENSION
            || u64::from(width) * u64::from(height) > MAX_PIXELS
        {
            return Err("画布尺寸超出限制".into());
        }
        Ok(Self {
            width,
            height,
            layers: vec![Layer::new(1, "图层 1".into())],
            active: 1,
            next_id: 2,
            palette: None,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
        })
    }

    pub fn validate(&self) -> Result<(), String> {
        crate::aseprite::validate_metadata(self)?;
        if self.animation.is_some() {
            return crate::animation::validate(self);
        }
        if self
            .layers
            .iter()
            .any(|layer| matches!(layer.content, LayerContent::CelTrack { .. }))
        {
            return Err("动画轨道缺少帧数据".into());
        }
        Self::new(self.width, self.height)?;
        self.assistants.validate()?;
        if let Some(palette) = &self.palette {
            palette.validate()?;
        }
        if self.layers.is_empty()
            || self.layers.len() > MAX_LAYER_NODES
            || self
                .layers
                .iter()
                .filter(|layer| layer.raster_opt().is_some() || layer.is_vector())
                .count()
                > MAX_LAYERS
            || !self.layers.iter().any(|layer| layer.id == self.active)
        {
            return Err("工程图层数据无效".into());
        }
        crate::groups::Hierarchy::new(self)?;
        if self.pixel_bytes() > MAX_DOCUMENT_BYTES {
            return Err("工程像素超过内存限制".into());
        }
        let vector_bytes: usize = self
            .layers
            .iter()
            .filter_map(|layer| layer.vector().ok())
            .map(|vector| vector.bytes())
            .sum();
        let vector_segments: usize = self
            .layers
            .iter()
            .filter_map(|layer| layer.vector().ok())
            .map(|vector| vector.segment_count())
            .sum();
        if vector_bytes > MAX_VECTOR_GEOMETRY_BYTES
            || vector_segments > MAX_DOCUMENT_VECTOR_SEGMENTS
        {
            return Err("矢量几何超过内存限制".into());
        }
        let mut ids = std::collections::BTreeSet::new();
        let mut mask_ids = std::collections::BTreeSet::new();
        for layer in &self.layers {
            if layer.masks.len() > MAX_LAYER_MASKS {
                return Err("图层蒙版数量超出限制".into());
            }
            for mask in &layer.masks {
                mask.plane.validate()?;
                if mask.id == 0
                    || mask.id >= self.next_mask_id
                    || !mask_ids.insert(mask.id)
                    || mask.name.is_empty()
                    || mask.name.len() > MAX_LAYER_NAME_BYTES
                {
                    return Err("工程蒙版属性无效".into());
                }
            }
            if layer.id == 0
                || !ids.insert(layer.id)
                || layer.id >= self.next_id
                || layer.name.len() > MAX_LAYER_NAME_BYTES
                || !layer.opacity.is_finite()
                || !(0.0..=1.0).contains(&layer.opacity)
            {
                return Err("工程图层属性无效".into());
            }
            let Some(raster) = layer.raster_opt() else {
                if let LayerContent::Vector(vector) = &layer.content {
                    if self.palette.is_some() {
                        return Err("矢量图层暂仅支持 RGBA 工程".into());
                    }
                    vector.validate()?;
                }
                if layer.alpha_locked {
                    return Err("非像素图层不能锁定透明像素".into());
                }
                if let LayerContent::Adjustment { settings } = &layer.content {
                    settings.compile()?;
                    if layer.blend != BlendMode::Normal {
                        return Err("调整图层暂仅支持普通混合模式".into());
                    }
                }
                if matches!(
                    layer.content,
                    LayerContent::Group {
                        isolation: GroupIsolation::PassThrough,
                        ..
                    }
                ) && (layer.opacity != 1.0
                    || !layer.masks.is_empty()
                    || layer.clipping
                    || layer.blend != BlendMode::Normal)
                {
                    return Err("穿透图层组暂不支持不透明度、蒙版、剪贴和自身混合模式".into());
                }
                continue;
            };
            if raster.is_indexed() != self.palette.is_some() {
                return Err("工程颜色模式不一致".into());
            }
            for (&(x, y), pixels) in raster.tiles() {
                if x >= self.width.div_ceil(TILE_SIZE)
                    || y >= self.height.div_ceil(TILE_SIZE)
                    || pixels.len() != raster.tile_bytes()
                    || (raster.is_indexed()
                        && pixels.iter().any(|&index| {
                            usize::from(index) >= self.palette.as_ref().unwrap().colors.len()
                        }))
                {
                    return Err("工程像素数据无效".into());
                }
            }
        }
        if self.next_mask_id == 0
            || self.active_mask_id.is_some_and(|id| {
                !self
                    .layers
                    .iter()
                    .find(|layer| layer.id == self.active)
                    .unwrap()
                    .masks
                    .iter()
                    .any(|mask| mask.id == id)
            })
        {
            return Err("工程蒙版编辑目标无效".into());
        }
        Ok(())
    }

    pub fn active_mut(&mut self) -> &mut Layer {
        self.layers
            .iter_mut()
            .find(|layer| layer.id == self.active)
            .unwrap()
    }

    pub fn tile_count(&self) -> usize {
        self.pixel_bytes().div_ceil(TILE_BYTES)
    }

    pub fn pixel_bytes(&self) -> usize {
        if self.animation.is_some() {
            return self
                .resources()
                .collect::<std::collections::HashSet<_>>()
                .into_iter()
                .map(|(_, bytes)| bytes)
                .sum();
        }
        self.layers
            .iter()
            .map(|layer| {
                layer
                    .raster_opt()
                    .map_or(0, |raster| raster.tiles().len() * raster.tile_bytes())
                    + layer.mask_bytes()
                    + layer.vector().map_or(0, |vector| vector.bytes())
            })
            .sum::<usize>()
            + self.assistants.bytes()
            + self
                .aseprite_metadata
                .as_ref()
                .map_or(0, |metadata| metadata.bytes())
    }

    pub fn buffers(&self) -> impl Iterator<Item = &Tile> {
        self.layers
            .iter()
            .flat_map(|layer| {
                layer
                    .raster_opt()
                    .into_iter()
                    .flat_map(|raster| raster.tiles().values())
                    .chain(layer.mask_buffers())
            })
            .chain(
                self.animation
                    .iter()
                    .flat_map(|animation| animation.cels.values())
                    .flat_map(|cel| cel.buffers()),
            )
    }

    pub fn resources(&self) -> impl Iterator<Item = (usize, usize)> + '_ {
        self.buffers()
            .map(|tile| (Arc::as_ptr(tile) as usize, tile.len()))
            .chain(
                self.layers
                    .iter()
                    .filter_map(|layer| layer.vector().ok())
                    .flat_map(|vector| {
                        std::iter::once((Arc::as_ptr(vector) as usize, vector.own_bytes())).chain(
                            vector
                                .objects
                                .iter()
                                .map(|object| (Arc::as_ptr(object) as usize, object.bytes())),
                        )
                    }),
            )
            .chain((!self.assistants.items.is_empty()).then(|| {
                (
                    Arc::as_ptr(&self.assistants) as usize,
                    self.assistants.bytes(),
                )
            }))
            .chain(self.animation.iter().flat_map(|animation| {
                std::iter::once((Arc::as_ptr(animation) as usize, animation.metadata_bytes()))
                    .chain(
                        animation
                            .cels
                            .values()
                            .flat_map(|cel| cel.vector_resources()),
                    )
            }))
            .chain(
                self.aseprite_metadata
                    .iter()
                    .map(|metadata| (Arc::as_ptr(metadata) as usize, metadata.bytes())),
            )
    }

    pub fn assign_mask_ids(&mut self) -> Result<(), String> {
        let maximum = self
            .layers
            .iter()
            .flat_map(|layer| &layer.masks)
            .map(|mask| mask.id)
            .max()
            .unwrap_or(0);
        self.next_mask_id = self
            .next_mask_id
            .max(maximum.checked_add(1).ok_or("蒙版编号超出限制")?)
            .max(1);
        for layer in &mut self.layers {
            for mask in &mut layer.masks {
                if mask.id == 0 {
                    mask.id = self.next_mask_id;
                    self.next_mask_id =
                        self.next_mask_id.checked_add(1).ok_or("蒙版编号超出限制")?;
                }
            }
        }
        self.normalize_mask_target();
        Ok(())
    }

    pub fn normalize_mask_target(&mut self) {
        if self.animation.is_some() {
            if self
                .active_mask_id
                .is_some_and(|id| !self.active_masks().iter().any(|mask| mask.id == id))
            {
                self.active_mask_id = None;
            }
            return;
        }
        if self
            .active_mask_id
            .is_some_and(|id| !self.active_mut().masks.iter().any(|mask| mask.id == id))
        {
            self.active_mask_id = None;
        }
    }

    pub fn selected_mask(&self) -> Option<&MaskEntry> {
        let masks = self.active_masks();
        self.active_mask_id
            .and_then(|id| masks.iter().find(|mask| mask.id == id))
            .or_else(|| masks.first())
    }

    pub fn active_masks(&self) -> &[MaskEntry] {
        if let Some(animation) = &self.animation {
            if let Some(cel) = animation.active_cel(self.active) {
                return &cel.masks;
            }
        }
        self.layers
            .iter()
            .find(|layer| layer.id == self.active)
            .map_or(&[], |layer| &layer.masks)
    }

    pub fn selected_mask_mut(&mut self) -> Option<&mut MaskEntry> {
        let id = self.active_mask_id;
        let layer = self.active_mut();
        let index = id
            .and_then(|id| layer.masks.iter().position(|mask| mask.id == id))
            .unwrap_or(0);
        layer.masks.get_mut(index)
    }
}

#[derive(Clone, Copy, Deserialize)]
pub struct Brush {
    pub size: f32,
    pub opacity: f32,
    pub hardness: f32,
    pub color: [u8; 3],
    #[serde(default)]
    pub index: Option<u8>,
    pub eraser: bool,
    #[serde(default)]
    pub smudge: bool,
    #[serde(default)]
    pub symmetry: Symmetry,
    #[serde(default)]
    pub tip: BrushTip,
    #[serde(default)]
    pub texture: BrushTexture,
    #[serde(default)]
    pub raster: BrushRaster,
    #[serde(default = "default_aspect")]
    pub aspect: f32,
    #[serde(default)]
    pub angle: f32,
    #[serde(default)]
    pub follow_direction: bool,
    #[serde(default)]
    pub grain: f32,
    #[serde(default = "default_spacing")]
    pub spacing: f32,
    #[serde(default)]
    pub stabilization: f32,
    #[serde(default)]
    pub pressure_curve: f32,
    #[serde(default = "default_size_pressure")]
    pub size_pressure: f32,
    #[serde(default)]
    pub opacity_pressure: f32,
    #[serde(default)]
    pub mix: f32,
    #[serde(default)]
    pub paper: f32,
}

#[derive(Clone, Copy, Default, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum BrushTip {
    #[default]
    Round,
    Flat,
    Leaf,
    Comb,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BrushTexture {
    #[default]
    Smooth,
    Graphite,
    Charcoal,
    Bristle,
    DryBristle,
    Pigment,
    Canvas,
    Wash,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BrushRaster {
    #[default]
    Antialiased,
    Pixel,
    PixelPerfect,
}

fn default_aspect() -> f32 {
    1.0
}
fn default_spacing() -> f32 {
    BRUSH_SPACING_RATIO
}
fn default_size_pressure() -> f32 {
    1.0
}

impl Default for Brush {
    fn default() -> Self {
        Self {
            size: 12.0,
            opacity: 1.0,
            hardness: 0.9,
            color: [0, 0, 0],
            index: None,
            eraser: false,
            smudge: false,
            symmetry: Symmetry::default(),
            tip: BrushTip::Round,
            texture: BrushTexture::Smooth,
            raster: BrushRaster::Antialiased,
            aspect: 1.0,
            angle: 0.0,
            follow_direction: false,
            grain: 0.0,
            spacing: BRUSH_SPACING_RATIO,
            stabilization: 0.0,
            pressure_curve: 0.0,
            size_pressure: 1.0,
            opacity_pressure: 0.0,
            mix: 0.0,
            paper: 0.0,
        }
    }
}

impl Brush {
    pub fn validate(self) -> Result<Self, String> {
        if !self.symmetry.x.is_finite()
            || !self.symmetry.y.is_finite()
            || !(0.0..=1.0).contains(&self.symmetry.x)
            || !(0.0..=1.0).contains(&self.symmetry.y)
        {
            return Err("对称轴位置无效".into());
        }
        if self.smudge && self.symmetry.mode != SymmetryMode::Off {
            return Err("涂抹暂不支持对称绘画".into());
        }
        if !self.size.is_finite()
            || !(1.0..=256.0).contains(&self.size)
            || !self.opacity.is_finite()
            || !(0.0..=1.0).contains(&self.opacity)
            || !self.hardness.is_finite()
            || !(0.0..=1.0).contains(&self.hardness)
            || !self.aspect.is_finite()
            || !(0.1..=1.0).contains(&self.aspect)
            || !self.angle.is_finite()
            || !(-180.0..=180.0).contains(&self.angle)
            || !self.grain.is_finite()
            || !(0.0..=1.0).contains(&self.grain)
            || !self.spacing.is_finite()
            || !(0.02..=1.0).contains(&self.spacing)
            || !self.stabilization.is_finite()
            || !(0.0..=1.0).contains(&self.stabilization)
            || !self.pressure_curve.is_finite()
            || !(-1.0..=1.0).contains(&self.pressure_curve)
            || !self.size_pressure.is_finite()
            || !(0.0..=1.0).contains(&self.size_pressure)
            || !self.opacity_pressure.is_finite()
            || !(0.0..=1.0).contains(&self.opacity_pressure)
            || !self.mix.is_finite()
            || !(0.0..=1.0).contains(&self.mix)
            || !self.paper.is_finite()
            || !(0.0..=1.0).contains(&self.paper)
            || (self.smudge && self.eraser)
        {
            return Err("画笔参数无效".into());
        }
        Ok(self)
    }

    fn pressure_response(self, input: f32) -> f32 {
        let pressure = input.clamp(0.0, 1.0);
        pressure + self.pressure_curve * pressure * (1.0 - pressure)
    }

    pub(crate) fn size_at_pressure(self, input: f32) -> f32 {
        self.size
            * (1.0 - self.size_pressure + self.size_pressure * self.pressure_response(input))
                .clamp(0.05, 1.0)
    }

    pub(crate) fn opacity_at_pressure(self, input: f32) -> f32 {
        self.opacity
            * (1.0 - self.opacity_pressure + self.opacity_pressure * self.pressure_response(input))
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub x: f32,
    pub y: f32,
    pub pressure: f32,
}

#[derive(Clone, Copy, Default, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum SymmetryMode {
    #[default]
    Off,
    Vertical,
    Horizontal,
    Quadrant,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(default)]
pub struct Symmetry {
    pub mode: SymmetryMode,
    pub x: f32,
    pub y: f32,
}

impl Default for Symmetry {
    fn default() -> Self {
        Self {
            mode: SymmetryMode::Off,
            x: 0.5,
            y: 0.5,
        }
    }
}
