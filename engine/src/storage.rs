use crate::{model::*, raster::FrameCompositor};
use bincode::Options;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use image::{ImageDecoder, ImageFormat};
use std::io::{Cursor, Read, Write};

pub const MAX_FILE_BYTES: usize = 256 * 1024 * 1024;
const MAGIC: &[u8] = b"PODOR\x0c";
const V11_MAGIC: &[u8] = b"PODOR\x0b";
const V10_MAGIC: &[u8] = b"PODOR\x0a";
const V9_MAGIC: &[u8] = b"PODOR\x09";
const V8_MAGIC: &[u8] = b"PODOR\x08";
const V7_MAGIC: &[u8] = b"PODOR\x07";
const V6_MAGIC: &[u8] = b"PODOR\x06";
const V5_MAGIC: &[u8] = b"PODOR\x05";
const V4_MAGIC: &[u8] = b"PODOR\x04";
const V3_MAGIC: &[u8] = b"PODOR\x03";
const V2_MAGIC: &[u8] = b"PODOR\x02";
const LEGACY_MAGIC: &[u8] = b"PODOR\x01";

mod v11;

#[derive(serde::Deserialize)]
struct V10Document {
    width: u32,
    height: u32,
    layers: Vec<V9Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
    assistants: std::sync::Arc<crate::assistants::AssistantSet>,
}

impl From<V10Document> for Document {
    fn from(old: V10Document) -> Self {
        let mut doc: Self = V9Document {
            width: old.width,
            height: old.height,
            layers: old.layers,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: old.next_mask_id,
            active_mask_id: old.active_mask_id,
        }
        .into();
        doc.assistants = old.assistants;
        doc
    }
}

#[derive(serde::Deserialize)]
enum V9Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: crate::AdjustmentEffect,
    },
    Vector(std::sync::Arc<crate::vector::VectorLayer>),
}

#[derive(serde::Deserialize)]
struct V9Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V9Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<MaskEntry>,
    clipping: bool,
}

#[derive(serde::Deserialize)]
struct V9Document {
    width: u32,
    height: u32,
    layers: Vec<V9Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
}

impl From<V9Document> for Document {
    fn from(old: V9Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: old.next_mask_id,
            active_mask_id: old.active_mask_id,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: match layer.content {
                        V9Content::Raster(raster) => LayerContent::Raster(raster),
                        V9Content::Group { isolation, closed } => {
                            LayerContent::Group { isolation, closed }
                        }
                        V9Content::Adjustment { settings } => LayerContent::Adjustment { settings },
                        V9Content::Vector(vector) => LayerContent::Vector(vector),
                    },
                    parent_id: layer.parent_id,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: layer.masks,
                    clipping: layer.clipping,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
enum V8Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: crate::AdjustmentEffect,
    },
}

#[derive(serde::Deserialize)]
struct V8Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V8Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<MaskEntry>,
    clipping: bool,
}

#[derive(serde::Deserialize)]
struct V8Document {
    width: u32,
    height: u32,
    layers: Vec<V8Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
}

impl From<V8Document> for Document {
    fn from(old: V8Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: old.next_mask_id,
            active_mask_id: old.active_mask_id,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: match layer.content {
                        V8Content::Raster(raster) => LayerContent::Raster(raster),
                        V8Content::Group { isolation, closed } => {
                            LayerContent::Group { isolation, closed }
                        }
                        V8Content::Adjustment { settings } => LayerContent::Adjustment { settings },
                    },
                    parent_id: layer.parent_id,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: layer.masks,
                    clipping: layer.clipping,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
enum V7Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: crate::AdjustmentEffect,
    },
}

