use super::MAX_FILE_BYTES;
use crate::{
    animation::{AnimationSet, Cel, CelKind, CelSource, Frame, FrameTag},
    aseprite::ProjectMetadata,
    assistants::AssistantSet,
    model::*,
    vector::{VectorLayer, VectorObject},
    AdjustmentEffect,
};
use bincode::Options;
use serde::{de::DeserializeOwned, de::Visitor, Deserialize, Deserializer, Serialize};
use std::{
    collections::{BTreeMap, HashMap},
    fmt,
    marker::PhantomData,
    sync::Arc,
};

#[derive(Serialize, Deserialize)]
struct WireDocument<B, O, M> {
    width: u32,
    height: u32,
    layers: Vec<WireLayer>,
    active: u32,
    next_id: u32,
    palette: Option<IndexedPalette>,
    next_mask_id: u32,
    active_mask_id: Option<u32>,
    assistants: Arc<AssistantSet>,
    animation: Option<WireAnimation>,
    buffers: Vec<B>,
    rasters: Vec<WireRaster>,
    objects: Vec<O>,
    vectors: Vec<WireVector>,
    aseprite_metadata: M,
}

#[derive(Serialize, Deserialize)]
struct WireLayer {
    id: u32,
    name: String,
    visible: bool,
    opacity: f32,
    content: WireContent,
    parent_id: Option<u32>,
    blend: BlendMode,
    alpha_locked: bool,
    locked: bool,
    masks: Vec<WireMaskEntry>,
    clipping: bool,
}

#[derive(Serialize, Deserialize)]
enum WireContent {
    Raster(u32),
    Group {
        isolation: GroupIsolation,
        closed: bool,
    },
    Adjustment {
        settings: AdjustmentEffect,
    },
    Vector(u32),
    CelTrack {
        kind: CelKind,
    },
}

#[derive(Serialize, Deserialize)]
struct WireRaster {
    indexed: bool,
    #[serde(deserialize_with = "unique_map")]
    tiles: BTreeMap<TileKey, u32>,
}

#[derive(Serialize, Deserialize)]
struct WireVector {
    next_object_id: u32,
    objects: Vec<u32>,
}

#[derive(Serialize, Deserialize)]
struct WireMaskEntry {
    id: u32,
    name: String,
    plane: WireMask,
}

#[derive(Serialize, Deserialize)]
struct WireMask {
    bounds: MaskBounds,
    default: u8,
    enabled: bool,
    linked: bool,
    #[serde(deserialize_with = "unique_map")]
    tiles: BTreeMap<TileKey, u32>,
}

#[derive(Serialize, Deserialize)]
struct WireAnimation {
    frames: Vec<WireFrame>,
    #[serde(deserialize_with = "unique_map")]
    cels: BTreeMap<u32, WireCel>,
    active_frame: u32,
    next_frame_id: u32,
    next_cel_id: u32,
    next_tag_id: u32,
    tags: Vec<FrameTag>,
}

#[derive(Serialize, Deserialize)]
struct WireFrame {
    id: u32,
    duration_ms: u32,
    #[serde(deserialize_with = "unique_map")]
    exposures: BTreeMap<u32, u32>,
}

#[derive(Serialize, Deserialize)]
struct WireCel {
    id: u32,
    layer_id: u32,
    source: WireSource,
    masks: Vec<WireMaskEntry>,
}

#[derive(Serialize, Deserialize)]
enum WireSource {
    Raster(u32),
    Vector(u32),
}

