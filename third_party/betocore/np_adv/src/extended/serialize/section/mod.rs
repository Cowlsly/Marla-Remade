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

#[cfg(feature = "std")]
extern crate std;

use array_view::ArrayView;
use core::fmt;
use crypto_provider::CryptoProvider;
use sink::{Sink, SinkWriter};

use crate::extended::{
    de_type::{DeType, OptionDeType},
    salt::SaltConvertible,
    serialize::{
        section::encoder::SectionEncoder, AdvBuilder, MutableSliceInArray, SortedChunkSlice,
        WriteDataElement,
    },
    NP_ADV_MAX_SECTION_LEN,
};

pub(crate) mod encoder;
pub(crate) mod header;

/// Type of `SectionBuilder.section`, used for keeping track
/// of the added section header and any DEs.
pub(crate) type SectionBuilderInternals = SortedChunkSlice<
    OptionDeType, // These will never be `OptionDeType::NONE`, we just need `Default`.
    u8,
    MutableSliceInArray<u8, NP_ADV_MAX_SECTION_LEN>,
    NP_ADV_MAX_SECTION_LEN, // At most as many DEs as there are bytes.
>;

/// Accumulates data elements and encodes them into a section.
#[derive(Debug)]
pub struct SectionBuilder<R: AsMut<AdvBuilder>, SE: SectionEncoder> {
    /// Contains the section header + identity-specified overhead, and keeps
    /// chunks of serialized DE bytes thereafter sorted by their [`DeType`].
    pub(crate) section: SectionBuilderInternals,
    pub(crate) section_encoder: SE,
    /// mut ref-able to enforce only one active section builder at a time
    pub(crate) adv_builder: R,
}
impl<SE: SectionEncoder> SectionBuilder<&mut AdvBuilder, SE> {
    /// Add this builder to the advertisement that created it.
    pub fn add_to_advertisement<C: CryptoProvider>(self) {
        let _ = self.add_to_advertisement_internal::<C>();
    }
}

impl<SE: SectionEncoder> SectionBuilder<AdvBuilder, SE> {
    /// Gets the count of the sections which were added to the advertisement
    /// prior to the creation of this section builder.
    pub fn previously_added_section_count(&self) -> usize {
        self.adv_builder.section_count()
    }
    /// Add this builder to the advertisement that created it,
    /// and returns the containing advertisement back to the caller.
    pub fn add_to_advertisement<C: CryptoProvider>(self) -> AdvBuilder {
        self.add_to_advertisement_internal::<C>()
    }
}

impl<R: AsMut<AdvBuilder>, SE: SectionEncoder> SectionBuilder<R, SE> {
    /// Constructs a new section builder from the given buffer
    /// already populated with padding for an encoding-specific header
    /// and whose mutable portion is just the writable space for data elements,
    /// the section encoder implementation, and the containing advertisement builder.
    pub(crate) fn new(
        section: MutableSliceInArray<u8, NP_ADV_MAX_SECTION_LEN>,
        section_encoder: SE,
        adv_builder: R,
    ) -> Self {
        let section = SortedChunkSlice::new(section);
        Self { section, section_encoder, adv_builder }
    }

    /// Helper function which is used to convert from a completed `SectionBuilder.section`
    /// into a `MutableSliceInArray` of the format expected by `SectionBuilder#build()`
    /// (mutable region bounded just around the data elements).
    pub(crate) fn finished_adding_des(
        section: SectionBuilderInternals,
    ) -> MutableSliceInArray<u8, NP_ADV_MAX_SECTION_LEN> {
        let des_written_len = section.len();
        let untruncated = section.finish();
        let mut data_range = untruncated.mutable_range();
        data_range.end = data_range.start + des_written_len;
        let data_range = data_range;
        let underlying = untruncated.into_data();
        MutableSliceInArray::new_with_mutable_bounds(underlying, data_range)
    }

    /// Add this builder to the advertisement that created it.
    /// Returns the mut-refable to the advertisement builder
    /// which the contents of this section builder were added to.
    fn add_to_advertisement_internal<C: CryptoProvider>(mut self) -> R {
        let adv_builder = self.adv_builder.as_mut();
        adv_builder.add_section(Self::build_section::<C>(
            Self::finished_adding_des(self.section),
            self.section_encoder,
        ));
        self.adv_builder
    }

    /// Gets the derived salt which would be employed for the given DE type.
    ///
    /// Suitable for scenarios (like FFI) where a closure would be inappropriate
    /// for DE construction, and interaction with the client is preferred.
    pub fn de_salt(&self, de_type: DeType) -> SE::DerivedSalt {
        self.section_encoder.de_salt(de_type)
    }

