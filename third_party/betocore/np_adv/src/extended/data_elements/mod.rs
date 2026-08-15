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

//! V1 data elements.
//!
//! Commonly used DEs have dedicated types (e.g. [TxPowerDataElement], etc), but if another DE is
//! needed, [GenericDataElement] will allow constructing any type of DE.

use crate::{
    extended::{
        de_type::{DeType, HasDEType},
        deserialize::data_element::{
            define_data_element_deserializer, DataElement, DeserializedDataElement,
        },
        salt::{DeSalt, Unsalted},
        serialize::{ProvidesDEType, WriteDataElement},
        MAX_DE_LEN,
    },
    shared_data::*,
};
use array_view::ArrayView;
use core::net;
use sink::Sink;
use strum_macros::FromRepr;

/// Define Actions of Presence Spec.
pub mod actions;
pub use actions::ActionId;
pub use actions::ActionsDataElement;
pub use actions::ActionsDataElementError;
pub use actions::ActionsDeserializationError;
pub use actions::DeserializedActionsDE;

#[cfg(test)]
mod tests;

define_data_element_deserializer! {
    /// Result of deserializing a V1 data element
    /// with one of the Google DE type-codes.
    #[allow(clippy::large_enum_variant)]
    pub enum DeserializedGoogleDE<'adv> borrows {
        /// A Google cast ID
        CastId: CastIdDataElement<'adv>,
        /// A collection of actions
        Actions: DeserializedActionsDE<'adv>,
        /// A media deduplication ID
        MediaDeduplicationId: MediaDeduplicationIdDataElement<'adv>,
    } owns {
        /// Device Info
        DeviceInfo: DeviceInfoDataElement,
        /// A transmission power
        TxPower: TxPowerDataElement,
        /// A context sync sequence number
        ContextSyncSeqNum: ContextSyncSeqNumDataElement,
        /// A deduplication hint.
        DedupHint: DeduplicationHintDataElement,
        /// A device type.
        DeviceType: DeviceTypeDataElement,
        /// Connectivity information.
        ConnectivityInfo: ConnectivityInfoDataElement,
        /// Device capabilities
        Capabilities: CapabilitiesDataElement,
        /// Required capabilities for remote device.
        Requirements: RequirementsDataElement,
    }
}

/// A general purpose data element for use cases that don't fit into an existing DE type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenericDataElement {
    de_type: DeType,
    data: ArrayView<u8, MAX_DE_LEN>,
}

impl GenericDataElement {
    /// Construct a `GenericDataElement` from the provided input.
    ///
    /// `de_type`: the DE type
    /// `data`: the DE contents, length <= 127
    pub fn try_from(de_type: DeType, data: &[u8]) -> Result<Self, GenericDataElementError> {
        ArrayView::try_from_slice(data)
            .ok_or(GenericDataElementError::DataTooLong)
            .map(|data| Self { de_type, data })
    }
}

/// Errors that can occur constructing a [GenericDataElement]
#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum GenericDataElementError {
    /// The DE data is too long
    #[error("DE data is too long")]
    DataTooLong,
}

impl ProvidesDEType for GenericDataElement {
    fn de_type(&self) -> DeType {
        self.de_type
    }
}

impl WriteDataElement for GenericDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_extend_from_slice(self.data.as_slice())
    }
}

/// Convert a deserialized DE into one you can serialize
impl<'adv> From<&'adv DataElement<'adv>> for GenericDataElement {
    fn from(de: &'adv DataElement<'adv>) -> Self {
        Self::try_from(de.de_type(), de.contents())
            .expect("Deserialized DE must have a valid length")
    }
}

/// Advertising power
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TxPowerDataElement {
    tx_power: TxPower,
}

impl From<TxPower> for TxPowerDataElement {
    fn from(tx_power: TxPower) -> Self {
        Self { tx_power }
    }
}

impl From<TxPowerDataElement> for TxPower {
    fn from(de: TxPowerDataElement) -> Self {
        de.tx_power
    }
}

/// Errors raised when attempting to deserialize a Tx power DE.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TxPowerMalformed {
    /// Tx power was out of the valid range `[-100, 20]`.
    OutOfBounds,
    /// There wasn't exactly one byte in the payload, but instead
    /// something else.
    WrongLength,
}

impl HasDEType for TxPowerDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x05);
}

