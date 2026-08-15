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

#![allow(clippy::unwrap_used)]

extern crate std;

use crate::{
    legacy::{
        data_elements::{
            actions::{self, *},
            tests::macros::de_roundtrip_test,
        },
        serialize::encode_de_header,
        serialize::tests::serialize,
        Ciphertext, Plaintext,
    },
    DeLengthOutOfRange,
};
use rand::seq::SliceRandom;
use rand::Rng;
use std::panic;
use std::prelude::rust_2021::*;

pub(crate) const CALL_TRANSFER: ActionType = ActionType(4);
pub(crate) const ACTIVE_UNLOCK: ActionType = ActionType(8);
pub(crate) const NEARBY_SHARE: ActionType = ActionType(9);
pub(crate) const INSTANT_TETHERING: ActionType = ActionType(10);
pub(crate) const PHONE_HUB: ActionType = ActionType(11);

#[test]
fn setting_action_only_changes_that_actions_bits() {
    fn do_test<F: PacketFlavor>(set_ones: impl Fn(ActionType, &mut ActionBits<F>)) {
        for t in supported_action_types() {
            let other_types =
                supported_action_types().into_iter().filter(|t2| *t2 != t).collect::<Vec<_>>();

            let mut actions = ActionBits::<F>::default();
            set_ones(t, &mut actions);

            // only the correct bits are set internally
            assert_eq!(t.all_bits(), actions.as_u32());
            // we can extract those bits
            assert_eq!(t.all_bits() >> (31 - t.high_bit_index()), actions.bits_for_type(t));
            // consider context sync (None) to be "set" for our purposes
            assert!(actions.has_action(t));
            // other types aren't set
            for &t2 in &other_types {
                assert_eq!(0, actions.bits_for_type(t2))
            }
        }
    }

    do_test(|t, bits: &mut ActionBits<Plaintext>| set_action(t, bits));
    do_test(|t, bits: &mut ActionBits<Ciphertext>| set_action(t, bits));
}

#[test]
fn random_combos_of_actions_have_correct_bits_set() {
    fn do_test<F: PacketFlavor>(set_ones: impl Fn(ActionType, &mut ActionBits<F>)) {
        let all_types = supported_action_types();
        let mut rng = rand::thread_rng();

        for _ in 0..1000 {
            let len = rng.gen_range(0..=all_types.len());
            let selected = all_types.choose_multiple(&mut rng, len).copied().collect::<Vec<_>>();
            let not_selected =
                all_types.iter().filter(|t| !selected.contains(t)).copied().collect::<Vec<_>>();

            let mut actions = ActionBits::<F>::default();
            for &t in &selected {
                set_ones(t, &mut actions);
            }

            for &t in &selected {
                assert_ne!(0, actions.bits_for_type(t));
            }
            for &t in &not_selected {
                assert_eq!(0, actions.bits_for_type(t));
            }

            assert_eq!(selected.iter().fold(0, |accum, t| accum | t.all_bits()), actions.bits);
        }
    }

    do_test::<Plaintext>(set_action);
    do_test::<Ciphertext>(set_action);
}

#[test]
fn set_last_bit_works() {
    let mut actions = ActionBits::<Plaintext>::default();

    actions.set_action(LastBit::from(true));
    assert_eq_hex(0x0100, actions.bits);
}

#[test]
fn set_last_bit_doesnt_clobber_others() {
    let mut actions = ActionBits::<Plaintext>::default();

    // set neighboring bits
    actions.bits |= 0x200;

    actions.set_action(LastBit::from(true));
    assert_eq_hex(0x300, actions.bits);
}

#[test]
fn unset_last_bit_works() {
    let mut actions = ActionBits::<Plaintext> {
        // all 1s
        bits: u32::MAX,
        ..Default::default()
    };

    actions.set_action(LastBit::from(false));
    assert_eq_hex(0xFFFFFEFF, actions.bits);
}

#[test]
fn bytes_used_works() {
    let mut actions = ActionBits::<Plaintext>::default();

    // Special-case: All-zeroes should lead to a single byte being used.
    assert_eq!(1, actions.bytes_used());

    actions.set_action(NEARBY_SHARE);
    assert_eq!(2, actions.bytes_used());

    actions.set_action(LastBit::from(true));
    assert_eq!(3, actions.bytes_used());

    actions.set_action(LastBit::from(false));
    assert_eq!(2, actions.bytes_used());
}

