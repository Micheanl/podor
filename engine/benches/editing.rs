use podor_engine::{Command, Engine};
use serde_json::json;
use std::time::Instant;

fn main() {
    let mut engine = Engine::new(2048, 2048).unwrap();
    for (name, value) in [
        (
            "fill",
            json!({"type":"fill","x":1024,"y":1024,"color":[139,41,66,255],"tolerance":0}),
        ),
        (
            "tone",
            json!({"type":"tone","settings":{"brightness":0.1,"contrast":0.2,"saturation":-0.3}}),
        ),
        ("blur sigma 8", json!({"type":"blur","sigma":8.0})),
        ("blur sigma 32", json!({"type":"blur","sigma":32.0})),
    ] {
        let command: Command = serde_json::from_value(value).unwrap();
        let start = Instant::now();
        engine.command(command).unwrap();
        println!(
            "2048x2048 {name}: {:.2} ms",
            start.elapsed().as_secs_f64() * 1000.0
        );
    }
}
