use crate::{adjustments, model::*, raster, selection::Selection};
use serde::Deserialize;
use std::collections::BTreeSet;

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AdjustmentKind {
    Tone,
    Blur,
    LayerBlend,
}

#[derive(Clone, Copy, Deserialize)]
pub struct AdjustmentSettings {
    pub kind: AdjustmentKind,
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub sigma: f32,
    #[serde(default = "default_opacity")]
    pub opacity: f32,
    #[serde(default)]
    pub blend: BlendMode,
}

fn default_opacity() -> f32 {
    1.0
}

#[derive(Deserialize)]
pub struct AdjustmentRequest {
    pub id: u32,
    pub revision: u64,
    pub settings: AdjustmentSettings,
}

pub fn prepare(
    source: &Document,
    revision: u64,
    selection: Option<&Selection>,
    request: AdjustmentRequest,
) -> Result<(Document, BTreeSet<TileKey>), String> {
    if request.revision != revision || request.id != source.active {
        return Err("图层已变化，请重新调整".into());
    }
    let original = source
        .layers
        .iter()
        .find(|layer| layer.id == request.id)
        .unwrap();
    if original.locked && !matches!(request.settings.kind, AdjustmentKind::LayerBlend) {
        return Err("图层已锁定，请先解锁".into());
    }
    if !original.visible && !matches!(request.settings.kind, AdjustmentKind::LayerBlend) {
        return Err("请先显示当前图层".into());
    }
    let mut document = source.clone();
    let bounds = source.bounds();
    let region = selection.map_or(bounds, Selection::bounds);
    let settings = request.settings;
    match settings.kind {
        AdjustmentKind::Tone => adjustments::tone(
            document.active_mut(),
            region,
            selection,
            adjustments::Tone {
                brightness: settings.brightness,
                contrast: settings.contrast,
                saturation: settings.saturation,
            },
        )?,
        AdjustmentKind::Blur => adjustments::blur(
            document.active_mut(),
            bounds,
            region,
            selection,
            settings.sigma,
        )?,
        AdjustmentKind::LayerBlend => {
            if !settings.opacity.is_finite() || !(0.0..=1.0).contains(&settings.opacity) {
                return Err("图层属性无效".into());
            }
            let edited = document.active_mut();
            edited.opacity = settings.opacity;
            edited.blend = settings.blend;
        }
    }
    document.validate()?;
    let edited = document.active_mut();
    let mut keys: BTreeSet<_> = original
        .tiles
        .keys()
        .chain(edited.tiles.keys())
        .copied()
        .collect();
    if original.opacity == edited.opacity && original.blend == edited.blend {
        keys.retain(|key| original.tiles.get(key) != edited.tiles.get(key));
    }
    if !original.visible {
        keys.clear();
    }
    Ok((document, keys))
}

pub fn frame(document: &Document, keys: &BTreeSet<TileKey>) -> Vec<u8> {
    let mut output = Vec::with_capacity(16 + keys.len() * (8 + TILE_BYTES));
    for value in [
        document.width,
        document.height,
        TILE_SIZE,
        keys.len() as u32,
    ] {
        output.extend(value.to_le_bytes());
    }
    for &key in keys {
        output.extend(key.0.to_le_bytes());
        output.extend(key.1.to_le_bytes());
        output.extend(raster::composite_tile(document, key));
    }
    output
}
