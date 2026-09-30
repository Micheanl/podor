use crate::model::*;

#[derive(Clone, Copy)]
struct Point {
    x: i32,
    y: i32,
    pressure: f32,
}

impl Point {
    fn sample(self) -> Sample {
        Sample {
            x: self.x as f32,
            y: self.y as f32,
            pressure: self.pressure,
        }
    }

    fn same_cell(self, other: Self) -> bool {
        self.x == other.x && self.y == other.y
    }
}

fn diameter(brush: Brush, pressure: f32) -> f32 {
    brush.size_at_pressure(pressure).round().clamp(1.0, 256.0)
}

pub fn nib(brush: Brush, point: Sample) -> (Brush, Sample) {
    let size = diameter(brush, point.pressure);
    let tip = if size == 1.0 {
        BrushTip::Round
    } else if brush.tip == BrushTip::Comb && size < 4.0 {
        BrushTip::Flat
    } else {
        brush.tip
    };
    let minor = if size == 1.0 || brush.tip == BrushTip::Comb {
        size
    } else {
        (size * brush.aspect).round().max(1.0)
    };
    let offset = |diameter: f32| {
        if (diameter as u32).is_multiple_of(2) {
            0.0
        } else {
            0.5
        }
    };
    let aspect = if tip == BrushTip::Comb {
        brush.aspect.max(1.001 / size)
    } else {
        minor / size
    };
    let (sin, cos) = brush.angle.to_radians().sin_cos();
    let offsets = if sin.abs() > cos.abs() {
        [offset(minor), offset(size)]
    } else {
        [offset(size), offset(minor)]
    };
    (
        Brush {
            size,
            tip,
            aspect,
            size_pressure: 0.0,
            hardness: 1.0,
            texture: BrushTexture::Smooth,
            grain: 0.0,
            paper: 0.0,
            follow_direction: false,
            ..brush
        },
        Sample {
            x: point.x + offsets[0],
            y: point.y + offsets[1],
            ..point
        },
    )
}

#[derive(Default)]
pub struct PixelStroke {
    last: Option<Point>,
    committed: Option<Point>,
    pending: Option<Point>,
}

impl PixelStroke {
    fn accept(
        &mut self,
        brush: Brush,
        point: Point,
        stamp: &mut impl FnMut(Sample) -> Result<bool, String>,
    ) -> Result<bool, String> {
        if brush.raster != BrushRaster::PixelPerfect {
            return stamp(point.sample());
        }
        let Some(previous) = self.committed else {
            self.committed = Some(point);
            return stamp(point.sample());
        };
        let Some(pending) = self.pending else {
            self.pending = Some(point);
            return Ok(false);
        };
        let diagonal = (previous.x - point.x).abs() == 1 && (previous.y - point.y).abs() == 1;
        let corner = (previous.x == pending.x && pending.y == point.y)
            || (previous.y == pending.y && pending.x == point.x);
        if diagonal
            && corner
            && [previous, pending, point]
                .iter()
                .all(|sample| diameter(brush, sample.pressure) == 1.0)
        {
            self.pending = Some(point);
            return Ok(false);
        }
        let modified = stamp(pending.sample())?;
        self.committed = Some(pending);
        self.pending = Some(point);
        Ok(modified)
    }

    pub fn paint(
        &mut self,
        brush: Brush,
        sample: Sample,
        mut stamp: impl FnMut(Sample) -> Result<bool, String>,
    ) -> Result<bool, String> {
        let point = Point {
            x: sample.x.floor() as i32,
            y: sample.y.floor() as i32,
            pressure: sample.pressure.clamp(0.0, 1.0),
        };
        let Some(last) = self.last else {
            self.last = Some(point);
            return self.accept(brush, point, &mut stamp);
        };
        if point.same_cell(last) {
            self.last = Some(point);
            if let Some(pending) = self.pending.as_mut() {
                *pending = point;
                return Ok(false);
            }
            if diameter(brush, point.pressure) > diameter(brush, last.pressure) {
                if brush.raster == BrushRaster::PixelPerfect {
                    self.committed = Some(point);
                }
                return stamp(point.sample());
            }
            return Ok(false);
        }
        let dx = (point.x - last.x).abs();
        let dy = -(point.y - last.y).abs();
        let sx = if last.x < point.x { 1 } else { -1 };
        let sy = if last.y < point.y { 1 } else { -1 };
        let steps = dx.max(-dy) as f32;
        let (mut x, mut y, mut error) = (last.x, last.y, dx + dy);
        let mut index = 0;
        let mut modified = false;
        while x != point.x || y != point.y {
            let doubled = 2 * error;
            if doubled >= dy {
                error += dy;
                x += sx;
            }
            if doubled <= dx {
                error += dx;
                y += sy;
            }
            index += 1;
            let pressure =
                last.pressure + (point.pressure - last.pressure) * (index as f32 / steps);
            modified |= self.accept(brush, Point { x, y, pressure }, &mut stamp)?;
        }
        self.last = Some(point);
        Ok(modified)
    }

    pub fn finish(
        &mut self,
        mut stamp: impl FnMut(Sample) -> Result<bool, String>,
    ) -> Result<bool, String> {
        self.pending
            .take()
            .map_or(Ok(false), |point| stamp(point.sample()))
    }
}