#[test]
fn write_de_empty_actions() {
    // The special case of no action bits set should still occupy one byte [of all zeroes].

    assert_eq!(
        &[actions_de_header_byte(1), 0x00],
        serialize(&ActionsDataElement::<Plaintext>::from(ActionBits::default())).as_slice()
    );
}

#[test]
fn write_de_one_action_byte() {
    let mut action = ActionBits::default();
    action.set_action(FirstBit::from(true));

    assert_eq!(
        &[actions_de_header_byte(1), 0b1000_0000],
        serialize(&ActionsDataElement::<Plaintext>::from(action)).as_slice()
    );
}

#[test]
fn write_de_three_action_bytes() {
    let mut action = ActionBits::default();
    action.set_action(LastBit::from(true));

    assert_eq!(
        &[actions_de_header_byte(3), 0, 0, 1],
        serialize(&ActionsDataElement::<Plaintext>::from(action)).as_slice()
    );
}

#[test]
fn write_de_all_plaintext_actions() {
    let mut action = all_plaintext_actions_set();
    action.set_action(LastBit::from(true));

    // byte 0: actions 0 through 7
    // byte 1: action 8 through 15
    // byte 2: actions 16 through 23
    // byte 2: actions 24 through 31
    assert_eq!(
        &[actions_de_header_byte(4), 0xFF, 0xFF, 0xFF, 0xFF],
        serialize(&ActionsDataElement::<Plaintext>::from(action)).as_slice()
    );
}

#[test]
fn write_de_all_encrypted_actions() {
    let mut action = all_ciphertext_actions_set();
    action.set_action(LastBit::from(true));

    // byte 0: actions 0 through 7
    // byte 1: action 8 through 15
    // byte 2: actions 16 through 23
    // byte 2: actions 24 through 31
    assert_eq!(
        &[actions_de_header_byte(4), 0xFF, 0xFF, 0xFF, 0xFF],
        serialize(&ActionsDataElement::<Ciphertext>::from(action)).as_slice()
    );
}

#[test]
fn roundtrip_de_random_action_combos() {
    fn do_test<F>(set_ones: impl Fn(ActionType, &mut ActionBits<F>))
    where
        F: PacketFlavor,
        ActionsDataElement<F>: DeserializeDataElement,
    {
        let all_types = supported_action_types();
        let mut rng = rand::thread_rng();

        for _ in 0..1000 {
            let len = rng.gen_range(0..=all_types.len());
            let selected = all_types.choose_multiple(&mut rng, len).copied().collect::<Vec<_>>();

            let mut actions = ActionBits::<F>::default();
            for &t in &selected {
                set_ones(t, &mut actions);
            }

            let de = ActionsDataElement::<F>::from(actions);
            let serialized = serialize(&de);
            // skip header
            let contents = &serialized.as_slice()[1..];
            let deserialized = ActionsDataElement::<F>::deserialize(contents).unwrap();

            assert_eq!(de.action, deserialized.action);
        }
    }

    do_test::<Plaintext>(set_action);
    do_test::<Ciphertext>(set_action);
}

#[test]
fn action_type_all_bits_masks() {
    assert_eq!(0x08000000, CALL_TRANSFER.all_bits());
    assert_eq!(0x00800000, ACTIVE_UNLOCK.all_bits());
    assert_eq!(0x00400000, NEARBY_SHARE.all_bits());
    assert_eq!(0x00200000, INSTANT_TETHERING.all_bits());
    assert_eq!(0x00100000, PHONE_HUB.all_bits());
}

#[test]
fn actions_de_deser_plaintext_with_ciphertext_error() {
    assert_eq!(
        DataElementDeserializeError::FlavorNotSupported {
            de_type: ActionsDataElement::<Plaintext>::DE_TYPE_CODE,
            flavor: PacketFlavorEnum::Plaintext,
        },
        <ActionsDataElement<Ciphertext> as DeserializeDataElement>::deserialize::<Plaintext>(&[
            0x00
        ])
        .unwrap_err()
    );
}

