//! Dynamic map markers: the app's pins, and (WS-F) simulated transit vehicles.
//!
//! A marker is the sprite counterpart of the user puck ([`crate::vulkan::renderer::UserPuck`]):
//! a screen-anchored icon glued to a ground `lon`/`lat`, billboarded upright under tilt through
//! [`crate::camera::Camera::screen_quad_to_clip`], and drawn from the shared unit quad by the
//! sprite pipeline against the process-global sprite atlas ([`crate::tile::sprite`]). Moving the
//! pins into the renderer is what stops them trailing the basemap on a pan or a tilt the way the
//! Compose overlays did.
//!
//! # The shared contract with WS-F (transit vehicles)
//!
//! WS-F draws simulated transit vehicles through this same path: an [`crate::vulkan::renderer`]
//! `Overlay::Vehicles(Vec<Marker>)` variant beside `Overlay::Markers`, the same billboarded
//! sprite draw, and the same atlas. A vehicle is just a [`Marker`] whose [`icon`](Marker::icon)
//! names a mode sprite (bus/tram/train/ferry), so the bulk many-sprites case drops in with no new
//! machinery — only a new `Overlay` arm and a bulk setter. The icon ids WS-F needs are already
//! reserved in [`icon`] below.
//!
//! # Why an icon *id* rather than a name across the JNI boundary
//!
//! Kotlin passes a small integer per marker, not a string: the boundary stays allocation-free and
//! ABI-stable (see [`crate::bridge`]), and the atlas can grow — a dedicated pin sheet added on the
//! build side — without changing the JNI signature. The renderer resolves the id to a sprite-atlas
//! **name** here, and a name the sheet does not carry simply draws nothing, exactly as a POI with
//! no icon does ([`crate::tile::sprite::SpriteAtlas::get`]).

/// One marker: a stable id for picking, a ground position, and which atlas icon to draw.
///
/// [`id`](Self::id) is the app's own stable feature id (a pin's parking/search/saved id, or a
/// vehicle's trip id), returned verbatim by the id-buffer pick so the host can rejoin the tap to
/// its own data — it is *not* interpreted here.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Marker {
    /// The host's stable id for this marker, echoed back by [`crate::vulkan::renderer`] picking.
    pub id: u64,
    /// Ground longitude in degrees.
    pub lon: f64,
    /// Ground latitude in degrees.
    pub lat: f64,
    /// Which atlas icon to draw — see [`icon`] and [`icon_sprite_name`].
    pub icon: u32,
}

/// The screen size a marker icon is drawn at, in Dp.
///
/// Screen-constant like the puck's radius: the icon keeps this size as the camera zooms, because
/// it is placed by [`crate::camera::Camera::screen_quad_to_clip`] (a fixed Dp extent) rather than
/// by a tile matrix. A touch larger than a POI icon (19 Dp) so an app pin reads as foreground.
pub const MARKER_SIZE_DP: f32 = 28.0;

/// Icon ids: the shared contract with WS-F. Kotlin passes these as ints; the renderer resolves
/// each to a sprite-atlas name via [`icon_sprite_name`].
///
/// The pin ids (0–4) are WS-C's; the vehicle ids (5–8) are reserved for WS-F so a mode maps to a
/// sprite through the same table and draw path. Ids are append-only — a value's meaning never
/// changes, so a host built against an older id set keeps working.
pub mod icon {
    /// The saved parking spot.
    pub const PARKING: u32 = 0;
    /// A transit stop pin (the app's live-departures pin, distinct from the basemap POI).
    pub const TRANSIT_STOP: u32 = 1;
    /// A search-result pin.
    pub const SEARCH: u32 = 2;
    /// A saved-place pin (home/work/starred).
    pub const SAVED: u32 = 3;
    /// A family-member location pin.
    pub const FAMILY: u32 = 4;

    /// WS-F: a bus vehicle.
    pub const VEHICLE_BUS: u32 = 5;
    /// WS-F: a tram/light-rail vehicle.
    pub const VEHICLE_TRAM: u32 = 6;
    /// WS-F: a train/subway vehicle.
    pub const VEHICLE_TRAIN: u32 = 7;
    /// WS-F: a ferry vehicle.
    pub const VEHICLE_FERRY: u32 = 8;
}

/// The sprite-atlas name an [`icon`] id resolves to, or `None` for an unknown id.
///
/// # Provisional pin art
///
/// The current sprite sheet (`assets/sprites/sprites@2x.png`) carries the Protomaps POI icons,
/// which have no dedicated *pin* pictograms, so the pin ids map to the closest existing sprite so a
/// marker is visible today rather than blank. A follow-up build-side asset pass can add dedicated
/// `pin-*` sprites and repoint these names with no code change beyond this table — the JNI ids stay
/// the same. The vehicle ids already resolve to real transit sprites, so WS-F needs no new art.
pub fn icon_sprite_name(icon: u32) -> Option<&'static str> {
    let name = match icon {
        // Pins — provisional mappings onto existing POI sprites (see the doc above).
        icon::PARKING => "fuel",
        icon::TRANSIT_STOP => "bus_stop",
        icon::SEARCH => "attraction",
        icon::SAVED => "artwork",
        icon::FAMILY => "attraction",
        // Vehicles (WS-F) — real transit sprites already in the sheet.
        icon::VEHICLE_BUS => "bus_stop",
        icon::VEHICLE_TRAM => "train_station",
        icon::VEHICLE_TRAIN => "train_station",
        icon::VEHICLE_FERRY => "ferry_terminal",
        _ => return None,
    };
    Some(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every declared icon id resolves to a name the sprite sheet actually carries, so a marker
    /// pushed by the host draws its icon rather than silently nothing. If the sheet is ever
    /// repacked without one of these, this fails here rather than as a blank pin on a device.
    #[test]
    fn every_icon_id_resolves_to_a_sprite_in_the_sheet() {
        let atlas = crate::tile::sprite::atlas();
        for id in [
            icon::PARKING,
            icon::TRANSIT_STOP,
            icon::SEARCH,
            icon::SAVED,
            icon::FAMILY,
            icon::VEHICLE_BUS,
            icon::VEHICLE_TRAM,
            icon::VEHICLE_TRAIN,
            icon::VEHICLE_FERRY,
        ] {
            let name = icon_sprite_name(id).unwrap_or_else(|| panic!("icon {id} has no name"));
            assert!(atlas.get(name).is_some(), "icon {id} -> `{name}` is not in the sheet");
        }
    }

    /// An unknown id is a miss, not a panic and not a wrong icon: the renderer skips it, exactly
    /// as it skips a POI kind the sheet has no picture for.
    #[test]
    fn an_unknown_icon_id_is_a_miss() {
        assert!(icon_sprite_name(9999).is_none());
        assert!(icon_sprite_name(u32::MAX).is_none());
    }

    /// The vehicle ids WS-F builds on are the reserved 5–8 and resolve to transit sprites, so the
    /// bulk vehicle case needs no new art. Pins them so a renumbering that collided with a pin id
    /// is caught here.
    #[test]
    fn the_reserved_vehicle_ids_map_to_transit_sprites() {
        assert_eq!(icon_sprite_name(icon::VEHICLE_BUS), Some("bus_stop"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_TRAM), Some("train_station"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_TRAIN), Some("train_station"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_FERRY), Some("ferry_terminal"));
    }
}
