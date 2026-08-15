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

//! V1 DE type types

pub use np_hkdf::v1_salt::{DeType, InvalidDeType, OptionDeType};

/// Common base trait for things which have an
/// associated [`DeType`] value.
pub trait HasDEType {
    /// The DE type for this structure.
    const DE_TYPE: DeType;
}

impl<H: HasDEType> HasDEType for &H {
    const DE_TYPE: DeType = <H as HasDEType>::DE_TYPE;
}

#[cfg(test)]
mod test {
    use crate::extended::de_type::DeType;

    #[test]
    fn u32_from_de_type() {
        let de = DeType::from(8u8);
        let _val: u32 = de.into();
    }
}
