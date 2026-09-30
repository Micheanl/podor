use crate::model::*;
use std::{
    collections::{btree_map::Entry, BTreeSet},
    sync::Arc,
};

mod channels;
use channels::Rows;

const DAMAGED: &str = "PSD 文件已损坏";
const UNSUPPORTED: &str = "PSD 包含尚未支持的图层属性，请先栅格化特效、文字或智能对象";
const UNSUPPORTED_MASK: &str = "PSD 目前支持普通像素蒙版，请先应用矢量蒙版或蒙版密度、羽化参数";

pub fn load(bytes: &[u8]) -> Result<Document, String> {
    let mut input = Reader(bytes);
    input.expect(b"8BPS")?;
    if input.u16()? != 1 {
        return Err("暂不支持 PSB，请另存为 PSD".into());
    }
    input.expect(&[0; 6])?;
    let channels = input.u16()? as usize;
    if !(3..=4).contains(&channels) {
        return Err("PSD 仅支持 RGB 和透明度通道，请移除额外通道".into());
    }
    let height = input.u32()?;
    let width = input.u32()?;
    let mut doc = Document::new(width, height)?;
    if input.u16()? != 8 || input.u16()? != 3 {
        return Err("PSD 目前支持 8 位 RGB，请先转换颜色模式".into());
    }
    if !input.block()?.0.is_empty() {
        return Err(DAMAGED.into());
    }
    let selected_ids = read_selection_ids(input.block()?)?;
    let mut section = input.block()?;
    let mut remaining_bytes = MAX_DOCUMENT_BYTES;
    let mut has_layers = false;
    if !section.0.is_empty() {
        let mut info = section.block()?;
        if !info.0.is_empty() {
            let count = (info.u16()? as i16).unsigned_abs() as usize;
            if count > MAX_PSD_RECORDS {
                return Err("PSD 图层数量超出限制".into());
            }
            if count != 0 {
                has_layers = true;
                let mut records = Vec::with_capacity(count);
                let mut decoded_bytes = 0u64;
                for index in 0..count {
                    let record = record(&mut info, index as u32 + 1)?;
                    decoded_bytes += record.width as u64 * record.height as u64 * 4;
                    if let Some(mask) = record.layer.first_mask() {
                        decoded_bytes +=
                            u64::from(mask.bounds.width()) * u64::from(mask.bounds.height());
                    }
                    if decoded_bytes > MAX_PSD_DECODED_BYTES {
                        return Err("PSD 解码像素超过限制".into());
                    }
                    records.push(record);
                }
                validate_structure(&records)?;
                assign_ids(&mut records)?;
                for record in &mut records {
                    let mut rows: [Option<Rows<'_>>; 4] = [None, None, None, None];
                    let mut mask_rows = None;
                    for (channel, length) in &record.channels {
                        let bytes = info.take(*length)?;
                        if *channel != 4
                            && bytes.is_empty()
                            && record.section != 0
                            && (record.width == 0 || record.height == 0)
                        {
                            continue;
                        }
                        if *channel == 4 {
                            let mask = record.layer.first_mask().ok_or(DAMAGED)?;
                            mask_rows = Some(Rows::new(bytes, mask.bounds.height() as usize)?);
                        } else {
                            rows[*channel] = Some(Rows::new(bytes, record.height)?);
                        }
                    }
                    let mut planes: [Vec<u8>; 4] = std::array::from_fn(|channel| {
                        vec![if channel == 3 { 255 } else { 0 }; record.width]
                    });
                    for y in 0..record.height {
                        for (row, plane) in rows.iter_mut().zip(&mut planes) {
                            if let Some(row) = row {
                                row.read(plane)?;
                            }
                        }
                        if record.section == 0 {
                            write_row(
                                &mut record.layer,
                                doc.bounds(),
                                record.left,
                                i64::from(record.top) + y as i64,
                                &planes,
                                &mut remaining_bytes,
                            )?;
                        }
                    }
                    for row in rows.into_iter().flatten() {
                        row.finish()?;
                    }
                    if let Some(mut rows) = mask_rows {
                        let mask = record.layer.first_mask_mut().ok_or(DAMAGED)?;
                        let mut row = vec![mask.default; mask.bounds.width() as usize];
                        for y in 0..mask.bounds.height() {
                            rows.read(&mut row)?;
                            if record.mask_inverted {
                                for value in &mut row {
                                    *value = 255 - *value;
                                }
                            }
                            write_mask_row(mask, y, &row, &mut remaining_bytes)?;
                        }
                        rows.finish()?;
                    }
                }
                info.padding()?;
                doc.layers = assemble_layers(records)?;
                doc.active = doc
                    .layers
                    .iter()
                    .rev()
                    .find(|layer| !layer.is_group())
                    .unwrap_or_else(|| doc.layers.last().unwrap())
                    .id;
                if let Some(id) = selected_ids
                    .iter()
                    .find(|id| doc.layers.iter().any(|layer| layer.id == **id))
                {
                    doc.active = *id;
                }
                doc.next_id = doc
                    .layers
                    .iter()
                    .map(|layer| layer.id)
                    .max()
                    .unwrap()
                    .checked_add(1)
                    .ok_or(DAMAGED)?;
            }
        }
        if !section.0.is_empty() && !section.block()?.0.is_empty() {
            return Err("PSD 全局蒙版尚未支持，请先应用全局蒙版".into());
        }
        while !section.0.is_empty() {
            if section.0.len() < 12 {
                section.padding()?;
                break;
            }
            let (key, mut data) = section.tag()?;
            match key {
                b"Patt" | b"Pat2" | b"Pat3" | b"Anno" | b"lnkD" | b"lnk2" | b"lnk3" => {}
                b"FMsk" if data.0.is_empty() => {}
                b"iOpa" if data.u8()? == 255 => {}
                _ => return Err(UNSUPPORTED.into()),
            }
        }
    }
    if !has_layers {
        if channels != 3 {
            return Err("PSD 合成图含额外通道，请保存包含像素图层的 PSD".into());
        }
        let mut rows = Rows::new(input.0, height as usize * channels)?;
        let mut pixels = vec![0; width as usize * height as usize * 3];
        let mut row = vec![0; width as usize];
        for channel in 0..3 {
            for y in 0..height as usize {
                rows.read(&mut row)?;
                let start = (y * width as usize) * 3;
                for (pixel, value) in pixels[start..start + width as usize * 3]
                    .as_chunks_mut::<3>()
                    .0
                    .iter_mut()
                    .zip(&row)
                {
                    pixel[channel] = *value;
                }
            }
        }
        rows.finish()?;
        let mut planes: [Vec<u8>; 4] = std::array::from_fn(|_| vec![255; width as usize]);
        for y in 0..height as usize {
            let start = y * width as usize * 3;
            for (x, pixel) in pixels[start..start + width as usize * 3]
                .as_chunks::<3>()
                .0
                .iter()
                .enumerate()
            {
                for channel in 0..3 {
                    planes[channel][x] = pixel[channel];
                }
            }
            let bounds = doc.bounds();
            write_row(
                doc.active_mut(),
                bounds,
                0,
                y as i64,
                &planes,
                &mut remaining_bytes,
            )?;
        }
        doc.active_mut().name = "导入的图像".into();
    }
    doc.assign_mask_ids()?;
    doc.validate()?;
    Ok(doc)
}

struct Record {
    layer: Layer,
    top: i32,
    left: i32,
    width: usize,
    height: usize,
    channels: Vec<(usize, usize)>,
    mask_inverted: bool,
    section: u32,
    source_id: Option<u32>,
}

fn read_selection_ids(mut input: Reader<'_>) -> Result<Vec<u32>, String> {
    let mut selected = None;
    while !input.0.is_empty() {
        input.expect(b"8BIM")?;
        let key = input.u16()?;
        let name_len = input.u8()? as usize;
        input.take(name_len)?;
        input.take((name_len + 1) % 2)?;
        let mut data = input.block()?;
        input.take(data.0.len() % 2)?;
        if key != 1069 {
            continue;
        }
        if selected.is_some() {
            return Err("PSD 图层选择信息重复".into());
        }
        let count = data.u16()? as usize;
        if count > MAX_LAYER_NODES || data.0.len() != count * 4 {
            return Err("PSD 图层选择信息无效".into());
        }
        let mut ids = BTreeSet::new();
        let mut values = Vec::with_capacity(count);
        for _ in 0..count {
            let id = data.u32()?;
            if id == 0 || id == u32::MAX || !ids.insert(id) {
                return Err("PSD 图层选择信息无效".into());
            }
            values.push(id);
        }
        selected = Some(values);
    }
    Ok(selected.unwrap_or_default())
}

fn record(input: &mut Reader<'_>, id: u32) -> Result<Record, String> {
    let top = input.u32()? as i32;
    let left = input.u32()? as i32;
    let height = i64::from(input.u32()? as i32) - i64::from(top);
    let width = i64::from(input.u32()? as i32) - i64::from(left);
    if width < 0
        || height < 0
        || width > i64::from(MAX_DIMENSION)
        || height > i64::from(MAX_DIMENSION)
        || width as u64 * height as u64 > MAX_PIXELS
    {
        return Err("PSD 图层尺寸超出限制".into());
    }
    let count = input.u16()?;
    if count > 5 {
        return Err("PSD 仅支持 RGB 和透明度通道，请移除额外通道".into());
    }
    let mut seen = [false; 5];
    let mut channels = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let channel = match input.u16()? as i16 {
            -1 => 3,
            -2 => 4,
            value @ 0..=2 => value as usize,
            -3 => return Err(UNSUPPORTED_MASK.into()),
            _ => return Err(UNSUPPORTED.into()),
        };
        if seen[channel] {
            return Err(DAMAGED.into());
        }
        seen[channel] = true;
        channels.push((channel, input.u32()? as usize));
    }
    input.expect(b"8BIM")?;
    let mut mode: [u8; 4] = input.take(4)?.try_into().unwrap();
    let opacity = f32::from(input.u8()?) / 255.0;
    let clipping = match input.u8()? {
        0 => false,
        1 => true,
        _ => return Err("PSD 剪贴蒙版标记无效".into()),
    };
    let flags = input.u8()?;
    input.u8()?;
    let mut extra = input.block()?;
    let (mask, mask_inverted) = read_mask(extra.block()?)?;
    if mask.is_some() != seen[4] {
        return Err(DAMAGED.into());
    }
    let ranges = extra.block()?.0;
    if !ranges.len().is_multiple_of(8)
        || ranges
            .as_chunks::<4>()
            .0
            .iter()
            .any(|range| *range != [0, 0, 255, 255])
    {
        return Err("PSD 混合颜色带尚未支持，请先栅格化该图层".into());
    }
    let name_length = extra.u8()? as usize;
    let name = String::from_utf8_lossy(extra.take(name_length)?).into_owned();
    extra.take((4 - (name_length + 1) % 4) % 4)?;
    let mut layer = Layer::new(id, name);
    layer.opacity = opacity;
    layer.clipping = clipping;
    layer.visible = flags & 2 == 0;
    layer.alpha_locked = flags & 1 != 0;
    layer.masks = mask
        .into_iter()
        .map(|plane| MaskEntry {
            id: 0,
            name: "Mask".into(),
            plane,
        })
        .collect();
    let mut section = None;
    let mut source_id = None;
    while !extra.0.is_empty() {
        if extra.0.len() < 12 {
            extra.padding()?;
            break;
        }
        let (key, mut data) = extra.tag()?;
        match key {
            b"brit" | b"curv" | b"grdm" | b"CgEd" => {
                return Err("PSD 暂不支持可编辑调整图层，请在原软件中导出烘焙副本".into());
            }
            b"luni" => {
                let count = data.u32()? as usize;
                if count > MAX_LAYER_NAME_BYTES {
                    return Err("PSD 图层名称过长".into());
                }
                let mut units = Vec::with_capacity(count);
                for _ in 0..count {
                    units.push(data.u16()?);
                }
                layer.name = String::from_utf16(&units).map_err(|_| DAMAGED)?;
            }
            b"lspf" => {
                let protection = data.u32()?;
                layer.alpha_locked |= protection & 1 != 0;
                layer.locked = protection & 0x8000_0006 != 0;
            }
            b"lsct" | b"lsdk" => {
                let length = data.0.len();
                if !matches!(length, 4 | 12 | 16) || section.is_some() {
                    return Err("PSD 图层组标记无效".into());
                }
                let kind = data.u32()?;
                if kind > 3 {
                    return Err("PSD 图层组标记无效".into());
                }
                section = Some(kind);
                if length >= 12 {
                    data.expect(b"8BIM")?;
                    mode = data.take(4)?.try_into().unwrap();
                }
                if length == 16 && data.u32()? != 0 {
                    return Err("PSD 场景图层组尚未支持".into());
                }
            }
            b"lyid" => {
                if data.0.len() != 4 || source_id.is_some() {
                    return Err(DAMAGED.into());
                }
                source_id = Some(data.u32()?);
            }
            b"lclr" | b"lnsr" | b"shmd" | b"fxrp" | b"lyvr" | b"name" => {}
            b"iOpa" if data.u8()? == 255 => {}
            b"clbl" | b"infx" if data.u8()? == 1 => {}
            b"knko" if data.u8()? == 0 => {}
            _ => return Err(UNSUPPORTED.into()),
        }
    }
    if layer.name.len() > MAX_LAYER_NAME_BYTES {
        return Err("PSD 图层名称过长".into());
    }
    let section = section.unwrap_or(0);
    if section == 0 && (!seen[..3].iter().all(|value| *value) || flags & 0x18 == 0x18) {
        return Err(UNSUPPORTED.into());
    }
    let (blend, isolation) = read_mode(mode)?;
    layer.blend = blend;
    match section {
        1 | 2 => {
            layer.content = LayerContent::Group {
                isolation,
                closed: section == 2,
            };
        }
        0 if isolation == GroupIsolation::PassThrough => {
            return Err("PSD 穿透混合模式仅适用于图层组".into());
        }
        3 if !layer.masks.is_empty() || layer.clipping => {
            return Err("PSD 图层组边界包含无效属性".into());
        }
        _ => {}
    }
    Ok(Record {
        layer,
        top,
        left,
        width: width as usize,
        height: height as usize,
        channels,
        mask_inverted,
        section,
        source_id,
    })
}