#[derive(serde::Deserialize)]
struct V7Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V7Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Deserialize)]
struct V7Document {
    width: u32,
    height: u32,
    layers: Vec<V7Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

fn legacy_masks(mask: Option<LayerMask>) -> Vec<MaskEntry> {
    mask.into_iter()
        .map(|plane| MaskEntry {
            id: 0,
            name: "Mask".into(),
            plane,
        })
        .collect()
}

impl From<V7Document> for Document {
    fn from(old: V7Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: match layer.content {
                        V7Content::Raster(raster) => LayerContent::Raster(raster),
                        V7Content::Group { isolation, closed } => {
                            LayerContent::Group { isolation, closed }
                        }
                        V7Content::Adjustment { settings } => LayerContent::Adjustment { settings },
                    },
                    parent_id: layer.parent_id,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: legacy_masks(layer.mask),
                    clipping: layer.clipping,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
enum V6Content {
    Raster(RasterPlane),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
}

#[derive(serde::Deserialize)]
struct V6Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: V6Content,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Deserialize)]
struct V6Document {
    width: u32,
    height: u32,
    layers: Vec<V6Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

impl From<V6Document> for Document {
    fn from(old: V6Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: match layer.content {
                        V6Content::Raster(raster) => LayerContent::Raster(raster),
                        V6Content::Group { isolation, closed } => {
                            LayerContent::Group { isolation, closed }
                        }
                    },
                    parent_id: layer.parent_id,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: legacy_masks(layer.mask),
                    clipping: layer.clipping,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
struct V4Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    raster: RasterPlane,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
}

#[derive(serde::Deserialize)]
struct V5Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    raster: RasterPlane,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
    clipping: bool,
}

#[derive(serde::Deserialize)]
struct V5Document {
    width: u32,
    height: u32,
    layers: Vec<V5Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

impl From<V5Document> for Document {
    fn from(old: V5Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: LayerContent::Raster(layer.raster),
                    parent_id: None,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: legacy_masks(layer.mask),
                    clipping: layer.clipping,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
struct V4Document {
    width: u32,
    height: u32,
    layers: Vec<V4Layer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
}

impl From<V4Document> for Document {
    fn from(old: V4Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: old.palette,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: LayerContent::Raster(layer.raster),
                    parent_id: None,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: legacy_masks(layer.mask),
                    clipping: false,
                })
                .collect(),
        }
    }
}

#[derive(serde::Deserialize)]
struct LegacyLayer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    tiles: std::collections::BTreeMap<TileKey, Tile>,
    blend: BlendMode,
}

#[derive(serde::Deserialize)]
struct LegacyDocument {
    width: u32,
    height: u32,
    layers: Vec<LegacyLayer>,
    active: u32,
    next_id: u32,
}

#[derive(serde::Deserialize)]
struct V2Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    tiles: std::collections::BTreeMap<TileKey, Tile>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
}

#[derive(serde::Deserialize)]
struct V2Document {
    width: u32,
    height: u32,
    layers: Vec<V2Layer>,
    active: u32,
    next_id: u32,
}

#[derive(serde::Deserialize)]
struct V3Layer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    tiles: std::collections::BTreeMap<TileKey, Tile>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    mask: Option<LayerMask>,
}

#[derive(serde::Deserialize)]
struct V3Document {
    width: u32,
    height: u32,
    layers: Vec<V3Layer>,
    active: u32,
    next_id: u32,
}

impl From<V3Document> for Document {
    fn from(old: V3Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: None,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: LayerContent::Raster(RasterPlane::Rgba(layer.tiles)),
                    parent_id: None,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: legacy_masks(layer.mask),
                    clipping: false,
                })
                .collect(),
        }
    }
}

impl From<V2Document> for Document {
    fn from(old: V2Document) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: None,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: LayerContent::Raster(RasterPlane::Rgba(layer.tiles)),
                    parent_id: None,
                    blend: layer.blend,
                    alpha_locked: layer.alpha_locked,
                    locked: layer.locked,
                    masks: Vec::new(),
                    clipping: false,
                })
                .collect(),
        }
    }
}

impl From<LegacyDocument> for Document {
    fn from(old: LegacyDocument) -> Self {
        Self {
            width: old.width,
            height: old.height,
            active: old.active,
            next_id: old.next_id,
            palette: None,
            next_mask_id: 1,
            active_mask_id: None,
            assistants: Default::default(),
            animation: None,
            aseprite_metadata: None,
            layers: old
                .layers
                .into_iter()
                .map(|layer| Layer {
                    id: layer.id,
                    name: layer.name,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    content: LayerContent::Raster(RasterPlane::Rgba(layer.tiles)),
                    parent_id: None,
                    blend: layer.blend,
                    alpha_locked: false,
                    locked: false,
                    masks: Vec::new(),
                    clipping: false,
                })
                .collect(),
        }
    }
}

pub fn save(doc: &Document) -> Result<Vec<u8>, String> {
    doc.validate()?;
    let bytes = v11::encode(doc)?;
    let mut encoder = GzEncoder::new(MAGIC.to_vec(), Compression::fast());
    encoder.write_all(&bytes).map_err(|e| e.to_string())?;
    encoder.finish().map_err(|e| e.to_string())
}

