use crate::{groups::Hierarchy, model::*, vector::*};
use std::fmt::Write;

pub fn svg(doc: &Document, id: u32) -> Result<Vec<u8>, String> {
    doc.validate()?;
    let index = doc
        .layers
        .iter()
        .position(|layer| layer.id == id)
        .ok_or("图层不存在")?;
    let layer = &doc.layers[index];
    let vector = layer.vector()?;
    if !layer.masks.is_empty() || layer.clipping || layer.blend != BlendMode::Normal {
        return Err("SVG 导出暂不能保留图层蒙版、剪贴或非普通混合效果".into());
    }
    let hierarchy = Hierarchy::new(doc)?;
    let mut parent = hierarchy.parent[index];
    while let Some(index) = parent {
        let ancestor = &doc.layers[index];
        if ancestor.opacity != 1.0
            || !ancestor.masks.is_empty()
            || ancestor.clipping
            || ancestor.blend != BlendMode::Normal
        {
            return Err("SVG 导出暂不能保留父级不透明度、蒙版、剪贴或混合效果".into());
        }
        parent = hierarchy.parent[index];
    }
    let mut output = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{}\" height=\"{}\" viewBox=\"0 0 {} {}\" overflow=\"hidden\"><g id=\"layer-{id}\" opacity=\"{}\"",
        doc.width, doc.height, doc.width, doc.height, layer.opacity,
    );
    if !hierarchy.visible[index] {
        output.push_str(" display=\"none\"");
    }
    write!(output, "><title>{}</title>", escaped(&layer.name)?).unwrap();
    for object in &vector.objects {
        shape(&mut output, &object.geometry);
        write!(
            output,
            " id=\"object-{}\" transform=\"matrix({} {} {} {} {} {})\"",
            object.id,
            object.transform[0],
            object.transform[1],
            object.transform[2],
            object.transform[3],
            object.transform[4],
            object.transform[5]
        )
        .unwrap();
        if !object.visible {
            output.push_str(" display=\"none\"");
        }
        match object.style.fill {
            Some(color) => paint(&mut output, "fill", color),
            None => output.push_str(" fill=\"none\""),
        }
        write!(
            output,
            " fill-rule=\"{}\"",
            match object.style.fill_rule {
                FillRule::NonZero => "nonzero",
                FillRule::EvenOdd => "evenodd",
            }
        )
        .unwrap();
        match &object.style.stroke {
            Some(stroke) => {
                paint(&mut output, "stroke", stroke.color);
                write!(output, " stroke-width=\"{}\" stroke-linecap=\"{}\" stroke-linejoin=\"{}\" stroke-miterlimit=\"{}\"",
                    stroke.width, match stroke.cap {Cap::Butt=>"butt",Cap::Round=>"round",Cap::Square=>"square"},
                    match stroke.join {Join::Miter=>"miter",Join::Round=>"round",Join::Bevel=>"bevel"},stroke.miter_limit).unwrap();
            }
            None => output.push_str(" stroke=\"none\""),
        }
        write!(
            output,
            "><title>{}</title></{}>",
            escaped(&object.name)?,
            object.kind()
        )
        .unwrap();
    }
    output.push_str("</g></svg>");
    Ok(output.into_bytes())
}

fn escaped(value: &str) -> Result<String, String> {
    let mut output = String::new();
    for character in value.chars() {
        match character {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '"' => output.push_str("&quot;"),
            '\'' => output.push_str("&apos;"),
            '\t'
            | '\n'
            | '\r'
            | '\u{20}'..='\u{d7ff}'
            | '\u{e000}'..='\u{fffd}'
            | '\u{10000}'..='\u{10ffff}' => output.push(character),
            _ => return Err("SVG 名称含有不能保存为 XML 的字符".into()),
        }
    }
    Ok(output)
}

fn paint(output: &mut String, kind: &str, color: [u8; 4]) {
    write!(
        output,
        " {kind}=\"#{:02x}{:02x}{:02x}\" {kind}-opacity=\"{}\"",
        color[0],
        color[1],
        color[2],
        f64::from(color[3]) / 255.0
    )
    .unwrap();
}

fn shape(output: &mut String, geometry: &Geometry) {
    match geometry {
        Geometry::Rect {
            x,
            y,
            width,
            height,
        } => write!(
            output,
            "<rect x=\"{x}\" y=\"{y}\" width=\"{width}\" height=\"{height}\""
        )
        .unwrap(),
        Geometry::Ellipse { cx, cy, rx, ry } => write!(
            output,
            "<ellipse cx=\"{cx}\" cy=\"{cy}\" rx=\"{rx}\" ry=\"{ry}\""
        )
        .unwrap(),
        Geometry::Line { x1, y1, x2, y2 } => write!(
            output,
            "<line x1=\"{x1}\" y1=\"{y1}\" x2=\"{x2}\" y2=\"{y2}\""
        )
        .unwrap(),
        Geometry::Path { segments } => {
            output.push_str("<path d=\"");
            for segment in segments {
                match segment {
                    Segment::MoveTo { x, y } => write!(output, "M {x} {y} ").unwrap(),
                    Segment::LineTo { x, y } => write!(output, "L {x} {y} ").unwrap(),
                    Segment::QuadTo { cx, cy, x, y } => {
                        write!(output, "Q {cx} {cy} {x} {y} ").unwrap()
                    }
                    Segment::CubicTo {
                        c1x,
                        c1y,
                        c2x,
                        c2y,
                        x,
                        y,
                    } => write!(output, "C {c1x} {c1y} {c2x} {c2y} {x} {y} ").unwrap(),
                    Segment::Close => output.push_str("Z "),
                }
            }
            output.push('"');
        }
    }
}
