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
        // Sidecar attributes (poi_attrs.bin). `24/7` earns its place here: it used
        // to parse as permanently closed, so it is the case worth pinning.
        (st.id("opening_hours"), st.id("24/7")),
        (st.id("contact:phone"), st.id("+1-555-0100")),
        (st.id("website"), st.id("https://cafe.example")),
        (st.id("addr:housenumber"), st.id("120")),
        (st.id("addr:street"), st.id("Market St")),
        (st.id("cuisine"), st.id("coffee_shop")),
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
    data_blob(&primitive_block())
}

fn data_blob(payload: &[u8]) -> Vec<u8> {
    let mut blob = Vec::new();
    varint_field(2, payload.len() as u64, &mut blob); // raw_size
    bytes_field(3, &zlib_store(payload), &mut blob); // zlib_data
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
    pbf_from_block(&primitive_block())
}

fn pbf_from_block(block: &[u8]) -> Vec<u8> {
    let mut header_block = Vec::new();
    bytes_field(4, b"OsmSchema-V0.6", &mut header_block);
    bytes_field(4, b"DenseNodes", &mut header_block);
    bytes_field(16, b"osm_ingest test fixture", &mut header_block);
    let mut header_blob = Vec::new();
    varint_field(2, header_block.len() as u64, &mut header_blob);
    bytes_field(3, &zlib_store(&header_block), &mut header_blob);

    let mut out = Vec::new();
    framed("OSMHeader", &header_blob, &mut out);
    framed("OSMData", &data_blob(block), &mut out);
    out
}

/// Write the fixture to a unique temp directory and return the path plus the
/// directory (which the caller may use for outputs).
pub fn write_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &sample_pbf())
}

fn write_pbf(tag: &str, bytes: &[u8]) -> (std::path::PathBuf, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!("osm_ingest_{tag}"));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("sample.osm.pbf");
    std::fs::write(&path, bytes).unwrap();
    (path, dir)
}

// ---- the vector-layer fixture --------------------------------------------
//
// A SECOND fixture rather than more features in the one above. The road-graph and
// POI tests assert exact counts against it (`ways.len() == 3`, `records == 3`,
// `NODE_COUNT`), so every addition there ripples into four modules' tests and
// makes each of them slightly less about what it is testing. The vector layers
// need different elements anyway.

/// `highway=speed_camera` with a direction.
pub const CAMERA_NODE_ID: i64 = 1001;
/// `man_made=surveillance` + `surveillance:type=ALPR` + a Flock operator, so it
/// hits the ALPR branch by two independent signals.
pub const ALPR_NODE_ID: i64 = 1003;
pub const STOP_SIGN_NODE_ID: i64 = 1006;
pub const SIGNALS_NODE_ID: i64 = 1007;
/// A named cafe: tagged, but not road furniture. The negative control.
pub const LAYERS_CAFE_NODE_ID: i64 = 1008;

/// `highway=residential` + `maxspeed=25 mph`, the raw-string case.
pub const MAXSPEED_WAY_ID: i64 = 3001;
/// `highway=motorway` + `maxspeed=none`, which the graph's parser would collapse
/// to 0 and this layer must keep verbatim. Also the `roads` layer's
/// fully-attributed way: a oneway bridge with lanes, turn lanes, width and layer.
pub const MAXSPEED_NONE_WAY_ID: i64 = 3002;
/// `highway=service` with no speed limit at all. The negative control.
pub const NO_MAXSPEED_WAY_ID: i64 = 3003;
/// `railway=subway`, a transit line in its own right.
pub const RAILWAY_WAY_ID: i64 = 5001;
/// `railway=narrow_gauge`, which folds into `rail`.
pub const NARROW_GAUGE_WAY_ID: i64 = 5002;
/// `railway=platform`, which is not a line. The negative control.
pub const PLATFORM_WAY_ID: i64 = 5003;
/// A **closed** `railway=platform` way, carried by the route relation below as a
/// `role=platform` member. The shape that drew a box around every station.
pub const ROUTE_PLATFORM_WAY_ID: i64 = 5004;
/// `type=route` + `route=subway`, with a colour and a ref, over two member ways
/// plus a `role=platform` member that is not part of the line.
pub const ROUTE_RELATION_ID: i64 = 9001;
/// `type=route` + `route=bus`, which this layer drops.
pub const BUS_RELATION_ID: i64 = 9002;
/// `type=boundary` + `boundary=administrative` + `admin_level=8`, with an outer
/// ring split across two ways and one inner ring (a hole).
pub const ADMIN_CITY_RELATION_ID: i64 = 9101;
/// The same, but `admin_level=6`: a county, which the city layer drops.
pub const ADMIN_COUNTY_RELATION_ID: i64 = 9102;

