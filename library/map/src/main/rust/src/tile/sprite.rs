//! The POI icon atlas: one RGBA sprite sheet, decoded once and sampled every frame.
//!
//! The counterpart to [`crate::tile::glyph`] and deliberately shaped like it — a
//! process-global built at first use, a `*_staged()` guard, an `atlas()` accessor — but
//! much simpler, because a sprite sheet arrives already packed. There is no rasterising,
//! no distance transform and no per-glyph placement: the sheet says where each icon is,
//! and this turns those pixel rects into the normalised UVs the shader samples.
//!
//! # Why the `@2x` sheet, decoded at runtime
//!
//! Two sheets ship beside the fonts: `sprites.png` (256x114) and `sprites@2x.png`
//! (512x228). Only the `@2x` one is used. Every consumer app is `minSdk 31` and so at
//! least density 2, an icon is drawn at `width / pixelRatio` Dp, and the extra 12 KB buys
//! icons that stay sharp instead of being upscaled on every phone in the fleet.
//!
//! Decoding happens at first use rather than in `build.rs` for the same reason the fonts
//! are rasterised at first use: the compressed PNG is 28 KB, while the raw RGBA a build
//! script would bake into the `.so` is 512 x 228 x 4 = 466 KB. The decode is one inflate
//! and one unfilter pass over a quarter-megapixel image, once per process.
//!
//! Both sheets live under the **crate's** own `assets/` rather than `src/main/assets/`,
//! for the reason [`crate::tile::glyph`] gives: they are compiled in, so putting them in
//! the Android source set would package a second copy into every consumer's APK for
//! nothing. (`sprites.png`, the 1x sheet, is staged but read by nothing.)
//!
//! # Coordinates
//!
//! The sheet JSON is MapLibre's own sprite format: `{x, y, width, height, pixelRatio}` in
//! **sheet pixels**, y-down from the top-left, which is the same orientation
//! [`crate::tile::glyph::UvRect`] uses. `pixelRatio` is how many sheet pixels make one Dp,
//! so an icon's drawn size is `width / pixel_ratio` Dp — not `width`, which would draw
//! every icon at twice its intended size.

use crate::tile::glyph::UvRect;
use serde_json::Value as Json;
use std::collections::HashMap;
use std::sync::OnceLock;

/// The 2x sprite sheet, embedded. An APK asset is not a file the renderer can open, so
/// the bytes ship in the `.so` exactly as the fonts do.
const SHEET_PNG: &[u8] = include_bytes!("../../assets/sprites/sprites@2x.png");
/// Its index: 53 entries of `{x, y, width, height, pixelRatio}`.
const SHEET_JSON: &str = include_str!("../../assets/sprites/sprites@2x.json");

/// One icon in the sheet: where to sample it, and how big to draw it.
#[derive(Clone, Copy, Debug)]
pub struct Sprite {
    /// The sub-rect of the sheet this icon occupies, normalised 0..1.
    pub uv: UvRect,
    /// Drawn size in **Dp** — the sheet size divided by `pixelRatio`.
    ///
    /// Dp rather than sheet pixels because that is the unit the style is authored in and
    /// the unit `icon-size` multiplies; the renderer scales by density on the way to the
    /// shader, as it does for a text size.
    pub width_dp: f32,
    pub height_dp: f32,
}

/// Whether the staged sheet is a real PNG rather than a 404 page.
///
/// The fonts were once staged as GitHub error HTML under a `.ttf` name
/// ([`crate::tile::glyph::fonts_staged`] exists for that reason), so the same guard is
/// here: a caller that cannot draw icons should say so rather than panic in a decoder.
pub fn sprites_staged() -> bool {
    SHEET_PNG.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A])
}

/// The decoded sheet plus its index.
pub struct SpriteAtlas {
    /// Row-major RGBA8, `width * height * 4` bytes.
    pub pixels: Vec<u8>,
    pub width: u32,
    pub height: u32,
    sprites: HashMap<String, Sprite>,
}

