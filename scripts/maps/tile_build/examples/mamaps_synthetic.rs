//! Write a small synthetic `.mamaps` archive, so `mamaps_dump` has something real to read.
//!
//! Not a published artefact and not part of any build: an example rather than a bin, because its
//! only job is to prove the writer, the reader and the dump agree on a file that exists on disk.
//! The generator that will produce real archives is `scripts/maps/mamaps_build`.

use tile_build::mamaps::body::{
    Body, Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, GEOM_POLYGON, NAME_NONE,
    WINDING_HOLE, WINDING_OUTER,
};
use tile_build::mamaps::write::{Options, StreamWriter};
use tile_build::mamaps::{dict, read};
use tile_build::pmtiles::tile_id;

fn main() {
    let out = std::env::args().nth(1).unwrap_or_else(|| "synthetic.mamaps".to_string());
    let max_zoom = 4u8;
    let options = Options {
        min_zoom: 0,
        max_zoom,
        build_id: 0x5EED_0000_0000_0001,
        rings_validated: true,
        ..Options::default()
    };
    let mut writer = StreamWriter::new(options).expect("options");

    // Ascending tile ids, which is what the writer requires and what a zoom-major Hilbert walk
    // produces for free.
    let mut ids: Vec<(u64, u8, u64, u64)> = Vec::new();
    for z in 0..=max_zoom {
        for x in 0..(1u64 << z) {
            for y in 0..(1u64 << z) {
                ids.push((tile_id(z, x, y), z, x, y));
            }
        }
    }
    ids.sort_by_key(|(id, _, _, _)| *id);
    for (id, z, x, y) in &ids {
        writer.append(*id, &tile(*z, *x, *y)).expect("append");
    }
    let bytes = writer.finish().expect("finish");

    // Read it back before it reaches disk, so a file that will not open never gets written.
    let entries = read::read_all(&bytes).expect("read back");
    std::fs::write(&out, &bytes).expect("write");
    println!(
        "wrote {out}: {} bytes, {} tiles addressed, {} index entries",
        bytes.len(),
        ids.len(),
        entries.len(),
    );
}

/// One tile. Land and a lake everywhere; a road only in the eastern half, so a dump shows
/// per-layer differences rather than one uniform number.
fn tile(z: u8, x: u64, _y: u64) -> Body {
    let mut earth = Layer::new(dict::LAYER_EARTH);
    earth.features.push(Feature {
        kind: dict::NONE,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
    });
    earth.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    let e = DEFAULT_EXTENT as i16;
    earth.coords = vec![(0, 0), (e, 0), (e, e), (0, e)];

    // A lake, with a hole, so the ring-winding fields carry something.
    let mut water = Layer::new(dict::LAYER_WATER);
    water.features.push(Feature {
        kind: 4,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 2,
        transit_color: 0,
    });
    water.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    water.parts.push(Part { coord_start: 4, point_count: 4, winding: WINDING_HOLE });
    water.coords = vec![
        (512, 512),
        (2048, 512),
        (2048, 2048),
        (512, 2048),
        (900, 900),
        (900, 1200),
        (1200, 1200),
        (1200, 900),
    ];

    let mut layers = vec![earth, water];
    if z >= 2 && x >= (1u64 << z) / 2 {
        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
        roads.coords = vec![(0, e / 2), (e / 2, e / 2 + 64), (e, e / 2)];
        layers.push(roads);
    }
    Body { extent: DEFAULT_EXTENT, layers, names: Vec::new() }
}
