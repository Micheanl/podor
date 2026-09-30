use crate::model::*;

pub fn base_index(layers: &[Layer], index: usize) -> usize {
    (0..=index)
        .rev()
        .find(|&candidate| {
            layers[candidate].parent_id == layers[index].parent_id && !layers[candidate].clipping
        })
        .unwrap_or(index)
}

pub fn base_id(layers: &[Layer], index: usize) -> Option<u32> {
    layers[index]
        .clipping
        .then(|| layers[base_index(layers, index)].id)
}

pub fn release_dependents(layers: &mut [Layer], index: usize) {
    if !layers[index].clipping {
        let parent = layers[index].parent_id;
        for candidate in layers.iter_mut().skip(index + 1) {
            if candidate.parent_id != parent {
                continue;
            }
            if !candidate.clipping {
                break;
            }
            candidate.clipping = false;
        }
    }
}

pub fn normalize_bottom(layers: &mut [Layer]) {
    crate::groups::normalize_bottom(layers);
}