impl DeserializedDataElement<'_> for TxPowerDataElement {
    type DeserializationError = TxPowerMalformed;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &[u8],
    ) -> Result<Self, Self::DeserializationError> {
        let len = contents.len();
        if len == 1 {
            let tx_power =
                TxPower::try_from(contents[0] as i8).map_err(|_| TxPowerMalformed::OutOfBounds)?;
            Ok(tx_power.into())
        } else {
            Err(TxPowerMalformed::WrongLength)
        }
    }
}

impl WriteDataElement for TxPowerDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_push(self.tx_power.as_i8() as u8)
    }
}

/// The length in bytes of a [`DeduplicationHintDataElement`].
pub const DEDUP_HINT_LENGTH: usize = 8;

/// Information to dedupe advertisements from the same device across different mediums.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DeduplicationHintDataElement {
    hint_bytes: [u8; DEDUP_HINT_LENGTH],
}
impl From<[u8; DEDUP_HINT_LENGTH]> for DeduplicationHintDataElement {
    fn from(hint_bytes: [u8; DEDUP_HINT_LENGTH]) -> Self {
        Self { hint_bytes }
    }
}
impl DeduplicationHintDataElement {
    /// Gets the raw bytes of this dedup hint.
    pub fn as_bytes(&self) -> [u8; DEDUP_HINT_LENGTH] {
        self.hint_bytes
    }
}

impl HasDEType for DeduplicationHintDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x12);
}

/// Errors raised when attempting to deserialize a dedup hint DE
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeduplicationHintMalformed {
    /// The size of the payload in bytes didn't match `DEDUP_HINT_LENGTH`.
    WrongLength,
}

impl DeserializedDataElement<'_> for DeduplicationHintDataElement {
    type DeserializationError = DeduplicationHintMalformed;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &[u8],
    ) -> Result<Self, Self::DeserializationError> {
        let hint_bytes: [u8; DEDUP_HINT_LENGTH] =
            contents.try_into().map_err(|_| DeduplicationHintMalformed::WrongLength)?;
        Ok(Self { hint_bytes })
    }
}

impl WriteDataElement for DeduplicationHintDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_extend_from_slice(&self.hint_bytes)
    }
}

/// Context sync sequence number
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ContextSyncSeqNumDataElement {
    num: ContextSyncSeqNum,
}

impl From<ContextSyncSeqNum> for ContextSyncSeqNumDataElement {
    fn from(num: ContextSyncSeqNum) -> Self {
        Self { num }
    }
}

impl From<ContextSyncSeqNumDataElement> for ContextSyncSeqNum {
    fn from(de: ContextSyncSeqNumDataElement) -> Self {
        de.num
    }
}

/// Errors raised when attempting to deserialize a context
/// sync sequence number DE.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ContextSyncSeqNumMalformed {
    /// The sequence number was out of the valid range.
    OutOfBounds,
    /// There wasn't exactly one byte in the payload, but instead
    /// something else.
    WrongLength,
}

impl HasDEType for ContextSyncSeqNumDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x13);
}
impl DeserializedDataElement<'_> for ContextSyncSeqNumDataElement {
    type DeserializationError = ContextSyncSeqNumMalformed;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &[u8],
    ) -> Result<Self, Self::DeserializationError> {
        let len = contents.len();
        if len == 1 {
            let num = ContextSyncSeqNum::try_from(contents[0])
                .map_err(|_| ContextSyncSeqNumMalformed::OutOfBounds)?;
            Ok(num.into())
        } else {
            Err(ContextSyncSeqNumMalformed::WrongLength)
        }
    }
}

impl WriteDataElement for ContextSyncSeqNumDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_push(self.num.as_u8())
    }
}

/// The length of a Google Cast ID in bytes.
pub const CAST_ID_LENGTH: usize = 32;

/// Google Cast ID
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CastIdDataElement<'adv> {
    id: &'adv [u8; CAST_ID_LENGTH],
}
impl<'adv> From<&'adv [u8; CAST_ID_LENGTH]> for CastIdDataElement<'adv> {
    fn from(id: &'adv [u8; CAST_ID_LENGTH]) -> Self {
        Self { id }
    }
}
impl CastIdDataElement<'_> {
    /// Gets the cast ID as raw bytes.
    pub fn get_id_as_bytes(&self) -> &[u8; CAST_ID_LENGTH] {
        self.id
    }
}

/// Potential errors raised when attempting to deserialize
/// a Google Cast ID data element.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CastIdDeserializationError {
    /// The DE payload was of the wrong length.
    WrongLength,
}

