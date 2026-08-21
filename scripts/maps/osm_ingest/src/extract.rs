//! Vector-layer extraction: `.osm.pbf` -> one geojsonseq per layer.
//!
//! The `osmium tags-filter | osmium export | normalize_*.py` chain, done in one
//! pass over the PBF with no external tools. Each layer's schema lives in its own
//! module ([`crate::safety`] and so on); this module owns the PBF traversal, the
//! bbox filter, the deterministic ordering and the output file.
//!
//! ## Pass ordering is fixed, and reversed
//!
//! Layers that need way or relation geometry cannot be done in one pass, because
//! a PBF stores nodes before the ways that reference them. So the traversal runs
//! **relations, then ways, then nodes** -- the reverse of the file order:
//!
//! 1. **Relations** decide which ways matter (a boundary's member ways, a route's
//!    member ways).
//! 2. **Ways** decide which node coordinates matter -- both their own refs and the
//!    refs of the ways relations claimed.
//! 3. **Nodes** supply those coordinates, and any node-based features.
//!
//! Only the passes a layer actually needs are run. `safety` is node-based, so it
//! runs one. The first pass to run is given `blob_kinds = None`, which is what
//! makes [`crate::pbf::run_pass`] build the per-blob kind mask that later passes
//! use to skip whole blobs.
//!
//! When way and relation layers land here, their node coordinates must be stored
//! the way [`crate::poi_build`] does it -- a sorted `Vec` of the needed ids plus an
//! index-aligned coordinate array, looked up by `binary_search` -- and **not** the
//! way [`crate::graph_build`] does it. That module allocates a bitset sized from
//! `BITSET_SIZE = 20e9`, i.e. 2.5 GB keyed by raw node id, and peaks around 10 GB
//! on California. The sorted-`Vec` form costs 16 bytes per *needed* node, which
//! for a selected subset is orders of magnitude smaller.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::bbox::{self, BBox};
use crate::geojson::{self, Feature};
use crate::osm::{visit_block, Element};
use crate::pbf::{self, KIND_NODES};
use crate::proto::{Error, Result};
use crate::safety::{self, Kind, SafetyTags};
use crate::select::Select;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layer {
    Safety,
}

impl Layer {
    pub fn parse(s: &str) -> std::result::Result<Layer, String> {
        match s {
            "safety" => Ok(Layer::Safety),
            other => Err(format!(
                "unknown --layer '{other}'; supported: {}",
                Layer::names().join(", ")
            )),
        }
    }

    pub fn names() -> Vec<&'static str> {
        vec!["safety"]
    }

    pub fn name(self) -> &'static str {
        match self {
            Layer::Safety => "safety",
        }
    }
}

pub struct Options {
    pub layer: Layer,
    pub bbox: Option<BBox>,
}

#[derive(Debug, Default, PartialEq)]
pub struct Stats {
    pub features: usize,
    pub from_nodes: usize,
    pub from_ways: usize,
    pub from_relations: usize,
    /// Classified, but outside `--bbox`. Reported so an empty layer can be told
    /// apart from a badly placed box.
    pub outside_bbox: usize,
}

pub fn build(input: &Path, out: &Path, opts: &Options) -> Result<Stats> {
    // Fail before the passes rather than after them if the output is unwritable.
    if let Some(parent) = out.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)
            .map_err(|e| Error(format!("cannot create {}: {e}", parent.display())))?;
    }

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    match opts.layer {
        Layer::Safety => build_safety(input, &blobs, out, opts),
    }
}

// --- safety ---------------------------------------------------------------

/// One classified node, with the attributes it carries copied out of the block.
///
/// The pass borrows tag values from the `PrimitiveBlock` it is reading, which is
/// dropped when the blob is, so anything kept has to be owned.
struct SafetyRow {
    lat_e7: i32,
    lon_e7: i32,
    id: i64,
    kind: Kind,
    name: Option<String>,
    direction: Option<String>,
    operator: Option<String>,
    reference: Option<String>,
    surveillance_type: Option<String>,
}