/// The corners of [`ROUTE_PLATFORM_WAY_ID`], well clear of every other fixture
/// coordinate so its absence from the route's geometry can be asserted directly.
const ROUTE_PLATFORM_NODES: [(i64, i32, i32); 4] = [
    (2201, 377_950_000, -1_224_050_000),
    (2202, 377_950_000, -1_224_040_000),
    (2203, 377_960_000, -1_224_040_000),
    (2204, 377_960_000, -1_224_050_000),
];

/// Way ids the admin boundary is assembled from.
pub const ADMIN_OUTER_WAY_A: i64 = 7001;
pub const ADMIN_OUTER_WAY_B: i64 = 7002;
pub const ADMIN_INNER_WAY: i64 = 7003;

/// Node ids the vector-layer fixture's ways are built from.
const WAY_NODE_IDS: [i64; 6] = [2001, 2002, 2003, 2004, 2005, 2006];

/// The admin boundary's outer ring corners, then its hole's corners. A 0.04-degree
/// square near the other fixture geometry, with a 0.01-degree hole inside it.
const ADMIN_NODES: [(i64, i32, i32); 8] = [
    (2101, 378_000_000, -1_224_000_000),
    (2102, 378_000_000, -1_223_600_000),
    (2103, 378_400_000, -1_223_600_000),
    (2104, 378_400_000, -1_224_000_000),
    (2111, 378_100_000, -1_223_900_000),
    (2112, 378_100_000, -1_223_800_000),
    (2113, 378_200_000, -1_223_800_000),
    (2114, 378_200_000, -1_223_900_000),
];

/// One node in the vector-layer fixture: `(id, lat_e7, lon_e7, tags)`.
type FixtureNode = (i64, i32, i32, Vec<(u32, u32)>);

/// Dense-node group for an arbitrary node list. Ids must ascend, as they do in a
/// real PBF.
fn dense_group(nodes: &[FixtureNode]) -> Vec<u8> {
    let mut keys_vals: Vec<u32> = Vec::new();
    for (_, _, _, tags) in nodes {
        for (k, v) in tags {
            keys_vals.push(*k);
            keys_vals.push(*v);
        }
        keys_vals.push(ST_EMPTY);
    }
    let mut out = Vec::new();
    packed_delta(1, &nodes.iter().map(|n| n.0).collect::<Vec<_>>(), &mut out);
    packed_delta(8, &nodes.iter().map(|n| n.1 as i64).collect::<Vec<_>>(), &mut out);
    packed_delta(9, &nodes.iter().map(|n| n.2 as i64).collect::<Vec<_>>(), &mut out);
    packed_u32(10, &keys_vals, &mut out);
    out
}