impl HasDEType for CastIdDataElement<'_> {
    const DE_TYPE: DeType = DeType::const_from(0x11);
}

impl<'adv> DeserializedDataElement<'adv> for CastIdDataElement<'adv> {
    type DeserializationError = CastIdDeserializationError;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        let id = <&[u8; CAST_ID_LENGTH]>::try_from(contents)
            .map_err(|_| CastIdDeserializationError::WrongLength)?;
        Ok(Self { id })
    }
}

impl WriteDataElement for CastIdDataElement<'_> {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_extend_from_slice(self.id)
    }
}

/// The length of a Media Deduplication ID in bytes.
pub const MEDIA_DEDUPLICATION_ID_LENGTH: usize = 20;

/// Media Deduplication ID
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MediaDeduplicationIdDataElement<'adv> {
    id: &'adv [u8; MEDIA_DEDUPLICATION_ID_LENGTH],
}
impl<'adv> From<&'adv [u8; MEDIA_DEDUPLICATION_ID_LENGTH]>
    for MediaDeduplicationIdDataElement<'adv>
{
    fn from(id: &'adv [u8; MEDIA_DEDUPLICATION_ID_LENGTH]) -> Self {
        Self { id }
    }
}
impl MediaDeduplicationIdDataElement<'_> {
    /// Gets the media deduplication ID as raw bytes.
    pub fn get_id_as_bytes(&self) -> &[u8; MEDIA_DEDUPLICATION_ID_LENGTH] {
        self.id
    }
}

/// Potential errors raised when attempting to deserialize
/// a Media Deduplication ID data element.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaDeduplicationIdDeserializationError {
    /// The DE payload was of the wrong length.
    WrongLength,
}

impl HasDEType for MediaDeduplicationIdDataElement<'_> {
    const DE_TYPE: DeType = DeType::const_from(0x17);
}

impl<'adv> DeserializedDataElement<'adv> for MediaDeduplicationIdDataElement<'adv> {
    type DeserializationError = MediaDeduplicationIdDeserializationError;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        let id = <&[u8; MEDIA_DEDUPLICATION_ID_LENGTH]>::try_from(contents)
            .map_err(|_| MediaDeduplicationIdDeserializationError::WrongLength)?;
        Ok(Self { id })
    }
}

impl WriteDataElement for MediaDeduplicationIdDataElement<'_> {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_extend_from_slice(self.id)
    }
}

/// A description of what kind of device is broadcasting.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, FromRepr)]
#[non_exhaustive]
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

impl TryFrom<DeviceTypeDataElement> for DeviceType {
    type Error = u8;
    fn try_from(de: DeviceTypeDataElement) -> Result<Self, Self::Error> {
        Self::from_repr(de.0).ok_or(de.0)
    }
}

impl From<DeviceType> for DeviceTypeDataElement {
    fn from(device_type: DeviceType) -> Self {
        DeviceTypeDataElement(device_type as u8)
    }
}

/// Data element representing a device type, which
/// may be a recognized [`DeviceType`] or some other
/// byte value which may not be recognized by this
/// iteration of the program.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DeviceTypeDataElement(pub u8);

/// Potential errors raised when attempting to deserialize
/// a device type data element.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceTypeDeserializationError {
    /// The DE payload was of the wrong length.
    WrongLength,
}
impl HasDEType for DeviceTypeDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x16);
}

impl<'adv> DeserializedDataElement<'adv> for DeviceTypeDataElement {
    type DeserializationError = DeviceTypeDeserializationError;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        let value: [u8; 1] =
            contents.try_into().map_err(|_| DeviceTypeDeserializationError::WrongLength)?;
        Ok(Self(value[0]))
    }
}

impl WriteDataElement for DeviceTypeDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_push(self.0)
    }
}

/// Device information of the broadcasting device.
#[derive(Debug)]
pub struct DeviceInfoDataElement {
    /// Data element representing the device information of the broadcasting device,
    /// including the [`DeviceType`], and device name, as well as a boolean flag
    /// representing whether the device name is truncated.
    pub device_info: DeviceInfo,
}

impl From<DeviceInfo> for DeviceInfoDataElement {
    fn from(device_info: DeviceInfo) -> Self {
        Self { device_info }
    }
}

impl From<DeviceInfoDataElement> for DeviceInfo {
    fn from(de: DeviceInfoDataElement) -> Self {
        de.device_info
    }
}