impl SpriteAtlas {
    /// Decode the sheet and parse its index. Called once per process.
    ///
    /// A failure yields an empty atlas rather than a panic: an icon that does not draw is
    /// a worse map, but a renderer that will not start is no map at all. The reason is
    /// returned so the caller can log it once.
    pub fn build() -> Result<SpriteAtlas, String> {
        if !sprites_staged() {
            return Err("the staged sprite sheet is not a PNG".into());
        }
        let (pixels, width, height) = decode_rgba8(SHEET_PNG)?;
        let index: Json = serde_json::from_str(SHEET_JSON)
            .map_err(|e| format!("the sprite index is not JSON: {e}"))?;
        let entries = index
            .as_object()
            .ok_or("the sprite index is not an object of name -> rect")?;
        let mut sprites = HashMap::with_capacity(entries.len());
        for (name, entry) in entries {
            let number = |key: &str| -> Result<f32, String> {
                entry
                    .get(key)
                    .and_then(Json::as_f64)
                    .map(|v| v as f32)
                    .ok_or_else(|| format!("sprite `{name}` has no numeric `{key}`"))
            };
            let (x, y) = (number("x")?, number("y")?);
            let (w, h) = (number("width")?, number("height")?);
            // A sheet that says an icon lies outside itself would sample a neighbour, or
            // wrap — a wrong icon rather than a missing one, which is harder to notice.
            if w <= 0.0 || h <= 0.0 || x < 0.0 || y < 0.0
                || x + w > width as f32
                || y + h > height as f32
            {
                return Err(format!(
                    "sprite `{name}` at {x},{y} {w}x{h} does not fit a {width}x{height} sheet"
                ));
            }
            // Absent rather than an error: `pixelRatio` is optional in the sprite format
            // and defaults to 1, and every entry in the staged sheet declares 2 anyway.
            let ratio = entry.get("pixelRatio").and_then(Json::as_f64).unwrap_or(1.0) as f32;
            let ratio = if ratio > 0.0 { ratio } else { 1.0 };
            let _ = sprites.insert(
                name.clone(),
                Sprite {
                    uv: UvRect {
                        u0: x / width as f32,
                        v0: y / height as f32,
                        u1: (x + w) / width as f32,
                        v1: (y + h) / height as f32,
                    },
                    width_dp: w / ratio,
                    height_dp: h / ratio,
                },
            );
        }
        Ok(SpriteAtlas { pixels, width, height, sprites })
    }

    /// An empty atlas: no pixels, no icons. What a failed decode leaves behind, so every
    /// lookup misses and POI labels draw without their icons.
    fn empty() -> SpriteAtlas {
        SpriteAtlas { pixels: Vec::new(), width: 0, height: 0, sprites: HashMap::new() }
    }

    /// The icon named `name`, or `None` when the sheet does not carry one.
    ///
    /// A miss is normal and must stay non-fatal: the reference style's `pois` layer names
    /// `townhall`, which has no sprite in this sheet, and MapLibre draws that POI as a
    /// label with no icon rather than dropping it.
    pub fn get(&self, name: &str) -> Option<Sprite> {
        self.sprites.get(name).copied()
    }

    /// How many icons the sheet carries. For diagnostics and tests.
    pub fn len(&self) -> usize {
        self.sprites.len()
    }

    pub fn is_empty(&self) -> bool {
        self.sprites.is_empty()
    }
}

/// Decode a PNG to tightly-packed RGBA8.
///
/// `EXPAND` normalises palette, greyscale and sub-8-bit inputs to 8-bit colour, so the
/// only cases left here are RGB (widened) and RGBA (taken as is). Anything else is a sheet
/// this renderer was not given, and is refused rather than sampled as noise.
fn decode_rgba8(bytes: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    // `Cursor`, because png 0.18's `Decoder` takes `BufRead + Seek` and a `&[u8]` is only
    // the former.
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().map_err(|e| format!("sprite PNG header: {e}"))?;
    let mut buffer = vec![0u8; reader.output_buffer_size().unwrap_or(0)];
    let info = reader.next_frame(&mut buffer).map_err(|e| format!("sprite PNG data: {e}"))?;
    if info.bit_depth != png::BitDepth::Eight {
        return Err(format!("the sprite sheet is {:?}, not 8-bit", info.bit_depth));
    }
    let (width, height) = (info.width, info.height);
    let texels = (width as usize) * (height as usize);
    let pixels = match info.color_type {
        png::ColorType::Rgba => {
            buffer.truncate(texels * 4);
            buffer
        }
        png::ColorType::Rgb => {
            let mut out = Vec::with_capacity(texels * 4);
            for rgb in buffer.chunks_exact(3).take(texels) {
                out.extend_from_slice(&[rgb[0], rgb[1], rgb[2], 0xFF]);
            }
            out
        }
        other => return Err(format!("the sprite sheet is {other:?}, not RGB or RGBA")),
    };
    if pixels.len() != texels * 4 {
        return Err(format!(
            "the sprite sheet decoded to {} bytes for {width}x{height}",
            pixels.len()
        ));
    }
    Ok((pixels, width, height))
}

