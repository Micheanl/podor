use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, sync::Arc};

pub const TILE_SIZE: u32 = 128;
pub const TILE_BYTES: usize = (TILE_SIZE * TILE_SIZE * 4) as usize;
pub const MAX_DIMENSION: u32 = 8192;
pub const MAX_PIXELS: u64 = 16_777_216;
pub const MAX_LAYERS: usize = 32;
pub const MAX_LAYER_NAME_BYTES: usize = 256;
pub const MAX_HISTORY_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_DOCUMENT_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_STROKE_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_RESAMPLE_CACHE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SELECTION_POINTS: usize = 4096;
pub const MAX_COMMAND_BYTES: usize = 4096;
pub const MAX_SELECTION_COMMAND_BYTES: usize = MAX_SELECTION_POINTS * 64 + 1024;
pub const MAX_CLIPBOARD_BYTES: usize = MAX_PIXELS as usize * 4 + 1024 * 1024;
pub const MAX_TRANSFORM_OFFSET: f64 = MAX_DIMENSION as f64 * 2.0;
pub const MAX_HISTORY_ENTRIES: usize = 60;
pub const MAX_ORA_ENTRIES: usize = 256;
pub const MAX_ORA_METADATA_BYTES: usize = 256 * 1024;
pub const MAX_ORA_XML_NODES: u32 = 1024;
pub const PREVIEW_EDGE: u32 = 96;
pub const EXPORT_THUMBNAIL_EDGE: u32 = 256;
pub const DEFAULT_EXPORT_QUALITY: u8 = 90;
pub const BRUSH_SPACING_RATIO: f32 = 0.08;
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

#[derive(Clone, Serialize, Deserialize)]
pub struct Layer {
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub opacity: f32,
    pub tiles: BTreeMap<TileKey, Tile>,
    pub blend: BlendMode,
    pub alpha_locked: bool,
    pub locked: bool,
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
            tiles: BTreeMap::new(),
            blend: BlendMode::Normal,
            alpha_locked: false,
            locked: false,
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Document {
    pub width: u32,
    pub height: u32,
    pub layers: Vec<Layer>,
    pub active: u32,
    pub next_id: u32,
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
        })
    }

    pub fn validate(&self) -> Result<(), String> {
        Self::new(self.width, self.height)?;
        if self.layers.is_empty()
            || self.layers.len() > MAX_LAYERS
            || !self.layers.iter().any(|layer| layer.id == self.active)
        {
            return Err("工程图层数据无效".into());
        }
        if self.tile_count() > MAX_DOCUMENT_BYTES / TILE_BYTES {
            return Err("工程像素超过内存限制".into());
        }
        let mut ids = std::collections::BTreeSet::new();
        for layer in &self.layers {
            if layer.id == 0
                || !ids.insert(layer.id)
                || layer.id >= self.next_id
                || layer.name.len() > MAX_LAYER_NAME_BYTES
                || !layer.opacity.is_finite()
                || !(0.0..=1.0).contains(&layer.opacity)
            {
                return Err("工程图层属性无效".into());
            }
            for (&(x, y), pixels) in &layer.tiles {
                if x >= self.width.div_ceil(TILE_SIZE)
                    || y >= self.height.div_ceil(TILE_SIZE)
                    || pixels.len() != TILE_BYTES
                {
                    return Err("工程像素数据无效".into());
                }
            }
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
        self.layers.iter().map(|layer| layer.tiles.len()).sum()
    }
}

#[derive(Clone, Copy, Deserialize)]
pub struct Brush {
    pub size: f32,
    pub opacity: f32,
    pub hardness: f32,
    pub color: [u8; 3],
    pub eraser: bool,
    #[serde(default)]
    pub tip: BrushTip,
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
}

#[derive(Clone, Copy, Default, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum BrushTip {
    #[default]
    Round,
    Flat,
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
            eraser: false,
            tip: BrushTip::Round,
            aspect: 1.0,
            angle: 0.0,
            follow_direction: false,
            grain: 0.0,
            spacing: BRUSH_SPACING_RATIO,
            stabilization: 0.0,
            pressure_curve: 0.0,
            size_pressure: 1.0,
            opacity_pressure: 0.0,
        }
    }
}

impl Brush {
    pub fn validate(self) -> Result<Self, String> {
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