/// Errors raised when attempting to deserialize a device info DE.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceInfoDeserializationError {
    /// The DE payload was of the wrong length.
    WrongLength,
    /// The device type is unknown
    UnknownDeviceType,
}

impl HasDEType for DeviceInfoDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x03);
}

impl<'adv> DeserializedDataElement<'adv> for DeviceInfoDataElement {
    type DeserializationError = DeviceInfoDeserializationError;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        // Contents should be min 5 byte (device name) + 1 byte (device type | name truncated ).
        if contents.len() < crate::shared_data::MIN_DEVICE_NAME_LEN + 1 {
            return Err(DeviceInfoDeserializationError::WrongLength);
        }

        let type_and_trunc = contents[0];
        let device_type = crate::shared_data::DeviceType::from_repr(type_and_trunc & 0b01111111)
            .ok_or(DeviceInfoDeserializationError::UnknownDeviceType)?;
        let name_truncated = (type_and_trunc & 0b10000000) != 0;
        let device_name = &contents[1..];

        let device_info = DeviceInfo::try_from((device_type, name_truncated, device_name))
            .map_err(|_| {
                // This is not ideal, but we don't have a specific error for this.
                // The length is already checked, so this should not happen.
                DeviceInfoDeserializationError::WrongLength
            })?;

        Ok(device_info.into())
    }
}

impl WriteDataElement for DeviceInfoDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        let mut type_and_trunc = self.device_info.device_type() as u8;
        if self.device_info.name_truncated() {
            type_and_trunc |= 0b10000000;
        }
        sink.try_push(type_and_trunc)?;
        sink.try_extend_from_slice(self.device_info.device_name())
    }
}

/// Combined connectivity information about a device.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ConnectivityInfoDataElement {
    /// BLE connectivity information.
    pub ble_info: BleConnectivityInfo,
    /// Wifi-LAN connectivity information.
    pub wifi_lan_info: WifiLanConnectivityInfo,
}

impl ConnectivityInfoDataElement {
    /// Returns true iff this connectivity info data element
    /// has some medium with defined contents.
    ///
    /// This should always be checked prior to serializing
    /// a connectivity info DE into a section to avoid wasting
    /// space on empty connectivity info DEs.
    pub fn is_some(&self) -> bool {
        self.ble_info.is_some() || self.wifi_lan_info.is_some()
    }
}

impl HasDEType for ConnectivityInfoDataElement {
    const DE_TYPE: DeType = DeType::const_from(0x14);
}

impl WriteDataElement for ConnectivityInfoDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        self.ble_info.serialize_if_populated(sink)?;
        self.wifi_lan_info.serialize_if_populated(sink)?;
        Some(())
    }
}

/// Errors which may be raised when attempting to deserialize a [`ConnectivityInfoDataElement`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectivityInfoDeserializationError {
    /// There are no included mediums - why did we even broadcast this?
    NoComponents,
    /// The included mediums are not sorted by their medium type.
    ComponentsOutOfOrder,
    /// There are duplicate mediums.
    DuplicateComponents,
    /// Parsing the component with the given medium type failed.
    ComponentParseFailure(u8),
}

impl<'adv> DeserializedDataElement<'adv> for ConnectivityInfoDataElement {
    type DeserializationError = ConnectivityInfoDeserializationError;
    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        mut contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        if contents.is_empty() {
            return Err(ConnectivityInfoDeserializationError::NoComponents);
        }
        let mut result = ConnectivityInfoDataElement::default();
        let mut maybe_previous_encountered_medium_type: Option<u8> = None;
        while !contents.is_empty() {
            let medium_type = contents[0];
            contents = &contents[1..];

            // Check that medium types are strictly increasing.
            if let Some(previous_encountered_medium_type) = maybe_previous_encountered_medium_type {
                match medium_type.cmp(&previous_encountered_medium_type) {
                    core::cmp::Ordering::Less => {
                        return Err(ConnectivityInfoDeserializationError::ComponentsOutOfOrder);
                    }
                    core::cmp::Ordering::Equal => {
                        return Err(ConnectivityInfoDeserializationError::DuplicateComponents);
                    }
                    core::cmp::Ordering::Greater => {}
                }
            }
            maybe_previous_encountered_medium_type = Some(medium_type);

            // Parse individual medium types, with parser errors delivered
            // if the component for any individual medium is empty (it should not have been
            // serialized in the broadcast section to begin with.)
            let parse_err =
                ConnectivityInfoDeserializationError::ComponentParseFailure(medium_type);
            match medium_type {
                BleConnectivityInfo::MEDIUM_TYPE => {
                    let (remaining, ble_info) =
                        BleConnectivityInfo::parse_from_payload(contents).map_err(|_| parse_err)?;
                    if !ble_info.is_some() {
                        return Err(parse_err);
                    }
                    result.ble_info = ble_info;
                    contents = remaining;
                }
                WifiLanConnectivityInfo::MEDIUM_TYPE => {
                    let (remaining, wifi_lan_info) =
                        WifiLanConnectivityInfo::parse_from_payload(contents)
                            .map_err(|_| parse_err)?;
                    if !wifi_lan_info.is_some() {
                        return Err(parse_err);
                    }
                    result.wifi_lan_info = wifi_lan_info;
                    contents = remaining;
                }
                _ => {
                    // Unknown medium type, bail with success, because
                    // we want deserialization to be forwards-compatible.
                    return Ok(result);
                }
            }
        }
        Ok(result)
    }
}