#[derive(Default)]
struct SafetyPass {
    rows: Vec<SafetyRow>,
    outside_bbox: usize,
}

fn build_safety(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    // The `osmium tags-filter` expression this layer used, as a cheap screen: it
    // reads three tags to reject almost every node, instead of the ten the
    // classifier and the property builder would read between them. Tag lookup is
    // a linear scan, so that matters at planet scale.
    let select = Select::parse(&safety::FILTERS)?;
    let bbox = opts.bbox.as_ref();

    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_NODES,
        "Pass 1: nodes",
        SafetyPass::default,
        |state, block| safety_blob(state, block, &select, bbox),
    )?;

    let mut rows: Vec<SafetyRow> = Vec::new();
    let mut outside_bbox = 0usize;
    for chunk in chunks {
        rows.extend(chunk.rows);
        outside_bbox += chunk.outside_bbox;
    }

    // Deterministic order, so two runs of the same input are byte-identical and a
    // diff against the previous build shows only real changes. Position first
    // keeps features that are near each other near each other in the file, which
    // is what the tiler wants; the id only breaks ties.
    rows.sort_by_key(|r| (r.lat_e7, r.lon_e7, r.id));

    let mut writer = BufWriter::new(create(out)?);
    let mut line: Vec<u8> = Vec::new();
    for row in &rows {
        let tags = SafetyTags {
            name: row.name.as_deref(),
            direction: row.direction.as_deref(),
            operator: row.operator.as_deref(),
            reference: row.reference.as_deref(),
            surveillance_type: row.surveillance_type.as_deref(),
            ..Default::default()
        };
        let f: Feature = safety::feature(
            row.kind,
            &tags,
            row.lon_e7 as f64 * 1e-7,
            row.lat_e7 as f64 * 1e-7,
            row.id,
        );
        geojson::write_feature(&mut writer, &f, &mut line).map_err(io_err)?;
    }
    writer.flush().map_err(io_err)?;

    println!(
        "Wrote {} safety feature(s) to {}",
        rows.len(),
        out.display()
    );
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: rows.len(),
        from_nodes: rows.len(),
        from_ways: 0,
        from_relations: 0,
        outside_bbox,
    })
}

