use crate::{model::*, selection::Selection};
use serde::Deserialize;
use std::collections::VecDeque;

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SelectionRefinementKind {
    Expand,
    Contract,
    Smooth,
    Feather,
}

pub fn prepare(
    source: &Selection,
    canvas: Rect,
    kind: SelectionRefinementKind,
    radius: u32,
) -> Result<Option<Selection>, String> {
    if radius > MAX_DIMENSION {
        return Err("选区调整半径超出限制".into());
    }
    if radius == 0 || source.is_empty() {
        return Ok(None);
    }
    let boxes = box_radii(radius);
    let margin = match kind {
        SelectionRefinementKind::Contract => 0,
        SelectionRefinementKind::Feather => boxes.iter().sum(),
        _ => radius,
    };
    let bounds = source.bounds();
    let area = Rect {
        left: bounds.left.saturating_sub(margin).max(canvas.left),
        top: bounds.top.saturating_sub(margin).max(canvas.top),
        right: bounds.right.saturating_add(margin).min(canvas.right),
        bottom: bounds.bottom.saturating_add(margin).min(canvas.bottom),
    };
    let width = (area.right - area.left) as usize;
    let height = (area.bottom - area.top) as usize;
    let length = width
        .checked_mul(height)
        .filter(|&length| length > 0 && length as u64 <= MAX_PIXELS)
        .ok_or("选区调整尺寸超出限制")?;
    let queue_capacity = width.max(height).min((radius * 2 + 1) as usize);
    let scratch_bytes = length
        .checked_mul(2)
        .and_then(|bytes| bytes.checked_add(queue_capacity * size_of::<(usize, u8)>()))
        .ok_or("选区调整超过内存限制")?;
    if scratch_bytes > MAX_DOCUMENT_BYTES {
        return Err("选区调整超过内存限制".into());
    }
    let mut pixels = Vec::with_capacity(length);
    for y in area.top..area.bottom {
        for x in area.left..area.right {
            pixels.push(source.coverage(x, y));
        }
    }
    let mut buffer = vec![0; length];
    let mut queue = VecDeque::with_capacity(queue_capacity);
    match kind {
        SelectionRefinementKind::Expand => morphology(
            &mut pixels,
            &mut buffer,
            width,
            height,
            radius,
            true,
            &mut queue,
        ),
        SelectionRefinementKind::Contract => morphology(
            &mut pixels,
            &mut buffer,
            width,
            height,
            radius,
            false,
            &mut queue,
        ),
        SelectionRefinementKind::Smooth => {
            for maximum in [true, false, false, true] {
                morphology(
                    &mut pixels,
                    &mut buffer,
                    width,
                    height,
                    radius,
                    maximum,
                    &mut queue,
                );
            }
        }
        SelectionRefinementKind::Feather => {
            for radius in boxes {
                if radius == 0 {
                    continue;
                }
                axis(
                    &pixels,
                    &mut buffer,
                    (width, height),
                    radius,
                    true,
                    Filter::Mean,
                    &mut queue,
                );
                axis(
                    &buffer,
                    &mut pixels,
                    (width, height),
                    radius,
                    false,
                    Filter::Mean,
                    &mut queue,
                );
            }
        }
    }
    if pixels.iter().enumerate().all(|(index, &value)| {
        value
            == source.coverage(
                area.left + (index % width) as u32,
                area.top + (index / width) as u32,
            )
    }) {
        return Ok(None);
    }
    Ok(Some(Selection::from_refined_mask(area, pixels)))
}

fn box_radii(radius: u32) -> [u32; 3] {
    let sigma = f64::from(radius);
    let mut lower = (4.0 * sigma * sigma + 1.0).sqrt().floor() as u32;
    if lower.is_multiple_of(2) {
        lower -= 1;
    }
    let lower_count =
        ((12.0 * sigma * sigma - 3.0 * f64::from(lower * lower) - 12.0 * f64::from(lower) - 9.0)
            / (-4.0 * f64::from(lower) - 4.0))
            .round()
            .clamp(0.0, 3.0) as usize;
    std::array::from_fn(|index| {
        if index < lower_count {
            (lower - 1) / 2
        } else {
            lower.div_ceil(2)
        }
    })
}

fn morphology(
    pixels: &mut [u8],
    buffer: &mut [u8],
    width: usize,
    height: usize,
    radius: u32,
    maximum: bool,
    queue: &mut VecDeque<(usize, u8)>,
) {
    axis(
        pixels,
        buffer,
        (width, height),
        radius,
        true,
        Filter::Extrema(maximum),
        queue,
    );
    axis(
        buffer,
        pixels,
        (width, height),
        radius,
        false,
        Filter::Extrema(maximum),
        queue,
    );
}

#[derive(Clone, Copy)]
enum Filter {
    Extrema(bool),
    Mean,
}

fn axis(
    source: &[u8],
    target: &mut [u8],
    dimensions: (usize, usize),
    radius: u32,
    horizontal: bool,
    filter: Filter,
    queue: &mut VecDeque<(usize, u8)>,
) {
    let (width, height) = dimensions;
    let (count, length, stride) = if horizontal {
        (height, width, 1)
    } else {
        (width, height, width)
    };
    let radius = radius as usize;
    for line in 0..count {
        let start = if horizontal { line * width } else { line };
        match filter {
            Filter::Extrema(maximum) => {
                queue.clear();
                let mut next = 0;
                for position in 0..length {
                    let left = position.saturating_sub(radius);
                    while queue.front().is_some_and(|&(index, _)| index < left) {
                        queue.pop_front();
                    }
                    let right = position.saturating_add(radius).min(length - 1);
                    while next <= right {
                        let value = source[start + next * stride];
                        while queue.back().is_some_and(|&(_, old)| {
                            if maximum {
                                old <= value
                            } else {
                                old >= value
                            }
                        }) {
                            queue.pop_back();
                        }
                        queue.push_back((next, value));
                        next += 1;
                    }
                    target[start + position * stride] =
                        if !maximum && (position < radius || radius > length - 1 - position) {
                            0
                        } else {
                            queue.front().map_or(0, |&(_, value)| value)
                        };
                }
            }
            Filter::Mean => {
                let diameter = (radius * 2 + 1) as u32;
                let mut sum = (0..=radius.min(length - 1))
                    .map(|position| u32::from(source[start + position * stride]))
                    .sum::<u32>();
                for position in 0..length {
                    target[start + position * stride] = ((sum + diameter / 2) / diameter) as u8;
                    if position >= radius {
                        sum -= u32::from(source[start + (position - radius) * stride]);
                    }
                    if position + radius + 1 < length {
                        sum += u32::from(source[start + (position + radius + 1) * stride]);
                    }
                }
            }
        }
    }
}