pub fn load(bytes: &[u8]) -> Result<Document, String> {
    if bytes.len() > MAX_FILE_BYTES {
        return Err("文件过大".into());
    }
    if bytes.len() >= 128 && bytes[4..6] == [0xe0, 0xa5] {
        return crate::aseprite::import(bytes);
    }
    if bytes.starts_with(b"PK\x03\x04") {
        return crate::openraster::load(bytes);
    }
    if bytes.starts_with(b"8BPS") {
        return crate::psd::load(bytes);
    }
    match image::guess_format(bytes) {
        Ok(ImageFormat::Png) => return load_png(bytes),
        Ok(ImageFormat::Jpeg) => {
            let decoder = image::codecs::jpeg::JpegDecoder::new(Cursor::new(bytes))
                .map_err(|_| "图片数据损坏或格式不受支持")?;
            return load_image(decoder);
        }
        Ok(ImageFormat::WebP) => {
            let decoder = image::codecs::webp::WebPDecoder::new(Cursor::new(bytes))
                .map_err(|_| "图片数据损坏或格式不受支持")?;
            if decoder.has_animation() {
                return Err("暂不支持动态 WebP，请先导出为静态图片".into());
            }
            return load_image(decoder);
        }
        _ => {}
    }
    if !bytes.starts_with(MAGIC)
        && !bytes.starts_with(V11_MAGIC)
        && !bytes.starts_with(V10_MAGIC)
        && !bytes.starts_with(V9_MAGIC)
        && !bytes.starts_with(V8_MAGIC)
        && !bytes.starts_with(V7_MAGIC)
        && !bytes.starts_with(V6_MAGIC)
        && !bytes.starts_with(V5_MAGIC)
        && !bytes.starts_with(V4_MAGIC)
        && !bytes.starts_with(V3_MAGIC)
        && !bytes.starts_with(V2_MAGIC)
        && !bytes.starts_with(LEGACY_MAGIC)
    {
        if bytes.starts_with(b"PODOR") {
            return Err("工程版本不受支持，请更新 podor".into());
        }
        return Err("请选择 podor、Aseprite、PSD、ORA 工程或 PNG、JPEG、WebP 图片".into());
    }
    let mut decoded = Vec::new();
    GzDecoder::new(&bytes[MAGIC.len()..])
        .take(MAX_FILE_BYTES as u64 + 1)
        .read_to_end(&mut decoded)
        .map_err(|_| "工程文件已损坏")?;
    if decoded.len() > MAX_FILE_BYTES {
        return Err("工程文件超过内存限制".into());
    }
    let options = bincode::DefaultOptions::new()
        .with_limit(MAX_FILE_BYTES as u64)
        .reject_trailing_bytes();
    let mut doc: Document = if bytes.starts_with(LEGACY_MAGIC) {
        options
            .deserialize::<LegacyDocument>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V2_MAGIC) {
        options
            .deserialize::<V2Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V3_MAGIC) {
        options
            .deserialize::<V3Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V4_MAGIC) {
        options
            .deserialize::<V4Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V6_MAGIC) {
        options
            .deserialize::<V6Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V5_MAGIC) {
        options
            .deserialize::<V5Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V7_MAGIC) {
        options
            .deserialize::<V7Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V8_MAGIC) {
        options
            .deserialize::<V8Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V9_MAGIC) {
        options
            .deserialize::<V9Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V10_MAGIC) {
        options
            .deserialize::<V10Document>(&decoded)
            .map(Document::from)
            .map_err(|_| "工程文件已损坏")?
    } else if bytes.starts_with(V11_MAGIC) {
        v11::decode(&decoded)?
    } else {
        v11::decode_with_metadata(&decoded)?
    };
    if !bytes.starts_with(MAGIC)
        && !bytes.starts_with(V11_MAGIC)
        && !bytes.starts_with(V10_MAGIC)
        && !bytes.starts_with(V9_MAGIC)
        && !bytes.starts_with(V8_MAGIC)
    {
        doc.assign_mask_ids()?;
    }
    doc.validate()?;
    Ok(doc)
}