/// The `PrimitiveBlock` for the vector-layer fixture. All nodes sit in San
/// Francisco, around 37.77N 122.41W, so a plausible `--bbox` includes them and an
/// Atlantic one does not.
pub fn layers_block() -> Vec<u8> {
    let mut st = StringTable::new();

    let k_highway = st.id("highway");
    let k_man_made = st.id("man_made");
    let k_surveillance_type = st.id("surveillance:type");
    let k_operator = st.id("operator");
    let k_direction = st.id("direction");
    let k_amenity = st.id("amenity");
    let k_name = st.id("name");
    let k_maxspeed = st.id("maxspeed");
    let k_maxspeed_forward = st.id("maxspeed:forward");
    let k_railway = st.id("railway");
    let k_type = st.id("type");
    let k_route = st.id("route");
    let k_ref = st.id("ref");
    let k_colour = st.id("colour");
    let k_boundary = st.id("boundary");
    let k_admin_level = st.id("admin_level");
    // roads: the attributes the `roads` layer carries beyond geometry and class.
    let k_lanes = st.id("lanes");
    let k_turn_lanes_forward = st.id("turn:lanes:forward");
    let k_oneway = st.id("oneway");
    let k_width = st.id("width");
    let k_bridge = st.id("bridge");
    let k_layer = st.id("layer");

    let v_speed_camera = st.id("speed_camera");
    let v_surveillance = st.id("surveillance");
    let v_alpr = st.id("ALPR");
    let v_flock = st.id("Flock Safety");
    let v_forward = st.id("forward");
    let v_stop = st.id("stop");
    let v_traffic_signals = st.id("traffic_signals");
    let v_cafe = st.id("cafe");
    let v_corner_cafe = st.id("Corner Cafe");
    let v_residential = st.id("residential");
    let v_motorway = st.id("motorway");
    let v_service = st.id("service");
    let v_25mph = st.id("25 mph");
    let v_none = st.id("none");
    let v_30mph = st.id("30 mph");
    let v_main_st = st.id("Main St");
    let v_subway = st.id("subway");
    let v_narrow_gauge = st.id("narrow_gauge");
    let v_platform = st.id("platform");
    let v_market_st = st.id("Market St Subway");
    let v_route = st.id("route");
    let v_bus = st.id("bus");
    let v_red_line = st.id("Red Line");
    let v_red = st.id("Red");
    let v_da291c = st.id("#DA291C");
    let v_boundary = st.id("boundary");
    let v_administrative = st.id("administrative");
    let v_eight = st.id("8");
    let v_six = st.id("6");
    let v_oakland = st.id("Oakland");
    let v_alameda = st.id("Alameda County");
    let v_three = st.id("3");
    let v_through_through_right = st.id("through|through|right");
    let v_yes = st.id("yes");
    let v_12m = st.id("12 m");
    let v_one = st.id("1");
    let role_outer = st.id("outer");
    let role_inner = st.id("inner");
    let role_platform = st.id("platform");
    // An empty member role is string-table index 0, per the PBF spec.
    let role_empty = ST_EMPTY;

    // Ids ascend, as they do in a real PBF.
    let nodes: Vec<FixtureNode> = vec![
        (
            CAMERA_NODE_ID,
            377_749_000,
            -1_224_194_000,
            vec![(k_highway, v_speed_camera), (k_direction, v_forward)],
        ),
        (
            ALPR_NODE_ID,
            377_770_000,
            -1_224_170_000,
            vec![
                (k_man_made, v_surveillance),
                (k_surveillance_type, v_alpr),
                (k_operator, v_flock),
            ],
        ),
        (
            STOP_SIGN_NODE_ID,
            377_800_000,
            -1_224_150_000,
            vec![(k_highway, v_stop)],
        ),
        (
            SIGNALS_NODE_ID,
            377_810_000,
            -1_224_140_000,
            vec![(k_highway, v_traffic_signals)],
        ),
        (
            LAYERS_CAFE_NODE_ID,
            377_820_000,
            -1_224_130_000,
            vec![(k_amenity, v_cafe), (k_name, v_corner_cafe)],
        ),
        // Untagged nodes the ways are built from, laid out along 37.79N.
        (WAY_NODE_IDS[0], 377_900_000, -1_224_300_000, vec![]),
        (WAY_NODE_IDS[1], 377_900_000, -1_224_200_000, vec![]),
        (WAY_NODE_IDS[2], 377_900_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[3], 377_910_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[4], 377_920_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[5], 377_930_000, -1_224_100_000, vec![]),
    ];
    let mut nodes = nodes;
    for (id, lat, lon) in ADMIN_NODES.into_iter().chain(ROUTE_PLATFORM_NODES) {
        nodes.push((id, lat, lon, vec![]));
    }
    let dense = dense_group(&nodes);

    let ways = [
        // maxspeed: a raw "25 mph", with a highway and a name to carry.
        way(
            MAXSPEED_WAY_ID,
            &[
                (k_highway, v_residential),
                (k_maxspeed, v_25mph),
                (k_name, v_main_st),
            ],
            &WAY_NODE_IDS[..3],
        ),
        // maxspeed=none, plus a directional tag that must NOT win over it. Also the
        // `roads` layer's fully-attributed way: a oneway bridge with lanes, turn
        // lanes, a width and a layer.
        way(
            MAXSPEED_NONE_WAY_ID,
            &[
                (k_highway, v_motorway),
                (k_maxspeed, v_none),
                (k_maxspeed_forward, v_30mph),
                (k_lanes, v_three),
                (k_turn_lanes_forward, v_through_through_right),
                (k_oneway, v_yes),
                (k_width, v_12m),
                (k_bridge, v_yes),
                (k_layer, v_one),
            ],
            &WAY_NODE_IDS[2..5],
        ),
        // No speed limit at all.
        way(NO_MAXSPEED_WAY_ID, &[(k_highway, v_service)], &WAY_NODE_IDS[..2]),
        // transit_lines: a subway way with a name, and a member of the route below.
        way(
            RAILWAY_WAY_ID,
            &[(k_railway, v_subway), (k_name, v_market_st)],
            &WAY_NODE_IDS[..3],
        ),
        // narrow_gauge folds into rail; also the route's second member.
        way(
            NARROW_GAUGE_WAY_ID,
            &[(k_railway, v_narrow_gauge)],
            &WAY_NODE_IDS[3..6],
        ),
        // A platform is not a line.
        way(PLATFORM_WAY_ID, &[(k_railway, v_platform)], &WAY_NODE_IDS[..2]),
        // And a closed one, which the route relation below claims as `role=platform`.
        way(
            ROUTE_PLATFORM_WAY_ID,
            &[(k_railway, v_platform)],
            &[2201, 2202, 2203, 2204, 2201],
        ),
        // admin_city: the outer ring split across two ways, so the assembler has to
        // stitch them, and one of them is reversed so it has to flip one too.
        way(ADMIN_OUTER_WAY_A, &[], &[2101, 2102, 2103]),
        way(ADMIN_OUTER_WAY_B, &[], &[2101, 2104, 2103]),
        // The hole, closed on its own.
        way(ADMIN_INNER_WAY, &[], &[2111, 2112, 2113, 2114, 2111]),
    ];

    let rels = [
        // A route relation over two member ways, so its geometry is a genuine
        // MultiLineString rather than a single part.
        relation(
            ROUTE_RELATION_ID,
            &[
                (k_type, v_route),
                (k_route, v_subway),
                (k_name, v_red_line),
                (k_ref, v_red),
                (k_colour, v_da291c),
            ],
            &[
                (RAILWAY_WAY_ID, role_empty, 1),
                (NARROW_GAUGE_WAY_ID, role_empty, 1),
                (ROUTE_PLATFORM_WAY_ID, role_platform, 1),
            ],
        ),
        // A bus route: this layer is the rail-like modes only.
        relation(
            BUS_RELATION_ID,
            &[(k_type, v_route), (k_route, v_bus)],
            &[(RAILWAY_WAY_ID, role_empty, 1)],
        ),
        // An admin_level=8 boundary. One member is unroled, which the OSM boundary
        // convention says is outer -- the case that matters most, since most real
        // members are unroled.
        relation(
            ADMIN_CITY_RELATION_ID,
            &[
                (k_type, v_boundary),
                (k_boundary, v_administrative),
                (k_admin_level, v_eight),
                (k_name, v_oakland),
            ],
            &[
                (ADMIN_OUTER_WAY_A, role_outer, 1),
                (ADMIN_OUTER_WAY_B, role_empty, 1),
                (ADMIN_INNER_WAY, role_inner, 1),
            ],
        ),
        // A county: same shape, wrong level.
        relation(
            ADMIN_COUNTY_RELATION_ID,
            &[
                (k_type, v_boundary),
                (k_boundary, v_administrative),
                (k_admin_level, v_six),
                (k_name, v_alameda),
            ],
            &[(ADMIN_OUTER_WAY_A, role_outer, 1), (ADMIN_OUTER_WAY_B, role_empty, 1)],
        ),
    ];

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

/// Write the vector-layer fixture to a unique temp directory.
pub fn write_layers_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &pbf_from_block(&layers_block()))
}

