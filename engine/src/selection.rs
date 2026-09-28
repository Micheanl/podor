use crate::model::{Rect, MAX_DIMENSION, MAX_SELECTION_POINTS};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SelectionKind {
    #[default]
    Rectangle,
    Ellipse,
    Lasso,
}

#[derive(Clone, Copy, Deserialize, Serialize)]
pub struct SelectionPoint {
    pub x: f32,
    pub y: f32,
}

#[derive(Deserialize, Serialize)]
pub struct SelectionSpec {
    #[serde(flatten)]
    pub bounds: Rect,
    #[serde(default)]
    pub kind: SelectionKind,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub points: Vec<SelectionPoint>,
}

#[derive(Serialize)]
pub struct Selection {
    #[serde(flatten)]
    spec: SelectionSpec,
    #[serde(skip)]
    mask: Vec<u8>,
}

impl Selection {
    pub fn rectangle(bounds: Rect, canvas: Rect) -> Result<Self, String> {
        Self::new(
            SelectionSpec {
                bounds,
                kind: SelectionKind::Rectangle,
                points: Vec::new(),
            },
            canvas,
        )
    }

    pub fn new(mut spec: SelectionSpec, canvas: Rect) -> Result<Self, String> {
        if spec.kind == SelectionKind::Lasso {
            if !(3..=MAX_SELECTION_POINTS).contains(&spec.points.len())
                || spec.points.iter().any(|p| {
                    !p.x.is_finite()
                        || !p.y.is_finite()
                        || p.x.abs().max(p.y.abs()) > MAX_DIMENSION as f32 * 2.0
                })
            {
                return Err("套索路径无效或过长".into());
            }
            let extent = |horizontal: bool, minimum: bool| {
                spec.points
                    .iter()
                    .map(|p| if horizontal { p.x } else { p.y })
                    .reduce(if minimum { f32::min } else { f32::max })
                    .unwrap()
            };
            spec.bounds = Rect {
                left: extent(true, true).floor().max(0.0) as u32,
                top: extent(false, true).floor().max(0.0) as u32,
                right: extent(true, false).ceil().max(0.0) as u32,
                bottom: extent(false, false).ceil().max(0.0) as u32,
            }
            .intersect(canvas)
            .ok_or("选区超出画布或为空")?;
        } else if !spec.points.is_empty() {
            return Err("选区参数无效".into());
        }
        if spec.bounds.intersect(canvas) != Some(spec.bounds) {
            return Err("选区超出画布或为空".into());
        }
        let mask = if spec.kind == SelectionKind::Rectangle {
            Vec::new()
        } else {
            rasterize(&spec)
        };
        if !mask.is_empty() && !mask.iter().any(|&value| value != 0) {
            return Err("选区为空".into());
        }
        Ok(Self { spec, mask })
    }

    pub fn bounds(&self) -> Rect {
        self.spec.bounds
    }

    pub fn row(&self, y: u32) -> Option<&[u8]> {
        if self.mask.is_empty() {
            return None;
        }
        let bounds = self.bounds();
        let width = (bounds.right - bounds.left) as usize;
        let start = (y - bounds.top) as usize * width;
        Some(&self.mask[start..start + width])
    }

    pub fn coverage(&self, x: u32, y: u32) -> u8 {
        if !self.bounds().contains(x, y) {
            return 0;
        }
        self.row(y)
            .map_or(255, |row| row[(x - self.bounds().left) as usize])
    }

    pub fn intersects(&self, area: Rect) -> bool {
        let Some(area) = self.bounds().intersect(area) else {
            return false;
        };
        if self.mask.is_empty() {
            return true;
        }
        let left = (area.left - self.bounds().left) as usize;
        let right = (area.right - self.bounds().left) as usize;
        (area.top..area.bottom).any(|y| {
            self.row(y).unwrap()[left..right]
                .iter()
                .any(|&value| value != 0)
        })
    }
}

struct Edge {
    top: f64,
    bottom: f64,
    x: f64,
    slope: f64,
}

fn rasterize(spec: &SelectionSpec) -> Vec<u8> {
    let bounds = spec.bounds;
    let width = (bounds.right - bounds.left) as usize;
    let height = (bounds.bottom - bounds.top) as usize;
    let mut mask = vec![0; width * height];
    let mut changes = vec![0.0_f64; width + 2];
    let mut edges = Vec::new();
    if spec.kind == SelectionKind::Lasso {
        for (&a, &b) in spec.points.iter().zip(spec.points.iter().cycle().skip(1)) {
            let (a, b) = if a.y < b.y { (a, b) } else { (b, a) };
            if a.y == b.y {
                continue;
            }
            edges.push(Edge {
                top: f64::from(a.y),
                bottom: f64::from(b.y),
                x: f64::from(a.x),
                slope: (f64::from(b.x) - f64::from(a.x)) / (f64::from(b.y) - f64::from(a.y)),
            });
        }
        edges.sort_unstable_by(|a, b| a.top.total_cmp(&b.top));
    }
    let mut next = 0;
    let mut active: Vec<&Edge> = Vec::with_capacity(edges.len());
    let mut crossings = Vec::with_capacity(edges.len());
    const SAMPLES: usize = 8;
    for y in 0..height {
        changes.fill(0.0);
        for sample in 0..SAMPLES {
            let scan = f64::from(bounds.top) + y as f64 + (sample as f64 + 0.5) / SAMPLES as f64;
            if spec.kind == SelectionKind::Ellipse {
                let ry = height as f64 * 0.5;
                let normalized = (scan - f64::from(bounds.top) - ry) / ry;
                let rx = width as f64 * 0.5;
                let extent = rx * (1.0 - normalized * normalized).max(0.0).sqrt();
                span(&mut changes, rx - extent, rx + extent, width);
            } else {
                while next < edges.len() && edges[next].top <= scan {
                    active.push(&edges[next]);
                    next += 1;
                }
                active.retain(|edge| edge.bottom > scan);
                crossings.clear();
                crossings.extend(
                    active
                        .iter()
                        .map(|edge| edge.x + (scan - edge.top) * edge.slope),
                );
                crossings.sort_unstable_by(f64::total_cmp);
                for pair in crossings.as_chunks::<2>().0 {
                    span(
                        &mut changes,
                        pair[0] - f64::from(bounds.left),
                        pair[1] - f64::from(bounds.left),
                        width,
                    );
                }
            }
        }
        let mut coverage = 0.0;
        for x in 0..width {
            coverage += changes[x];
            mask[y * width + x] = (coverage * (255.0 / SAMPLES as f64))
                .round()
                .clamp(0.0, 255.0) as u8;
        }
    }
    mask
}

fn span(changes: &mut [f64], left: f64, right: f64, width: usize) {
    let left = left.clamp(0.0, width as f64);
    let right = right.clamp(0.0, width as f64);
    if left >= right {
        return;
    }
    let first = left.floor() as usize;
    let last = right.floor() as usize;
    let start = 1.0 - left.fract();
    let end = right.fract();
    changes[first] += start;
    changes[first + 1] += 1.0 - start;
    changes[last] -= 1.0 - end;
    changes[last + 1] -= end;
}

pub fn mix(old: u8, new: u8, coverage: u8) -> u8 {
    ((u32::from(old) * (255 - u32::from(coverage)) + u32::from(new) * u32::from(coverage) + 127)
        / 255) as u8
}
