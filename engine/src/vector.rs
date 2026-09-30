use crate::{groups, masks, model::*};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    mem::size_of,
    sync::{Arc, Weak},
};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct VectorLayer {
    pub next_object_id: u32,
    pub objects: Vec<Arc<VectorObject>>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct VectorObject {
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub geometry: Geometry,
    pub transform: [f32; 6],
    pub style: Style,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum Geometry {
    Rect {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
    },
    Ellipse {
        cx: f32,
        cy: f32,
        rx: f32,
        ry: f32,
    },
    Line {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
    },
    Path {
        segments: Vec<Segment>,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum Segment {
    MoveTo {
        x: f32,
        y: f32,
    },
    LineTo {
        x: f32,
        y: f32,
    },
    QuadTo {
        cx: f32,
        cy: f32,
        x: f32,
        y: f32,
    },
    CubicTo {
        c1x: f32,
        c1y: f32,
        c2x: f32,
        c2y: f32,
        x: f32,
        y: f32,
    },
    Close,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Style {
    pub fill: Option<[u8; 4]>,
    pub stroke: Option<StrokeStyle>,
    pub fill_rule: FillRule,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct StrokeStyle {
    pub color: [u8; 4],
    pub width: f32,
    pub cap: Cap,
    pub join: Join,
    pub miter_limit: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FillRule {
    NonZero,
    EvenOdd,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Cap {
    Butt,
    Round,
    Square,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Join {
    Miter,
    Round,
    Bevel,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ObjectSpec {
    pub name: String,
    pub visible: bool,
    pub geometry: GeometrySpec,
    pub transform: [f32; 6],
    pub style: Style,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum GeometrySpec {
    Rect {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
    },
    Ellipse {
        cx: f32,
        cy: f32,
        rx: f32,
        ry: f32,
    },
    Line {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
    },
    Path {
        segments: Vec<SegmentSpec>,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SegmentSpec {
    MoveTo {
        x: f32,
        y: f32,
    },
    LineTo {
        x: f32,
        y: f32,
    },
    QuadTo {
        cx: f32,
        cy: f32,
        x: f32,
        y: f32,
    },
    CubicTo {
        c1x: f32,
        c1y: f32,
        c2x: f32,
        c2y: f32,
        x: f32,
        y: f32,
    },
    Close,
}

impl From<SegmentSpec> for Segment {
    fn from(value: SegmentSpec) -> Self {
        match value {
            SegmentSpec::MoveTo { x, y } => Self::MoveTo { x, y },
            SegmentSpec::LineTo { x, y } => Self::LineTo { x, y },
            SegmentSpec::QuadTo { cx, cy, x, y } => Self::QuadTo { cx, cy, x, y },
            SegmentSpec::CubicTo {
                c1x,
                c1y,
                c2x,
                c2y,
                x,
                y,
            } => Self::CubicTo {
                c1x,
                c1y,
                c2x,
                c2y,
                x,
                y,
            },
            SegmentSpec::Close => Self::Close,
        }
    }
}

impl From<&Segment> for SegmentSpec {
    fn from(value: &Segment) -> Self {
        match *value {
            Segment::MoveTo { x, y } => Self::MoveTo { x, y },
            Segment::LineTo { x, y } => Self::LineTo { x, y },
            Segment::QuadTo { cx, cy, x, y } => Self::QuadTo { cx, cy, x, y },
            Segment::CubicTo {
                c1x,
                c1y,
                c2x,
                c2y,
                x,
                y,
            } => Self::CubicTo {
                c1x,
                c1y,
                c2x,
                c2y,
                x,
                y,
            },
            Segment::Close => Self::Close,
        }
    }
}

impl From<GeometrySpec> for Geometry {
    fn from(value: GeometrySpec) -> Self {
        match value {
            GeometrySpec::Rect {
                x,
                y,
                width,
                height,
            } => Self::Rect {
                x,
                y,
                width,
                height,
            },
            GeometrySpec::Ellipse { cx, cy, rx, ry } => Self::Ellipse { cx, cy, rx, ry },
            GeometrySpec::Line { x1, y1, x2, y2 } => Self::Line { x1, y1, x2, y2 },
            GeometrySpec::Path { segments } => Self::Path {
                segments: segments.into_iter().map(Into::into).collect(),
            },
        }
    }
}

impl From<&Geometry> for GeometrySpec {
    fn from(value: &Geometry) -> Self {
        match value {
            Geometry::Rect {
                x,
                y,
                width,
                height,
            } => Self::Rect {
                x: *x,
                y: *y,
                width: *width,
                height: *height,
            },
            Geometry::Ellipse { cx, cy, rx, ry } => Self::Ellipse {
                cx: *cx,
                cy: *cy,
                rx: *rx,
                ry: *ry,
            },
            Geometry::Line { x1, y1, x2, y2 } => Self::Line {
                x1: *x1,
                y1: *y1,
                x2: *x2,
                y2: *y2,
            },
            Geometry::Path { segments } => Self::Path {
                segments: segments.iter().map(Into::into).collect(),
            },
        }
    }
}

impl ObjectSpec {
    fn object(self, id: u32) -> VectorObject {
        VectorObject {
            id,
            name: self.name,
            visible: self.visible,
            geometry: self.geometry.into(),
            transform: self.transform,
            style: self.style,
        }
    }
}

impl VectorObject {
    pub fn spec(&self) -> ObjectSpec {
        ObjectSpec {
            name: self.name.clone(),
            visible: self.visible,
            geometry: (&self.geometry).into(),
            transform: self.transform,
            style: self.style.clone(),
        }
    }

    pub fn bytes(&self) -> usize {
        size_of::<Self>()
            + size_of::<usize>() * 2
            + self.name.capacity()
            + match &self.geometry {
                Geometry::Path { segments } => segments.capacity() * size_of::<Segment>(),
                _ => 0,
            }
    }

    pub fn kind(&self) -> &'static str {
        match self.geometry {
            Geometry::Rect { .. } => "rect",
            Geometry::Ellipse { .. } => "ellipse",
            Geometry::Line { .. } => "line",
            Geometry::Path { .. } => "path",
        }
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.id == 0
            || self.name.is_empty()
            || self.name.len() > MAX_LAYER_NAME_BYTES
            || self
                .transform
                .iter()
                .any(|v| !v.is_finite() || v.abs() > MAX_TRANSFORM_OFFSET as f32)
            || !affine(self.transform).is_valid()
            || (self.transform[0] * self.transform[3] - self.transform[1] * self.transform[2]).abs()
                < 1e-8
            || self.style.fill.is_none() && self.style.stroke.is_none()
        {
            return Err("矢量对象属性无效".into());
        }
        if let Some(stroke) = &self.style.stroke {
            if !stroke.width.is_finite()
                || !(0.01..=MAX_DIMENSION as f32).contains(&stroke.width)
                || !stroke.miter_limit.is_finite()
                || !(1.0..=100.0).contains(&stroke.miter_limit)
            {
                return Err("矢量描边参数无效".into());
            }
        }
        let prepared = PreparedObject::new(self)?;
        if prepared.bounds.is_some_and(|b| {
            [b.left(), b.top(), b.right(), b.bottom()]
                .iter()
                .any(|v| !v.is_finite() || v.abs() > MAX_TRANSFORM_OFFSET as f32)
        }) {
            return Err("矢量对象坐标超出限制".into());
        }
        Ok(())
    }
}

impl VectorLayer {
    pub fn new() -> Self {
        Self {
            next_object_id: 1,
            objects: Vec::new(),
        }
    }

    pub fn own_bytes(&self) -> usize {
        size_of::<Self>()
            + size_of::<usize>() * 2
            + self.objects.capacity() * size_of::<Arc<VectorObject>>()
    }

    pub fn bytes(&self) -> usize {
        self.own_bytes()
            + self
                .objects
                .iter()
                .map(|object| object.bytes())
                .sum::<usize>()
    }

    pub fn segment_count(&self) -> usize {
        self.objects
            .iter()
            .map(|object| match &object.geometry {
                Geometry::Path { segments } => segments.len(),
                _ => 0,
            })
            .sum()
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.objects.len() > MAX_VECTOR_OBJECTS || self.next_object_id == 0 {
            return Err("矢量对象数量超出限制".into());
        }
        let mut seen = BTreeSet::new();
        for object in &self.objects {
            if object.id >= self.next_object_id || !seen.insert(object.id) {
                return Err("矢量对象编号无效".into());
            }
            object.validate()?;
        }
        Ok(())
    }
}

impl Default for VectorLayer {
    fn default() -> Self {
        Self::new()
    }
}

pub(crate) fn batch(objects: Vec<ObjectSpec>) -> Result<Arc<VectorLayer>, String> {
    if objects.is_empty() || objects.len() > MAX_VECTOR_OBJECTS {
        return Err("矢量对象数量超出限制".into());
    }
    let count = objects.len();
    let objects = objects
        .into_iter()
        .enumerate()
        .map(|(index, object)| Arc::new(object.object(index as u32 + 1)))
        .collect();
    let vector = Arc::new(VectorLayer {
        next_object_id: count as u32 + 1,
        objects,
    });
    vector.validate()?;
    Ok(vector)
}

pub fn affine(value: [f32; 6]) -> tiny_skia::Transform {
    tiny_skia::Transform::from_row(value[0], value[1], value[2], value[3], value[4], value[5])
}

fn coordinate(values: &[f32]) -> Result<(), String> {
    if values
        .iter()
        .any(|value| !value.is_finite() || value.abs() > MAX_TRANSFORM_OFFSET as f32)
    {
        Err("矢量路径坐标无效".into())
    } else {
        Ok(())
    }
}

fn path(geometry: &Geometry) -> Result<tiny_skia::Path, String> {
    let mut builder = tiny_skia::PathBuilder::new();
    match geometry {
        Geometry::Rect {
            x,
            y,
            width,
            height,
        } => {
            coordinate(&[*x, *y, *width, *height])?;
            if *width <= 0.0 || *height <= 0.0 {
                return Err("矩形尺寸无效".into());
            }
            builder.push_rect(
                tiny_skia::Rect::from_xywh(*x, *y, *width, *height).ok_or("矩形尺寸无效")?,
            );
        }
        Geometry::Ellipse { cx, cy, rx, ry } => {
            coordinate(&[*cx, *cy, *rx, *ry])?;
            if *rx <= 0.0 || *ry <= 0.0 {
                return Err("椭圆尺寸无效".into());
            }
            builder.push_oval(
                tiny_skia::Rect::from_xywh(cx - rx, cy - ry, rx * 2.0, ry * 2.0)
                    .ok_or("椭圆尺寸无效")?,
            );
        }
        Geometry::Line { x1, y1, x2, y2 } => {
            coordinate(&[*x1, *y1, *x2, *y2])?;
            if x1 == x2 && y1 == y2 {
                return Err("直线长度无效".into());
            }
            builder.move_to(*x1, *y1);
            builder.line_to(*x2, *y2);
        }
        Geometry::Path { segments } => {
            if segments.len() < 2 || segments.len() > MAX_VECTOR_SEGMENTS {
                return Err("矢量路径节点数量无效".into());
            }
            let mut open = false;
            let mut drawn = false;
            for segment in segments {
                match *segment {
                    Segment::MoveTo { x, y } => {
                        coordinate(&[x, y])?;
                        if open && !drawn {
                            return Err("矢量路径轮廓无效".into());
                        }
                        builder.move_to(x, y);
                        open = true;
                        drawn = false;
                    }
                    Segment::LineTo { x, y } => {
                        coordinate(&[x, y])?;
                        if !open {
                            return Err("路径必须从起点开始".into());
                        }
                        builder.line_to(x, y);
                        drawn = true;
                    }
                    Segment::QuadTo { cx, cy, x, y } => {
                        coordinate(&[cx, cy, x, y])?;
                        if !open {
                            return Err("路径必须从起点开始".into());
                        }
                        builder.quad_to(cx, cy, x, y);
                        drawn = true;
                    }
                    Segment::CubicTo {
                        c1x,
                        c1y,
                        c2x,
                        c2y,
                        x,
                        y,
                    } => {
                        coordinate(&[c1x, c1y, c2x, c2y, x, y])?;
                        if !open {
                            return Err("路径必须从起点开始".into());
                        }
                        builder.cubic_to(c1x, c1y, c2x, c2y, x, y);
                        drawn = true;
                    }
                    Segment::Close => {
                        if !open || !drawn {
                            return Err("矢量路径闭合无效".into());
                        }
                        builder.close();
                        open = false;
                    }
                }
            }
            if open && !drawn {
                return Err("矢量路径轮廓无效".into());
            }
        }
    }
    builder.finish().ok_or_else(|| "矢量路径无效".into())
}

pub(crate) struct PreparedObject {
    fill: tiny_skia::Path,
    stroke: Option<tiny_skia::Path>,
    pub bounds: Option<tiny_skia::Rect>,
}

fn append_render_line(
    builder: &mut tiny_skia::PathBuilder,
    remaining: &mut usize,
    from: kurbo::Point,
    to: kurbo::Point,
) -> bool {
    let steps = (from.distance(to) / VECTOR_MAX_RENDER_EDGE).ceil().max(1.0) as usize;
    if steps > *remaining {
        *remaining = 0;
        return false;
    }
    for index in 1..=steps {
        let p = from.lerp(to, index as f64 / steps as f64);
        builder.line_to(
            ((p.x * 256.0).round() / 256.0) as f32,
            ((p.y * 256.0).round() / 256.0) as f32,
        );
    }
    *remaining -= steps;
    true
}

fn stable_outline(path: tiny_skia::Path, remaining: &mut usize) -> Result<tiny_skia::Path, String> {
    let point = |value: tiny_skia::Point| kurbo::Point::new(f64::from(value.x), f64::from(value.y));
    let elements = path.segments().map(|segment| match segment {
        tiny_skia::PathSegment::MoveTo(p) => kurbo::PathEl::MoveTo(point(p)),
        tiny_skia::PathSegment::LineTo(p) => kurbo::PathEl::LineTo(point(p)),
        tiny_skia::PathSegment::QuadTo(c, p) => kurbo::PathEl::QuadTo(point(c), point(p)),
        tiny_skia::PathSegment::CubicTo(c1, c2, p) => {
            kurbo::PathEl::CurveTo(point(c1), point(c2), point(p))
        }
        tiny_skia::PathSegment::Close => kurbo::PathEl::ClosePath,
    });
    let mut builder = tiny_skia::PathBuilder::new();
    let mut exceeded = false;
    let mut open = false;
    let mut first = kurbo::Point::ZERO;
    let mut previous = kurbo::Point::ZERO;
    kurbo::flatten(elements, VECTOR_FLATTEN_TOLERANCE, |element| {
        if exceeded || *remaining == 0 {
            exceeded = true;
            return;
        }
        match element {
            kurbo::PathEl::MoveTo(p) => {
                if open {
                    exceeded |= !append_render_line(&mut builder, remaining, previous, first);
                    builder.close();
                }
                if *remaining == 0 {
                    exceeded = true;
                    return;
                }
                builder.move_to(
                    ((p.x * 256.0).round() / 256.0) as f32,
                    ((p.y * 256.0).round() / 256.0) as f32,
                );
                first = p;
                previous = p;
                open = true;
                *remaining -= 1;
            }
            kurbo::PathEl::LineTo(p) => {
                exceeded |= !append_render_line(&mut builder, remaining, previous, p);
                previous = p;
            }
            kurbo::PathEl::ClosePath => {
                exceeded |= !append_render_line(&mut builder, remaining, previous, first);
                builder.close();
                open = false;
            }
            _ => unreachable!(),
        }
    });
    if open {
        exceeded |= !append_render_line(&mut builder, remaining, previous, first);
        builder.close();
    }
    if exceeded {
        return Err("矢量曲线展开超出渲染预算".into());
    }
    builder.finish().ok_or_else(|| "矢量曲线展开无效".into())
}

impl PreparedObject {
    fn new(object: &VectorObject) -> Result<Self, String> {
        let source = path(&object.geometry)?;
        let transform = affine(object.transform);
        let stroke = match &object.style.stroke {
            Some(style) => {
                let mut settings = tiny_skia::Stroke {
                    width: style.width,
                    miter_limit: style.miter_limit,
                    line_cap: match style.cap {
                        Cap::Butt => tiny_skia::LineCap::Butt,
                        Cap::Round => tiny_skia::LineCap::Round,
                        Cap::Square => tiny_skia::LineCap::Square,
                    },
                    line_join: match style.join {
                        Join::Miter => tiny_skia::LineJoin::Miter,
                        Join::Round => tiny_skia::LineJoin::Round,
                        Join::Bevel => tiny_skia::LineJoin::Bevel,
                    },
                    ..Default::default()
                };
                settings.dash = None;
                let scale = transform.get_scale();
                source
                    .stroke(&settings, scale.0.max(scale.1).max(1.0))
                    .and_then(|path| path.transform(transform))
            }
            None => None,
        };
        let fill = source.transform(transform).ok_or("矢量变换无效")?;
        for bound in std::iter::once(fill.bounds()).chain(stroke.as_ref().map(|path| path.bounds()))
        {
            if [bound.left(), bound.top(), bound.right(), bound.bottom()]
                .iter()
                .any(|value| !value.is_finite() || value.abs() > MAX_TRANSFORM_OFFSET as f32)
            {
                return Err("矢量对象坐标超出限制".into());
            }
        }
        let mut bounds = object
            .style
            .fill
            .filter(|color| color[3] > 0)
            .and_then(|_| fill.compute_tight_bounds());
        if object
            .style
            .stroke
            .as_ref()
            .is_some_and(|style| style.color[3] > 0)
        {
            if let Some(stroke) = &stroke {
                bounds = match bounds {
                    Some(bound) => bound.join(&stroke.bounds()),
                    None => Some(stroke.bounds()),
                };
            }
        }
        let mut remaining = MAX_VECTOR_RENDER_SEGMENTS;
        let fill = stable_outline(fill, &mut remaining)?;
        let stroke = stroke
            .map(|path| stable_outline(path, &mut remaining))
            .transpose()?;
        let prepared = Self {
            fill,
            stroke,
            bounds,
        };
        if prepared.bytes() > MAX_VECTOR_CACHE_BYTES {
            return Err("矢量曲线展开超出渲染预算".into());
        }
        Ok(prepared)
    }

    fn bytes(&self) -> usize {
        let size = |path: &tiny_skia::Path| {
            std::mem::size_of_val(path.points()) + std::mem::size_of_val(path.verbs())
        };
        size_of::<Self>() + 2 * (size(&self.fill) + self.stroke.as_ref().map_or(0, size))
    }

    fn draw(
        &self,
        object: &VectorObject,
        pixels: &mut tiny_skia::PixmapMut<'_>,
        offset_x: f32,
        offset_y: f32,
    ) {
        let transform = tiny_skia::Transform::from_translate(offset_x, offset_y);
        if let Some(color) = object.style.fill {
            let mut paint = tiny_skia::Paint::default();
            paint.set_color_rgba8(color[0], color[1], color[2], color[3]);
            pixels.fill_path(
                &self.fill,
                &paint,
                match object.style.fill_rule {
                    FillRule::NonZero => tiny_skia::FillRule::Winding,
                    FillRule::EvenOdd => tiny_skia::FillRule::EvenOdd,
                },
                transform,
                None,
            );
        }
        if let (Some(style), Some(stroke)) = (&object.style.stroke, &self.stroke) {
            let mut paint = tiny_skia::Paint::default();
            paint.set_color_rgba8(
                style.color[0],
                style.color[1],
                style.color[2],
                style.color[3],
            );
            pixels.fill_path(
                stroke,
                &paint,
                tiny_skia::FillRule::Winding,
                transform,
                None,
            );
        }
    }
}

pub fn object_bounds(object: &VectorObject) -> Option<[f32; 4]> {
    PreparedObject::new(object)
        .ok()?
        .bounds
        .map(|bounds| [bounds.left(), bounds.top(), bounds.right(), bounds.bottom()])
}

pub fn bounds(vector: &VectorLayer, canvas: Rect) -> Option<Rect> {
    vector
        .objects
        .iter()
        .filter(|object| object.visible)
        .filter_map(|object| object_bounds(object))
        .filter_map(|bound| clipped_bounds(bound, canvas, 0.0))
        .reduce(|a, b| Rect {
            left: a.left.min(b.left),
            top: a.top.min(b.top),
            right: a.right.max(b.right),
            bottom: a.bottom.max(b.bottom),
        })
}

fn clipped_bounds(bound: [f32; 4], canvas: Rect, margin: f32) -> Option<Rect> {
    let rect = Rect {
        left: (bound[0] - margin)
            .floor()
            .clamp(canvas.left as f32, canvas.right as f32) as u32,
        top: (bound[1] - margin)
            .floor()
            .clamp(canvas.top as f32, canvas.bottom as f32) as u32,
        right: (bound[2] + margin)
            .ceil()
            .clamp(canvas.left as f32, canvas.right as f32) as u32,
        bottom: (bound[3] + margin)
            .ceil()
            .clamp(canvas.top as f32, canvas.bottom as f32) as u32,
    };
    (rect.left < rect.right && rect.top < rect.bottom).then_some(rect)
}

pub fn keys(vector: &VectorLayer, canvas: Rect) -> BTreeSet<TileKey> {
    let mut keys = BTreeSet::new();
    for area in vector
        .objects
        .iter()
        .filter(|object| object.visible)
        .filter_map(|object| object_bounds(object))
        .filter_map(|bound| clipped_bounds(bound, canvas, 2.0))
    {
        for y in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
            for x in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
                keys.insert((x, y));
            }
        }
    }
    keys
}

pub(crate) fn changed_keys(
    before: &VectorLayer,
    after: &VectorLayer,
    canvas: Rect,
) -> BTreeSet<TileKey> {
    let mut keys = BTreeSet::new();
    for (position, object) in before.objects.iter().enumerate() {
        if after
            .objects
            .get(position)
            .is_some_and(|next| next.as_ref() == object.as_ref())
        {
            continue;
        }
        if let Some(area) =
            object_bounds(object).and_then(|bounds| clipped_bounds(bounds, canvas, 2.0))
        {
            for y in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
                for x in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
                    keys.insert((x, y));
                }
            }
        }
    }
    for (position, object) in after.objects.iter().enumerate() {
        if before
            .objects
            .get(position)
            .is_some_and(|old| old.as_ref() == object.as_ref())
        {
            continue;
        }
        if let Some(area) =
            object_bounds(object).and_then(|bounds| clipped_bounds(bounds, canvas, 2.0))
        {
            for y in area.top / TILE_SIZE..area.bottom.div_ceil(TILE_SIZE) {
                for x in area.left / TILE_SIZE..area.right.div_ceil(TILE_SIZE) {
                    keys.insert((x, y));
                }
            }
        }
    }
    keys
}

struct PreparedEntry {
    object: Weak<VectorObject>,
    prepared: Arc<PreparedObject>,
    bytes: usize,
}
struct TileEntry {
    vector: Weak<VectorLayer>,
    key: TileKey,
    pixels: Tile,
}

#[derive(Default)]
pub(crate) struct RenderCache {
    paths: VecDeque<PreparedEntry>,
    path_bytes: usize,
    tiles: VecDeque<TileEntry>,
}

impl RenderCache {
    pub fn discard_expired(&mut self) {
        self.paths.retain(|entry| entry.object.strong_count() != 0);
        self.path_bytes = self.paths.iter().map(|entry| entry.bytes).sum();
        self.tiles.retain(|entry| entry.vector.strong_count() != 0);
    }

    fn path_storage_bytes(&self) -> usize {
        self.path_bytes + self.paths.capacity() * size_of::<PreparedEntry>()
    }

    fn tile_storage_bytes(&self) -> usize {
        self.tiles.len()
            * (TILE_BYTES
                + size_of::<Vec<u8>>()
                + size_of::<VectorLayer>()
                + size_of::<usize>() * 4)
            + self.tiles.capacity() * size_of::<TileEntry>()
    }

    fn prepared(&mut self, object: &Arc<VectorObject>) -> Arc<PreparedObject> {
        if let Some(index) = self
            .paths
            .iter()
            .position(|entry| entry.object.ptr_eq(&Arc::downgrade(object)))
        {
            let entry = self.paths.remove(index).unwrap();
            let prepared = entry.prepared.clone();
            self.paths.push_back(entry);
            return prepared;
        }
        let prepared = Arc::new(PreparedObject::new(object).expect("无效的矢量对象"));
        let bytes = prepared.bytes() + size_of::<VectorObject>() + size_of::<usize>() * 4;
        let required = |cache: &Self| {
            cache.path_storage_bytes()
                + bytes
                + (cache
                    .paths
                    .capacity()
                    .max(cache.paths.len() + 1)
                    .next_power_of_two()
                    - cache.paths.capacity())
                    * size_of::<PreparedEntry>()
        };
        while required(self) > MAX_VECTOR_CACHE_BYTES && !self.paths.is_empty() {
            self.path_bytes -= self.paths.pop_front().unwrap().bytes;
        }
        if required(self) <= MAX_VECTOR_CACHE_BYTES {
            self.paths.reserve_exact(1);
            self.path_bytes += bytes;
            self.paths.push_back(PreparedEntry {
                object: Arc::downgrade(object),
                prepared: prepared.clone(),
                bytes,
            });
        }
        prepared
    }

    pub fn tile(&mut self, vector: &Arc<VectorLayer>, key: TileKey) -> Option<Tile> {
        if let Some(index) = self
            .tiles
            .iter()
            .position(|entry| entry.key == key && entry.vector.ptr_eq(&Arc::downgrade(vector)))
        {
            let entry = self.tiles.remove(index).unwrap();
            let pixels = entry.pixels.clone();
            self.tiles.push_back(entry);
            return Some(pixels);
        }
        let edge = TILE_SIZE + 4;
        let mut workspace = vec![0; (edge * edge * 4) as usize];
        let mut pixmap = tiny_skia::PixmapMut::from_bytes(&mut workspace, edge, edge).unwrap();
        let left = (key.0 * TILE_SIZE) as f32;
        let top = (key.1 * TILE_SIZE) as f32;
        for object in vector.objects.iter().filter(|object| object.visible) {
            let prepared = self.prepared(object);
            if prepared.bounds.is_some_and(|bound| {
                bound.right() >= left - 2.0
                    && bound.bottom() >= top - 2.0
                    && bound.left() <= left + TILE_SIZE as f32 + 2.0
                    && bound.top() <= top + TILE_SIZE as f32 + 2.0
            }) {
                prepared.draw(object, &mut pixmap, 2.0 - left, 2.0 - top);
            }
        }
        let mut pixels = vec![0; TILE_BYTES];
        for row in 0..TILE_SIZE as usize {
            let source = ((row + 2) * edge as usize + 2) * 4;
            pixels[row * TILE_SIZE as usize * 4..(row + 1) * TILE_SIZE as usize * 4]
                .copy_from_slice(&workspace[source..source + TILE_SIZE as usize * 4]);
        }
        if pixels.as_chunks::<4>().0.iter().all(|pixel| pixel[3] == 0) {
            return None;
        }
        let pixels = Arc::new(pixels);
        let required = |cache: &Self| {
            cache.tile_storage_bytes()
                + TILE_BYTES
                + size_of::<Vec<u8>>()
                + size_of::<VectorLayer>()
                + size_of::<usize>() * 4
                + (cache.tiles.len() + 1).saturating_sub(cache.tiles.capacity())
                    * size_of::<TileEntry>()
        };
        while required(self) > MAX_VECTOR_CACHE_BYTES && !self.tiles.is_empty() {
            self.tiles.pop_front();
        }
        self.tiles.reserve_exact(1);
        self.tiles.push_back(TileEntry {
            vector: Arc::downgrade(vector),
            key,
            pixels: pixels.clone(),
        });
        Some(pixels)
    }
}

#[derive(Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Edit {
    Add {
        object: ObjectSpec,
        #[serde(default)]
        index: Option<usize>,
    },
    Set {
        object_id: u32,
        object: ObjectSpec,
    },
    Delete {
        object_id: u32,
    },
    Reorder {
        object_id: u32,
        index: usize,
    },
}

pub fn prepare_edit(doc: &Document, id: u32, edit: Edit) -> Result<Document, String> {
    let index = groups::check_editable(doc, id, false)?;
    if doc.palette.is_some() {
        return Err("矢量图层暂仅支持 RGBA 工程".into());
    }
    let mut vector = doc.layers[index].vector()?.as_ref().clone();
    match edit {
        Edit::Add { object, index } => {
            if vector.objects.len() >= MAX_VECTOR_OBJECTS {
                return Err("矢量对象数量超出限制".into());
            }
            let index = index.unwrap_or(vector.objects.len());
            if index > vector.objects.len() {
                return Err("矢量对象位置无效".into());
            }
            let object = object.object(vector.next_object_id);
            object.validate()?;
            vector.next_object_id = vector
                .next_object_id
                .checked_add(1)
                .ok_or("矢量对象编号超出限制")?;
            vector.objects.insert(index, Arc::new(object));
        }
        Edit::Set { object_id, object } => {
            let index = vector
                .objects
                .iter()
                .position(|object| object.id == object_id)
                .ok_or("矢量对象不存在")?;
            let object = object.object(object_id);
            object.validate()?;
            if vector.objects[index].as_ref() == &object {
                return Ok(doc.clone());
            }
            vector.objects[index] = Arc::new(object);
        }
        Edit::Delete { object_id } => {
            let index = vector
                .objects
                .iter()
                .position(|object| object.id == object_id)
                .ok_or("矢量对象不存在")?;
            vector.objects.remove(index);
        }
        Edit::Reorder { object_id, index } => {
            let previous = vector
                .objects
                .iter()
                .position(|object| object.id == object_id)
                .ok_or("矢量对象不存在")?;
            if index >= vector.objects.len() {
                return Err("矢量对象位置无效".into());
            }
            if previous == index {
                return Ok(doc.clone());
            }
            let object = vector.objects.remove(previous);
            vector.objects.insert(index, object);
        }
    }
    let mut next = doc.clone();
    next.layers[index].content = LayerContent::Vector(Arc::new(vector));
    masks::check_transaction(doc, &next)?;
    Ok(next)
}

pub fn transformed(
    vector: &Arc<VectorLayer>,
    transform: tiny_skia::Transform,
) -> Result<Arc<VectorLayer>, String> {
    if transform.is_identity() {
        return Ok(vector.clone());
    }
    let mut next = vector.as_ref().clone();
    for object in &mut next.objects {
        let mut edited = object.as_ref().clone();
        let combined = affine(edited.transform).post_concat(transform);
        edited.transform = [
            combined.sx,
            combined.ky,
            combined.kx,
            combined.sy,
            combined.tx,
            combined.ty,
        ];
        edited.validate()?;
        *object = Arc::new(edited);
    }
    Ok(Arc::new(next))
}

pub fn transform_matrix(
    area: Rect,
    transform: crate::LayerTransform,
) -> Result<tiny_skia::Transform, String> {
    Document::new(transform.width, transform.height)?;
    if !transform.dx.is_finite()
        || !transform.dy.is_finite()
        || !transform.angle.is_finite()
        || transform.dx.abs() > MAX_TRANSFORM_OFFSET
        || transform.dy.abs() > MAX_TRANSFORM_OFFSET
        || transform.angle.abs() > 360.0
    {
        return Err("矢量变换参数无效".into());
    }
    let cx = (area.left + area.right) as f32 / 2.0;
    let cy = (area.top + area.bottom) as f32 / 2.0;
    let sx = transform.width as f32 / (area.right - area.left) as f32
        * if transform.flip_x { -1.0 } else { 1.0 };
    let sy = transform.height as f32 / (area.bottom - area.top) as f32
        * if transform.flip_y { -1.0 } else { 1.0 };
    let (sin, cos) = (transform.angle as f32).to_radians().sin_cos();
    Ok(tiny_skia::Transform::from_row(
        sx * cos,
        sx * sin,
        -sy * sin,
        sy * cos,
        cx + transform.dx as f32 - cx * sx * cos + cy * sy * sin,
        cy + transform.dy as f32 - cx * sx * sin - cy * sy * cos,
    ))
}

pub fn rasterize(doc: &Document, id: u32) -> Result<Document, String> {
    let index = groups::check_editable(doc, id, false)?;
    let vector = doc.layers[index].vector()?;
    let mut cache = RenderCache::default();
    let mut tiles = BTreeMap::new();
    for key in keys(vector, doc.bounds()) {
        if let Some(mut pixels) = cache.tile(vector, key) {
            let data = Arc::make_mut(&mut pixels);
            for y in 0..TILE_SIZE {
                for x in 0..TILE_SIZE {
                    if key.0 * TILE_SIZE + x >= doc.width || key.1 * TILE_SIZE + y >= doc.height {
                        data[((y * TILE_SIZE + x) * 4) as usize
                            ..((y * TILE_SIZE + x) * 4 + 4) as usize]
                            .fill(0);
                    }
                }
            }
            tiles.insert(key, pixels);
            if doc.pixel_bytes() - vector.bytes() + tiles.len() * TILE_BYTES > MAX_DOCUMENT_BYTES {
                return Err("栅格化会超出像素内存限制".into());
            }
        }
    }
    let mut next = doc.clone();
    next.layers[index].content = LayerContent::Raster(RasterPlane::Rgba(tiles));
    masks::check_transaction(doc, &next)?;
    Ok(next)
}

pub fn pick(vector: &VectorLayer, x: f32, y: f32, tolerance: f32) -> Result<Option<u32>, String> {
    if !x.is_finite()
        || !y.is_finite()
        || !tolerance.is_finite()
        || !(0.0..=16.0).contains(&tolerance)
    {
        return Err("矢量命中坐标无效".into());
    }
    let radius = tolerance.ceil() as u32;
    let edge = radius * 2 + 1;
    for object in vector.objects.iter().rev().filter(|object| object.visible) {
        let prepared = PreparedObject::new(object)?;
        if !prepared.bounds.is_some_and(|b| {
            b.right() + tolerance >= x
                && b.left() - tolerance <= x
                && b.bottom() + tolerance >= y
                && b.top() - tolerance <= y
        }) {
            continue;
        }
        let mut pixels = vec![0; (edge * edge * 4) as usize];
        let mut pixmap = tiny_skia::PixmapMut::from_bytes(&mut pixels, edge, edge).unwrap();
        prepared.draw(
            object,
            &mut pixmap,
            radius as f32 - x.floor(),
            radius as f32 - y.floor(),
        );
        if pixels.as_chunks::<4>().0.iter().any(|pixel| pixel[3] > 0) {
            return Ok(Some(object.id));
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rectangle(id: u32, x: f32, y: f32, width: f32, height: f32) -> Arc<VectorObject> {
        Arc::new(
            ObjectSpec {
                name: "Shape".into(),
                visible: true,
                geometry: GeometrySpec::Rect {
                    x,
                    y,
                    width,
                    height,
                },
                transform: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
                style: Style {
                    fill: Some([200, 80, 40, 160]),
                    stroke: Some(StrokeStyle {
                        color: [20, 30, 40, 192],
                        width: 3.5,
                        cap: Cap::Round,
                        join: Join::Miter,
                        miter_limit: 4.0,
                    }),
                    fill_rule: FillRule::NonZero,
                },
            }
            .object(id),
        )
    }

    #[test]
    fn vector_tiles_keep_antialias_seams_and_match_a_single_renderer_surface() {
        let mut second = rectangle(2, 100.0, 80.0, 110.0, 90.0).as_ref().clone();
        second.geometry = Geometry::Path {
            segments: vec![
                Segment::MoveTo { x: 10.5, y: 40.0 },
                Segment::CubicTo {
                    c1x: 90.0,
                    c1y: 240.0,
                    c2x: 170.0,
                    c2y: 1.0,
                    x: 245.0,
                    y: 199.5,
                },
                Segment::LineTo { x: 40.0, y: 220.0 },
                Segment::Close,
            ],
        };
        let layer = Arc::new(VectorLayer {
            next_object_id: 3,
            objects: vec![rectangle(1, 70.25, 60.5, 140.5, 140.0), Arc::new(second)],
        });
        layer.validate().unwrap();
        let mut expected = vec![0; 256 * 256 * 4];
        let mut pixmap = tiny_skia::PixmapMut::from_bytes(&mut expected, 256, 256).unwrap();
        for object in &layer.objects {
            PreparedObject::new(object)
                .unwrap()
                .draw(object, &mut pixmap, 0.0, 0.0);
        }
        let mut cache = RenderCache::default();
        let mut first = BTreeMap::new();
        let mut maximum = 0;
        let mut seam = 0;
        let mut count = 0;
        for key in [(0, 0), (1, 0), (0, 1), (1, 1)] {
            let tile = cache.tile(&layer, key).unwrap();
            for y in 0..TILE_SIZE {
                for x in 0..TILE_SIZE {
                    let offset = ((y * TILE_SIZE + x) * 4) as usize;
                    let target =
                        (((key.1 * TILE_SIZE + y) * 256 + key.0 * TILE_SIZE + x) * 4) as usize;
                    for channel in 0..4 {
                        let difference =
                            tile[offset + channel].abs_diff(expected[target + channel]);
                        maximum = maximum.max(difference);
                        if !(3..=124).contains(&x) || !(3..=124).contains(&y) {
                            seam = seam.max(difference);
                        }
                        if difference > 1 {
                            count += 1;
                        }
                    }
                }
            }
            first.insert(key, tile);
        }
        assert!(maximum <= 1, "max={maximum}, seam={seam}, count={count}");
        for key in [(1, 1), (0, 1), (1, 0), (0, 0)] {
            assert_eq!(cache.tile(&layer, key).unwrap(), first[&key]);
        }
    }

    #[test]
    fn discarded_previews_release_cache_without_evicting_live_layers() {
        let layer = Arc::new(VectorLayer {
            next_object_id: 2,
            objects: vec![rectangle(1, 5.0, 5.0, 20.0, 20.0)],
        });
        let mut cache = RenderCache::default();
        let pixels = cache.tile(&layer, (0, 0)).unwrap();
        let prepared = cache.prepared(&layer.objects[0]);
        for id in 2..24 {
            let draft = Arc::new(VectorLayer {
                next_object_id: id + 1,
                objects: vec![rectangle(id, 35.0, 5.0, 20.0, 20.0)],
            });
            let discarded = cache.tile(&draft, (0, 0)).unwrap();
            drop(draft);
            cache.discard_expired();
            assert_eq!(Arc::strong_count(&discarded), 1);
            assert_eq!(cache.paths.len(), 1);
            assert_eq!(cache.tiles.len(), 1);
            assert!(Arc::ptr_eq(&cache.tile(&layer, (0, 0)).unwrap(), &pixels));
            assert!(Arc::ptr_eq(&cache.prepared(&layer.objects[0]), &prepared));
            assert!(cache.path_storage_bytes() <= MAX_VECTOR_CACHE_BYTES);
            assert!(cache.tile_storage_bytes() <= MAX_VECTOR_CACHE_BYTES);
        }
    }

    #[test]
    fn vector_caches_bound_retained_allocations_and_rebuild_evicted_tiles() {
        let layer = Arc::new(VectorLayer {
            next_object_id: 2,
            objects: vec![rectangle(1, 0.0, 0.0, 8192.0, 128.0)],
        });
        let mut cache = RenderCache::default();
        let first = cache.tile(&layer, (0, 0)).unwrap();
        for x in 1..64 {
            cache.tile(&layer, (x, 0)).unwrap();
            assert!(cache.tile_storage_bytes() <= MAX_VECTOR_CACHE_BYTES);
            assert!(cache.path_storage_bytes() <= MAX_VECTOR_CACHE_BYTES);
        }
        let recreated = cache.tile(&layer, (0, 0)).unwrap();
        assert_eq!(first, recreated);
        assert!(!Arc::ptr_eq(&first, &recreated));
        let mut altered = layer.as_ref().clone();
        let mut object = altered.objects[0].as_ref().clone();
        object.style.fill = Some([0, 200, 0, 255]);
        altered.objects[0] = Arc::new(object);
        assert_ne!(cache.tile(&Arc::new(altered), (0, 0)).unwrap(), first);
        let objects: Vec<_> = (1..=48)
            .map(|id| rectangle(id, 0.0, 0.0, 8192.0, 8192.0))
            .collect();
        let first = cache.prepared(&objects[0]);
        for object in &objects {
            cache.prepared(object);
            assert!(cache.path_storage_bytes() <= MAX_VECTOR_CACHE_BYTES);
        }
        assert!(cache.paths.len() < objects.len());
        let restored = cache.prepared(&objects[0]);
        assert!(!Arc::ptr_eq(&first, &restored));
        let mut expected = vec![0; TILE_BYTES];
        let mut actual = vec![0; TILE_BYTES];
        for (prepared, pixels) in [(first, &mut expected), (restored, &mut actual)] {
            prepared.draw(
                &objects[0],
                &mut tiny_skia::PixmapMut::from_bytes(pixels, TILE_SIZE, TILE_SIZE).unwrap(),
                0.0,
                0.0,
            );
        }
        assert_eq!(actual, expected);
    }

    #[test]
    fn changed_object_bounds_keep_unrelated_tiles_out_of_dirty_roi() {
        let before = VectorLayer {
            next_object_id: 3,
            objects: vec![
                rectangle(1, 10.0, 10.0, 20.0, 20.0),
                rectangle(2, 400.0, 10.0, 20.0, 20.0),
            ],
        };
        let mut after = before.clone();
        let mut object = after.objects[0].as_ref().clone();
        object.transform[4] = 140.0;
        after.objects[0] = Arc::new(object);
        assert_eq!(
            changed_keys(
                &before,
                &after,
                Rect {
                    left: 0,
                    top: 0,
                    right: 512,
                    bottom: 128
                }
            ),
            BTreeSet::from([(0, 0), (1, 0)])
        );
    }
}
