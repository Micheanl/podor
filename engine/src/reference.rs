use crate::{model::*, resample, storage};

pub fn decode(bytes: &[u8]) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_CLIPBOARD_BYTES {
        return Err("参考图文件过大".into());
    }
    match image::guess_format(bytes) {
        Ok(image::ImageFormat::Png | image::ImageFormat::Jpeg | image::ImageFormat::WebP) => {}
        _ => return Err("参考图支持 PNG、JPEG 和静态 WebP".into()),
    }
    let mut document = storage::load(bytes)?;
    let original = (document.width, document.height);
    let longest = document.width.max(document.height);
    if longest > MAX_REFERENCE_EDGE {
        document = resample::resize_pixels(
            &document,
            (document.width * MAX_REFERENCE_EDGE / longest).max(1),
            (document.height * MAX_REFERENCE_EDGE / longest).max(1),
            resample::ResampleFilter::Lanczos3,
        )?;
    }
    let mut output = vec![0; 16 + (document.width * document.height * 4) as usize];
    for (index, value) in [original.0, original.1, document.width, document.height]
        .iter()
        .enumerate()
    {
        output[index * 4..index * 4 + 4].copy_from_slice(&value.to_le_bytes());
    }
    for (&(tx, ty), pixels) in &document.layers[0].tiles {
        let left = tx * TILE_SIZE;
        let top = ty * TILE_SIZE;
        let width = TILE_SIZE.min(document.width - left) as usize * 4;
        for row in 0..TILE_SIZE.min(document.height - top) {
            let from = (row * TILE_SIZE * 4) as usize;
            let to = 16 + (((top + row) * document.width + left) * 4) as usize;
            output[to..to + width].copy_from_slice(&pixels[from..from + width]);
        }
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(width: u32, height: u32, pixels: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, width, height);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder
                .write_header()
                .unwrap()
                .write_image_data(pixels)
                .unwrap();
        }
        bytes
    }

    #[test]
    fn preview_preserves_shape_alpha_and_cross_tile_pixels() {
        let pixels = (0..257 * 129)
            .flat_map(|i| [((i % 257) % 256) as u8, (i / 257) as u8, 80, 128])
            .collect::<Vec<_>>();
        let preview = decode(&png(257, 129, &pixels)).unwrap();
        assert_eq!(preview.len(), 16 + pixels.len());
        assert_eq!(&preview[..8], &preview[8..16]);
        for (actual, source) in preview[16..]
            .as_chunks::<4>()
            .0
            .iter()
            .zip(pixels.as_chunks::<4>().0.iter())
        {
            assert_eq!(actual[3], 128);
            for c in 0..3 {
                assert_eq!(actual[c], ((u32::from(source[c]) * 128 + 127) / 255) as u8);
            }
        }
    }

    #[test]
    fn large_references_are_bounded_and_bad_input_is_rejected() {
        let preview = decode(&png(4096, 4, &[200, 100, 50, 128].repeat(4096 * 4))).unwrap();
        assert_eq!(
            u32::from_le_bytes(preview[8..12].try_into().unwrap()),
            MAX_REFERENCE_EDGE
        );
        assert_eq!(u32::from_le_bytes(preview[12..16].try_into().unwrap()), 2);
        assert!(preview[16..]
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| *p == [100, 50, 25, 128]));
        assert!(decode(b"not an image").is_err());
    }
}
