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

use super::*;
use crate::extended::{
    V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN, V1_ENCODING_UNENCRYPTED,
};
use crate::{
    credential::v1::V1BroadcastCredential,
    extended::{
        data_elements::GenericDataElement,
        deserialize::SectionMic,
        salt::{ExtendedV1Salt, SaltConvertible, Unsalted, V1Salt},
        serialize::AddSectionError::MaxSectionCountExceeded,
        V1IdentityToken, V1_IDENTITY_TOKEN_LEN,
    },
};
use crypto_provider::{
    aes::ctr::{AesCtr, NonceAndCounter},
    hmac::Hmac,
    CryptoProvider, CryptoRng,
};
use crypto_provider_default::CryptoProviderImpl;
use np_hkdf::v1_salt::{OptionDeType, EXTENDED_SALT_LEN};
use np_hkdf::DerivedSectionKeys;
use rand::{rngs::StdRng, Rng as _, SeedableRng as _};
use std::{prelude::rust_2021::*, vec};

#[test]
fn unencrypted_section_empty() {
    let mut adv_builder = AdvBuilder::new();
    let section_builder = adv_builder.section_builder(UnencryptedSectionEncoder).unwrap();

    assert_eq!(
        &[V1_ENCODING_UNENCRYPTED.0, 0_u8],
        section_builder.into_section::<CryptoProviderImpl>().as_slice()
    );
}

#[test]
fn mic_encrypted_identity_section_empty() {
    do_mic_encrypted_identity_fixed_key_material_test::<DummyDataElement>(&[]);
}

#[test]
fn mic_encrypted_identity_section_random_des() {
    let mut rng = StdRng::from_entropy();
    let mut crypto_rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();

    for _ in 0..1_000 {
        let num_des = rng.gen_range(1..=5);

        let mut extra_des = (0..num_des)
            .map(|_| {
                let de_len = rng.gen_range(0..=30);
                DummyDataElement {
                    de_type: rng.gen_range(0_u32..0xFFFFFFFF).try_into().unwrap(),
                    data: rand_ext::random_vec_rc(&mut rng, de_len),
                }
            })
            .collect::<Vec<_>>();

        extra_des.sort_by_key(|de| de.de_type);
        extra_des.dedup_by_key(|de| de.de_type);
        let extra_des = extra_des;

        let identity_token: V1IdentityToken = crypto_rng.gen();
        let key_seed = rng.gen();
        let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);

        let mut adv_builder = AdvBuilder::new();

        let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

        let mut section_builder =
            adv_builder
                .section_builder(MicEncryptedSectionEncoder::<_>::new_random_salt::<
                    CryptoProviderImpl,
                >(&mut crypto_rng, &broadcast_cred))
                .unwrap();
        let section_salt = section_builder.section_encoder.salt;

        for de in extra_des.iter() {
            section_builder.add_de(de).unwrap();
        }

        let section_length = mic_section_len(section_salt, &extra_des);

        let mut hmac = key_seed_hkdf
            .v1_mic_extended_salt_keys()
            .mic_hmac_key()
            .build_hmac::<CryptoProviderImpl>();
        let nonce = section_salt.compute_nonce::<CryptoProviderImpl>();

        let mut cipher = <CryptoProviderImpl as CryptoProvider>::AesCtr128::new(
            &key_seed_hkdf.v1_mic_extended_salt_keys().aes_key(),
            NonceAndCounter::from_nonce(nonce),
        );

        // encrypt identity token and de contents
        let mut plaintext_identity_token = identity_token.as_slice().to_vec();
        cipher.apply_keystream(&mut plaintext_identity_token);
        let ct_identity_token = plaintext_identity_token;

        let mut de_contents = Vec::new();
        for de in extra_des {
            let _ = de.write_de(Unsalted, &mut de_contents);
        }
        cipher.apply_keystream(&mut de_contents);

        // just to be sure, we'll construct our test hmac all in one update() call
        let mut hmac_input = vec![];
        hmac_input.push(VERSION_HEADER_V1);
        hmac_input.push(V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN.0);
        hmac_input.extend_from_slice(section_salt.as_slice());
        hmac_input.extend_from_slice(nonce.as_slice());
        hmac_input.extend_from_slice(&ct_identity_token);
        hmac_input.push(section_length);
        hmac_input.extend_from_slice(&de_contents);
        hmac.update(&hmac_input);
        let mic = hmac.finalize();

        let mut expected = vec![];
        expected.push(V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN.0);
        expected.extend_from_slice(section_salt.as_slice());
        expected.extend_from_slice(&ct_identity_token);
        expected.push(section_length);
        expected.extend_from_slice(&de_contents);
        expected.extend_from_slice(&mic[..16]);

        assert_eq!(&expected, section_builder.into_section::<CryptoProviderImpl>().as_slice());
    }
}

