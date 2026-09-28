use crate::model::{Sample, MAX_STABILIZER_DISTANCE};

pub struct Stabilizer {
    distance: f32,
    input: Option<Sample>,
    filtered: Option<Sample>,
}

impl Stabilizer {
    pub fn new(amount: f32) -> Self {
        Self {
            distance: amount * amount * MAX_STABILIZER_DISTANCE,
            input: None,
            filtered: None,
        }
    }

    pub fn push(&mut self, point: Sample) -> Sample {
        let output = match (self.input, self.filtered) {
            (Some(input), Some(filtered)) if self.distance > f32::EPSILON => {
                let dx = point.x - input.x;
                let dy = point.y - input.y;
                let length = dx.hypot(dy);
                if length <= f32::EPSILON {
                    filtered
                } else {
                    let ratio = length / self.distance;
                    let blend = -(-ratio).exp_m1();
                    // 按弧长积分线性输入，同一路径不因采样率改变稳笔距离。
                    let advance = if ratio < 0.001 {
                        ratio * (0.5 - ratio / 6.0)
                    } else {
                        1.0 - blend / ratio
                    };
                    Sample {
                        x: filtered.x + (input.x - filtered.x) * blend + dx * advance,
                        y: filtered.y + (input.y - filtered.y) * blend + dy * advance,
                        pressure: filtered.pressure
                            + (input.pressure - filtered.pressure) * blend
                            + (point.pressure - input.pressure) * advance,
                    }
                }
            }
            _ => point,
        };
        self.input = Some(point);
        self.filtered = Some(output);
        output
    }

    pub fn finish(&self) -> Option<Sample> {
        self.input.filter(|input| {
            self.distance > f32::EPSILON
                && self.filtered.is_some_and(|filtered| {
                    (input.x - filtered.x).hypot(input.y - filtered.y) > f32::EPSILON
                })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(x: f32, y: f32) -> Sample {
        Sample {
            x,
            y,
            pressure: 0.7,
        }
    }

    #[test]
    fn disabled_filter_preserves_input_and_does_not_add_a_tail() {
        let mut filter = Stabilizer::new(0.0);
        for input in [point(10.0, 20.0), point(42.0, -5.0), point(42.0, -5.0)] {
            let output = filter.push(input);
            assert_eq!(
                (input.x, input.y, input.pressure),
                (output.x, output.y, output.pressure)
            );
        }
        assert!(filter.finish().is_none());
    }

    #[test]
    fn straight_path_has_the_same_lag_at_different_sampling_rates() {
        for amount in [0.01, 0.2, 0.5, 1.0] {
            let mut sparse = Stabilizer::new(amount);
            sparse.push(point(10.0, 20.0));
            let expected = sparse.push(point(210.0, 120.0));
            for steps in [2, 20, 200, 2000] {
                let mut dense = Stabilizer::new(amount);
                let mut actual = dense.push(point(10.0, 20.0));
                for i in 1..=steps {
                    let t = i as f32 / steps as f32;
                    actual = dense.push(point(10.0 + 200.0 * t, 20.0 + 100.0 * t));
                }
                assert!((actual.x - expected.x).abs() < 0.01);
                assert!((actual.y - expected.y).abs() < 0.01);
            }
            assert!((210.0 - expected.x).hypot(120.0 - expected.y) <= MAX_STABILIZER_DISTANCE);
        }
    }

    #[test]
    fn repeated_stationary_input_preserves_the_path_and_keeps_pressure_steady() {
        let mut filter = Stabilizer::new(1.0);
        filter.push(point(0.0, 0.0));
        let before = filter.push(point(100.0, 0.0));
        for _ in 0..100 {
            let output = filter.push(Sample {
                pressure: 0.2,
                ..point(100.0, 0.0)
            });
            assert_eq!((before.x, before.y), (output.x, output.y));
            assert_eq!(output.pressure, before.pressure);
        }
        let finish = filter.finish().unwrap();
        assert_eq!((finish.x, finish.y, finish.pressure), (100.0, 0.0, 0.2));
    }

    #[test]
    fn pressure_jitter_is_damped_the_same_way_at_any_sampling_rate() {
        fn triangle(distance: f32) -> f32 {
            if distance <= 100.0 {
                0.5 + distance * 0.004
            } else if distance <= 300.0 {
                0.9 - (distance - 100.0) * 0.004
            } else {
                0.1 + (distance - 300.0) * 0.004
            }
        }
        let mut expected = None;
        for steps in [4, 40, 400, 4000] {
            let mut filter = Stabilizer::new(0.6);
            filter.push(Sample {
                x: 10.0,
                y: 10.0,
                pressure: 0.5,
            });
            let mut output = Sample {
                x: 10.0,
                y: 10.0,
                pressure: 0.5,
            };
            for i in 1..=steps {
                let distance = i as f32 * 400.0 / steps as f32;
                output = filter.push(Sample {
                    x: 10.0 + distance,
                    y: 10.0,
                    pressure: triangle(distance),
                });
            }
            if expected.is_none() {
                expected = Some(output);
            }
            assert!((output.pressure - expected.unwrap().pressure).abs() < 0.01);
            assert!((0.1..=0.9).contains(&output.pressure));
        }
    }

    #[test]
    fn alternating_pressure_flattens_into_a_steady_weight() {
        let mut filter = Stabilizer::new(0.6);
        filter.push(Sample {
            x: 0.0,
            y: 0.0,
            pressure: 0.5,
        });
        let mut swing = 0.0f32;
        let mut output = Sample {
            x: 0.0,
            y: 0.0,
            pressure: 0.5,
        };
        for i in 1..=2000 {
            output = filter.push(Sample {
                x: i as f32 * 0.2,
                y: 0.0,
                pressure: if i % 2 == 0 { 0.9 } else { 0.1 },
            });
            swing = swing.max((output.pressure - 0.5).abs());
        }
        assert!(swing < 0.1);
        assert!((0.4..=0.6).contains(&output.pressure));
    }

    #[test]
    fn held_pressure_converges_while_the_stroke_moves_on() {
        let mut filter = Stabilizer::new(0.4);
        filter.push(Sample {
            x: 0.0,
            y: 0.0,
            pressure: 0.1,
        });
        let mut output = Sample {
            x: 0.0,
            y: 0.0,
            pressure: 0.1,
        };
        for i in 1..=400 {
            output = filter.push(Sample {
                x: i as f32 * 2.0,
                y: 30.0,
                pressure: 1.0,
            });
        }
        assert!(output.pressure > 0.99);
        assert!(output.x > 700.0);
    }
}
