//! Nearby Connections `BleAdvertisement` codec and service-id derivation.
//!
//! Quick Share bootstraps over BLE service UUID **`0000FEF3-0000-1000-8000-00805F9B34FB`**
//! (`p000\dses.java:21`; the secondary is `0000FC73-…` at `:22`, and `:20` holds the
//! GATT-server variant `0000FEF3-0004-1000-8000-001A11000100`). The Nearby
//! Presence UUID `0xFCF1` this module replaces belongs to a different subsystem —
//! see [`crate::presence`].
//!
//! The Nearby Connections service id is the literal string `"NearbySharing"`
//! (`p000\dzqx.java:108`, `:143`, `:150`), and both the mDNS service type and the
//! BLE `serviceIdHash` are truncations of its SHA-256:
//!
//! - mDNS: `_%s._tcp` with `%s` = uppercase hex of `SHA-256(serviceId)[0..6]`
//!   (`p000\dsmo.java:119-121`, `:180`; `drwj.m66219Z` is SHA-256-then-`copyOf`,
//!   `blqe.m21076e` is uppercase hex) → **`_FC9F5ED42C8A._tcp`**.
//! - BLE: the 3-byte `serviceIdHash` in the advertisement → **`FC9F5E`**.
//!
//! # What this codec cannot do
//!
//! It produces the `BleAdvertisement` envelope only. The `data` payload it carries is
//! the Nearby Sharing endpoint-info blob, which lives in [`crate::endpoint_info`] — a
//! peer that cannot parse that blob discards the whole advertisement
//! (`p000\eafg.java:89-93`).

use sha2::{Digest, Sha256};

/// The Nearby Connections service id Quick Share registers under.
///
/// `p000\dzqx.java:108`, `:143`, `:150` pass this literal to advertise, discover
/// and connect.
pub const SERVICE_ID: &str = "NearbySharing";

/// Bytes of `SHA-256(SERVICE_ID)` used for the mDNS service type.
///
/// `drwj.m66219Z(str.getBytes(), 6)` at `p000\dsmo.java:120`.
pub const MDNS_HASH_LEN: usize = 6;

/// Bytes of `SHA-256(SERVICE_ID)` used for the BLE `serviceIdHash`.
///
/// `p000\dscb.java:44` copies the caller's hash to exactly 3 bytes and `:86`
/// rejects any other length.
pub const BLE_SERVICE_ID_HASH_LEN: usize = 3;

/// Only supported `BleAdvertisement` version — `p000\dscb.java:64-68` accepts 2.
pub const BLE_ADV_VERSION: u8 = 2;

/// Only supported socket version — `p000\dscb.java:56-60` accepts 2.
pub const BLE_ADV_SOCKET_VERSION: u8 = 2;

/// `deviceToken` width — `p000\dscb.java:90` rejects any length but 2.
pub const DEVICE_TOKEN_LEN: usize = 2;

/// Maximum total advertisement length in fast mode — `p000\dscb.java:118`.
pub const MAX_FAST_ADV_LEN: usize = 27;

/// Maximum total advertisement length in extended mode — `p000\dscb.java:116`.
pub const MAX_EXTENDED_ADV_LEN: usize = 512;

/// FastInitiation service-data prefix — `p000\dvyf.java:34` (`blqe.m21075d("FC128E")`).
///
/// The 16-bit service UUID itself (`0xFE2C`, `p000\dvyf.java:10`) lives in
/// `BleDiscoveryManager.kt`, where the `ScanFilter` needs it.
pub const FAST_INITIATION_PREFIX: [u8; 3] = [0xFC, 0x12, 0x8E];

fn service_id_digest() -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(SERVICE_ID.as_bytes());
    hasher.finalize().into()
}

fn upper_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02X}")).collect()
}

/// The mDNS service type Quick Share browses and registers, derived at runtime.
///
/// Returns `_FC9F5ED42C8A._tcp`. Derived rather than hard-coded so a wrong digest
/// fails in [`tests::mdns_service_type_matches_gms`] instead of on the wire.
pub fn mdns_service_type() -> String {
    let digest = service_id_digest();
    let prefix = digest.get(..MDNS_HASH_LEN).unwrap_or(&digest);
    format!("_{}._tcp", upper_hex(prefix))
}

/// The 3-byte BLE `serviceIdHash` for `"NearbySharing"`.
pub fn ble_service_id_hash() -> [u8; BLE_SERVICE_ID_HASH_LEN] {
    let digest = service_id_digest();
    let mut out = [0u8; BLE_SERVICE_ID_HASH_LEN];
    if let Some(prefix) = digest.get(..BLE_SERVICE_ID_HASH_LEN) {
        out.copy_from_slice(prefix);
    }
    out
}