fn read_mode(mode: [u8; 4]) -> Result<(BlendMode, GroupIsolation), String> {
    let blend = match &mode {
        b"norm" | b"pass" => BlendMode::Normal,
        b"mul " => BlendMode::Multiply,
        b"scrn" => BlendMode::Screen,
        b"over" => BlendMode::Overlay,
        b"sLit" => BlendMode::SoftLight,
        b"dark" => BlendMode::Darken,
        b"lite" => BlendMode::Lighten,
        b"diff" => BlendMode::Difference,
        _ => return Err("PSD 包含尚未支持的混合模式".into()),
    };
    Ok((
        blend,
        if &mode == b"pass" {
            GroupIsolation::PassThrough
        } else {
            GroupIsolation::Isolated
        },
    ))
}

fn validate_structure(records: &[Record]) -> Result<(), String> {
    let mut depth = 0;
    let mut nodes = 0;
    let mut leaves = 0;
    for record in records {
        match record.section {
            3 => {
                depth += 1;
                if depth > MAX_GROUP_DEPTH {
                    return Err("PSD 图层组嵌套超过上限".into());
                }
            }
            1 | 2 => {
                if depth == 0 {
                    return Err("PSD 图层组起止标记不匹配".into());
                }
                depth -= 1;
                nodes += 1;
            }
            _ => {
                nodes += 1;
                leaves += 1;
            }
        }
        if nodes > MAX_LAYER_NODES || leaves > MAX_LAYERS {
            return Err("PSD 图层数量超出限制".into());
        }
    }
    if depth != 0 || nodes == 0 {
        return Err("PSD 图层组起止标记不匹配".into());
    }
    Ok(())
}

