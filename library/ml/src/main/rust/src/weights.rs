//! The `.maml` reader.
//!
//! `.maml` is a **weights container only** — ordered tensors, no operators and no
//! topology — because each network's forward pass is hardcoded in [`crate::nets`]
//! rather than interpreted. See `scripts/ml/maml_convert.py`, which writes it, for
//! the byte layout; this is the other half of that contract.
//!
//! Two consequences worth stating plainly:
//!
//! * Tensor **order** is the whole interface. A reordering loads cleanly and infers
//!   nonsense, which is why the converter pins a SHA-256 over the ordered layer
//!   table and why [`Weights::graph_id`] is checked before a net will touch a file.
//! * Nothing here allocates or copies the tensor data. The whole file is uploaded to
//!   one `VkBuffer` verbatim, so a [`Tensor`]'s offset is simultaneously its offset
//!   in the file and its offset in device memory. That is the reason the format is
//!   one contiguous blob.

use std::fs::File;

use crate::preprocess::f16_to_f32;

/// `b"MAML"`, little-endian, at offset 0.
const MAGIC: [u8; 4] = *b"MAML";
/// Bumped when the layout below changes incompatibly.
const FORMAT_VERSION: u32 = 1;
const HEADER_BYTES: usize = 64;
const TENSOR_ENTRY_BYTES: usize = 32;
const DTYPE_F16: u32 = 0;

/// Signed 8-bit, with a **separate** scale tensor beside it.
///
/// Used only where the weights dominate the download and fp16 would double it: SMaLL-100 is
/// 330 million parameters, which is 660 MB at fp16 and 330 MB here.
///
/// The quantisation is symmetric — zero point 0 — and the scale is per output channel, both read
/// from the export rather than assumed. So a value is `int8 * scale`, and because the scale
/// applies to a whole output row it multiplies the *accumulator* once rather than every tap. The
/// scale lives in its own fp16 tensor because a table entry is already 32 bytes with no room
/// for it, and a companion tensor needs no format version bump.
///
/// Activations stay fp16. The export quantises them too, dynamically, per tensor; not doing
/// that is both simpler and strictly more accurate, and it saves nothing to copy since the
/// weights are what take the space.
const DTYPE_I8: u32 = 1;
/// Every tensor starts on this boundary, so an offset is a valid fp16 index too.
const ALIGNMENT: u32 = 16;

/// Graph ids, shared with `GRAPHS` in `scripts/ml/maml_convert.py`.
pub mod graph {
    /// MediaPipe Selfie Segmentation, 256x256.
    pub const SELFIE: u32 = 1;
    /// U^2-Net portable, 320x320.
    pub const U2NETP: u32 = 2;
    /// SCRFD 500M face detection, 640 on the long side.
    pub const SCRFD: u32 = 3;
    /// MobileFaceNet face embedding, 112x112 in, 512-d out.
    pub const MOBILEFACENET: u32 = 4;
    /// PP-OCRv5 mobile text detection, 960 on the long side.
    pub const PPOCR_DET: u32 = 5;
    /// PP-OCRv5 mobile text recognition, 48 tall.
    pub const PPOCR_REC: u32 = 6;
    // 7..10 were Piper's four VITS graphs, deleted when Supertonic replaced it. The numbers are
    // **not** reused: an id identifies a forward pass, and a `.maml` built for the old vocoder
    // must be rejected rather than loaded as whatever took its slot.
    /// Supertonic 3's ConvNeXt vocoder. See [`crate::nets::supertonic_vocoder`].
    pub const SUPERTONIC_VOC: u32 = 11;
    /// Supertonic 3's duration predictor. See [`crate::nets::supertonic_duration`].
    pub const SUPERTONIC_DP: u32 = 12;
    /// Supertonic 3's text encoder. See [`crate::nets::supertonic_text`].
    pub const SUPERTONIC_TTL: u32 = 13;
    /// Supertonic 3's flow-matching sampler. See [`crate::nets::supertonic_sampler`].
    pub const SUPERTONIC_VE: u32 = 14;
    /// SMaLL-100 translation, encoder and decoder in one file. See `crate::nets::small100`.
    ///
    /// One graph rather than two because the 128,112-row embedding is **tied**: it is the encoder's
    /// input table, the decoder's input table and the logits kernel, and two files would upload
    /// 125 MiB of it twice.
    pub const SMALL100: u32 = 15;
    /// TinyCLIP-ViT-8M/16 Text-3M, both towers in one file. See [`crate::nets::tinyclip`].
    ///
    /// One graph rather than two because the towers share a file and a [`super::Weights`] upload,
    /// even though they share no weights: `Mode::Image` and `Mode::Text` are two plans over one net.
    pub const TINYCLIP: u32 = 16;
}

