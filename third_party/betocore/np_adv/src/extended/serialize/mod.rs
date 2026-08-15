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

//! Serialization support for V1 advertisements.
//!
//! # Examples
//!
//! Serialize some DEs without an adv salt:
//!
//! ```
//! use crypto_provider_default::CryptoProviderImpl;
//! use np_adv::{
//!     extended::{data_elements::*, serialize::*, de_type::DeType, V1_ENCODING_UNENCRYPTED},
//!     shared_data::TxPower
//! };
//!
//! // no section identities or DEs need salt in this example
//! let mut adv_builder = AdvBuilder::new();
//! let mut section_builder = adv_builder.section_builder(UnencryptedSectionEncoder).unwrap();
//!
//! section_builder.add_de(&TxPowerDataElement::from(TxPower::try_from(3).unwrap())).unwrap();
//!
//! // add some other DE with type = 1000
//! section_builder.add_de(
//!     &GenericDataElement::try_from( DeType::from(1000_u16), &[10, 11, 12, 13]).unwrap()
//! ).unwrap();
//!
//! section_builder.add_to_advertisement::<CryptoProviderImpl>();
//!
//! assert_eq!(
//!     &[
//!         0x20, // version header
//!         V1_ENCODING_UNENCRYPTED.byte_value(), //section format
//!         0x09, // section length
//!         0x15, 3, // tx power
//!         0x84, 0x87, 0x68, 10, 11, 12, 13, // other DE
//!     ],
//!     adv_builder.into_advertisement().as_slice()
//! );
//! ```
//!
//! Serialize some DEs in an adv with an encrypted section:
//!
//! ```
//! use np_adv::{
//!     credential::{ v1::{V1, V1BroadcastCredential}},
//!     extended::{data_elements::*, serialize::*, de_type::{DeType, HasDEType}, V1IdentityToken },
//!     extended::salt::DeSalt,
//! };
//! use rand::{Rng as _, SeedableRng as _};
//! use crypto_provider::{CryptoProvider, CryptoRng, ed25519};
//! use crypto_provider_default::CryptoProviderImpl;
//! use np_adv::shared_data::TxPower;
//! use sink::Sink;
//!
//! let mut adv_builder = AdvBuilder::new();
//!
//! // these would come from the credential
//!
//! let mut rng = <CryptoProviderImpl as CryptoProvider>::CryptoRng::new();
//! let identity_token = rng.gen();
//! let key_seed: [u8; 32] = rng.gen();
//! // use your preferred crypto impl
//! let key_seed_hkdf = np_hkdf::NpKeySeedHkdf::<CryptoProviderImpl>::new(&key_seed);
//!
//! let broadcast_cm = V1BroadcastCredential::new(
//!     key_seed,
//!     identity_token,
//! );
//!
//! let mut section_builder = adv_builder.section_builder(MicEncryptedSectionEncoder::<_>::new_random_salt::<CryptoProviderImpl>(
//!     &mut rng,
//!     &broadcast_cm,
//! )).unwrap();
//!
//! section_builder.add_de(&TxPowerDataElement::from(TxPower::try_from(3).unwrap())).unwrap();
//!
//! // Sample `WriteDataElement` implementor which
//! // leverages a provided salt to write a payload.
//! struct FancyCryptoDataElement;
//!
//! impl HasDEType for FancyCryptoDataElement {
//!     const DE_TYPE: DeType = DeType::const_from(1000_u32);
//! }
//!
//! impl WriteDataElement for FancyCryptoDataElement {
//!     // Mark that we actually want derived DE salts.
//!     type Salt = DeSalt;
//!     fn write_de_contents<S: Sink<u8>>(&self, salt: Self::Salt, sink: &mut S) -> Option<()> {
//!         let derived_salt = salt.derive::<16, CryptoProviderImpl>().expect("16 is a valid HDKF length");
//!         let contents = do_fancy_crypto(derived_salt);
//!         sink.try_extend_from_slice(&contents)
//!     }
//! }
//!
//! // add our fancy-crypto DE with type = 1000
//! section_builder.add_de(&FancyCryptoDataElement).unwrap();
//!
//! section_builder.add_to_advertisement::<CryptoProviderImpl>();
//!
//! // can't assert much about this since most of it is random
//! assert_eq!(
//!     0x20, // adv header
//!     adv_builder.into_advertisement().as_slice()[0]
//! );
//!
//! // A hypothetical function that uses the per-DE derived salt to do something like encrypt or
//! // otherwise scramble data
//! fn do_fancy_crypto(derived_salt: [u8; 16]) -> [u8; 16] {
//!     // flipping bits is just a nonsense example, do something real here
//!     derived_salt.iter().map(|b| !b)
//!         .collect::<Vec<_>>()
//!         .try_into().expect("array sizes match")
//! }
//! ```

#[cfg(feature = "std")]
extern crate std;

use core::fmt::{self, Display};
use core::marker::PhantomData;

use array_view::ArrayView;
use sink::{Sink, SinkWriter};

use crate::extended::{
    de_requires_extended_bit,
    de_type::{DeType, HasDEType},
    salt::DeSalt,
    serialize::section::EncodedSection,
    DeLength, EncodingType, BLE_5_ADV_SVC_MAX_CONTENT_LEN, MAX_DE_LEN, NP_ADV_MAX_SECTION_LEN,
    NP_V1_ADV_MAX_SECTION_COUNT,
};