fn assign_ids(records: &mut [Record]) -> Result<(), String> {
    let mut ids = BTreeSet::new();
    for record in records.iter().filter(|record| record.section != 3) {
        if let Some(id) = record.source_id {
            if id == 0 || id == u32::MAX || !ids.insert(id) {
                return Err("PSD 图层标识无效".into());
            }
        }
    }
    let mut next = 1u32;
    for record in records.iter_mut().filter(|record| record.section != 3) {
        record.layer.id = if let Some(id) = record.source_id {
            id
        } else {
            while ids.contains(&next) {
                next += 1;
            }
            ids.insert(next);
            next
        };
    }
    Ok(())
}

fn assemble_layers(records: Vec<Record>) -> Result<Vec<Layer>, String> {
    let mut stack: Vec<Vec<Layer>> = vec![Vec::new()];
    for record in records {
        match record.section {
            3 => stack.push(Vec::new()),
            1 | 2 => {
                let mut children = stack.pop().ok_or(DAMAGED)?;
                for child in &mut children {
                    if child.parent_id.is_none() {
                        child.parent_id = Some(record.layer.id);
                    }
                }
                let parent = stack.last_mut().ok_or(DAMAGED)?;
                parent.push(record.layer);
                parent.extend(children);
            }
            _ => stack.last_mut().ok_or(DAMAGED)?.push(record.layer),
        }
    }
    if stack.len() != 1 {
        return Err("PSD 图层组起止标记不匹配".into());
    }
    Ok(stack.pop().unwrap())
}