/// One tensor's entry in the table: where it is and what shape it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Tensor {
    /// 1..=4 significant entries of [`Tensor::dims`].
    pub rank: u32,
    /// Trailing entries beyond `rank` are zero.
    pub dims: [u32; 4],
    /// Byte offset from the start of the data section.
    pub offset: u32,
    /// Elements, not bytes.
    pub len: u32,
    /// True when the payload is [`DTYPE_I8`] rather than fp16, so one byte per element.
    pub int8: bool,
}

impl Tensor {
    /// The offset as an fp16 **element** index, which is how the shaders address it.
    pub fn elem_offset(&self) -> u32 {
        self.offset / 2
    }

    /// The offset as a 32-bit **word** index, which is how an int8 tensor is addressed.
    ///
    /// Int8 weights are read through a `uint` view of the same buffer and unpacked four at a
    /// time, because a byte view would need `VK_KHR_8bit_storage` — an extension on top of the
    /// fp16 one this runtime already requires, and every extra requirement narrows the fleet.
    /// `ALIGNMENT` is 16, so a tensor always starts on a word boundary.
    pub fn word_offset(&self) -> u32 {
        self.offset / 4
    }
}

/// A `.maml`'s data section, readable a piece at a time.
///
/// [`crate::vulkan::run::Net::new`] needs the data section and nothing else: the [`Plan`] it is
/// handed already carries every resolved offset. It does *not* need the section in one host
/// allocation, and for a bundled Supertonic it must not — the current path allocates the model
/// three times over, as a Java `byte[]`, as the `Vec<u8>` JNI hands Rust, and as [`Weights`]'s own
/// copy. At the ~105 MB an int8 bundle comes to, that is ~300 MB of transient heap and an
/// out-of-memory kill on a low-RAM device.
///
/// So the upload asks for ranges instead, and two implementations answer: [`Weights`], which has
/// the bytes already, and [`Streamed`], which leaves them in the APK and reads them positionally.
/// Neither is faster than the other in device time — both end up doing the same
/// `cmd_copy_buffer`s — and the second has a peak host cost of one chunk.
///
/// [`Plan`]: crate::nets::Plan
pub trait Blob {
    /// Bytes in the data section.
    fn data_len(&self) -> u64;

    /// The tensor table, so `vulkan::segment` can find the extent of every tensor an op reads.
    ///
    /// Nothing about *running* a net needs the table - a [`Plan`] carries every resolved offset,
    /// which is what makes [`Weights`] and [`Streamed`] interchangeable. Segmenting the weights
    /// buffer does: it has to know where each tensor ends to choose a boundary that does not fall
    /// inside one, and only the table says that.
    fn tensors(&self) -> &[Tensor];

    /// Fill `into` from `offset` bytes into the data section.
    ///
    /// A short read is an error rather than a partial fill: every caller here knows exactly how
    /// many bytes it wants, and silently uploading a half-read chunk would leave one tensor of a
    /// net holding whatever the buffer was allocated with.
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String>;
}

/// A `.maml` header and tensor table, without the data section.
///
/// Parsed by [`parse_header`] from the first `HEADER_BYTES + count * 32` bytes of a file, which is
/// a few kilobytes for even the largest net here. Both [`Weights::parse`] and [`Streamed::open`]
/// go through it, so there is one implementation of the format's bounds checks rather than two
/// that have to agree.
struct Header {
    graph_id: u32,
    source_sha256: [u8; 32],
    tensors: Vec<Tensor>,
    data_offset: usize,
    data_len: usize,
}

