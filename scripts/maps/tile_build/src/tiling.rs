//! Tile a point layer, and merge tilesets — the `tippecanoe` and `tile-join`
//! replacements.
//!
//! Points need no clipping, simplification or winding-order handling, so this
//! module stays as simple as a point layer allows. The geometry core that lines and
//! polygons need lives in [`crate::geom`], [`crate::clip`] and
//! [`crate::simplify`]; [`merge_tiles`] still never looks inside a geometry stream,
//! which is what makes a composite lossless.

use crate::geom::project;
use crate::mvt::{self, Feature, GeomType, Layer, Tile, Value, DEFAULT_EXTENT};
use crate::pmtiles::{self, Archive, Builder};
use crate::progress::Progress;
use std::path::{Path, PathBuf};
use crate::proto::Result;
use std::collections::HashMap;

/// One point to be tiled.
#[derive(Debug, Clone)]
pub struct Point {
    pub lon: f64,
    pub lat: f64,
    pub props: Vec<(String, Value)>,
}

/// Bucket points into tiles for one zoom and encode each as an MVT.
///
/// Returns `(tile_id, mvt body)`. Points landing outside the tile grid are
/// dropped rather than clamped into the edge tile, which would pile every bad
/// coordinate onto one pin.
pub fn tile_points(
    layer_name: &str,
    points: &[Point],
    z: u8,
    extent: u32,
) -> Vec<(u64, Vec<u8>)> {
    let n = 1u64 << z;
    let mut by_tile: HashMap<(u64, u64), Vec<(i32, i32, usize)>> = HashMap::new();
    for (i, p) in points.iter().enumerate() {
        let (fx, fy) = project(p.lon, p.lat, z);
        if !fx.is_finite() || !fy.is_finite() {
            continue;
        }
        let (tx, ty) = (fx.floor(), fy.floor());
        if tx < 0.0 || ty < 0.0 || tx >= n as f64 || ty >= n as f64 {
            continue;
        }
        let (tx, ty) = (tx as u64, ty as u64);
        // Position within the tile, quantised to the extent grid.
        let px = ((fx - tx as f64) * extent as f64).round() as i32;
        let py = ((fy - ty as f64) * extent as f64).round() as i32;
        by_tile.entry((tx, ty)).or_default().push((
            px.clamp(0, extent as i32),
            py.clamp(0, extent as i32),
            i,
        ));
    }

    let mut out = Vec::with_capacity(by_tile.len());
    for ((tx, ty), mut pts) in by_tile {
        // Deterministic feature order, so a rebuild is byte-identical.
        pts.sort_by_key(|&(x, y, i)| (x, y, i));
        let mut layer = Layer::new(layer_name);
        layer.extent = extent;
        for (x, y, i) in pts {
            layer.features.push(Feature {
                id: None,
                geom_type: GeomType::Point,
                geometry: mvt::encode_points(&[(x, y)]),
                props: points[i].props.clone(),
            });
        }
        let tile = Tile { layers: vec![layer] };
        out.push((pmtiles::tile_id(z, tx, ty), tile.encode()));
    }
    out.sort_by_key(|(id, _)| *id);
    out
}

/// Build a single-layer point archive across `min_zoom..=max_zoom`, silently.
pub fn build_point_archive(
    layer_name: &str,
    points: &[Point],
    min_zoom: u8,
    max_zoom: u8,
) -> Result<Vec<u8>> {
    build_point_archive_with(layer_name, points, min_zoom, max_zoom, false)
}

/// As [`build_point_archive`], with a per-zoom progress bar when `progress`.
///
/// The binaries pass true and the library defaults to false, so tests stay quiet.
/// Worth having for points and not only for lines: `ma_pois` is 22.6 M features at
/// planet scale and used to print nothing at all between its start and its report.
pub fn build_point_archive_with(
    layer_name: &str,
    points: &[Point],
    min_zoom: u8,
    max_zoom: u8,
    progress: bool,
) -> Result<Vec<u8>> {
    let mut b = Builder::new();
    b.min_zoom = min_zoom;
    b.max_zoom = max_zoom;
    b.center_zoom = min_zoom;
    b.metadata = point_metadata(layer_name, min_zoom, max_zoom).into_bytes();
    for z in min_zoom..=max_zoom {
        // `tile_points` buckets and MVT-encodes the whole zoom before returning, so
        // the bar covers the gzip loop -- which is where the time goes -- and the
        // bucketing shows as a pause before the bar appears.
        let tiles = tile_points(layer_name, points, z, DEFAULT_EXTENT);
        let mut bar = Progress::new(format!("{layer_name} z{z}"), tiles.len(), TILES, progress);
        for (id, body) in tiles {
            bar.tick(TILES);
            b.add_tile_raw(id, crate::gz::compress(&body));
        }
        bar.finish(TILES);
    }
    b.build()
}

/// Points are bucketed before they are counted, so unlike the pyramid's candidates
/// every one of these really is written.
const TILES: &str = "tile(s)";

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it
/// to render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful vector_layers list keeps the archive introspectable.
fn point_metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

