use crate::model::*;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeSet, mem::size_of, sync::Arc};

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

impl Point {
    fn subtract(self, other: Self) -> Self {
        Self {
            x: self.x - other.x,
            y: self.y - other.y,
        }
    }
    fn length(self) -> f64 {
        self.x.hypot(self.y)
    }
    fn normalized(self) -> Self {
        let length = self.length();
        Self {
            x: self.x / length,
            y: self.y / length,
        }
    }
    fn dot(self, other: Self) -> f64 {
        self.x * other.x + self.y * other.y
    }
    fn validate(self, bound: f64) -> Result<(), String> {
        if !self.x.is_finite()
            || !self.y.is_finite()
            || self.x.abs() > bound
            || self.y.abs() > bound
        {
            return Err("绘画助手坐标无效".into());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub enum Family {
    FiniteVanishingPoint { point: Point },
    InfiniteDirection { direction: Point },
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub enum Geometry {
    Parallel { a: Point, b: Point },
    Radial { center: Point },
    Perspective { families: [Family; 3] },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Assistant {
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub geometry: Geometry,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AssistantSet {
    pub next_id: u32,
    pub snap_id: Option<u32>,
    pub items: Vec<Assistant>,
}

impl Default for AssistantSet {
    fn default() -> Self {
        Self {
            next_id: 1,
            snap_id: None,
            items: Vec::new(),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum FamilySpec {
    FiniteVanishingPoint { point: Point },
    InfiniteDirection { direction: Point },
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum GeometrySpec {
    Parallel { a: Point, b: Point },
    Radial { center: Point },
    Perspective { families: [FamilySpec; 3] },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AssistantSpec {
    pub name: String,
    pub visible: bool,
    pub geometry: GeometrySpec,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize)]
pub struct StrokeBinding {
    pub id: u32,
    pub revision: u64,
}

impl From<FamilySpec> for Family {
    fn from(value: FamilySpec) -> Self {
        match value {
            FamilySpec::FiniteVanishingPoint { point } => Self::FiniteVanishingPoint { point },
            FamilySpec::InfiniteDirection { direction } => Self::InfiniteDirection { direction },
        }
    }
}
impl From<Family> for FamilySpec {
    fn from(value: Family) -> Self {
        match value {
            Family::FiniteVanishingPoint { point } => Self::FiniteVanishingPoint { point },
            Family::InfiniteDirection { direction } => Self::InfiniteDirection { direction },
        }
    }
}
impl From<GeometrySpec> for Geometry {
    fn from(value: GeometrySpec) -> Self {
        match value {
            GeometrySpec::Parallel { a, b } => Self::Parallel { a, b },
            GeometrySpec::Radial { center } => Self::Radial { center },
            GeometrySpec::Perspective { families } => Self::Perspective {
                families: families.map(Family::from),
            },
        }
    }
}
impl From<Geometry> for GeometrySpec {
    fn from(value: Geometry) -> Self {
        match value {
            Geometry::Parallel { a, b } => Self::Parallel { a, b },
            Geometry::Radial { center } => Self::Radial { center },
            Geometry::Perspective { families } => Self::Perspective {
                families: families.map(FamilySpec::from),
            },
        }
    }
}

impl Geometry {
    pub fn validate(self) -> Result<(), String> {
        let direction = |d: Point| -> Result<(), String> {
            if d.length() < MIN_ASSISTANT_DIRECTION_DISTANCE {
                return Err("绘画助手方向退化".into());
            }
            Ok(())
        };
        match self {
            Self::Parallel { a, b } => {
                a.validate(MAX_ASSISTANT_COORDINATE)?;
                b.validate(MAX_ASSISTANT_COORDINATE)?;
                direction(b.subtract(a))?;
            }
            Self::Radial { center } => center.validate(MAX_ASSISTANT_COORDINATE)?,
            Self::Perspective { families } => {
                if !families
                    .iter()
                    .any(|family| matches!(family, Family::FiniteVanishingPoint { .. }))
                {
                    return Err("透视助手至少需要一个消失点".into());
                }
                for (index, family) in families.iter().enumerate() {
                    match *family {
                        Family::FiniteVanishingPoint { point } => {
                            point.validate(MAX_ASSISTANT_COORDINATE)?
                        }
                        Family::InfiniteDirection { direction: d } => {
                            d.validate(MAX_ASSISTANT_COORDINATE)?;
                            direction(d)?
                        }
                    }
                    for other in &families[..index] {
                        let duplicate = match (*family, *other) {
                            (
                                Family::FiniteVanishingPoint { point: a },
                                Family::FiniteVanishingPoint { point: b },
                            ) => a.subtract(b).length() < MIN_ASSISTANT_DIRECTION_DISTANCE,
                            (
                                Family::InfiniteDirection { direction: a },
                                Family::InfiniteDirection { direction: b },
                            ) => {
                                let a = a.normalized();
                                let b = b.normalized();
                                (a.x * b.y - a.y * b.x).abs() < MIN_ASSISTANT_DIRECTION_DISTANCE
                            }
                            _ => false,
                        };
                        if duplicate {
                            return Err("透视助手方向重复".into());
                        }
                    }
                }
            }
        }
        Ok(())
    }

    fn affine(self, sx: f64, sy: f64, dx: f64, dy: f64) -> Result<Self, String> {
        let point = |p: Point| Point {
            x: p.x * sx + dx,
            y: p.y * sy + dy,
        };
        let geometry = match self {
            Self::Parallel { a, b } => Self::Parallel {
                a: point(a),
                b: point(b),
            },
            Self::Radial { center } => Self::Radial {
                center: point(center),
            },
            Self::Perspective { families } => Self::Perspective {
                families: families.map(|family| match family {
                    Family::FiniteVanishingPoint { point: p } => {
                        Family::FiniteVanishingPoint { point: point(p) }
                    }
                    Family::InfiniteDirection { direction } => Family::InfiniteDirection {
                        direction: Point {
                            x: direction.x * sx,
                            y: direction.y * sy,
                        },
                    },
                }),
            },
        };
        geometry.validate()?;
        Ok(geometry)
    }
}

impl Assistant {
    pub fn validate(&self) -> Result<(), String> {
        if self.id == 0 || self.name.is_empty() || self.name.len() > MAX_LAYER_NAME_BYTES {
            return Err("绘画助手属性无效".into());
        }
        self.geometry.validate()
    }
    pub fn spec(&self) -> AssistantSpec {
        AssistantSpec {
            name: self.name.clone(),
            visible: self.visible,
            geometry: self.geometry.into(),
        }
    }
}
impl AssistantSpec {
    fn into_assistant(self, id: u32) -> Result<Assistant, String> {
        let assistant = Assistant {
            id,
            name: self.name,
            visible: self.visible,
            geometry: self.geometry.into(),
        };
        assistant.validate()?;
        Ok(assistant)
    }
}
impl AssistantSet {
    pub fn bytes(&self) -> usize {
        if self.items.is_empty() {
            0
        } else {
            size_of::<Self>()
                + size_of::<usize>() * 2
                + self.items.capacity() * size_of::<Assistant>()
                + self
                    .items
                    .iter()
                    .map(|item| item.name.capacity())
                    .sum::<usize>()
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.next_id == 0
            || self.items.len() > MAX_DRAWING_ASSISTANTS
            || self.bytes() > MAX_ASSISTANT_GEOMETRY_BYTES
        {
            return Err("绘画助手数量或内存超出限制".into());
        }
        let mut seen = BTreeSet::new();
        for item in &self.items {
            item.validate()?;
            if item.id >= self.next_id || !seen.insert(item.id) {
                return Err("绘画助手编号无效".into());
            }
        }
        if self.snap_id.is_some_and(|id| !seen.contains(&id)) {
            return Err("绘画助手吸附目标无效".into());
        }
        Ok(())
    }
    pub fn json(&self) -> serde_json::Value {
        serde_json::json!({"nextId":self.next_id,"snapId":self.snap_id,"items":self.items.iter().map(|item|{let mut value=serde_json::to_value(item.spec()).unwrap();value["id"]=item.id.into();value}).collect::<Vec<_>>()})
    }
    pub fn affine(&self, sx: f64, sy: f64, dx: f64, dy: f64) -> Result<Arc<Self>, String> {
        if self.items.is_empty() || sx == 1.0 && sy == 1.0 && dx == 0.0 && dy == 0.0 {
            return Ok(Arc::new(self.clone()));
        }
        let mut result = self.clone();
        for item in &mut result.items {
            item.geometry = item.geometry.affine(sx, sy, dx, dy)?;
        }
        result.validate()?;
        Ok(Arc::new(result))
    }
}

pub enum Edit {
    Add(AssistantSpec),
    Set(u32, AssistantSpec),
    Delete(u32),
    Snap(Option<u32>),
}
pub fn prepare_edit(doc: &Document, edit: Edit) -> Result<Document, String> {
    let mut next = doc.clone();
    let mut set = doc.assistants.as_ref().clone();
    match edit {
        Edit::Add(spec) => {
            if set.items.len() >= MAX_DRAWING_ASSISTANTS {
                return Err("已达到绘画助手上限".into());
            }
            let item = spec.into_assistant(set.next_id)?;
            set.next_id = set.next_id.checked_add(1).ok_or("绘画助手编号超出限制")?;
            set.items.push(item);
        }
        Edit::Set(id, spec) => {
            let index = set
                .items
                .iter()
                .position(|item| item.id == id)
                .ok_or("绘画助手不存在")?;
            let item = spec.into_assistant(id)?;
            if item == set.items[index] {
                return Ok(next);
            }
            set.items[index] = item;
        }
        Edit::Delete(id) => {
            let index = set
                .items
                .iter()
                .position(|item| item.id == id)
                .ok_or("绘画助手不存在")?;
            set.items.remove(index);
            if set.snap_id == Some(id) {
                set.snap_id = None;
            }
        }
        Edit::Snap(id) => {
            if id.is_some_and(|id| !set.items.iter().any(|item| item.id == id)) {
                return Err("绘画助手不存在".into());
            }
            if set.snap_id == id {
                return Ok(next);
            }
            set.snap_id = id;
        }
    }
    set.validate()?;
    next.assistants = Arc::new(set);
    crate::masks::check_transaction(doc, &next)?;
    Ok(next)
}

#[derive(Clone, Copy, Debug, Serialize)]
pub struct Axis {
    pub origin: Point,
    pub direction: Point,
}
pub struct Constraint {
    pub binding: StrokeBinding,
    geometry: Geometry,
    origin: Option<Point>,
    axis: Option<Axis>,
    family: Option<usize>,
}
impl Constraint {
    pub fn new(item: &Assistant, binding: StrokeBinding) -> Result<Self, String> {
        item.validate()?;
        Ok(Self {
            binding,
            geometry: item.geometry,
            origin: None,
            axis: None,
            family: None,
        })
    }
    pub fn project(&mut self, point: Sample) -> Sample {
        let position = Point {
            x: f64::from(point.x),
            y: f64::from(point.y),
        };
        let origin = *self.origin.get_or_insert(position);
        if self.axis.is_none() {
            let movement = position.subtract(origin);
            let moving = movement.length() >= MIN_ASSISTANT_DIRECTION_DISTANCE;
            let direction = match self.geometry {
                Geometry::Parallel { a, b } => Some(b.subtract(a)),
                Geometry::Radial { center } => {
                    let radial = origin.subtract(center);
                    if radial.length() >= MIN_ASSISTANT_DIRECTION_DISTANCE {
                        Some(radial)
                    } else if moving {
                        Some(movement)
                    } else {
                        None
                    }
                }
                Geometry::Perspective { families } => {
                    if moving {
                        let movement = movement.normalized();
                        let mut best = None;
                        for (index, family) in families.into_iter().enumerate() {
                            let direction = match family {
                                Family::FiniteVanishingPoint { point } => {
                                    let delta = point.subtract(origin);
                                    if delta.length() >= MIN_ASSISTANT_DIRECTION_DISTANCE {
                                        delta
                                    } else {
                                        movement
                                    }
                                }
                                Family::InfiniteDirection { direction } => direction,
                            };
                            let direction = direction.normalized();
                            let score = direction.dot(movement).abs();
                            if best.is_none_or(|(_, _, previous)| score > previous) {
                                best = Some((index, direction, score));
                            }
                        }
                        let (index, direction, _) = best.unwrap();
                        self.family = Some(index);
                        Some(direction)
                    } else {
                        None
                    }
                }
            };
            self.axis = direction.map(|direction| Axis {
                origin,
                direction: direction.normalized(),
            });
        }
        self.project_final(point)
    }
    pub fn project_final(&self, point: Sample) -> Sample {
        let Some(axis) = self.axis else {
            return point;
        };
        let mut lower = f64::NEG_INFINITY;
        let mut upper = f64::INFINITY;
        for (origin, direction) in [
            (axis.origin.x, axis.direction.x),
            (axis.origin.y, axis.direction.y),
        ] {
            if direction.abs() > f64::EPSILON {
                let a = (-MAX_ASSISTANT_STROKE_COORDINATE - origin) / direction;
                let b = (MAX_ASSISTANT_STROKE_COORDINATE - origin) / direction;
                lower = lower.max(a.min(b));
                upper = upper.min(a.max(b));
            }
        }
        let position = Point {
            x: f64::from(point.x),
            y: f64::from(point.y),
        };
        let t = position
            .subtract(axis.origin)
            .dot(axis.direction)
            .clamp(lower, upper);
        Sample {
            x: (axis.origin.x + axis.direction.x * t) as f32,
            y: (axis.origin.y + axis.direction.y * t) as f32,
            ..point
        }
    }
    pub fn axis(&self) -> Option<Axis> {
        self.axis
    }
    pub fn family(&self) -> Option<usize> {
        self.family
    }
}

pub fn bind(
    doc: &Document,
    request: Option<StrokeBinding>,
    revision: u64,
    smudge: bool,
) -> Result<Option<Constraint>, String> {
    if smudge {
        return if request.is_some() {
            Err("绘画助手暂不支持涂抹".into())
        } else {
            Ok(None)
        };
    }
    if request.is_some_and(|request| {
        request.revision != revision || Some(request.id) != doc.assistants.snap_id
    }) {
        return Err("绘画助手已变化，请重新开始笔画".into());
    }
    let Some(id) = request.map(|request| request.id).or(doc.assistants.snap_id) else {
        return Ok(None);
    };
    let item = doc
        .assistants
        .items
        .iter()
        .find(|item| item.id == id)
        .ok_or("绘画助手不存在")?;
    Constraint::new(item, StrokeBinding { id, revision }).map(Some)
}

pub fn preview(
    spec: AssistantSpec,
    origin: Point,
    point: Point,
    revision: u64,
) -> Result<serde_json::Value, String> {
    origin.validate(MAX_ASSISTANT_STROKE_COORDINATE)?;
    point.validate(MAX_ASSISTANT_STROKE_COORDINATE)?;
    let item = spec.into_assistant(1)?;
    let mut constraint = Constraint::new(&item, StrokeBinding { id: 1, revision })?;
    constraint.project(Sample {
        x: origin.x as f32,
        y: origin.y as f32,
        pressure: 1.0,
    });
    let point = constraint.project(Sample {
        x: point.x as f32,
        y: point.y as f32,
        pressure: 1.0,
    });
    Ok(
        serde_json::json!({"revision":revision,"point":{"x":point.x,"y":point.y},"family":constraint.family(),"axis":constraint.axis()}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn constraint(geometry: Geometry) -> Constraint {
        Constraint::new(
            &Assistant {
                id: 1,
                name: "Guide".into(),
                visible: true,
                geometry,
            },
            StrokeBinding { id: 1, revision: 7 },
        )
        .unwrap()
    }

    fn point(x: f64, y: f64) -> Point {
        Point { x, y }
    }

    fn sample(x: f32, y: f32, pressure: f32) -> Sample {
        Sample { x, y, pressure }
    }

    #[test]
    fn parallel_projects_through_pen_down_and_preserves_pressure_bits() {
        let mut guide = constraint(Geometry::Parallel {
            a: point(-30.0, 70.0),
            b: point(30.0, 150.0),
        });
        guide.project(sample(10.0, 20.0, 0.3));
        let raw = sample(50.0, 20.0, 0.12345679);
        let actual = guide.project(raw);
        assert!((actual.x - 24.4).abs() < 1e-5);
        assert!((actual.y - 39.2).abs() < 1e-5);
        assert_eq!(actual.pressure.to_bits(), raw.pressure.to_bits());
        let tail = guide.project_final(sample(-42.0, 120.0, 0.023));
        assert!(((tail.x - 10.0) * 4.0 - (tail.y - 20.0) * 3.0).abs() < 1e-4);
        assert_eq!(tail.pressure.to_bits(), 0.023f32.to_bits());
    }

    #[test]
    fn world_clip_moves_along_locked_axis_and_keeps_all_samples_bounded() {
        let bound = MAX_ASSISTANT_STROKE_COORDINATE as f32;
        for slope in [0.125, 0.5, 1.0, 2.0, 8.0] {
            let mut guide = constraint(Geometry::Parallel {
                a: point(0.0, 0.0),
                b: point(1.0, slope),
            });
            guide.project(sample(bound - 1.0, -bound + 1.0, 0.5));
            for raw in [
                sample(bound, bound, 0.1),
                sample(-bound, -bound, 0.7),
                sample(-bound, bound, 1.0),
            ] {
                let actual = guide.project(raw);
                assert!(actual.x.abs() <= bound && actual.y.abs() <= bound);
                let axis = guide.axis().unwrap();
                let cross = (f64::from(actual.x) - axis.origin.x) * axis.direction.y
                    - (f64::from(actual.y) - axis.origin.y) * axis.direction.x;
                assert!(cross.abs() < 0.002, "{slope}: {cross}");
                assert_eq!(raw.pressure.to_bits(), actual.pressure.to_bits());
            }
        }
    }

    #[test]
    fn radial_at_center_defers_axis_and_then_locks_it() {
        let mut guide = constraint(Geometry::Radial {
            center: point(30.0, 50.0),
        });
        guide.project(sample(30.0, 50.0, 0.4));
        assert!(guide.axis().is_none());
        guide.project(sample(30.0, 50.0, 0.6));
        assert!(guide.axis().is_none());
        guide.project(sample(50.0, 60.0, 0.8));
        let actual = guide.project(sample(30.0, 100.0, 0.9));
        assert_eq!((actual.x, actual.y), (50.0, 60.0));
        assert!(guide.family().is_none());
    }

    #[test]
    fn perspective_first_displacement_locks_family_and_ties_choose_first() {
        let families = [
            Family::FiniteVanishingPoint {
                point: point(100.0, 0.0),
            },
            Family::InfiniteDirection {
                direction: point(0.0, 1.0),
            },
            Family::InfiniteDirection {
                direction: point(1.0, 1.0),
            },
        ];
        let mut guide = constraint(Geometry::Perspective { families });
        guide.project(sample(0.0, 0.0, 1.0));
        assert!(guide.family().is_none());
        assert_eq!(guide.project(sample(2.0, 40.0, 0.7)).x, 0.0);
        assert_eq!(guide.family(), Some(1));
        let actual = guide.project(sample(60.0, 4.0, 0.4));
        assert_eq!((actual.x, actual.y), (0.0, 4.0));
        assert_eq!(guide.family(), Some(1));
        let mut at_vanishing_point = constraint(Geometry::Perspective { families });
        at_vanishing_point.project(sample(100.0, 0.0, 1.0));
        at_vanishing_point.project(sample(100.0, 50.0, 0.6));
        assert_eq!(at_vanishing_point.family(), Some(0));
    }

    #[test]
    fn geometry_validates_anchors_without_limiting_their_derived_delta() {
        let bound = MAX_ASSISTANT_COORDINATE;
        assert!(Geometry::Parallel {
            a: point(-bound, -bound),
            b: point(bound, bound),
        }
        .validate()
        .is_ok());
        assert!(Geometry::Parallel {
            a: point(0.0, 0.0),
            b: point(0.0, 0.0),
        }
        .validate()
        .is_err());
        assert!(Geometry::Perspective {
            families: [
                Family::FiniteVanishingPoint {
                    point: point(10.0, 20.0),
                },
                Family::InfiniteDirection {
                    direction: point(1.0, 2.0),
                },
                Family::InfiniteDirection {
                    direction: point(-2.0, -4.0),
                },
            ],
        }
        .validate()
        .is_err());
    }

    #[test]
    fn readonly_preview_has_same_sample_precision_as_strokes() {
        let item = Assistant {
            id: 1,
            name: "Guide".into(),
            visible: false,
            geometry: Geometry::Parallel {
                a: point(-100.0, -40.0),
                b: point(50.0, 30.0),
            },
        };
        let origin = point(72.234567891, -45.345678912);
        let end = point(234.123456789, 87.876543211);
        let actual = preview(item.spec(), origin, end, 7).unwrap();
        let mut guide = Constraint::new(&item, StrokeBinding { id: 1, revision: 7 }).unwrap();
        guide.project(sample(origin.x as f32, origin.y as f32, 0.5));
        let expected = guide.project(sample(end.x as f32, end.y as f32, 0.7));
        assert_eq!(
            actual["point"]["x"].as_f64().unwrap(),
            f64::from(expected.x)
        );
        assert_eq!(
            actual["point"]["y"].as_f64().unwrap(),
            f64::from(expected.y)
        );
        assert_eq!(actual["revision"], 7);
        assert!(preview(item.spec(), origin, point(16385.0, 0.0), 7).is_err());
    }
}