/// A parsed or to-be-serialised Nearby Connections `BleAdvertisement`.
///
/// Layout per `p000\dscb.java:126-146` (serialise) and `p000\dsdu.java:239-297`
/// (parse):
///
/// ```text
/// byte 0 : (version << 5) & 0xE0 | (socketVersion << 2) & 0x1C | (fast ? 2 : 0) | (rx ? 1 : 0)
///          fast mode                    extended mode
///          ─────────                    ─────────────
///          u8 dataLen                   3-byte serviceIdHash
///          data                         u32be dataLen
///          2-byte deviceToken (opt)     data
///                                       2-byte deviceToken (opt)
///                                       trailing extra field (opt, not modelled)
/// ```
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BleAdvertisement {
    /// `true` for the 27-byte legacy advertising payload, `false` for extended.
    pub is_fast: bool,
    /// Set when this advertisement solicits a scan response (`p000\dsdu.java:250`).
    pub is_rx: bool,
    /// 3-byte truncated service-id hash. Absent in fast mode.
    pub service_id_hash: Option<[u8; BLE_SERVICE_ID_HASH_LEN]>,
    /// The endpoint payload — a [`crate::endpoint_info`] blob.
    pub data: Vec<u8>,
    /// Optional 2-byte device token.
    pub device_token: Option<[u8; DEVICE_TOKEN_LEN]>,
}

impl BleAdvertisement {
    /// Build an extended-mode advertisement for `"NearbySharing"`.
    pub fn extended(data: Vec<u8>, device_token: Option<[u8; DEVICE_TOKEN_LEN]>) -> Self {
        Self {
            is_fast: false,
            is_rx: false,
            service_id_hash: Some(ble_service_id_hash()),
            data,
            device_token,
        }
    }

    /// Build a fast-mode advertisement, for a legacy 31-byte BLE advertisement.
    ///
    /// Fast mode carries no `serviceIdHash` and a single length byte, so it fits about
    /// four characters of device name once the endpoint-info header is accounted for;
    /// [`serialize`](Self::serialize) returns `None` above [`MAX_FAST_ADV_LEN`].
    pub fn fast(data: Vec<u8>, device_token: Option<[u8; DEVICE_TOKEN_LEN]>) -> Self {
        Self {
            is_fast: true,
            is_rx: false,
            service_id_hash: None,
            data,
            device_token,
        }
    }

    /// Serialise, or `None` if the result would exceed the mode's length budget
    /// (`p000\dscb.java:110-123`).
    pub fn serialize(&self) -> Option<Vec<u8>> {
        let token_len = self.device_token.map_or(0, |t| t.len());
        let (total, limit) = if self.is_fast {
            (self.data.len() + 2 + token_len, MAX_FAST_ADV_LEN)
        } else {
            (self.data.len() + 8 + token_len, MAX_EXTENDED_ADV_LEN)
        };
        if total > limit {
            return None;
        }
        if self.is_fast && u8::try_from(self.data.len()).is_err() {
            return None;
        }
        let mut out = Vec::with_capacity(total);
        let mut header = ((BLE_ADV_VERSION << 5) & 0xE0) | ((BLE_ADV_SOCKET_VERSION << 2) & 0x1C);
        if self.is_fast {
            header |= 0x02;
        }
        if self.is_rx {
            header |= 0x01;
        }
        out.push(header);
        if !self.is_fast {
            let hash = self.service_id_hash.unwrap_or_else(ble_service_id_hash);
            out.extend_from_slice(&hash);
        }
        if self.is_fast {
            out.push(self.data.len() as u8);
        } else {
            out.extend_from_slice(&(self.data.len() as u32).to_be_bytes());
        }
        out.extend_from_slice(&self.data);
        if let Some(token) = self.device_token {
            out.extend_from_slice(&token);
        }
        Some(out)
    }

    /// Parse, or `None` if the bytes are not a supported advertisement.
    ///
    /// Rejects versions other than 2 and socket versions other than 2, exactly as
    /// `p000\dsdu.java:242-247` does.
    pub fn parse(raw: &[u8]) -> Option<Self> {
        let header = *raw.first()?;
        if (header & 0xE0) >> 5 != BLE_ADV_VERSION {
            return None;
        }
        if (header & 0x1C) >> 2 != BLE_ADV_SOCKET_VERSION {
            return None;
        }
        let is_fast = (header & 0x02) != 0;
        let is_rx = (header & 0x01) != 0;
        let min_len = if is_fast { 2 } else { 8 };
        if raw.len() < min_len {
            return None;
        }
        let mut cursor = 1usize;
        let service_id_hash = if is_fast {
            None
        } else {
            let slice = raw.get(cursor..cursor + BLE_SERVICE_ID_HASH_LEN)?;
            let mut hash = [0u8; BLE_SERVICE_ID_HASH_LEN];
            hash.copy_from_slice(slice);
            cursor += BLE_SERVICE_ID_HASH_LEN;
            Some(hash)
        };
        let data_len = if is_fast {
            let len = *raw.get(cursor)?;
            cursor += 1;
            len as usize
        } else {
            let slice = raw.get(cursor..cursor + 4)?;
            let mut be = [0u8; 4];
            be.copy_from_slice(slice);
            cursor += 4;
            // p000\dsdu.java:271 rejects a negative signed length.
            let signed = i32::from_be_bytes(be);
            if signed < 0 {
                return None;
            }
            signed as usize
        };
        let data = raw.get(cursor..cursor + data_len)?.to_vec();
        cursor += data_len;
        // p000\dsdu.java:278 takes the token only if 2 or more bytes remain.
        let device_token = match raw.get(cursor..cursor + DEVICE_TOKEN_LEN) {
            Some(slice) => {
                let mut token = [0u8; DEVICE_TOKEN_LEN];
                token.copy_from_slice(slice);
                Some(token)
            }
            None => None,
        };
        Some(Self {
            is_fast,
            is_rx,
            service_id_hash,
            data,
            device_token,
        })
    }
}