#[test]
fn actions_de_deser_ciphertext_with_plaintext_error() {
    assert_eq!(
        DataElementDeserializeError::FlavorNotSupported {
            de_type: ActionsDataElement::<Plaintext>::DE_TYPE_CODE,
            flavor: PacketFlavorEnum::Ciphertext,
        },
        <ActionsDataElement<Plaintext> as DeserializeDataElement>::deserialize::<Ciphertext>(&[
            0x00
        ])
        .unwrap_err()
    );
}

#[test]
fn deserialize_content_too_long_error() {
    assert_eq!(
        DataElementDeserializeError::DeserializeError {
            de_type: ActionsDataElement::<Plaintext>::DE_TYPE_CODE
        },
        <ActionsDataElement<Plaintext> as DeserializeDataElement>::deserialize::<Plaintext>(
            &[0x00; 10]
        )
        .unwrap_err()
    );
}

#[test]
fn actions_min_len_unencrypted() {
    let actions = ActionBits::<Plaintext>::default();

    let (_de, ser) = de_roundtrip_test!(
        ActionsDataElement<Plaintext>,
        Actions,
        Actions,
        Plaintext,
        serialize(&actions::ActionsDataElement::from(actions))
    );

    assert_eq!(
        &[
            encode_de_header(
                ActionsDataElement::<Plaintext>::DE_TYPE_CODE,
                DeEncodedLength::from(1),
            ),
            0
        ],
        ser.as_slice()
    );
}

#[test]
fn actions_min_len_ldt() {
    let actions = ActionBits::<Ciphertext>::default();

    let (_de, ser) = de_roundtrip_test!(
        ActionsDataElement<Ciphertext>,
        Actions,
        Actions,
        Ciphertext,
        serialize(&actions::ActionsDataElement::from(actions))
    );

    // header and 1 DE contents byte
    assert_eq!(2, ser.as_slice().len());
}

#[test]
fn actions_de_contents_normal_actions_roundtrip_unencrypted() {
    let actions = all_plaintext_actions_set();

    let _ = de_roundtrip_test!(
        ActionsDataElement<Plaintext>,
        Actions,
        Actions,
        Plaintext,
        serialize(&actions::ActionsDataElement::from(actions))
    );
}

#[test]
fn actions_de_contents_normal_actions_roundtrip_ldt() {
    let actions = all_ciphertext_actions_set();

    let _ = de_roundtrip_test!(
        ActionsDataElement<Ciphertext>,
        Actions,
        Actions,
        Ciphertext,
        serialize(&actions::ActionsDataElement::from(actions))
    );
}

#[test]
fn has_action_plaintext_works() {
    let mut action_bits = ActionBits::<Plaintext>::default();
    action_bits.set_action(NEARBY_SHARE);
    let action_de = ActionsDataElement::from(action_bits);
    assert!(action_de.action.has_action(NEARBY_SHARE));
    assert!(!action_de.action.has_action(ACTIVE_UNLOCK));
    assert!(!action_de.action.has_action(PHONE_HUB));
}

#[test]
fn has_action_encrypted_works() {
    let mut action_bits = ActionBits::<Ciphertext>::default();
    action_bits.set_action(NEARBY_SHARE);
    action_bits.set_action(ACTIVE_UNLOCK);
    let action_de = ActionsDataElement::from(action_bits);
    assert!(action_de.action.has_action(NEARBY_SHARE));
    assert!(action_de.action.has_action(ACTIVE_UNLOCK));
    assert!(!action_de.action.has_action(PHONE_HUB));
}

#[test]
fn actual_length_must_be_in_range() {
    let de = ActionsDataElement::<Plaintext>::from(ActionBits::default());

    for l in [0, ACTIONS_MAX_LEN + 1] {
        let actual = DeActualLength::try_from(l).unwrap();
        let _ = panic::catch_unwind(|| de.map_actual_len_to_encoded_len(actual)).unwrap_err();
    }

    for l in ACTIONS_VALID_ACTUAL_LEN {
        assert_eq!(
            l,
            de.map_actual_len_to_encoded_len(DeActualLength::try_from(l).unwrap()).as_usize()
        )
    }
}

