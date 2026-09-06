//! `transit`: coloured rail lines, and the one layer whose data is not in the `.osm.pbf` at all.
//!
//! A transit line is a **GTFS shape**: one polyline out of an agency's own `shapes.txt`, carrying
//! the official `route_color` out of its `routes.txt`. `scripts/maps/gtfs_ingest`'s `transit_shapes`
//! binary turns a set of feeds into a `.geojsonseq` of them, and [`stream_routes`] reads that file
//! the way [`crate::schema::earth::stream_prepared`] reads a prepared land polygon. The rail track
//! itself stays on the `roads` layer as the grey dashed casing from the reference style's
//! `roads_rail`, so the coloured line draws over neutral track exactly like a metro map.
//!
//! # Why not OSM route relations
//!
//! It used to be exactly that: a `type=route` relation's member ways, coloured by the relation's
//! `colour=` tag or a per-mode guess. Both halves were wrong. A relation's members include its
//! **platforms**, and the member role is not in scope where the colour is assigned, so every
//! platform way came out as a wide coloured band beside the track. And a guessed colour is a guess
//! — GTFS has the agency's answer, so the layer takes it.
//!
//! The trade is coverage: anywhere with no feed in the built region loses lines it had from OSM,
//! and an agency-supplied shape does not sit exactly on OSM's rail casing.
//!
//! # Stations
//!
//! Stations stay on the `poi` layer as kind `station` (task 50), but with a `kind_detail`
//! naming the station mode — which is what makes them identifiable as *transit* stations when
//! the POI toggle is off. That half is still a tag query over the `.osm.pbf`. See
//! [`station_detail`]; its `station` tag reaches the pre-screen through `poi`'s own `FILTERS`.

use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::dict::LAYER_TRANSIT;
use tilecodec::mvt::Value;

use super::{detail, kind, Class, TagSource};

/// The modes this layer carries.
///
/// **A matched pair with `gtfs_ingest`'s `transit_shapes`**, which maps GTFS `route_type` onto
/// these five names and carries the fallback colour for each. The two crates cannot share code, so
/// a name added here has to be added there or nothing will ever be classified into it.
const MODES: &[&str] = &["subway", "light_rail", "tram", "train", "monorail"];

/// The zoom every transit line surfaces at, whatever its mode.
///
/// One floor rather than [`crate::schema::roads`]' per-mode ladder, because a transit layer is a
/// **network** and half a network is worse than none: trunk rail at z8 with the metro withheld
/// until z12 draws a city as though it had no metro. The zoom that matters is the one where the
/// whole network first reads, and that is the trunk's.
const MIN_ZOOM: u8 = 8;

/// A transit line's class: layer `transit`, kind `rail`, detail naming the mode.
///
/// The kind stays `rail` (what the reference style's `roads_rail` matches) while the detail
/// distinguishes a subway from a tram — which is what lets the renderer vary width by mode
/// while keying colour off `transit_color`.
pub fn transit_class(mode: &str) -> Option<Class> {
    if !MODES.contains(&mode) {
        return None;
    }
    Some(Class {
        layer: LAYER_TRANSIT,
        kind: kind("rail"),
        kind_detail: detail(mode),
        flags: 0,
        area: false,
        min_zoom: MIN_ZOOM,
        min_area_px: 0.0,
    })
}

