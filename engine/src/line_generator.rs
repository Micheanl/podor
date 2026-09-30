use crate::{
    assistants::Point,
    groups, masks,
    model::*,
    vector::{self, Cap, FillRule, GeometrySpec, ObjectSpec, SegmentSpec, StrokeStyle, Style},
};
use serde::Deserialize;
use std::collections::BTreeSet;

#[derive(Clone, Deserialize)]
pub struct Common {
    pub seed: u32,
    pub count: usize,
    pub stroke: StrokeStyle,
    pub opacity: f32,
    pub randomness: f64,
    pub taper_start: f64,
    pub taper_end: f64,
}

#[derive(Clone, Copy, Deserialize)]
pub struct Radii {
    pub rx: f64,
    pub ry: f64,
}

#[derive(Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Settings {
    Concentration {
        #[serde(flatten)]
        common: Common,
        center: Point,
        inner: Radii,
        outer: Radii,
        angle_start: f64,
        angle_sweep: f64,
    },
    Speed {
        #[serde(flatten)]
        common: Common,
        origin: Point,
        angle: f64,
        length: f64,
        spacing: f64,
    },
}

fn sample(seed: u32, index: usize, salt: u64) -> f64 {
    let mut value = u64::from(seed) ^ (index as u64).wrapping_mul(0x9e3779b97f4a7c15) ^ salt;
    value = value.wrapping_add(0x9e3779b97f4a7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^= value >> 31;
    (value >> 11) as f64 / 9_007_199_254_740_992.0
}

fn positive(value: f64) -> bool {
    value.is_finite() && value > 0.0 && value <= MAX_TRANSFORM_OFFSET
}

fn angle(value: f64) -> bool {
    value.is_finite() && (-360.0..=360.0).contains(&value)
}

fn finite(point: Point) -> bool {
    [point.x, point.y]
        .into_iter()
        .all(|value| value.is_finite() && value.abs() <= MAX_TRANSFORM_OFFSET)
}

fn offset(origin: Point, direction: Point, distance: f64) -> Point {
    Point {
        x: origin.x + direction.x * distance,
        y: origin.y + direction.y * distance,
    }
}

fn basis(angle: f64) -> (Point, Point) {
    let (y, x) = angle.to_radians().sin_cos();
    (Point { x, y }, Point { x: -y, y: x })
}

impl Common {
    fn validate(&self) -> Result<(), String> {
        if self.count == 0
            || self.count > MAX_GENERATED_LINES
            || !(0.0..=1.0).contains(&self.opacity)
            || !(0.0..=1.0).contains(&self.randomness)
            || !(0.0..=1.0).contains(&self.taper_start)
            || !(0.0..=1.0).contains(&self.taper_end)
            || !self.stroke.width.is_finite()
            || !self.stroke.miter_limit.is_finite()
            || !(1.0..=100.0).contains(&self.stroke.miter_limit)
        {
            return Err("线条生成参数无效".into());
        }
        let width = f64::from(self.stroke.width);
        if width * (1.0 - 0.5 * self.randomness) < f64::from(0.01f32)
            || width * (1.0 + 0.5 * self.randomness) > f64::from(MAX_DIMENSION)
        {
            return Err("生成线条宽度超出限制".into());
        }
        Ok(())
    }

    fn width(&self, index: usize) -> f64 {
        f64::from(self.stroke.width)
            * (1.0 - 0.5 * self.randomness
                + self.randomness * sample(self.seed, index, 0x7769647468))
    }

    fn object(&self, name: &str, index: usize, p: Point, q: Point) -> Result<ObjectSpec, String> {
        if !finite(p) || !finite(q) || (p.x as f32, p.y as f32) == (q.x as f32, q.y as f32) {
            return Err("生成线条坐标超出限制或长度退化".into());
        }
        let width = self.width(index);
        let mut stroke = self.stroke.clone();
        stroke.width = width as f32;
        stroke.color[3] = (f32::from(stroke.color[3]) * self.opacity).round() as u8;
        let uniform = self.taper_start == 1.0 && self.taper_end == 1.0;
        let geometry = if uniform {
            GeometrySpec::Line {
                x1: p.x as f32,
                y1: p.y as f32,
                x2: q.x as f32,
                y2: q.y as f32,
            }
        } else {
            let dx = q.x - p.x;
            let dy = q.y - p.y;
            let length = dx.hypot(dy);
            let normal = Point {
                x: -dy / length,
                y: dx / length,
            };
            let point = |t: f64, half_width: f64| {
                offset(
                    Point {
                        x: p.x + dx * t,
                        y: p.y + dy * t,
                    },
                    normal,
                    half_width,
                )
            };
            let h = width / 2.0;
            let first = h * self.taper_start;
            let last = h * self.taper_end;
            let cubic = |t1: f64, h1: f64, t2: f64, h2: f64, t: f64, h: f64| {
                let a = point(t1, h1);
                let b = point(t2, h2);
                let end = point(t, h);
                SegmentSpec::CubicTo {
                    c1x: a.x as f32,
                    c1y: a.y as f32,
                    c2x: b.x as f32,
                    c2y: b.y as f32,
                    x: end.x as f32,
                    y: end.y as f32,
                }
            };
            let start = point(0.0, first);
            let end = point(1.0, -last);
            GeometrySpec::Path {
                segments: vec![
                    SegmentSpec::MoveTo {
                        x: start.x as f32,
                        y: start.y as f32,
                    },
                    cubic(1.0 / 6.0, (first + 2.0 * h) / 3.0, 1.0 / 3.0, h, 0.5, h),
                    cubic(2.0 / 3.0, h, 5.0 / 6.0, (last + 2.0 * h) / 3.0, 1.0, last),
                    SegmentSpec::LineTo {
                        x: end.x as f32,
                        y: end.y as f32,
                    },
                    cubic(5.0 / 6.0, -(last + 2.0 * h) / 3.0, 2.0 / 3.0, -h, 0.5, -h),
                    cubic(
                        1.0 / 3.0,
                        -h,
                        1.0 / 6.0,
                        -(first + 2.0 * h) / 3.0,
                        0.0,
                        -first,
                    ),
                    SegmentSpec::Close,
                ],
            }
        };
        Ok(ObjectSpec {
            name: format!("{name} {:03}", index + 1),
            visible: true,
            geometry,
            transform: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
            style: Style {
                fill: (!uniform).then_some(stroke.color),
                stroke: uniform.then_some(stroke),
                fill_rule: FillRule::NonZero,
            },
        })
    }
}

impl Settings {
    pub fn objects(&self) -> Result<Vec<ObjectSpec>, String> {
        let common = match self {
            Self::Concentration { common, .. } | Self::Speed { common, .. } => common,
        };
        common.validate()?;
        let mut objects = Vec::with_capacity(common.count);
        let mut endpoints = BTreeSet::new();
        let mut previous_lane = None;
        for index in 0..common.count {
            let (name, p, q) = match *self {
                Self::Concentration {
                    center,
                    inner,
                    outer,
                    angle_start,
                    angle_sweep,
                    ..
                } => {
                    if !finite(center)
                        || ![inner.rx, inner.ry, outer.rx, outer.ry]
                            .into_iter()
                            .all(positive)
                        || inner.rx >= outer.rx
                        || inner.ry >= outer.ry
                        || !angle(angle_start)
                        || !angle_sweep.is_finite()
                        || !(0.0..=360.0).contains(&angle_sweep)
                        || angle_sweep == 0.0
                    {
                        return Err("集中线椭圆或角度无效".into());
                    }
                    let slot = index as f64
                        + 0.5
                        + common.randomness
                            * 0.45
                            * (2.0 * sample(common.seed, index, 0x616e676c65) - 1.0);
                    let (u, _) = basis(angle_start + slot * angle_sweep / common.count as f64);
                    let width = common.width(index);
                    let uniform_square = common.taper_start == 1.0
                        && common.taper_end == 1.0
                        && common.stroke.cap == Cap::Square;
                    let margin = width / 2.0
                        * if uniform_square {
                            std::f64::consts::SQRT_2
                        } else {
                            1.0
                        }
                        + 1.0;
                    let radius = |radii: Radii| 1.0 / (u.x / radii.rx).hypot(u.y / radii.ry);
                    let start = radius(inner) * (1.0 + margin / inner.rx.min(inner.ry));
                    let end = radius(outer) * (1.0 - margin / outer.rx.min(outer.ry));
                    let gap = end - start;
                    if !gap.is_finite() || gap <= 0.0 {
                        return Err("集中线内外椭圆间距不足，请扩大范围或减小宽度".into());
                    }
                    let start = start
                        + 0.2 * common.randomness * sample(common.seed, index, 0x7374617274) * gap;
                    let end =
                        end - 0.2 * common.randomness * sample(common.seed, index, 0x656e64) * gap;
                    (
                        "Concentration",
                        offset(center, u, start),
                        offset(center, u, end),
                    )
                }
                Self::Speed {
                    origin,
                    angle: degrees,
                    length,
                    spacing,
                    ..
                } => {
                    if !finite(origin) || !angle(degrees) || !positive(length) || !positive(spacing)
                    {
                        return Err("速度线方向、长度或间距无效".into());
                    }
                    let (u, n) = basis(degrees);
                    let lane = (index as f64 - (common.count - 1) as f64 / 2.0
                        + common.randomness
                            * 0.45
                            * (2.0 * sample(common.seed, index, 0x6c61746572616c) - 1.0))
                        * spacing;
                    let start = 0.25
                        * common.randomness
                        * (2.0 * sample(common.seed, index, 0x7374617274) - 1.0)
                        * length;
                    let length = length
                        * (1.0 - 0.5 * common.randomness
                            + common.randomness * sample(common.seed, index, 0x6c656e677468));
                    let p = offset(offset(origin, n, lane), u, start);
                    let actual_lane = (f64::from(p.x as f32) - origin.x) * n.x
                        + (f64::from(p.y as f32) - origin.y) * n.y;
                    if previous_lane.is_some_and(|previous| actual_lane <= previous) {
                        return Err("速度线间距不足以表示独立线条".into());
                    }
                    previous_lane = Some(actual_lane);
                    ("Speed", p, offset(p, u, length))
                }
            };
            let key = [p.x as f32, p.y as f32, q.x as f32, q.y as f32].map(f32::to_bits);
            if !endpoints.insert(key) {
                return Err("生成线条端点重复".into());
            }
            objects.push(common.object(name, index, p, q)?);
        }
        Ok(objects)
    }
}

pub fn prepare(
    doc: &Document,
    name: String,
    parent_id: Option<u32>,
    index: usize,
    settings: Settings,
) -> Result<Document, String> {
    if doc.palette.is_some() {
        return Err("线条生成器暂仅支持 RGBA 工程".into());
    }
    if doc.layers.len() >= MAX_LAYER_NODES
        || doc
            .layers
            .iter()
            .filter(|layer| layer.raster_opt().is_some() || layer.is_vector())
            .count()
            >= MAX_LAYERS
    {
        return Err("已达到图层上限".into());
    }
    if let Some(parent) = parent_id {
        groups::check_editable(doc, parent, false)?;
    }
    let plan = groups::Hierarchy::new(doc)?;
    let siblings = match parent_id {
        Some(id) => {
            &plan.children[doc
                .layers
                .iter()
                .position(|layer| layer.id == id)
                .ok_or("父级图层不存在")?]
        }
        None => &plan.roots,
    };
    if index > siblings.len() {
        return Err("生成图层位置无效".into());
    }
    if siblings
        .get(index)
        .is_some_and(|&node| doc.layers[node].clipping)
    {
        return Err("请在完整剪贴链之外创建生成线条".into());
    }
    let objects = settings.objects()?;
    let vector = vector::batch(objects)?;
    let mut next = groups::create(doc, name, parent_id, index, GroupIsolation::Isolated)?;
    next.active_mut().content = LayerContent::Vector(vector);
    groups::expand_active_ancestors(&mut next)?;
    masks::check_transaction(doc, &next)?;
    Ok(next)
}