/// Parse the header and tensor table out of `bytes`, which must reach at least the table's end.
///
/// Every offset and length in the table is bounds-checked here rather than at use, so a truncated
/// or hand-edited asset fails at load with a message instead of dispatching a shader that reads
/// past the end of a device buffer — where the symptom would be a driver reset, not an error.
///
/// The one thing this does *not* check is that the file ends where the data section does: a
/// [`Streamed`] read only has the prefix in hand, and an asset inside an APK is followed by the
/// next asset. [`Weights::parse`] checks it separately, because there the whole file is the slice
/// and a length mismatch means a truncated download.
fn parse_header(bytes: &[u8], expect_graph: u32) -> Result<Header, String> {
    if bytes.len() < HEADER_BYTES {
        return Err(format!("{} bytes is shorter than a .maml header", bytes.len()));
    }
    if bytes[0..4] != MAGIC {
        return Err("not a .maml file (bad magic)".into());
    }
    let version = u32(bytes, 4);
    if version != FORMAT_VERSION {
        return Err(format!("format version {version}, expected {FORMAT_VERSION}"));
    }
    let graph_id = u32(bytes, 8);
    if graph_id != expect_graph {
        return Err(format!(
            "this file is for graph {graph_id}, but graph {expect_graph} asked for it"
        ));
    }
    let count = u32(bytes, 12) as usize;
    let mut source_sha256 = [0u8; 32];
    source_sha256.copy_from_slice(&bytes[16..48]);
    let data_offset = u32(bytes, 48) as usize;
    let data_len = u32(bytes, 52) as usize;

    let table_bytes = count
        .checked_mul(TENSOR_ENTRY_BYTES)
        .ok_or_else(|| format!("{count} tensors overflows the table size"))?;
    if data_offset != HEADER_BYTES + table_bytes {
        return Err(format!(
            "data starts at {data_offset}, but {count} tensors put it at {}",
            HEADER_BYTES + table_bytes
        ));
    }
    data_offset
        .checked_add(data_len)
        .ok_or_else(|| "data section overflows".to_string())?;
    if bytes.len() < data_offset {
        return Err(format!(
            "{} bytes does not reach the end of a {count}-tensor table at {data_offset}",
            bytes.len()
        ));
    }

    let mut tensors = Vec::with_capacity(count);
    for i in 0..count {
        let at = HEADER_BYTES + i * TENSOR_ENTRY_BYTES;
        let rank = u32(bytes, at);
        if rank == 0 || rank > 4 {
            return Err(format!("tensor {i} has rank {rank}"));
        }
        let dims =
            [u32(bytes, at + 4), u32(bytes, at + 8), u32(bytes, at + 12), u32(bytes, at + 16)];
        let dtype = u32(bytes, at + 20);
        let stride = match dtype {
            DTYPE_F16 => 2u64,
            DTYPE_I8 => 1,
            other => return Err(format!("tensor {i} has dtype {other}, expected fp16 or int8")),
        };
        let offset = u32(bytes, at + 24);
        let len = u32(bytes, at + 28);

        let expected: u64 = dims[..rank as usize].iter().map(|&d| d as u64).product();
        if expected != len as u64 {
            return Err(format!("tensor {i} has dims {dims:?} but len {len}"));
        }
        if !offset.is_multiple_of(ALIGNMENT) {
            return Err(format!("tensor {i} is at {offset}, not {ALIGNMENT}-aligned"));
        }
        let end = (offset as u64) + (len as u64) * stride;
        if end > data_len as u64 {
            return Err(format!(
                "tensor {i} spans {offset}..{end} of a {data_len}-byte data section"
            ));
        }
        tensors.push(Tensor { rank, dims, offset, len, int8: dtype == DTYPE_I8 });
    }
    Ok(Header { graph_id, source_sha256, tensors, data_offset, data_len })
}

/// The tensor table on its own, without the data section.
///
/// [`crate::vulkan::run::Net::rebuild`] re-records a net at a new shape, and that means building a
/// fresh [`crate::nets::Plan`] — which needs a [`crate::nets::WeightSource`]. It does not need the
/// blob: a builder only ever consults offsets and shapes, and the blob is already in device memory
/// by then. Holding a whole [`Weights`] alive to rebuild from would keep 127 MB of host RSS for the
/// Supertonic sampler alone, beside the copy the device has, where the table is a few kilobytes.
///
/// So a caller that means to rebuild takes one of these first and lets the `Weights` go.
#[derive(Clone, Debug)]
pub struct Offsets {
    tensors: Vec<Tensor>,
}

impl Offsets {
    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.tensors
            .get(index)
            .copied()
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.tensors.len()))
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        let tensor = self.tensor(index)?;
        let got = &tensor.dims[..tensor.rank as usize];
        if got != dims {
            return Err(format!("tensor {index} is {got:?}, the forward pass wants {dims:?}"));
        }
        Ok(tensor)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.tensors.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.tensors.is_empty()
    }
}

/// A parsed `.maml` file: the tensor table, and the data section to upload.
#[derive(Debug)]
pub struct Weights {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    data: Vec<u8>,
}

impl Weights {
    /// Parse `bytes`, rejecting anything not built for `expect_graph`.
    ///
    /// The header and table go through `parse_header`, which does every bounds check; the one
    /// thing added here is that the file must end exactly where its data section does, since with
    /// the whole slice in hand a mismatch means a truncated or padded download.
    pub fn parse(bytes: &[u8], expect_graph: u32) -> Result<Weights, String> {
        let header = parse_header(bytes, expect_graph)?;
        let data_end = header.data_offset + header.data_len;
        if data_end != bytes.len() {
            return Err(format!(
                "data section ends at {data_end} but the file is {} bytes",
                bytes.len()
            ));
        }
        let data = bytes.get(header.data_offset..).unwrap_or(&[]).to_vec();
        Ok(Weights {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            data,
        })
    }

