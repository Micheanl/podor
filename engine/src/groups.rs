use crate::model::*;

pub struct Hierarchy {
    pub roots: Vec<usize>,
    pub children: Vec<Vec<usize>>,
    pub parent: Vec<Option<usize>>,
    pub end: Vec<usize>,
    pub depth: Vec<usize>,
    pub visible: Vec<bool>,
    pub locked: Vec<bool>,
}

impl Hierarchy {
    pub fn new(doc: &Document) -> Result<Self, String> {
        let count = doc.layers.len();
        if count > MAX_LAYER_NODES {
            return Err("图层节点超过限制".into());
        }
        let mut result = Self {
            roots: Vec::new(),
            children: vec![Vec::new(); count],
            parent: vec![None; count],
            end: (1..=count).collect(),
            depth: vec![0; count],
            visible: vec![false; count],
            locked: vec![false; count],
        };
        let mut stack: Vec<usize> = Vec::new();
        for (index, layer) in doc.layers.iter().enumerate() {
            let keep = match layer.parent_id {
                None => 0,
                Some(id) => stack
                    .iter()
                    .position(|&parent| doc.layers[parent].id == id)
                    .map(|position| position + 1)
                    .ok_or("图层父级或子树顺序无效")?,
            };
            for parent in stack.drain(keep..) {
                result.end[parent] = index;
            }
            result.parent[index] = stack.last().copied();
            result.depth[index] = stack.len();
            if result.depth[index] > MAX_GROUP_DEPTH
                || layer.is_group() && result.depth[index] == MAX_GROUP_DEPTH
            {
                return Err("图层组嵌套超过限制".into());
            }
            result.visible[index] =
                layer.visible && result.parent[index].is_none_or(|parent| result.visible[parent]);
            result.locked[index] =
                layer.locked || result.parent[index].is_some_and(|parent| result.locked[parent]);
            let siblings = match result.parent[index] {
                Some(parent) => &mut result.children[parent],
                None => &mut result.roots,
            };
            if siblings.is_empty() && layer.clipping {
                return Err("同级最底层不能使用剪贴蒙版".into());
            }
            if layer.clipping {
                let base = siblings
                    .iter()
                    .rev()
                    .find(|&&sibling| !doc.layers[sibling].clipping)
                    .copied()
                    .ok_or("剪贴蒙版没有同级底层")?;
                if doc.layers[base].is_adjustment() {
                    return Err("调整图层不能作为剪贴底层".into());
                }
                if matches!(
                    doc.layers[base].content,
                    LayerContent::Group {
                        isolation: GroupIsolation::PassThrough,
                        ..
                    }
                ) {
                    return Err("穿透图层组暂不能作为剪贴底层".into());
                }
            }
            siblings.push(index);
            if layer.is_group() {
                stack.push(index);
            }
        }
        for parent in stack {
            result.end[parent] = count;
        }
        Ok(result)
    }

    pub fn siblings(&self, index: usize) -> &[usize] {
        self.parent[index].map_or(&self.roots, |parent| &self.children[parent])
    }

    pub fn ui_order(&self) -> Vec<usize> {
        fn visit(plan: &Hierarchy, siblings: &[usize], output: &mut Vec<usize>) {
            for &index in siblings.iter().rev() {
                output.push(index);
                visit(plan, &plan.children[index], output);
            }
        }
        let mut order = Vec::new();
        visit(self, &self.roots, &mut order);
        order
    }
}

pub fn expand_active_ancestors(doc: &mut Document) -> Result<(), String> {
    let plan = Hierarchy::new(doc)?;
    let active = doc
        .layers
        .iter()
        .position(|layer| layer.id == doc.active)
        .ok_or("图层不存在")?;
    let mut parent = plan.parent[active];
    while let Some(index) = parent {
        if let LayerContent::Group { closed, .. } = &mut doc.layers[index].content {
            *closed = false;
        }
        parent = plan.parent[index];
    }
    Ok(())
}

pub fn bounds(doc: &Document, id: u32) -> Result<Rect, String> {
    let plan = Hierarchy::new(doc)?;
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    doc.layers[index..plan.end[index]]
        .iter()
        .filter_map(|layer| crate::transform::bounds(layer, doc.bounds(), doc.palette.as_ref()))
        .reduce(|a, b| Rect {
            left: a.left.min(b.left),
            top: a.top.min(b.top),
            right: a.right.max(b.right),
            bottom: a.bottom.max(b.bottom),
        })
        .ok_or_else(|| "当前图层组没有可变换的内容".into())
}