/// Build the FastInitiation service-data for `0xFE2C`.
///
/// `p000\dvyf.java:34-38` fixes the `FC128E` prefix and a total length of
/// `prefix + 2`; `p000\dvys.java:288-295` advertises it. This is the "someone
/// nearby is sharing" beacon — it makes a receiver show the heads-up
/// notification, and it is not required for a transfer.
pub fn fast_initiation_service_data(metadata: [u8; 2]) -> Vec<u8> {
    let mut out = Vec::with_capacity(FAST_INITIATION_PREFIX.len() + 2);
    out.extend_from_slice(&FAST_INITIATION_PREFIX);
    out.extend_from_slice(&metadata);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mdns_service_type_matches_gms() {
        // If SHA-256("NearbySharing")[0..6] ever stops being FC9F5ED42C8A, either the
        // service id or the digest recipe is wrong and discovery silently breaks.
        assert_eq!(mdns_service_type(), "_FC9F5ED42C8A._tcp");
    }

    #[test]
    fn ble_service_id_hash_is_the_first_three_digest_bytes() {
        assert_eq!(ble_service_id_hash(), [0xFC, 0x9F, 0x5E]);
    }

    #[test]
    fn extended_advertisement_round_trip() {
        let adv = BleAdvertisement::extended(b"endpoint-blob".to_vec(), Some([0xAB, 0xCD]));
        let raw = adv.serialize().expect("serialize");
        // header: version 2 -> 0x40, socketVersion 2 -> 0x08, not fast, not rx.
        assert_eq!(raw.first().copied(), Some(0x48));
        assert_eq!(raw.get(1..4), Some([0xFC, 0x9F, 0x5E].as_slice()));
        assert_eq!(raw.get(4..8), Some([0x00, 0x00, 0x00, 0x0D].as_slice()));
        assert_eq!(BleAdvertisement::parse(&raw), Some(adv));
    }

    #[test]
    fn fast_advertisement_round_trip() {
        let adv = BleAdvertisement {
            is_fast: true,
            is_rx: true,
            service_id_hash: None,
            data: b"short".to_vec(),
            device_token: Some([0x01, 0x02]),
        };
        let raw = adv.serialize().expect("serialize");
        // header: 0x40 | 0x08 | fast 0x02 | rx 0x01
        assert_eq!(raw.first().copied(), Some(0x4B));
        // Fast mode uses a single length byte and carries no serviceIdHash.
        assert_eq!(raw.get(1).copied(), Some(5));
        assert_eq!(BleAdvertisement::parse(&raw), Some(adv));
    }

    #[test]
    fn fast_mode_length_budget_is_enforced() {
        let adv = BleAdvertisement {
            is_fast: true,
            is_rx: false,
            service_id_hash: None,
            data: vec![0u8; MAX_FAST_ADV_LEN],
            device_token: None,
        };
        assert!(adv.serialize().is_none());
    }

    #[test]
    fn wrong_version_is_rejected() {
        // version 1 in the top three bits: 0x20 | socketVersion 2.
        assert!(BleAdvertisement::parse(&[0x28, 0x00]).is_none());
        // version 2 but socket version 1: 0x40 | 0x04.
        assert!(BleAdvertisement::parse(&[0x44, 0x00]).is_none());
    }

    #[test]
    fn truncated_advertisement_is_rejected() {
        assert!(BleAdvertisement::parse(&[]).is_none());
        // Claims 100 bytes of data but supplies none.
        assert!(BleAdvertisement::parse(&[0x48, 0xFC, 0x9F, 0x5E, 0, 0, 0, 100]).is_none());
    }

    #[test]
    fn fast_initiation_data_has_the_gms_prefix() {
        assert_eq!(
            fast_initiation_service_data([0x00, 0x00]),
            vec![0xFC, 0x12, 0x8E, 0x00, 0x00]
        );
    }
}
