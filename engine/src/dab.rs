use crate::{model::*, selection::Selection};

pub(crate) struct Dab {
    pub bounds: Rect,
    brush: Brush,
    point: Sample,
    radius: f32,
    feather: f32,
    sin: f32,
    cos: f32,
    inverse_aspect: f32,
    circular: bool,
}

impl Dab {
    pub fn new(canvas: Rect, selection: Option<&Selection>, brush: Brush, point: Sample) -> Self {
        let radius = (brush.size_at_pressure(point.pressure) * 0.5).max(0.5);
        let extent = if brush.tip == BrushTip::Flat {
            radius * std::f32::consts::SQRT_2
        } else {
            radius
        };
        let region = selection.map_or(canvas, Selection::bounds);
        let (sin, cos) = brush.angle.to_radians().sin_cos();
        Self {
            bounds: Rect {
                left: ((point.x - extent).floor().max(0.0) as u32).max(region.left),
                top: ((point.y - extent).floor().max(0.0) as u32).max(region.top),
                right: ((point.x + extent).ceil().max(0.0) as u32).min(region.right),
                bottom: ((point.y + extent).ceil().max(0.0) as u32).min(region.bottom),
            },
            brush,
            point,
            radius,
            feather: (radius - radius * brush.hardness).max(0.75),
            sin,
            cos,
            inverse_aspect: 1.0 / brush.aspect,
            circular: brush.tip == BrushTip::Round && brush.aspect == 1.0,
        }
    }

    pub fn coverage<const SIMPLE: bool>(&self, x: u32, y: u32) -> f32 {
        let dx = x as f32 + 0.5 - self.point.x;
        let dy = y as f32 + 0.5 - self.point.y;
        let squared = if SIMPLE || self.circular {
            dx * dx + dy * dy
        } else {
            let rx = dx * self.cos + dy * self.sin;
            let ry = (-dx * self.sin + dy * self.cos) * self.inverse_aspect;
            if self.brush.tip == BrushTip::Flat {
                rx.abs().max(ry.abs()).powi(2)
            } else {
                rx * rx + ry * ry
            }
        };
        if squared >= self.radius * self.radius {
            return 0.0;
        }
        let mut coverage = ((self.radius - squared.sqrt()) / self.feather).clamp(0.0, 1.0);
        if !SIMPLE && self.brush.grain > 0.0 {
            let mut hash = x.wrapping_mul(374_761_393) ^ y.wrapping_mul(668_265_263);
            hash = (hash ^ (hash >> 13)).wrapping_mul(1_274_126_177);
            let noise = ((hash ^ (hash >> 16)) & 65535) as f32 / 65535.0;
            coverage *= (1.0 - self.brush.grain) + self.brush.grain * noise.powi(3);
        }
        coverage
    }
}
