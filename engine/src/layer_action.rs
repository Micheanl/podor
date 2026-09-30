use crate::{
    gradient, masks, model::*, selection::Selection, transform, translation, Gradient,
    LayerTransform,
};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum LayerAction {
    GenerateLines {
        name: String,
        #[serde(default)]
        parent_id: Option<u32>,
        index: usize,
        settings: crate::LineGeneratorSettings,
    },
    Vector {
        edit: crate::vector::Edit,
    },
    Adjustment {
        settings: crate::AdjustmentSpec,
    },
    Translate {
        dx: i32,
        dy: i32,
    },
    Transform {
        transform: LayerTransform,
    },
    Gradient {
        settings: Gradient,
    },
}

#[derive(Deserialize)]
pub struct LayerActionRequest {
    #[serde(default)]
    pub frame_id: Option<u32>,
    #[serde(default, deserialize_with = "crate::animation_engine::optional_cel")]
    pub cel_id: Option<Option<u32>>,
    #[serde(default)]
    pub target_layer_id: Option<u32>,
    pub id: u32,
    pub revision: u64,
    #[serde(alias = "selectionId")]
    pub selection_id: u64,
    #[serde(alias = "maskEditing")]
    pub mask_editing: bool,
    #[serde(default)]
    pub mask_id: Option<u32>,
    pub action: LayerAction,
}

pub fn prepare(
    source: &Document,
    selection: Option<&Selection>,
    mask_editing: bool,
    id: u32,
    action: LayerAction,
) -> Result<Document, String> {
    if let LayerAction::GenerateLines {
        name,
        parent_id,
        index,
        settings,
    } = action
    {
        if mask_editing || selection.is_some() {
            return Err("请先退出蒙版编辑并取消像素选区".into());
        }
        return crate::line_generator::prepare(source, name, parent_id, index, settings);
    }
    if let LayerAction::Vector { edit } = action {
        if mask_editing || selection.is_some() {
            return Err("请先退出蒙版编辑并取消像素选区".into());
        }
        return crate::vector::prepare_edit(source, id, edit);
    }
    if let LayerAction::Adjustment { settings } = action {
        if mask_editing {
            return Err("请先切换到调整图层参数".into());
        }
        return crate::adjustment_layers::set(source, id, settings.into());
    }
    let index = crate::groups::check_editable(
        source,
        id,
        !mask_editing
            && !source
                .layers
                .iter()
                .find(|layer| layer.id == id)
                .is_some_and(Layer::is_vector)
            && !source
                .layers
                .iter()
                .find(|layer| layer.id == id)
                .is_some_and(Layer::is_group),
    )?;
    let layer = &source.layers[index];
    if layer.locked || !layer.visible {
        return Err("请先显示并解锁当前图层".into());
    }
    if layer.is_group() && !mask_editing {
        if selection.is_some() {
            return Err("请先取消选区，再移动或变换图层组".into());
        }
        return crate::groups::geometry(source, id, action);
    }
    if layer.is_vector() && !mask_editing {
        if selection.is_some() {
            return Err("请先取消像素选区，再移动或变换矢量图层".into());
        }
        let matrix = match action {
            LayerAction::Translate { dx, dy } => {
                if dx.unsigned_abs() > source.width || dy.unsigned_abs() > source.height {
                    return Err("移动距离超出画布尺寸".into());
                }
                tiny_skia::Transform::from_translate(dx as f32, dy as f32)
            }
            LayerAction::Transform { transform } => crate::vector::transform_matrix(
                crate::transform::bounds(layer, source.bounds(), None)
                    .ok_or("矢量图层没有可变换的内容")?,
                transform,
            )?,
            _ => return Err("请先选择像素图层".into()),
        };
        let mut next = source.clone();
        next.layers[index].content =
            LayerContent::Vector(crate::vector::transformed(layer.vector()?, matrix)?);
        for (mask, target) in layer
            .masks
            .iter()
            .zip(&mut next.layers[index].masks)
            .filter(|(mask, _)| mask.plane.linked)
        {
            target.plane = match action {
                LayerAction::Translate { dx, dy } => masks::offset(&mask.plane, dx, dy)?,
                LayerAction::Transform { transform } => {
                    let area = crate::transform::bounds(layer, source.bounds(), None).unwrap();
                    masks::affine(
                        &mask.plane,
                        MaskBounds {
                            left: area.left as i32,
                            top: area.top as i32,
                            right: area.right as i32,
                            bottom: area.bottom as i32,
                        },
                        transform,
                    )?
                }
                _ => unreachable!(),
            };
        }
        masks::check_transaction(source, &next)?;
        return Ok(next);
    }
    let mut next = source.clone();
    match action {
        LayerAction::Translate { dx, dy } => {
            if (selection.is_some() || mask_editing) && id != source.active {
                return Err("请先选择当前图层".into());
            }
            if mask_editing {
                let mask = &source.selected_mask().ok_or("当前图层没有蒙版")?.plane;
                next.selected_mask_mut().unwrap().plane =
                    masks::translate_selected(source, mask, dx, dy, selection)?;
            } else {
                next.layers[index]
                    .raster_mut()?
                    .set_tiles(translation::translate(source, id, dx, dy, selection)?);
                if selection.is_none() {
                    for (mask, target) in layer
                        .masks
                        .iter()
                        .zip(&mut next.layers[index].masks)
                        .filter(|(mask, _)| mask.plane.linked)
                    {
                        target.plane = masks::offset(&mask.plane, dx, dy)?;
                    }
                }
            }
        }
        LayerAction::Transform { transform } => {
            if selection.is_some() {
                return Err("请先取消选区，再变换图层".into());
            }
            if mask_editing {
                let mask = &source.selected_mask().ok_or("当前图层没有蒙版")?.plane;
                next.selected_mask_mut().unwrap().plane =
                    masks::affine(mask, mask.bounds, transform)?;
            } else {
                next.layers[index]
                    .raster_mut()?
                    .set_tiles(transform::prepare(source, id, transform)?);
                for (mask, target) in layer
                    .masks
                    .iter()
                    .zip(&mut next.layers[index].masks)
                    .filter(|(mask, _)| mask.plane.linked)
                {
                    let mask = &mask.plane;
                    let bounds = transform::bounds(layer, source.bounds(), source.palette.as_ref())
                        .ok_or("当前图层没有可变换的内容")?;
                    target.plane = masks::affine(
                        mask,
                        MaskBounds {
                            left: bounds.left as i32,
                            top: bounds.top as i32,
                            right: bounds.right as i32,
                            bottom: bounds.bottom as i32,
                        },
                        transform,
                    )?;
                }
            }
        }
        LayerAction::Gradient { settings } => {
            if mask_editing {
                return Err("请先切换到图层像素再绘制渐变".into());
            }
            if source.palette.is_some() {
                return Err("渐变尚未支持索引色，请先转换为 RGBA".into());
            }
            if id != source.active {
                return Err("请先选择当前图层".into());
            }
            next.layers[index]
                .raster_mut()?
                .set_tiles(gradient::prepare(source, selection, settings)?);
        }
        LayerAction::Adjustment { .. }
        | LayerAction::Vector { .. }
        | LayerAction::GenerateLines { .. } => unreachable!(),
    }
    Ok(next)
}