fn read_mask(mut input: Reader<'_>) -> Result<(Option<LayerMask>, bool), String> {
    if input.0.is_empty() {
        return Ok((None, false));
    }
    if input.0.len() != 20 {
        return Err(UNSUPPORTED_MASK.into());
    }
    let top = input.u32()? as i32;
    let left = input.u32()? as i32;
    let bottom = input.u32()? as i32;
    let right = input.u32()? as i32;
    let default = input.u8()?;
    let flags = input.u8()?;
    if !matches!(default, 0 | 255) {
        return Err(DAMAGED.into());
    }
    if flags & !7 != 0 {
        return Err(UNSUPPORTED_MASK.into());
    }
    input.expect(&[0; 2])?;
    let inverted = flags & 4 != 0;
    let mut mask = LayerMask::new(
        MaskBounds {
            left,
            top,
            right,
            bottom,
        },
        if inverted { 255 - default } else { default },
    );
    mask.enabled = flags & 2 == 0;
    mask.linked = flags & 1 == 0;
    mask.validate()?;
    Ok((Some(mask), inverted))
}

fn write_mask_row(
    mask: &mut LayerMask,
    y: u32,
    row: &[u8],
    remaining_bytes: &mut usize,
) -> Result<(), String> {
    for (tx, source) in row.chunks(TILE_SIZE as usize).enumerate() {
        if source.iter().all(|value| *value == mask.default) {
            continue;
        }
        let tile = match mask.tiles.entry((tx as u32, y / TILE_SIZE)) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if *remaining_bytes < MASK_TILE_BYTES {
                    return Err("工程像素超过内存限制".into());
                }
                *remaining_bytes -= MASK_TILE_BYTES;
                entry.insert(Arc::new(vec![mask.default; MASK_TILE_BYTES]))
            }
        };
        let target = (y % TILE_SIZE * TILE_SIZE) as usize;
        Arc::get_mut(tile).unwrap()[target..target + source.len()].copy_from_slice(source);
    }
    Ok(())
}

