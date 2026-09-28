use crate::{model::*, selection::Selection};

const PAPER_CELL: u32 = 4;
const PAPER_LATTICE: i32 = 24;

fn lattice(x: i32, y: i32) -> f32 {
    let hash = (x as u32).wrapping_mul(374_761_393) ^ (y as u32).wrapping_mul(668_265_263);
    let hash = (hash ^ (hash >> 13)).wrapping_mul(1_274_126_177);
    ((hash ^ (hash >> 16)) & 65535) as f32 / 65535.0
}

fn paper_noise(x: u32, y: u32) -> f32 {
    let gx = x as f32 / PAPER_CELL as f32;
    let gy = y as f32 / PAPER_CELL as f32;
    let fx = gx.fract();
    let fy = gy.fract();
    let x0 = (gx as i32) % PAPER_LATTICE;
    let y0 = (gy as i32) % PAPER_LATTICE;
    let x1 = (x0 + 1) % PAPER_LATTICE;
    let y1 = (y0 + 1) % PAPER_LATTICE;
    let sx = fx * fx * (3.0 - 2.0 * fx);
    let sy = fy * fy * (3.0 - 2.0 * fy);
    let top = lattice(x0, y0) + (lattice(x1, y0) - lattice(x0, y0)) * sx;
    let bottom = lattice(x0, y1) + (lattice(x1, y1) - lattice(x0, y1)) * sx;
    top + (bottom - top) * sy
}

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
    leaf_center: f32,
    leaf_radius: f32,
}

impl Dab {
    pub fn new(canvas: Rect, selection: Option<&Selection>, brush: Brush, point: Sample) -> Self {
        let radius = (brush.size_at_pressure(point.pressure) * 0.5).max(0.5);
        let extent = if brush.tip == BrushTip::Flat || brush.tip == BrushTip::Comb {
            radius * std::f32::consts::SQRT_2
        } else {
            radius
        };
        let region = selection.map_or(canvas, Selection::bounds);
        let (sin, cos) = brush.angle.to_radians().sin_cos();
        let (leaf_center, leaf_radius) = if brush.tip == BrushTip::Leaf {
            let half = (radius * brush.aspect).max(0.25);
            let center = (radius * radius - half * half) / (2.0 * half);
            (center, center + half)
        } else {
            (0.0, radius)
        };
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
            leaf_center,
            leaf_radius,
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

    fn comb_coverage(&self, rx: f32, ry: f32) -> f32 {
        const TINES: f32 = 4.0;
        let pitch = self.radius * 2.0 / TINES;
        let tine = (self.radius * self.brush.aspect).max(pitch * 0.12);
        let along = ((self.radius - rx.abs()) / self.feather).clamp(0.0, 1.0);
        if along <= 0.0 {
            return 0.0;
        }
        let offset = ry + self.radius;
        if offset <= 0.0 {
            return 0.0;
        }
        let m = offset / pitch;
        let k = (m - 0.5).round().clamp(0.0, TINES - 1.0) + 0.5;
        let across = ((tine - (m - k).abs() * pitch) / self.feather).clamp(0.0, 1.0);
        along.min(across)
    }

    fn shape_at<const SIMPLE: bool>(&self, px: f32, py: f32) -> f32 {
        let dx = px - self.point.x;
        let dy = py - self.point.y;
        let squared = if SIMPLE || self.circular {
            dx * dx + dy * dy
        } else {
            let rx = dx * self.cos + dy * self.sin;
            let ry = -dx * self.sin + dy * self.cos;
            match self.brush.tip {
                BrushTip::Flat => (rx.abs().max(ry.abs() * self.inverse_aspect)).powi(2),
                BrushTip::Leaf => {
                    rx * rx + ry * ry + self.leaf_center * (self.leaf_center + 2.0 * ry.abs())
                }
                BrushTip::Comb => return self.comb_coverage(rx, ry),
                BrushTip::Round => {
                    let ry = ry * self.inverse_aspect;
                    rx * rx + ry * ry
                }
            }
        };
        let reach = if self.brush.tip == BrushTip::Leaf {
            self.leaf_radius
        } else {
            self.radius
        };
        if squared >= reach * reach {
            return 0.0;
        }
        ((reach - squared.sqrt()) / self.feather).clamp(0.0, 1.0)
    }

    pub fn coverage<const SIMPLE: bool>(&self, x: u32, y: u32) -> f32 {
        let px = x as f32 + 0.5;
        let py = y as f32 + 0.5;
        let dx = px - self.point.x;
        let dy = py - self.point.y;
        let far = self.radius * 1.5 + 0.5;
        if dx * dx + dy * dy > far * far {
            return 0.0;
        }
        let core = self.shape_at::<SIMPLE>(px, py);
        let shape = if core >= 1.0 {
            1.0
        } else {
            let mut sum = 0.0;
            for (ox, oy) in [(-0.25, -0.25), (0.25, -0.25), (-0.25, 0.25), (0.25, 0.25)] {
                sum += self.shape_at::<SIMPLE>(px + ox, py + oy);
            }
            sum * 0.25
        };
        if shape <= 0.0 {
            return 0.0;
        }
        let mut coverage = shape;
        if self.brush.grain > 0.0 || self.brush.paper > 0.0 {
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
            if self.brush.grain > 0.0 {
                let mut hash = x.wrapping_mul(374_761_393) ^ y.wrapping_mul(668_265_263);
                hash = (hash ^ (hash >> 13)).wrapping_mul(1_274_126_177);
                let noise = ((hash ^ (hash >> 16)) & 65535) as f32 / 65535.0;
                coverage *= (1.0 - self.brush.grain) + self.brush.grain * noise.powi(3);
            }
            if self.brush.paper > 0.0 {
                coverage *= 1.0 - self.brush.paper * (1.0 - paper_noise(x, y));
            }
        }
        coverage
    }
}
