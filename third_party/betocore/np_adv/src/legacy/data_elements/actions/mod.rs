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

//! The "Actions" data element and associated types.
//!
//! This DE is somewhat more complex than other DEs. Whether or not it supports a particular flavor
//! depends on the actions set, so it has to be treated as two separate types based on which
//! flavor type parameter is used.
use crate::legacy::data_elements::{DirectMapPredicate, DirectMapper, LengthMapper};
use crate::{
    legacy::{
        data_elements::{
            de_type::{DeActualLength, DeEncodedLength, DeTypeCode},
            DataElementDeserializeError, DataElementSerializationBuffer, DataElementSerializeError,
            DeserializeDataElement, SerializeDataElement,
        },
        PacketFlavor, PacketFlavorEnum,
    },
    private::Sealed,
};

#[cfg(feature = "devtools")]
use core::ops::Range;
use core::{marker, ops};
use nom::{bytes, combinator, error};
use sink::Sink;

#[cfg(test)]
pub(crate) mod tests;

/// Actions DE.
/// Only as many DE payload bytes will be present as needed to represent all set bits that are encoded,
/// with a lower bound of 1 byte in the special case of no set action bits, and an upper bound
/// of 3 bytes occupied by the DE payload.
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct ActionsDataElement<F: PacketFlavor> {
    /// The action bits
    pub action: ActionBits<F>,
}

/// Max length of an actions DE contents
pub(crate) const ACTIONS_MAX_LEN: usize = 4;
/// Range of valid actual lengths
pub(crate) const ACTIONS_VALID_ACTUAL_LEN: ops::RangeInclusive<usize> = 1..=ACTIONS_MAX_LEN;

impl<F> ActionsDataElement<F>
where
    F: PacketFlavor,
{
    /// Generic deserialize, not meant to be called directly -- use [DeserializeDataElement] impls instead.
    #[allow(clippy::assertions_on_constants)]
    fn deserialize(de_contents: &[u8]) -> Result<Self, DataElementDeserializeError> {
        combinator::all_consuming::<&[u8], _, error::Error<&[u8]>, _>(combinator::map(
            bytes::complete::take_while_m_n(0, ACTIONS_MAX_LEN, |_| true),
            |bytes: &[u8]| {
                // pack bits into u32 for convenient access
                debug_assert!(4 >= ACTIONS_MAX_LEN, "Actions must fit in u32");
                let mut action_bytes = [0_u8; 4];
                action_bytes[..bytes.len()].copy_from_slice(bytes);
                u32::from_be_bytes(action_bytes)
            },
        ))(de_contents)
        .map_err(|_| DataElementDeserializeError::DeserializeError { de_type: Self::DE_TYPE_CODE })
        .map(|(_remaining, actions)| actions)
        .map(|action_bits_num| {
            let action = ActionBits::from(action_bits_num);
            Self { action }
        })
    }
}

impl<F: PacketFlavor> From<ActionBits<F>> for ActionsDataElement<F> {
    fn from(action: ActionBits<F>) -> Self {
        Self { action }
    }
}

impl<F: PacketFlavor> Sealed for ActionsDataElement<F> {}

impl<F: PacketFlavor> SerializeDataElement<F> for ActionsDataElement<F> {
    fn de_type_code(&self) -> DeTypeCode {
        ActionsDataElement::<F>::DE_TYPE_CODE
    }

    fn map_actual_len_to_encoded_len(&self, actual_len: DeActualLength) -> DeEncodedLength {
        <Self as DeserializeDataElement>::LengthMapper::map_actual_len_to_encoded_len(actual_len)
    }

    fn serialize_contents(
        &self,
        sink: &mut DataElementSerializationBuffer,
    ) -> Result<(), DataElementSerializeError> {
        let used = self.action.bytes_used();
        sink.try_extend_from_slice(&self.action.bits.to_be_bytes()[..used])
            .ok_or(DataElementSerializeError::InsufficientSpace)
    }
}

impl<E: PacketFlavor> DeserializeDataElement for ActionsDataElement<E> {
    const DE_TYPE_CODE: DeTypeCode = match DeTypeCode::try_from(0b0110) {
        Ok(t) => t,
        Err(_) => unreachable!(),
    };

    type LengthMapper = DirectMapper<ActionsLengthPredicate>;

    fn deserialize<F: PacketFlavor>(
        de_contents: &[u8],
    ) -> Result<Self, DataElementDeserializeError> {
        if E::ENUM_VARIANT == F::ENUM_VARIANT {
            ActionsDataElement::deserialize(de_contents)
        } else {
            Err(DataElementDeserializeError::FlavorNotSupported {
                de_type: Self::DE_TYPE_CODE,
                flavor: F::ENUM_VARIANT,
            })
        }
    }
}

pub(in crate::legacy) struct ActionsLengthPredicate;

impl DirectMapPredicate for ActionsLengthPredicate {
    fn is_valid(len: usize) -> bool {
        ACTIONS_VALID_ACTUAL_LEN.contains(&len)
    }
}

/// Container for the 24 bits defined for "actions" (feature flags and the like).
/// This internally stores a u32, but only the 24 highest bits of this
/// field will actually ever be populated.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct ActionBits<F: PacketFlavor> {
    bits: u32,
    // marker for element type
    flavor: marker::PhantomData<F>,
}