    /// The fp16 blob to upload, verbatim.
    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// A [`Weights`] that is nothing but a data section, for the device-parity fixtures.
    ///
    /// [`crate::vulkan::run::Net::new`] reads no part of a [`Weights`] except [`data`], because
    /// the [`crate::nets::Plan`] it is handed already carries every resolved offset. So a
    /// fixture that built its plan against a test [`WeightSource`] can give the device the same
    /// blob without assembling a header and a tensor table that nothing would ever read.
    ///
    /// [`data`]: Weights::data
    #[cfg(test)]
    pub(crate) fn from_data(data: Vec<u8>) -> Weights {
        let table = Offsets { tensors: Vec::new() };
        Weights { graph_id: 0, source_sha256: [0u8; 32], table, data }
    }

    /// The tensor table alone, for a caller that will rebuild a plan after this `Weights` is
    /// gone. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }

    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.table.tensor(index)
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        self.table.shaped(index, dims)
    }
}

impl Blob for Weights {
    fn data_len(&self) -> u64 {
        self.data.len() as u64
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = usize::try_from(offset).map_err(|_| "a data offset overflowed usize")?;
        let end = start
            .checked_add(into.len())
            .ok_or("a data range overflowed")?;
        let from = self
            .data
            .get(start..end)
            .ok_or_else(|| format!("{start}..{end} of a {}-byte data section", self.data.len()))?;
        into.copy_from_slice(from);
        Ok(())
    }
}

/// A `.maml` whose table is in memory and whose data section is still in a file.
///
/// The counterpart of [`Weights`], and the whole of what bundling Supertonic needs: its table is a
/// few kilobytes, and its data section is the ~105 MB that must not be resident three times over.
/// See [`Blob`] for why that mattered enough to add a second implementation.
///
/// # Not `mmap`
///
/// `memmap2` was deliberately removed from this repo, and nothing here needs it back. A mapping
/// would let the upload read the data as a slice, but the upload does not want a slice: it wants
/// to hand fixed-size pieces to a staging buffer, and a positional read does that with no
/// unsafety, no dependency, and no page-fault behaviour to reason about on an unknown filesystem.
///
/// # The offset, and why it is not always zero
///
/// An asset inside an APK is a *range* of the APK, so `AssetManager.openFd` returns a descriptor
/// alongside a `startOffset` and a `length` rather than a file of its own. This holds that range
/// and adds it to every read, so a bundled asset and a downloaded file are one code path. It also
/// means `openFd` must succeed, which is what `noCompress += "maml"` in the app's Gradle
/// configuration is load-bearing for: `openFd` throws for a deflated asset.
pub struct Streamed {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    file: File,
    /// Byte offset of the **data section** within `file`, i.e. the asset's own start plus the
    /// header and table. Every [`Blob::read_at`] adds it, so callers index the data section.
    data_at: u64,
    data_len: u64,
}

impl Streamed {
    /// Read the header and table of the `.maml` occupying `at..at + len` of `file`.
    ///
    /// Only the prefix is read — the header plus the tensor table, a few kilobytes — so this costs
    /// nothing whatever the size of the net. `file` is retained; the data section is read later, by
    /// the upload.
    ///
    /// `len` is what the caller was told the asset is, and is checked against the header rather
    /// than trusted: a `.maml` claiming a data section past the end of its own range would
    /// otherwise be caught only by the first read that ran off the end, or not at all if a
    /// neighbouring asset happened to follow it.
    pub fn open(file: File, at: u64, len: u64, expect_graph: u32) -> Result<Streamed, String> {
        // A generous prefix read: one page covers the header and a 128-tensor table, and the
        // largest net here has 605. Reading `min(len, PREFIX)` rather than exactly the table means
        // one syscall instead of two, since the table's size is in the header being read.
        const PREFIX: u64 = 64 * 1024;
        let want = usize::try_from(len.min(PREFIX)).map_err(|_| "a .maml prefix overflowed")?;
        let mut prefix = vec![0u8; want];
        read_exact_at(&file, &mut prefix, at)
            .map_err(|e| format!("reading a .maml header at {at}: {e}"))?;
        let header = parse_header(&prefix, expect_graph)?;
        let data_at = header.data_offset as u64;
        let end = data_at
            .checked_add(header.data_len as u64)
            .ok_or("data section overflows")?;
        if end > len {
            return Err(format!(
                "the data section ends at {end} but this .maml is only {len} bytes"
            ));
        }
        Ok(Streamed {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            file,
            data_at: at + data_at,
            data_len: header.data_len as u64,
        })
    }

    /// The tensor table alone, for building and rebuilding plans. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    ///
    /// Each read is a positional read of a few kilobytes out of the APK, which is what makes this
    /// affordable: the sampler's host block is 0.44% of its 127 MB.
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }
}

impl Blob for Streamed {
    fn data_len(&self) -> u64 {
        self.data_len
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let end = offset
            .checked_add(into.len() as u64)
            .ok_or("a data range overflowed")?;
        if end > self.data_len {
            return Err(format!(
                "{offset}..{end} of a {}-byte data section",
                self.data_len
            ));
        }
        read_exact_at(&self.file, into, self.data_at + offset)
            .map_err(|e| format!("reading {} bytes at {offset}: {e}", into.len()))
    }
}