// ---- the dangling-reference fixture --------------------------------------
//
// A THIRD fixture, for one hole: a routable way referencing a node the file never
// defines. Real extracts are full of these — every way clipped at an extract's
// boundary keeps refs to nodes outside it — and the road graph's whole handling
// of them is "the pair fails to resolve, so skip it", which nothing tested.
//
// It matters more than it looks. The node-id bitset is marked from way refs, so a
// dangling ref *is* marked, and any scheme that treats the marked set as the node
// address space hands it a slot. A slot that never receives coordinates reads as
// (0, 0) — null island — which corrupts distances, spatial keys and polylines
// while leaving every count plausible.

/// A node id no element in this fixture defines, referenced from the middle of a
/// routable way.
pub const DANGLING_REF_ID: i64 = 999;
pub const DANGLING_WAY_ID: i64 = 103;

/// `(id, lat_e7, lon_e7)` of the nodes this fixture does define.
pub const DANGLING_NODES: [(i64, i32, i32); 3] = [
    (1, 370_000_000, -1_220_000_000),
    (2, 370_010_000, -1_220_000_000),
    (3, 370_020_000, -1_220_000_000),
];

/// One routable way over `[1, 2, 999, 3]`, so the dangling ref sits between two
/// present nodes and breaks two consecutive pairs rather than one.
fn dangling_block() -> Vec<u8> {
    let mut st = StringTable::new();
    let k_hw = st.id("highway");
    let k_name = st.id("name");
    let v_residential = st.id("residential");
    let v_gap = st.id("Gap St");

    let nodes: Vec<FixtureNode> = DANGLING_NODES
        .iter()
        .map(|(id, lat, lon)| (*id, *lat, *lon, Vec::new()))
        .collect();
    let dense = dense_group(&nodes);
    let ways = [way(
        DANGLING_WAY_ID,
        &[(k_hw, v_residential), (k_name, v_gap)],
        &[1, 2, DANGLING_REF_ID, 3],
    )];

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for w in &ways {
        bytes_field(3, w, &mut way_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    block
}

/// Write the dangling-reference fixture to a unique temp directory.
pub fn write_dangling_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &pbf_from_block(&dangling_block()))
}