impl<F: PacketFlavor> ActionBits<F> {
    /// Returns the actions bits as a u32. The upper limit of an actions field is 3 bytes,
    /// so the last bytes of this u32 will always be 0
    pub fn as_u32(self) -> u32 {
        self.bits
    }

    /// Return whether a boolean action type is set in this data element, or `None` if the given
    /// action type does not represent a boolean.
    pub fn has_action(&self, action_type: ActionType) -> bool {
        self.bits_for_type(action_type) != 0
    }

    /// Return a list of all ActionType contained within the ActionBits.
    pub fn actions(&self) -> impl Iterator<Item = ActionType> + use<'_, F> {
        (0..ActionType::MAX_ACTION_ID).map(ActionType).filter(|action| self.has_action(*action))
    }
}

impl<F: PacketFlavor> Default for ActionBits<F> {
    fn default() -> Self {
        ActionBits {
            bits: 0, // no bits set
            flavor: marker::PhantomData,
        }
    }
}

/// At least one action doesn't support the required flavor
#[derive(PartialEq, Eq, Debug)]
pub struct FlavorNotSupported {
    flavor: PacketFlavorEnum,
}

impl<F: PacketFlavor> ActionBits<F> {
    /// Tries to create ActionBits from a u32, returning error in the event a specific bit is set for
    /// an unsupported flavor
    pub fn from(value: u32) -> Self {
        Self { bits: value, flavor: marker::PhantomData }
    }

    /// Set the bits for the provided element.
    /// Bits outside the range set by the action will be unaffected.
    pub fn set_action<E: ActionElement>(&mut self, action_element: E) {
        let bits = action_element.bits();

        // validate that the element is not horribly broken
        debug_assert!(action_element.high_bit_index() < 32);
        // must not have bits set past the low `len` bits
        debug_assert_eq!(0, bits >> 1);

        // 0-extend to u32
        let byte_extended = bits as u32;
        // Shift so that the high bit is at the desired index.
        // Won't overflow since length > 0.
        let bits_in_position = byte_extended << (31 - action_element.high_bit_index());

        // We want to effectively clear out the bits already in place, so we don't want to just |=.
        // Instead, we construct a u32 with all 1s above and below the relevant bits and &=, so that
        // if the new bits are 0, the stored bits will be cleared.

        // avoid overflow when index = 0 -- need zero 1 bits to the left in that case
        let left_1s = u32::MAX.checked_shl(32 - action_element.high_bit_index()).unwrap_or(0);
        // avoid underflow when index + len = 32 -- zero 1 bits to the right
        let right_1s = u32::MAX.checked_shr(action_element.high_bit_index() + 1).unwrap_or(0);
        let mask = left_1s | right_1s;
        let bits_for_other_actions = self.bits & mask;
        self.bits = bits_for_other_actions | bits_in_position;
    }

    /// How many bytes (1-3) are needed to represent the set bits, starting from the most
    /// significant bit. The lower bound of 1 is because the unique special case of
    /// an actions field of all zeroes is required by the spec to occupy exactly one byte.
    fn bytes_used(&self) -> usize {
        let bits_used = 32 - self.bits.trailing_zeros();
        let raw_count = (bits_used as usize).div_ceil(8);
        if raw_count == 0 {
            1 // Uncommon case - should only be hit for all-zero action bits
        } else {
            raw_count
        }
    }

    /// Return the bits for a given action type as the low bits in the returned u32.
    ///
    /// For example, when extracting the bits `B` from `0bXXXXXXXXXXBBBBBBXXXXXXXXXXXXXXXX`, the
    /// return value will be `0b00000000000000000000000000BBBBBB`.
    pub fn bits_for_type(&self, action_type: ActionType) -> u32 {
        self.bits << action_type.high_bit_index() >> (31)
    }
}

/// Core trait for an individual action
pub trait ActionElement {
    /// The assigned offset for this type from the high bit in the eventual bit sequence of all
    /// actions.
    fn high_bit_index(&self) -> u32;

    /// Returns the low bit that should be included in the final bit vector
    /// starting at [Self::high_bit_index()].
    fn bits(&self) -> u8;
}

/// Marker trait indicating support for a particular [PacketFlavor].
pub trait ActionElementFlavor<F: PacketFlavor>: ActionElement {}

/// Provides a way to iterate over all action types.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
#[allow(missing_docs)]
pub struct ActionType(pub u8);

impl ActionType {
    const MAX_ACTION_ID: u8 = 31;

    #[cfg(test)]
    /// A u32 with all possible bits for this action type set
    const fn all_bits(&self) -> u32 {
        (u32::MAX << (31_u32)) >> self.high_bit_index()
    }
    /// Get the range of the bits occupied used by this bit index. For example, if the action type
    /// uses the 5th and 6th bits, the returned range will be (5..7).
    /// (0 is the index of the most significant bit).
    #[cfg(feature = "devtools")]
    pub const fn bits_range_for_devtools(&self) -> Range<u32> {
        let high_bit_index = self.high_bit_index();
        high_bit_index..high_bit_index + 1
    }

    const fn high_bit_index(&self) -> u32 {
        self.0 as u32
    }
}

impl ActionElement for ActionType {
    fn high_bit_index(&self) -> u32 {
        self.high_bit_index()
    }

    fn bits(&self) -> u8 {
        true as u8
    }
}