/// A tensor table and the bytes it indexes, for the reads the host does itself.
///
/// A handful of Supertonic's tensors never reach a shader: the sampler's timestep MLP depends on
/// the step number, its rotary `theta` on the sequence length, and its folded style keys are
/// inputs to other nets. `post::supertonic` reads those on the host, and needed a [`Weights`] to
/// do it — which is exactly the 105 MB allocation the bundled path exists to avoid.
///
/// So it takes one of these instead. Both halves come from the same file either way; keeping them
/// as one argument rather than two means a caller cannot pair one net's table with another's bytes.
#[derive(Clone, Copy)]
pub struct Reader<'a> {
    table: &'a Offsets,
    data: &'a dyn Blob,
}

impl<'a> Reader<'a> {
    /// A reader over `table` and `data`, which must describe the same file.
    pub fn new(table: &'a Offsets, data: &'a dyn Blob) -> Reader<'a> {
        Reader { table, data }
    }

    /// Tensor `index` as `f32`, in the file's order, checked against `dims`.
    ///
    /// fp16 only: every tensor the host reads is fp16, and an int8 one would need its companion
    /// scale, which is a caller's decision rather than something to guess at here.
    pub fn fp16(&self, index: usize, dims: &[u32]) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.int8 {
            return Err(format!("tensor {index} is int8, and the host reads fp16"));
        }
        let mut bytes = vec![0u8; (found.len as usize) * 2];
        self.data
            .read_at(found.offset as u64, &mut bytes)
            .map_err(|e| format!("tensor {index}: {e}"))?;
        Ok(bytes
            .chunks_exact(2)
            .map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]])))
            .collect())
    }

    /// One row of an int8 tensor, dequantised by that row's scale.
    ///
    /// The counterpart of [`Reader::fp16`] for a quantised table, and it exists for one caller:
    /// [`crate::nets::small100`] gathers rows of the tied embedding on the host rather than in a
    /// shader. Doing so removes the need for an int8 `embed.comp`, lets `sqrt(d_model)` and the
    /// sinusoidal position be applied in f32 before anything is rounded, and reads 1 KB per token
    /// instead of uploading a 125 MiB table a second time.
    ///
    /// A row rather than the whole tensor because the whole tensor is 125 MiB. `dims[0]` is the
    /// row count and a row is contiguous, which is a property of the `[out, in, 1, 1]` layout
    /// `scripts/ml/maml_convert.py` writes rather than an assumption — and `shaped` checks it.
    pub fn int8_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if !found.int8 {
            return Err(format!("tensor {index} is fp16, and this dequantises int8"));
        }
        let rows = *dims.first().ok_or("an int8 row read needs a row count")?;
        if row >= rows {
            return Err(format!("row {row} of a {rows}-row tensor {index}"));
        }
        let stride = (found.len / rows) as usize;
        let mut bytes = vec![0u8; stride];
        let at = u64::from(found.offset) + u64::from(row) * stride as u64;
        self.data.read_at(at, &mut bytes).map_err(|e| format!("tensor {index} row {row}: {e}"))?;

        let scale = self.table.shaped(scale_index, &[rows])?;
        if scale.int8 {
            return Err(format!("tensor {scale_index} is int8, and a scale is fp16"));
        }
        let mut half = [0u8; 2];
        self.data
            .read_at(u64::from(scale.offset) + u64::from(row) * 2, &mut half)
            .map_err(|e| format!("tensor {scale_index} row {row}: {e}"))?;
        let scale = f16_to_f32(u16::from_le_bytes(half));
        Ok(bytes.iter().map(|&b| f32::from(b as i8) * scale).collect())
    }
}

/// Fill `buf` from `offset` without moving the file's cursor.
///
/// The same helper as `library/tilecodec`'s `pmtiles::read_exact_at`, and copied rather than shared
/// because these two crates have no dependency between them and this is six lines. Both platforms
/// expose a positional read; neither is guaranteed to return everything at once, hence the loop.
///
/// Positional rather than seek-then-read because the cursor is shared with whatever else holds this
/// descriptor — on Android the descriptor came out of `AssetManager`, and moving its cursor is not
/// this module's business.
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "the file ended mid-tensor",
            ));
        }
        buf = buf.get_mut(n..).unwrap_or(&mut []);
        offset += n as u64;
    }
    Ok(())
}

/// One tensor for [`write_mixed`]: fp16 values, or the int8 payload of a quantised kernel.
#[cfg(test)]
pub(crate) enum Fixture {
    /// fp16, the format every tensor but a quantised kernel is in.
    F16(Vec<u32>, Vec<f32>),
    /// int8, addressed by the shaders as a 32-bit word offset. See [`Tensor::word_offset`].
    I8(Vec<u32>, Vec<i8>),
}

