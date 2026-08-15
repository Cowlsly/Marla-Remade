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

//! Various representations of salts for extended advertisements.

use nom::combinator;

use crypto_provider::{aes::ctr::AesCtrNonce, CryptoProvider, CryptoRng, FromCryptoRng};

use crate::helpers::parse_byte_array;

pub use np_hkdf::v1_salt::{DeType, ExtendedV1Salt, OptionDeType};

/// Derived salt for an individual V1 data element.
#[derive(Clone, Copy, PartialEq, Debug, Eq)]
pub struct DeSalt {
    /// The containing section's extended V1 salt.
    pub(crate) salt: ExtendedV1Salt,
    /// The DE type of the data element.
    pub(crate) de_type: DeType,
}

impl DeSalt {
    /// Derive salt of the requested length.
    ///
    /// The length must be a valid HKDF-SHA256 length.
    pub fn derive<const N: usize, C: CryptoProvider>(&self) -> Option<[u8; N]> {
        self.salt.derive::<N, C>(OptionDeType::from(self.de_type))
    }
}

/// Common behavior for V1 section salts.
pub trait V1Salt: Copy + Into<MultiSalt> {
    /// Derive the nonce used for section encryption.
    ///
    /// Both kinds of salts can compute the nonce needed for de/encrypting a
    /// section, but only extended salts can have data derived from them.
    fn compute_nonce<C: CryptoProvider>(&self) -> AesCtrNonce;
}

impl V1Salt for ExtendedV1Salt {
    fn compute_nonce<C: CryptoProvider>(&self) -> AesCtrNonce {
        self.derive::<12, C>(OptionDeType::NONE).expect("AES-CTR nonce is a valid HKDF size")
    }
}

pub(crate) const SHORT_SALT_LEN: usize = 2;

/// A byte buffer the size of a V1 short salt
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct ShortV1Salt([u8; SHORT_SALT_LEN]);

impl From<[u8; SHORT_SALT_LEN]> for ShortV1Salt {
    fn from(value: [u8; SHORT_SALT_LEN]) -> Self {
        Self(value)
    }
}

impl ShortV1Salt {
    pub(crate) fn bytes(&self) -> &[u8; SHORT_SALT_LEN] {
        &self.0
    }

    pub(crate) fn parse(input: &[u8]) -> nom::IResult<&[u8], Self> {
        combinator::map(parse_byte_array::<SHORT_SALT_LEN>, Self)(input)
    }
}

impl V1Salt for ShortV1Salt {
    fn compute_nonce<C: CryptoProvider>(&self) -> AesCtrNonce {
        np_hkdf::extended_mic_section_short_salt_nonce::<C>(self.0)
    }
}

impl FromCryptoRng for ShortV1Salt {
    fn new_random<R: CryptoRng>(rng: &mut R) -> Self {
        rng.gen::<[u8; SHORT_SALT_LEN]>().into()
    }
}

/// Either a short or extended salt.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum MultiSalt {
    /// A 2-byte salt
    Short(ShortV1Salt),
    /// A 16-byte salt
    Extended(ExtendedV1Salt),
}

impl MultiSalt {
    /// Salt bytes as a slice, for when variable-size access is sensible
    pub fn as_slice(&self) -> &[u8] {
        match self {
            MultiSalt::Short(s) => s.bytes().as_slice(),
            MultiSalt::Extended(s) => s.bytes().as_slice(),
        }
    }
}

impl From<ExtendedV1Salt> for MultiSalt {
    fn from(value: ExtendedV1Salt) -> Self {
        Self::Extended(value)
    }
}

impl From<ShortV1Salt> for MultiSalt {
    fn from(value: ShortV1Salt) -> Self {
        Self::Short(value)
    }
}

impl V1Salt for MultiSalt {
    /// Both kinds of salts can compute the nonce needed for decrypting an
    /// advertisement, but only extended salts can have data derived from them.
    fn compute_nonce<C: CryptoProvider>(&self) -> AesCtrNonce {
        match self {
            Self::Short(s) => V1Salt::compute_nonce::<C>(s),
            Self::Extended(s) => s.compute_nonce::<C>(),
        }
    }
}

/// Type-level predicate which indicates that
/// a derived salt type can be used in call
/// sites which expect this derived salt type.
///
/// Specifically, if `S: SaltConvertible<T>`,
/// then a salt of type `T` suffices in
/// all call sites which expect a salt
/// of type `S` (possibly by omitting
/// information, if `S` is `Unsalted`).
pub trait SaltConvertible<T: Into<Option<DeSalt>>>: Into<Option<DeSalt>> {
    /// Converts a salt of one type into a salt
    /// usable in place of this salt type in method parameters.
    fn convert(salt: T) -> Self;
}

/// Type-level token indicating that a section encoding
/// scheme does not employ any sort of salt.
pub struct Unsalted;

impl From<Unsalted> for Option<DeSalt> {
    fn from(_: Unsalted) -> Self {
        None
    }
}

impl SaltConvertible<DeSalt> for DeSalt {
    fn convert(salt: DeSalt) -> Self {
        salt
    }
}

impl<T: Into<Option<DeSalt>>> SaltConvertible<T> for Option<DeSalt> {
    fn convert(salt: T) -> Self {
        salt.into()
    }
}

impl<T: Into<Option<DeSalt>>> SaltConvertible<T> for Unsalted {
    fn convert(_salt: T) -> Unsalted {
        Unsalted
    }
}