pub(crate) mod section;

use crate::header::VERSION_HEADER_V1;
pub use section::{
    encoder::{MicEncryptedSectionEncoder, SectionEncoder, UnencryptedSectionEncoder},
    AddDataElementError, SectionBuilder,
};

#[cfg(test)]
pub(crate) mod adv_tests;
#[cfg(test)]
mod de_header_tests;
#[cfg(test)]
pub(crate) mod section_tests;
#[cfg(test)]
mod test_vectors;

/// Builder for V1 advertisements.
#[derive(Debug)]
pub struct AdvBuilder {
    // TODO make this configurable, and test making sections whose length is not restricted by BLE limitations
    /// Contains the adv header byte, and keeps chunks-of-serialized-section
    /// bytes thereafter sorted by their [`EncodingType`].
    adv: SortedChunkSlice<
        EncodingType,
        u8,
        MutableSliceInArray<u8, BLE_5_ADV_SVC_MAX_CONTENT_LEN>,
        NP_V1_ADV_MAX_SECTION_COUNT,
    >,
}

impl Default for AdvBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl AsMut<AdvBuilder> for AdvBuilder {
    fn as_mut(&mut self) -> &mut AdvBuilder {
        self
    }
}

impl AdvBuilder {
    /// Build an [AdvBuilder].
    pub fn new() -> Self {
        let mut adv = [0u8; BLE_5_ADV_SVC_MAX_CONTENT_LEN];
        adv[0] = VERSION_HEADER_V1;
        let adv = MutableSliceInArray::new_with_immutable_prefix(adv, 1..);
        let adv = SortedChunkSlice::new(adv);
        Self { adv }
    }

    /// Create a section builder whose contents may be added to this advertisement.
    ///
    /// The builder will not accept more DEs than can fit given the space already used in the
    /// advertisement by previous sections, if any.
    ///
    /// Once the builder is populated, add it to the originating advertisement with
    /// [SectionBuilder.add_to_advertisement].
    pub fn section_builder<SE: SectionEncoder>(
        &mut self,
        section_encoder: SE,
    ) -> Result<SectionBuilder<&mut AdvBuilder, SE>, AddSectionError> {
        let section_buffer = self.prepare_section_builder_buffer(&section_encoder)?;
        Ok(SectionBuilder::new(section_buffer, section_encoder, self))
    }

    /// Create a section builder which actually takes ownership of this advertisement builder.
    ///
    /// This is unlike `AdvertisementBuilder#section_builder` in that the returned section
    /// builder will take ownership of this advertisement builder, if the operation was
    /// successful. Otherwise, this advertisement builder will be returned back to the
    /// caller unaltered as part of the `Err` arm.
    #[allow(clippy::result_large_err)]
    pub fn into_section_builder<SE: SectionEncoder>(
        self,
        section_encoder: SE,
    ) -> Result<SectionBuilder<AdvBuilder, SE>, (AdvBuilder, AddSectionError)> {
        match self.prepare_section_builder_buffer::<SE>(&section_encoder) {
            Ok(section_buffer) => Ok(SectionBuilder::new(section_buffer, section_encoder, self)),
            Err(err) => Err((self, err)),
        }
    }

    /// Convert the builder into an encoded advertisement.
    pub fn into_advertisement(self) -> EncodedAdvertisement {
        // Determine how many bytes we've written, and destructure
        // our wrappers around the underlying output array.
        let num_post_header_bytes = self.adv.len();
        let raw_bytes = self.adv.finish().into_data();
        let total_len = num_post_header_bytes + 1;
        let adv = ArrayView::try_from_array(raw_bytes, total_len)
            .expect("Advertisement length calculation should never exceed max length");
        EncodedAdvertisement { adv }
    }

    /// Gets the current number of sections added to this advertisement
    /// builder, not counting any outstanding SectionBuilders.
    pub fn section_count(&self) -> usize {
        self.adv.num_chunks()
    }

    /// Returns a restricted-mutability view of a section's byte-buffer which is already
    /// populated with header information in an immutable prefix, also with an immutable
    /// reserved suffix of the length of the section encoder's suffix length.
    /// The mutable region of the returned byte-buffer will correspond only
    /// to the writeable region for data elements.
    fn prepare_section_builder_buffer<SE: SectionEncoder>(
        &self,
        section_encoder: &SE,
    ) -> Result<MutableSliceInArray<u8, NP_ADV_MAX_SECTION_LEN>, AddSectionError> {
        if self.section_count() >= NP_V1_ADV_MAX_SECTION_COUNT {
            return Err(AddSectionError::MaxSectionCountExceeded);
        }

        // The header contains all the header bytes except for the final length byte.
        let header = section_encoder.header();
        let header_slice = header.as_slice();

        // The max overall len available to the section
        // Calculated by subtracting the adv header byte from
        // the max advertisement content length, and then accounting
        // for any sections that we've written previously.
        let available_len = BLE_5_ADV_SVC_MAX_CONTENT_LEN - 1 - self.adv.len();

        let suffix_start_index = available_len
            .checked_sub(SE::SUFFIX_LEN)
            .ok_or(AddSectionError::InsufficientAdvSpace)?;

        // Ensure that header + length byte will not overlap where the start
        // of the suffix would need to be [or else we're out of space.]
        if header_slice.len() >= suffix_start_index {
            return Err(AddSectionError::InsufficientAdvSpace);
        }

        // Allocate a buffer for the section
        // and insert the header and a placeholder for the section length.
        let mut buffer = [0u8; NP_ADV_MAX_SECTION_LEN];
        buffer[..header_slice.len()].copy_from_slice(header_slice);
        buffer[header_slice.len()] = 0u8;
        Ok(MutableSliceInArray::new_with_mutable_bounds(
            buffer,
            (header_slice.len() + 1)..suffix_start_index,
        ))
    }

