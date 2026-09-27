use crate::model::BlendMode;
use std::sync::OnceLock;

pub fn composite(dst: &mut [u8], src: &[u8], opacity: u32, mode: BlendMode, opaque: bool) {
    match mode {
        BlendMode::Normal if opaque => normal::<true>(dst, src, opacity),
        BlendMode::Normal => normal::<false>(dst, src, opacity),
        BlendMode::Multiply => blend(dst, src, opacity, |b, s| (b * s + 127) / 255),
        BlendMode::Screen => blend(dst, src, opacity, |b, s| b + s - (b * s + 127) / 255),
        BlendMode::Overlay => blend(dst, src, opacity, |b, s| {
            if b < 128 {
                (2 * b * s + 127) / 255
            } else {
                255 - (2 * (255 - b) * (255 - s) + 127) / 255
            }
        }),
        BlendMode::SoftLight => {
            static TABLE: OnceLock<Box<[[u8; 256]; 256]>> = OnceLock::new();
            let table = TABLE.get_or_init(|| {
                let mut table = Box::new([[0; 256]; 256]);
                for (b, row) in table.iter_mut().enumerate() {
                    let back = b as f32 / 255.0;
                    let curve = if back <= 0.25 {
                        ((16.0 * back - 12.0) * back + 4.0) * back
                    } else {
                        back.sqrt()
                    };
                    for (s, value) in row.iter_mut().enumerate() {
                        let front = s as f32 / 255.0;
                        let mixed = if front <= 0.5 {
                            back - (1.0 - 2.0 * front) * back * (1.0 - back)
                        } else {
                            back + (2.0 * front - 1.0) * (curve - back)
                        };
                        *value = (mixed * 255.0).round() as u8;
                    }
                }
                table
            });
            blend(dst, src, opacity, |b, s| {
                u32::from(table[b as usize][s as usize])
            });
        }
        BlendMode::Darken => blend(dst, src, opacity, u32::min),
        BlendMode::Lighten => blend(dst, src, opacity, u32::max),
        BlendMode::Difference => blend(dst, src, opacity, u32::abs_diff),
    }
}

fn normal<const OPAQUE: bool>(target: &mut [u8], source: &[u8], opacity: u32) {
    for (dst, src) in target
        .as_chunks_mut::<4>()
        .0
        .iter_mut()
        .zip(source.as_chunks::<4>().0)
    {
        let alpha = (u32::from(src[3]) * opacity + 127) / 255;
        for channel in 0..3 {
            dst[channel] = ((u32::from(src[channel]) * opacity
                + u32::from(dst[channel]) * (255 - alpha)
                + 127)
                / 255)
                .min(255) as u8;
        }
        if !OPAQUE {
            dst[3] = (alpha + (u32::from(dst[3]) * (255 - alpha) + 127) / 255) as u8;
        }
    }
}

fn blend(target: &mut [u8], source: &[u8], opacity: u32, channel: impl Fn(u32, u32) -> u32) {
    for (dst, src) in target
        .as_chunks_mut::<4>()
        .0
        .iter_mut()
        .zip(source.as_chunks::<4>().0)
    {
        let source_alpha = u32::from(src[3]);
        let alpha = (source_alpha * opacity + 127) / 255;
        if alpha == 0 {
            continue;
        }
        let back_alpha = u32::from(dst[3]);
        if source_alpha == 255 && back_alpha == 255 {
            if alpha == 255 {
                for c in 0..3 {
                    dst[c] = channel(u32::from(dst[c]), u32::from(src[c])) as u8;
                }
            } else {
                for c in 0..3 {
                    let back = u32::from(dst[c]);
                    let mixed = channel(back, u32::from(src[c]));
                    dst[c] = ((mixed * alpha + back * (255 - alpha) + 127) / 255) as u8;
                }
            }
            continue;
        }
        let result_alpha = alpha + (back_alpha * (255 - alpha) + 127) / 255;
        for c in 0..3 {
            let back = u32::from(dst[c]);
            let front = u32::from(src[c]).min(source_alpha);
            let overlap = (back * 255 + back_alpha / 2)
                .checked_div(back_alpha)
                .map(|back| {
                    channel(
                        back.min(255),
                        (front * 255 + source_alpha / 2) / source_alpha,
                    )
                })
                .unwrap_or(0);
            dst[c] = ((front * opacity * (255 - back_alpha)
                + back * (255 - alpha) * 255
                + alpha * back_alpha * overlap
                + 32512)
                / 65025)
                .min(result_alpha) as u8;
        }
        dst[3] = result_alpha as u8;
    }
}
