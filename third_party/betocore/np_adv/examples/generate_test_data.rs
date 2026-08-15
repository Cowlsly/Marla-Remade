// Copyright 2024 Google LLC
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

#![allow(dead_code)]
#![allow(missing_docs)]
#![allow(clippy::unwrap_used)]
#![allow(clippy::expect_used)]
#![allow(clippy::indexing_slicing)]
#![allow(clippy::panic)]

use clap::Parser;
use crypto_provider::{CryptoProvider, CryptoRng};
use crypto_provider_default::CryptoProviderImpl;
use np_adv::credential::{
    book::CredentialBookBuilder,
    matched::{MatchedCredential, MetadataMatchedCredential, WithMatchedCredential},
    v1::{V1BroadcastCredential, V1DiscoveryCredential, V1},
    MatchableCredential,
};
use np_adv::extended::deserialize::{Section, V1DeserializedSection};
use np_adv::extended::serialize::{AdvBuilder, MicEncryptedSectionEncoder};
use np_adv::extended::{data_elements::TxPowerDataElement, V1IdentityToken};
use np_adv::shared_data::TxPower;
use np_adv::{deserialization_arena, deserialize_advertisement};
use np_hkdf::DerivedSectionKeys;
use serde::{Deserialize, Serialize};

mod fmt;

#[derive(Parser, Debug)]
struct Args {}

fn main() {
    let args = Args::parse();
    generate_data(&args);
}

fn generate_data(_args: &Args) {
    // identity material
    let mut rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();

    let identity_token = V1IdentityToken::from(rng.gen::<[u8; 16]>());
    let key_seed = rng.gen();
    let hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);

    let broadcast_cred = V1BroadcastCredential::new(key_seed, identity_token);

    // Serialize and encrypt some identity metadata (sender-side)
    let sender_metadata = IdentityMetadata {
        uuid: "378845e1-2616-420d-86f5-674177a7504d".to_string(),
        display_name: "Alice".to_string(),
        location: "Wonderland".to_string(),
    };
    let sender_metadata_bytes = sender_metadata.to_bytes();
    let encrypted_sender_metadata = MetadataMatchedCredential::<Vec<u8>>::encrypt_from_plaintext::<
        V1,
        CryptoProviderImpl,
    >(&hkdf, identity_token, &sender_metadata_bytes);

    // prepare advertisement
    let mut adv_builder = AdvBuilder::new();

    let mut section_builder = adv_builder
        .section_builder(MicEncryptedSectionEncoder::new_random_salt::<CryptoProviderImpl>(
            &mut rng,
            &broadcast_cred,
        ))
        .unwrap();
    section_builder.add_de(&TxPowerDataElement::from(TxPower::try_from(7).unwrap())).unwrap();
    section_builder.add_to_advertisement::<CryptoProviderImpl>();
    let adv = adv_builder.into_advertisement();

    let discovery_credential = V1DiscoveryCredential::new(
        key_seed,
        hkdf.v1_mic_short_salt_keys()
            .identity_token_hmac_key()
            .calculate_hmac::<CryptoProviderImpl>(identity_token.bytes()),
        hkdf.v1_mic_extended_salt_keys()
            .identity_token_hmac_key()
            .calculate_hmac::<CryptoProviderImpl>(identity_token.bytes()),
    );

    let credentials: [MatchableCredential<V1, MetadataMatchedCredential<_>>; 1] =
        [MatchableCredential {
            discovery_credential,
            match_data: encrypted_sender_metadata.clone(),
        }];
    let cred_book = CredentialBookBuilder::build_cached_slice_book::<0, 0, CryptoProviderImpl>(
        &[],
        &credentials,
    );

    let arena = deserialization_arena!();
    let contents =
        deserialize_advertisement::<_, CryptoProviderImpl>(arena, adv.as_slice(), &cred_book)
            .expect("Should be a valid advertisement")
            .into_v1()
            .expect("Should be V1");

    let sections = contents.sections().collect::<Vec<_>>();
    let matched: &WithMatchedCredential<_, _> = match &sections[0] {
        V1DeserializedSection::Decrypted(d) => d,
        _ => panic!("this is a ciphertext adv"),
    };
    let section = matched.contents();
    let data_elements = section.iter_data_elements().collect::<Result<Vec<_>, _>>().unwrap();
    let de = &data_elements[0];

    println!("\nC++ test data (nearby/presence/np_cpp_ffi/shared/shared_test_util.h):\n");
    println!("{}", fmt::cpp_static("V1AdvEncryptedBytes", adv.as_slice()));
    println!("{}", fmt::cpp_static("V1AdvKeySeed", &key_seed));
    println!(
        "{}",
        fmt::cpp_static(
            "V1AdvExpectedMicShortSaltIdentityTokenHmac",
            &credentials[0].discovery_credential.expected_mic_short_salt_identity_token_hmac
        )
    );
    println!(
        "{}",
        fmt::cpp_static(
            "V1AdvExpectedMicExtendedSaltIdentityTokenHmac",
            &credentials[0].discovery_credential.expected_mic_extended_salt_identity_token_hmac
        )
    );
    println!(
        "inline std::vector<uint8_t> V1AdvEncryptedMetadata = {};",
        fmt::cpp_bytes(&encrypted_sender_metadata.fetch_encrypted_metadata().unwrap())
    );
    println!(
        "inline std::string ExpectedV1DecryptedMetadata = {:?};",
        std::str::from_utf8(&sender_metadata_bytes).unwrap()
    );
    let derived_salt = de.salt().unwrap().derive::<16, CryptoProviderImpl>().unwrap();
    println!("{}", fmt::cpp_static("ExpectedV1DerivedSalt", &derived_salt));

    println!("\nJava test data (nearby/presence/np_java_ffi/test/com/google/android/nearby/presence/rust/TestData.java)\n");
    println!("{}", fmt::java_static("V1_KEY_SEED", &key_seed));
    println!("{}", fmt::java_static("V1_IDENTITY_TOKEN", identity_token.bytes()));
    println!(
        "{}",
        fmt::java_static(
            "V1_MIC_SHORT_HMAC",
            &credentials[0].discovery_credential.expected_mic_short_salt_identity_token_hmac
        )
    );
    println!(
        "{}",
        fmt::java_static(
            "V1_MIC_LONG_HMAC",
            &credentials[0].discovery_credential.expected_mic_extended_salt_identity_token_hmac
        )
    );
    println!("{}", fmt::java_static("V1_ALICE_METADATA", &sender_metadata_bytes));
    println!(
        "{}",
        fmt::java_static(
            "V1_ENCRYPTED_ALICE_METADATA",
            &encrypted_sender_metadata.fetch_encrypted_metadata().unwrap()
        )
    );
    println!("{}", fmt::java_static("V1_PRIVATE", adv.as_slice()));
}

/// Sample contents for some encrypted identity metadata
/// which consists of a UUID together with a display name
/// and a general location.
#[derive(Debug, Eq, PartialEq, Serialize, Deserialize)]
struct IdentityMetadata {
    uuid: String,
    display_name: String,
    location: String,
}

impl IdentityMetadata {
    /// Serialize this identity metadata to a json byte-string.
    fn to_bytes(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("Identity metadata serialization is infallible")
    }
    /// Attempt to deserialize identity metadata from a json byte-string.
    fn try_from_bytes(serialized: &[u8]) -> Option<Self> {
        serde_json::from_slice(serialized).ok()
    }
}
