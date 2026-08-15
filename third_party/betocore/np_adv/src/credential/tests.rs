// Copyright 2023 Google LLC
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

//! Tests of functionality related to credentials, credential-views, and credential suppliers.

extern crate alloc;

use crate::credential::matched::{
    EmptyMatchedCredential, KeySeedMatchedCredential, MatchedCredentialWithId,
    ReferencedMatchedCredential,
};
use crate::credential::v1::MicSectionVerificationMaterial;
use crate::credential::{
    book::{
        init_cache_from_source, CachedCredentialSource, CredentialBook, CredentialBookBuilder,
        PossiblyCachedDiscoveryCryptoMaterialKind,
    },
    source::{CredentialSource, SliceCredentialSource},
    v0::{V0DiscoveryCredential, V0},
    v1::{V1BroadcastCredential, V1DiscoveryCredential, V1DiscoveryCryptoMaterial, V1},
    MatchableCredential, MatchedCredential,
};
use crate::extended::{V1IdentityToken, V1_IDENTITY_TOKEN_LEN};
use alloc::vec;
use alloc::vec::Vec;
use crypto_provider_default::CryptoProviderImpl;

fn get_zeroed_v0_discovery_credential() -> V0DiscoveryCredential {
    V0DiscoveryCredential::new([0u8; 32], [0u8; 32])
}

fn get_constant_packed_v1_discovery_credential(value: u8) -> V1DiscoveryCredential {
    V1BroadcastCredential::new([value; 32], V1IdentityToken::from([value; V1_IDENTITY_TOKEN_LEN]))
        .derive_discovery_credential::<CryptoProviderImpl>()
}

#[test]
fn cached_credential_source_keeps_same_entries_as_original() {
    let creds: [MatchableCredential<V1, KeySeedMatchedCredential>; 5] =
        [0u8, 1, 2, 3, 4].map(|x| {
            let match_data = KeySeedMatchedCredential::from([x; 32]);
            MatchableCredential {
                discovery_credential: get_constant_packed_v1_discovery_credential(x),
                match_data,
            }
        });
    let supplier = SliceCredentialSource::new(&creds);
    let cache = init_cache_from_source::<_, _, 3, CryptoProviderImpl>(&supplier);
    let cached = CachedCredentialSource::new(supplier, cache);
    let cached_view = &cached;
    assert_eq!(cached_view.iter().count(), 5);
    // Now we're going to check that the pairings between the match-data
    // and the MIC hmac key wind up being the same between the original
    // creds list and what's provided by the cached source.
    let expected: Vec<_> = creds
        .iter()
        .map(|cred| {
            (
                *cred
                    .discovery_credential
                    .mic_extended_salt_verification_material::<CryptoProviderImpl>()
                    .mic_hmac_key()
                    .as_bytes(),
                ReferencedMatchedCredential::from(&cred.match_data),
            )
        })
        .collect();
    let actual: Vec<_> = cached_view
        .iter()
        .map(|(crypto_material, match_data)| {
            (
                *crypto_material
                    .mic_extended_salt_verification_material::<CryptoProviderImpl>()
                    .mic_hmac_key()
                    .as_bytes(),
                match_data,
            )
        })
        .collect();
    assert_eq!(actual, expected);
}

#[test]
fn cached_credential_source_has_requested_cache_size() {
    let creds: [MatchableCredential<V0, EmptyMatchedCredential>; 10] =
        [0u8; 10].map(|_| MatchableCredential {
            discovery_credential: get_zeroed_v0_discovery_credential(),
            match_data: EmptyMatchedCredential,
        });
    let supplier = SliceCredentialSource::new(&creds);
    let cache = init_cache_from_source::<_, _, 5, CryptoProviderImpl>(&supplier);
    let cached = CachedCredentialSource::new(supplier, cache);
    let cached_view = &cached;
    assert_eq!(cached_view.iter().count(), 10);
    for (i, (cred, _)) in cached_view.iter().enumerate() {
        if i < 5 {
            // Should be cached
            if let PossiblyCachedDiscoveryCryptoMaterialKind::Precalculated(_) = cred.wrapped {
            } else {
                panic!("Credential #{} was not cached", i);
            }
        } else {
            // Should be discovery credentials
            if let PossiblyCachedDiscoveryCryptoMaterialKind::Discovery(_) = cred.wrapped {
            } else {
                panic!("Credential #{} was not supposed to be cached", i);
            }
        }
    }
}

#[test]
fn precalculated_owned_book_with_id_iterates_correctly() {
    let v0_creds: [MatchableCredential<V0, MatchedCredentialWithId<Vec<u8>>>; 2] =
        [0, 1].map(|i| {
            let match_data = MatchedCredentialWithId::new(vec![i], i as i64);
            MatchableCredential {
                discovery_credential: get_zeroed_v0_discovery_credential(),
                match_data,
            }
        });
    let v1_creds: [MatchableCredential<V1, MatchedCredentialWithId<Vec<u8>>>; 3] =
        [2, 3, 4].map(|i| {
            let match_data = MatchedCredentialWithId::new(vec![i], i as i64);
            MatchableCredential {
                discovery_credential: get_constant_packed_v1_discovery_credential(i),
                match_data,
            }
        });

    let v0_expected: Vec<_> = v0_creds
        .iter()
        .map(|c| (c.match_data.id(), c.match_data.fetch_encrypted_metadata().unwrap()))
        .collect();

    let v1_expected: Vec<_> = v1_creds
        .iter()
        .map(|c| (c.match_data.id(), c.match_data.fetch_encrypted_metadata().unwrap()))
        .collect();

    let book = CredentialBookBuilder::build_precalculated_owned_book_with_id::<CryptoProviderImpl>(
        v0_creds, v1_creds,
    );

    let v0_from_book: Vec<_> =
        book.v0_iter().map(|(_, m)| (m.id(), m.fetch_encrypted_metadata().unwrap())).collect();

    assert_eq!(v0_from_book, v0_expected);

    let v1_from_book: Vec<_> =
        book.v1_iter().map(|(_, m)| (m.id(), m.fetch_encrypted_metadata().unwrap())).collect();

    assert_eq!(v1_from_book, v1_expected);
}

mod coverage_gaming {
    use crate::credential::MetadataDecryptionError;
    use alloc::format;

    #[test]
    fn metadata_decryption_error_debug() {
        let err = MetadataDecryptionError;
        let _ = format!("{err:?}");
    }
}
