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

/// Read a prepared land polygon into `earth` features.
///
/// One GeoJSON feature per line, as `tile_build`'s own reader expects, and every polygon becomes one
/// `earth` feature with no kind — which is what the style's unfiltered `earth` layer draws.
pub fn read_prepared(path: &Path) -> Result<Vec<crate::extract::Feature>> {
    let text = std::fs::read_to_string(path).map_err(|e| {
        osm_ingest::proto::Error(format!("cannot read {}: {e}", path.display()))
    })?;
    let mut out = Vec::new();
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
        // Polygons only. A prepared land product has nothing else in it, and a line in there would
        // be a sign the wrong file was handed over.
        let polygons = match feature.geometry {
            Geometry::Polygons(polygons) => polygons,
            _ => continue,
        };
        if polygons.is_empty() {
            continue;
        }
        out.push(crate::extract::Feature {
            // No kind: the mainland is what `earth`'s unfiltered layer draws, and giving it one
            // would put it behind a whitelist it is not in.
            class: Class::area(LAYER_EARTH, tilecodec::mamaps::dict::NONE, 0),
            geometry: Geometry::Polygons(polygons),
        });
    }
    if out.is_empty() {
        return err(format!("{} holds no land polygons", path.display()));
    }
    Ok(out)
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

    #[test]
    fn a_missing_or_empty_prepared_polygon_is_an_error_rather_than_an_empty_layer() {
        let missing = std::path::Path::new("no_such_land_polygons.geojsonseq");
        assert!(read_prepared(missing).is_err());

        let empty = std::env::temp_dir().join("mamaps_empty_land.geojsonseq");
        std::fs::write(&empty, "\n\n").expect("write");
        let failure = match read_prepared(&empty) {
            Ok(_) => panic!("an empty land file should be refused"),
            Err(e) => e,
        };
        assert!(failure.0.contains("no land polygons"), "{}", failure.0);
        let _ = std::fs::remove_file(&empty);
    }

    #[test]
    fn a_prepared_polygon_reads_as_an_unfiltered_earth_feature() {
        let path = std::env::temp_dir().join("mamaps_land.geojsonseq");
        std::fs::write(
            &path,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-120.0,35.0],[-119.0,35.0],[-119.0,36.0],[-120.0,36.0],\
             [-120.0,35.0]]]}}\n",
        )
        .expect("write");
        let features = read_prepared(&path).expect("read");
        assert_eq!(features.len(), 1);
        assert_eq!(features[0].class.layer, dict::LAYER_EARTH);
        // No kind, so the style's unfiltered `earth` layer draws it.
        assert_eq!(features[0].class.kind, dict::NONE);
        assert_eq!(features[0].class.min_zoom, 0, "the mainland carries a world tile");
        let _ = std::fs::remove_file(&path);
    }
}