fn unique_map<'de, D, K, V>(deserializer: D) -> Result<BTreeMap<K, V>, D::Error>
where
    D: Deserializer<'de>,
    K: Ord + Deserialize<'de>,
    V: Deserialize<'de>,
{
    struct UniqueMap<K, V>(PhantomData<(K, V)>);
    impl<'de, K: Ord + Deserialize<'de>, V: Deserialize<'de>> Visitor<'de> for UniqueMap<K, V> {
        type Value = BTreeMap<K, V>;

        fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("唯一的工程引用")
        }

        fn visit_map<A: serde::de::MapAccess<'de>>(
            self,
            mut access: A,
        ) -> Result<Self::Value, A::Error> {
            let mut result = BTreeMap::new();
            while let Some((key, value)) = access.next_entry()? {
                if result.insert(key, value).is_some() {
                    return Err(serde::de::Error::custom("工程包含重复引用"));
                }
            }
            Ok(result)
        }
    }
    deserializer.deserialize_map(UniqueMap(PhantomData))
}

#[derive(Default)]
struct Tables<'a> {
    buffers: Vec<&'a [u8]>,
    buffer_refs: HashMap<usize, u32>,
    rasters: Vec<WireRaster>,
    raster_refs: HashMap<usize, u32>,
    objects: Vec<&'a VectorObject>,
    object_refs: HashMap<usize, u32>,
    vectors: Vec<WireVector>,
    vector_refs: HashMap<usize, u32>,
}

fn table_id(length: usize) -> Result<u32, String> {
    u32::try_from(length).map_err(|_| "工程共享数据表过大".into())
}

impl<'a> Tables<'a> {
    fn buffer(&mut self, tile: &'a Tile) -> Result<u32, String> {
        let pointer = Arc::as_ptr(tile) as usize;
        if let Some(&id) = self.buffer_refs.get(&pointer) {
            return Ok(id);
        }
        let id = table_id(self.buffers.len())?;
        self.buffers.push(tile.as_slice());
        self.buffer_refs.insert(pointer, id);
        Ok(id)
    }

    fn tiles(
        &mut self,
        tiles: &'a BTreeMap<TileKey, Tile>,
    ) -> Result<BTreeMap<TileKey, u32>, String> {
        tiles
            .iter()
            .map(|(&key, tile)| Ok((key, self.buffer(tile)?)))
            .collect()
    }

    fn raster(&mut self, plane: &'a RasterPlane) -> Result<u32, String> {
        let pointer = plane as *const RasterPlane as usize;
        if let Some(&id) = self.raster_refs.get(&pointer) {
            return Ok(id);
        }
        let id = table_id(self.rasters.len())?;
        let tiles = self.tiles(plane.tiles())?;
        self.rasters.push(WireRaster {
            indexed: plane.is_indexed(),
            tiles,
        });
        self.raster_refs.insert(pointer, id);
        Ok(id)
    }

    fn object(&mut self, object: &'a Arc<VectorObject>) -> Result<u32, String> {
        let pointer = Arc::as_ptr(object) as usize;
        if let Some(&id) = self.object_refs.get(&pointer) {
            return Ok(id);
        }
        let id = table_id(self.objects.len())?;
        self.objects.push(object);
        self.object_refs.insert(pointer, id);
        Ok(id)
    }

    fn vector(&mut self, vector: &'a Arc<VectorLayer>) -> Result<u32, String> {
        let pointer = Arc::as_ptr(vector) as usize;
        if let Some(&id) = self.vector_refs.get(&pointer) {
            return Ok(id);
        }
        let id = table_id(self.vectors.len())?;
        let objects = vector
            .objects
            .iter()
            .map(|object| self.object(object))
            .collect::<Result<_, _>>()?;
        self.vectors.push(WireVector {
            next_object_id: vector.next_object_id,
            objects,
        });
        self.vector_refs.insert(pointer, id);
        Ok(id)
    }

    fn masks(&mut self, masks: &'a [MaskEntry]) -> Result<Vec<WireMaskEntry>, String> {
        masks
            .iter()
            .map(|mask| {
                Ok(WireMaskEntry {
                    id: mask.id,
                    name: mask.name.clone(),
                    plane: WireMask {
                        bounds: mask.plane.bounds,
                        default: mask.plane.default,
                        enabled: mask.plane.enabled,
                        linked: mask.plane.linked,
                        tiles: self.tiles(&mask.plane.tiles)?,
                    },
                })
            })
            .collect()
    }