    /// Add the section, which must have come from a SectionBuilder generated from this, into this
    /// advertisement.
    fn add_section(&mut self, section: EncodedSection) {
        // Peel off the encoding type (first byte) from the encoded section,
        // since we'll be using that for section sorting.
        let encoding_type = EncodingType(section.as_slice()[0]);
        self.adv
            .push_slice(encoding_type, section.as_slice())
            .expect("Section capacity enforced in the section builder");
    }
}

/// Errors that can occur when adding a section to an advertisement
#[derive(Debug, PartialEq, Eq)]
pub enum AddSectionError {
    /// The advertisement doesn't have enough space to hold the minimum size of the section
    InsufficientAdvSpace,
    /// The advertisement can only hold a maximum of NP_V1_ADV_MAX_SECTION_COUNT number of sections
    MaxSectionCountExceeded,
}

impl Display for AddSectionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AddSectionError::InsufficientAdvSpace => {
                write!(f, "The advertisement (max {BLE_5_ADV_SVC_MAX_CONTENT_LEN} bytes) doesn't have enough remaining space to hold the section")
            }
            AddSectionError::MaxSectionCountExceeded => {
                write!(f, "The advertisement can only hold a maximum of {NP_V1_ADV_MAX_SECTION_COUNT} number of sections")
            }
        }
    }
}

#[cfg(feature = "std")]
impl std::error::Error for AddSectionError {}

/// An encoded NP V1 advertisement, starting with the NP advertisement header byte.
#[derive(Debug, PartialEq, Eq)]
pub struct EncodedAdvertisement {
    adv: ArrayView<u8, BLE_5_ADV_SVC_MAX_CONTENT_LEN>,
}

impl EncodedAdvertisement {
    /// Returns the advertisement as a slice.
    pub fn as_slice(&self) -> &[u8] {
        self.adv.as_slice()
    }
    /// Converts this encoded advertisement into
    /// a raw byte-array.
    pub fn into_array_view(self) -> ArrayView<u8, BLE_5_ADV_SVC_MAX_CONTENT_LEN> {
        self.adv
    }
}

/// Trait for things which may provide a
/// [`DeType`] for a given instance. This is a slight
/// relaxation of [`HasDEType`] to data-types whose
/// associated DE type may be determined at run-time.
pub trait ProvidesDEType {
    /// Returns the DE type of this instance.
    fn de_type(&self) -> DeType;
}

impl<H: HasDEType> ProvidesDEType for H {
    fn de_type(&self) -> DeType {
        Self::DE_TYPE
    }
}

/// Writes data for a V1 DE into a provided buffer.
///
/// V1 data elements can be hundreds of bytes, so we ideally wouldn't maintain a buffer
/// big enough for that, hence an abstraction that writes into an existing buffer.
///
/// Implementors should ensure that the length of the contents to write are checked
/// to ensure that they fit within a DE before they are added to a section to ensure
/// that errors are surfaced early and accurately in client applications.
pub trait WriteDataElement: ProvidesDEType {
    /// The type of derived salts required to write this data element.
    /// Most likely to be just `Unsalted`, with occasional uses for `DeSalt`
    /// if the data element's payload requires additional cryptography.
    type Salt: Into<Option<DeSalt>>;

    /// Write just the contents of the DE, returning `Some` if all contents could be written and
    /// `None` otherwise.
    ///
    /// This method is allowed to leave the passed [`Sink`] in a dirty state
    /// for incomplete (failed) writes. The caller of this method must appropriately
    /// deal with such states.
    fn write_de_contents<S: Sink<u8>>(&self, salt: Self::Salt, sink: &mut S) -> Option<()>;

    /// Write the entire DE, including the header, returning `Some` if all contents could be
    /// written and `None` otherwise.
    ///
    /// This method is allowed to leave the passed [`Sink`] in a dirty state
    /// for incomplete (failed) writes. The caller of this method must appropriately
    /// deal with such states.
    fn write_de<S: Sink<u8>>(&self, salt: Self::Salt, sink: &mut S) -> Option<()> {
        // TODO: In some glorious future, we could develop an abstraction layer
        // where the underlying advertisement content array is actually slightly larger
        // than the requested size, so that we could write a fake max-length multi-byte
        // DE header first, then the contents of a DE. Then, after we do so, we could
        // write the contents of the DE and then "fix up" the header to possibly
        // use fewer bytes, and finally shift over the DE contents as needed.
        // For now, we use an auxiliary buffer instead of this more complex process,
        // which unfortunately means we use more memory, but we keep the interface
        // of this method using a `Sink<u8>` to allow for this improvement in the future.

        // First, write the DE contents into an internal buffer
        let mut de_contents: tinyvec::ArrayVec<[u8; MAX_DE_LEN]> = tinyvec::ArrayVec::new();
        self.write_de_contents(salt, &mut de_contents)?;
        let de_contents = de_contents;

        // Construct the DE header from what was written.
        let de_len = DeLength::try_from(de_contents.len())
            .expect("We made a buffer of the max DE contents len, so all contained lengths should be valid.");

        let de_header = DeHeader::new(self.de_type(), de_len);

        // Try to write the header followed by a copy of the DE contents.
        sink.try_extend_from_slice(de_header.serialize().as_slice())?;
        sink.try_extend_from_slice(de_contents.as_slice())
    }
}

