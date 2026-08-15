// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use super::*;
use crate::deserialization_arena;
use rand::SeedableRng;

#[test]
fn v1_plaintext_empty_contents() {
    let mut adv_builder = AdvBuilder::new();
    let sb = adv_builder.section_builder(UnencryptedSectionEncoder).unwrap();
    sb.add_to_advertisement::<CryptoProviderImpl>();
    let adv = adv_builder.into_advertisement();

    let arena = deserialization_arena!();
    let cred_book = build_empty_cred_book();
    let v1_error = deser_v1_error::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(
        v1_error,
        AdvDeserializationError::ParseError {
            details_hazmat: AdvDeserializationErrorDetailsHazmat::AdvertisementDeserializeError
        }
    );
}

#[test]
fn v1_adv_no_sections() {
    let mut rng = StdRng::from_entropy();
    let identities = TestIdentities::generate::<5, _>(&mut rng);
    let adv_builder = AdvBuilder::new();
    let adv = adv_builder.into_advertisement();

    let arena = deserialization_arena!();
    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();

    let v1_error = deser_v1_error::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(
        v1_error,
        AdvDeserializationError::ParseError {
            details_hazmat: AdvDeserializationErrorDetailsHazmat::AdvertisementDeserializeError
        }
    );
}

#[test]
fn v1_no_creds_available_ciphertext() {
    let mut rng = StdRng::from_entropy();
    for _ in 0..100 {
        let identities = TestIdentities::generate::<100, _>(&mut rng);
        let mut adv_builder = AdvBuilder::new();
        let section_configs = fill_with_encrypted_sections::<_, CryptoProviderImpl>(
            &mut rng,
            &identities,
            &mut adv_builder,
        );
        let adv = adv_builder.into_advertisement();

        let cred_book = build_empty_cred_book();
        let arena = deserialization_arena!();
        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
        assert_eq!(section_configs.len(), v1_contents.invalid_sections_count());
        assert_eq!(0, v1_contents.sections().len());
    }
}

#[test]
fn v1_only_non_matching_identities_available_ciphertext() {
    let mut rng = StdRng::from_entropy();
    for _ in 0..100 {
        let mut identities = TestIdentities::generate::<100, _>(&mut rng);
        let mut adv_builder = AdvBuilder::new();
        let cloned_identities = identities.clone();
        let section_configs = fill_with_encrypted_sections::<_, CryptoProviderImpl>(
            &mut rng,
            &cloned_identities,
            &mut adv_builder,
        );
        let adv = adv_builder.into_advertisement();

        // only retain identities in the book that haven't been used in the constructed adv sections
        identities.0.retain(|identity| {
            !section_configs.iter().any(|sc| match &sc.identity_kind {
                IdentityKind::Plaintext => panic!("There are no plaintext sections"),
                IdentityKind::Encrypted { identity: section_identity, verification_mode: _ } => {
                    identity.key_seed == section_identity.key_seed
                }
            })
        });

        let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
        let arena = deserialization_arena!();
        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
        assert_eq!(section_configs.len(), v1_contents.invalid_sections_count());
        assert_eq!(0, v1_contents.sections().len());
    }
}

#[test]
fn v1_encrypted_then_plaintext_sections() {
    let mut rng = StdRng::from_entropy();
    let mut builder = AdvBuilder::new();
    let identities = TestIdentities::generate::<1, _>(&mut rng);

    let _ = add_mic_rand_length_salt_to_adv::<_, CryptoProviderImpl, 0>(
        &mut rng,
        &identities.0[0],
        &mut builder,
    )
    .unwrap();
    let mut adv = builder.into_advertisement().as_slice().to_vec();

    // Append plaintext section
    adv.push(V1_ENCODING_UNENCRYPTED.byte_value()); //Header
    adv.push(3); // section len
    adv.extend_from_slice(&[0xFF; 3]); //section contents

    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
    let arena = deserialization_arena!();

    let v1_error = deser_v1_error::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(
        v1_error,
        AdvDeserializationError::ParseError {
            details_hazmat: AdvDeserializationErrorDetailsHazmat::AdvertisementDeserializeError
        }
    );
}

#[test]
fn v1_mic_encrypted_out_of_order() {
    let mut rng = StdRng::from_entropy();
    let identities = TestIdentities::generate::<1, _>(&mut rng);
    let identity = &identities.0[0];

    // First, build an advertisement with a MIC section with an extended salt
    // which contains no DEs.
    let mut builder = AdvBuilder::new();
    let _ = add_mic_rand_salt_to_adv::<_, CryptoProviderImpl, 0>(
        &mut rng,
        identity,
        &mut builder,
        false,
    )
    .unwrap();
    let mut adv = builder.into_advertisement().as_slice().to_vec();

    // Build another advertisement with a MIC section with a short salt which
    // also contains no DEs.
    let mut builder = AdvBuilder::new();
    let _ = add_mic_rand_salt_to_adv::<_, CryptoProviderImpl, 0>(
        &mut rng,
        identity,
        &mut builder,
        true,
    )
    .unwrap();
    let mut short_mic_section = builder.into_advertisement().as_slice()[1..].to_vec();

    // Stitch 'em together
    adv.append(&mut short_mic_section);
    let adv = adv;

    // Attempt to deserialize
    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
    let arena = deserialization_arena!();

    let v1_error = deser_v1_error::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(
        v1_error,
        AdvDeserializationError::ParseError {
            details_hazmat: AdvDeserializationErrorDetailsHazmat::AdvertisementDeserializeError
        }
    );
}

#[test]
fn v1_encrypted_matching_identity_but_mic_mismatch() {
    for _ in 0..100 {
        v1_deserialize_error_test_tampered_adv(
            add_mic_rand_length_salt_to_adv::<StdRng, CryptoProviderImpl, 0>,
            |adv| {
                // mangle the last 2 bytes of the suffix to invalidate the advertisement.
                // the identity should still correctly match
                let adv_len = adv.len();
                adv[adv_len - 2] = adv[adv_len - 2].wrapping_add(1);
                adv[adv_len - 1] = adv[adv_len - 1].wrapping_sub(1);
            },
        )
    }
}

fn v1_deserialize_error_test_tampered_adv(
    add_to_adv: impl for<'a> Fn(
        &mut StdRng,
        &'a TestIdentity,
        &mut AdvBuilder,
    ) -> Result<SectionConfig<'a>, AddSectionError>,
    mangle_adv: impl Fn(&mut Vec<u8>),
) {
    let mut rng = StdRng::from_entropy();
    let mut builder = AdvBuilder::new();
    let identities = TestIdentities::generate::<1, _>(&mut rng);

    let _ = add_to_adv(&mut rng, &identities.0[0], &mut builder);
    let mut adv = builder.into_advertisement().as_slice().to_vec();

    mangle_adv(&mut adv);
    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
    let arena = deserialization_arena!();
    let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(1, v1_contents.invalid_sections_count());
    assert_eq!(0, v1_contents.sections().len());
}
