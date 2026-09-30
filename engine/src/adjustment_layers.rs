use crate::{adjustments, curves, gradient_map, groups, masks, model::*, selection::Selection};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeSet;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum AdjustmentEffect {
    Tone(adjustments::Tone),
    Curves(curves::Curves),
    GradientMap(gradient_map::GradientMap),
}

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum AdjustmentSpec {
    Tone {
        brightness: f32,
        contrast: f32,
        saturation: f32,
    },
    Curves {
        curves: curves::Curves,
    },
    GradientMap {
        gradient_map: gradient_map::GradientMap,
    },
}

impl From<AdjustmentSpec> for AdjustmentEffect {
    fn from(spec: AdjustmentSpec) -> Self {
        match spec {
            AdjustmentSpec::Tone {
                brightness,
                contrast,
                saturation,
            } => Self::Tone(adjustments::Tone {
                brightness,
                contrast,
                saturation,
            }),
            AdjustmentSpec::Curves { curves } => Self::Curves(curves),
            AdjustmentSpec::GradientMap { gradient_map } => Self::GradientMap(gradient_map),
        }
    }
}

impl AdjustmentEffect {
    pub fn json(&self) -> Value {
        match self {
            Self::Tone(settings) => {
                json!({"kind":"tone", "brightness":settings.brightness,"contrast":settings.contrast,"saturation":settings.saturation})
            }
            Self::Curves(settings) => json!({"kind":"curves","curves":settings}),
            Self::GradientMap(settings) => json!({"kind":"gradient_map","gradient_map":settings}),
        }
    }

    pub fn compile(&self) -> Result<Kernel, String> {
        Ok(match self {
            Self::Tone(settings) => {
                Kernel::Tone(Box::new(adjustments::ToneKernel::new(*settings)?))
            }
            Self::Curves(settings) => Kernel::Curves(Box::new(curves::tables(settings)?)),
            Self::GradientMap(settings) => {
                Kernel::GradientMap(Box::new(gradient_map::lookup(settings)?))
            }
        })
    }
}

pub enum Kernel {
    Tone(Box<adjustments::ToneKernel>),
    Curves(Box<[[u8; 256]; 3]>),
    GradientMap(Box<[[u8; 3]; 256]>),
}

impl Kernel {
    pub fn apply(
        &self,
        pixels: &mut [u8],
        layer: &Layer,
        key: TileKey,
        cache: &std::cell::RefCell<crate::mask_stack::CoverageCache>,
    ) {
        if !layer.visible || layer.opacity == 0.0 {
            return;
        }
        let opacity = (layer.opacity * 255.0).round() as u32;
        let tile = cache.borrow_mut().tile(layer, key);
        for (index, pixel) in pixels.as_chunks_mut::<4>().0.iter_mut().enumerate() {
            let mask = tile.as_ref().map_or_else(
                || {
                    crate::mask_stack::coverage(
                        layer,
                        (key.0 * TILE_SIZE + index as u32 % TILE_SIZE) as i32,
                        (key.1 * TILE_SIZE + index as u32 / TILE_SIZE) as i32,
                    )
                },
                |tile| tile[index],
            );
            let coverage = ((u32::from(mask) * opacity + 127) / 255) as u8;
            *pixel = match self {
                Self::Tone(kernel) => kernel.mapped(*pixel, coverage),
                Self::Curves(tables) => curves::mapped(*pixel, coverage, tables),
                Self::GradientMap(table) => gradient_map::mapped(*pixel, coverage, table),
            };
        }
    }
}

pub fn affected_keys(doc: &Document, index: usize) -> Result<BTreeSet<TileKey>, String> {
    let plan = groups::Hierarchy::new(doc)?;
    let mut keys: BTreeSet<_> = doc.layers[index..plan.end[index]]
        .iter()
        .flat_map(|layer| layer.content_keys(doc.bounds()))
        .collect();
    for adjustment in (index..plan.end[index]).filter(|&node| doc.layers[node].is_adjustment()) {
        let mut scope = adjustment;
        loop {
            let siblings = plan.siblings(scope);
            let position = siblings
                .iter()
                .position(|&sibling| sibling == scope)
                .unwrap();
            let start = if doc.layers[scope].clipping {
                siblings
                    .iter()
                    .position(|&sibling| sibling == crate::clipping::base_index(&doc.layers, scope))
                    .unwrap()
            } else {
                0
            };
            for &sibling in &siblings[start..position] {
                keys.extend(
                    doc.layers[sibling..plan.end[sibling]]
                        .iter()
                        .flat_map(|layer| layer.content_keys(doc.bounds())),
                );
            }
            match plan.parent[scope] {
                Some(parent)
                    if matches!(
                        doc.layers[parent].content,
                        LayerContent::Group {
                            isolation: GroupIsolation::PassThrough,
                            ..
                        }
                    ) =>
                {
                    scope = parent
                }
                _ => break,
            }
        }
    }
    Ok(keys)
}

pub fn set(doc: &Document, id: u32, settings: AdjustmentEffect) -> Result<Document, String> {
    settings.compile()?;
    let plan = groups::Hierarchy::new(doc)?;
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    if plan.locked[index] {
        return Err("请先解锁调整图层及父级".into());
    }
    let mut next = doc.clone();
    match &mut next.layers[index].content {
        LayerContent::Adjustment { settings: value } => *value = settings,
        _ => return Err("请选择调整图层".into()),
    }
    next.validate()?;
    Ok(next)
}

pub fn create(
    doc: &Document,
    name: String,
    parent_id: Option<u32>,
    index: usize,
    settings: AdjustmentEffect,
    selection: Option<&Selection>,
) -> Result<Document, String> {
    settings.compile()?;
    if let Some(parent) = parent_id {
        groups::check_editable(doc, parent, false)?;
    }
    let mut next = groups::create(doc, name, parent_id, index, GroupIsolation::Isolated)?;
    let active = next.active;
    let layer = next.active_mut();
    layer.content = LayerContent::Adjustment { settings };
    if selection.is_some() {
        layer.set_first_mask(Some(masks::create(
            doc,
            selection,
            masks::MaskMode::Selection,
        )?));
    }
    groups::expand_active_ancestors(&mut next)?;
    next.active = active;
    next.assign_mask_ids()?;
    masks::check_transaction(doc, &next)?;
    Ok(next)
}
