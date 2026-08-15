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

//! High-level, early-stage parsed structures for a section: the header, then everything else.

use crate::extended::{
    salt::ShortV1Salt, EncodingType, V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN,
    V1_ENCODING_ENCRYPTED_MIC_WITH_SHORT_SALT_AND_TOKEN, V1_ENCODING_UNENCRYPTED,
    V1_IDENTITY_TOKEN_LEN,
};
use crate::helpers::parse_byte_array;
use nom::{combinator, number, sequence};
use np_hkdf::v1_salt::{ExtendedV1Salt, EXTENDED_SALT_LEN};

#[cfg(test)]
mod tests;

/// A successfully-parsed (pre-length) section header
/// for a known encoding type.
#[derive(PartialEq, Eq, Debug)]
pub(crate) enum SectionHeader {
    Unencrypted,
    Encrypted(EncryptedSectionHeader),
}

impl SectionHeader {
    /// Gets the 4-bit encoding ID of the encoding to be employed
    /// according to the section header.
    pub(crate) fn encoding_type(&self) -> EncodingType {
        match self {
            Self::Unencrypted => V1_ENCODING_UNENCRYPTED,
            Self::Encrypted(x) => x.encoding_type(),
        }
    }
    /// Attempts to parse a section header from the given bytes.
    ///
    /// If the encoding type of the section header is recognized,
    /// this method will return:
    /// - The entire parsed section header if the formatting was valid.
    /// - A nom parser error if the formatting was invalid.
    ///
    /// If the encoding type is unrecognized, this parser will
    /// succeed, but it will only consume a single byte and
    /// the result will be an `Err(EncodingType)` containing
    /// the unrecognized encoding type.
    pub(crate) fn parse(input: &[u8]) -> nom::IResult<&[u8], Result<Self, EncodingType>> {
        // 0bRRRRSSSS first header byte expressing the section encoding.
        let (input, encoding) = combinator::verify(number::complete::u8, |b| {
            // Verify all reserved bits are zero.
            (*b & 0xF0) == 0
        })(input)?;

        let encoding = EncodingType(encoding);
        match encoding {
            V1_ENCODING_UNENCRYPTED => Ok((input, Ok(SectionHeader::Unencrypted))),
            V1_ENCODING_ENCRYPTED_MIC_WITH_SHORT_SALT_AND_TOKEN => combinator::map(
                sequence::tuple((ShortV1Salt::parse, CiphertextExtendedIdentityToken::parse)),
                |(salt, token)| {
                    Ok(SectionHeader::Encrypted(EncryptedSectionHeader::MicShortSalt {
                        salt,
                        token,
                    }))
                },
            )(input),
            V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN => combinator::map(
                sequence::tuple((parse_v1_extended_salt, CiphertextExtendedIdentityToken::parse)),
                |(salt, token)| {
                    Ok(SectionHeader::Encrypted(EncryptedSectionHeader::MicExtendedSalt {
                        salt,
                        token,
                    }))
                },
            )(input),
            _ => Ok((input, Err(encoding))),
        }
    }
}

fn parse_v1_extended_salt(input: &[u8]) -> nom::IResult<&[u8], ExtendedV1Salt> {
    combinator::map(parse_byte_array::<{ EXTENDED_SALT_LEN }>, ExtendedV1Salt::from)(input)
}

#[allow(clippy::enum_variant_names)]
#[derive(PartialEq, Eq, Debug)]
pub(crate) enum EncryptedSectionHeader {
    MicShortSalt { salt: ShortV1Salt, token: CiphertextExtendedIdentityToken },
    MicExtendedSalt { salt: ExtendedV1Salt, token: CiphertextExtendedIdentityToken },
}
impl EncryptedSectionHeader {
    /// Gets the 4-bit encoding ID of the encoding to be employed
    /// according to the section header.
    pub(crate) fn encoding_type(&self) -> EncodingType {
        match self {
            Self::MicShortSalt { salt: _, token: _ } => {
                V1_ENCODING_ENCRYPTED_MIC_WITH_SHORT_SALT_AND_TOKEN
            }
            Self::MicExtendedSalt { salt: _, token: _ } => {
                V1_ENCODING_ENCRYPTED_MIC_WITH_EXTENDED_SALT_AND_TOKEN
            }
        }
    }
}

/// 16-byte identity token, straight out of the section.
///
/// If identity resolution succeeds, decrypted to an [ExtendedIdentityToken](crate::extended::V1IdentityToken).
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub(crate) struct CiphertextExtendedIdentityToken(pub(crate) [u8; V1_IDENTITY_TOKEN_LEN]);

impl CiphertextExtendedIdentityToken {
    pub(crate) fn parse(input: &[u8]) -> nom::IResult<&[u8], Self> {
        combinator::map(parse_byte_array::<V1_IDENTITY_TOKEN_LEN>, Self)(input)
    }
}

impl From<[u8; V1_IDENTITY_TOKEN_LEN]> for CiphertextExtendedIdentityToken {
    fn from(value: [u8; V1_IDENTITY_TOKEN_LEN]) -> Self {
        Self(value)
    }
}