#[test]
fn section_builder_too_full_doesnt_advance_de_index() {
    let mut crypto_rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();

    let mut adv_builder = AdvBuilder::new();

    let key_seed = [22; 32];
    let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);
    let identity_token = V1IdentityToken([33; 16]);

    let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

    let mut section_builder = adv_builder
        .section_builder(MicEncryptedSectionEncoder::<_>::new_random_salt::<CryptoProviderImpl>(
            &mut crypto_rng,
            &broadcast_cred,
        ))
        .unwrap();
    let salt = section_builder.section_encoder.salt;

    section_builder
        .add_de(&DummyDataElement {
            de_type: 100_u8.into(),
            data: salt
                .derive::<100, CryptoProviderImpl>(DeType::from(100_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();

    // this write won't advance the internal section buffer length
    assert_eq!(
        AddDataElementError::InsufficientSpace,
        section_builder
            .add_de(&DummyDataElement {
                de_type: 101_u8.into(),
                data: salt
                    .derive::<100, CryptoProviderImpl>(DeType::from(101_u8).into())
                    .unwrap()
                    .to_vec(),
            })
            .unwrap_err()
    );

    section_builder
        .add_de(&DummyDataElement {
            de_type: 102_u8.into(),
            data: salt
                .derive::<10, CryptoProviderImpl>(DeType::from(102_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();

    section_builder.add_to_advertisement::<CryptoProviderImpl>();

    let mut expected = vec![];
    // identity token
    expected.extend_from_slice(&identity_token.0);
    // section len
    expected.push(2 + 100 + 2 + 10u8 + u8::try_from(SectionMic::CONTENTS_LEN).unwrap());
    // de header
    expected.extend_from_slice(&[0x80 + 100, 100]);
    // section 0 de 0
    expected.extend_from_slice(
        &salt.derive::<100, CryptoProviderImpl>(DeType::from(100_u8).into()).unwrap(),
    );
    // de header
    expected.extend_from_slice(&[0x80 + 10, 102]);
    // section 0 de 1
    expected.extend_from_slice(
        &salt.derive::<10, CryptoProviderImpl>(DeType::from(102_u8).into()).unwrap(),
    );

    let mut cipher = <CryptoProviderImpl as CryptoProvider>::AesCtr128::new(
        &key_seed_hkdf.v1_mic_extended_salt_keys().aes_key(),
        NonceAndCounter::from_nonce(salt.compute_nonce::<CryptoProviderImpl>()),
    );

    cipher.apply_keystream(&mut expected[..V1_IDENTITY_TOKEN_LEN]);
    cipher.apply_keystream(&mut expected[V1_IDENTITY_TOKEN_LEN + 1..]);

    let adv_bytes = adv_builder.into_advertisement();
    // ignoring the MIC, etc, since that's tested elsewhere
    let ciphertext_end = adv_bytes.as_slice().len() - SectionMic::CONTENTS_LEN;
    assert_eq!(&expected, &adv_bytes.as_slice()[1 + 1 + EXTENDED_SALT_LEN..ciphertext_end])
}

#[test]
fn section_de_fits_exactly() {
    // leave room for initial filler section's header and the identities
    // for section_contents_capacity in 1..NP_ADV_MAX_SECTION_LEN - 3 {
    let mut adv_builder = AdvBuilder::new();

    // fill up space to produce desired capacity
    let mut section_builder = adv_builder.section_builder(UnencryptedSectionEncoder).unwrap();
    // leave space for adv header, 1 section len, 1 section header and 1 extra byte
    fill_section_builder(BLE_5_ADV_SVC_MAX_CONTENT_LEN - 1 - 1 - 1 - 1, &mut section_builder)
        .unwrap();

    // can't add a 2 byte DE
    let two_byte_de = GenericDataElement::try_from(1_u8.into(), &[0xFF]).unwrap();
    assert_eq!(
        Err(AddDataElementError::InsufficientSpace),
        section_builder.add_de(&two_byte_de),
        "capacity: "
    );

    // can add a 1 byte DE
    let one_byte_de = GenericDataElement::try_from(1_u8.into(), &[]).unwrap();
    section_builder.add_de(&one_byte_de).unwrap();
}

#[test]
fn section_builder_build_de_error_doesnt_advance_de_index() {
    let mut crypto_rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();

    let mut adv_builder = AdvBuilder::new();

    let key_seed = [22; 32];
    let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);
    let identity_token = V1IdentityToken([33; 16]);

    let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

    let mut section_builder = adv_builder
        .section_builder(MicEncryptedSectionEncoder::<_>::new_random_salt::<CryptoProviderImpl>(
            &mut crypto_rng,
            &broadcast_cred,
        ))
        .unwrap();
    let salt = section_builder.section_encoder.salt;

    section_builder
        .add_de(&DummyDataElement {
            de_type: 100_u8.into(),
            data: salt
                .derive::<100, CryptoProviderImpl>(DeType::from(100_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();

    section_builder
        .add_de(&DummyDataElement {
            de_type: 103_u8.into(),
            data: salt
                .derive::<10, CryptoProviderImpl>(DeType::from(103_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();

    section_builder.add_to_advertisement::<CryptoProviderImpl>();

    let mut expected = vec![];
    // identity_token
    expected.extend_from_slice(identity_token.as_slice());
    //section len
    expected.push(2 + 100 + 2 + 10 + u8::try_from(SectionMic::CONTENTS_LEN).unwrap());
    // de header
    expected.extend_from_slice(&[0x80 + 100, 100]);
    // section 0 de 0
    expected.extend_from_slice(
        &salt.derive::<100, CryptoProviderImpl>(DeType::from(100u8).into()).unwrap(),
    );
    // de header
    expected.extend_from_slice(&[0x80 + 10, 103]);
    // section 0 de 1
    expected.extend_from_slice(
        &salt.derive::<10, CryptoProviderImpl>(DeType::from(103u8).into()).unwrap(),
    );

    let mut cipher = <CryptoProviderImpl as CryptoProvider>::AesCtr128::new(
        &key_seed_hkdf.v1_mic_extended_salt_keys().aes_key(),
        NonceAndCounter::from_nonce(salt.compute_nonce::<CryptoProviderImpl>()),
    );

    cipher.apply_keystream(&mut expected[..V1_IDENTITY_TOKEN_LEN]);
    cipher.apply_keystream(&mut expected[V1_IDENTITY_TOKEN_LEN + 1..]);

    let adv_bytes = adv_builder.into_advertisement();
    // ignoring the MIC, etc, since that's tested elsewhere
    let ciphertext_end = adv_bytes.as_slice().len() - SectionMic::CONTENTS_LEN;
    assert_eq!(&expected, &adv_bytes.as_slice()[1 + 1 + EXTENDED_SALT_LEN..ciphertext_end])
}

#[test]
fn add_multiple_de_correct_de_offsets_mic_encrypted_identity() {
    let mut crypto_rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();

    let mut adv_builder = AdvBuilder::new();

    let key_seed = [22; 32];
    let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);
    let identity_token = V1IdentityToken([33; 16]);

    let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

    let mut section_builder = adv_builder
        .section_builder(MicEncryptedSectionEncoder::<_>::new_random_salt::<CryptoProviderImpl>(
            &mut crypto_rng,
            &broadcast_cred,
        ))
        .unwrap();
    let salt = section_builder.section_encoder.salt;

    section_builder
        .add_de(&DummyDataElement {
            de_type: 64_u8.into(),
            data: salt
                .derive::<16, CryptoProviderImpl>(DeType::from(64_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();
    section_builder
        .add_de(&DummyDataElement {
            de_type: 65_u8.into(),
            data: salt
                .derive::<16, CryptoProviderImpl>(DeType::from(65_u8).into())
                .unwrap()
                .to_vec(),
        })
        .unwrap();

    section_builder.add_to_advertisement::<CryptoProviderImpl>();

    let mut expected = vec![];
    // identity_token
    expected.extend_from_slice(identity_token.as_slice());
    // section len, 2 des of 18 bytes each + section mic length
    expected.push((18 * 2u8) + u8::try_from(SectionMic::CONTENTS_LEN).unwrap());
    // de header
    expected.extend_from_slice(&[0x90, 0x40]);
    // section 0 de 0
    expected.extend_from_slice(
        &salt.derive::<16, CryptoProviderImpl>(DeType::from(64u8).into()).unwrap(),
    );
    // de header
    expected.extend_from_slice(&[0x90, 0x41]);
    // section 0 de 1
    expected.extend_from_slice(
        &salt.derive::<16, CryptoProviderImpl>(DeType::from(65u8).into()).unwrap(),
    );

    let mut cipher = <CryptoProviderImpl as CryptoProvider>::AesCtr128::new(
        &key_seed_hkdf.v1_mic_extended_salt_keys().aes_key(),
        NonceAndCounter::from_nonce(
            salt.derive::<12, CryptoProviderImpl>(OptionDeType::NONE).unwrap(),
        ),
    );

    cipher.apply_keystream(&mut expected[..V1_IDENTITY_TOKEN_LEN]);
    cipher.apply_keystream(&mut expected[V1_IDENTITY_TOKEN_LEN + 1..]);

    let adv_bytes = adv_builder.into_advertisement();
    // ignoring the MIC, etc, since that's tested elsewhere
    let ciphertext_end = adv_bytes.as_slice().len() - 16;
    assert_eq!(&expected, &adv_bytes.as_slice()[1 + 1 + 16..ciphertext_end])
}

#[test]
fn serialize_max_number_of_public_sections() {
    let mut adv_builder = AdvBuilder::new();
    for _ in 0..NP_V1_ADV_MAX_SECTION_COUNT {
        let mut section_builder = adv_builder.section_builder(UnencryptedSectionEncoder).unwrap();
        section_builder
            .add_de(&DummyDataElement { de_type: 100_u8.into(), data: vec![0; 27] })
            .unwrap();
        section_builder.add_to_advertisement::<CryptoProviderImpl>();
    }
    assert_eq!(
        adv_builder.section_builder(UnencryptedSectionEncoder).unwrap_err(),
        MaxSectionCountExceeded
    );
}

fn do_mic_encrypted_identity_fixed_key_material_test<W: WriteDataElement>(extra_des: &[W])
where
    <W as WriteDataElement>::Salt: SaltConvertible<DeSalt>,
{
    let identity_token = V1IdentityToken([1; 16]);
    let key_seed = [2; 32];
    let adv_header_byte = 0b00100000;
    let section_salt: ExtendedV1Salt = [3; 16].into();
    let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);

    let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

    let mut adv_builder = AdvBuilder::new();
    let mut section_builder = adv_builder
        .section_builder(MicEncryptedSectionEncoder::<_>::new::<CryptoProviderImpl>(
            section_salt,
            &broadcast_cred,
        ))
        .unwrap();
    for de in extra_des {
        section_builder.add_de(&RefWriteDataElement(de)).unwrap();
    }

    // now construct expected bytes
    // mic length + length of des
    let section_length = mic_section_len(section_salt, extra_des);

    let mut hmac =
        key_seed_hkdf.v1_mic_extended_salt_keys().mic_hmac_key().build_hmac::<CryptoProviderImpl>();
    let nonce = section_salt.compute_nonce::<CryptoProviderImpl>();

    let mut cipher = <CryptoProviderImpl as CryptoProvider>::AesCtr128::new(
        &key_seed_hkdf.v1_mic_extended_salt_keys().aes_key(),
        NonceAndCounter::from_nonce(nonce),
    );

    // encrypt identity token and de contents
    let mut plaintext_identity_token = identity_token.as_slice().to_vec();
    cipher.apply_keystream(&mut plaintext_identity_token);
    let ct_identity_token = plaintext_identity_token;

    let mut de_contents = Vec::new();
    for de in extra_des {
        let de_type = de.de_type();
        let de_salt = DeSalt { salt: section_salt, de_type };
        let salt = <<W as WriteDataElement>::Salt as SaltConvertible<DeSalt>>::convert(de_salt);
        let _ = de.write_de(salt, &mut de_contents);
    }
    cipher.apply_keystream(&mut de_contents);

    // just to be sure, we'll construct our test hmac all in one update() call
    let mut hmac_input = vec![];
    hmac_input.push(adv_header_byte);
    hmac_input.push(V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN.0);
    hmac_input.extend_from_slice(section_salt.as_slice());
    hmac_input.extend_from_slice(nonce.as_slice());
    hmac_input.extend_from_slice(&ct_identity_token);
    hmac_input.push(section_length);
    hmac_input.extend_from_slice(&de_contents);
    hmac.update(&hmac_input);
    let mic = hmac.finalize();

    let mut expected = vec![];
    expected.push(V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN.0);
    expected.extend_from_slice(section_salt.as_slice());
    expected.extend_from_slice(&ct_identity_token);
    expected.push(section_length);
    expected.extend_from_slice(&de_contents);
    expected.extend_from_slice(&mic[..16]);

    assert_eq!(&expected, section_builder.into_section::<CryptoProviderImpl>().as_slice());
}

/// Returns the length of a mic section containing `extra_des`
fn mic_section_len<W: WriteDataElement>(section_salt: ExtendedV1Salt, extra_des: &[W]) -> u8
where
    <W as WriteDataElement>::Salt: SaltConvertible<DeSalt>,
{
    u8::try_from(SectionMic::CONTENTS_LEN).unwrap()
        + extra_des
            .iter()
            .map(|de| {
                let de_type = de.de_type();
                let de_salt = DeSalt { salt: section_salt, de_type };
                let salt =
                    <<W as WriteDataElement>::Salt as SaltConvertible<DeSalt>>::convert(de_salt);
                let mut de_bytes = Vec::new();
                let _ = de.write_de(salt, &mut de_bytes);
                de_bytes.len() as u8
            })
            .sum::<u8>()
}

/// Write `section_contents_len` bytes of DE into `section_builder`
pub(crate) fn fill_section_builder<I: SectionEncoder>(
    section_contents_len: usize,
    section_builder: &mut SectionBuilder<&mut AdvBuilder, I>,
) -> Result<(), AddDataElementError> {
    let original_len = section_builder.section.len();
    // DEs can only go up to 127, so we'll need multiple for long sections
    for i in 0..(section_contents_len / 100) {
        let de_contents = vec![0x33; 98];
        let generic_de = GenericDataElement::try_from((100_u8 + (i as u8)).into(), &de_contents)
            .expect("98 is a valid DE contents length");
        section_builder.add_de(&generic_de)?;
    }

    let remainder_len = section_contents_len % 100;
    match remainder_len {
        0 => {
            // leave remainder empty
        }
        1 => {
            // 1 byte header
            let generic_de = GenericDataElement::try_from(3_u8.into(), &[])
                .expect("0 is a valid DE contents length");
            section_builder.add_de(&generic_de)?;
        }
        2 => {
            // 2 byte header
            let generic_de = GenericDataElement::try_from(98_u8.into(), &[])
                .expect("0 is a valid DE contents length");
            section_builder.add_de(&generic_de)?;
        }
        _ => {
            // 2 byte header + contents as needed
            // leave room for section length, section header, and DE headers
            let de_contents = vec![0x44; remainder_len - 2];
            let generic_de = GenericDataElement::try_from(99_u8.into(), &de_contents)
                .expect("DEs with content length less than 100 should be valid DEs");
            section_builder.add_de(&generic_de)?;
        }
    }

    assert_eq!(section_contents_len, section_builder.section.len() - original_len);

    Ok(())
}

#[derive(Clone)]
pub(crate) struct DummyDataElement {
    pub(crate) de_type: DeType,
    pub(crate) data: Vec<u8>,
}

impl ProvidesDEType for DummyDataElement {
    fn de_type(&self) -> DeType {
        self.de_type
    }
}

impl ProvidesDEType for &DummyDataElement {
    fn de_type(&self) -> DeType {
        self.de_type
    }
}

impl WriteDataElement for DummyDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, _salt: Unsalted, sink: &mut S) -> Option<()> {
        sink.try_extend_from_slice(&self.data)
    }
}

impl WriteDataElement for &DummyDataElement {
    type Salt = Unsalted;
    fn write_de_contents<S: Sink<u8>>(&self, salt: Unsalted, sink: &mut S) -> Option<()> {
        (*self).write_de_contents(salt, sink)
    }
}

pub(crate) trait SectionBuilderExt {
    fn into_section<C: CryptoProvider>(self) -> EncodedSection;
}

impl<R: AsMut<AdvBuilder>, I: SectionEncoder> SectionBuilderExt for SectionBuilder<R, I> {
    /// Convenience method for tests
    fn into_section<C: CryptoProvider>(self) -> EncodedSection {
        Self::build_section::<C>(Self::finished_adding_des(self.section), self.section_encoder)
    }
}
