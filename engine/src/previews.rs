use crate::{model::*, raster::FrameCompositor};
use std::collections::BTreeSet;

fn preview_pixels<T: AsRef<[u8]>>(
    doc: &Document,
    edge: u32,
    tiles: impl IntoIterator<Item = (TileKey, T)>,
) -> Vec<u8> {
    let longest = doc.width.max(doc.height);
    let width = (doc.width * edge / longest).max(1);
    let height = (doc.height * edge / longest).max(1);
    let work_width = width.min(doc.width);
    let work_height = height.min(doc.height);
    let mut sums = vec![[0u64; 4]; (work_width * work_height) as usize];
    for ((tx, ty), tile) in tiles {
        let tile = tile.as_ref();
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        for y in 0..TILE_SIZE.min(doc.height - top) {
            let row = (top + y) * work_height / doc.height * work_width;
            for x in 0..TILE_SIZE.min(doc.width - left) {
                let start = ((y * TILE_SIZE + x) * 4) as usize;
                if tile[start + 3] == 0 {
                    continue;
                }
                let index = (row + (left + x) * work_width / doc.width) as usize;
                for channel in 0..4 {
                    sums[index][channel] += u64::from(tile[start + channel]);
                }
            }
        }
    }
    let mut pixels = vec![0; (work_width * work_height * 4) as usize];
    for y in 0..work_height {
        let rows =
            ((y + 1) * doc.height).div_ceil(work_height) - (y * doc.height).div_ceil(work_height);
        for x in 0..work_width {
            let columns =
                ((x + 1) * doc.width).div_ceil(work_width) - (x * doc.width).div_ceil(work_width);
            let count = u64::from(rows * columns);
            let index = (y * work_width + x) as usize;
            for channel in 0..4 {
                pixels[index * 4 + channel] =
                    ((sums[index][channel] + count / 2) / count).min(255) as u8;
            }
        }
    }
    let image = image::RgbaImage::from_raw(work_width, work_height, pixels).unwrap();
    let resized = if work_width == width && work_height == height {
        image
    } else {
        image::imageops::resize(&image, width, height, image::imageops::FilterType::Triangle)
    };
    let mut result = vec![0; (edge * edge * 4) as usize];
    let left = (edge - width) / 2;
    let top = (edge - height) / 2;
    for y in 0..height {
        let destination = (((top + y) * edge + left) * 4) as usize;
        let source = (y * width * 4) as usize;
        result[destination..destination + (width * 4) as usize]
            .copy_from_slice(&resized.as_raw()[source..source + (width * 4) as usize]);
    }
    result
}

pub fn render(doc: &Document, revision: u64) -> Vec<u8> {
    let plan = crate::raster::RenderPlan::new(doc);
    let mut layers = Vec::with_capacity(doc.layers.len());
    for (index, layer) in doc.layers.iter().enumerate() {
        let keys = crate::adjustment_layers::affected_keys(doc, index).expect("无效的图层依赖");
        let pixels = if layer.is_adjustment() {
            let scope = crate::raster::adjustment_preview_document(doc, &plan, index);
            let mut compositor = FrameCompositor::new(&scope, true);
            preview_pixels(
                doc,
                PREVIEW_EDGE,
                keys.into_iter()
                    .map(|key| (key, compositor.tile(&scope, key))),
            )
        } else {
            preview_pixels(
                doc,
                PREVIEW_EDGE,
                keys.into_iter().map(|key| {
                    (
                        key,
                        crate::raster::preview_node_tile(doc, &plan, index, key),
                    )
                }),
            )
        };
        layers.push((layer.id, pixels));
    }
    let visible: Vec<_> = doc
        .layers
        .iter()
        .enumerate()
        .filter(|(_, layer)| layer.visible && layer.opacity > 0.0)
        .collect();
    let composite = if !doc
        .layers
        .iter()
        .any(|layer| layer.is_group() || layer.is_adjustment())
        && visible.len() == 1
        && visible[0].1.opacity == 1.0
        && !visible[0].1.clipping
    {
        layers[visible[0].0].1.clone()
    } else {
        let keys: BTreeSet<_> = visible
            .iter()
            .flat_map(|(_, layer)| layer.content_keys(doc.bounds()))
            .collect();
        let mut compositor = FrameCompositor::new(doc, true);
        preview_pixels(
            doc,
            PREVIEW_EDGE,
            keys.into_iter().map(|key| (key, compositor.tile(doc, key))),
        )
    };
    let mut output = Vec::with_capacity(16 + (4 + composite.len()) * (layers.len() + 1));
    output.extend_from_slice(&revision.to_le_bytes());
    output.extend_from_slice(&PREVIEW_EDGE.to_le_bytes());
    output.extend_from_slice(&(layers.len() as u32 + 1).to_le_bytes());
    for (id, pixels) in std::iter::once((0u32, composite)).chain(layers) {
        output.extend_from_slice(&id.to_le_bytes());
        output.extend_from_slice(&pixels);
    }
    output
}