/// Stream a prepared transit-routes file into the feature sink, clipped to `bbox`.
///
/// The `.geojsonseq` `transit_shapes` writes: one `LineString` per rail route, with `color`
/// (`RRGGBB`), `mode`, `ordinal`, `lanes` and `taper` properties. Modelled on
/// [`crate::schema::earth::stream_prepared`] down to the streaming read and the "this file yielded
/// nothing, so you passed the wrong one" error at the end.
///
/// Every malformed line is a hard error rather than a skip. The file is written by a tool in this
/// repo, so anything this cannot read means the wrong file was handed over — and a transit layer
/// that is quietly half a city is the failure mode that took a device pass to notice. The three
/// lane properties are the one exception: **absent** ones default to a route on its own alignment
/// (`ordinal 0, lanes 1, taper 255`), so a file written before corridors existed still reads. One
/// that is present and unreadable is still an error.
///
/// `bbox` is `(min_lon, min_lat, max_lon, max_lat)` in degrees and is an intersection test, not a
/// clip: a route running out of the extract is drawn to where the tiler cuts it, exactly as an OSM
/// way straddling the same edge is.
pub fn stream_routes(
    path: &Path,
    bbox: (f64, f64, f64, f64),
    sink: &mut crate::store::Sink,
) -> Result<u64> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| osm_ingest::proto::Error(format!("cannot read {}: {e}", path.display())))?;
    let mut written = 0u64;
    let mut skipped = 0u64;
    for (line_number, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let at = |what: &str| {
            osm_ingest::proto::Error(format!("{}:{}: {what}", path.display(), line_number + 1))
        };
        let Some(feature) = tile_build::geojson::parse_feature(line) else {
            return Err(at("not a GeoJSON feature"));
        };
        // Lines only. A prepared transit-routes file has nothing else in it, and a polygon in
        // there would be a sign the coastline product was passed by mistake.
        let Geometry::Lines(lines) = feature.geometry else {
            return Err(at("not a LineString; is this the transit-routes file?"));
        };
        let Some(mode) = prop(&feature.props, "mode") else {
            return Err(at("no `mode` property"));
        };
        let Some(class) = transit_class(&mode) else {
            return Err(at(&format!("`{mode}` is not a mode this layer carries")));
        };
        let Some(color) = prop(&feature.props, "color").as_deref().and_then(hex_rrggbb) else {
            return Err(at("no usable `color` property; it must be non-zero bare RRGGBB hex"));
        };
        let Some(ordinal) = byte_prop(&feature.props, "ordinal", 0) else {
            return Err(at("an `ordinal` property that is not a whole number in 0..=255"));
        };
        let Some(lanes) = byte_prop(&feature.props, "lanes", 1) else {
            return Err(at("a `lanes` property that is not a whole number in 0..=255"));
        };
        let Some(taper) = byte_prop(&feature.props, "taper", 255) else {
            return Err(at("a `taper` property that is not a whole number in 0..=255"));
        };
        for line in lines {
            if line.len() < 2 || !meets(&line, bbox) {
                skipped += 1;
                continue;
            }
            sink.push_transit(&class, &Geometry::Lines(vec![line]), color, ordinal, lanes, taper)?;
            written += 1;
        }
    }
    if written == 0 {
        return err(format!(
            "{} holds no transit route meeting {bbox:?}; is it the right area?",
            path.display(),
        ));
    }
    println!("  {written} transit route(s) kept, {skipped} outside the extract");
    Ok(written)
}

/// A string-valued property, by name.
fn prop(props: &[(String, Value)], name: &str) -> Option<String> {
    props.iter().find(|(k, _)| k == name).and_then(|(_, v)| match v {
        Value::String(s) => Some(s.clone()),
        _ => None,
    })
}

/// One of the lane inputs as a byte. `None` only for a property that is there and unreadable.
///
/// Absent is `missing` on purpose: everything else in this reader hard-errors, and falling into
/// that path would make a routes file written before corridors existed unreadable rather than
/// simply un-fanned. The GeoJSON reader gives a non-negative literal back as a `Uint` and a
/// negative one as a `Double`, so every arm is the same number.
fn byte_prop(props: &[(String, Value)], name: &str, missing: u8) -> Option<u8> {
    match props.iter().find(|(k, _)| k == name) {
        None => Some(missing),
        Some((_, Value::Uint(v))) => u8::try_from(*v).ok(),
        Some((_, Value::Int(v))) | Some((_, Value::SInt(v))) => u8::try_from(*v).ok(),
        Some((_, Value::Double(v))) if v.fract() == 0.0 => {
            (*v >= 0.0 && *v <= u8::MAX as f64).then_some(*v as u8)
        }
        Some(_) => None,
    }
}

