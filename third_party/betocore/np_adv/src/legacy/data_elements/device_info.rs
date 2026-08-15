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

//! Data element for Device Info.

use crate::legacy::data_elements::de_type::{DeActualLength, DeEncodedLength, DeTypeCode};
use crate::legacy::data_elements::{
    DataElementDeserializeError, DataElementSerializationBuffer, DataElementSerializeError,
    DeserializeDataElement, SerializeDataElement,
};
use crate::legacy::PacketFlavor;
use crate::private::Sealed;
use crate::shared_data::{DeviceInfo, DeviceType};
use sink::Sink;

/// Data element holding a [DeviceInfo].
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct DeviceInfoDataElement {
    /// The device info value
    pub device_info: DeviceInfo,
}

impl From<DeviceInfo> for DeviceInfoDataElement {
    fn from(device_info: DeviceInfo) -> Self {
        Self { device_info }
    }
}

impl Sealed for DeviceInfoDataElement {}

impl<F: PacketFlavor> SerializeDataElement<F> for DeviceInfoDataElement {
    fn de_type_code(&self) -> DeTypeCode {
        DeviceInfoDataElement::DE_TYPE_CODE
    }

    fn map_actual_len_to_encoded_len(&self, actual_len: DeActualLength) -> DeEncodedLength {
        // V0 length is just the device name length + 1 for the type/truncation byte.
        <Self as DeserializeDataElement>::LengthMapper::map_actual_len_to_encoded_len(actual_len)
    }

    fn serialize_contents(
        &self,
        sink: &mut DataElementSerializationBuffer,
    ) -> Result<(), DataElementSerializeError> {
        let mut type_and_trunc = self.device_info.device_type() as u8;
        if self.device_info.name_truncated() {
            type_and_trunc |= 0b10000000;
        }
        sink.try_push(type_and_trunc)
            .and_then(|_| sink.try_extend_from_slice(self.device_info.device_name()))
            .ok_or(DataElementSerializeError::InsufficientSpace)
    }
}

impl DeserializeDataElement for DeviceInfoDataElement {
    const DE_TYPE_CODE: DeTypeCode = match DeTypeCode::try_from(0b0011) {
        Ok(t) => t,
        Err(_) => unreachable!(),
    };

    type LengthMapper = DeviceInfoLengthMapper;

    fn deserialize<F: PacketFlavor>(
        de_contents: &[u8],
    ) -> Result<Self, DataElementDeserializeError> {
        if de_contents.len() < 6 {
            return Err(DataElementDeserializeError::DeserializeError {
                de_type: Self::DE_TYPE_CODE,
            });
        }

        let type_and_trunc = de_contents[0];
        let device_type = DeviceType::from_repr(type_and_trunc & 0b01111111)
            .ok_or(DataElementDeserializeError::DeserializeError { de_type: Self::DE_TYPE_CODE })?;
        let name_truncated = (type_and_trunc & 0b10000000) != 0;
        let device_name = &de_contents[1..];

        let device_info = DeviceInfo::try_from((device_type, name_truncated, device_name))
            .map_err(|_| DataElementDeserializeError::DeserializeError {
                de_type: Self::DE_TYPE_CODE,
            })?;

        Ok(device_info.into())
    }
}

pub(in crate::legacy) struct DeviceInfoLengthMapper;

use crate::legacy::data_elements::{DeLengthOutOfRange, LengthMapper};
impl LengthMapper for DeviceInfoLengthMapper {
    fn map_actual_len_to_encoded_len(actual_len: DeActualLength) -> DeEncodedLength {
        DeEncodedLength::try_from(actual_len.as_u8())
            .expect("Broken DE implementation produced invalid length.")
    }

    fn map_encoded_len_to_actual_len(
        encoded_len: DeEncodedLength,
    ) -> Result<DeActualLength, DeLengthOutOfRange> {
        DeActualLength::try_from(encoded_len.as_u8() as usize)
    }
}