pub fn thumbnail(doc: &Document) -> Vec<u8> {
    let keys: BTreeSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .flat_map(|layer| layer.content_keys(doc.bounds()))
        .collect();
    let mut compositor = FrameCompositor::new(doc, true);
    let pixels = preview_pixels(
        doc,
        EXPORT_THUMBNAIL_EDGE,
        keys.into_iter().map(|key| (key, compositor.tile(doc, key))),
    );
    let mut output = EXPORT_THUMBNAIL_EDGE.to_le_bytes().to_vec();
    output.extend(pixels);
    output
}

pub(crate) fn frame_thumbnail(
    doc: &Document,
    edge: u32,
    cache: std::sync::Arc<std::sync::Mutex<crate::vector::RenderCache>>,
) -> Vec<u8> {
    let keys: BTreeSet<_> = doc
        .layers
        .iter()
        .filter(|layer| layer.visible && layer.opacity > 0.0)
        .flat_map(|layer| layer.content_keys(doc.bounds()))
        .collect();
    let mut compositor = FrameCompositor::with_vector_cache(doc, true, cache);
    preview_pixels(
        doc,
        edge,
        keys.into_iter().map(|key| (key, compositor.tile(doc, key))),
    )
}

pub fn render_masks(doc: &Document, revision: u64) -> Vec<u8> {
    let layers: Vec<_> = doc
        .layers
        .iter()
        .filter_map(|layer| {
            (if layer.id == doc.active {
                doc.selected_mask().map(|entry| &entry.plane)
            } else {
                layer.first_mask()
            })
            .map(|mask| (layer.id, mask))
        })
        .collect();
    mask_packet(doc, revision, layers)
}

pub fn render_mask_stack(doc: &Document, revision: u64) -> Vec<u8> {
    let masks = doc
        .layers
        .iter()
        .find(|layer| layer.id == doc.active)
        .unwrap()
        .masks
        .iter()
        .map(|entry| (entry.id, &entry.plane))
        .collect();
    mask_packet(doc, revision, masks)
}

fn mask_packet(doc: &Document, revision: u64, layers: Vec<(u32, &LayerMask)>) -> Vec<u8> {
    let mut output = revision.to_le_bytes().to_vec();
    output.extend(PREVIEW_EDGE.to_le_bytes());
    output.extend((layers.len() as u32).to_le_bytes());
    for (id, mask) in layers {
        let pixels = mask_pixels(doc, mask);
        output.extend(id.to_le_bytes());
        output.extend(pixels);
    }
    output
}

fn mask_pixels(doc: &Document, mask: &LayerMask) -> Vec<u8> {
    let edge = PREVIEW_EDGE;
    let width = (doc.width * edge / doc.width.max(doc.height)).max(1);
    let height = (doc.height * edge / doc.width.max(doc.height)).max(1);
    let work_width = width.min(doc.width);
    let work_height = height.min(doc.height);
    let mut sums = vec![0i64; (work_width * work_height) as usize];
    for (&(tx, ty), tile) in &mask.tiles {
        for y in 0..TILE_SIZE.min(mask.bounds.height() - ty * TILE_SIZE) {
            let ydoc = mask.bounds.top + (ty * TILE_SIZE + y) as i32;
            if ydoc < 0 || ydoc >= doc.height as i32 {
                continue;
            }
            let row = ydoc as u32 * work_height / doc.height * work_width;
            for x in 0..TILE_SIZE.min(mask.bounds.width() - tx * TILE_SIZE) {
                let xdoc = mask.bounds.left + (tx * TILE_SIZE + x) as i32;
                if xdoc < 0 || xdoc >= doc.width as i32 {
                    continue;
                }
                let index = (row + xdoc as u32 * work_width / doc.width) as usize;
                sums[index] +=
                    i64::from(tile[(y * TILE_SIZE + x) as usize]) - i64::from(mask.default);
            }
        }
    }
    let mut pixels = vec![0; (work_width * work_height * 4) as usize];
    for y in 0..work_height {
        let rows =
            ((y + 1) * doc.height).div_ceil(work_height) - (y * doc.height).div_ceil(work_height);
        for x in 0..work_width {
            let columns =
                ((x + 1) * doc.width).div_ceil(work_width) - (x * doc.width).div_ceil(work_width);
            let count = i64::from(rows * columns);
            let index = (y * work_width + x) as usize;
            let value = ((sums[index] + i64::from(mask.default) * count + count / 2) / count)
                .clamp(0, 255) as u8;
            pixels[index * 4..index * 4 + 4].copy_from_slice(&[value, value, value, 255]);
        }
    }
    let source = image::RgbaImage::from_raw(work_width, work_height, pixels).unwrap();
    let scaled = if (work_width, work_height) == (width, height) {
        source
    } else {
        image::imageops::resize(
            &source,
            width,
            height,
            image::imageops::FilterType::Triangle,
        )
    };
    let mut output = vec![0; (edge * edge * 4) as usize];
    let left = (edge - width) / 2;
    let top = (edge - height) / 2;
    for y in 0..height {
        let start = (((top + y) * edge + left) * 4) as usize;
        let src = (y * width * 4) as usize;
        output[start..start + (width * 4) as usize]
            .copy_from_slice(&scaled.as_raw()[src..src + (width * 4) as usize]);
    }
    output
}