    fn layer(&mut self, layer: &'a Layer) -> Result<WireLayer, String> {
        let content = match &layer.content {
            LayerContent::Raster(plane) => WireContent::Raster(self.raster(plane)?),
            LayerContent::Group { isolation, closed } => WireContent::Group {
                isolation: *isolation,
                closed: *closed,
            },
            LayerContent::Adjustment { settings } => WireContent::Adjustment {
                settings: settings.clone(),
            },
            LayerContent::Vector(vector) => WireContent::Vector(self.vector(vector)?),
            LayerContent::CelTrack { kind } => WireContent::CelTrack { kind: *kind },
        };
        Ok(WireLayer {
            id: layer.id,
            name: layer.name.clone(),
            visible: layer.visible,
            opacity: layer.opacity,
            content,
            parent_id: layer.parent_id,
            blend: layer.blend,
            alpha_locked: layer.alpha_locked,
            locked: layer.locked,
            masks: self.masks(&layer.masks)?,
            clipping: layer.clipping,
        })
    }

    fn animation(&mut self, animation: &'a AnimationSet) -> Result<WireAnimation, String> {
        let frames = animation
            .frames
            .iter()
            .map(|frame| WireFrame {
                id: frame.id,
                duration_ms: frame.duration_ms,
                exposures: frame.exposures.clone(),
            })
            .collect();
        let cels = animation
            .cels
            .iter()
            .map(|(&key, cel)| {
                let source = match &cel.source {
                    CelSource::Raster(plane) => WireSource::Raster(self.raster(plane)?),
                    CelSource::Vector(vector) => WireSource::Vector(self.vector(vector)?),
                };
                Ok((
                    key,
                    WireCel {
                        id: cel.id,
                        layer_id: cel.layer_id,
                        source,
                        masks: self.masks(&cel.masks)?,
                    },
                ))
            })
            .collect::<Result<_, String>>()?;
        Ok(WireAnimation {
            frames,
            cels,
            active_frame: animation.active_frame,
            next_frame_id: animation.next_frame_id,
            next_cel_id: animation.next_cel_id,
            next_tag_id: animation.next_tag_id,
            tags: animation.tags.clone(),
        })
    }
}

pub(super) fn encode(doc: &Document) -> Result<Vec<u8>, String> {
    let mut tables = Tables::default();
    let layers = doc
        .layers
        .iter()
        .map(|layer| tables.layer(layer))
        .collect::<Result<_, _>>()?;
    let animation = doc
        .animation
        .as_ref()
        .map(|animation| tables.animation(animation))
        .transpose()?;
    let wire = WireDocument {
        width: doc.width,
        height: doc.height,
        layers,
        active: doc.active,
        next_id: doc.next_id,
        palette: doc.palette.clone(),
        next_mask_id: doc.next_mask_id,
        active_mask_id: doc.active_mask_id,
        assistants: doc.assistants.clone(),
        animation,
        buffers: tables.buffers,
        rasters: tables.rasters,
        objects: tables.objects,
        vectors: tables.vectors,
        aseprite_metadata: doc.aseprite_metadata.clone(),
    };
    bincode::DefaultOptions::new()
        .with_limit(MAX_FILE_BYTES as u64)
        .serialize(&wire)
        .map_err(|_| "工程数据超过保存限制".into())
}

struct Reader {
    buffers: Vec<Tile>,
    used_buffers: Vec<bool>,
    rasters: Vec<Arc<RasterPlane>>,
    used_rasters: Vec<bool>,
    objects: Vec<Arc<VectorObject>>,
    used_objects: Vec<bool>,
    vectors: Vec<Arc<VectorLayer>>,
    used_vectors: Vec<bool>,
}