fn load_png(bytes: &[u8]) -> Result<Document, String> {
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(|_| "无法读取 PNG 图片")?;
    if reader.info().color_type == png::ColorType::Indexed {
        let decoded = crate::indexed_png::decode(bytes)?;
        if decoded.transparent.is_some() || decoded.colors.len() < MAX_INDEXED_COLORS {
            return crate::indexed::from_png(decoded);
        }
    }
    let doc = Document::new(reader.info().width, reader.info().height)?;
    let mut decoded = vec![0; reader.output_buffer_size()];
    let info = reader
        .next_frame(&mut decoded)
        .map_err(|_| "PNG 图片数据损坏")?;
    let channels = info.color_type.samples();
    let mut rgba = vec![0; doc.width as usize * doc.height as usize * 4];
    for (source, target) in decoded[..info.buffer_size()]
        .chunks_exact(channels)
        .zip(rgba.as_chunks_mut::<4>().0.iter_mut())
    {
        let (rgb, alpha) = match info.color_type {
            png::ColorType::Rgb => ([source[0], source[1], source[2]], 255),
            png::ColorType::Rgba => ([source[0], source[1], source[2]], source[3]),
            png::ColorType::Grayscale => ([source[0]; 3], 255),
            png::ColorType::GrayscaleAlpha => ([source[0]; 3], source[1]),
            _ => return Err("暂不支持这种 PNG 颜色格式".into()),
        };
        for c in 0..3 {
            target[c] = ((u32::from(rgb[c]) * u32::from(alpha) + 127) / 255) as u8;
        }
        target[3] = alpha;
    }
    write_import(doc, rgba)
}

fn load_image(mut decoder: impl ImageDecoder) -> Result<Document, String> {
    let (width, height) = decoder.dimensions();
    let mut doc = Document::new(width, height)?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(MAX_DIMENSION);
    limits.max_image_height = Some(MAX_DIMENSION);
    limits.max_alloc = Some(MAX_DOCUMENT_BYTES as u64);
    decoder
        .set_limits(limits)
        .map_err(|_| "图片解码超过内存限制")?;
    let orientation = decoder.orientation().map_err(|_| "无法读取图片方向")?;
    let mut image =
        image::DynamicImage::from_decoder(decoder).map_err(|_| "图片数据损坏或格式不受支持")?;
    image.apply_orientation(orientation);
    doc.width = image.width();
    doc.height = image.height();
    let mut rgba = image.into_rgba8().into_raw();
    for pixel in rgba.as_chunks_mut::<4>().0 {
        let alpha = u32::from(pixel[3]);
        for value in &mut pixel[..3] {
            *value = ((u32::from(*value) * alpha + 127) / 255) as u8;
        }
    }
    write_import(doc, rgba)
}

fn write_import(mut doc: Document, rgba: Vec<u8>) -> Result<Document, String> {
    let region = doc.bounds();
    crate::adjustments::Surface {
        region,
        pixels: rgba,
    }
    .write(doc.active_mut(), region, None)?;
    doc.active_mut().name = "导入的图像".into();
    doc.validate()?;
    Ok(doc)
}

pub fn export_png(doc: &Document) -> Result<Vec<u8>, String> {
    export_image(doc, ExportOptions::default())
}

#[derive(Clone, Copy, Default, Debug, serde::Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ExportFormat {
    #[default]
    Png,
    Jpeg,
    Webp,
    Ora,
    Tiff,
    Bmp,
    Psd,
    #[serde(rename = "indexed_png")]
    IndexedPng,
}

#[derive(Clone, Copy, Default, Debug, serde::Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum IndexedExportPolicy {
    #[default]
    Exact,
    Quantize,
}

#[derive(Clone, Copy, serde::Deserialize)]
#[serde(default)]
pub struct ExportOptions {
    pub format: ExportFormat,
    pub transparent: bool,
    pub quality: u8,
    pub indexed_policy: IndexedExportPolicy,
    pub bake_layers: bool,
    pub frame_id: Option<u32>,
}

impl Default for ExportOptions {
    fn default() -> Self {
        Self {
            format: ExportFormat::Png,
            transparent: false,
            quality: DEFAULT_EXPORT_QUALITY,
            indexed_policy: IndexedExportPolicy::Exact,
            bake_layers: false,
            frame_id: None,
        }
    }
}

