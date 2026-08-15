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

mod happy_path {
    use super::super::*;
    use alloc::vec;

    #[test]
    fn parse_unencrypted() {
        assert_eq!(
            SectionHeader::parse(&[0b0000_0000]).expect("Parsing should succeed"),
            ([].as_slice(), Ok(SectionHeader::Unencrypted))
        );
    }

    #[test]
    fn parse_section_header_mic_with_short_salt() {
        let mut section_header = vec![];
        section_header.push(0b0000_0001u8); //format
        section_header.extend_from_slice(&[0xFF; 2]); // short salt
        section_header.extend_from_slice(&[0xCC; 16]); // identity token

        assert_eq!(
            SectionHeader::parse(section_header.as_slice()).expect("Parsing should succeed"),
            (
                [].as_slice(),
                Ok(SectionHeader::Encrypted(EncryptedSectionHeader::MicShortSalt {
                    salt: [0xFF; 2].into(),
                    token: [0xCC; 16].into(),
                }))
            )
        );
    }

    #[test]
    fn parse_section_header_mic_with_extended_salt() {
        let mut section_header = vec![];
        section_header.push(0b0000_0010u8); //format
        section_header.extend_from_slice(&[0xFF; 16]); // short salt
        section_header.extend_from_slice(&[0xCC; 16]); // identity token

        assert_eq!(
            SectionHeader::parse(section_header.as_slice()).expect("Parsing should succeed"),
            (
                [].as_slice(),
                Ok(SectionHeader::Encrypted(EncryptedSectionHeader::MicExtendedSalt {
                    salt: [0xFF; 16].into(),
                    token: [0xCC; 16].into(),
                }),)
            )
        );
    }
}

mod error_condition {
    use super::super::*;

    #[test]
    fn parse_single_byte_invalid_reserve() {
        let _ = SectionHeader::parse(&[0b0001_0000])
            .expect_err("Invalid reserve bits should fail to parse");
        let _ = SectionHeader::parse(&[0b0011_0000])
            .expect_err("Invalid reserve bits should fail to parse");
        let _ = SectionHeader::parse(&[0b0111_0000])
            .expect_err("Invalid reserve bits should fail to parse");
        let _ = SectionHeader::parse(&[0b1111_0000])
            .expect_err("Invalid reserve bits should fail to parse");
    }

    #[test]
    fn parse_section_header_mic_with_short_salt_too_short() {
        let _ = SectionHeader::parse(&[0b0000_0001])
            .expect_err("Not enough bytes present to parse a mic with short salt");
    }
}

mod coverage_gaming {
    use crate::extended::deserialize::section::header::{
        CiphertextExtendedIdentityToken, EncryptedSectionHeader,
    };
    use alloc::format;

    #[test]
    fn ct_identity_token_clone() {
        let token = CiphertextExtendedIdentityToken([0; 16]);
        #[allow(clippy::clone_on_copy)]
        let _ = token.clone();
    }

    #[test]
    fn encrypted_section_header_debug() {
        let header = EncryptedSectionHeader::MicShortSalt {
            salt: [0; 2].into(),
            token: CiphertextExtendedIdentityToken([0; 16]),
        };
        let _ = format!("{header:?}");
    }
}
