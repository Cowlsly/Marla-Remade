//! A hand-built `.osm.pbf` used by the crate's tests.
//!
//! Encoding the fixture by hand (rather than checking in a binary) keeps the
//! test readable and, more importantly, exercises the decoder against bytes
//! produced independently of it. The DEFLATE side uses *stored* blocks, so the
//! zlib framing and Adler-32 trailer are real without pulling in a compressor.

const ST_EMPTY: u32 = 0;

/// Node ids 1..=6 are laid out as a 4-node road, one detached bus stop and one
/// cafe.
pub const NODE_COUNT: usize = 6;
/// A `highway=bus_stop` node that no way references, so it lands outside the
/// largest connected component and must be reconnected synthetically.
pub const STOP_NODE_ID: i64 = 5;
pub const CAFE_NODE_ID: i64 = 6;
pub const MAIN_WAY_ID: i64 = 100;
pub const SERVICE_WAY_ID: i64 = 101;
pub const AREA_WAY_ID: i64 = 102;
pub const RELATION_ID: i64 = 200;

/// `(id, lat_e7, lon_e7)` of every node in the fixture. The road runs north
/// along 122°W near 37°N; the bus stop sits just off it and the cafe beyond it.
pub const NODES: [(i64, i32, i32); NODE_COUNT] = [
    (1, 370_000_000, -1_220_000_000),
    (2, 370_010_000, -1_220_000_000),
    (3, 370_020_000, -1_220_000_000),
    (4, 370_030_000, -1_220_000_000),
    (STOP_NODE_ID, 370_005_000, -1_220_005_000),
    (CAFE_NODE_ID, 370_040_000, -1_220_010_000),
];

// ---- protobuf encoding helpers -------------------------------------------