// ---- an arbitrary graph shape --------------------------------------------
//
// The three fixtures above each say something specific and the tests assert exact
// counts against them, so growing one to cover a new topology makes every test
// that reads it slightly less about what it was testing. Chain cutting is all
// topology, though, and needs many shapes: a ring, a self-touching way, two ways
// meeting end-to-end, a repeated ref. This builds a PBF from a shape stated
// directly in the test.

/// A PBF of untagged nodes plus `highway=residential` ways over them.
///
/// Deliberately minimal: no names, no lanes, no speed limits and no oneways, so
/// every way agrees with every other on attributes and the *only* thing deciding
/// where chains are cut is the graph's shape.
pub fn write_shape_sample(
    tag: &str,
    nodes: &[(i64, i32, i32)],
    ways: &[(i64, &[i64])],
) -> (std::path::PathBuf, std::path::PathBuf) {
    let mut st = StringTable::new();
    let k_hw = st.id("highway");
    let v_residential = st.id("residential");

    let fixture: Vec<FixtureNode> = nodes
        .iter()
        .map(|(id, lat, lon)| (*id, *lat, *lon, Vec::new()))
        .collect();
    let dense = dense_group(&fixture);

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for (id, refs) in ways {
        bytes_field(3, &way(*id, &[(k_hw, v_residential)], refs), &mut way_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    write_pbf(tag, &pbf_from_block(&block))
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
