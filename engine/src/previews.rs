use crate::{model::*, raster::composite_tile_background};
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
    let mut layers = Vec::with_capacity(doc.layers.len());
    for layer in &doc.layers {
        let pixels = preview_pixels(
            doc,
            PREVIEW_EDGE,
            layer
                .tiles
                .iter()
                .map(|(&key, pixels)| (key, pixels.as_slice())),
        );
        layers.push((layer.id, pixels));
    }
    let visible: Vec<_> = doc
        .layers
        .iter()
        .enumerate()
        .filter(|(_, layer)| layer.visible && layer.opacity > 0.0)
        .collect();
    let composite = if visible.len() == 1 && visible[0].1.opacity == 1.0 {
        layers[visible[0].0].1.clone()
    } else {
        let keys: BTreeSet<_> = visible
            .iter()
            .flat_map(|(_, layer)| layer.tiles.keys().copied())
            .collect();
        preview_pixels(
            doc,
            PREVIEW_EDGE,
            keys.into_iter()
                .map(|key| (key, composite_tile_background(doc, key, true))),
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
        .flat_map(|layer| layer.tiles.keys().copied())
        .collect();
    let pixels = preview_pixels(
        doc,
        EXPORT_THUMBNAIL_EDGE,
        keys.into_iter()
            .map(|key| (key, composite_tile_background(doc, key, true))),
    );
    let mut output = EXPORT_THUMBNAIL_EDGE.to_le_bytes().to_vec();
    output.extend(pixels);
    output
}
