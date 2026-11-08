use hayro_syntax::{Pdf, object::{Dict, Object, ObjectIdentifier, String as PdfString}};
use std::collections::{HashMap, HashSet};

pub fn read(path: &str) -> Result<String, String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    extract(bytes)
}

fn extract(bytes: Vec<u8>) -> Result<String, String> {
    let pdf = Pdf::new(bytes).map_err(|e| format!("{e:?}"))?;
    let xref = pdf.xref();
    let root = xref.get::<Dict<'_>>(xref.root_id()).ok_or("missing catalog")?;
    let pages: HashMap<_, _> = pdf.pages().iter().enumerate()
        .filter_map(|(i, page)| page.raw().obj_id().map(|id| (id, i))).collect();
    let mut result = Vec::new();
    let mut seen = HashSet::new();
    if let Some(outlines) = root.get::<Dict<'_>>(b"Outlines") {
        walk(outlines.get(b"First"), 0, &root, &pages, &mut seen, &mut result);
    }
    serde_json::to_string(&result).map_err(|e| e.to_string())
}

fn walk(mut next: Option<Dict<'_>>, level: usize, root: &Dict<'_>, pages: &HashMap<ObjectIdentifier, usize>,
    seen: &mut HashSet<ObjectIdentifier>, result: &mut Vec<serde_json::Value>) {
    if level > 32 { return; }
    while let Some(item) = next {
        if result.len() >= 4096 || seen.len() >= 8192 { break; }
        if let Some(id) = item.obj_id() { if !seen.insert(id) { break; } }
        let dest = item.get::<Object<'_>>(b"Dest").or_else(|| item.get::<Dict<'_>>(b"A")
            .filter(|a| a.get::<hayro_syntax::object::Name<'_>>(b"S").is_some_and(|n| n.as_ref() == b"GoTo"))
            .and_then(|a| a.get(b"D")));
        if let Some(title) = item.get::<PdfString<'_>>(b"Title") {
            if let Some(page) = dest.and_then(|d| destination(d, root, pages, 0)) {
                result.push(serde_json::json!({"title": text(title.as_bytes()), "page": page, "level": level}));
            }
        }
        walk(item.get(b"First"), level + 1, root, pages, seen, result);
        next = item.get(b"Next");
    }
}

fn destination(value: Object<'_>, root: &Dict<'_>, pages: &HashMap<ObjectIdentifier, usize>, depth: usize) -> Option<usize> {
    if depth > 32 { return None; }
    match value {
        Object::Array(array) => {
            if let Some(id) = array.raw_iter().next()?.as_obj_ref() { return pages.get(&id.into()).copied(); }
            let first = array.iter::<Object<'_>>().next()?;
            match first {
                Object::Dict(d) => pages.get(&d.obj_id()?).copied(),
                n => n.into_i32().filter(|n| *n >= 0 && (*n as usize) < pages.len()).map(|n| n as usize),
            }
        }
        Object::Dict(dict) => destination(dict.get(b"D")?, root, pages, depth + 1),
        Object::Name(name) => named(name.as_ref(), root, pages, depth),
        Object::String(name) => named(name.as_bytes(), root, pages, depth),
        _ => None,
    }
}

fn named(name: &[u8], root: &Dict<'_>, pages: &HashMap<ObjectIdentifier, usize>, depth: usize) -> Option<usize> {
    let direct = root.get::<Dict<'_>>(b"Dests").and_then(|d| d.get::<Object<'_>>(name));
    let value = direct.or_else(|| {
        let tree = root.get::<Dict<'_>>(b"Names")?.get::<Dict<'_>>(b"Dests")?;
        find_name(tree, name, 0, &mut 4096)
    })?;
    destination(value, root, pages, depth + 1)
}

fn find_name<'a>(dict: Dict<'a>, name: &[u8], depth: usize, budget: &mut usize) -> Option<Object<'a>> {
    if depth > 32 || *budget == 0 { return None; }
    *budget -= 1;
    if let Some(array) = dict.get::<hayro_syntax::object::Array<'_>>(b"Names") {
        let mut values = array.iter::<Object<'_>>();
        while let Some(key) = values.next() {
            let value = values.next()?;
            if key.into_string().is_some_and(|key| key.as_bytes() == name) { return Some(value); }
        }
    }
    for kid in dict.get::<hayro_syntax::object::Array<'_>>(b"Kids").into_iter().flat_map(|a| a.iter::<Dict<'_>>()) {
        if let Some(value) = find_name(kid, name, depth + 1, budget) { return Some(value); }
    }
    None
}

fn text(bytes: &[u8]) -> String {
    if bytes.starts_with(&[0xfe, 0xff]) {
        return String::from_utf16_lossy(&bytes[2..].chunks_exact(2).map(|v| u16::from_be_bytes([v[0], v[1]])).collect::<Vec<_>>());
    }
    if bytes.starts_with(&[0xef, 0xbb, 0xbf]) { return String::from_utf8_lossy(&bytes[3..]).into_owned(); }
    const SPECIAL: [char; 32] = ['•','†','‡','…','—','–','ƒ','⁄','‹','›','−','‰','„','“','”','‘','’','‚','™','ﬁ','ﬂ','Ł','Œ','Š','Ÿ','Ž','ı','ł','œ','š','ž','\u{fffd}'];
    bytes.iter().map(|b| match b {
        0x18 => '˘', 0x19 => 'ˇ', 0x1a => 'ˆ', 0x1b => '˙', 0x1c => '˝', 0x1d => '˛', 0x1e => '˚', 0x1f => '˜',
        0x80..=0x9f => SPECIAL[(*b - 0x80) as usize], 0xa0 => '€', _ => char::from(*b),
    }).collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn named_destination_unicode_title_and_cyclic_siblings() {
        let objects = [
            "<< /Type /Catalog /Pages 2 0 R /Outlines 7 0 R /Names << /Dests << /Names [(chapter-two) [4 0 R /Fit]] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 6 0 R >>",
            "<< /Length 0 >>\nstream\n\nendstream", "<< /Length 0 >>\nstream\n\nendstream",
            "<< /Type /Outlines /First 8 0 R /Last 9 0 R /Count 2 >>",
            "<< /Title (First) /Parent 7 0 R /Dest [3 0 R /Fit] /Next 9 0 R >>",
            "<< /Title <FEFF7B2C4E8C9875> /Parent 7 0 R /A << /S /GoTo /D (chapter-two) >> /Next 8 0 R >>",
        ];
        let mut pdf = String::from("%PDF-1.7\n");
        let mut offsets = vec![0];
        for (i, object) in objects.iter().enumerate() {
            offsets.push(pdf.len()); pdf.push_str(&format!("{} 0 obj\n{object}\nendobj\n", i + 1));
        }
        let xref = pdf.len();
        pdf.push_str(&format!("xref\n0 {}\n0000000000 65535 f \n", offsets.len()));
        for offset in offsets.iter().skip(1) { pdf.push_str(&format!("{offset:010} 00000 n \n")); }
        pdf.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", offsets.len()));
        let output: serde_json::Value = serde_json::from_str(&super::extract(pdf.into_bytes()).unwrap()).unwrap();
        assert_eq!(output.as_array().unwrap().len(), 2);
        assert_eq!(output[0]["page"], 0);
        assert_eq!(output[1]["page"], 1);
        assert_eq!(output[1]["title"], "第二页");
    }
}