/// Merge several archives straight to `out`, holding no archive in memory.
///
/// The in-memory [`merge_archives`] is fine for a metro extract and cannot do a planet
/// overlay join: it copies every input tile into a `HashMap`, then [`Builder`] keeps
/// three more copies of every body. Measured on the real 7-layer overlay merge — 36.8 M
/// tiles, 17 GB of inputs — that is roughly 76 GB and gets killed.
///
/// This keeps only a `(tile_id, offset, length)` row per input tile (12 bytes) and one
/// tile body at a time, so peak memory is set by the tile COUNT: about 1.5 GB for that
/// same merge, versus 76 GB.
///
/// Inputs are still read into memory by the CALLER, because a PMTiles body can only be
/// found through its directory and the directories live in the same file. That is the
/// remaining ceiling and it is the size of the inputs, not a multiple of it.
pub fn merge_archives_to(
    inputs: &[&Archive],
    out: impl AsRef<Path>,
    scratch: impl Into<PathBuf>,
    progress: bool,
) -> Result<()> {
    let mut min_zoom = u8::MAX;
    let mut max_zoom = 0u8;
    let mut bounds: Option<(i32, i32, i32, i32)> = None;
    for a in inputs {
        let h = &a.header;
        min_zoom = min_zoom.min(h.min_zoom);
        max_zoom = max_zoom.max(h.max_zoom);
        bounds = Some(match bounds {
            None => (h.min_lon_e7, h.min_lat_e7, h.max_lon_e7, h.max_lat_e7),
            Some((w, s, e, n)) => (
                w.min(h.min_lon_e7),
                s.min(h.min_lat_e7),
                e.max(h.max_lon_e7),
                n.max(h.max_lat_e7),
            ),
        });
    }

    // One row per input tile, sorted by (tile_id, input index) so a tile's sources come
    // out in input order -- which is what makes a later input win a layer collision.
    //
    // The offset is u64. A planet layer's data section is well past u32::MAX, so a
    // narrower field wraps every body beyond 4 GiB onto the wrong bytes.
    let mut rows: Vec<(u64, u32, u64, u32)> = Vec::new();
    for (i, a) in inputs.iter().enumerate() {
        for (id, off, len) in a.tile_offsets()? {
            rows.push((id, i as u32, off, len));
        }
    }
    rows.sort_unstable_by_key(|(id, i, _, _)| (*id, *i));

    let mut b = pmtiles::StreamBuilder::new(scratch)?;
    b.min_zoom = if min_zoom == u8::MAX { 0 } else { min_zoom };
    b.max_zoom = max_zoom;
    b.center_zoom = b.min_zoom;
    if let Some((w, s, e, n)) = bounds {
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
    }
    if !inputs.is_empty() {
        b.metadata = merge_metadata(inputs);
    }

    let mut bar = Progress::new("merging".to_string(), rows.len(), TILES, progress);
    let mut k = 0usize;
    while k < rows.len() {
        let id = rows[k].0;
        let mut j = k;
        while j < rows.len() && rows[j].0 == id {
            j += 1;
        }
        let group = &rows[k..j];
        for _ in group {
            bar.tick(TILES);
        }

        if let [(_, i, off, len)] = group {
            let src = inputs[*i as usize];
            if src.header.tile_compression == pmtiles::COMPRESSION_GZIP {
                // Sole owner, already gzip: the producer's bytes go straight out.
                b.add_tile_raw(id, src.body_at(*off, *len)?)?;
                k = j;
                continue;
            }
        }
        let mut decoded: Vec<Vec<u8>> = Vec::with_capacity(group.len());
        for (_, i, off, len) in group {
            let src = inputs[*i as usize];
            let raw = src.body_at(*off, *len)?;
            decoded.push(match src.header.tile_compression {
                pmtiles::COMPRESSION_NONE => raw.to_vec(),
                _ => crate::gz::decompress(raw)?,
            });
        }
        let merged = if decoded.len() == 1 {
            decoded.pop().unwrap_or_default()
        } else {
            merge_tiles(&decoded)?
        };
        b.add_tile_raw(id, &crate::gz::compress(&merged))?;
        k = j;
    }
    bar.finish(TILES);
    b.finish(out)
}

