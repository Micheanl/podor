mod export;
mod import;
#[cfg(test)]
mod tests;

use crate::{animation::TagDirection, model::*, Engine};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

pub(crate) use import::import;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PaletteMetadata {
    pub colors: Vec<[u8; 4]>,
    pub names: Vec<Option<String>>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GridMetadata {
    pub x: i16,
    pub y: i16,
    pub width: u16,
    pub height: u16,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProjectMetadata {
    pub companion_palette: Option<PaletteMetadata>,
    pub indexed_names: Vec<Option<String>>,
    pub grid: GridMetadata,
    pub srgb: bool,
}

impl ProjectMetadata {
    pub fn bytes(&self) -> usize {
        std::mem::size_of::<Self>()
            + self.indexed_names.len() * std::mem::size_of::<Option<String>>()
            + self
                .indexed_names
                .iter()
                .flatten()
                .map(String::len)
                .sum::<usize>()
            + self.companion_palette.as_ref().map_or(0, |palette| {
                palette.colors.len() * 4
                    + palette.names.len() * std::mem::size_of::<Option<String>>()
                    + palette
                        .names
                        .iter()
                        .flatten()
                        .map(String::len)
                        .sum::<usize>()
            })
    }

    pub fn json(&self) -> Value {
        json!({"companionPalette":self.companion_palette.as_ref().map(|palette|json!({"colors":palette.colors,"names":palette.names})),
            "indexedPaletteNames":self.indexed_names,"grid":self.grid,"srgb":self.srgb})
    }
}

pub(crate) fn validate_metadata(document: &Document) -> Result<(), String> {
    let Some(metadata) = &document.aseprite_metadata else {
        return Ok(());
    };
    let names_valid = |names: &[Option<String>]| {
        names
            .iter()
            .flatten()
            .all(|name| name.len() <= MAX_ASEPRITE_NAME_BYTES)
    };
    if metadata.bytes() > MAX_ASEPRITE_METADATA_BYTES
        || metadata.indexed_names.len()
            != document
                .palette
                .as_ref()
                .map_or(0, |palette| palette.colors.len())
        || !names_valid(&metadata.indexed_names)
        || metadata.companion_palette.as_ref().is_some_and(|palette| {
            palette.colors.is_empty()
                || palette.colors.len() > MAX_INDEXED_COLORS
                || palette.names.len() != palette.colors.len()
                || !names_valid(&palette.names)
        })
    {
        return Err("Aseprite 工程元数据无效或超出限制".into());
    }
    Ok(())
}

pub(crate) fn resize_grid(
    metadata: &mut Option<Arc<ProjectMetadata>>,
    x: i64,
    y: i64,
    sx: f64,
    sy: f64,
) -> Result<(), String> {
    let Some(metadata) = metadata else {
        return Ok(());
    };
    let grid = &mut Arc::make_mut(metadata).grid;
    let nx = (f64::from(grid.x) * sx).round() + x as f64;
    let ny = (f64::from(grid.y) * sy).round() + y as f64;
    let width = if grid.width == 0 {
        0.0
    } else {
        (f64::from(grid.width) * sx).round().max(1.0)
    };
    let height = if grid.height == 0 {
        0.0
    } else {
        (f64::from(grid.height) * sy).round().max(1.0)
    };
    if !nx.is_finite()
        || !ny.is_finite()
        || !width.is_finite()
        || !height.is_finite()
        || nx < f64::from(i16::MIN)
        || nx > f64::from(i16::MAX)
        || ny < f64::from(i16::MIN)
        || ny > f64::from(i16::MAX)
        || width > f64::from(u16::MAX)
        || height > f64::from(u16::MAX)
    {
        return Err("Aseprite 网格尺寸超出限制".into());
    }
    *grid = GridMetadata {
        x: nx as i16,
        y: ny as i16,
        width: width as u16,
        height: height as u16,
    };
    Ok(())
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AsepriteExportRequest {
    pub revision: u64,
    #[serde(default)]
    pub bake_layers: bool,
}

impl Engine {
    pub(crate) fn aseprite_export_snapshot(
        &self,
        request: &AsepriteExportRequest,
    ) -> Result<Document, String> {
        if request.revision != self.revision {
            return Err("Aseprite 导出请求已过期".into());
        }
        if self.has_live_stroke() {
            return Err("请先结束笔画再导出 Aseprite 工程".into());
        }
        Ok(self.document.clone())
    }
    pub fn export_aseprite(&self, request: AsepriteExportRequest) -> Result<Vec<u8>, String> {
        export::export(
            &self.aseprite_export_snapshot(&request)?,
            request.bake_layers,
        )
    }
}

pub(crate) fn export(
    document: &Document,
    request: AsepriteExportRequest,
) -> Result<Vec<u8>, String> {
    export::export(document, request.bake_layers)
}

pub(crate) fn issues(
    document: &Document,
) -> (BTreeMap<&'static str, usize>, BTreeMap<&'static str, usize>) {
    let mut editable = BTreeMap::new();
    let mut blocking = BTreeMap::new();
    if validate_metadata(document).is_err() {
        blocking.insert("metadata_invalid", 1);
    }
    let mut isolated = false;
    let mut passthrough = false;
    let mut add = |kind: &'static str, count: usize| {
        if count > 0 {
            *editable.entry(kind).or_default() += count;
        }
    };
    for layer in &document.layers {
        match layer.content {
            LayerContent::Vector(_)
            | LayerContent::CelTrack {
                kind: crate::animation::CelKind::Vector,
            } => add("vector", 1),
            LayerContent::Adjustment { .. } => add("adjustment", 1),
            LayerContent::Group {
                isolation: GroupIsolation::Isolated,
                ..
            } => isolated = true,
            LayerContent::Group {
                isolation: GroupIsolation::PassThrough,
                ..
            } => passthrough = true,
            _ => {}
        }
        add("mask", layer.masks.len());
        add("clipping", usize::from(layer.clipping));
        add("alpha_lock", usize::from(layer.alpha_locked));
        add(
            "opacity_precision",
            usize::from(layer.opacity != (layer.opacity * 255.0).round() / 255.0),
        );
    }
    add("mixed_groups", usize::from(isolated && passthrough));
    add("assistant", document.assistants.items.len());
    if let Some(palette) = &document.palette {
        add(
            "palette_order",
            usize::from(
                palette
                    .order
                    .iter()
                    .enumerate()
                    .any(|(index, &slot)| index != usize::from(slot)),
            ),
        );
        add(
            "companion_palette",
            usize::from(
                document
                    .aseprite_metadata
                    .as_ref()
                    .is_some_and(|metadata| metadata.companion_palette.is_some()),
            ),
        );
    }
    if let Some(animation) = &document.animation {
        for cel in animation.cels.values() {
            add("mask", cel.masks.len());
        }
        let count = animation
            .tags
            .iter()
            .filter(|tag| {
                tag.repeat > 0
                    && matches!(
                        tag.direction,
                        TagDirection::PingPong | TagDirection::PingPongReverse
                    )
            })
            .count();
        if count > 0 {
            blocking.insert("finite_ping_pong_repeat", count);
        }
    }
    (editable, blocking)
}

pub(crate) fn capabilities(document: &Document) -> Value {
    let (editable, blocking) = issues(document);
    let entries = |map: BTreeMap<&str, usize>| {
        map.into_iter()
            .map(|(kind, count)| json!({"kind":kind,"count":count}))
            .collect::<Vec<_>>()
    };
    json!({"operation":27,"maxFrames":MAX_ANIMATION_FRAMES,"maxCels":MAX_ANIMATION_CELS,
        "maxLayers":MAX_LAYERS,"maxLayerNodes":MAX_LAYER_NODES,"maxGroupDepth":MAX_GROUP_DEPTH,
        "maxPaletteColors":MAX_INDEXED_COLORS,"maxInputBytes":MAX_ASEPRITE_BYTES,"maxOutputBytes":MAX_ASEPRITE_BYTES,
        "maxDecodedBytes":MAX_ASEPRITE_DECODED_BYTES,"maxScratchBytes":MAX_ASEPRITE_SCRATCH_BYTES,
        "fullFormatSupport":false,"editableIssues":entries(editable),"blockingIssues":entries(blocking)})
}

fn blend_from(code: u16) -> Result<BlendMode, String> {
    Ok(match code {
        0 => BlendMode::Normal,
        1 => BlendMode::Multiply,
        2 => BlendMode::Screen,
        3 => BlendMode::Overlay,
        4 => BlendMode::Darken,
        5 => BlendMode::Lighten,
        9 => BlendMode::SoftLight,
        10 => BlendMode::Difference,
        _ => return Err("Aseprite 混合模式暂不支持".into()),
    })
}

fn blend_code(blend: BlendMode) -> u16 {
    match blend {
        BlendMode::Normal => 0,
        BlendMode::Multiply => 1,
        BlendMode::Screen => 2,
        BlendMode::Overlay => 3,
        BlendMode::Darken => 4,
        BlendMode::Lighten => 5,
        BlendMode::SoftLight => 9,
        BlendMode::Difference => 10,
    }
}