/// Structure implementing [`WriteDataElement`]
/// for references to [`WriteDataElement`] instances.
pub struct RefWriteDataElement<'a, W>(&'a W);

impl<W: ProvidesDEType> ProvidesDEType for RefWriteDataElement<'_, W> {
    fn de_type(&self) -> DeType {
        self.0.de_type()
    }
}

impl<W: WriteDataElement> WriteDataElement for RefWriteDataElement<'_, W> {
    type Salt = <W as WriteDataElement>::Salt;
    fn write_de_contents<S: Sink<u8>>(&self, salt: Self::Salt, sink: &mut S) -> Option<()> {
        self.0.write_de_contents(salt, sink)
    }
}

/// Serialization-specific representation of a DE header
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct DeHeader {
    /// The length of the content of the DE
    len: DeLength,
    pub(crate) de_type: DeType,
}

impl DeHeader {
    /// Build a DeHeader from the provided type and length
    pub fn new(de_type: DeType, len: DeLength) -> Self {
        DeHeader { de_type, len }
    }

    /// Gets the DeType of this header.
    pub fn de_type(&self) -> DeType {
        self.de_type
    }

    /// Serialize the DE header as per the V1 DE header format:
    /// - 1 byte form for length <= 3 bits, type <= 4 bits: `0LLLTTTT`
    /// - multi byte form: `0b1LLLLLLL [0b1TTTTTTT ...] 0b0TTTTTTT`
    ///   - the shortest possible encoding must be used (no empty prefix type bytes)
    ///
    /// We assume that a 32-bit de type is sufficient, which would take at most 5 7-bit chunks to
    /// encode, resulting in a total length of 6 bytes with the initial length byte.
    pub(crate) fn serialize(&self) -> ArrayView<u8, 6> {
        let mut buffer = [0; 6];
        let de_type = self.de_type.as_u32();
        let hi_bit = 0x80_u8;
        let len = self.len.len;
        if !de_requires_extended_bit(de_type, len) {
            buffer[0] = len << 4 | de_type as u8;
            ArrayView::try_from_array(buffer, 1).expect("1 is a valid length")
        } else {
            // length w/ extended bit
            buffer[0] = hi_bit | len;

            // expand to a u64 so we can represent all 5 7-bit chunks of a u32, shifted so that
            // it fills the top 5 * 7 = 35 bits after the high bit, which is left unset so that
            // the MSB can be interpreted as a 7-bit chunk with an unset high bit.
            let mut type64 = (de_type as u64) << (64 - 35 - 1);
            let mut remaining_chunks = 5;
            let mut chunks_written = 0;
            // write 7 bit chunks, skipping leading 0 chunks
            while remaining_chunks > 0 {
                let chunk = type64.to_be_bytes()[0];
                remaining_chunks -= 1;

                // shift 7 more bits up, leaving the high bit unset
                type64 = (type64 << 7) & (u64::MAX >> 1);

                if chunks_written == 0 && chunk == 0 {
                    // skip leading all-zero chunks
                    continue;
                }

                buffer[1 + chunks_written] = chunk;
                chunks_written += 1;
            }
            if chunks_written > 0 {
                // fill in high bits for all but the last
                for byte in buffer[1..chunks_written].iter_mut() {
                    *byte |= hi_bit;
                }

                ArrayView::try_from_array(buffer, 1 + chunks_written).expect("length is at most 6")
            } else {
                // type byte is a leading 0 bit w/ 0 type, so use the existing 0 byte
                ArrayView::try_from_array(buffer, 2).expect("2 is a valid length")
            }
        }
    }
}

/// Wrapper around a fixed-size array which provides an `AsMut<[T]>` implementation
/// which gives out a fixed-position slice which excludes some fixed-length
/// prefix of the array and some fixed-length suffix. Useful to provide
/// a restricted, owned [`AsMut`] view into a slice of an array for which
/// elements outside the slice are treated as immutable.
///
/// Zero-length slices (as determined by the passed index range) are valid,
/// and can be interpreted as the underlying array being completely immutable
/// (`AsMut` will hand out an empty slice).
///
/// Indexing operations will panic if the range provided upon construction
/// does not correspond to a valid range of the wrapped data array.
#[derive(Debug)]
pub(crate) struct MutableSliceInArray<T, const N: usize> {
    mutable_range: core::ops::Range<usize>,
    data: [T; N],
}

impl<T, const N: usize> MutableSliceInArray<T, N> {
    /// Constructs a new wrapper around the given
    /// array for which only the given index range
    /// is exposed as mutable as part of the [`AsMut`] implementation.
    pub(crate) fn new_with_mutable_bounds(
        data: [T; N],
        mutable_range: core::ops::Range<usize>,
    ) -> Self {
        Self { data, mutable_range }
    }
    /// Constructs a new wrapper around the given
    /// array for which only the given index range
    /// is exposed as mutable as part of the [`AsMut`] implementation.
    pub(crate) fn new_with_immutable_prefix(
        data: [T; N],
        mutable_range: core::ops::RangeFrom<usize>,
    ) -> Self {
        Self { data, mutable_range: core::ops::Range { start: mutable_range.start, end: N } }
    }