/// Merge several tilesets into one, unioning each tile's layers.
///
/// This is the `tile-join` replacement. Later inputs win a name collision, so a
/// freshly built layer replaces a stale copy of itself in the base archive. Tiles
/// present in only one input are carried through as-is.
///
/// Both the decode and the re-encode are lossless for geometry, which is what lets
/// the base tileset's lines and polygons survive a merge untouched.
/// Zoom range, bounds, centre and the `vector_layers` list are all derived from every
/// input rather than taken from any one of them. That matters because the header is
/// what a client reads to decide which tiles to even ask for: an overlay-only merge
/// has no base to inherit a world-sized envelope from, so whichever overlay happened
/// to be listed first would otherwise clip every other layer to its own extent — and
/// point a viewer at its own centre.
///
/// A tile only ONE input holds is copied through as the producer's own compressed
/// bytes: no inflate, no deflate, no MVT round trip. That is the overwhelming
/// majority of tiles in an overlay-only merge, because the layers are disjoint and so
/// are most of their tile sets — and deflate at [`crate::gz`]'s archive level is by
/// far the most expensive thing this function can do. It also keeps peak memory down,
/// since those tiles stay compressed while they wait.
/// Merge several tilesets into one IN MEMORY, returning the archive bytes.
///
/// Kept as the reference implementation and the oracle the streaming
/// [`merge_archives_to`] is pinned against byte for byte. Use that one for anything
/// large: this holds several copies of every tile body and cannot do a planet join.
pub fn merge_archives(inputs: &[&Archive]) -> Result<Vec<u8>> {
    let mut min_zoom = u8::MAX;
    let mut max_zoom = 0u8;
    // (west, south, east, north), in e7 degrees.
    let mut bounds: Option<(i32, i32, i32, i32)> = None;
    // tile_id -> each input's STILL-COMPRESSED tile, in input order, paired with
    // that input's `tile_compression` so the decode below stays faithful to it.
    let mut collected: HashMap<u64, Vec<(Vec<u8>, u8)>> = HashMap::new();
    for a in inputs {
        let h = &a.header;
        min_zoom = min_zoom.min(h.min_zoom);
        max_zoom = max_zoom.max(h.max_zoom);
        bounds = Some(match bounds {
            None => (h.min_lon_e7, h.min_lat_e7, h.max_lon_e7, h.max_lat_e7),
            Some((w, s, e, n)) => (
                w.min(h.min_lon_e7),
                s.min(h.min_lat_e7),
                e.max(h.max_lon_e7),
                n.max(h.max_lat_e7),
            ),
        });
        // Only gzip is pass-through-able, because gzip is what the builder writes.
        // Anything else still has to be decoded, and labelling e.g. zstd bytes as
        // gzip would produce an archive no reader could open.
        for (id, raw) in a.iter_tiles()? {
            collected.entry(id).or_default().push((raw.to_vec(), h.tile_compression));
        }
    }

    let mut b = Builder::new();
    b.min_zoom = if min_zoom == u8::MAX { 0 } else { min_zoom };
    b.max_zoom = max_zoom;
    // Shallowest zoom any input holds, matching what every layer builder sets for
    // itself. Inheriting the first input's would pair a unioned bbox with an
    // unrelated zoom.
    b.center_zoom = b.min_zoom;
    if let Some((w, s, e, n)) = bounds {
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
    }
    if !inputs.is_empty() {
        b.metadata = merge_metadata(inputs);
    }

    let mut ids: Vec<u64> = collected.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let bodies = &collected[&id];
        // Sole owner, already in the compression the builder writes: hand the
        // producer's bytes straight through. No inflate, no deflate, and the tile is
        // preserved byte for byte.
        if let [(raw, pmtiles::COMPRESSION_GZIP)] = bodies.as_slice() {
            b.add_tile_raw(id, raw.clone());
            continue;
        }
        let mut decoded: Vec<Vec<u8>> = Vec::with_capacity(bodies.len());
        for (raw, compression) in bodies {
            decoded.push(match *compression {
                pmtiles::COMPRESSION_NONE => raw.clone(),
                _ => crate::gz::decompress(raw)?,
            });
        }
        let merged = if decoded.len() == 1 {
            // One input, but not gzip: re-encoding the MVT is still unnecessary.
            decoded.pop().unwrap_or_default()
        } else {
            merge_tiles(&decoded)?
        };
        b.add_tile_raw(id, crate::gz::compress(&merged));
    }
    b.build()
}

/// Widened to i64 first: two e7 longitudes at opposite edges of the world sum to
/// 3.6e9, which an i32 cannot hold.
fn midpoint_e7(a: i32, b: i32) -> i32 {
    ((a as i64 + b as i64) / 2) as i32
}

/// Graft every input's `vector_layers` entries into the first input's metadata.
///
/// The list is how a reader learns which layers an archive holds, so publishing one
/// input's copy of it would advertise a fraction of the merge. Keeping the first
/// input as the template preserves a base archive's other keys (name, attribution,
/// tilestats) instead of discarding them.
///
/// Hand-rolled because this crate deliberately carries no JSON dependency. Only the
/// one array is interpreted; every other byte is passed through verbatim.
fn merge_metadata(inputs: &[&Archive]) -> Vec<u8> {
    let texts: Vec<String> = inputs
        .iter()
        .map(|a| String::from_utf8_lossy(&a.metadata).into_owned())
        .collect();

    let mut layers: Vec<(String, String)> = Vec::new();
    for text in &texts {
        let Some(span) = vector_layers_span(text) else { continue };
        for obj in array_elements(text, span) {
            let id = object_string_field(&obj, "id");
            // An entry with no string `id` cannot be matched against, so it is kept
            // rather than deduplicated. Folding them all onto one empty key would
            // make several id-less layers collapse into a single advertised one.
            let slot = id
                .as_ref()
                .and_then(|id| layers.iter_mut().find(|(seen, _)| seen == id));
            match slot {
                // Later inputs win, exactly as they do for the tiles themselves.
                Some(slot) => slot.1 = obj,
                None => layers.push((id.unwrap_or_default(), obj)),
            }
        }
    }

    let template = texts.first().map(String::as_str).unwrap_or("");
    let inner: Vec<&str> = layers.iter().map(|(_, o)| o.as_str()).collect();
    splice_vector_layers(template, &inner.join(",")).into_bytes()
}