impl Reader {
    fn buffer(&mut self, id: u32, bytes: usize) -> Result<Tile, String> {
        let tile = self
            .buffers
            .get(id as usize)
            .filter(|tile| tile.len() == bytes)
            .ok_or("工程像素引用无效")?;
        self.used_buffers[id as usize] = true;
        Ok(tile.clone())
    }

    fn tiles(
        &mut self,
        tiles: BTreeMap<TileKey, u32>,
        bytes: usize,
    ) -> Result<BTreeMap<TileKey, Tile>, String> {
        tiles
            .into_iter()
            .map(|(key, id)| Ok((key, self.buffer(id, bytes)?)))
            .collect()
    }

    fn raster(&mut self, id: u32) -> Result<Arc<RasterPlane>, String> {
        let plane = self.rasters.get(id as usize).ok_or("工程像素源引用无效")?;
        self.used_rasters[id as usize] = true;
        Ok(plane.clone())
    }

    fn vector(&mut self, id: u32) -> Result<Arc<VectorLayer>, String> {
        let vector = self.vectors.get(id as usize).ok_or("工程矢量源引用无效")?;
        self.used_vectors[id as usize] = true;
        Ok(vector.clone())
    }

    fn masks(&mut self, masks: Vec<WireMaskEntry>) -> Result<Vec<MaskEntry>, String> {
        masks
            .into_iter()
            .map(|mask| {
                Ok(MaskEntry {
                    id: mask.id,
                    name: mask.name,
                    plane: LayerMask {
                        bounds: mask.plane.bounds,
                        default: mask.plane.default,
                        enabled: mask.plane.enabled,
                        linked: mask.plane.linked,
                        tiles: self.tiles(mask.plane.tiles, MASK_TILE_BYTES)?,
                    },
                })
            })
            .collect()
    }

    fn layer(&mut self, layer: WireLayer) -> Result<Layer, String> {
        let content = match layer.content {
            WireContent::Raster(id) => LayerContent::Raster(self.raster(id)?.as_ref().clone()),
            WireContent::Group { isolation, closed } => LayerContent::Group { isolation, closed },
            WireContent::Adjustment { settings } => LayerContent::Adjustment { settings },
            WireContent::Vector(id) => LayerContent::Vector(self.vector(id)?),
            WireContent::CelTrack { kind } => LayerContent::CelTrack { kind },
        };
        Ok(Layer {
            id: layer.id,
            name: layer.name,
            visible: layer.visible,
            opacity: layer.opacity,
            content,
            parent_id: layer.parent_id,
            blend: layer.blend,
            alpha_locked: layer.alpha_locked,
            locked: layer.locked,
            masks: self.masks(layer.masks)?,
            clipping: layer.clipping,
        })
    }

    fn animation(&mut self, animation: WireAnimation) -> Result<Arc<AnimationSet>, String> {
        let cels = animation
            .cels
            .into_iter()
            .map(|(key, cel)| {
                let source = match cel.source {
                    WireSource::Raster(id) => CelSource::Raster(self.raster(id)?),
                    WireSource::Vector(id) => CelSource::Vector(self.vector(id)?),
                };
                Ok((
                    key,
                    Arc::new(Cel {
                        id: cel.id,
                        layer_id: cel.layer_id,
                        source,
                        masks: self.masks(cel.masks)?,
                    }),
                ))
            })
            .collect::<Result<BTreeMap<_, _>, String>>()?;
        Ok(Arc::new(AnimationSet {
            frames: animation
                .frames
                .into_iter()
                .map(|frame| Frame {
                    id: frame.id,
                    duration_ms: frame.duration_ms,
                    exposures: frame.exposures,
                })
                .collect(),
            cels,
            active_frame: animation.active_frame,
            next_frame_id: animation.next_frame_id,
            next_cel_id: animation.next_cel_id,
            next_tag_id: animation.next_tag_id,
            tags: animation.tags,
        }))
    }
}