pub fn geometry(doc: &Document, id: u32, action: crate::LayerAction) -> Result<Document, String> {
    let index = check_editable(doc, id, false)?;
    let plan = Hierarchy::new(doc)?;
    match action {
        crate::LayerAction::Gradient { .. }
        | crate::LayerAction::GenerateLines { .. }
        | crate::LayerAction::Adjustment { .. }
        | crate::LayerAction::Vector { .. } => return Err("请先选择组内的像素图层".into()),
        crate::LayerAction::Translate { dx, dy }
            if dx.unsigned_abs() > doc.width || dy.unsigned_abs() > doc.height =>
        {
            return Err("移动距离超出画布尺寸".into())
        }
        _ => {}
    }
    let end = plan.end[index];
    if doc.layers[index..end]
        .iter()
        .enumerate()
        .any(|(offset, layer)| plan.locked[index + offset] || layer.alpha_locked)
    {
        return Err("请先解锁组内所有图层及透明度".into());
    }
    let area = if matches!(action, crate::LayerAction::Transform { .. }) {
        Some(bounds(doc, id)?)
    } else {
        None
    };
    let mut next = doc.clone();
    for node in index..end {
        let source = &doc.layers[node];
        if let Ok(vector) = source.vector() {
            let matrix = match action {
                crate::LayerAction::Translate { dx, dy } => {
                    tiny_skia::Transform::from_translate(dx as f32, dy as f32)
                }
                crate::LayerAction::Transform { transform } => {
                    crate::vector::transform_matrix(area.unwrap(), transform)?
                }
                _ => return Err("请先选择组内图层".into()),
            };
            next.layers[node].content =
                LayerContent::Vector(crate::vector::transformed(vector, matrix)?);
        }
        if source.raster_opt().is_some() {
            let mut working = next.clone();
            working.layers[node].visible = true;
            let pixels = match action {
                crate::LayerAction::Translate { dx, dy } => {
                    crate::translation::translate(&working, source.id, dx, dy, None)?
                }
                crate::LayerAction::Transform { transform } => {
                    crate::transform::prepare_with_bounds(&working, source.id, transform, area)?
                }
                crate::LayerAction::Gradient { .. }
                | crate::LayerAction::GenerateLines { .. }
                | crate::LayerAction::Adjustment { .. }
                | crate::LayerAction::Vector { .. } => return Err("请先选择组内的像素图层".into()),
            };
            next.layers[node].raster_mut()?.set_tiles(pixels);
        }
        for (mask, target) in source
            .masks
            .iter()
            .zip(&mut next.layers[node].masks)
            .filter(|(mask, _)| mask.plane.linked)
        {
            let mask = &mask.plane;
            target.plane = match action {
                crate::LayerAction::Translate { dx, dy } => crate::masks::offset(mask, dx, dy)?,
                crate::LayerAction::Transform { transform } => {
                    let area = area.unwrap();
                    crate::masks::affine(
                        mask,
                        MaskBounds {
                            left: area.left as i32,
                            top: area.top as i32,
                            right: area.right as i32,
                            bottom: area.bottom as i32,
                        },
                        transform,
                    )?
                }
                _ => return Err("请先选择组内的像素图层".into()),
            };
        }
        if next.pixel_bytes() > MAX_DOCUMENT_BYTES {
            return Err("图层组变换超出像素内存限制".into());
        }
    }
    crate::masks::check_transaction(doc, &next)?;
    Ok(next)
}

pub fn check_editable(doc: &Document, id: u32, pixels: bool) -> Result<usize, String> {
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    let hierarchy = Hierarchy::new(doc)?;
    if hierarchy.locked[index] || !hierarchy.visible[index] {
        return Err("请先显示并解锁当前图层及父级".into());
    }
    if pixels {
        doc.layers[index].raster()?;
    }
    Ok(index)
}

fn insertion(doc: &Document, parent_id: Option<u32>, index: usize) -> Result<usize, String> {
    let plan = Hierarchy::new(doc)?;
    let (siblings, end) = match parent_id {
        None => (&plan.roots, doc.layers.len()),
        Some(id) => {
            let parent = doc
                .layers
                .iter()
                .position(|layer| layer.id == id)
                .ok_or("父级图层组不存在")?;
            if !doc.layers[parent].is_group() {
                return Err("父级必须为图层组".into());
            }
            (&plan.children[parent], plan.end[parent])
        }
    };
    if index > siblings.len() {
        return Err("图层组插入位置无效".into());
    }
    Ok(siblings.get(index).copied().unwrap_or(end))
}

pub fn create(
    doc: &Document,
    name: String,
    parent_id: Option<u32>,
    index: usize,
    isolation: GroupIsolation,
) -> Result<Document, String> {
    if name.is_empty() || name.len() > MAX_LAYER_NAME_BYTES {
        return Err("图层组名称无效".into());
    }
    if doc.next_id == u32::MAX {
        return Err("图层编号已达到上限".into());
    }
    let position = insertion(doc, parent_id, index)?;
    let mut next = doc.clone();
    let mut group = Layer::group(next.next_id, name, isolation);
    group.parent_id = parent_id;
    next.active = group.id;
    next.next_id += 1;
    next.layers.insert(position, group);
    next.normalize_mask_target();
    next.validate()?;
    Ok(next)
}

