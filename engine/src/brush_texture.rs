use crate::model::{BrushTexture, BRUSH_TEXTURE_EDGE};

const BYTES: usize = BRUSH_TEXTURE_EDGE * BRUSH_TEXTURE_EDGE;

pub struct Material {
    tip: &'static [u8; BYTES],
    paper: &'static [u8; BYTES],
}

macro_rules! material {
    ($name:literal) => {
        Material {
            tip: include_bytes!(concat!("../assets/brushes/", $name, "-tip.bin")),
            paper: include_bytes!(concat!("../assets/brushes/", $name, "-paper.bin")),
        }
    };
}

static GRAPHITE: Material = material!("graphite");
static CHARCOAL: Material = material!("charcoal");
static BRISTLE: Material = material!("bristle");
static DRY_BRISTLE: Material = material!("dry-bristle");
static PIGMENT: Material = material!("pigment");
static CANVAS: Material = material!("canvas");
static WASH: Material = material!("wash");

impl Material {
    pub fn for_texture(texture: BrushTexture) -> Option<&'static Self> {
        match texture {
            BrushTexture::Smooth => None,
            BrushTexture::Graphite => Some(&GRAPHITE),
            BrushTexture::Charcoal => Some(&CHARCOAL),
            BrushTexture::Bristle => Some(&BRISTLE),
            BrushTexture::DryBristle => Some(&DRY_BRISTLE),
            BrushTexture::Pigment => Some(&PIGMENT),
            BrushTexture::Canvas => Some(&CANVAS),
            BrushTexture::Wash => Some(&WASH),
        }
    }

    pub fn coverage(&self, u: f32, v: f32, x: u32, y: u32) -> f32 {
        let edge = BRUSH_TEXTURE_EDGE;
        let px = ((u + 1.0) * 0.5 * (edge - 1) as f32).clamp(0.0, (edge - 1) as f32);
        let py = ((v + 1.0) * 0.5 * (edge - 1) as f32).clamp(0.0, (edge - 1) as f32);
        let (ix, iy) = (px as usize, py as usize);
        let (nx, ny) = ((ix + 1).min(edge - 1), (iy + 1).min(edge - 1));
        let (fx, fy) = (px.fract(), py.fract());
        let a = f32::from(self.tip[iy * edge + ix]);
        let b = f32::from(self.tip[iy * edge + nx]);
        let c = f32::from(self.tip[ny * edge + ix]);
        let d = f32::from(self.tip[ny * edge + nx]);
        let top = a + (b - a) * fx;
        let bottom = c + (d - c) * fx;
        let paper = self.paper[(y as usize % edge) * edge + x as usize % edge];
        (top + (bottom - top) * fy) * f32::from(paper) / (255.0 * 255.0)
    }
}