/// Build a `.maml` blob from a mix of fp16 and int8 tensors, for the fixtures.
///
/// The `write` helper in this module's tests emits fp16 only, and an int8 convolution needs a table
/// where one tensor is int8 and the two after it — its per-channel scale and its bias — are not.
/// Shared rather than hand-rolled per test because the 16-byte alignment and the resulting word
/// offsets are precisely what a second copy would get subtly wrong, and a wrong offset here reads a
/// neighbouring tensor at the right shape.
#[cfg(test)]
pub(crate) fn write_mixed(graph_id: u32, tensors: &[Fixture]) -> Vec<u8> {
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xFF) as i32 - 127 + 15;
        let mantissa = bits & 0x007F_FFFF;
        if exponent >= 0x1F {
            return sign | 0x7C00;
        }
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    let mut table = Vec::new();
    let mut data: Vec<u8> = Vec::new();
    for tensor in tensors {
        let (dims, dtype, len, bytes) = match tensor {
            Fixture::F16(dims, values) => (
                dims,
                DTYPE_F16,
                values.len() as u32,
                values.iter().flat_map(|&v| f32_to_f16(v).to_le_bytes()).collect::<Vec<u8>>(),
            ),
            Fixture::I8(dims, values) => (
                dims,
                DTYPE_I8,
                values.len() as u32,
                values.iter().map(|&v| v as u8).collect::<Vec<u8>>(),
            ),
        };
        while !data.len().is_multiple_of(ALIGNMENT as usize) {
            data.push(0);
        }
        let offset = data.len() as u32;
        data.extend_from_slice(&bytes);
        table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
        for slot in 0..4 {
            table.extend_from_slice(&dims.get(slot).copied().unwrap_or(0).to_le_bytes());
        }
        table.extend_from_slice(&dtype.to_le_bytes());
        table.extend_from_slice(&offset.to_le_bytes());
        table.extend_from_slice(&len.to_le_bytes());
    }

    let mut blob = Vec::new();
    blob.extend_from_slice(&MAGIC);
    blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    blob.extend_from_slice(&graph_id.to_le_bytes());
    blob.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 32]);
    blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
    blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 8]);
    blob.extend_from_slice(&table);
    blob.extend_from_slice(&data);
    blob
}