pub fn export_image(doc: &Document, options: ExportOptions) -> Result<Vec<u8>, String> {
    if doc.animation.is_some() {
        let frame = options.frame_id.ok_or("动画导出必须明确选择帧")?;
        let view = crate::animation::view(doc, frame)?;
        return export_image(&view, options);
    }
    if options.quality == 0 || options.quality > 100 {
        return Err("导出质量必须在 1 到 100 之间".into());
    }
    if options.transparent && options.format == ExportFormat::Jpeg {
        return Err("JPEG 不支持透明背景".into());
    }
    if options.bake_layers {
        if !matches!(options.format, ExportFormat::Psd | ExportFormat::Ora) {
            return Err("烘焙副本仅用于 PSD 或 ORA 导出".into());
        }
        let mut copy = Document::new(doc.width, doc.height)?;
        copy.layers[0].name = "Composite".into();
        let mut compositor = FrameCompositor::new(doc, true);
        for ty in 0..doc.height.div_ceil(TILE_SIZE) {
            for tx in 0..doc.width.div_ceil(TILE_SIZE) {
                let pixels = compositor.tile(doc, (tx, ty));
                if pixels.as_chunks::<4>().0.iter().any(|pixel| pixel[3] != 0) {
                    copy.layers[0]
                        .raster_mut()?
                        .tiles_mut()
                        .insert((tx, ty), std::sync::Arc::new(pixels));
                }
            }
        }
        copy.validate()?;
        return match options.format {
            ExportFormat::Psd => crate::psd::export(&copy),
            ExportFormat::Ora => crate::openraster::export(&copy),
            _ => unreachable!(),
        };
    }
    if options.format == ExportFormat::Ora {
        return crate::openraster::export(doc);
    }
    if options.format == ExportFormat::Psd {
        return crate::psd::export(doc);
    }
    if options.format == ExportFormat::IndexedPng {
        return crate::indexed_png::export(doc, options.transparent, options.indexed_policy);
    }
    let mut rgba = vec![255; doc.width as usize * doc.height as usize * 4];
    let mut compositor = FrameCompositor::new(doc, options.transparent);
    for ty in 0..doc.height.div_ceil(TILE_SIZE) {
        for tx in 0..doc.width.div_ceil(TILE_SIZE) {
            let tile = compositor.tile(doc, (tx, ty));
            let width = TILE_SIZE.min(doc.width - tx * TILE_SIZE) as usize;
            let height = TILE_SIZE.min(doc.height - ty * TILE_SIZE) as usize;
            for y in 0..height {
                let start = ((ty as usize * TILE_SIZE as usize + y) * doc.width as usize
                    + tx as usize * TILE_SIZE as usize)
                    * 4;
                let source = y * TILE_SIZE as usize * 4;
                rgba[start..start + width * 4].copy_from_slice(&tile[source..source + width * 4]);
            }
        }
    }
    if options.transparent {
        unpremultiply(&mut rgba);
    }
    let mut output = Vec::new();
    match options.format {
        ExportFormat::Jpeg => {
            let image =
                image::RgbaImage::from_raw(doc.width, doc.height, rgba).ok_or("图像尺寸无效")?;
            image::codecs::jpeg::JpegEncoder::new_with_quality(&mut output, options.quality)
                .encode_image(&image)
                .map_err(|error| error.to_string())?;
        }
        ExportFormat::Webp => image::codecs::webp::WebPEncoder::new_lossless(&mut output)
            .encode(
                &rgba,
                doc.width,
                doc.height,
                image::ExtendedColorType::Rgba8,
            )
            .map_err(|error| error.to_string())?,
        ExportFormat::Png => {
            let mut encoder = png::Encoder::new(&mut output, doc.width, doc.height);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().map_err(|e| e.to_string())?;
            writer.write_image_data(&rgba).map_err(|e| e.to_string())?;
        }
        ExportFormat::Tiff => {
            let mut encoder = tiff::encoder::TiffEncoder::new(Cursor::new(&mut output))
                .map_err(|error| error.to_string())?
                .with_compression(tiff::encoder::Compression::Deflate(
                    tiff::encoder::DeflateLevel::Fast,
                ));
            let mut image = encoder
                .new_image::<tiff::encoder::colortype::RGB8>(doc.width, doc.height)
                .map_err(|error| error.to_string())?;
            image
                .extra_samples(&[tiff::tags::ExtraSamples::UnassociatedAlpha])
                .map_err(|error| error.to_string())?;
            image.write_data(&rgba).map_err(|error| error.to_string())?;
        }
        ExportFormat::Bmp => image::codecs::bmp::BmpEncoder::new(&mut output)
            .encode(
                &rgba,
                doc.width,
                doc.height,
                image::ExtendedColorType::Rgba8,
            )
            .map_err(|error| error.to_string())?,
        ExportFormat::Ora | ExportFormat::Psd | ExportFormat::IndexedPng => unreachable!(),
    }
    Ok(output)
}

pub(crate) fn unpremultiply(rgba: &mut [u8]) {
    for pixel in rgba.as_chunks_mut::<4>().0 {
        let alpha = u32::from(pixel[3]);
        for value in &mut pixel[..3] {
            *value = (u32::from(*value) * 255 + alpha / 2)
                .checked_div(alpha)
                .unwrap_or(0)
                .min(255) as u8;
        }
    }
}
