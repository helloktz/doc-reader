use hayro_interpret::{
    BlendMode, CacheKey, ClipPath, Context, Device, GlyphDrawMode, Image, InterpreterCache,
    InterpreterSettings, InterpreterWarning, Paint, PathDrawMode, SoftMask, TransformExt,
    font::Glyph, interpret_page,
};
use hayro_syntax::Pdf;
use kurbo::{Affine, BezPath, Rect, Shape};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};

/// Horizontal limits in each page's displayed coordinate system, normalized by
/// page width before unioning. This matches the reader's mixed-size page layout.
pub struct TextBounds {
    pub horizontal: [f64; 2],
    pub vertical: Vec<Vec<[f64; 2]>>,
}

pub fn read(path: &str, cancelled: impl FnMut() -> bool) -> Result<Option<TextBounds>, String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    extract(bytes, cancelled)
}

fn extract(
    bytes: Vec<u8>,
    mut cancelled: impl FnMut() -> bool,
) -> Result<Option<TextBounds>, String> {
    let pdf = Pdf::new(bytes).map_err(|e| format!("{e:?}"))?;
    let cache = InterpreterCache::new();
    let unsupported = Arc::new(AtomicBool::new(false));
    let flag = unsupported.clone();
    let settings = InterpreterSettings {
        warning_sink: Arc::new(move |warning| {
            if matches!(warning, InterpreterWarning::UnsupportedFont) {
                flag.store(true, Ordering::Relaxed);
            }
        }),
        ..Default::default()
    };
    let mut glyphs = HashMap::new();
    let mut result: Option<[f64; 2]> = None;
    let mut vertical = Vec::with_capacity(pdf.pages().len());
    for page in pdf.pages().iter() {
        if cancelled() {
            return Err("cancelled".into());
        }
        let (width, height) = page.render_dimensions();
        let area = Rect::new(0.0, 0.0, f64::from(width), f64::from(height));
        let mut context = Context::new(
            page.initial_transform(true).to_kurbo(),
            area,
            &cache,
            pdf.xref(),
            settings.clone(),
        );
        let mut device = TextDevice {
            clips: vec![area],
            glyphs: &mut glyphs,
            bounds: None,
            rows: Vec::new(),
            type3_depth: 0,
        };
        interpret_page(page, &mut context, &mut device);
        device.rows.sort_unstable_by(|a, b| a[0].total_cmp(&b[0]));
        let mut rows: Vec<[f64; 2]> = Vec::new();
        for [top, bottom] in &device.rows {
            if let Some(last) = rows.last_mut().filter(|last| *top <= last[1]) {
                last[1] = last[1].max(*bottom);
            } else {
                rows.push([*top, *bottom]);
            }
        }
        vertical.push(
            rows.into_iter()
                .map(|r| [r[0] / f64::from(width), r[1] / f64::from(width)])
                .collect(),
        );
        if let Some(bounds) = device.bounds {
            let left = bounds.x0 / f64::from(width);
            let right = bounds.x1 / f64::from(width);
            if let Some(total) = &mut result {
                total[0] = total[0].min(left);
                total[1] = total[1].max(right);
            } else {
                result = Some([left, right]);
            }
        }
    }
    if unsupported.load(Ordering::Relaxed) {
        return Err("无法完整读取此文档的文字边界".into());
    }
    Ok(result.map(|horizontal| TextBounds {
        horizontal,
        vertical,
    }))
}

#[cfg(test)]
mod tests {
    #[test]
    fn unions_later_pages_normalizes_mixed_sizes_and_ignores_graphics() {
        let result = super::extract(
            include_bytes!("../../app/src/androidTest/assets/text-fit.pdf").to_vec(),
            || false,
        )
        .unwrap()
        .unwrap();
        assert_eq!(result.vertical.len(), 4);
        assert!(result.vertical[3].is_empty());
        let bounds = result.horizontal;
        assert!(
            (0.10..0.11).contains(&bounds[0]),
            "leftmost text is on page two: {bounds:?}"
        );
        assert!(
            (0.82..0.84).contains(&bounds[1]),
            "rightmost text is on page three: {bounds:?}"
        );
    }