pub(super) fn decode(bytes: &[u8]) -> Result<Document, String> {
    decode_version::<()>(bytes, |_| None)
}

pub(super) fn decode_with_metadata(bytes: &[u8]) -> Result<Document, String> {
    decode_version::<Option<Arc<ProjectMetadata>>>(bytes, |metadata| metadata)
}

fn decode_version<M: DeserializeOwned>(
    bytes: &[u8],
    metadata: impl FnOnce(M) -> Option<Arc<ProjectMetadata>>,
) -> Result<Document, String> {
    let wire: WireDocument<Vec<u8>, VectorObject, M> = bincode::DefaultOptions::new()
        .with_limit(MAX_FILE_BYTES as u64)
        .reject_trailing_bytes()
        .deserialize(bytes)
        .map_err(|_| "工程文件已损坏")?;
    let aseprite_metadata = metadata(wire.aseprite_metadata);
    let mut total = aseprite_metadata
        .as_ref()
        .map_or(0, |metadata| metadata.bytes());
    if total > MAX_DOCUMENT_BYTES {
        return Err("工程元数据超过内存限制".into());
    }
    for buffer in &wire.buffers {
        if ![TILE_BYTES, MASK_TILE_BYTES].contains(&buffer.len()) {
            return Err("工程像素数据无效".into());
        }
        total = total.checked_add(buffer.len()).ok_or("工程像素数据过大")?;
        if total > MAX_DOCUMENT_BYTES {
            return Err("工程像素超过内存限制".into());
        }
    }
    let mut reader = Reader {
        used_buffers: vec![false; wire.buffers.len()],
        buffers: wire.buffers.into_iter().map(Arc::new).collect(),
        rasters: Vec::with_capacity(wire.rasters.len()),
        used_rasters: vec![false; wire.rasters.len()],
        objects: wire.objects.into_iter().map(Arc::new).collect(),
        used_objects: vec![],
        vectors: Vec::with_capacity(wire.vectors.len()),
        used_vectors: vec![false; wire.vectors.len()],
    };
    reader.used_objects.resize(reader.objects.len(), false);
    for raster in wire.rasters {
        let tiles = reader.tiles(
            raster.tiles,
            if raster.indexed {
                INDEX_TILE_BYTES
            } else {
                TILE_BYTES
            },
        )?;
        reader.rasters.push(Arc::new(if raster.indexed {
            RasterPlane::Indexed(tiles)
        } else {
            RasterPlane::Rgba(tiles)
        }));
    }
    for vector in wire.vectors {
        let objects = vector
            .objects
            .into_iter()
            .map(|id| {
                let object = reader
                    .objects
                    .get(id as usize)
                    .ok_or("工程矢量对象引用无效")?;
                reader.used_objects[id as usize] = true;
                Ok(object.clone())
            })
            .collect::<Result<Vec<_>, String>>()?;
        let vector = Arc::new(VectorLayer {
            next_object_id: vector.next_object_id,
            objects,
        });
        vector.validate()?;
        reader.vectors.push(vector);
    }
    let layers = wire
        .layers
        .into_iter()
        .map(|layer| reader.layer(layer))
        .collect::<Result<_, _>>()?;
    let animation = wire
        .animation
        .map(|animation| reader.animation(animation))
        .transpose()?;
    if reader
        .used_buffers
        .iter()
        .chain(&reader.used_rasters)
        .chain(&reader.used_objects)
        .chain(&reader.used_vectors)
        .any(|used| !used)
    {
        return Err("工程包含未引用的共享数据".into());
    }
    Ok(Document {
        width: wire.width,
        height: wire.height,
        layers,
        active: wire.active,
        next_id: wire.next_id,
        palette: wire.palette,
        next_mask_id: wire.next_mask_id,
        active_mask_id: wire.active_mask_id,
        assistants: wire.assistants,
        animation,
        aseprite_metadata,
    })
}
