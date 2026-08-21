//! Spatial primitives shared by both tools.
//!
//! Both functions are on-disk / cross-tool contracts:
//! * [`latlng_to_spatial`] must agree bit-for-bit with
//!   `Graph::latlng_to_spatial` in `maps/src/main/rust/src/graph.rs`, because the
//!   device binary-searches `nodes.bin` and `poi_index.bin` by this key.
//! * [`accurate_dist_mm`] produces the `dist_mm` stored in every edge, so the
//!   radius `R` must stay at the value the router's cost model was tuned against.

pub const DEG_TO_RAD: f64 = std::f64::consts::PI / 180.0;

/// Earth radius in millimetres, as used by the router.
const R_MM: f64 = 6_371_000_800.0;

/// 64-bit Morton (Z-order) code of a lat/lon pair.
pub fn latlng_to_spatial(lat: f64, lon: f64) -> u64 {
    let x = (lon + 180.0) / 360.0;
    let y = (lat + 90.0) / 180.0;
    let ix = (x * 4_294_967_295.0) as u32;
    let iy = (y * 4_294_967_295.0) as u32;
    let mut res: u64 = 0;
    for i in 0..32u64 {
        res |= (((ix >> i) & 1) as u64) << (2 * i);
        res |= (((iy >> i) & 1) as u64) << (2 * i + 1);
    }
    res
}

#[inline]
pub fn spatial_from_e7(lat_e7: i32, lon_e7: i32) -> u64 {
    latlng_to_spatial(lat_e7 as f64 * 1e-7, lon_e7 as f64 * 1e-7)
}

/// Haversine great-circle distance in millimetres, saturating at `u32::MAX`.
pub fn accurate_dist_mm(lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> u32 {
    let phi1 = (lat1_e7 as f64 * 1e-7) * DEG_TO_RAD;
    let phi2 = (lat2_e7 as f64 * 1e-7) * DEG_TO_RAD;
    // Widened before subtracting: two points either side of the antimeridian are
    // ~3.6e9 apart in 1e-7 degrees, which overflows i32.
    let delta_phi = (lat2_e7 as i64 - lat1_e7 as i64) as f64 * 1e-7 * DEG_TO_RAD;
    let delta_lambda = (lon2_e7 as i64 - lon1_e7 as i64) as f64 * 1e-7 * DEG_TO_RAD;
    let s_dphi = (delta_phi / 2.0).sin();
    let s_dlamb = (delta_lambda / 2.0).sin();
    let a = s_dphi * s_dphi + phi1.cos() * phi2.cos() * s_dlamb * s_dlamb;
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    (R_MM * c) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The reader's copy of the same function, transcribed from
    /// `maps/src/main/rust/src/graph.rs`. If either drifts the device's spatial
    /// binary search silently returns the wrong nodes, so they are compared here
    /// rather than trusted.
    fn graph_rs_latlng_to_spatial(lat: f64, lon: f64) -> u64 {
        let x = (lon + 180.0) / 360.0;
        let y = (lat + 90.0) / 180.0;
        let ix = (x * 4_294_967_295.0) as u32;
        let iy = (y * 4_294_967_295.0) as u32;
        let mut res: u64 = 0;
        for i in 0..32u64 {
            res |= (((ix >> i) & 1) as u64) << (2 * i);
            res |= (((iy >> i) & 1) as u64) << (2 * i + 1);
        }
        res
    }

    #[test]
    fn morton_key_matches_the_router() {
        let coords = [
            (0.0, 0.0),
            (37.7749, -122.4194),   // San Francisco
            (35.2828, -120.6596),   // San Luis Obispo
            (-33.8688, 151.2093),   // Sydney
            (90.0, 180.0),
            (-90.0, -180.0),
            (0.0000001, -0.0000001),
        ];
        for (lat, lon) in coords {
            assert_eq!(
                latlng_to_spatial(lat, lon),
                graph_rs_latlng_to_spatial(lat, lon),
                "({lat}, {lon})"
            );
        }
    }

    #[test]
    fn morton_interleaves_x_into_even_bits() {
        // lon = -180 -> ix = 0; lat = -90 -> iy = 0.
        assert_eq!(latlng_to_spatial(-90.0, -180.0), 0);
        // Every odd bit set means iy = u32::MAX-ish and ix = 0.
        let k = latlng_to_spatial(90.0, -180.0);
        assert_eq!(k & 0x5555_5555_5555_5555, 0, "no even (lon) bit set");
        assert_ne!(k, 0);
    }

    #[test]
    fn morton_is_monotonic_in_a_single_axis() {
        let a = latlng_to_spatial(37.0, -122.0);
        let b = latlng_to_spatial(37.0, -121.0);
        assert!(a < b, "increasing longitude must increase the key");
    }

    #[test]
    fn haversine_matches_known_city_pairs() {
        // Distances are within 0.5% of the published great-circle values, which
        // catches a transcription error (wrong radius, swapped lat/lon, degrees
        // used as radians) while tolerating last-bit differences.
        let cases = [
            // SF -> LA, ~559 km
            ((377_749_000, -1_224_194_000), (340_522_000, -1_182_437_000), 559_000_000u32),
            // SF -> San Luis Obispo, ~318.6 km
            ((377_749_000, -1_224_194_000), (352_828_000, -1_206_596_000), 318_600_000),
            // 1 degree of latitude at the equator, ~111.2 km
            ((0, 0), (10_000_000, 0), 111_200_000),
        ];
        for ((la1, lo1), (la2, lo2), expect) in cases {
            let got = accurate_dist_mm(la1, lo1, la2, lo2);
            assert!(
                got.abs_diff(expect) < expect / 200,
                "expected ~{expect} mm, got {got} mm"
            );
        }
    }

    #[test]
    fn haversine_is_zero_and_symmetric() {
        let sf = (377_749_000, -1_224_194_000);
        let la = (340_522_000, -1_182_437_000);
        assert_eq!(accurate_dist_mm(sf.0, sf.1, sf.0, sf.1), 0);
        assert_eq!(
            accurate_dist_mm(sf.0, sf.1, la.0, la.1),
            accurate_dist_mm(la.0, la.1, sf.0, sf.1)
        );
    }

    #[test]
    fn haversine_spans_the_antimeridian_without_overflowing() {
        // The coordinate delta here is ~3.6e9, well past i32::MAX. Going the short
        // way round is ~2.2 km; haversine takes the long way (~40,000 km) because
        // it has no wrap-around notion, but it must not overflow or panic.
        let d = accurate_dist_mm(0, 1_799_900_000, 0, -1_799_900_000);
        assert!(d > 0, "got {d}");
        assert_eq!(d, accurate_dist_mm(0, -1_799_900_000, 0, 1_799_900_000));
        // Same for latitude, pole to pole.
        assert!(accurate_dist_mm(-900_000_000, 0, 900_000_000, 0) > 0);
    }
}
