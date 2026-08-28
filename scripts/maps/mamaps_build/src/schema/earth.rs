//! `earth`: the land itself, which is the one layer that is not a tag query.
//!
//! # Why this is different
//!
//! There is no `natural=land` in OpenStreetMap. Land is defined by its **absence**: the data has
//! `natural=coastline` ways, oriented land-on-the-left, and the continents are whatever those ways
//! enclose. Deriving land from them means stitching every coastline way on the planet into closed
//! rings, orienting them, closing them against the extract's cut edges, and subtracting the result
//! from an ocean rectangle. It is the single hardest piece of this project and the most likely thing
//! to look visibly wrong — a stitch that fails anywhere puts a wedge of ocean through a continent.
//!
//! So it is not done here. [`Source::Prepared`] reads a **pre-validated land polygon** — the
//! OSMCoastline `land_polygons` product, which is effectively the same input Planetiler is given —
//! through `tile_build`'s GeoJSON-seq reader. That takes the largest single risk off the critical
//! path at the cost of one vendored artefact and this provenance note. Self-stitching sits behind
//! the same interface when it is worth the one to two weeks.
//!
//! # `island` is a tag query
//!
//! `place=island` and `place=islet` are real tags on real ways and relations, and they are what the
//! style's `earth` filter implies when it names `island` as a kind. So that half is here and works
//! from the `.osm.pbf` alone — which means the layer is not empty without the vendored polygon, it
//! just has no mainland.

use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::dict::LAYER_EARTH;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["place", "natural"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["island", "earth", "cliff"];

/// The `earth` features a `.osm.pbf` alone can produce.
///
/// An island is an area whatever its geometry says, and it is carried from z4: an island big enough
/// to be tagged is big enough to see, and the minimum area filters the rest.
pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if matches!(tags.get("place"), Some("island" | "islet")) {
        return Some(Class {
            min_area_px: 1.0,
            ..Class::area(LAYER_EARTH, kind("island"), 4)
        });
    }
    // A cliff is a line in this layer, and it is what makes a coastal relief map read as relief.
    if tags.get("natural") == Some("cliff") {
        return Some(Class::line(LAYER_EARTH, kind("cliff"), 12));
    }
    None
}

/// Stream a prepared land polygon into the feature sink, clipped to `bbox`.
///
/// Streamed rather than returned, because the planet product is 1.26 GB of geometry and collecting it
/// would undo the change this signature exists to serve.
///
/// `bbox` is `(min_lon, min_lat, max_lon, max_lat)` in degrees, and is **required**: the land polygon
/// product covers the whole planet, so a regional build that did not clip would tile every coastline
/// on Earth. That is not a slow build, it is a wrong one — the archive's bounding box would be the
/// globe and its tile count would be planetary.
///
/// Two formats, told apart by extension: an ESRI `.shp`, which is what the OSMCoastline product ships
/// as, and GeoJSON-seq, which is what a hand-made test fixture is easiest to write.
pub fn stream_prepared(
    path: &Path,
    bbox: (f64, f64, f64, f64),
    sink: &mut crate::store::Sink,
) -> Result<u64> {
    let class = Class::area(LAYER_EARTH, tilecodec::mamaps::dict::NONE, 0);
    let is_shapefile = path
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| e.eq_ignore_ascii_case("shp"));

    let mut written = 0u64;
    if is_shapefile {
        let mut reader = crate::shapefile::ShapeReader::open(path)?;
        reader.clip_to(bbox);
        while let Some(rings) = reader.next()? {
            // One mamaps feature per polygon, not per record: a record can hold several islands, and
            // the tessellator wants one exterior with its own holes.
            for polygon in crate::shapefile::group(rings) {
                sink.push(&class, &Geometry::Polygons(vec![polygon]))?;
                written += 1;
            }
        }
        println!(
            "  {} land polygon(s) kept, {} outside the extract",
            written, reader.skipped,
        );
    } else {
        let text = std::fs::read_to_string(path).map_err(|e| {
            osm_ingest::proto::Error(format!("cannot read {}: {e}", path.display()))
        })?;
        for (line_number, line) in text.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let Some(feature) = tile_build::geojson::parse_feature(line) else {
                return err(format!(
                    "{}:{}: not a GeoJSON feature",
                    path.display(),
                    line_number + 1,
                ));
            };
            // Polygons only. A prepared land product has nothing else in it, and a line in there
            // would be a sign the wrong file was handed over.
            let Geometry::Polygons(polygons) = feature.geometry else { continue };
            for polygon in polygons {
                if polygon.is_empty() || !meets(&polygon, bbox) {
                    continue;
                }
                sink.push(&class, &Geometry::Polygons(vec![polygon]))?;
                written += 1;
            }
        }
    }
    if written == 0 {
        return err(format!(
            "{} holds no land polygon meeting {bbox:?}; is it the right area?",
            path.display(),
        ));
    }
    Ok(written)
}

