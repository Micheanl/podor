use crate::{
    color_selection::ColorSelection,
    model::*,
    selection::{mix, Selection},
};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque},
    sync::Arc,
};

#[derive(Default)]
pub struct RgbaCache {
    palette: Option<IndexedPalette>,
    tiles: BTreeMap<(u32, TileKey), (Tile, Tile)>,
    order: VecDeque<(u32, TileKey)>,
}

impl RgbaCache {
    pub fn tile(&mut self, layer: &Layer, palette: &IndexedPalette, key: TileKey) -> Option<Tile> {
        let raster = layer.raster_opt()?;
        let source = raster.tiles().get(&key)?;
        if !raster.is_indexed() {
            return Some(source.clone());
        }
        if self.palette.as_ref() != Some(palette) {
            self.palette = Some(palette.clone());
            self.tiles.clear();
            self.order.clear();
        }
        let cache_key = (layer.id, key);
        if let Some((old, pixels)) = self.tiles.get(&cache_key) {
            if Arc::ptr_eq(old, source) {
                return Some(pixels.clone());
            }
        }
        if !self.tiles.contains_key(&cache_key) {
            let limit = MAX_INDEXED_CACHE_BYTES / (TILE_BYTES + INDEX_TILE_BYTES + 2048);
            if self.tiles.len() >= limit {
                self.tiles.remove(&self.order.pop_front().unwrap());
            }
            self.order.push_back(cache_key);
        }
        let pixels: Tile = Arc::new(
            source
                .iter()
                .flat_map(|&index| palette.premultiplied(index))
                .collect(),
        );
        self.tiles
            .insert(cache_key, (source.clone(), pixels.clone()));
        Some(pixels)
    }
}

pub(crate) fn nearest_color(colors: &[[u8; 4]], pixel: [u8; 4]) -> u8 {
    nearest_with_alpha(colors, pixel, None)
}

fn nearest_with_alpha(colors: &[[u8; 4]], pixel: [u8; 4], alpha: Option<u8>) -> u8 {
    colors
        .iter()
        .enumerate()
        .filter(|(_, color)| alpha.is_none_or(|alpha| color[3] == alpha))
        .min_by_key(|(index, color)| {
            let a = u32::from(color[3]);
            let mut candidate = **color;
            for value in &mut candidate[..3] {
                *value = ((u32::from(*value) * a + 127) / 255) as u8;
            }
            let distance: u32 = candidate
                .iter()
                .zip(pixel)
                .map(|(&a, b)| u32::from(a.abs_diff(b)).pow(2))
                .sum();
            (distance, *index)
        })
        .map_or(0, |(index, _)| index as u8)
}

pub fn from_png(source: crate::indexed_png::DecodedIndexedPng) -> Result<Document, String> {
    let mut colors = source.colors;
    let transparent = if let Some(index) = source.transparent {
        index
    } else {
        if colors.len() >= MAX_INDEXED_COLORS {
            return Err("PNG 调色板没有可用透明槽，请以 RGBA 打开".into());
        }
        let index = colors.len() as u8;
        colors.push([0; 4]);
        index
    };
    if colors.len() == 1 {
        colors.push([0, 0, 0, 255]);
    }
    let palette = IndexedPalette {
        order: (0..colors.len()).map(|i| i as u8).collect(),
        colors,
        transparent,
    };
    let mut document = create(source.width, source.height, palette)?;
    let mut tiles = BTreeMap::new();
    for ty in 0..source.height.div_ceil(TILE_SIZE) {
        for tx in 0..source.width.div_ceil(TILE_SIZE) {
            let mut tile = vec![transparent; INDEX_TILE_BYTES];
            for y in ty * TILE_SIZE..((ty + 1) * TILE_SIZE).min(source.height) {
                let count = TILE_SIZE.min(source.width - tx * TILE_SIZE) as usize;
                let start = y as usize * source.width as usize + (tx * TILE_SIZE) as usize;
                let destination = (y % TILE_SIZE * TILE_SIZE) as usize;
                tile[destination..destination + count]
                    .copy_from_slice(&source.indices[start..start + count]);
            }
            if tile.iter().any(|&index| index != transparent) {
                tiles.insert((tx, ty), Arc::new(tile));
            }
        }
    }
    document.active_mut().raster_mut()?.set_tiles(tiles);
    document.active_mut().name = "导入的图像".into();
    document.validate()?;
    Ok(document)
}