    #[test]
    fn respects_crop_rotation_and_page_clips() {
        let bounds = super::extract(
            include_bytes!("../../app/src/androidTest/assets/text-fit-rotated.pdf").to_vec(),
            || false,
        )
        .unwrap()
        .unwrap()
        .horizontal;
        assert!((0.24..0.27).contains(&bounds[0]), "{bounds:?}");
        assert!((0.28..0.30).contains(&bounds[1]), "{bounds:?}");
    }

    #[test]
    fn no_text_is_distinct_from_a_parse_failure_or_cancellation() {
        let bytes = include_bytes!("../../app/src/androidTest/assets/text-fit-empty.pdf");
        assert!(super::extract(bytes.to_vec(), || false).unwrap().is_none());
        assert!(super::extract(bytes.to_vec(), || true).is_err());
        assert!(super::extract(b"invalid".to_vec(), || false).is_err());
    }
}

struct TextDevice<'g> {
    clips: Vec<Rect>,
    glyphs: &'g mut HashMap<u128, Option<Rect>>,
    bounds: Option<Rect>,
    rows: Vec<[f64; 2]>,
    type3_depth: usize,
}

impl TextDevice<'_> {
    fn include(&mut self, bounds: Rect) {
        let clipped = bounds.intersect(*self.clips.last().unwrap());
        if clipped.is_finite() && clipped.width() > 0.0 && clipped.height() > 0.0 {
            self.bounds = Some(self.bounds.map_or(clipped, |old| old.union(clipped)));
            self.rows.push([clipped.y0, clipped.y1]);
        }
    }
}

impl<'a> Device<'a> for TextDevice<'_> {
    fn draw_glyph(
        &mut self,
        glyph: &Glyph<'a>,
        transform: Affine,
        glyph_transform: Affine,
        paint: &Paint<'a>,
        mode: &GlyphDrawMode,
    ) {
        match glyph {
            Glyph::Outline(glyph) => {
                let bounds = *self
                    .glyphs
                    .entry(glyph.identifier().cache_key())
                    .or_insert_with(|| {
                        let path = glyph.outline();
                        if path.elements().is_empty() {
                            None
                        } else {
                            Some(path.bounding_box())
                        }
                    });
                if let Some(bounds) = bounds {
                    let mut bounds = (transform * glyph_transform).transform_rect_bbox(bounds);
                    if let GlyphDrawMode::Stroke(stroke) = mode {
                        let c = transform.as_coeffs();
                        bounds = bounds.inflate(
                            f64::from(stroke.line_width) * c[0].hypot(c[1]).max(c[2].hypot(c[3]))
                                / 2.0,
                            f64::from(stroke.line_width) * c[0].hypot(c[1]).max(c[2].hypot(c[3]))
                                / 2.0,
                        );
                    }
                    self.include(bounds);
                }
            }
            Glyph::Type3(glyph) => {
                self.type3_depth += 1;
                glyph.interpret(self, transform, glyph_transform, paint);
                self.type3_depth -= 1;
            }
        }
    }
    fn draw_path(&mut self, path: &BezPath, transform: Affine, _: &Paint<'a>, _: &PathDrawMode) {
        if self.type3_depth > 0 && !path.elements().is_empty() {
            self.include((transform * path).bounding_box());
        }
    }
    fn draw_image(&mut self, _: Image<'a, '_>, transform: Affine) {
        if self.type3_depth > 0 {
            self.include(transform.transform_rect_bbox(Rect::new(0.0, 0.0, 1.0, 1.0)));
        }
    }
    fn push_clip_path(&mut self, clip: &ClipPath) {
        self.clips.push(
            self.clips
                .last()
                .unwrap()
                .intersect(clip.path.bounding_box()),
        );
    }
    fn pop_clip_path(&mut self) {
        if self.clips.len() > 1 {
            self.clips.pop();
        }
    }
    fn set_soft_mask(&mut self, _: Option<SoftMask<'a>>) {}
    fn set_blend_mode(&mut self, _: BlendMode) {}
    fn push_transparency_group(&mut self, _: f32, _: Option<SoftMask<'a>>, _: BlendMode) {}
    fn pop_transparency_group(&mut self) {}
}
