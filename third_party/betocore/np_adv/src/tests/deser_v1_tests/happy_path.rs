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

extern crate std;

use super::*;
use crate::{deserialization_arena, extended::salt::*};
use rand::SeedableRng;
use std::collections::HashSet;

#[test]
fn deserialize_rand_identities_single_section_finds_correct_one_mic_short_salt() {
    deserialize_rand_identities_finds_correct_one(
        |_rng, bc| {
            let salt = ShortV1Salt::from([0x3; 2]);
            MicEncryptedSectionEncoder::<_>::new_with_salt::<CryptoProviderImpl>(salt, bc)
        },
        VerificationMode::Mic,
    )
}

#[test]
fn deserialize_rand_identities_single_section_finds_correct_one_mic_extended_salt() {
    deserialize_rand_identities_finds_correct_one(
        |rng, bc| {
            let salt: ExtendedV1Salt = rng.gen();
            MicEncryptedSectionEncoder::<_>::new_with_salt::<CryptoProviderImpl>(salt, bc)
        },
        VerificationMode::Mic,
    )
}

#[test]
fn v1_plaintext() {
    let mut rng = StdRng::from_entropy();
    for _ in 0..100 {
        let mut adv_builder = AdvBuilder::new();
        let section_config = fill_plaintext_adv(&mut rng, &mut adv_builder);
        let adv = adv_builder.into_advertisement();
        let arena = deserialization_arena!();
        let cred_book =
            CredentialBookBuilder::build_cached_slice_book::<0, 0, CryptoProviderImpl>(&[], &[]);

        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
        assert_eq!(0, v1_contents.invalid_sections_count());
        assert_eq!(1, v1_contents.sections().len());
        let sections = v1_contents.into_sections();
        assert_section_equals(&section_config, &sections[0]);
    }
}

#[test]
fn v1_encodings_forward_extensibility() {
    let mut rng = StdRng::from_entropy();
    for _ in 0..100 {
        let mut adv_builder = AdvBuilder::new();
        add_plaintext_section(&mut rng, &mut adv_builder).unwrap();

        let mut adv = adv_builder.into_advertisement().as_slice().to_vec();
        // Append a section header with a randomized encoding type which
        // is chosen so as not to be recognized.
        let mut encoding_type: u8 = 0;
        while encoding_type == V1_ENCODING_UNENCRYPTED.byte_value()
            || encoding_type == V1_ENCODING_ENCRYPTED_MIC_WITH_SHORT_SALT_AND_TOKEN.byte_value()
            || encoding_type == V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN.byte_value()
        {
            encoding_type = rng.gen_range(0..=15);
        }
        adv.push(encoding_type);
        // Generate random garbage for the section contents. We don't
        // even care if the length matches, since calculation can be encoding-specific.
        let section_contents_len = rng.gen_range(0..=127);
        for _ in 0..section_contents_len {
            adv.push(rng.gen());
        }
        // Deserialize, and ensure that we only get back the plaintext section.
        let arena = deserialization_arena!();
        let cred_book = CredentialBookBuilder::<EmptyMatchedCredential>::build_cached_slice_book::<
            0,
            0,
            CryptoProviderImpl,
        >(&[], &[]);

        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
        assert_eq!(0, v1_contents.invalid_sections_count());

        let sections = v1_contents.into_sections();
        assert_eq!(1, sections.len());

        if let V1DeserializedSection::Decrypted(_) = &sections[0] {
            panic!("Expected a plaintext section");
        }
        // To further ensure it's not just garbage data, ensure that there's only
        // one data element in the section, per what add_plaintext_section does
        assert_eq!(1, sections[0].iter_data_elements().count());
    }
}

#[test]
fn v1_multiple_plaintext_sections() {
    let mut rng = StdRng::from_entropy();
    let mut adv_builder = AdvBuilder::new();
    add_plaintext_section(&mut rng, &mut adv_builder).unwrap();

    // append an extra plaintext section
    let adv = [
        adv_builder.into_advertisement().as_slice(),
        &[
            0x00, // format unencrypted
            0x03, // section len
        ],
        &[0xDD; 3], // 3 bytes of de contents
    ]
    .concat();

    let arena = deserialization_arena!();
    let cred_book = build_empty_cred_book();
    let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, &adv, &cred_book);
    assert_eq!(0, v1_contents.invalid_sections_count());
    assert_eq!(2, v1_contents.sections().len());
}

#[test]
fn v1_plaintext_then_encrypted_sections() {
    let mut rng = StdRng::from_entropy();
    let mut adv_builder = AdvBuilder::new();
    let identities = TestIdentities::generate::<1, _>(&mut rng);

    add_plaintext_section(&mut rng, &mut adv_builder).unwrap();
    let _ = add_mic_rand_length_salt_to_adv::<_, CryptoProviderImpl, 1>(
        &mut rng,
        &identities.0[0],
        &mut adv_builder,
    )
    .unwrap();
    let adv = adv_builder.into_advertisement().as_slice().to_vec();

    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
    let arena = deserialization_arena!();

    let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
    assert_eq!(0, v1_contents.invalid_sections_count());
    assert_eq!(2, v1_contents.sections().len());
}