pub fn group(
    doc: &Document,
    ids: &[u32],
    name: String,
    parent_id: Option<u32>,
    index: usize,
) -> Result<Document, String> {
    let mut next = create(doc, name, parent_id, index, GroupIsolation::Isolated)?;
    if ids.is_empty() {
        return Err("请选择需要分组的图层".into());
    }
    let plan = Hierarchy::new(doc)?;
    let first = doc
        .layers
        .iter()
        .position(|layer| layer.id == ids[0])
        .ok_or("图层不存在")?;
    let siblings = plan.siblings(first);
    let start = siblings
        .iter()
        .position(|&sibling| sibling == first)
        .unwrap();
    let chosen = siblings
        .get(start..start + ids.len())
        .ok_or("请选择连续同级图层")?;
    if doc.layers[first].parent_id != parent_id
        || chosen
            .iter()
            .zip(ids)
            .any(|(&node, &id)| doc.layers[node].id != id)
    {
        return Err("请选择按底到顶顺序排列的连续同级图层".into());
    }
    if doc.layers[first].clipping
        || siblings
            .get(start + ids.len())
            .is_some_and(|&node| doc.layers[node].clipping)
    {
        return Err("请将完整剪贴链一起分组".into());
    }
    let group_id = next.active;
    let end = plan.end[*chosen.last().unwrap()];
    let mut subtree: Vec<_> = doc.layers[first..end].to_vec();
    for layer in &mut subtree {
        if ids.contains(&layer.id) {
            layer.parent_id = Some(group_id);
        }
    }
    next.layers
        .retain(|layer| !doc.layers[first..end].iter().any(|old| old.id == layer.id));
    let position = next
        .layers
        .iter()
        .position(|layer| layer.id == group_id)
        .unwrap()
        + 1;
    next.layers.splice(position..position, subtree);
    next.normalize_mask_target();
    next.validate()?;
    Ok(next)
}

pub fn move_node(
    doc: &Document,
    id: u32,
    parent_id: Option<u32>,
    index: usize,
) -> Result<Document, String> {
    let plan = Hierarchy::new(doc)?;
    let start = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    let end = plan.end[start];
    if doc.layers[start].parent_id == parent_id
        && plan.siblings(start).iter().position(|&node| node == start) == Some(index)
    {
        return Ok(doc.clone());
    }
    if parent_id.is_some_and(|parent| {
        doc.layers[start..end]
            .iter()
            .any(|layer| layer.id == parent)
    }) {
        return Err("图层组不能移动到自身或子级".into());
    }
    let mut next = doc.clone();
    crate::clipping::release_dependents(&mut next.layers, start);
    let mut moved: Vec<_> = next.layers.drain(start..end).collect();
    if moved[0].parent_id != parent_id {
        moved[0].clipping = false;
    }
    moved[0].parent_id = parent_id;
    normalize_bottom(&mut next.layers);
    let position = insertion(&next, parent_id, index)?;
    next.layers.splice(position..position, moved);
    normalize_bottom(&mut next.layers);
    next.normalize_mask_target();
    next.validate()?;
    Ok(next)
}

pub fn ungroup(doc: &Document, id: u32) -> Result<Document, String> {
    let plan = Hierarchy::new(doc)?;
    let start = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层组不存在")?;
    let group = &doc.layers[start];
    if !group.is_group() {
        return Err("请选择图层组".into());
    }
    if group.opacity != 1.0
        || !group.masks.is_empty()
        || group.clipping
        || group.blend != BlendMode::Normal
    {
        return Err("请先解除组不透明度、蒙版、剪贴及混合效果再取消分组".into());
    }
    let mut next = doc.clone();
    crate::clipping::release_dependents(&mut next.layers, start);
    for layer in &mut next.layers[start + 1..plan.end[start]] {
        if layer.parent_id == Some(id) {
            layer.parent_id = group.parent_id;
            layer.visible &= group.visible;
            layer.locked |= group.locked;
        }
    }
    next.layers.remove(start);
    if next.layers.is_empty() {
        return Err("至少保留一个图层节点".into());
    }
    if next.active == id {
        next.active = next.layers[start.min(next.layers.len() - 1)].id;
    }
    normalize_bottom(&mut next.layers);
    next.normalize_mask_target();
    next.validate()?;
    Ok(next)
}

pub fn normalize_bottom(layers: &mut [Layer]) {
    let mut seen = std::collections::BTreeSet::new();
    for layer in layers {
        if seen.insert(layer.parent_id) {
            layer.clipping = false;
        }
    }
}
