use crate::{adjustments, model::*, raster, selection::Selection};
use serde::Deserialize;
use std::collections::BTreeSet;

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AdjustmentKind {
    Tone,
    Blur,
    LayerBlend,
    Curves,
    GradientMap,
}

#[derive(Clone, Deserialize)]
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
    #[serde(default)]
    pub curves: crate::curves::Curves,
    #[serde(default)]
    pub gradient_map: crate::gradient_map::GradientMap,
}

impl AdjustmentSettings {
    pub fn gradient_map(settings: crate::gradient_map::GradientMap) -> Self {
        Self {
            kind: AdjustmentKind::GradientMap,
            brightness: 0.0,
            contrast: 0.0,
            saturation: 0.0,
            sigma: 0.0,
            opacity: 1.0,
            blend: BlendMode::Normal,
            curves: Default::default(),
            gradient_map: settings,
        }
    }
}

fn default_opacity() -> f32 {
    1.0
}

#[derive(Deserialize)]
pub struct AdjustmentRequest {
    #[serde(default)]
    pub frame_id: Option<u32>,
    #[serde(default, deserialize_with = "crate::animation_engine::optional_cel")]
    pub cel_id: Option<Option<u32>>,
    #[serde(default)]
    pub target_layer_id: Option<u32>,
    pub id: u32,
    pub revision: u64,
    #[serde(default, alias = "selectionId")]
    pub selection_id: Option<u64>,
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
    let hierarchy = crate::groups::Hierarchy::new(source)?;
    let index = source
        .layers
        .iter()
        .position(|layer| layer.id == request.id)
        .ok_or("图层不存在")?;
    if !matches!(request.settings.kind, AdjustmentKind::LayerBlend) {
        crate::groups::check_editable(source, request.id, true)?;
    }
    let original = &source.layers[index];
    let mut document = source.clone();
    let bounds = source.bounds();
    let region = selection.map_or(bounds, Selection::bounds);
    let settings = request.settings;
    match settings.kind {
        AdjustmentKind::GradientMap => {
            crate::gradient_map::apply(&mut document, region, selection, &settings.gradient_map)?
        }
        AdjustmentKind::Curves => {
            crate::curves::apply(document.active_mut(), region, selection, &settings.curves)?
        }
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
    let edited = &document.layers[index];
    let mut keys: BTreeSet<_> = source.layers[index..hierarchy.end[index]]
        .iter()
        .chain(document.layers[index..hierarchy.end[index]].iter())
        .flat_map(|layer| layer.content_keys(bounds))
        .collect();
    if original.is_adjustment() {
        keys.extend(crate::adjustment_layers::affected_keys(source, index)?);
    }
    if original.opacity == edited.opacity && original.blend == edited.blend {
        if original.is_group() || original.is_adjustment() || original.is_vector() {
            keys.clear();
        } else {
            let original = original.raster()?.tiles();
            let edited = edited.raster()?.tiles();
            keys.retain(|key| original.get(key) != edited.get(key));
        }
    }
    if !hierarchy.visible[index] {
        keys.clear();
    }
    Ok((document, keys))
}

pub fn frame(document: &Document, keys: &BTreeSet<TileKey>, transparent: bool) -> Vec<u8> {
    let mut output = Vec::with_capacity(16 + keys.len() * (8 + TILE_BYTES));
    for value in [
        document.width,
        document.height,
        TILE_SIZE,
        keys.len() as u32,
    ] {
        output.extend(value.to_le_bytes());
    }
    let mut compositor = raster::FrameCompositor::new(document, transparent);
    for &key in keys {
        output.extend(key.0.to_le_bytes());
        output.extend(key.1.to_le_bytes());
        output.extend(compositor.tile(document, key));
    }
    output
}
