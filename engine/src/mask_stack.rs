use crate::model::*;
use std::{
    collections::{BTreeMap, VecDeque},
    sync::Arc,
};

pub fn coverage(layer: &Layer, x: i32, y: i32) -> u8 {
    let mut product = 1u128;
    let mut denominator = 1u128;
    let mut count = 0;
    for mask in layer.masks.iter().filter(|mask| mask.plane.enabled) {
        product *= u128::from(mask.plane.sample(x, y));
        if count > 0 {
            denominator *= 255;
        }
        count += 1;
    }
    if count == 0 {
        255
    } else {
        ((product + denominator / 2) / denominator) as u8
    }
}

#[derive(Default)]
pub struct CoverageCache {
    tiles: BTreeMap<(u32, TileKey), Arc<Vec<u8>>>,
    order: VecDeque<(u32, TileKey)>,
}

impl CoverageCache {
    pub fn remove_layer(&mut self, id: u32) {
        self.tiles.retain(|(layer, _), _| *layer != id);
        self.order.retain(|(layer, _)| *layer != id);
    }

    pub fn tile(&mut self, layer: &Layer, key: TileKey) -> Option<Arc<Vec<u8>>> {
        if layer.masks.iter().filter(|mask| mask.plane.enabled).count() < 2 {
            return None;
        }
        let cache_key = (layer.id, key);
        if let Some(tile) = self.tiles.get(&cache_key) {
            let index = self.order.iter().position(|key| *key == cache_key).unwrap();
            self.order.remove(index);
            self.order.push_back(cache_key);
            return Some(tile.clone());
        }
        let pixels = Arc::new(
            if layer
                .masks
                .iter()
                .filter(|mask| mask.plane.enabled)
                .all(|mask| mask.plane.tiles.is_empty())
            {
                vec![coverage(layer, 0, 0); MASK_TILE_BYTES]
            } else {
                (0..MASK_TILE_BYTES)
                    .map(|index| {
                        coverage(
                            layer,
                            (key.0 * TILE_SIZE + index as u32 % TILE_SIZE) as i32,
                            (key.1 * TILE_SIZE + index as u32 / TILE_SIZE) as i32,
                        )
                    })
                    .collect()
            },
        );
        if self.tiles.len() == MAX_MASK_CACHE_BYTES / MASK_TILE_BYTES {
            self.tiles.remove(&self.order.pop_front().unwrap());
        }
        self.tiles.insert(cache_key, Arc::clone(&pixels));
        self.order.push_back(cache_key);
        Some(pixels)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coverage_lru_is_bounded_reuses_recent_tiles_and_invalidates_edited_targets() {
        let mut layer = Layer::new(1, "Paint".into());
        let bounds = MaskBounds {
            left: 0,
            top: 0,
            right: 128,
            bottom: 128,
        };
        layer.masks = (1..=16)
            .map(|id| MaskEntry {
                id,
                name: "Mask".into(),
                plane: LayerMask::new(bounds, 255),
            })
            .collect();
        let mut cache = CoverageCache::default();
        let first = cache.tile(&layer, (0, 0)).unwrap();
        for x in 1..(MAX_MASK_CACHE_BYTES / MASK_TILE_BYTES) as u32 {
            cache.tile(&layer, (x, 0));
        }
        assert!(Arc::ptr_eq(&first, &cache.tile(&layer, (0, 0)).unwrap()));
        cache.tile(&layer, (256, 0));
        assert!(!cache.tiles.contains_key(&(1, (1, 0))));
        assert!(cache.tiles.contains_key(&(1, (0, 0))));
        assert_eq!(
            cache.tiles.values().map(|tile| tile.len()).sum::<usize>(),
            MAX_MASK_CACHE_BYTES
        );
        assert_eq!(cache.order.len(), cache.tiles.len());
        layer.masks[3].plane.default = 0;
        cache.remove_layer(layer.id);
        assert!(cache.order.is_empty());
        assert!(cache.tiles.is_empty());
        assert!(cache
            .tile(&layer, (0, 0))
            .unwrap()
            .iter()
            .all(|&value| value == 0));
    }
}