fn uvarint(mut v: u64, out: &mut Vec<u8>) {
    loop {
        let b = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

fn zz(v: i64) -> u64 {
    ((v << 1) ^ (v >> 63)) as u64
}

fn varint_field(field: u32, v: u64, out: &mut Vec<u8>) {
    uvarint((field as u64) << 3, out);
    uvarint(v, out);
}

fn bytes_field(field: u32, data: &[u8], out: &mut Vec<u8>) {
    uvarint(((field as u64) << 3) | 2, out);
    uvarint(data.len() as u64, out);
    out.extend_from_slice(data);
}

fn packed_u32(field: u32, values: &[u32], out: &mut Vec<u8>) {
    if values.is_empty() {
        return;
    }
    let mut payload = Vec::new();
    for v in values {
        uvarint(*v as u64, &mut payload);
    }
    bytes_field(field, &payload, out);
}

fn packed_delta(field: u32, values: &[i64], out: &mut Vec<u8>) {
    if values.is_empty() {
        return;
    }
    let mut payload = Vec::new();
    let mut prev = 0i64;
    for v in values {
        uvarint(zz(v - prev), &mut payload);
        prev = *v;
    }
    bytes_field(field, &payload, out);
}

// ---- zlib with stored DEFLATE blocks -------------------------------------

fn adler32(data: &[u8]) -> u32 {
    let mut a: u32 = 1;
    let mut b: u32 = 0;
    for &x in data {
        a = (a + x as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

fn zlib_store(data: &[u8]) -> Vec<u8> {
    let mut out = vec![0x78, 0x01];
    let mut offset = 0usize;
    loop {
        let len = (data.len() - offset).min(0xFFFF);
        let last = offset + len >= data.len();
        out.push(if last { 1 } else { 0 }); // BFINAL, BTYPE = stored
        out.extend_from_slice(&(len as u16).to_le_bytes());
        out.extend_from_slice(&(!(len as u16)).to_le_bytes());
        out.extend_from_slice(&data[offset..offset + len]);
        offset += len;
        if last {
            break;
        }
    }
    out.extend_from_slice(&adler32(data).to_be_bytes());
    out
}

// ---- string table --------------------------------------------------------

struct StringTable {
    strings: Vec<Vec<u8>>,
}

impl StringTable {
    fn new() -> Self {
        // Index 0 must be unused/empty per the PBF spec.
        StringTable {
            strings: vec![Vec::new()],
        }
    }

    fn id(&mut self, s: &str) -> u32 {
        if let Some(i) = self.strings.iter().position(|x| x == s.as_bytes()) {
            return i as u32;
        }
        self.strings.push(s.as_bytes().to_vec());
        (self.strings.len() - 1) as u32
    }

    fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        for s in &self.strings {
            bytes_field(1, s, &mut out);
        }
        out
    }
}

// ---- the fixture itself --------------------------------------------------

fn dense_nodes(st: &mut StringTable) -> Vec<u8> {
    let stop_tags = [
        (st.id("highway"), st.id("bus_stop")),
        (st.id("name"), st.id("Test Stop")),
    ];
    let cafe_tags = [
        (st.id("amenity"), st.id("cafe")),
        (st.id("name"), st.id("Corner Cafe")),
    ];

    let mut keys_vals: Vec<u32> = Vec::new();
    for (id, _, _) in NODES {
        let tags: &[(u32, u32)] = match id {
            STOP_NODE_ID => &stop_tags,
            CAFE_NODE_ID => &cafe_tags,
            _ => &[],
        };
        for (k, v) in tags {
            keys_vals.push(*k);
            keys_vals.push(*v);
        }
        keys_vals.push(ST_EMPTY);
    }

    let mut out = Vec::new();
    packed_delta(1, &NODES.map(|n| n.0), &mut out);
    packed_delta(8, &NODES.map(|n| n.1 as i64), &mut out);
    packed_delta(9, &NODES.map(|n| n.2 as i64), &mut out);
    packed_u32(10, &keys_vals, &mut out);
    out
}

fn way(id: i64, tags: &[(u32, u32)], refs: &[i64]) -> Vec<u8> {
    let mut out = Vec::new();
    varint_field(1, id as u64, &mut out);
    packed_u32(2, &tags.iter().map(|t| t.0).collect::<Vec<_>>(), &mut out);
    packed_u32(3, &tags.iter().map(|t| t.1).collect::<Vec<_>>(), &mut out);
    packed_delta(8, refs, &mut out);
    out
}

fn relation(id: i64, tags: &[(u32, u32)], members: &[(i64, u32, u32)]) -> Vec<u8> {
    let mut out = Vec::new();
    varint_field(1, id as u64, &mut out);
    packed_u32(2, &tags.iter().map(|t| t.0).collect::<Vec<_>>(), &mut out);
    packed_u32(3, &tags.iter().map(|t| t.1).collect::<Vec<_>>(), &mut out);
    packed_u32(8, &members.iter().map(|m| m.1).collect::<Vec<_>>(), &mut out);
    packed_delta(9, &members.iter().map(|m| m.0).collect::<Vec<_>>(), &mut out);
    packed_u32(10, &members.iter().map(|m| m.2).collect::<Vec<_>>(), &mut out);
    out
}

/// The `PrimitiveBlock` message for the fixture: one dense-node group, one way
/// group and one relation group.
pub fn primitive_block() -> Vec<u8> {
    let mut st = StringTable::new();
    let dense = dense_nodes(&mut st);

    let (k_hw, k_name, k_lanes, k_tlf, k_oneway, k_maxspeed, k_amenity, k_type, k_leisure) = (
        st.id("highway"),
        st.id("name"),
        st.id("lanes"),
        st.id("turn:lanes:forward"),
        st.id("oneway"),
        st.id("maxspeed"),
        st.id("amenity"),
        st.id("type"),
        st.id("leisure"),
    );
    let v_residential = st.id("residential");
    let v_main = st.id("Main St");
    let v_two = st.id("2");
    let v_left_through = st.id("left|through");
    let v_service = st.id("service");
    let v_yes = st.id("yes");
    let v_30mph = st.id("30 mph");
    let v_cafe = st.id("cafe");
    let v_plaza = st.id("Plaza");
    let v_multipolygon = st.id("multipolygon");
    let v_park = st.id("Riverside Park");
    let v_leisure_park = st.id("park");
    let role_outer = st.id("outer");

    let ways = [
        // A 4-node bidirectional residential road with real turn:lanes.
        way(
            MAIN_WAY_ID,
            &[
                (k_hw, v_residential),
                (k_name, v_main),
                (k_lanes, v_two),
                (k_tlf, v_left_through),
            ],
            &[1, 2, 3, 4],
        ),
        // A oneway service road, mph speed limit, no name.
        way(
            SERVICE_WAY_ID,
            &[(k_hw, v_service), (k_oneway, v_yes), (k_maxspeed, v_30mph)],
            &[4, 2],
        ),
        // A closed, unrouted area: a POI polygon, invisible to the road graph.
        way(
            AREA_WAY_ID,
            &[(k_amenity, v_cafe), (k_name, v_plaza)],
            &[1, 2, 3, 4, 1],
        ),
    ];
    let rels = [relation(
        RELATION_ID,
        &[
            (k_type, v_multipolygon),
            (k_name, v_park),
            (k_leisure, v_leisure_park),
        ],
        // Outer ring = the closed way above, the case where the centroid can be
        // reproduced exactly.
        &[(AREA_WAY_ID, role_outer, 1)],
    )];

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for w in &ways {
        bytes_field(3, w, &mut way_group);
    }
    let mut rel_group = Vec::new();
    for r in &rels {
        bytes_field(4, r, &mut rel_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    bytes_field(2, &rel_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    block
}

/// The `Blob` message wrapping the fixture's `PrimitiveBlock`.
pub fn sample_data_blob() -> Vec<u8> {
    let payload = primitive_block();
    let mut blob = Vec::new();
    varint_field(2, payload.len() as u64, &mut blob); // raw_size
    bytes_field(3, &zlib_store(&payload), &mut blob); // zlib_data
    blob
}

fn framed(kind: &str, blob: &[u8], out: &mut Vec<u8>) {
    let mut header = Vec::new();
    bytes_field(1, kind.as_bytes(), &mut header);
    varint_field(3, blob.len() as u64, &mut header);
    out.extend_from_slice(&(header.len() as u32).to_be_bytes());
    out.extend_from_slice(&header);
    out.extend_from_slice(blob);
}

/// A complete `.osm.pbf`: one `OSMHeader` blob (which every reader must skip)
/// followed by one `OSMData` blob.
pub fn sample_pbf() -> Vec<u8> {
    let mut header_block = Vec::new();
    bytes_field(4, b"OsmSchema-V0.6", &mut header_block);
    bytes_field(4, b"DenseNodes", &mut header_block);
    bytes_field(16, b"osm_ingest test fixture", &mut header_block);
    let mut header_blob = Vec::new();
    varint_field(2, header_block.len() as u64, &mut header_blob);
    bytes_field(3, &zlib_store(&header_block), &mut header_blob);

    let mut out = Vec::new();
    framed("OSMHeader", &header_blob, &mut out);
    framed("OSMData", &sample_data_blob(), &mut out);
    out
}

/// Write the fixture to a unique temp directory and return the path plus the
/// directory (which the caller may use for outputs).
pub fn write_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!("osm_ingest_{tag}"));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("sample.osm.pbf");
    std::fs::write(&path, sample_pbf()).unwrap();
    (path, dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stored_deflate_round_trips_through_miniz() {
        for payload in [
            b"".to_vec(),
            b"hello".to_vec(),
            (0..200_000u32).map(|i| i as u8).collect::<Vec<u8>>(),
        ] {
            let z = zlib_store(&payload);
            let back = miniz_oxide::inflate::decompress_to_vec_zlib(&z).unwrap();
            assert_eq!(back, payload, "len {}", payload.len());
        }
    }

    #[test]
    fn adler32_matches_the_reference_value() {
        // The canonical zlib test vector.
        assert_eq!(adler32(b"Wikipedia"), 0x11E6_0398);
        assert_eq!(adler32(b""), 1);
    }

    #[test]
    fn a_corrupt_adler_trailer_is_rejected() {
        let mut z = zlib_store(b"payload");
        let last = z.len() - 1;
        z[last] ^= 0xFF;
        assert!(miniz_oxide::inflate::decompress_to_vec_zlib(&z).is_err());
    }
}
