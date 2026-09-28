use crate::{model::*, selection::Selection};

#[derive(Clone, Copy)]
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
    grain_mirror: [bool; 2],
    grain_axis: [f32; 2],
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
            grain_mirror: [false; 2],
            grain_axis: [0.0; 2],
        }
    }

    pub fn symmetric(
        canvas: Rect,
        selection: Option<&Selection>,
        brush: Brush,
        point: Sample,
    ) -> ([Self; 4], usize) {
        let original = Self::new(canvas, selection, brush, point);
        let mut dabs = [original; 4];
        let axes = [
            (brush.symmetry.x * canvas.right as f32 * 2.0).round(),
            (brush.symmetry.y * canvas.bottom as f32 * 2.0).round(),
        ];
        let flips: &[[bool; 2]] = match brush.symmetry.mode {
            SymmetryMode::Off => &[],
            SymmetryMode::Vertical => &[[true, false]],
            SymmetryMode::Horizontal => &[[false, true]],
            SymmetryMode::Quadrant => &[[true, false], [false, true], [true, true]],
        };
        for (index, flip) in flips.iter().enumerate() {
            let mirrored = Sample {
                x: if flip[0] { axes[0] - point.x } else { point.x },
                y: if flip[1] { axes[1] - point.y } else { point.y },
                ..point
            };
            let brush = Brush {
                angle: if flip[0] != flip[1] {
                    -brush.angle
                } else {
                    brush.angle
                },
                ..brush
            };
            let mut dab = Self::new(canvas, selection, brush, mirrored);
            dab.grain_mirror = *flip;
            dab.grain_axis = axes;
            dabs[index + 1] = dab;
        }
        (dabs, flips.len() + 1)
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
            let x = if self.grain_mirror[0] {
                (self.grain_axis[0] - x as f32 - 1.0) as i64 as u32
            } else {
                x
            };
            let y = if self.grain_mirror[1] {
                (self.grain_axis[1] - y as f32 - 1.0) as i64 as u32
            } else {
                y
            };
            let mut hash = x.wrapping_mul(374_761_393) ^ y.wrapping_mul(668_265_263);
            hash = (hash ^ (hash >> 13)).wrapping_mul(1_274_126_177);
            let noise = ((hash ^ (hash >> 16)) & 65535) as f32 / 65535.0;
            coverage *= (1.0 - self.brush.grain) + self.brush.grain * noise.powi(3);
        }
        coverage
    }
}