/// Common trait for fields of `ConnectivityInfoDataElement`.
pub(crate) trait ConnectivityInfoComponent {
    /// The byte-value of the identifying code for the connection medium represented by this component.
    const MEDIUM_TYPE: u8;

    /// Returns the field bitmask for this component indicating populated fields.
    fn field_bitmask(&self) -> u8;
    /// Serializes the contents of this component of `ConnectivityInfoDataElement`
    /// coming after the medium type identifier and field bitmask into a given `Sink`.
    fn serialize_field_values_into_payload<S: Sink<u8>>(&self, sink: &mut S) -> Option<()>;
    /// Parses this component of `ConnectivityInfoDataElement` from a given byte-slice (which
    /// includes the leading bitmask, but not the preceding medium type).
    fn parse_from_payload(payload: &[u8]) -> nom::IResult<&[u8], Self>
    where
        Self: Sized;

    /// Returns true iff this connectivity info component's contents are non-empty.
    fn is_some(&self) -> bool {
        self.field_bitmask() > 0
    }

    /// Serializes this connectivity info component into the given `Sink`
    /// if its contents are non-empty.
    fn serialize_if_populated<S: Sink<u8>>(&self, sink: &mut S) -> Option<()> {
        let field_bitmask = self.field_bitmask();
        if field_bitmask > 0 {
            sink.try_push(Self::MEDIUM_TYPE)?;
            sink.try_push(field_bitmask)?;
            self.serialize_field_values_into_payload(sink)?;
        }
        Some(())
    }
}

/// Maximum length of a gatt service identifier.
pub const MAX_GATT_SERVICE_IDENTIFIER_LEN: usize = 17;

/// Length of a BLE MAC address in bytes.
pub const BLE_MAC_ADDRESS_LEN: usize = 6;

/// Length of a Nearby device token.
pub const DEVICE_TOKEN_LEN: usize = 2;

/// Length of a PSM port identifier.
pub const PSM_LEN: usize = 2;

/// Bitmask for the BLE MAC address.
const BLE_MAC_ADDRESS_BITMASK: u8 = 0b10000000;

/// Bitmask for the GATT service identifier.
const GATT_SERVICE_BITMASK: u8 = 0b01000000;

/// Bitmask for the PSM port.
const PSM_BITMASK: u8 = 0b00100000;

/// Bitmask for the Nearby device token.
const DEVICE_TOKEN_BITMASK: u8 = 0b00010000;

/// Disallowed bits for the BLE connectivity info bitmask.
const BLE_BITMASK_DISALLOWED: u8 =
    !(BLE_MAC_ADDRESS_BITMASK | GATT_SERVICE_BITMASK | PSM_BITMASK | DEVICE_TOKEN_BITMASK);

/// Information about BLE connectivity to a given device.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BleConnectivityInfo {
    /// Bluetooth MAC address
    pub mac_address: Option<[u8; BLE_MAC_ADDRESS_LEN]>,
    /// An identifier for the GATT service to connect to.
    /// This could be a UUID + instance ID (17 bytes),
    /// an attribute handle + instance ID (3 bytes),
    /// or just a UUID (16 bytes) or attribute handle (2 bytes).
    pub gatt_service_identifier: Option<tinyvec::ArrayVec<[u8; MAX_GATT_SERVICE_IDENTIFIER_LEN]>>,
    /// The PSM (L2CAP) port to connect on.
    pub psm: Option<[u8; PSM_LEN]>,
    /// Physical identifier ("device token") for the device.
    pub device_token: Option<[u8; DEVICE_TOKEN_LEN]>,
}