#[test]
fn v1_all_identities_resolvable_ciphertext() {
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

        let arena = deserialization_arena!();
        let cred_book = identities.build_cred_book::<CryptoProviderImpl>();

        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);
        assert_eq!(0, v1_contents.invalid_sections_count());
        assert_eq!(section_configs.len(), v1_contents.sections().len());
        for section_config in &section_configs {
            let mut has_match: bool = false;
            for section in v1_contents.sections() {
                if test_section_equals(section_config, section).is_ok() {
                    has_match = true;
                    break;
                }
            }
            if !has_match {
                panic!("Section config does not exist in output: {:?}", section_config);
            }
        }
    }
}

#[test]
fn v1_only_some_matching_identities_available_ciphertext() {
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

        // Hopefully one day we can use extract_if instead: https://github.com/rust-lang/rust/issues/43244
        let mut removed = vec![];
        // Remove some of the identities which have been used to build the adv
        identities.0.retain(|identity| {
            let res = !section_configs.iter().any(|sc| match &sc.identity_kind {
                IdentityKind::Plaintext => panic!("There are no plaintext sections"),
                IdentityKind::Encrypted { identity: section_identity, verification_mode: _ } => {
                    // only remove half the identities that were used
                    (identity.key_seed == section_identity.key_seed) && rng.gen()
                }
            });
            if !(res) {
                removed.push(identity.clone());
            }
            res
        });

        let removed_key_seeds: HashSet<[u8; 32]> = removed.iter().map(|i| i.key_seed).collect();

        // Need to account for how many sections were affected since the same identity could be used
        // to encode multiple different sections
        let affected_sections = section_configs
            .iter()
            .filter(|sc| match sc.identity_kind {
                IdentityKind::Plaintext => {
                    panic!("There are no plaintext sections")
                }
                IdentityKind::Encrypted { identity: si, verification_mode: _ } => {
                    removed_key_seeds.contains(&si.key_seed)
                }
            })
            .count();

        let arena = deserialization_arena!();
        let cred_book = identities.build_cred_book::<CryptoProviderImpl>();
        let v1_contents = deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book);

        // Verify that the number of not-successfully-decrypted sections matches
        // the number of sections for which we had no associated credentials to decrypt.
        assert_eq!(affected_sections, v1_contents.invalid_sections_count());
        assert_eq!(section_configs.len() - affected_sections, v1_contents.sections().len());

        for section_config in &section_configs {
            // Verify that each section config corresponds to _some_
            // actual section in the output (but they could have been re-ordered.)
            let IdentityKind::Encrypted { identity, verification_mode: _ } =
                &section_config.identity_kind
            else {
                panic!("There are no plaintext sections");
            };
            if removed_key_seeds.contains(&identity.key_seed) {
                continue;
            }
            let mut has_match: bool = false;
            for section in v1_contents.sections() {
                if test_section_equals(section_config, section).is_ok() {
                    has_match = true;
                    break;
                }
            }
            if !has_match {
                panic!("Section config does not exist in output: {:?}", section_config);
            }
        }
    }
}

#[test]
fn v1_decrypted_mic_short_salt_matches() {
    let mut rng = StdRng::from_entropy();
    let salt = MultiSalt::Short(rng.gen::<[u8; 2]>().into());
    v1_decrypted_adv_salt_matches(
        &mut rng,
        salt,
        add_mic_with_salt_to_adv::<_, CryptoProviderImpl, 0>,
    );
}

#[test]
fn v1_decrypted_mic_extended_salt_matches() {
    let mut rng = StdRng::from_entropy();
    let salt = MultiSalt::Extended(rng.gen::<[u8; 16]>().into());
    v1_decrypted_adv_salt_matches(
        &mut rng,
        salt,
        add_mic_with_salt_to_adv::<_, CryptoProviderImpl, 0>,
    );
}

fn v1_decrypted_adv_salt_matches(
    rng: &mut StdRng,
    salt: MultiSalt,
    add_to_adv: impl for<'a> Fn(
        &mut StdRng,
        &'a TestIdentity,
        &mut AdvBuilder,
        MultiSalt,
    ) -> Result<SectionConfig<'a>, AddSectionError>,
) {
    let identities = TestIdentities::generate::<1, _>(rng);
    let mut adv_builder = AdvBuilder::new();

    let _ = add_to_adv(rng, &identities.0[0], &mut adv_builder, salt);

    let adv = adv_builder.into_advertisement();
    let arena = deserialization_arena!();
    let cred_book = identities.build_cred_book::<CryptoProviderImpl>();

    let sections =
        deser_v1::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book).into_sections();
    let section = &sections[0];
    let decrypted = match section {
        V1DeserializedSection::Plaintext(_) => {
            panic!("section is encrypted")
        }
        V1DeserializedSection::Decrypted(d) => d,
    };
    assert_eq!(salt, *decrypted.contents().salt())
}