pub fn create(width: u32, height: u32, palette: IndexedPalette) -> Result<Document, String> {
    palette.validate()?;
    let mut document = Document::new(width, height)?;
    document.palette = Some(palette);
    document.active_mut().content = LayerContent::Raster(RasterPlane::Indexed(BTreeMap::new()));
    Ok(document)
}

pub fn convert(source: &Document, palette: Option<IndexedPalette>) -> Result<Document, String> {
    if palette.is_some() && source.layers.iter().any(Layer::is_vector) {
        return Err("请先显式栅格化矢量图层，再转换为索引色".into());
    }
    if let Some(palette) = &palette {
        palette.validate()?;
    }
    if source.palette == palette {
        return Ok(source.clone());
    }
    let bytes = if palette.is_some() {
        INDEX_TILE_BYTES
    } else {
        TILE_BYTES
    };
    let future = source
        .layers
        .iter()
        .map(|layer| {
            layer
                .raster_opt()
                .map_or(0, |raster| raster.tiles().len() * bytes)
                + layer.mask_bytes()
        })
        .sum::<usize>();
    if future > MAX_DOCUMENT_BYTES {
        return Err("转换后的工程超出像素内存限制".into());
    }
    let masks: HashSet<_> = source
        .layers
        .iter()
        .flat_map(|layer| layer.mask_buffers())
        .map(Arc::as_ptr)
        .collect();
    let retained: HashMap<_, _> = source
        .layers
        .iter()
        .flat_map(|layer| layer.raster_buffers())
        .filter(|tile| !masks.contains(&Arc::as_ptr(tile)))
        .map(|tile| (Arc::as_ptr(tile), tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("转换会超出撤销内存限制".into());
    }
    let mut document = source.clone();
    document.palette = palette;
    if let Some(metadata) = document.aseprite_metadata.as_mut() {
        let metadata = Arc::make_mut(metadata);
        if let Some(palette) = &document.palette {
            metadata.indexed_names = if let Some(previous) = &metadata.companion_palette {
                if previous.colors == palette.colors {
                    previous.names.clone()
                } else {
                    vec![None; palette.colors.len()]
                }
            } else {
                vec![None; palette.colors.len()]
            };
            if metadata
                .companion_palette
                .as_ref()
                .is_some_and(|previous| previous.colors == palette.colors)
            {
                metadata.companion_palette = None;
            }
        } else if let Some(palette) = &source.palette {
            metadata.companion_palette = Some(crate::aseprite::PaletteMetadata {
                colors: palette.colors.clone(),
                names: metadata.indexed_names.clone(),
            });
            metadata.indexed_names.clear();
        }
    }
    for layer in &mut document.layers {
        if layer.raster_opt().is_none() {
            continue;
        }
        let original = source
            .layers
            .iter()
            .find(|original| original.id == layer.id)
            .unwrap();
        let mut tiles = BTreeMap::new();
        for &key in original.raster()?.tiles().keys() {
            let pixels = original.rgba_tile(source.palette.as_ref(), key).unwrap();
            let converted = if let Some(palette) = &document.palette {
                let values: Vec<_> = pixels
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .map(|&pixel| palette.nearest(pixel))
                    .collect();
                if values.iter().all(|&index| index == palette.transparent) {
                    continue;
                }
                values
            } else {
                pixels.into_owned()
            };
            tiles.insert(key, Arc::new(converted));
        }
        layer.content = LayerContent::Raster(if document.palette.is_some() {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        });
    }
    crate::masks::check_transaction(source, &document)?;
    Ok(document)
}

pub fn remove_color(source: &Document, index: u8, replacement: u8) -> Result<Document, String> {
    let palette = source.palette.as_ref().ok_or("当前工程不是索引色模式")?;
    if palette.colors.len() <= 2
        || index == replacement
        || usize::from(index) >= palette.colors.len()
        || usize::from(replacement) >= palette.colors.len()
        || (index == palette.transparent && palette.colors[usize::from(replacement)][3] != 0)
    {
        return Err("调色板删除或替换参数无效".into());
    }
    let remap = |value: u8| {
        let value = if value == index { replacement } else { value };
        value - u8::from(value > index)
    };
    let mut changed = HashSet::new();
    let mut live = HashSet::new();
    for layer in &source.layers {
        for tile in layer.raster_buffers() {
            if tile.iter().any(|&value| remap(value) != value) {
                changed.insert(Arc::as_ptr(tile));
            } else {
                live.insert(Arc::as_ptr(tile));
            }
        }
        live.extend(layer.mask_buffers().map(Arc::as_ptr));
    }
    let retained: HashMap<_, _> = source
        .buffers()
        .filter(|tile| changed.contains(&Arc::as_ptr(tile)) && !live.contains(&Arc::as_ptr(tile)))
        .map(|tile| (Arc::as_ptr(tile), tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("删除颜色会超出撤销内存限制".into());
    }
    let mut document = source.clone();
    let palette = document.palette.as_mut().unwrap();
    palette.colors.remove(usize::from(index));
    palette.order.retain(|&value| value != index);
    for value in &mut palette.order {
        *value = remap(*value);
    }
    palette.transparent = remap(palette.transparent);
    if let Some(metadata) = document.aseprite_metadata.as_mut() {
        Arc::make_mut(metadata)
            .indexed_names
            .remove(usize::from(index));
    }
    let transparent = palette.transparent;
    let mut shared: HashMap<_, Tile> = HashMap::new();
    for layer in &mut document.layers {
        if layer.raster_opt().is_none() {
            continue;
        }
        for tile in layer.raster_mut()?.tiles_mut().values_mut() {
            let pointer = Arc::as_ptr(tile);
            if !changed.contains(&pointer) {
                continue;
            }
            *tile = shared
                .entry(pointer)
                .or_insert_with(|| Arc::new(tile.iter().map(|&value| remap(value)).collect()))
                .clone();
        }
        layer
            .raster_mut()?
            .tiles_mut()
            .retain(|_, tile| tile.iter().any(|&value| value != transparent));
    }
    crate::masks::check_transaction(source, &document)?;
    Ok(document)
}

pub fn read_index(layer: &Layer, palette: &IndexedPalette, x: u32, y: u32) -> u8 {
    layer
        .raster_opt()
        .and_then(|raster| raster.tiles().get(&(x / TILE_SIZE, y / TILE_SIZE)))
        .map_or(palette.transparent, |tile| {
            tile[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize]
        })
}

fn edit_index(
    palette: &IndexedPalette,
    old: u8,
    replacement: u8,
    coverage: u8,
    alpha_locked: bool,
) -> u8 {
    if coverage == 0 || old == replacement {
        return old;
    }
    if alpha_locked && palette.colors[usize::from(old)][3] == 0 {
        return old;
    }
    if coverage == 255
        && (!alpha_locked
            || palette.colors[usize::from(old)][3] == palette.colors[usize::from(replacement)][3])
    {
        return replacement;
    }
    let previous = palette.premultiplied(old);
    let target = palette.premultiplied(replacement);
    let mut pixel = std::array::from_fn(|i| mix(previous[i], target[i], coverage));
    if alpha_locked {
        pixel[3] = previous[3];
        let color = palette.colors[usize::from(replacement)];
        for i in 0..3 {
            pixel[i] = mix(
                previous[i],
                ((u32::from(color[i]) * u32::from(previous[3]) + 127) / 255) as u8,
                coverage,
            );
        }
        nearest_with_alpha(&palette.colors, pixel, Some(previous[3]))
    } else {
        palette.nearest(pixel)
    }
}

fn paint_index(
    palette: &IndexedPalette,
    old: u8,
    replacement: u8,
    coverage: u8,
    alpha_locked: bool,
    eraser: bool,
) -> u8 {
    if coverage == 0 {
        return old;
    }
    let previous = palette.premultiplied(old);
    let source = palette.premultiplied(replacement);
    let mut pixel = previous;
    if eraser {
        for value in &mut pixel {
            *value = ((u32::from(*value) * u32::from(255 - coverage) + 127) / 255) as u8;
        }
    } else {
        let alpha = (u32::from(source[3]) * u32::from(coverage) + 127) / 255;
        if alpha == 0 {
            return old;
        }
        if alpha_locked {
            crate::blending::paint_preserving_alpha(
                &mut pixel,
                palette.colors[usize::from(replacement)][..3]
                    .try_into()
                    .unwrap(),
                alpha,
            );
        } else {
            for i in 0..4 {
                pixel[i] = ((u32::from(source[i]) * u32::from(coverage)
                    + u32::from(previous[i]) * (255 - alpha)
                    + 127)
                    / 255)
                    .min(255) as u8;
            }
        }
        if coverage == 255 && pixel == source {
            return replacement;
        }
    }
    if pixel == previous {
        return old;
    }
    if alpha_locked {
        nearest_with_alpha(&palette.colors, pixel, Some(previous[3]))
    } else {
        palette.nearest(pixel)
    }
}

pub fn fill(
    document: &Document,
    selection: Option<&Selection>,
    settings: ColorSelection,
    replacement: u8,
    opacity: f32,
) -> Result<(BTreeMap<TileKey, Tile>, Vec<TileKey>), String> {
    crate::groups::check_editable(document, document.active, true)?;
    if !opacity.is_finite() || !(0.0..=1.0).contains(&opacity) {
        return Err("填充不透明度必须在 0 到 1 之间".into());
    }
    let palette = document.palette.as_ref().ok_or("当前工程不是索引色模式")?;
    if usize::from(replacement) >= palette.colors.len() {
        return Err("索引颜色不存在".into());
    }
    let layer = document
        .layers
        .iter()
        .find(|layer| layer.id == document.active)
        .unwrap();
    if layer.locked {
        return Err("图层已锁定，请先解锁".into());
    }
    if !layer.visible {
        return Err("请先显示当前图层".into());
    }
    if !document.bounds().contains(settings.x, settings.y) {
        return Err("填充位置超出画布".into());
    }
    if opacity == 0.0 || selection.is_some_and(Selection::is_empty) {
        return Ok((layer.raster()?.tiles().clone(), Vec::new()));
    }
    if selection.is_some_and(|selection| selection.coverage(settings.x, settings.y) == 0) {
        return Err("填充位置不在选区内".into());
    }
    if layer.alpha_locked && palette.colors[usize::from(replacement)][3] == 0 {
        return Ok((layer.raster()?.tiles().clone(), Vec::new()));
    }
    let mask =
        crate::color_selection::select_clipped(document, settings, selection, layer.alpha_locked)?;
    let bounds = mask.bounds();
    let mut changed = BTreeSet::new();
    let mut added = 0;
    for ty in bounds.top / TILE_SIZE..bounds.bottom.div_ceil(TILE_SIZE) {
        for tx in bounds.left / TILE_SIZE..bounds.right.div_ceil(TILE_SIZE) {
            let key = (tx, ty);
            let modifies = (bounds.top.max(ty * TILE_SIZE)
                ..bounds.bottom.min((ty + 1) * TILE_SIZE))
                .any(|y| {
                    (bounds.left.max(tx * TILE_SIZE)..bounds.right.min((tx + 1) * TILE_SIZE)).any(
                        |x| {
                            let old = read_index(layer, palette, x, y);
                            edit_index(
                                palette,
                                old,
                                replacement,
                                (f32::from(mask.coverage(x, y)) * opacity).round() as u8,
                                layer.alpha_locked,
                            ) != old
                        },
                    )
                });
            if modifies {
                changed.insert(key);
                added += usize::from(!layer.raster()?.tiles().contains_key(&key));
            }
        }
    }
    if document.pixel_bytes() + added * INDEX_TILE_BYTES > MAX_DOCUMENT_BYTES {
        return Err("工程像素超过内存限制".into());
    }
    let live: HashSet<_> = document
        .layers
        .iter()
        .flat_map(|other| {
            other
                .raster_opt()
                .into_iter()
                .flat_map(|raster| raster.tiles().iter())
                .filter(|(key, _)| other.id != layer.id || !changed.contains(key))
                .map(|(_, tile)| Arc::as_ptr(tile))
                .chain(other.mask_buffers().map(Arc::as_ptr))
        })
        .collect();
    let retained: HashMap<_, _> = changed
        .iter()
        .filter_map(|key| layer.raster_opt()?.tiles().get(key))
        .filter(|tile| !live.contains(&Arc::as_ptr(tile)))
        .map(|tile| (Arc::as_ptr(tile), tile.len()))
        .collect();
    if retained.values().sum::<usize>() > MAX_HISTORY_BYTES {
        return Err("填充会超出撤销内存限制".into());
    }
    let mut tiles = layer.raster()?.tiles().clone();
    for &key in &changed {
        let mut tile = tiles.get(&key).map_or_else(
            || vec![palette.transparent; INDEX_TILE_BYTES],
            |tile| tile.as_ref().clone(),
        );
        for y in bounds.top.max(key.1 * TILE_SIZE)..bounds.bottom.min((key.1 + 1) * TILE_SIZE) {
            for x in bounds.left.max(key.0 * TILE_SIZE)..bounds.right.min((key.0 + 1) * TILE_SIZE) {
                let offset = (y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize;
                tile[offset] = edit_index(
                    palette,
                    tile[offset],
                    replacement,
                    (f32::from(mask.coverage(x, y)) * opacity).round() as u8,
                    layer.alpha_locked,
                );
            }
        }
        if tile.iter().all(|&index| index == palette.transparent) {
            tiles.remove(&key);
        } else {
            tiles.insert(key, Arc::new(tile));
        }
    }
    Ok((tiles, changed.into_iter().collect()))
}

pub fn select_indices(
    document: &Document,
    settings: ColorSelection,
    selection: Option<&Selection>,
    preserve_alpha: bool,
) -> Result<Selection, String> {
    let palette = document.palette.as_ref().unwrap();
    let layer = document
        .layers
        .iter()
        .find(|layer| layer.id == document.active)
        .unwrap();
    let seed = read_index(layer, palette, settings.x, settings.y);
    let width = document.width as usize;
    let mut mask = vec![0; width * document.height as usize];
    for (offset, value) in mask.iter_mut().enumerate() {
        let (x, y) = ((offset % width) as u32, (offset / width) as u32);
        let index = read_index(layer, palette, x, y);
        *value = u8::from(
            index == seed
                && (!preserve_alpha || palette.colors[usize::from(index)][3] != 0)
                && selection.is_none_or(|selection| selection.coverage(x, y) != 0),
        );
    }
    if settings.contiguous {
        crate::color_selection::connected(
            &mut mask,
            width,
            settings.y as usize * width + settings.x as usize,
        )?;
    } else {
        for value in &mut mask {
            *value *= 255;
        }
    }
    if let Some(selection) = selection {
        for (offset, value) in mask.iter_mut().enumerate() {
            if *value != 0 {
                *value = selection.coverage((offset % width) as u32, (offset / width) as u32);
            }
        }
    }
    Ok(Selection::from_mask(document.bounds(), mask))
}

pub fn stamp(
    document: &mut Document,
    selection: Option<&Selection>,
    brush: Brush,
    point: Sample,
    dirty: &mut BTreeSet<TileKey>,
) -> Result<bool, String> {
    let palette = document.palette.as_ref().unwrap();
    let replacement = if brush.eraser {
        palette.transparent
    } else {
        brush.index.ok_or("请选择索引颜色")?
    };
    if usize::from(replacement) >= palette.colors.len() {
        return Err("索引颜色不存在".into());
    }
    let opacity = brush.opacity_at_pressure(point.pressure);
    if opacity == 0.0 {
        return Ok(false);
    }
    let (dabs, count) = crate::dab::Dab::symmetric(document.bounds(), selection, brush, point);
    let mut bytes = document.pixel_bytes();
    let palette = palette.clone();
    let layer = document.active_mut();
    let mut modified = false;
    for (index, dab) in dabs[..count].iter().enumerate() {
        for y in dab.bounds.top..dab.bounds.bottom {
            for x in dab.bounds.left..dab.bounds.right {
                if dabs[..index].iter().any(|part| part.bounds.contains(x, y)) {
                    continue;
                }
                if selection.is_some_and(|selection| selection.coverage(x, y) < 128) {
                    continue;
                }
                let coverage = dabs[..count]
                    .iter()
                    .filter(|part| part.bounds.contains(x, y))
                    .fold(0.0f32, |coverage, part| {
                        coverage.max(part.coverage::<false>(x, y))
                    });
                let amount = (coverage * opacity * 255.0).round() as u8;
                let old = read_index(layer, &palette, x, y);
                let new = paint_index(
                    &palette,
                    old,
                    replacement,
                    amount,
                    layer.alpha_locked,
                    brush.eraser,
                );
                if new == old {
                    continue;
                }
                let key = (x / TILE_SIZE, y / TILE_SIZE);
                if !layer.raster()?.tiles().contains_key(&key) {
                    if bytes + INDEX_TILE_BYTES > MAX_DOCUMENT_BYTES {
                        return Err("当前工程已达到像素内存上限".into());
                    }
                    bytes += INDEX_TILE_BYTES;
                }
                let tile = layer
                    .raster_mut()?
                    .tiles_mut()
                    .entry(key)
                    .or_insert_with(|| Arc::new(vec![palette.transparent; INDEX_TILE_BYTES]));
                Arc::make_mut(tile)[(y % TILE_SIZE * TILE_SIZE + x % TILE_SIZE) as usize] = new;
                dirty.insert(key);
                modified = true;
            }
        }
    }
    Ok(modified)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derived_rgba_cache_is_bounded_and_evicted_tiles_and_palette_edits_refresh_exactly() {
        let palette = IndexedPalette {
            colors: vec![[0; 4], [90, 170, 30, 128]],
            transparent: 0,
            order: vec![0, 1],
        };
        let source = Arc::new(vec![1; INDEX_TILE_BYTES]);
        let mut layer = Layer::new(1, String::new());
        layer.content = crate::model::LayerContent::Raster(RasterPlane::Indexed(
            (0..128).map(|x| ((x, 0), source.clone())).collect(),
        ));
        let mut cache = RgbaCache::default();
        for x in 0..128 {
            let pixels = cache.tile(&layer, &palette, (x, 0)).unwrap();
            assert_eq!(&pixels[..4], &[45, 85, 15, 128]);
            let bytes = cache
                .tiles
                .values()
                .map(|(indices, rgba)| indices.len() + rgba.len() + 2048)
                .sum::<usize>();
            assert!(bytes <= MAX_INDEXED_CACHE_BYTES);
        }
        assert!(!cache.tiles.contains_key(&(1, (0, 0))));
        assert_eq!(
            &cache.tile(&layer, &palette, (0, 0)).unwrap()[..4],
            &[45, 85, 15, 128]
        );
        let mut changed = palette;
        changed.colors[1] = [20, 40, 80, 255];
        assert_eq!(
            &cache.tile(&layer, &changed, (0, 0)).unwrap()[..4],
            &[20, 40, 80, 255]
        );
        assert_eq!(cache.tiles.len(), 1);
        assert!(Arc::ptr_eq(
            &source,
            &layer.raster().unwrap().tiles()[&(0, 0)]
        ));
    }
}