    /// Gets the range of mutable indices.
    pub(crate) fn mutable_range(&self) -> core::ops::Range<usize> {
        self.mutable_range.clone()
    }

    /// De-structures this restricted-mutability array wrapper back into
    /// a regular array, dropping any restrictions on mutability.
    pub(crate) fn into_data(self) -> [T; N] {
        self.data
    }
}

impl<T, const N: usize> AsMut<[T]> for MutableSliceInArray<T, N> {
    fn as_mut(&mut self) -> &mut [T] {
        &mut self.data[self.mutable_range.clone()]
    }
}

/// Error raised when a chunk has an improper size for an operation
/// of [`SortedChunkSlice`].
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum ImproperChunkSizeError {
    /// The chunk is zero-length
    ZeroLength,
    /// The chunk is too long
    TooLong,
}

#[derive(Debug, Default, PartialEq, Eq, Clone, Copy)]
/// (N - 1) representation of the size of a chunk in a [`SortedChunkSlice`].
struct ChunkSize(u8);

impl TryFrom<usize> for ChunkSize {
    type Error = ImproperChunkSizeError;
    fn try_from(size: usize) -> Result<Self, Self::Error> {
        if size > 256 {
            Err(ImproperChunkSizeError::TooLong)
        } else if size == 0 {
            Err(ImproperChunkSizeError::ZeroLength)
        } else {
            Ok(Self((size - 1) as u8))
        }
    }
}

impl ChunkSize {
    fn size(&self) -> usize {
        (self.0 as usize) + 1
    }
}

/// Errors which may be raised from [`SortedChunkSlice#push`].
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum ChunkPushError {
    /// The slice to copy a chunk from was of an improper size.
    ImproperChunkSizeError(ImproperChunkSizeError),
    /// The capacity of the sorted array of chunks would be
    /// exceeded by attempting to push the given slice.
    CapacityExhaustedError,
    /// We cannot push the new chunk, because we have hit
    /// the limit on the allowable number of chunks.
    TooManyChunks,
}

impl From<ImproperChunkSizeError> for ChunkPushError {
    fn from(err: ImproperChunkSizeError) -> Self {
        Self::ImproperChunkSizeError(err)
    }
}

/// A container (+ metadata) of a mutable buffer managing contiguous "chunks"
/// of items within that buffer [possibly of differing lengths] with associated keys
/// which are mean to be kept ordered by key as new elements ("chunks") are added.
///
/// This structure is add-only, and only ever overwrites as much data in the wrapped
/// mutable slice as is consumed by the contiguous chunks managed by the structure.
///
/// The sizes of individual chunks are restricted to be between 1 and 256, inclusive.
///
/// To avoid logic errors, the buffer leveraged by this function should
/// NEVER have its associated `AsMut` implementation modify the size or extent
/// of the returned slice. Doing so may result in unexpected results and/or panics.
///
/// The type parameter `N` denotes the maximum number of chunks which may be tracked
/// by this structure.
#[derive(Debug)]
pub(crate) struct SortedChunkSlice<K: Ord + Default, T: Clone, S: AsMut<[T]>, const N: usize> {
    /// The actual contents of the underlying buffer of chunks.
    buffer: S,
    /// How many elements (not chunks) are currently being managed by this data
    /// structure. Equal to the sum of the chunk sizes in `chunk_metadata`, but
    /// useful for quickly finding the end of the managed region.
    consumed_buffer_len: usize,
    /// Metadata about each chunk (key, chunk size)
    chunk_metadata: tinyvec::ArrayVec<[(K, ChunkSize); N]>,
    /// Indicator to keep the `AsMut<[T]>` implementation constant, because
    /// we don't want that or the array type changing, otherwise the chunk sizes may
    /// no longer hold due to the returned slices being different.
    _marker: PhantomData<fn(&mut S) -> &mut [T]>,
}

impl<K: Ord + Default, T: Clone, S: AsMut<[T]>, const N: usize> SortedChunkSlice<K, T, S, N> {
    /// Constructs a new [`SortedChunkSlice`] from the given mutable buffer,
    /// with contents initially empty. Nothing is changed about the original buffer.
    pub(crate) fn new(buffer: S) -> Self {
        Self {
            buffer,
            consumed_buffer_len: 0,
            chunk_metadata: tinyvec::ArrayVec::new(),
            _marker: PhantomData,
        }
    }
    /// Returns the current number of chunks maintained by this structure.
    pub(crate) fn num_chunks(&self) -> usize {
        self.chunk_metadata.len()
    }

    /// Returns `true` if this structure has a chunk with the given key.
    pub(crate) fn contains_key(&self, query_key: &K) -> bool {
        // A linear scan is likely more efficient than binary search
        // for the (small) data sizes involved.
        for (key, _) in self.chunk_metadata.iter() {
            match key.cmp(query_key) {
                core::cmp::Ordering::Greater => {
                    return false;
                }
                core::cmp::Ordering::Equal => {
                    return true;
                }
                core::cmp::Ordering::Less => {
                    continue;
                }
            }
        }
        false
    }