/// Byte range of the `vector_layers` array, brackets included.
fn vector_layers_span(text: &str) -> Option<(usize, usize)> {
    let b = text.as_bytes();
    let value = vector_layers_value_start(text)?;
    if b.get(value) != Some(&b'[') {
        return None;
    }
    balanced_end(b, value).map(|e| (value, e))
}

/// First byte of whatever `vector_layers` is set to, array or not.
///
/// Walks strings properly rather than scanning for the key, so a `"vector_layers"`
/// mentioned inside some layer's description cannot be mistaken for the real key.
fn vector_layers_value_start(text: &str) -> Option<usize> {
    let b = text.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] != b'"' {
            i += 1;
            continue;
        }
        let key_end = string_end(b, i)?;
        let is_key = &b[i + 1..key_end] == b"vector_layers";
        i = key_end + 1;
        if !is_key {
            continue;
        }
        let j = skip_ws(b, i);
        if b.get(j) != Some(&b':') {
            continue;
        }
        return Some(skip_ws(b, j + 1));
    }
    None
}

/// The top-level elements of the array at `span`, each as its own raw JSON text.
fn array_elements(text: &str, span: (usize, usize)) -> Vec<String> {
    let b = text.as_bytes();
    let close = span.1 - 1;
    let mut out = Vec::new();
    let mut i = skip_ws(b, span.0 + 1);
    while i < close {
        let stop = match b[i] {
            b'{' | b'[' => match balanced_end(b, i) {
                Some(e) => e,
                None => break,
            },
            b'"' => match string_end(b, i) {
                Some(e) => e + 1,
                None => break,
            },
            _ => {
                let mut k = i;
                while k < close && b[k] != b',' {
                    k += 1;
                }
                k
            }
        };
        let element = text[i..stop].trim();
        if !element.is_empty() {
            out.push(element.to_string());
        }
        i = skip_ws(b, stop);
        if b.get(i) == Some(&b',') {
            i = skip_ws(b, i + 1);
        }
    }
    out
}

/// A JSON object's own string field. Nested values are skipped rather than
/// descended into, so a layer's `fields` map cannot supply the layer's `id`.
fn object_string_field(text: &str, key: &str) -> Option<String> {
    let b = text.as_bytes();
    if b.first() != Some(&b'{') {
        return None;
    }
    let mut i = skip_ws(b, 1);
    while i < b.len() && b[i] != b'}' {
        if b[i] != b'"' {
            return None;
        }
        let key_end = string_end(b, i)?;
        let matched = &b[i + 1..key_end] == key.as_bytes();
        let mut j = skip_ws(b, key_end + 1);
        if b.get(j) != Some(&b':') {
            return None;
        }
        j = skip_ws(b, j + 1);
        if matched {
            return match b.get(j) {
                Some(b'"') => {
                    let value_end = string_end(b, j)?;
                    Some(text[j + 1..value_end].to_string())
                }
                _ => None,
            };
        }
        i = match b.get(j)? {
            b'{' | b'[' => balanced_end(b, j)?,
            b'"' => string_end(b, j)? + 1,
            _ => {
                let mut k = j;
                while k < b.len() && b[k] != b',' && b[k] != b'}' {
                    k += 1;
                }
                k
            }
        };
        i = skip_ws(b, i);
        if b.get(i) == Some(&b',') {
            i = skip_ws(b, i + 1);
        }
    }
    None
}

/// Replace the metadata's `vector_layers` with `inner`, or add one if it had none.
fn splice_vector_layers(template: &str, inner: &str) -> String {
    let array = format!("[{inner}]");
    if let Some((start, end)) = vector_layers_span(template) {
        return format!("{}{}{}", &template[..start], array, &template[end..]);
    }
    // A template that sets `vector_layers` to something that is not an array cannot
    // be spliced, and grafting a second copy of the key would emit duplicate keys.
    // Nor can an unparseable template be preserved. Either way our list is the part
    // that has to be right.
    let t = template.trim();
    if vector_layers_value_start(template).is_some() {
        return format!("{{\"vector_layers\":{array}}}");
    }
    let body = t
        .strip_prefix('{')
        .and_then(|s| s.strip_suffix('}'))
        .map(str::trim)
        .unwrap_or("");
    if body.is_empty() {
        format!("{{\"vector_layers\":{array}}}")
    } else {
        format!("{{\"vector_layers\":{array},{body}}}")
    }
}

/// Index of the quote closing the string that opens at `open`, honouring `\"`.
fn string_end(b: &[u8], open: usize) -> Option<usize> {
    let mut i = open + 1;
    while i < b.len() {
        match b[i] {
            b'\\' => i += 2,
            b'"' => return Some(i),
            _ => i += 1,
        }
    }
    None
}

