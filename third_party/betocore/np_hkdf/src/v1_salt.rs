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

//! Salt used in a V1 advertisement.
use crate::np_salt_hkdf;
use core::cmp::Ordering;
use crypto_provider::{hkdf::Hkdf, CryptoProvider, CryptoRng, FromCryptoRng};

/// Length of a V1 extended salt
pub const EXTENDED_SALT_LEN: usize = 16;

/// Salt optionally included in V1 advertisement header.
///
/// The salt is never used directly; rather, a derived salt should be extracted as needed for any
/// section or DE that requires it.
#[derive(Clone, Copy, PartialEq, Debug, Eq)]
pub struct ExtendedV1Salt {
    data: [u8; EXTENDED_SALT_LEN],
}

impl ExtendedV1Salt {
    /// Derive a salt for a particular DE, or for a section's encoder implementation
    /// (`OptionDeType::NONE`).
    ///
    /// Returns `None` if the requested size is larger than HKDF allows (8192 bytes).
    pub fn derive<const N: usize, C: CryptoProvider>(
        &self,
        maybe_de_type: OptionDeType,
    ) -> Option<[u8; N]> {
        let hkdf = np_salt_hkdf::<C>(&self.data);
        let mut arr = [0_u8; N];
        // `OptionDeType` uses `FORBIDDEN_DE_TYPE_CODE` for `None`, which matches the method
        // contract against the advertisement specification correctly.
        hkdf.expand_multi_info(&[b"V1 derived salt", &maybe_de_type.code.to_le_bytes()], &mut arr)
            .map(|_| arr)
            .ok()
    }

    /// Returns the salt bytes as a slice
    pub fn as_slice(&self) -> &[u8] {
        self.data.as_slice()
    }

    /// Returns the salt bytes as an array
    pub fn into_array(self) -> [u8; EXTENDED_SALT_LEN] {
        self.data
    }

    /// Returns the salt bytes as a reference to an array
    pub fn bytes(&self) -> &[u8; EXTENDED_SALT_LEN] {
        &self.data
    }
}

impl From<[u8; EXTENDED_SALT_LEN]> for ExtendedV1Salt {
    fn from(arr: [u8; EXTENDED_SALT_LEN]) -> Self {
        Self { data: arr }
    }
}

impl FromCryptoRng for ExtendedV1Salt {
    fn new_random<R: CryptoRng>(rng: &mut R) -> Self {
        rng.gen::<[u8; EXTENDED_SALT_LEN]>().into()
    }
}

/// Error type indicating that a value used in an
/// attempt to construct a [`DeType`] is
/// a forbidden value out of the range of
/// allowable DE type codes.
#[derive(Debug)]
pub struct InvalidDeType;

/// Reserved `u32` which may not be employed for data element
/// type codes, but is employed in deriving section-level
/// salts used for e.g: MIC encrypted sections.
pub const FORBIDDEN_DE_TYPE_CODE: u32 = 0xFFFFFFFF;

/// Minimal-size representation of an `Option<DeType>`, exploiting
/// the [`FORBIDDEN_DE_TYPE_CODE`] as a niche to store `None` in.
#[derive(Debug, Clone, Copy, Hash, PartialEq, Eq)]
pub struct OptionDeType {
    code: u32,
}

impl OptionDeType {
    /// [`OptionDeType`] corresponding to `Option::None`.
    pub const NONE: Self = Self { code: FORBIDDEN_DE_TYPE_CODE };
}

impl Default for OptionDeType {
    fn default() -> Self {
        Self::NONE
    }
}

impl From<DeType> for OptionDeType {
    fn from(de_type: DeType) -> Self {
        Self { code: de_type.code }
    }
}

impl From<Option<DeType>> for OptionDeType {
    fn from(maybe_de_type: Option<DeType>) -> Self {
        maybe_de_type.map(OptionDeType::from).unwrap_or(Self::NONE)
    }
}

impl From<OptionDeType> for Option<DeType> {
    fn from(option_de_type: OptionDeType) -> Self {
        if option_de_type.code == FORBIDDEN_DE_TYPE_CODE {
            None
        } else {
            Some(DeType { code: option_de_type.code })
        }
    }
}

impl PartialOrd for OptionDeType {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for OptionDeType {
    fn cmp(&self, other: &Self) -> Ordering {
        match (self.code, other.code) {
            (FORBIDDEN_DE_TYPE_CODE, FORBIDDEN_DE_TYPE_CODE) => Ordering::Equal,
            (_, FORBIDDEN_DE_TYPE_CODE) => Ordering::Greater,
            (FORBIDDEN_DE_TYPE_CODE, _) => Ordering::Less,
            (my_de_type, other_de_type) => my_de_type.cmp(&other_de_type),
        }
    }
}

/// Data element types for extended advertisements
#[derive(Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Clone, Copy)]
pub struct DeType {
    // 4 billion type codes should be enough for anybody
    code: u32,
}

impl DeType {
    /// A `const` equivalent to `From<u32>` since trait methods can't yet be const.
    #[allow(clippy::panic)]
    pub const fn const_from(value: u32) -> Self {
        if value == FORBIDDEN_DE_TYPE_CODE {
            panic!("Invalid DeType.");
        }
        Self { code: value }
    }

    /// Returns the type as a u32
    pub fn as_u32(&self) -> u32 {
        self.code
    }
}

impl From<u8> for DeType {
    fn from(value: u8) -> Self {
        DeType { code: value.into() }
    }
}

impl From<u16> for DeType {
    fn from(value: u16) -> Self {
        DeType { code: value.into() }
    }
}

impl TryFrom<u32> for DeType {
    type Error = InvalidDeType;
    fn try_from(value: u32) -> Result<Self, Self::Error> {
        if value == FORBIDDEN_DE_TYPE_CODE {
            Err(InvalidDeType)
        } else {
            Ok(DeType { code: value })
        }
    }
}

impl From<DeType> for u32 {
    fn from(value: DeType) -> Self {
        value.code
    }
}