fn write_row(
    layer: &mut Layer,
    bounds: Rect,
    x: i32,
    y: i64,
    planes: &[Vec<u8>; 4],
    remaining_bytes: &mut usize,
) -> Result<(), String> {
    if y < 0 || y >= i64::from(bounds.bottom) {
        return Ok(());
    }
    let left = i64::from(x).max(0);
    let right = (i64::from(x) + planes[0].len() as i64).min(i64::from(bounds.right));
    if right <= left {
        return Ok(());
    }
    let (left, right, y) = (left as u32, right as u32, y as u32);
    for tx in left / TILE_SIZE..right.div_ceil(TILE_SIZE) {
        let begin = (tx * TILE_SIZE).max(left);
        let end = ((tx + 1) * TILE_SIZE).min(right);
        let source = (i64::from(begin) - i64::from(x)) as usize;
        let count = (end - begin) as usize;
        if planes[3][source..source + count]
            .iter()
            .all(|alpha| *alpha == 0)
        {
            continue;
        }
        let tile = match layer.raster_mut()?.tiles_mut().entry((tx, y / TILE_SIZE)) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if *remaining_bytes < TILE_BYTES {
                    return Err("工程像素超过内存限制".into());
                }
                *remaining_bytes -= TILE_BYTES;
                entry.insert(Arc::new(vec![0; TILE_BYTES]))
            }
        };
        let target = ((y % TILE_SIZE * TILE_SIZE + begin % TILE_SIZE) * 4) as usize;
        for (offset, pixel) in Arc::get_mut(tile).unwrap()[target..target + count * 4]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .enumerate()
        {
            let alpha = planes[3][source + offset];
            for channel in 0..3 {
                pixel[channel] = ((u32::from(planes[channel][source + offset]) * u32::from(alpha)
                    + 127)
                    / 255) as u8;
            }
            pixel[3] = alpha;
        }
    }
    Ok(())
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, size: usize) -> Result<&'a [u8], String> {
        let (head, tail) = self.0.split_at_checked(size).ok_or(DAMAGED)?;
        self.0 = tail;
        Ok(head)
    }
    fn expect(&mut self, expected: &[u8]) -> Result<(), String> {
        if self.take(expected.len())? == expected {
            Ok(())
        } else {
            Err(DAMAGED.into())
        }
    }
    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, String> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn block(&mut self) -> Result<Reader<'a>, String> {
        let size = self.u32()? as usize;
        Ok(Reader(self.take(size)?))
    }
    fn tag(&mut self) -> Result<(&'a [u8], Reader<'a>), String> {
        if !matches!(self.take(4)?, b"8BIM" | b"8B64") {
            return Err(DAMAGED.into());
        }
        let key = self.take(4)?;
        let data = self.block()?;
        self.take(data.0.len() % 2)?;
        Ok((key, data))
    }
    fn padding(self) -> Result<(), String> {
        if self.0.len() <= 3 && self.0.iter().all(|v| *v == 0) {
            Ok(())
        } else {
            Err(DAMAGED.into())
        }
    }
}