#[test]
fn encoded_length_must_be_in_range() {
    for l in [0, ACTIONS_MAX_LEN + 1] {
        assert_eq!(
            DeLengthOutOfRange,
            <ActionsDataElement<Plaintext> as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(
                DeEncodedLength::try_from(l as u8).unwrap()
            )
                .unwrap_err()
        )
    }

    for l in ACTIONS_VALID_ACTUAL_LEN {
        assert_eq!(
            l,
            <ActionsDataElement<Plaintext> as DeserializeDataElement>::LengthMapper::map_encoded_len_to_actual_len(
                DeEncodedLength::try_from(l as u8).unwrap()
            )
                .unwrap()
                .as_usize()
        );
    }
}

mod coverage_gaming {
    use crate::legacy::data_elements::actions::tests::{
        ACTIVE_UNLOCK, CALL_TRANSFER, INSTANT_TETHERING, NEARBY_SHARE, PHONE_HUB,
    };
    use crate::legacy::data_elements::actions::*;
    use crate::legacy::Plaintext;
    use alloc::format;

    #[test]
    fn actions_de_debug() {
        let actions = ActionsDataElement::<Plaintext>::from(ActionBits::default());
        let _ = format!("{actions:?}");
    }

    #[test]
    fn flavor_not_supported_debug() {
        let _ = format!("{:?}", FlavorNotSupported { flavor: PacketFlavorEnum::Plaintext });
    }

    #[test]
    fn action_type_clone_debug() {
        let _ = format!("{:?}", CALL_TRANSFER.clone());
    }

    #[test]
    fn actions_debug() {
        let _ = format!("{CALL_TRANSFER:?}");
        let _ = format!("{ACTIVE_UNLOCK:?}");
        let _ = format!("{NEARBY_SHARE:?}");
        let _ = format!("{INSTANT_TETHERING:?}");
        let _ = format!("{PHONE_HUB:?}");
    }
}

// Test only action which uses the first bit
#[derive(Debug)]
pub(crate) struct FirstBit {
    enabled: bool,
}

impl From<bool> for FirstBit {
    fn from(value: bool) -> Self {
        FirstBit { enabled: value }
    }
}

impl ActionElement for FirstBit {
    fn bits(&self) -> u8 {
        self.enabled as u8
    }

    fn high_bit_index(&self) -> u32 {
        0
    }
}

// hypothetical action using the last bit
#[derive(Debug)]
pub(crate) struct LastBit {
    enabled: bool,
}

impl From<bool> for LastBit {
    fn from(value: bool) -> Self {
        LastBit { enabled: value }
    }
}

impl ActionElement for LastBit {
    fn bits(&self) -> u8 {
        self.enabled as u8
    }

    fn high_bit_index(&self) -> u32 {
        23
    }
}

// An action that only supports plaintext, to allow testing that error case
pub(in crate::legacy) struct PlaintextOnly {
    enabled: bool,
}

impl From<bool> for PlaintextOnly {
    fn from(value: bool) -> Self {
        Self { enabled: value }
    }
}

impl ActionElement for PlaintextOnly {
    fn bits(&self) -> u8 {
        self.enabled as u8
    }

    fn high_bit_index(&self) -> u32 {
        22
    }
}

fn assert_eq_hex(expected: u32, actual: u32) {
    assert_eq!(expected, actual, "{expected:#010X} != {actual:#010X}");
}

pub(crate) fn all_plaintext_actions_set() -> ActionBits<Plaintext> {
    let mut action = ActionBits::default();
    for a in supported_action_types() {
        action.set_action(a);
    }

    action
}

pub(crate) fn all_ciphertext_actions_set() -> ActionBits<Ciphertext> {
    let mut action = ActionBits::default();
    for a in supported_action_types() {
        action.set_action(a);
    }

    action
}

pub(crate) fn supported_action_types() -> Vec<ActionType> {
    (0..ActionType::MAX_ACTION_ID + 1).map(ActionType).collect()
}

/// Encode a DE header byte with the provided type and actual len, transforming into an encoded
/// len appropriately.
fn actions_de_header_byte(actual_len: u8) -> u8 {
    encode_de_header(
        ActionsDataElement::<Plaintext>::DE_TYPE_CODE,
        DeEncodedLength::try_from(actual_len).unwrap(),
    )
}

pub(crate) fn set_action<T: PacketFlavor>(t: ActionType, bits: &mut ActionBits<T>) {
    bits.set_action(t);
}
