// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Data types shared between V0 and V1 advertisements

use sink::Sink;
use strum_macros::FromRepr;
use tinyvec::ArrayVec;

/// Power in dBm, calibrated as per
/// [Eddystone](https://github.com/google/eddystone/tree/master/eddystone-uid#tx-power)
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct TxPower {
    /// Power in `[-100, 20]`
    power: i8,
}

impl TxPower {
    /// Power in `[-100, 20]`
    pub fn as_i8(&self) -> i8 {
        self.power
    }
}

impl TryFrom<i8> for TxPower {
    type Error = TxPowerOutOfRange;

    fn try_from(value: i8) -> Result<Self, Self::Error> {
        if !(-100..=20).contains(&value) {
            Err(TxPowerOutOfRange)
        } else {
            Ok(TxPower { power: value })
        }
    }
}

/// Tx power was out of the valid range `[-100, 20]`.
#[derive(Debug, PartialEq, Eq)]
pub struct TxPowerOutOfRange;

/// MDP's context sync number, used to inform other devices that they have stale context.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct ContextSyncSeqNum {
    /// 4-bit sequence
    num: u8,
}

impl ContextSyncSeqNum {
    /// Returns the context seq num as a u8
    pub fn as_u8(&self) -> u8 {
        self.num
    }
}

impl TryFrom<u8> for ContextSyncSeqNum {
    type Error = ContextSyncSeqNumOutOfRange;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        // must only have low nibble
        if value > 0x0F {
            Err(ContextSyncSeqNumOutOfRange)
        } else {
            Ok(ContextSyncSeqNum { num: value })
        }
    }
}

/// Seq num must be in `[0-15]`.
#[derive(Debug, PartialEq, Eq)]
pub struct ContextSyncSeqNumOutOfRange;

/// A description of what kind of device is broadcasting.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, FromRepr, strum_macros::EnumIter)]
pub enum DeviceType {
    /// The type of device doing the broadcasting is completely unknown.
    Unknown = 0,
    /// The broadcasting device is a mobile phone.
    Phone = 1,
    /// The broadcasting device is a tablet.
    Tablet = 2,
    /// The broadcasting device is a (non-TV) display.
    Display = 3,
    /// The broadcasting device is a (non-CrOS) laptop.
    Laptop = 4,
    /// The broadcasting device is a TV.
    TV = 5,
    /// The broadcasting device is a watch.
    Watch = 6,
    /// The broadcasting device is a Chromebook.
    ChromeOS = 7,
    /// The broadcasting device is some kind of foldable.
    Foldable = 8,
    /// The broadcasting device is a car.
    Automotive = 9,
    /// The broadscasting device is a speaker.
    Speaker = 10,
}

impl TryFrom<u8> for DeviceType {
    type Error = ();

    // We implement try_from to maintain the standard Rust idiom for fallible conversions.
    // This is the expected API for any crate consuming DeviceType directly.
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        DeviceType::from_repr(value).ok_or(())
    }
}

/// The minimum length of a device name in a `DeviceInfo` data element.
pub const MIN_DEVICE_NAME_LEN: usize = 5;
/// The maximum length of a device name in a `DeviceInfo` data element.
pub const MAX_DEVICE_NAME_LEN: usize = 9;

/// Device information including a device type and a device name.
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct DeviceInfo {
    device_type: DeviceType,
    name_truncated: bool,
    device_name: ArrayVec<[u8; MAX_DEVICE_NAME_LEN]>,
}

impl DeviceInfo {
    /// The device type.
    pub fn device_type(&self) -> DeviceType {
        self.device_type
    }

    /// Whether or not the device name was truncated.
    pub fn name_truncated(&self) -> bool {
        self.name_truncated
    }

    /// The device name.
    pub fn device_name(&self) -> &[u8] {
        &self.device_name
    }
}

/// Errors that can occur when creating a `DeviceInfo`.
#[derive(Debug, PartialEq, Eq)]
pub enum DeviceInfoMalformed {
    /// The device name is too short.
    NameTooShort,
    /// The device name is too long.
    NameTooLong,
}

impl<'a> TryFrom<(DeviceType, bool, &'a [u8])> for DeviceInfo {
    type Error = DeviceInfoMalformed;

    fn try_from(
        (device_type, name_truncated, device_name): (DeviceType, bool, &'a [u8]),
    ) -> Result<Self, Self::Error> {
        if device_name.len() < MIN_DEVICE_NAME_LEN {
            Err(DeviceInfoMalformed::NameTooShort)
        } else if device_name.len() > MAX_DEVICE_NAME_LEN {
            Err(DeviceInfoMalformed::NameTooLong)
        } else {
            let mut array_vec = ArrayVec::new();
            array_vec.try_extend_from_slice(device_name).ok_or(DeviceInfoMalformed::NameTooLong)?;
            Ok(Self { device_type, name_truncated, device_name: array_vec })
        }
    }
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tx_power_out_of_range() {
        assert_eq!(TxPowerOutOfRange, TxPower::try_from(-101).unwrap_err());
        assert_eq!(TxPowerOutOfRange, TxPower::try_from(21).unwrap_err());
    }

    #[test]
    fn tx_power_ok() {
        assert_eq!(-100, TxPower::try_from(-100).unwrap().power);
        assert_eq!(20, TxPower::try_from(20).unwrap().power);
    }

    #[test]
    fn context_sync_seq_num_out_of_range() {
        assert_eq!(ContextSyncSeqNumOutOfRange, ContextSyncSeqNum::try_from(0x10).unwrap_err());
    }

    #[test]
    fn context_sync_seq_num_ok() {
        assert_eq!(0x0F, ContextSyncSeqNum::try_from(0x0F).unwrap().num);
    }

    #[test]
    fn device_info_name_too_short() {
        assert_eq!(
            DeviceInfoMalformed::NameTooShort,
            DeviceInfo::try_from((DeviceType::Phone, false, "abcd".as_bytes())).unwrap_err()
        );
    }

    #[test]
    fn device_info_name_too_long() {
        assert_eq!(
            DeviceInfoMalformed::NameTooLong,
            DeviceInfo::try_from((DeviceType::Phone, false, "0123456789".as_bytes())).unwrap_err()
        );
    }

    #[test]
    fn device_info_ok() {
        let device_info =
            DeviceInfo::try_from((DeviceType::Phone, true, "abcde".as_bytes())).unwrap();
        assert_eq!(device_info.device_type(), DeviceType::Phone);
        assert!(device_info.name_truncated());
        assert_eq!(device_info.device_name(), "abcde".as_bytes());
    }
}