/// Modified version of `nom::bytes::complete::take` which returns an owned array
/// of the exact length which was taken from the input.
fn parse_fixed_length_byte_array<const N: usize>(input: &[u8]) -> nom::IResult<&[u8], [u8; N]> {
    nom::combinator::map(nom::bytes::complete::take(N), |slice: &[u8]| {
        slice.try_into().expect("We took N bytes, but we also didn't?")
    })(input)
}

impl ConnectivityInfoComponent for BleConnectivityInfo {
    const MEDIUM_TYPE: u8 = 0;
    fn field_bitmask(&self) -> u8 {
        let mut result: u8 = 0b00000000;
        if self.mac_address.is_some() {
            result |= BLE_MAC_ADDRESS_BITMASK;
        }
        if self.gatt_service_identifier.is_some() {
            result |= GATT_SERVICE_BITMASK;
        }
        if self.psm.is_some() {
            result |= PSM_BITMASK;
        }
        if self.device_token.is_some() {
            result |= DEVICE_TOKEN_BITMASK;
        }
        result
    }
    fn serialize_field_values_into_payload<S: Sink<u8>>(&self, sink: &mut S) -> Option<()> {
        if let Some(mac_address) = self.mac_address.as_ref() {
            sink.try_extend_from_slice(mac_address)?;
        }
        if let Some(gatt_service_identifier) = self.gatt_service_identifier.as_ref() {
            sink.try_push(gatt_service_identifier.len() as u8)?;
            sink.try_extend_from_slice(gatt_service_identifier.as_slice())?;
        }
        if let Some(psm) = self.psm.as_ref() {
            sink.try_extend_from_slice(psm)?;
        }
        if let Some(device_token) = self.device_token.as_ref() {
            sink.try_extend_from_slice(device_token)?;
        }
        Some(())
    }
    fn parse_from_payload(payload: &[u8]) -> nom::IResult<&[u8], Self>
    where
        Self: Sized,
    {
        let (remaining, bitmask) = nom::combinator::verify(nom::number::complete::u8, |bitmask| {
            (bitmask & BLE_BITMASK_DISALLOWED) == 0u8
        })(payload)?;
        let has_mac = (bitmask & BLE_MAC_ADDRESS_BITMASK) > 0;
        let has_gatt_service_identifier = (bitmask & GATT_SERVICE_BITMASK) > 0;
        let has_psm = (bitmask & PSM_BITMASK) > 0;
        let has_device_token = (bitmask & DEVICE_TOKEN_BITMASK) > 0;
        nom::combinator::map(
            nom::sequence::pair(
                nom::sequence::pair(
                    nom::combinator::cond(
                        has_mac,
                        parse_fixed_length_byte_array::<BLE_MAC_ADDRESS_LEN>,
                    ),
                    nom::combinator::cond(
                        has_gatt_service_identifier,
                        nom::combinator::map_opt(
                            nom::multi::length_data(nom::number::complete::u8),
                            |gatt_service_identifier: &[u8]| {
                                let mut result = tinyvec::ArrayVec::new();
                                if gatt_service_identifier.len() > MAX_GATT_SERVICE_IDENTIFIER_LEN {
                                    None
                                } else {
                                    result.extend_from_slice(gatt_service_identifier);
                                    Some(result)
                                }
                            },
                        ),
                    ),
                ),
                nom::sequence::pair(
                    nom::combinator::cond(has_psm, parse_fixed_length_byte_array::<PSM_LEN>),
                    nom::combinator::cond(
                        has_device_token,
                        parse_fixed_length_byte_array::<DEVICE_TOKEN_LEN>,
                    ),
                ),
            ),
            |((mac_address, gatt_service_identifier), (psm, device_token))| Self {
                mac_address,
                gatt_service_identifier,
                psm,
                device_token,
            },
        )(remaining)
    }
}

/// Length of a wifi BSSID.
pub const WIFI_BSSID_LEN: usize = 6;

/// Bitmask for the IPV4 of a [`WifiLanConnectivityInfo`].
const IPV4_BITMASK: u8 = 0b10000000;
/// Bitmask for the IPV6 of a [`WifiLanConnectivityInfo`].
const IPV6_BITMASK: u8 = 0b01000000;
/// Bitmask for the port of a [`WifiLanConnectivityInfo`].
const PORT_BITMASK: u8 = 0b00100000;
/// Bitmask for the BSSID of a [`WifiLanConnectivityInfo`].
const BSSID_BITMASK: u8 = 0b00010000;