/// The process-wide sprite atlas, built once.
///
/// Empty when the sheet will not decode, so callers need no `Result`: a lookup simply
/// misses and the POI draws label-only, which is the same path `townhall` already takes.
pub fn atlas() -> &'static SpriteAtlas {
    static ATLAS: OnceLock<SpriteAtlas> = OnceLock::new();
    ATLAS.get_or_init(|| {
        SpriteAtlas::build().unwrap_or_else(|e| {
            // `eprintln` rather than `bridge::log`, as in `vulkan::renderer`: the android
            // logger does not link into the host test binary.
            eprintln!("sprite atlas unavailable: {e}");
            SpriteAtlas::empty()
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The reference style's `pois` layer names 36 kinds. Every one of them must resolve
    /// to a sprite or be a stated exception, or a POI draws with the wrong icon (a
    /// neighbouring rect) or none at all.
    const POI_KINDS: &[&str] = &[
        "beach", "forest", "marina", "park", "peak", "zoo", "garden", "bench", "aerodrome",
        "station", "bus_stop", "ferry_terminal", "stadium", "university", "library", "school",
        "animal", "toilets", "drinking_water", "post_office", "building", "townhall",
        "restaurant", "fast_food", "cafe", "bar", "supermarket", "convenience", "books",
        "beauty", "electronics", "clothes", "attraction", "museum", "theatre", "artwork",
    ];

    #[test]
    fn the_staged_sheet_is_a_png_that_decodes() {
        assert!(sprites_staged(), "the staged sheet is not a PNG");
        let atlas = SpriteAtlas::build().expect("the staged sheet should decode");
        assert_eq!((atlas.width, atlas.height), (512, 228), "the 2x sheet's declared size");
        assert_eq!(atlas.pixels.len(), 512 * 228 * 4, "RGBA8, tightly packed");
        assert_eq!(atlas.len(), 53, "the staged index's entry count");
    }

    /// The whole point of the module: a name from the style resolves to a rect.
    #[test]
    fn every_poi_kind_resolves_to_a_sprite_or_a_stated_exception() {
        let atlas = atlas();
        for &kind in POI_KINDS {
            // The reference `icon-image` is `match(kind, "station", "train_station", kind)`,
            // so `station` is the one renamed lookup. `townhall` genuinely has no sprite,
            // and MapLibre draws it label-only.
            let name = if kind == "station" { "train_station" } else { kind };
            let found = atlas.get(name).is_some();
            if kind == "townhall" {
                assert!(!found, "townhall gained a sprite; the label-only path can go");
                continue;
            }
            assert!(found, "`{kind}` resolves to `{name}`, which the sheet does not carry");
        }
    }

    /// A rect that escaped its sheet would sample a neighbouring icon, which reads as the
    /// wrong icon rather than a missing one — much harder to spot on a map.
    #[test]
    fn every_uv_rect_lies_inside_the_sheet_and_is_not_degenerate() {
        let atlas = atlas();
        for &kind in POI_KINDS {
            let name = if kind == "station" { "train_station" } else { kind };
            let Some(sprite) = atlas.get(name) else { continue };
            let uv = sprite.uv;
            assert!(uv.u0 < uv.u1 && uv.v0 < uv.v1, "{name} has an empty rect");
            assert!(uv.u0 >= 0.0 && uv.v0 >= 0.0, "{name} starts outside the sheet");
            assert!(uv.u1 <= 1.0 && uv.v1 <= 1.0, "{name} runs past the sheet");
        }
    }

    /// `pixelRatio` is the difference between a 19 Dp icon and a 38 Dp one, and getting it
    /// wrong draws every icon at double size beside correctly-sized text.
    #[test]
    fn a_sprites_drawn_size_is_its_sheet_size_over_its_pixel_ratio() {
        let park = atlas().get("park").expect("park is in the sheet");
        // 38x38 at pixelRatio 2 in the staged index.
        assert!((park.width_dp - 19.0).abs() < 1e-6, "{}", park.width_dp);
        assert!((park.height_dp - 19.0).abs() < 1e-6, "{}", park.height_dp);
        // And the rect really is 38 sheet px wide, so the two agree.
        let uv_px = (park.uv.u1 - park.uv.u0) * 512.0;
        assert!((uv_px - 38.0).abs() < 1e-3, "{uv_px} sheet px");
    }

    #[test]
    fn an_unknown_name_misses_rather_than_returning_a_neighbour() {
        assert!(atlas().get("not_an_icon").is_none());
        assert!(atlas().get("").is_none());
    }

    /// A sheet that will not decode must leave an atlas that answers every lookup with
    /// `None`, because that is the path the renderer relies on to keep drawing labels.
    #[test]
    fn a_failed_decode_leaves_an_atlas_that_simply_has_no_icons() {
        let empty = SpriteAtlas::empty();
        assert!(empty.is_empty());
        assert_eq!(empty.len(), 0);
        assert!(empty.get("park").is_none());
    }

    #[test]
    fn a_sheet_that_is_not_a_png_is_refused_rather_than_decoded() {
        assert!(decode_rgba8(b"<!DOCTYPE html>").is_err());
        assert!(decode_rgba8(&[]).is_err());
    }
}
