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

use crate::legacy::data_elements::actions::tests::set_action;
use crate::legacy::data_elements::device_info::DeviceInfoDataElement;
use crate::legacy::data_elements::psm::PsmDataElement;
use crate::{
    legacy::{
        data_elements::{actions::*, de_type::DataElementType, tx_power::TxPowerDataElement, *},
        deserialize::DeserializedDataElement,
        Ciphertext, PacketFlavor, Plaintext,
    },
    shared_data::{DeviceInfo, DeviceType, TxPower},
};
use alloc::vec;
use rand::prelude::IteratorRandom;
use rand_ext::rand::{distributions, prelude::SliceRandom as _};
use std::prelude::rust_2021::*;
use strum::IntoEnumIterator;

impl distributions::Distribution<ActionsDataElement<Plaintext>> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> ActionsDataElement<Plaintext> {
        let mut available_actions = actions::tests::supported_action_types();
        available_actions.shuffle(rng);

        // choose some of the available actions.
        let selected_actions: &[ActionType] =
            &available_actions[0..rng.gen_range(0..available_actions.len())];

        let mut bits = ActionBits::default();

        for a in selected_actions {
            // generating boolean actions with `true` since we already did our random selection
            // of which actions to use above

            set_action(*a, &mut bits);
        }

        ActionsDataElement::from(bits)
    }
}

impl distributions::Distribution<ActionsDataElement<Ciphertext>> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> ActionsDataElement<Ciphertext> {
        let mut available_actions = actions::tests::supported_action_types();
        available_actions.shuffle(rng);

        // choose some of the available actions
        let selected_actions: &[ActionType] =
            &available_actions[0..rng.gen_range(0..available_actions.len())];

        let mut bits = ActionBits::default();

        for a in selected_actions {
            // generating boolean actions with `true` since we already did our random selection
            // of which actions to use above
            set_action(*a, &mut bits);
        }

        ActionsDataElement::from(bits)
    }
}

impl distributions::Distribution<TxPower> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> TxPower {
        let tx_power: i8 = rng.gen_range(-100..=20);
        TxPower::try_from(tx_power).unwrap()
    }
}

impl distributions::Distribution<TxPowerDataElement> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> TxPowerDataElement {
        let tx_power: TxPower = self.sample(rng);
        TxPowerDataElement::from(tx_power)
    }
}

impl distributions::Distribution<PsmDataElement> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> PsmDataElement {
        let psm: u16 = self.sample(rng);
        PsmDataElement { value: psm }
    }
}

impl distributions::Distribution<DeviceInfoDataElement> for distributions::Standard {
    fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> DeviceInfoDataElement {
        let device_type = DeviceType::iter().choose(rng).unwrap();
        let name_len = rng.gen_range(5..=9);
        let mut device_name = vec![0; name_len];
        rng.fill(&mut device_name[..]);
        let device_info =
            DeviceInfo::try_from((device_type, false, device_name.as_slice())).unwrap();
        DeviceInfoDataElement { device_info }
    }
}

/// Generate a random instance of the requested DE and return it wrapped in [DeserializedDataElement].
pub(crate) fn rand_de<F, D, E, R>(to_enum: E, rng: &mut R) -> DeserializedDataElement<F>
where
    F: PacketFlavor,
    D: SerializeDataElement<F>,
    E: Fn(D) -> DeserializedDataElement<F>,
    R: rand::Rng,
    distributions::Standard: distributions::Distribution<D>,
{
    let de = rng.gen::<D>();
    to_enum(de)
}

/// Generate a random instance of the requested de type, or `None` if that type does not support
/// plaintext.
pub(crate) fn random_de_plaintext<R>(
    de_type: DataElementType,
    rng: &mut R,
) -> DeserializedDataElement<Plaintext>
where
    R: rand::Rng,
{
    match de_type {
        DataElementType::TxPower => rand_de(DeserializedDataElement::TxPower, rng),
        DataElementType::Actions => rand_de(DeserializedDataElement::Actions, rng),
        DataElementType::Psm => rand_de(DeserializedDataElement::Psm, rng),
        DataElementType::DeviceInfo => rand_de(DeserializedDataElement::DeviceInfo, rng),
    }
}

/// Generate a random instance of the requested de type, or `None` if that type does not support
/// ciphertext.
pub(crate) fn random_de_ciphertext<R>(
    de_type: DataElementType,
    rng: &mut R,
) -> DeserializedDataElement<Ciphertext>
where
    R: rand::Rng,
{
    match de_type {
        DataElementType::TxPower => rand_de(DeserializedDataElement::TxPower, rng),
        DataElementType::Actions => rand_de(DeserializedDataElement::Actions, rng),
        DataElementType::Psm => rand_de(DeserializedDataElement::Psm, rng),
        DataElementType::DeviceInfo => rand_de(DeserializedDataElement::DeviceInfo, rng),
    }
}