    /// Helper which corrects a partially-updated version of this structure
    /// where all chunks are in order by key except for possibly the last one,
    /// but the structure is otherwise entirely valid.
    ///
    /// Will panic if the structure contains no chunks.
    fn fixup_last_chunk(&mut self) {
        // NOTE: This is likely not the most time-efficient way of doing this,
        // but that's not much of a problem for the intended applications,
        // since serialization is non-performance-critical. It could be
        // possible that there are better ways to lay out the data structure
        // while retaining similar space efficiency, but faster overall.

        // Extract the mutable slice reference to the underlying buffer,
        // we'll only ever deal with this (no std::mem::replace shenanigans.)
        let buffer = self.buffer.as_mut();

        // Pop the metadata of the last chunk so we can determine
        // where it goes. Note that the underlying buffer and the
        // tracked length have not changed!
        let (key, chunk_size) =
            self.chunk_metadata.pop().expect("No last chunk to fixup, why is this being called?");

        // Take the key and determine the destination "metadata index" (0-based)
        // of the chunk we're adding among all chunks in the buffer,
        // along with the index into the underlying buffer.
        // It's okay for this to be O(n), since the rest of this method
        // is anyway in the case where all elements need to move.
        let mut dest_metadata_index = 0;
        let mut dest_buffer_index = 0;
        for (other_key, other_chunk_size) in &self.chunk_metadata {
            // Every time we see that our key is bigger, move the tentative
            // destination down one chunk. Stop when this no longer holds.
            if &key > other_key {
                dest_metadata_index += 1;
                dest_buffer_index += other_chunk_size.size();
            } else {
                break;
            }
        }
        let dest_metadata_index = dest_metadata_index;
        let dest_buffer_index = dest_buffer_index;

        // Information-gathering complete, time to do the big rotate.
        // We leverage the "triple reversal rotation" mentioned
        // here: https://github.com/scandum/rotate.
        // We could do much better using the other techniques there,
        // but ideally we'd have a crate dependency which implements them.

        let last_chunk_begin_index = self.consumed_buffer_len - chunk_size.size();
        // Reverse the most-recently added chunk.
        buffer[last_chunk_begin_index..self.consumed_buffer_len].reverse();
        // Reverse everything before the most-recently-added
        // chunk which is after the destination index for the chunk.
        buffer[dest_buffer_index..last_chunk_begin_index].reverse();
        // Reverse the concatenation.
        buffer[dest_buffer_index..self.consumed_buffer_len].reverse();

        // Buffer elements are now in place, all that's left
        // is to correct the position of the metadata.
        //
        // Will not panic because we previously removed this element from
        // the metadata array at a different position.
        self.chunk_metadata.insert(dest_metadata_index, (key, chunk_size));
    }

    /// Attempts to write a new chunk into the buffer with the supplied
    /// writer. May fail with if the writer exhausts all available space, it
    /// writes zero or >256 chunk elements, or if writing the chunk would
    /// result in exceeding the maximum number of chunks storable in this structure.
    ///
    /// In case of failure, the contents of the underlying buffer up to the
    /// reported length (prior to this operation) of this object will
    /// remain unchanged, but the writer may overwrite bytes in the buffer
    /// beyond the end of this structure's stated length.
    pub(crate) fn write_chunk<W: SinkWriter<DataType = T>>(
        &mut self,
        key: K,
        writer: W,
    ) -> Result<(), ChunkPushError> {
        let previously_consumed_buffer_len = self.consumed_buffer_len;
        let buffer = self.buffer.as_mut();

        // Before we do anything wild, make sure that we could actually push
        // a new chunk to begin with.
        if self.chunk_metadata.len() == N {
            return Err(ChunkPushError::TooManyChunks);
        }

        // Write into our buffer.
        let mut sink = tinyvec::SliceVec::from_slice_len(buffer, self.consumed_buffer_len);
        match writer.write_payload(&mut sink) {
            Some(()) => {
                // Successful write, determine how much we wrote.
                let written_chunk_len = sink.len() - previously_consumed_buffer_len;

                // Make sure that the size of what we've written can be expressed for a chunk.
                // No big deal if this bails, since we haven't updated
                // any of our state variables other than adding extra
                // garbage bytes beyond the current cursor.
                let chunk_size = ChunkSize::try_from(written_chunk_len)?;

                // All looks good, update state variables.
                self.consumed_buffer_len = sink.len();
                self.chunk_metadata.push((key, chunk_size));

                // Fix ordering and return.
                self.fixup_last_chunk();
                Ok(())
            }
            None => {
                // Unsuccessful write. Thankfully, we haven't updated any
                // of our state other than adding extra garbage bytes beyond
                // the current cursor, and so we can just return.
                Err(ChunkPushError::CapacityExhaustedError)
            }
        }
    }