    /// Attempt to add a data element to the section.
    ///
    /// May fail if the contents of the DE exceed the available
    /// space left in the section. If this happens, the contents
    /// of the section will not change.
    pub fn add_de<W: WriteDataElement>(&mut self, de: &W) -> Result<(), AddDataElementError>
    where
        <W as WriteDataElement>::Salt: SaltConvertible<SE::DerivedSalt>,
    {
        let de_type = de.de_type();

        // Before we do anything, make sure that we're not trying to add a DE
        // with a duplicate type-code.
        //
        // NOTE: We could make this slightly more efficient in the non-error case by fusing
        // the write operation with the "duplicate key" check in the `SortedChunkSlice`
        // interface, but doing so naively comes at the cost of eroding the elegance
        // of the exposed interface, and the potential gain is only a [small] constant
        // factor improvement.
        if self.section.contains_key(&de_type.into()) {
            return Err(AddDataElementError::DuplicateDataElementTypeCode);
        }

        let salt = <<W as WriteDataElement>::Salt as SaltConvertible<
            <SE as SectionEncoder>::DerivedSalt,
        >>::convert(self.section_encoder.de_salt(de_type));

        /// `SinkWriter` implementation for writing the DE with
        /// the provided salt.
        struct DataElementWriter<'a, W: WriteDataElement> {
            de: &'a W,
            salt: <W as WriteDataElement>::Salt,
        }

        impl<W: WriteDataElement> SinkWriter for DataElementWriter<'_, W> {
            type DataType = u8;
            fn write_payload<S: Sink<u8>>(self, sink: &mut S) -> Option<()> {
                self.de.write_de(self.salt, sink)
            }
        }

        let writer = DataElementWriter { de, salt };

        // Since `write_de` is a derived method which writes the de header,
        // we will never have a length-zero (invalid) chunk, and so all
        // reachable failure modes are different expressions of running out of space.
        self.section
            .write_chunk(de_type.into(), writer)
            .map_err(|_| AddDataElementError::InsufficientSpace)
    }

    /// Convert a section builder's contents into an encoded section.
    ///
    /// The passed section contents must have both the section header
    /// and any subsequent data elements, the mutable region
    /// should be bounded around only the data elements,
    /// and there should be at least `SE::SUFFIX_LEN` bytes
    /// available after the end of the mutable region to
    /// store the section encoder's suffix (if any).
    ///
    /// Implemented without self to avoid partial-move issues.
    pub(crate) fn build_section<C: CryptoProvider>(
        section_contents: MutableSliceInArray<u8, NP_ADV_MAX_SECTION_LEN>,
        mut section_encoder: SE,
    ) -> EncodedSection {
        // Extract the bounds around the DEs on the section buffer.
        // The lower bound is equal to the length of the section header,
        // and the upper bound is equal to the length of all currently-written
        // section contents.
        let core::ops::Range { start: header_len, end: section_written_len } =
            section_contents.mutable_range();
        let de_contents_len = section_written_len - header_len;

        let mut section_contents = section_contents.into_data();

        let (section_header, rest_of_contents) = section_contents.split_at_mut(header_len);
        let (section_length_byte, encoding_type_and_encoding_specific_header) =
            section_header.split_last_mut().expect("Section header should be at least one byte");

        let des_and_section_suffix_len = de_contents_len + SE::SUFFIX_LEN;
        let des_and_section_suffix = &mut rest_of_contents[..des_and_section_suffix_len];

        let section_len = des_and_section_suffix_len.try_into().expect(
            "section length will always fit into a u8 and has been validated by the section builder",
        );
        // set the section length byte
        *section_length_byte = section_len;

        section_encoder.postprocess::<C>(
            encoding_type_and_encoding_specific_header,
            section_len,
            des_and_section_suffix,
        );

        let total_len = header_len + des_and_section_suffix_len;

        ArrayView::try_from_array(section_contents, total_len)
            .expect("Section buffer should have enough capacity for the suffix.")
    }
}

/// Errors for adding a DE to a section
#[derive(Debug, PartialEq, Eq)]
pub enum AddDataElementError {
    /// Too much data to fit into the section and/or too much
    /// data to fit within the limits of a single DE.
    ///
    /// [`WriteDataElement`] implementors should ensure (upon
    /// construction, or during mutating updates) that their
    /// header and contents fit within 127 bytes to avoid tripping this
    /// error upon attempting to add the DE, which may be too late,
    /// depending on the desired client behavior.
    InsufficientSpace,
    /// The attempt to add the data element failed because a data
    /// element with the same type-code already was added to the section.
    DuplicateDataElementTypeCode,
}

impl fmt::Display for AddDataElementError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AddDataElementError::InsufficientSpace => {
                write!(f, "Insufficient space to write DE")
            }
            AddDataElementError::DuplicateDataElementTypeCode => {
                write!(f, "A DE with the same type-code was already added")
            }
        }
    }
}

#[cfg(feature = "std")]
impl std::error::Error for AddDataElementError {}

/// The encoded form of an advertisement section
pub(crate) type EncodedSection = ArrayView<u8, NP_ADV_MAX_SECTION_LEN>;