pub fn frame(
    source: &Document,
    document: &Document,
    transparent: bool,
    cache: std::sync::Arc<std::sync::Mutex<crate::vector::RenderCache>>,
) -> Result<Vec<u8>, String> {
    let before_index = source
        .layers
        .iter()
        .position(|layer| layer.id == source.active)
        .ok_or("图层不存在")?;
    let after_index = document
        .layers
        .iter()
        .position(|layer| layer.id == document.active)
        .ok_or("图层不存在")?;
    let mut keys = crate::adjustment_layers::affected_keys(document, after_index)?;
    if source
        .layers
        .iter()
        .any(|layer| layer.id == document.active)
    {
        keys.extend(crate::adjustment_layers::affected_keys(
            source,
            before_index,
        )?);
    }
    let mut output = Vec::new();
    for value in [document.width, document.height, TILE_SIZE, 0] {
        output.extend(value.to_le_bytes());
    }
    let mut original_compositor =
        crate::raster::FrameCompositor::with_vector_cache(source, transparent, cache.clone());
    let mut edited_compositor =
        crate::raster::FrameCompositor::with_vector_cache(document, transparent, cache);
    let mut count = 0u32;
    for key in keys {
        let original = original_compositor.tile(source, key);
        let edited = edited_compositor.tile(document, key);
        if original == edited {
            continue;
        }
        if output.len() + 8 + TILE_BYTES > MAX_LAYER_PREVIEW_BYTES {
            return Err("图层预览超过内存限制".into());
        }
        count += 1;
        output.extend(key.0.to_le_bytes());
        output.extend(key.1.to_le_bytes());
        output.extend(edited);
    }
    output[12..16].copy_from_slice(&count.to_le_bytes());
    Ok(output)
}