    /// Attempts to insert a new chunk into the buffer, reordering
    /// existing chunks as needed to ensure that chunks remain sorted
    /// by key. May fail due to the underlying buffer capacity being
    /// exhausted or due to the source slice being of an improper size
    /// (zero or >256 elements) or due to reaching the maximum number
    /// of chunks storable in this structure.
    ///
    /// In case of failure, the contents of the underlying buffer will
    /// remain unchanged.
    pub(crate) fn push_slice(&mut self, key: K, chunk: &[T]) -> Result<(), ChunkPushError> {
        // Extract the mutable slice reference to the underlying buffer,
        // we'll only ever deal with this (no std::mem::replace shenanigans.)
        let buffer = self.buffer.as_mut();

        // Before doing anything, make sure the chunk is the right
        // size and that we won't run out of space by pushing it.
        if self.chunk_metadata.len() == N {
            return Err(ChunkPushError::TooManyChunks);
        }

        let chunk_size = ChunkSize::try_from(chunk.len())?;

        if self.consumed_buffer_len + chunk.len() > buffer.len() {
            return Err(ChunkPushError::CapacityExhaustedError);
        }

        // Append the chunk (ordering is fixed later).
        self.chunk_metadata.push((key, chunk_size));
        buffer[self.consumed_buffer_len..(self.consumed_buffer_len + chunk.len())]
            .clone_from_slice(chunk);
        self.consumed_buffer_len += chunk.len();

        // Fix ordering and return.
        self.fixup_last_chunk();
        Ok(())
    }

    /// Gets the total length of all chunks consumed via this structure
    /// in the underlying buffer.
    pub(crate) fn len(&self) -> usize {
        self.consumed_buffer_len
    }
    /// Deconstructs this sorted slice containing chunks into just the raw
    /// buffer contents, including any sorted chunks that we've built,
    /// but discarding any keys or other metadata.
    ///
    /// Note that this will yield the _entire_ buffer, not the portion
    /// of the buffer which was populated using this data-structure.
    ///
    /// To truncate appropriately [if necessary], leverage the `len()`
    /// method on this structure.
    pub(crate) fn finish(self) -> S {
        self.buffer
    }
}

/// A wrapper around a fixed-size tinyvec that can have its capacity further constrained to handle
/// dynamic size limits.
#[derive(Debug)]
pub(crate) struct CapacityLimitedVec<T, const N: usize>
where
    T: fmt::Debug + Clone,
    [T; N]: tinyvec::Array + fmt::Debug,
    <[T; N] as tinyvec::Array>::Item: fmt::Debug + Clone,
{
    /// constraint on the occupied space in `vec`.
    /// Invariant: `vec.len() <= capacity` and `vec.capacity() >= capacity`.
    capacity: usize,
    vec: tinyvec::ArrayVec<[T; N]>,
}