fn safety_blob(
    state: &mut SafetyPass,
    block: &pbf::PrimitiveBlock,
    select: &Select,
    bbox: Option<&BBox>,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| n.tags.get_str(k)) {
                return Ok(());
            }
            let t = SafetyTags {
                highway: n.tags.get_str("highway"),
                man_made: n.tags.get_str("man_made"),
                enforcement: n.tags.get_str("enforcement"),
                surveillance_type: n.tags.get_str("surveillance:type"),
                camera_type: n.tags.get_str("camera:type"),
                operator: n.tags.get_str("operator"),
                manufacturer: n.tags.get_str("manufacturer"),
                name: n.tags.get_str("name"),
                direction: n.tags.get_str("direction"),
                reference: n.tags.get_str("ref"),
            };
            let Some(kind) = safety::classify(&t) else {
                return Ok(());
            };
            // Counted after classification, so the number means "safety features
            // outside the box" rather than "nodes outside the box".
            if !bbox::keep_e7(bbox, n.lat_e7, n.lon_e7) {
                state.outside_bbox += 1;
                return Ok(());
            }
            state.rows.push(SafetyRow {
                lat_e7: n.lat_e7,
                lon_e7: n.lon_e7,
                id: n.id,
                kind,
                name: t.name.map(str::to_string),
                direction: t.direction.map(str::to_string),
                operator: t.operator.map(str::to_string),
                reference: t.reference.map(str::to_string),
                surveillance_type: t.surveillance_type.map(str::to_string),
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

// --- CLI ------------------------------------------------------------------

pub struct Args {
    pub input: PathBuf,
    pub out: PathBuf,
    pub layer: Layer,
    pub bbox: Option<BBox>,
}

/// `osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX]`
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut layer: Option<Layer> = None;
    let mut bbox: Option<BBox> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            flag @ ("--out" | "--layer" | "--bbox") => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| format!("{flag} needs a value"))?
                    .as_str();
                match flag {
                    "--out" => out = Some(PathBuf::from(value)),
                    "--layer" => layer = Some(Layer::parse(value)?),
                    _ => bbox = Some(BBox::parse(value).map_err(|e| e.0)?),
                }
            }
            a if a.starts_with('-') => return Err(format!("unknown option: {a}")),
            a => {
                if input.is_some() {
                    return Err(format!("unexpected extra argument: {a}"));
                }
                input = Some(PathBuf::from(a));
            }
        }
        i += 1;
    }
    Ok(Args {
        input: input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        out: out.ok_or_else(|| "--out is required".to_string())?,
        layer: layer.ok_or_else(|| "--layer is required".to_string())?,
        bbox,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    fn features(path: &Path) -> Vec<String> {
        std::fs::read_to_string(path)
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn extracts_the_safety_layer_from_a_pbf() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_safety");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: None,
            },
        )
        .unwrap();

        // The fixture's four safety nodes: a speed camera, an ALPR, a stop sign
        // and traffic signals. Everything else in it belongs to other layers.
        assert_eq!(stats.features, 4);
        assert_eq!(stats.from_nodes, 4);
        assert_eq!((stats.from_ways, stats.from_relations), (0, 0));

        let lines = features(&out);
        assert_eq!(lines.len(), 4);
        let kinds: Vec<&str> = ["speed_camera", "alpr", "stop_sign", "traffic_signals"]
            .into_iter()
            .filter(|k| lines.iter().any(|l| l.contains(&format!("\"kind\":\"{k}\""))))
            .collect();
        assert_eq!(kinds, ["speed_camera", "alpr", "stop_sign", "traffic_signals"]);

        // Every line is a Point Feature with an osm_id in the node/N form.
        for l in &lines {
            assert!(l.starts_with("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\""), "{l}");
            assert!(l.contains("\"osm_id\":\"node/"), "{l}");
        }

        // The ALPR node keeps its operator and surveillance_type.
        let alpr = lines.iter().find(|l| l.contains("\"alpr\"")).unwrap();
        assert!(alpr.contains("\"operator\":\"Flock Safety\""), "{alpr}");
        assert!(alpr.contains("\"surveillance_type\":\"ALPR\""), "{alpr}");

        // The cafe node has a name and tags, but is not road furniture.
        assert!(!lines.iter().any(|l| l.contains("Corner Cafe")));
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_det");
        let run = |suffix: &str| {
            let out = dir.join(format!("safety{suffix}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options {
                    layer: Layer::Safety,
                    bbox: None,
                },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));
    }

    #[test]
    fn a_bbox_that_excludes_everything_yields_an_empty_layer() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox");
        let out = dir.join("safety.geojsonseq");
        // The fixture sits near 37N 122W; this box is over the Atlantic.
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        // The count says "4 safety features were outside the box", which is what
        // distinguishes a bad bbox from a PBF with no cameras in it.
        assert_eq!(stats.outside_bbox, 4);
        assert_eq!(features(&out).len(), 0);
    }

    #[test]
    fn a_bbox_that_includes_everything_changes_nothing() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox_all");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-123,36,-121,38").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 4);
        assert_eq!(stats.outside_bbox, 0);
    }

    #[test]
    fn args_need_a_layer_and_an_out() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "s.geojsonseq".into(),
            "--bbox".into(),
            "-122.6,37.2,-121.7,37.9".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.layer, Layer::Safety);
        assert!(ok.bbox.is_some());

        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--layer".into(), "safety".into()]).is_err());
        assert!(parse_args(&["--layer".into(), "safety".into(), "--out".into(), "o".into()]).is_err());
        // A bad layer name names the ones that do exist.
        let err = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "nope".into(),
            "--out".into(),
            "o".into(),
        ])
        .map(|_| ())
        .unwrap_err();
        assert!(err.contains("safety"), "{err}");
        // A malformed bbox is rejected here, not silently ignored.
        assert!(parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "o".into(),
            "--bbox".into(),
            "1,2,3".into(),
        ])
        .is_err());
    }
}