/// A bare `RRGGBB` hex colour as `0xRRGGBB`, or `None`.
///
/// Zero is refused: it means "no colour" on the wire and [`crate::store::Sink::push_transit`]
/// rejects it, so catching it here names the offending line instead.
fn hex_rrggbb(raw: &str) -> Option<u32> {
    let hex = raw.trim();
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    u32::from_str_radix(hex, 16).ok().filter(|c| *c != 0)
}

/// Does a polyline meet `bbox`? Intersection, not containment.
fn meets(line: &[(f64, f64)], bbox: (f64, f64, f64, f64)) -> bool {
    let (mut min_x, mut min_y) = (f64::MAX, f64::MAX);
    let (mut max_x, mut max_y) = (f64::MIN, f64::MIN);
    for &(x, y) in line {
        min_x = min_x.min(x);
        min_y = min_y.min(y);
        max_x = max_x.max(x);
        max_y = max_y.max(y);
    }
    !(max_x < bbox.0 || min_x > bbox.2 || max_y < bbox.1 || min_y > bbox.3)
}

/// The station mode for a `poi` station feature's `kind_detail`.
///
/// `station=subway/light_rail/tram` name the mode directly; a bare `railway=station` is a
/// `station`; a `railway=halt` is a `halt`. This is the tag the renderer reads to show stations
/// when Transit is ON even with POIs OFF: `poi` layer, kind `station`, detail naming one of
/// these — everything else on the layer is a different kind already.
pub fn station_detail(tags: &(impl TagSource + ?Sized)) -> &'static str {
    match tags.get("station") {
        Some("subway") => "subway",
        Some("light_rail") => "light_rail",
        Some("tram") => "tram",
        Some("monorail") => "monorail",
        _ => match tags.get("railway") {
            Some("halt") => "halt",
            _ => "station",
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn station_mode(pairs: &[(&str, &str)]) -> &'static str {
        super::station_detail(pairs)
    }

    /// One `LineString` per line, written by `transit_shapes`.
    fn feature(color: &str, mode: &str, coords: &str) -> String {
        format!(
            "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"LineString\",\
             \"coordinates\":[{coords}]}},\"properties\":{{\"color\":\"{color}\",\
             \"mode\":\"{mode}\"}}}}"
        )
    }

    /// The same, carrying the corridor lane inputs.
    fn slotted(color: &str, mode: &str, ordinal: u8, lanes: u8, taper: u8, coords: &str) -> String {
        format!(
            "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"LineString\",\
             \"coordinates\":[{coords}]}},\"properties\":{{\"color\":\"{color}\",\
             \"mode\":\"{mode}\",\"ordinal\":{ordinal},\"lanes\":{lanes},\
             \"taper\":{taper}}}}}"
        )
    }

    fn scratch(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("mamaps_transit_{tag}_{}", std::process::id()))
    }

    #[test]
    fn every_mode_has_a_class_and_they_all_share_one_floor() {
        for mode in MODES {
            let class = transit_class(mode).expect("a class");
            assert_eq!(class.layer, dict::LAYER_TRANSIT);
            assert_eq!(dict::KINDS[class.kind as usize - 1], "rail");
            assert_eq!(dict::DETAILS[class.kind_detail as usize - 1], *mode);
            assert!(!class.area, "a line, even looped");
            // The whole network appears at once; see [`MIN_ZOOM`]. The style's `transit-rail`
            // carries the same 8, and a floor deeper than that here would leave it asking for
            // features the archive does not hold.
            assert_eq!(class.min_zoom, 8, "{mode}");
        }
        assert!(transit_class("bus").is_none());
    }

    #[test]
    fn colours_are_nonzero_bare_hex() {
        assert_eq!(hex_rrggbb("E4002B"), Some(0xE4002B));
        assert_eq!(hex_rrggbb(" e4002b "), Some(0xE4002B));
        assert_eq!(hex_rrggbb("#E4002B"), None, "the exporter writes bare hex");
        assert_eq!(hex_rrggbb("F00"), None, "no short form");
        assert_eq!(hex_rrggbb("GGGGGG"), None);
        // Zero means "no colour" on the wire, so it must never reach `push_transit`.
        assert_eq!(hex_rrggbb("000000"), None);
    }

    #[test]
    fn a_prepared_route_streams_in_as_a_coloured_transit_line() {
        let path = scratch("routes.geojsonseq");
        std::fs::write(
            &path,
            format!(
                "{}\n{}\n",
                feature("0054A5", "subway", "[-122.4,37.7],[-122.39,37.71]"),
                feature("E31E24", "light_rail", "[-122.4,37.7],[-122.41,37.72]"),
            ),
        )
        .expect("write");
        let spill = scratch("routes.features");
        let mut sink = crate::store::Sink::create(&spill).expect("sink");
        assert_eq!(
            stream_routes(&path, (-180.0, -90.0, 180.0, 90.0), &mut sink).expect("read"),
            2,
        );
        let store = sink.finish(&spill).expect("finish");

        let mut reader = store.reader().expect("reader");
        let first = reader.next().expect("read").expect("a feature");
        assert_eq!(first.class.layer, dict::LAYER_TRANSIT);
        assert_eq!(dict::KINDS[first.class.kind as usize - 1], "rail");
        assert_eq!(dict::DETAILS[first.class.kind_detail as usize - 1], "subway");
        assert_eq!(first.transit_color, 0x00_54_A5, "the agency's own colour, verbatim");
        assert_eq!(
            (first.transit_ordinal, first.transit_lanes, first.transit_taper),
            (0, 1, 255),
            "absent lane inputs are a route on its own alignment",
        );
        let second = reader.next().expect("read").expect("a feature");
        assert_eq!(second.transit_color, 0xE3_1E_24);
        assert_eq!(second.class.min_zoom, 8, "the network's single floor");

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&spill);
    }

    /// The lane inputs ride through to the feature untouched — the renderer, not the exporter,
    /// decides how many lanes to use, so nothing here clamps the count.
    #[test]
    fn the_corridor_lane_inputs_stream_through_to_the_feature() {
        let path = scratch("slots.geojsonseq");
        std::fs::write(
            &path,
            format!(
                "{}\n{}\n{}\n",
                slotted("0054A5", "subway", 0, 10, 255, "[-122.4,37.7],[-122.39,37.71]"),
                slotted("0054A5", "subway", 9, 10, 128, "[-122.4,37.7],[-122.39,37.71]"),
                slotted("0054A5", "subway", 0, 1, 255, "[-122.4,37.7],[-122.39,37.71]"),
            ),
        )
        .expect("write");
        let spill = scratch("slots.features");
        let mut sink = crate::store::Sink::create(&spill).expect("sink");
        assert_eq!(stream_routes(&path, (-180.0, -90.0, 180.0, 90.0), &mut sink).expect("read"), 3);
        let store = sink.finish(&spill).expect("finish");

        let mut reader = store.reader().expect("reader");
        let mut read = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            read.push((feature.transit_ordinal, feature.transit_lanes, feature.transit_taper));
        }
        assert_eq!(read, vec![(0, 10, 255), (9, 10, 128), (0, 1, 255)]);

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&spill);
    }

    #[test]
    fn a_lane_input_that_is_not_a_byte_is_an_error() {
        let spill = scratch("badslot.features");
        let path = scratch("badslot.geojsonseq");
        for body in [
            "\"ordinal\":\"left\"",
            "\"ordinal\":1.5",
            "\"lanes\":99999",
            "\"taper\":-1",
        ] {
            std::fs::write(
                &path,
                format!(
                    "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"LineString\",\
                     \"coordinates\":[[-122.4,37.7],[-122.39,37.71]]}},\"properties\":\
                     {{\"color\":\"0054A5\",\"mode\":\"subway\",{body}}}}}\n"
                ),
            )
            .expect("write");
            let mut sink = crate::store::Sink::create(&spill).expect("sink");
            assert!(
                stream_routes(&path, (-180.0, -90.0, 180.0, 90.0), &mut sink).is_err(),
                "{body} was accepted",
            );
        }
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&spill);
    }

    /// The same clip `earth` does, for the same reason: the file covers whatever regions were
    /// exported, and a corridor build must not carry lines from outside it.
    #[test]
    fn a_route_outside_the_build_area_is_not_carried() {
        let path = scratch("clip.geojsonseq");
        std::fs::write(
            &path,
            format!(
                "{}\n{}\n",
                feature("0054A5", "subway", "[-122.4,37.7],[-122.39,37.71]"),
                feature("0054A5", "train", "[-9.0,38.0],[-8.9,38.1]"),
            ),
        )
        .expect("write");
        let spill = scratch("clip.features");
        let mut sink = crate::store::Sink::create(&spill).expect("sink");
        let california = (-125.0, 32.0, -114.0, 42.0);
        assert_eq!(stream_routes(&path, california, &mut sink).expect("read"), 1);

        // And an area with no route in it is an error, not an empty layer.
        let mut sink = crate::store::Sink::create(&spill).expect("sink");
        assert!(stream_routes(&path, (-160.0, 0.0, -150.0, 10.0), &mut sink).is_err());

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&spill);
    }

    /// Every way this file can be the wrong file. Each is an error naming the line, because a
    /// transit layer that is quietly half a city is what this change exists to stop shipping.
    #[test]
    fn a_line_this_cannot_read_is_an_error_rather_than_a_skip() {
        let spill = scratch("bad.features");
        let world = (-180.0, -90.0, 180.0, 90.0);
        for (tag, body) in [
            ("nonsense", "not json at all".to_string()),
            (
                "polygon",
                "{\"type\":\"Feature\",\"properties\":{\"color\":\"0054A5\",\"mode\":\"subway\"},\
                 \"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0.0,0.0],[1.0,0.0],\
                 [1.0,1.0],[0.0,0.0]]]}}"
                    .to_string(),
            ),
            ("no_mode", feature("0054A5", "", "[-122.4,37.7],[-122.39,37.71]")),
            ("bus", feature("0054A5", "bus", "[-122.4,37.7],[-122.39,37.71]")),
            ("black", feature("000000", "subway", "[-122.4,37.7],[-122.39,37.71]")),
            ("no_colour", feature("nope", "subway", "[-122.4,37.7],[-122.39,37.71]")),
        ] {
            let path = scratch(&format!("bad_{tag}.geojsonseq"));
            std::fs::write(&path, format!("{body}\n")).expect("write");
            let mut sink = crate::store::Sink::create(&spill).expect("sink");
            assert!(stream_routes(&path, world, &mut sink).is_err(), "{tag} was accepted");
            let _ = std::fs::remove_file(&path);
        }
        // A missing file too, rather than an empty layer.
        let mut sink = crate::store::Sink::create(&spill).expect("sink");
        assert!(stream_routes(Path::new("no_such_routes.geojsonseq"), world, &mut sink).is_err());
        let _ = std::fs::remove_file(&spill);
    }

    #[test]
    fn stations_name_their_mode() {
        assert_eq!(station_mode(&[("railway", "station")]), "station");
        assert_eq!(station_mode(&[("railway", "halt")]), "halt");
        assert_eq!(
            station_mode(&[("railway", "station"), ("station", "subway")]),
            "subway"
        );
        assert_eq!(
            station_mode(&[("station", "light_rail")]),
            "light_rail",
            "a station tag alone counts"
        );
    }
}