impl<T, const N: usize> CapacityLimitedVec<T, N>
where
    T: fmt::Debug + Clone,
    [T; N]: tinyvec::Array + fmt::Debug,
    <[T; N] as tinyvec::Array>::Item: fmt::Debug + Clone,
{
    /// Returns `None` if `capacity > N`
    pub(crate) fn new(capacity: usize) -> Option<Self> {
        if capacity <= N {
            Some(Self { capacity, vec: tinyvec::ArrayVec::new() })
        } else {
            None
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.vec.len()
    }

    fn capacity(&self) -> usize {
        self.capacity
    }

    pub(crate) fn into_inner(self) -> tinyvec::ArrayVec<[T; N]> {
        self.vec
    }
}

impl<T, const N: usize> Sink<<[T; N] as tinyvec::Array>::Item> for CapacityLimitedVec<T, N>
where
    T: fmt::Debug + Clone,
    [T; N]: tinyvec::Array + fmt::Debug,
    <[T; N] as tinyvec::Array>::Item: fmt::Debug + Clone,
{
    fn try_extend_from_slice(&mut self, items: &[<[T; N] as tinyvec::Array>::Item]) -> Option<()> {
        if items.len() > (self.capacity() - self.len()) {
            return None;
        }
        // won't panic: just checked the length
        self.vec.extend_from_slice(items);
        Some(())
    }

    fn try_push(&mut self, item: <[T; N] as tinyvec::Array>::Item) -> Option<()> {
        if self.len() == self.capacity() {
            // already full
            None
        } else {
            self.vec.push(item);
            Some(())
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use alloc::vec::Vec;
    use rand::prelude::SliceRandom;
    use rand::rngs::StdRng;
    use rand::{Rng, SeedableRng};

    extern crate std;

    #[test]
    fn backwards_insert_sorted_chunks_array() {
        let mut result: SortedChunkSlice<usize, _, _, 5> = SortedChunkSlice::new([0u8; 50]);
        result.push_slice(5, &[5, 4, 3, 2, 1]).unwrap();
        result.push_slice(4, &[7, 6]).unwrap();
        result.push_slice(3, &[10, 9, 8]).unwrap();
        result.push_slice(2, &[15, 14, 13, 12, 11]).unwrap();
        result.push_slice(1, &[16]).unwrap();
        let len = result.len();
        let result = result.finish();
        assert_eq!(&result[..len], &[16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1]);
    }
    #[test]
    fn insert_empty_slice_sorted_chunks_array() {
        let mut result: SortedChunkSlice<u8, _, _, 1> = SortedChunkSlice::new([0u8; 10]);
        let empty_slice: &[u8] = &[];
        let err = result.push_slice(1, empty_slice).expect_err("Empty slices should be disallowed");
        assert_eq!(err, ChunkPushError::ImproperChunkSizeError(ImproperChunkSizeError::ZeroLength));
        let len = result.len();
        let result = result.finish();
        assert_eq!(&result[..len], empty_slice);
    }
    #[test]
    fn capacity_exhausted_cancels_write_sorted_chunks_array() {
        let mut result: SortedChunkSlice<u8, _, _, 2> = SortedChunkSlice::new([0u8; 3]);
        result.push_slice(2, &[0xFF, 0xEE]).unwrap();
        let err = result
            .push_slice(1, &[0xDD, 0xCC])
            .expect_err("Attempting to push too many elements should fail.");
        assert_eq!(err, ChunkPushError::CapacityExhaustedError);
        let len = result.len();
        let result = result.finish();
        assert_eq!(&result[..len], &[0xFF, 0xEE]);
    }
    #[test]
    fn too_many_chunks_cancels_write() {
        let mut result: SortedChunkSlice<u8, _, _, 1> = SortedChunkSlice::new([0u8; 3]);
        result.push_slice(2, &[0xFF, 0xEE]).unwrap();
        let err = result
            .push_slice(1, &[0xDD, 0xCC])
            .expect_err("Attempting to push too many chunks should fail.");
        assert_eq!(err, ChunkPushError::TooManyChunks);
        let len = result.len();
        let result = result.finish();
        assert_eq!(&result[..len], &[0xFF, 0xEE]);
    }
    #[test]
    fn chunk_size_out_of_range_sorted_chunks_array() {
        let mut result: SortedChunkSlice<u8, _, _, 2> = SortedChunkSlice::new([0u8; 600]);
        result.push_slice(2, &[0xFF; 256]).expect("256 is an okay chunk length.");
        let err =
            result.push_slice(1, &[0xEE; 257]).expect_err("len 257 is too large for a chunk.");
        assert_eq!(err, ChunkPushError::ImproperChunkSizeError(ImproperChunkSizeError::TooLong));
        let len = result.len();
        let result = result.finish();
        assert_eq!(&result[..len], &[0xFF; 256]);
    }

    /// Simple [`SinkWriter`] implementation
    /// which pushes bytes from the given slice
    /// one at a time.
    struct ByteSliceSinkWriter<'a> {
        slice: &'a [u8],
    }

    impl SinkWriter for ByteSliceSinkWriter<'_> {
        type DataType = u8;
        fn write_payload<S: Sink<u8>>(self, sink: &mut S) -> Option<()> {
            for byte in self.slice {
                sink.try_push(*byte)?;
            }
            Some(())
        }
    }
    #[test]
    fn slice_pushing_matches_writer_pushing() {
        let mut rng = StdRng::from_entropy();
        for _ in 0..10_000 {
            let num_chunks: usize = rng.gen_range(1..=16);
            let chunk_max_len: usize = rng.gen_range(1..=16);

            // Deliberately picking 15 max chunks to check
            // the "full" edge-case, and deliberately
            // picking a 128-byte capacity so that it will
            // be exceeded in some test-cases.
            let mut slices_pushed: SortedChunkSlice<u16, _, _, 15> =
                SortedChunkSlice::new([0u8; 128]);
            let mut writers_pushed: SortedChunkSlice<u16, _, _, 15> =
                SortedChunkSlice::new([0u8; 128]);

            for _ in 0..num_chunks {
                let key: u16 = rng.gen();
                let chunk_len: usize = rng.gen_range(1..=chunk_max_len);
                let mut value = Vec::new();
                for _ in 0..chunk_len {
                    value.push(rng.gen());
                }

                let slice_push_result = slices_pushed.push_slice(key, &value);
                let writer = ByteSliceSinkWriter { slice: &value };
                let writer_push_result = writers_pushed.write_chunk(key, writer);
                assert_eq!(slice_push_result, writer_push_result);
            }
            // Check that the contents of the two SortedChunkSlices
            // are the same up to their stated written length.
            let slices_len = slices_pushed.len();
            let writers_len = writers_pushed.len();
            let slices_pushed = slices_pushed.finish();
            let writers_pushed = writers_pushed.finish();
            assert_eq!(&slices_pushed[..slices_len], &writers_pushed[..writers_len]);
        }
    }

    // TODO: Add test that the writer variant is equivalent to
    // the slice variant for adding chunks.

    #[test]
    // The ranged loop at the end _is_ necessary, since it doesn't
    // have the same semantics w.r.t. panics as what the iterator solution
    // would accomplish.
    #[allow(clippy::needless_range_loop)]
    fn randomized_chunk_sorting() {
        let mut rng = StdRng::from_entropy();
        for _ in 0..10_000 {
            let mut runs: Vec<Vec<usize>> = Vec::new();
            let mut counter: usize = 0;
            // Create up to 2-16 randomly-split runs of 1-16 elements
            // each, where the underlying counter always increases.
            let num_runs = rng.gen_range(2..=16);
            for _ in 0..num_runs {
                let run_len = rng.gen_range(1..=16);
                let mut run = Vec::new();
                for _ in 0..run_len {
                    run.push(counter);
                    counter += 1;
                }
                runs.push(run);
            }
            // Gather how many elements were inserted, since
            // we'll be using this to ensure that the vector
            // gets sorted in an increasing order.
            let total_num_elements = counter;

            // Randomize the insertion order.
            runs.shuffle(&mut rng);

            let mut result: SortedChunkSlice<usize, _, _, 16> =
                SortedChunkSlice::new([0usize; 256]);

            // Insert the runs into the sorted chunks array, with the key
            // set as the first element.
            for run in runs.drain(..) {
                let key = run[0];
                result
                    .push_slice(key, run.as_slice())
                    .expect("We should be able to push this many slices of this size.");
            }

            let result = result.finish();

            // Verify that we have a contiguous vector
            // counting up to `total_num_elements`.
            for i in 0..total_num_elements {
                assert_eq!(result[i], i);
            }
        }
    }
}