/// Does a polygon's exterior meet `bbox`? Intersection, not containment: a polygon straddling the
/// extract's edge is land inside it.
fn meets(polygon: &[Vec<(f64, f64)>], bbox: (f64, f64, f64, f64)) -> bool {
    let Some(exterior) = polygon.first() else { return false };
    let (mut min_x, mut min_y) = (f64::MAX, f64::MAX);
    let (mut max_x, mut max_y) = (f64::MIN, f64::MIN);
    for &(x, y) in exterior {
        min_x = min_x.min(x);
        min_y = min_y.min(y);
        max_x = max_x.max(x);
        max_y = max_y.max(y);
    }
    !(max_x < bbox.0 || min_x > bbox.2 || max_y < bbox.1 || min_y > bbox.3)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    #[test]
    fn an_island_is_an_area_carried_from_a_continental_zoom() {
        for value in ["island", "islet"] {
            let class = classify_tags(&[("place", value)]).expect(value);
            assert_eq!(class.layer, dict::LAYER_EARTH);
            assert_eq!(dict::KINDS[class.kind as usize - 1], "island");
            assert!(class.area);
            assert_eq!(class.min_zoom, 4);
            // A minimum area, because `place=islet` is on some very small rocks.
            assert!(class.min_area_px > 0.0);
        }
    }

    #[test]
    fn a_cliff_is_a_line_and_nothing_else_is_earth() {
        let cliff = classify_tags(&[("natural", "cliff")]).expect("cliff");
        assert!(!cliff.area);
        for pairs in [
            vec![("place", "city")],
            vec![("natural", "coastline")],
            vec![("natural", "water")],
            vec![],
        ] {
            assert!(classify_tags(&pairs).is_none(), "{pairs:?} should not be earth");
        }
    }

    /// **The reason `natural=coastline` is not handled here.** Land is defined by absence: the
    /// coastline ways enclose it, and turning them into polygons is a stitching problem, not a
    /// classification one.
    #[test]
    fn a_coastline_way_is_not_classified_as_land() {
        assert!(classify_tags(&[("natural", "coastline")]).is_none());
    }

    /// A land file that yields nothing is an error, not an empty layer. Silently shipping an
    /// oceanless world because a path was wrong is the failure this catches.
    #[test]
    fn a_missing_or_empty_prepared_polygon_is_an_error_rather_than_an_empty_layer() {
        let scratch = std::env::temp_dir().join("mamaps_land_sink.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");

        let missing = std::path::Path::new("no_such_land_polygons.geojsonseq");
        assert!(stream_prepared(missing, (-180.0, -90.0, 180.0, 90.0), &mut sink).is_err());

        let empty = std::env::temp_dir().join("mamaps_empty_land.geojsonseq");
        std::fs::write(&empty, "\n\n").expect("write");
        let failure = match stream_prepared(&empty, (-180.0, -90.0, 180.0, 90.0), &mut sink) {
            Ok(_) => panic!("an empty land file should be refused"),
            Err(e) => e,
        };
        assert!(failure.0.contains("no land polygon"), "{}", failure.0);
        let _ = std::fs::remove_file(&empty);
        let _ = std::fs::remove_file(&scratch);
    }

    #[test]
    fn a_prepared_polygon_streams_in_as_an_unfiltered_earth_feature() {
        let path = std::env::temp_dir().join("mamaps_land.geojsonseq");
        std::fs::write(
            &path,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-120.0,35.0],[-119.0,35.0],[-119.0,36.0],[-120.0,36.0],\
             [-120.0,35.0]]]}}\n",
        )
        .expect("write");
        let scratch = std::env::temp_dir().join("mamaps_land_out.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        assert_eq!(stream_prepared(&path, (-180.0, -90.0, 180.0, 90.0), &mut sink).expect("read"), 1);
        let store = sink.finish(&scratch).expect("finish");

        let feature = store
            .reader()
            .expect("reader")
            .next()
            .expect("read")
            .expect("a feature");
        assert_eq!(feature.class.layer, dict::LAYER_EARTH);
        // No kind, so the style's unfiltered `earth` layer draws it.
        assert_eq!(feature.class.kind, dict::NONE);
        assert_eq!(feature.class.min_zoom, 0, "the mainland carries a world tile");
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&scratch);
    }
    /// **The bug this clip exists for.** The land polygon product covers the whole planet, so a
    /// California build that did not clip would tile every coastline on Earth: it ran for 37 minutes
    /// before being killed, and the archive it was building had a global bounding box.
    #[test]
    fn a_land_polygon_outside_the_build_area_is_not_carried() {
        let path = std::env::temp_dir().join("mamaps_land_clip.geojsonseq");
        // One polygon off California, one off Portugal.
        std::fs::write(
            &path,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-120.0,35.0],[-119.0,35.0],[-119.0,36.0],[-120.0,36.0],\
             [-120.0,35.0]]]}}\n\
             {\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-9.0,38.0],[-8.0,38.0],[-8.0,39.0],[-9.0,39.0],[-9.0,38.0]]]}}\n",
        )
        .expect("write");

        let scratch = std::env::temp_dir().join("mamaps_land_clip.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let california = (-125.0, 32.0, -114.0, 42.0);
        assert_eq!(
            stream_prepared(&path, california, &mut sink).expect("read"),
            1,
            "only the polygon meeting the build area is carried",
        );

        // Straddling the edge counts as inside: that is land in the extract, cut off at the border.
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let straddling = (-119.5, 35.5, -100.0, 45.0);
        assert_eq!(stream_prepared(&path, straddling, &mut sink).expect("read"), 1);

        // And an area with no land in it is an error rather than an empty layer, because it almost
        // certainly means the wrong file or the wrong extract.
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let pacific = (-160.0, 0.0, -150.0, 10.0);
        assert!(stream_prepared(&path, pacific, &mut sink).is_err());

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&scratch);
    }
}
