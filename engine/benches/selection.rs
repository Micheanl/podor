use podor_engine::{model::*, Command, Engine};
use serde_json::json;
use std::time::Instant;

fn main() {
    for (width, height) in [(1600, 1200), (4096, 4096), (8192, 2048)] {
        for kind in ["ellipse", "lasso"] {
            let points = if kind == "lasso" {
                (0..MAX_SELECTION_POINTS).map(|i| {
                    let angle = i as f64 * std::f64::consts::TAU / MAX_SELECTION_POINTS as f64;
                    json!({"x":f64::from(width)*(0.5 + 0.45 * angle.cos()),"y":f64::from(height)*(0.5 + 0.45 * angle.sin())})
                }).collect::<Vec<_>>()
            } else {
                Vec::new()
            };
            let mut elapsed = Vec::new();
            for _ in 0..7 {
                let mut engine = Engine::new(width, height).unwrap();
                let command: Command = serde_json::from_value(json!({"type":"select_shape","selection":{"kind":kind,"left":0,"top":0,"right":width,"bottom":height,"points":points}})).unwrap();
                let start = Instant::now();
                engine.command(command).unwrap();
                elapsed.push(start.elapsed().as_secs_f64() * 1000.0);
            }
            elapsed.sort_by(f64::total_cmp);
            println!(
                "{width}x{height} {kind}: median {:.2} ms (mask + serialized state; no rendering)",
                elapsed[3]
            );
        }
    }
}