/// Disallowed bits for the wifi connectivity info bitmask.
const WIFI_BITMASK_DISALLOWED: u8 = !(IPV4_BITMASK | IPV6_BITMASK | PORT_BITMASK | BSSID_BITMASK);

/// Information about Wifi/LAN connectivity info to a given device.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WifiLanConnectivityInfo {
    /// The IP address of the device to connect to.
    pub ip: Option<net::IpAddr>,
    /// The port number of the device to connect to.
    pub port: Option<u16>,
    /// The BSSID of the device to connect to.
    pub bssid: Option<[u8; WIFI_BSSID_LEN]>,
}

impl ConnectivityInfoComponent for WifiLanConnectivityInfo {
    const MEDIUM_TYPE: u8 = 1;
    fn field_bitmask(&self) -> u8 {
        let mut result = 0b00000000;
        match self.ip.as_ref() {
            Some(net::IpAddr::V4(_)) => result |= IPV4_BITMASK,
            Some(net::IpAddr::V6(_)) => result |= IPV6_BITMASK,
            None => {}
        }
        if self.port.is_some() {
            result |= PORT_BITMASK
        }
        if self.bssid.is_some() {
            result |= BSSID_BITMASK
        }
        result
    }
    fn serialize_field_values_into_payload<S: Sink<u8>>(&self, sink: &mut S) -> Option<()> {
        if let Some(ip) = self.ip.as_ref() {
            match ip {
                net::IpAddr::V4(ip) => {
                    sink.try_extend_from_slice(&ip.octets())?;
                }
                net::IpAddr::V6(ip) => {
                    sink.try_extend_from_slice(&ip.octets())?;
                }
            }
        }
        if let Some(port) = self.port.as_ref() {
            sink.try_extend_from_slice(&port.to_be_bytes())?;
        }
        if let Some(bssid) = self.bssid.as_ref() {
            sink.try_extend_from_slice(bssid)?;
        }
        Some(())
    }
    fn parse_from_payload(payload: &[u8]) -> nom::IResult<&[u8], Self>
    where
        Self: Sized,
    {
        let (after_bitmask, bitmask) =
            nom::combinator::verify(nom::number::complete::u8, |bitmask| {
                (bitmask & WIFI_BITMASK_DISALLOWED) == 0u8
            })(payload)?;
        let has_ipv4 = (bitmask & IPV4_BITMASK) > 0u8;
        let has_ipv6 = (bitmask & IPV6_BITMASK) > 0u8;
        let has_port = (bitmask & PORT_BITMASK) > 0u8;
        let has_bssid = (bitmask & BSSID_BITMASK) > 0u8;
        let (after_ip, ip) = match (has_ipv4, has_ipv6) {
            (false, false) => (after_bitmask, None),
            (true, false) => nom::combinator::map(parse_fixed_length_byte_array::<4>, |octets| {
                Some(net::Ipv4Addr::from_bits(u32::from_be_bytes(octets)).into())
            })(after_bitmask)?,
            (false, true) => nom::combinator::map(parse_fixed_length_byte_array::<16>, |octets| {
                Some(net::Ipv6Addr::from_bits(u128::from_be_bytes(octets)).into())
            })(after_bitmask)?,
            (true, true) => {
                return Err(nom::Err::Failure(nom::error::Error::new(
                    after_bitmask,
                    nom::error::ErrorKind::Verify,
                )));
            }
        };
        nom::combinator::map(
            nom::sequence::pair(
                nom::combinator::cond(
                    has_port,
                    nom::number::complete::u16(nom::number::Endianness::Big),
                ),
                nom::combinator::cond(has_bssid, parse_fixed_length_byte_array::<WIFI_BSSID_LEN>),
            ),
            move |(port, bssid)| Self { ip, port, bssid },
        )(after_ip)
    }
}

/// A characteristic identifying a capability of a device.
#[derive(Default, Debug, PartialEq, Eq, Clone, Copy)]
#[repr(u8)]
pub enum Capability {
    /// The device has internet connectivity.
    #[default]
    InternetConnectivity = 0,
    /// The device is a cast receiver.
    CastReceiver = 1,
    /// The device is a camera.
    Camera = 2,
    /// The device is a speaker.
    Speaker = 3,
}

