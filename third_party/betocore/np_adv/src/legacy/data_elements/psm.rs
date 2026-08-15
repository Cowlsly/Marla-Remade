// Copyright 2025 Google LLC
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

//! Data element for PSM.

use crate::legacy::data_elements::de_type::{DeActualLength, DeEncodedLength, DeTypeCode};
use crate::legacy::data_elements::{
    DataElementDeserializeError, DataElementSerializationBuffer, DataElementSerializeError,
    DeserializeDataElement, DirectMapPredicate, DirectMapper, LengthMapper, SerializeDataElement,
};
use crate::legacy::PacketFlavor;
use crate::private::Sealed;
use sink::Sink;

/// Data element holding a PSM.
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct PsmDataElement {
    /// The two bytes PSM value.
    pub value: u16,
}

impl Sealed for crate::legacy::data_elements::psm::PsmDataElement {}

impl<F: PacketFlavor> SerializeDataElement<F>
    for crate::legacy::data_elements::psm::PsmDataElement
{
    fn de_type_code(&self) -> DeTypeCode {
        crate::legacy::data_elements::psm::PsmDataElement::DE_TYPE_CODE
    }

    fn map_actual_len_to_encoded_len(&self, actual_len: DeActualLength) -> DeEncodedLength {
        <Self as DeserializeDataElement>::LengthMapper::map_actual_len_to_encoded_len(actual_len)
    }

    fn serialize_contents(
        &self,
        sink: &mut DataElementSerializationBuffer,
    ) -> Result<(), DataElementSerializeError> {
        sink.try_extend_from_slice(self.value.to_be_bytes().as_slice())
            .ok_or(DataElementSerializeError::InsufficientSpace)
    }
}

impl DeserializeDataElement for crate::legacy::data_elements::psm::PsmDataElement {
    const DE_TYPE_CODE: DeTypeCode = match DeTypeCode::try_from(0b0100) {
        Ok(t) => t,
        Err(_) => unreachable!(),
    };

    type LengthMapper = DirectMapper<PsmLengthPredicate>;

    fn deserialize<F: PacketFlavor>(
        de_contents: &[u8],
    ) -> Result<Self, DataElementDeserializeError> {
        de_contents
            .try_into()
            .ok()
            .map(|arr: [u8; 2]| Self { value: u16::from_be_bytes(arr) })
            .ok_or(DataElementDeserializeError::DeserializeError { de_type: Self::DE_TYPE_CODE })
    }
}

pub(in crate::legacy) struct PsmLengthPredicate;

impl DirectMapPredicate for PsmLengthPredicate {
    /// PSM is 2 bytes value.
    fn is_valid(len: usize) -> bool {
        len == 2
    }
}

#[allow(clippy::unwrap_used)]
#[cfg(test)]
mod tests {
    use crate::legacy::data_elements::de_type::{DeActualLength, DeEncodedLength};
    use crate::legacy::data_elements::psm::PsmDataElement;
    use crate::legacy::data_elements::tests::macros::de_roundtrip_test;
    use crate::legacy::data_elements::{DeserializeDataElement, LengthMapper};
    use crate::legacy::serialize::tests::serialize;
    use crate::legacy::{Ciphertext, Plaintext};
    use crate::DeLengthOutOfRange;
    use std::panic;

    extern crate std;

    #[test]
    fn actual_length_must_be_2() {
        for l in [0, 1, 3] {
            let actual = DeActualLength::try_from(l).unwrap();
            let _ = panic::catch_unwind(|| {
                <PsmDataElement as DeserializeDataElement>::LengthMapper::map_actual_len_to_encoded_len(actual)
            }).unwrap_err();
        }

        assert_eq!(
            2,
            <PsmDataElement as DeserializeDataElement>::LengthMapper::map_actual_len_to_encoded_len(
                DeActualLength::try_from(2).unwrap(),
            )
                .as_u8()
        )
    }

    #[test]
    fn encoded_length_must_be_2() {
        for l in [0, 1, 3] {
            assert_eq!(
                DeLengthOutOfRange,
                <PsmDataElement as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(
                    DeEncodedLength::try_from(l).unwrap()
                )
                    .unwrap_err()
            )
        }

        assert_eq!(
            2,
            <PsmDataElement as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(
                DeEncodedLength::from(2)
            )
                .unwrap()
                .as_u8()
        );
    }

    #[test]
    fn dedup_hint_de_contents_roundtrip_unencrypted() {
        let _ = de_roundtrip_test!(
            PsmDataElement,
            Psm,
            Psm,
            Plaintext,
            serialize::<Plaintext, _>(&PsmDataElement { value: 0x10 })
        );
    }

    #[test]
    fn psm_de_contents_roundtrip_ldt() {
        let _ = de_roundtrip_test!(
            PsmDataElement,
            Psm,
            Psm,
            Ciphertext,
            serialize::<Ciphertext, _>(&PsmDataElement { value: 0x10 })
        );
    }

    mod coverage_gaming {
        use crate::legacy::data_elements::psm::PsmDataElement;
        use alloc::format;

        #[test]
        fn psm_de() {
            let de = PsmDataElement { value: 0x10 };
            // debug
            let _ = format!("{de:?}");
            // trivial accessor
            assert_eq!(0x10, de.value);
        }
    }
}