fn u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a file the way the converter does, so the round-trip is a real check of
    /// the two implementations agreeing rather than of this module agreeing with
    /// itself.
    fn write(graph_id: u32, tensors: &[(Vec<u32>, Vec<f32>)]) -> Vec<u8> {
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        for (dims, values) in tensors {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            for &v in values {
                data.extend_from_slice(&f32_to_f16(v).to_le_bytes());
            }
            let mut padded = [0u32; 4];
            padded[..dims.len()].copy_from_slice(dims);
            table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
            for d in padded {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&DTYPE_F16.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&(values.len() as u32).to_le_bytes());
        }

        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        out.extend_from_slice(&graph_id.to_le_bytes());
        out.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 32]);
        out.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 8]);
        out.extend_from_slice(&table);
        out.extend_from_slice(&data);
        out
    }

    /// Round-half-to-even fp32 to fp16, enough for the test fixtures above.
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xff) as i32 - 127 + 15;
        let mantissa = bits & 0x007f_ffff;
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    /// Write `bytes` into a temp file after `pad` bytes of filler, and return the file.
    ///
    /// The padding is the point: a `.maml` bundled as an asset is a *range* of the APK, not a file,
    /// so [`Streamed`] adds a base offset to every read. A fixture at offset 0 passes whether or not
    /// that offset is applied, which is the one thing worth checking here.
    fn on_disk(name: &str, pad: usize, bytes: &[u8]) -> (File, u64, u64) {
        use std::io::Write;
        let path = std::env::temp_dir().join(format!("modelrunner-{name}.maml"));
        let mut file = std::fs::File::create(&path).expect("a temp file");
        file.write_all(&vec![0xABu8; pad]).expect("the padding writes");
        file.write_all(bytes).expect("the blob writes");
        // Trailing filler as well, so the file does not end where the data section does: an asset
        // is followed by the next asset, and `Streamed` must not require otherwise.
        file.write_all(&[0xCDu8; 7]).expect("the trailer writes");
        drop(file);
        let opened = std::fs::File::open(&path).expect("the temp file reopens");
        (opened, pad as u64, bytes.len() as u64)
    }

    #[test]
    fn a_streamed_file_answers_exactly_as_a_parsed_one() {
        // The bundled path: the table is read from the file's prefix and the data section stays on
        // disk. Both halves must match what `Weights::parse` produces from the same bytes, because
        // `Net::new` uploads one and the plan was resolved against the other.
        let bytes = write(
            graph::SUPERTONIC_VE,
            &[(vec![2, 3], vec![1.0, 2.0, 4.0, 8.0, 16.0, 32.0]), (vec![2], vec![0.5, 0.25])],
        );
        let parsed = Weights::parse(&bytes, graph::SUPERTONIC_VE).expect("parses");
        let (file, at, len) = on_disk("streamed", 4096, &bytes);
        let streamed =
            Streamed::open(file, at, len, graph::SUPERTONIC_VE).expect("the file streams");

        assert_eq!(streamed.graph_id, parsed.graph_id);
        assert_eq!(streamed.len(), parsed.len());
        assert_eq!(streamed.data_len(), parsed.data_len());
        for index in 0..parsed.len() {
            assert_eq!(
                streamed.offsets().tensor(index).expect("in range"),
                parsed.tensor(index).expect("in range")
            );
        }
        // Byte for byte, and read in pieces rather than whole: the upload asks for chunks, so a
        // base offset applied once at open rather than per read would pass a single-read fixture.
        let mut got = vec![0u8; parsed.data_len() as usize];
        for (chunk, into) in got.chunks_mut(7).enumerate() {
            streamed.read_at((chunk * 7) as u64, into).expect("the chunk reads");
        }
        let mut want = vec![0u8; parsed.data_len() as usize];
        parsed.read_at(0, &mut want).expect("the whole section reads");
        assert_eq!(got, want);

        // And the host-side reads go through the same table and the same bytes.
        assert_eq!(
            streamed.reader().fp16(0, &[2, 3]).expect("the streamed tensor"),
            parsed.reader().fp16(0, &[2, 3]).expect("the parsed tensor")
        );
    }

    #[test]
    fn a_streamed_read_past_the_data_section_is_refused() {
        let bytes = write(graph::SUPERTONIC_DP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let (file, at, len) = on_disk("streamed-bounds", 16, &bytes);
        let streamed = Streamed::open(file, at, len, graph::SUPERTONIC_DP).expect("streams");

        let mut into = [0u8; 8];
        assert!(streamed.read_at(streamed.data_len() - 4, &mut into).is_err());
        assert!(streamed.read_at(u64::MAX, &mut into).is_err());
        // The trailing filler `on_disk` wrote is past the data section and must stay unreachable,
        // or a truncated `.maml` would upload whatever followed it in the APK.
        assert!(streamed.read_at(streamed.data_len(), &mut into[..1]).is_err());
    }

    #[test]
    fn a_streamed_file_shorter_than_its_own_header_says_is_refused() {
        // A length the caller was told, against a header that claims more. `AssetManager` reports
        // the range it will serve, so a disagreement means the asset was truncated at build time —
        // and the reads that ran off the end would land in the next asset rather than failing.
        let bytes = write(graph::SUPERTONIC_VOC, &[(vec![8], vec![1.0; 8])]);
        let (file, at, len) = on_disk("streamed-short", 0, &bytes);
        assert!(Streamed::open(file, at, len - 8, graph::SUPERTONIC_VOC).is_err());
    }

    #[test]
    fn a_streamed_file_for_another_graph_is_refused() {
        // The same check `Weights::parse` makes, and it has to happen here too: the four Supertonic
        // plans arrive as four descriptors in a fixed order, so a caller that swapped two would
        // otherwise upload the vocoder's weights into the text encoder's buffer.
        let bytes = write(graph::SUPERTONIC_TTL, &[(vec![2], vec![1.0, 2.0])]);
        let (file, at, len) = on_disk("streamed-wrong-graph", 32, &bytes);
        assert!(Streamed::open(file, at, len, graph::SUPERTONIC_VE).is_err());
    }

    #[test]
    fn the_table_outlives_the_blob_and_answers_identically() {
        // What `Net::rebuild` depends on: the offsets a plan resolves against do not come from the
        // data section, so a retained table gives the same answers after the file is gone. If it
        // did not, a rebuilt plan would index device memory that holds something else — the right
        // shape and the wrong tensor, which no count or digest check would notice.
        let bytes = write(
            graph::SUPERTONIC_TTL,
            &[(vec![2, 1, 1, 1], vec![1.0, 2.0]), (vec![3], vec![4.0, 8.0, 16.0])],
        );
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_TTL).expect("parses");
        let table = weights.offsets();
        let whole: Vec<Tensor> =
            (0..weights.len()).map(|i| weights.tensor(i).expect("in range")).collect();
        drop(weights);

        assert_eq!(table.len(), whole.len());
        for (index, expected) in whole.iter().enumerate() {
            assert_eq!(&table.tensor(index).expect("in range"), expected);
        }
        assert_eq!(table.shaped(0, &[2, 1, 1, 1]).expect("the declared shape").elem_offset(), 0);
        // And the shape check is still the shape check, not a length check.
        assert!(table.shaped(1, &[4]).is_err());
        assert!(table.tensor(2).is_err());
    }

    #[test]
    fn a_written_file_reads_back_with_the_same_table() {
        let bytes = write(
            graph::U2NETP,
            &[
                (vec![2, 1, 1, 1], vec![1.0, 2.0]),
                (vec![3], vec![4.0, 8.0, 16.0]),
            ],
        );
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert_eq!(weights.len(), 2);
        assert_eq!(
            weights.tensor(0).expect("first"),
            Tensor { rank: 4, dims: [2, 1, 1, 1], offset: 0, len: 2, int8: false }
        );
        // Tensor 0 is 4 bytes but the next starts at 16: the alignment the shaders
        // rely on, and the arithmetic most likely to be got wrong.
        assert_eq!(
            weights.tensor(1).expect("second"),
            Tensor { rank: 1, dims: [3, 0, 0, 0], offset: 16, len: 3, int8: false }
        );
        assert_eq!(weights.tensor(1).expect("second").elem_offset(), 8);
        assert_eq!(weights.data().len(), 16 + 6);
    }

    #[test]
    fn an_int8_tensor_reads_back_with_a_byte_stride_and_a_word_offset() {
        // Hand-built, because `write` above only emits fp16. One int8 tensor of five bytes,
        // then an fp16 scale after it — the layout `Builder::conv_int8` expects.
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        // int8 [5], at offset 0.
        let payload: [i8; 5] = [-128, -1, 0, 1, 127];
        for (dtype, dims, len, bytes) in [
            (DTYPE_I8, [5u32, 0, 0, 0], 5u32, payload.iter().map(|&b| b as u8).collect::<Vec<u8>>()),
            (DTYPE_F16, [1u32, 0, 0, 0], 1u32, f32_to_f16(0.25).to_le_bytes().to_vec()),
        ] {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            data.extend_from_slice(&bytes);
            table.extend_from_slice(&1u32.to_le_bytes());
            for d in dims {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&dtype.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&len.to_le_bytes());
        }
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SUPERTONIC_VE.to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&table);
        blob.extend_from_slice(&data);

        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int8 tensor");
        assert!(quantised.int8);
        assert_eq!(quantised.len, 5);
        // Five elements at one byte each, so the *scale* still lands at 16: the alignment is
        // in bytes, not elements, and an int8 tensor must not be read with an fp16 stride.
        let scale = weights.tensor(1).expect("the scale");
        assert!(!scale.int8);
        assert_eq!(scale.offset, ALIGNMENT);
        // The shader addresses int8 through the 32-bit view, so offset 0 is word 0.
        assert_eq!(quantised.word_offset(), 0);
        assert_eq!(scale.elem_offset(), ALIGNMENT / 2);
        // And the payload survived: a five-byte tensor is not padded to an even length.
        assert_eq!(&weights.data()[0..5], &[0x80, 0xFF, 0x00, 0x01, 0x7F]);
    }

    #[test]
    fn an_unknown_dtype_is_refused() {
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SELFIE.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + TENSOR_ENTRY_BYTES) as u32).to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&1u32.to_le_bytes());
        for d in [1u32, 0, 0, 0] {
            blob.extend_from_slice(&d.to_le_bytes());
        }
        // A dtype nothing implements. Refusing beats reading it as whichever stride is
        // nearest and returning plausible rubbish.
        blob.extend_from_slice(&7u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 2]);
        let error = Weights::parse(&blob, graph::SELFIE).expect_err("dtype 7");
        assert!(error.contains("dtype 7"), "{error}");
    }

    #[test]
    fn a_file_for_another_graph_is_refused() {
        let bytes = write(graph::SELFIE, &[(vec![1], vec![1.0])]);
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("wrong graph");
        assert!(error.contains("graph 1"), "{error}");
    }

    #[test]
    fn a_truncated_file_is_refused_at_load() {
        let bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let error =
            Weights::parse(&bytes[..bytes.len() - 2], graph::U2NETP).expect_err("truncated");
        assert!(error.contains("data section ends at"), "{error}");
    }

    #[test]
    fn a_shape_the_forward_pass_did_not_expect_is_refused() {
        let bytes = write(graph::U2NETP, &[(vec![2, 2], vec![1.0, 2.0, 3.0, 4.0])]);
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert!(weights.shaped(0, &[2, 2]).is_ok());
        let error = weights.shaped(0, &[4]).expect_err("wrong shape");
        assert!(error.contains("[2, 2]"), "{error}");
    }

    #[test]
    fn a_dims_and_len_disagreement_is_refused() {
        let mut bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        // Claim 5 elements for a 4-element tensor: the shape check must catch it
        // before the bounds check would.
        let len_at = HEADER_BYTES + 28;
        bytes[len_at..len_at + 4].copy_from_slice(&5u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("bad len");
        assert!(error.contains("but len 5"), "{error}");
    }
}