/// Trait to represent the kind of capability bitset.
/// This is a "marker" trait, it has no methods, but we can implement it for
/// different types to differentiate them.
pub trait CapabilityBitsetKind {
    /// The DE type code of the associated capability bitset DE.
    const DE_TYPE: DeType;
}

/// Capabilities DE bitset kind.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Capabilities;

impl CapabilityBitsetKind for Capabilities {
    const DE_TYPE: DeType = DeType::const_from(0x18);
}

/// Requirements DE bitset kind.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Requirements;

impl CapabilityBitsetKind for Requirements {
    const DE_TYPE: DeType = DeType::const_from(0x19);
}

/// A generic data element for capabilities and requirements.
/// The T type parameter will be either `Capabilities` or `Requirements`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CapabilityBitsetDataElement<T: CapabilityBitsetKind>(u8, core::marker::PhantomData<T>);

/// Data element representing a set of device capabilities.
pub type CapabilitiesDataElement = CapabilityBitsetDataElement<Capabilities>;
/// Data element representing a set of required device capabilities for a remote device.
pub type RequirementsDataElement = CapabilityBitsetDataElement<Requirements>;

impl<T: CapabilityBitsetKind> Default for CapabilityBitsetDataElement<T> {
    fn default() -> Self {
        Self(0, core::marker::PhantomData)
    }
}

impl<T: CapabilityBitsetKind> CapabilityBitsetDataElement<T> {
    /// Creates a new empty capability bitset data element.
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a capability to the bitset.
    pub fn add(&mut self, capability: Capability) {
        self.0 |= 1 << (capability as u8);
    }

    /// Checks if a capability is present in the bitset.
    pub fn has(&self, capability: Capability) -> bool {
        (self.0 >> (capability as u8)) & 1 == 1
    }

    /// Creates a `CapabilityBitSetDataElement<T>` from a list of capabilities.
    pub fn from_capabilities(capabilities: tinyvec::ArrayVec<[Capability; 4]>) -> Self {
        let mut caps = Self::new();
        for &capability in capabilities.iter() {
            caps.add(capability);
        }
        caps
    }
}

impl<T: CapabilityBitsetKind> From<CapabilityBitsetDataElement<T>> for u8 {
    fn from(capabilities: CapabilityBitsetDataElement<T>) -> Self {
        capabilities.0
    }
}

impl<T: CapabilityBitsetKind> TryFrom<u8> for CapabilityBitsetDataElement<T> {
    type Error = CapabilityBitsetDeserializationError;

    fn try_from(byte: u8) -> Result<Self, Self::Error> {
        // Check for reserved bits. The 4 high bits are reserved.
        if byte & 0b1111_0000 != 0 {
            return Err(CapabilityBitsetDeserializationError::ReservedBitsSet);
        }
        Ok(Self(byte, core::marker::PhantomData))
    }
}

impl<T: CapabilityBitsetKind> HasDEType for CapabilityBitsetDataElement<T> {
    const DE_TYPE: DeType = T::DE_TYPE;
}

impl<T: CapabilityBitsetKind> WriteDataElement for CapabilityBitsetDataElement<T> {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Self::Salt, sink: &mut S) -> Option<()> {
        sink.try_push(self.0)
    }
}

impl<'adv, T: CapabilityBitsetKind + 'adv> DeserializedDataElement<'adv>
    for CapabilityBitsetDataElement<T>
{
    type DeserializationError = CapabilityBitsetDeserializationError;

    fn try_deserialize(
        _maybe_salt: Option<&DeSalt>,
        contents: &'adv [u8],
    ) -> Result<Self, Self::DeserializationError> {
        if contents.len() != 1 {
            return Err(CapabilityBitsetDeserializationError::InvalidLength);
        }
        // Eagerly check format
        let byte = contents[0];
        if byte & 0b1111_0000 != 0 {
            return Err(CapabilityBitsetDeserializationError::ReservedBitsSet);
        }
        Ok(Self(byte, core::marker::PhantomData))
    }
}

/// Errors that can occur when deserializing a capability bitset DE.
#[derive(Debug, PartialEq, Eq, Clone, Copy, thiserror::Error)]
pub enum CapabilityBitsetDeserializationError {
    /// The DE payload was not exactly 1 byte.
    #[error("Invalid length")]
    InvalidLength,
    /// Reserved bits in the byte were set.
    #[error("Reserved bits set")]
    ReservedBitsSet,
}