/// Index just past the bracket or brace matching the one at `open`.
fn balanced_end(b: &[u8], open: usize) -> Option<usize> {
    let mut depth = 0usize;
    let mut i = open;
    while i < b.len() {
        match b[i] {
            b'"' => i = string_end(b, i)?,
            b'[' | b'{' => depth += 1,
            b']' | b'}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(i + 1);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

fn skip_ws(b: &[u8], mut i: usize) -> usize {
    while i < b.len() && b[i].is_ascii_whitespace() {
        i += 1;
    }
    i
}

/// Union the layers of several MVT bodies for the same tile. Later bodies win a
/// layer-name collision.
pub fn merge_tiles(bodies: &[Vec<u8>]) -> Result<Vec<u8>> {
    let mut out = Tile::new();
    for body in bodies {
        let tile = Tile::decode(body)?;
        for layer in tile.layers {
            match out.layers.iter_mut().find(|l| l.name == layer.name) {
                Some(existing) => *existing = layer,
                None => out.layers.push(layer),
            }
        }
    }
    Ok(out.encode())
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL_TILE: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    fn pt(lon: f64, lat: f64, name: &str) -> Point {
        Point {
            lon,
            lat,
            props: vec![("name".to_string(), Value::String(name.to_string()))],
        }
    }

    #[test]
    fn a_point_lands_in_the_expected_tile() {
        // San Francisco at z11 is a well-known tile: x=327, y=791.
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!((fx.floor() as u64, fy.floor() as u64), (327, 791));
    }

    /// A minimal one-tile archive carrying `metadata` verbatim.
    fn meta_archive(metadata: &str) -> Vec<u8> {
        let mut b = Builder::new();
        b.min_zoom = 1;
        b.max_zoom = 1;
        b.metadata = metadata.as_bytes().to_vec();
        b.add_tile(1, 0, 0, &one_point_tile("x"));
        b.build().unwrap()
    }

    /// A one-tile archive with real bounds, as every layer builder produces.
    /// `bounds` is `(west, south, east, north)` in e7 degrees.
    fn bounded_archive(
        layer: &str,
        bounds: (i32, i32, i32, i32),
        min_zoom: u8,
        max_zoom: u8,
    ) -> Vec<u8> {
        let (w, s, e, n) = bounds;
        let mut b = Builder::new();
        b.min_zoom = min_zoom;
        b.max_zoom = max_zoom;
        b.center_zoom = min_zoom;
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
        b.metadata = point_metadata(layer, min_zoom, max_zoom).into_bytes();
        b.add_tile(min_zoom, 0, 0, &one_point_tile(layer));
        b.build().unwrap()
    }

    fn one_point_tile(layer: &str) -> Vec<u8> {
        let mut l = Layer::new(layer);
        l.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(1, 1)]),
            props: vec![],
        });
        Tile { layers: vec![l] }.encode()
    }

    #[test]
    fn tiling_buckets_points_and_round_trips_them() {
        let pts = vec![
            pt(-122.4194, 37.7749, "SF"),
            pt(-122.4180, 37.7760, "SF2"),
            // Far away, so it must land in a different tile.
            pt(-74.0060, 40.7128, "NYC"),
        ];
        let tiles = tile_points("transit_stops", &pts, 11, DEFAULT_EXTENT);
        assert_eq!(tiles.len(), 2, "two distinct tiles");

        let sf_id = pmtiles::tile_id(11, 327, 791);
        let (_, body) = tiles.iter().find(|(id, _)| *id == sf_id).expect("the SF tile");
        let tile = Tile::decode(body).unwrap();
        let layer = tile.layer("transit_stops").unwrap();
        assert_eq!(layer.features.len(), 2, "both SF points in one tile");
        for f in &layer.features {
            assert_eq!(f.geom_type, GeomType::Point);
            let decoded = mvt::decode_points(&f.geometry).unwrap();
            assert_eq!(decoded.len(), 1);
            let (x, y) = decoded[0];
            assert!(
                (0..=DEFAULT_EXTENT as i32).contains(&x)
                    && (0..=DEFAULT_EXTENT as i32).contains(&y),
                "({x},{y}) inside the extent grid"
            );
        }
    }

    #[test]
    fn tiling_is_deterministic() {
        let pts = vec![pt(-122.42, 37.77, "a"), pt(-122.41, 37.78, "b")];
        assert_eq!(
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
        );
    }

    #[test]
    fn a_point_archive_round_trips_at_every_zoom() {
        let pts = vec![pt(-122.4194, 37.7749, "Embarcadero"), pt(-118.2437, 34.0522, "Union")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 13).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (11, 13));
        assert!(
            String::from_utf8_lossy(&a.metadata).contains("transit_stops"),
            "metadata names the layer"
        );
        for z in 11..=13u8 {
            let (fx, fy) = project(-122.4194, 37.7749, z);
            let body = a
                .tile(z, fx.floor() as u64, fy.floor() as u64)
                .unwrap()
                .unwrap_or_else(|| panic!("a tile at z{z}"));
            let tile = Tile::decode(&body).unwrap();
            let l = tile.layer("transit_stops").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(
                l.features[0].get("name"),
                Some(&Value::String("Embarcadero".into()))
            );
        }
    }

    #[test]
    fn merging_unions_layers_and_preserves_the_base_geometry() {
        // A real tippecanoe tile (polygons + lines) merged with our own point
        // layer: the union must keep all four layers, and the base geometry must
        // come through byte-identically.
        let base = REAL_TILE.to_vec();
        let mut ours = Layer::new("transit_stops");
        ours.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(100, 200)]),
            props: vec![("motis_id".to_string(), Value::String("us-ca-X_1".into()))],
        });
        let overlay = Tile { layers: vec![ours] }.encode();

        let merged = merge_tiles(&[base, overlay]).unwrap();
        let tile = Tile::decode(&merged).unwrap();
        let mut names = tile.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["earth", "roads", "transit_stops", "water"]);

        let before = Tile::decode(REAL_TILE).unwrap();
        for name in ["earth", "roads", "water"] {
            let b = before.layer(name).unwrap();
            let a = tile.layer(name).unwrap();
            assert_eq!(b.features.len(), a.features.len(), "{name} feature count");
            for (bf, af) in b.features.iter().zip(a.features.iter()) {
                assert_eq!(bf.geometry, af.geometry, "{name} geometry must be untouched");
                assert_eq!(bf.geom_type, af.geom_type);
            }
        }
        assert_eq!(
            tile.layer("transit_stops").unwrap().features[0].get("motis_id"),
            Some(&Value::String("us-ca-X_1".into()))
        );
    }

    #[test]
    fn a_later_input_replaces_a_colliding_layer() {
        let mut old = Layer::new("transit_stops");
        old.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(1, 1)]),
            props: vec![("v".to_string(), Value::Uint(1))],
        });
        let mut new = Layer::new("transit_stops");
        new.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(2, 2)]),
            props: vec![("v".to_string(), Value::Uint(2))],
        });
        let merged = merge_tiles(&[
            Tile { layers: vec![old] }.encode(),
            Tile { layers: vec![new] }.encode(),
        ])
        .unwrap();
        let tile = Tile::decode(&merged).unwrap();
        assert_eq!(tile.layers.len(), 1, "not duplicated");
        let l = tile.layer("transit_stops").unwrap();
        assert_eq!(l.features.len(), 1);
        assert_eq!(l.features[0].get("v"), Some(&Value::Uint(2)), "the later one wins");
    }

    #[test]
    fn merging_archives_unions_overlapping_and_disjoint_tiles() {
        // Base: two tiles. Overlay: one overlapping, one new.
        let mut base = Builder::new();
        base.min_zoom = 11;
        base.max_zoom = 11;
        let mut l = Layer::new("water");
        l.features.push(Feature {
            id: None,
            geom_type: GeomType::Polygon,
            geometry: vec![mvt::command(mvt::CMD_MOVE_TO, 1), 0, 0],
            props: vec![],
        });
        let water = Tile { layers: vec![l] }.encode();
        base.add_tile(11, 327, 791, &water);
        base.add_tile(11, 100, 100, &water);
        let base_bytes = base.build().unwrap();

        let mut ov = Builder::new();
        ov.min_zoom = 11;
        ov.max_zoom = 11;
        let mut sl = Layer::new("transit_stops");
        sl.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(5, 5)]),
            props: vec![],
        });
        let stops = Tile { layers: vec![sl] }.encode();
        ov.add_tile(11, 327, 791, &stops);
        ov.add_tile(11, 500, 500, &stops);
        let ov_bytes = ov.build().unwrap();

        let ba = Archive::parse(&base_bytes).unwrap();
        let oa = Archive::parse(&ov_bytes).unwrap();
        let merged = merge_archives(&[&ba, &oa]).unwrap();
        let m = Archive::parse(&merged).unwrap();

        // Overlapping tile carries both layers.
        let t = Tile::decode(&m.tile(11, 327, 791).unwrap().unwrap()).unwrap();
        let mut names = t.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["transit_stops", "water"]);
        // Base-only tile survives.
        let t = Tile::decode(&m.tile(11, 100, 100).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["water"]);
        // Overlay-only tile survives.
        let t = Tile::decode(&m.tile(11, 500, 500).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["transit_stops"]);
    }

    #[test]
    fn merging_a_single_archive_is_a_faithful_copy() {
        let pts = vec![pt(-122.4194, 37.7749, "SF")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 11).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let merged = merge_archives(&[&a]).unwrap();
        let m = Archive::parse(&merged).unwrap();
        assert_eq!(m.header.addressed_tiles, a.header.addressed_tiles);
        assert_eq!(m.metadata, a.metadata, "metadata round-trips unchanged");
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!(
            m.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
            a.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
        );
    }

    /// The overlay-only case: no input covers the whole extent, so the merged
    /// envelope has to be the union or the narrowest input would clip the rest.
    ///
    /// The fixtures set their bounds explicitly. `build_point_archive` leaves the
    /// builder's whole-world default in place, so using it here would make every
    /// assertion pass under "take the first input" too.
    #[test]
    fn merging_unions_zoom_and_bounds_across_inputs() {
        // San Francisco, z10-16.
        let west = bounded_archive(
            "safety",
            (-1_224_200_000, 377_000_000, -1_223_800_000, 377_900_000),
            10,
            16,
        );
        // New York, z12-14 - east of, and north of, the other one.
        let east = bounded_archive(
            "ma_pois",
            (-740_200_000, 407_000_000, -739_800_000, 407_900_000),
            12,
            14,
        );
        let (wa, ea) = (Archive::parse(&west).unwrap(), Archive::parse(&east).unwrap());

        let merged = merge_archives(&[&wa, &ea]).unwrap();
        let m = Archive::parse(&merged).unwrap();

        assert_eq!((m.header.min_zoom, m.header.max_zoom), (10, 16));
        assert_eq!(m.header.min_lon_e7, -1_224_200_000, "west edge from SF");
        assert_eq!(m.header.max_lon_e7, -739_800_000, "east edge from NYC");
        assert_eq!(m.header.min_lat_e7, 377_000_000, "south edge from SF");
        assert_eq!(m.header.max_lat_e7, 407_900_000, "north edge from NYC");
        // Neither input's own centre, which is the point.
        assert_eq!(m.header.center_lon_e7, (-1_224_200_000 + -739_800_000) / 2);
        assert_eq!(m.header.center_lat_e7, (377_000_000 + 407_900_000) / 2);
        assert_eq!(m.header.center_zoom, 10, "the shallowest zoom on offer");
    }

    /// Two e7 longitudes at opposite edges of the world sum past `i32::MAX`, so the
    /// midpoint has to widen before it adds.
    #[test]
    fn the_merged_centre_survives_a_world_wide_envelope() {
        let west = bounded_archive("a", (-1_800_000_000, -850_000_000, -1_700_000_000, -840_000_000), 1, 2);
        let east = bounded_archive("b", (1_700_000_000, 840_000_000, 1_800_000_000, 850_000_000), 1, 2);
        let (wa, ea) = (Archive::parse(&west).unwrap(), Archive::parse(&east).unwrap());

        let m = Archive::parse(&merge_archives(&[&wa, &ea]).unwrap()).unwrap();
        assert_eq!(m.header.min_lon_e7, -1_800_000_000);
        assert_eq!(m.header.max_lon_e7, 1_800_000_000);
        assert_eq!(m.header.center_lon_e7, 0);
        assert_eq!(m.header.center_lat_e7, 0);
    }

    #[test]
    fn merging_unions_the_vector_layers_list() {
        let a = build_point_archive("safety", &[pt(-122.4, 37.7, "a")], 10, 16).unwrap();
        let b = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "b")], 12, 16).unwrap();
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let merged = merge_archives(&[&aa, &ba]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[\
             {\"id\":\"safety\",\"minzoom\":10,\"maxzoom\":16},\
             {\"id\":\"ma_pois\",\"minzoom\":12,\"maxzoom\":16}]}",
            "both layers advertised, in input order"
        );
    }

    /// A base archive's other metadata keys are what carry attribution, so the
    /// splice has to leave everything it does not understand alone.
    #[test]
    fn splicing_preserves_the_templates_other_keys() {
        let spliced = splice_vector_layers(
            "{\"name\":\"v5\",\"vector_layers\":[{\"id\":\"water\"}],\"attribution\":\"OSM\"}",
            "{\"id\":\"safety\"}",
        );
        assert_eq!(
            spliced,
            "{\"name\":\"v5\",\"vector_layers\":[{\"id\":\"safety\"}],\"attribution\":\"OSM\"}"
        );
    }

    #[test]
    fn splicing_grafts_a_list_onto_metadata_that_lacks_one() {
        assert_eq!(
            splice_vector_layers("{\"name\":\"v5\"}", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}],\"name\":\"v5\"}"
        );
        assert_eq!(
            splice_vector_layers("", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}]}"
        );
    }

    /// A `vector_layers` that is not an array cannot be spliced, and keeping the
    /// template would emit the key twice.
    #[test]
    fn a_non_array_vector_layers_is_replaced_rather_than_duplicated() {
        assert_eq!(
            splice_vector_layers("{\"vector_layers\":5,\"name\":\"v5\"}", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}]}"
        );
    }

    /// The scanner walks strings rather than searching for the key, so a decoy
    /// inside a value cannot misdirect it.
    #[test]
    fn the_metadata_scanner_ignores_decoys_inside_strings() {
        let text = r#"{"note":"see \"vector_layers\": [bogus]","vector_layers":[{"id":"real"}]}"#;
        let span = vector_layers_span(text).expect("the real key");
        assert_eq!(array_elements(text, span), vec![r#"{"id":"real"}"#]);
    }

    #[test]
    fn an_id_is_read_past_a_nested_field_map() {
        let obj = "{\"fields\":{\"id\":\"decoy\"},\"id\":\"real\",\"minzoom\":3}";
        assert_eq!(object_string_field(obj, "id").as_deref(), Some("real"));
        assert_eq!(object_string_field(obj, "missing"), None);
    }

    /// Entries with no string `id` cannot be matched against, so they must be kept
    /// side by side rather than folded onto one empty key and collapsed into one.
    #[test]
    fn id_less_layer_entries_are_kept_rather_than_deduplicated() {
        let a = meta_archive("{\"vector_layers\":[{\"minzoom\":1},{\"minzoom\":2}]}");
        let b = meta_archive("{\"vector_layers\":[{\"minzoom\":3}]}");
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let merged = merge_archives(&[&aa, &ba]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[{\"minzoom\":1},{\"minzoom\":2},{\"minzoom\":3}]}"
        );
    }

    #[test]
    fn a_colliding_layer_id_keeps_only_the_later_description() {
        let stale = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "a")], 12, 14).unwrap();
        let fresh = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "b")], 12, 16).unwrap();
        let (sa, fa) = (Archive::parse(&stale).unwrap(), Archive::parse(&fresh).unwrap());

        let merged = merge_archives(&[&sa, &fa]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[{\"id\":\"ma_pois\",\"minzoom\":12,\"maxzoom\":16}]}",
            "listed once, at the rebuilt layer's zoom range"
        );
    }

    /// A tile only one input holds must come out as that producer's exact bytes.
    ///
    /// Worth pinning because the win is invisible from the output alone: a re-deflate
    /// would still produce a valid archive, just slowly. Comparing the raw stored
    /// bytes is the only way to tell the passthrough is actually happening.
    #[test]
    fn a_sole_owner_tile_is_copied_through_without_recompressing() {
        let mut a = Builder::new();
        a.min_zoom = 11;
        a.max_zoom = 11;
        a.add_tile(11, 1, 1, &one_point_tile("safety"));
        let a_bytes = a.build().unwrap();

        let mut b = Builder::new();
        b.min_zoom = 11;
        b.max_zoom = 11;
        // A different tile, so neither input shares one with the other.
        b.add_tile(11, 9, 9, &one_point_tile("ma_pois"));
        let b_bytes = b.build().unwrap();

        let (aa, ba) = (
            Archive::parse(&a_bytes).unwrap(),
            Archive::parse(&b_bytes).unwrap(),
        );
        let m = Archive::parse(&merge_archives(&[&aa, &ba]).unwrap()).unwrap();

        assert_eq!(
            m.tile_raw(11, 1, 1).unwrap().map(<[u8]>::to_vec),
            aa.tile_raw(11, 1, 1).unwrap().map(<[u8]>::to_vec),
            "the first input's tile is byte-identical to its stored bytes",
        );
        assert_eq!(
            m.tile_raw(11, 9, 9).unwrap().map(<[u8]>::to_vec),
            ba.tile_raw(11, 9, 9).unwrap().map(<[u8]>::to_vec),
            "and so is the second's",
        );
    }

    /// The passthrough must not apply where the tile actually needs merging.
    #[test]
    fn a_shared_tile_is_still_merged_rather_than_passed_through() {
        let mut base = Builder::new();
        base.min_zoom = 11;
        base.max_zoom = 11;
        base.add_tile(11, 5, 5, &one_point_tile("water"));
        let base_bytes = base.build().unwrap();

        let mut ov = Builder::new();
        ov.min_zoom = 11;
        ov.max_zoom = 11;
        ov.add_tile(11, 5, 5, &one_point_tile("transit_stops"));
        let ov_bytes = ov.build().unwrap();

        let (ba, oa) = (
            Archive::parse(&base_bytes).unwrap(),
            Archive::parse(&ov_bytes).unwrap(),
        );
        let m = Archive::parse(&merge_archives(&[&ba, &oa]).unwrap()).unwrap();

        let t = Tile::decode(&m.tile(11, 5, 5).unwrap().unwrap()).unwrap();
        let mut names = t.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["transit_stops", "water"], "both layers present");
    }

    /// The streaming join must produce EXACTLY what the in-memory one does.
    ///
    /// This is the test the rewrite lives or dies by. `merge_archives` is covered by
    /// every test above, so pinning the two byte for byte inherits all of it — the
    /// header, the deduplication, the run coalescing, the directory split and the tile
    /// bodies — without restating any of it.
    #[test]
    fn the_streaming_join_is_byte_identical_to_the_in_memory_one() {
        let dir = std::env::temp_dir().join(format!("tb_stream_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // Overlapping and disjoint tiles, several zooms, and a repeated body so the
        // deduplication and run coalescing paths are both exercised.
        let a = build_point_archive(
            "safety",
            &[pt(-122.42, 37.77, "a"), pt(-74.0, 40.7, "b")],
            10,
            12,
        )
        .unwrap();
        let b = build_point_archive(
            "ma_pois",
            &[pt(-122.42, 37.77, "c"), pt(2.35, 48.85, "d")],
            11,
            13,
        )
        .unwrap();
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let in_memory = merge_archives(&[&aa, &ba]).unwrap();

        let out = dir.join("streamed.pmtiles");
        merge_archives_to(&[&aa, &ba], &out, dir.join("scratch.bin"), false).unwrap();
        let streamed = std::fs::read(&out).unwrap();

        assert_eq!(
            streamed.len(),
            in_memory.len(),
            "streamed {} bytes, in-memory {}",
            streamed.len(),
            in_memory.len()
        );
        assert!(streamed == in_memory, "the two archives differ byte for byte");

        // The scratch file must not survive a successful run.
        assert!(
            !dir.join("scratch.bin").exists(),
            "the scratch file was left behind"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Ids must ascend for run coalescing and for `clustered` to be honest, so the
    /// writer refuses rather than quietly emitting an archive readers will mis-seek.
    #[test]
    fn the_streaming_writer_rejects_out_of_order_ids() {
        let dir = std::env::temp_dir().join(format!("tb_order_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut b = pmtiles::StreamBuilder::new(dir.join("s.bin")).unwrap();
        b.add_tile_raw(10, b"x").unwrap();
        assert!(b.add_tile_raw(9, b"y").is_err(), "9 after 10 must fail");
        assert!(b.add_tile_raw(10, b"y").is_err(), "a repeat must fail too");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
