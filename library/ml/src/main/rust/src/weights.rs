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
/// The quantisation is symmetric — zero point 0 — and the scale is per tensor, both read from
/// the export rather than assumed. So a value is `int8 * scale`, and because the scale is one
/// number for the whole tensor it multiplies the *accumulator* once rather than every tap. The
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
    /// Piper's VITS vocoder — the `dec` module, 192 latent channels to a 16 kHz waveform.
    ///
    /// Only the vocoder: the text encoder, the flow and the duration predictor are separate
    /// graphs, because VITS's duration predictor samples noise and builds its alignment out
    /// of ops no plan can express. See [`crate::nets::vits_dec`].
    pub const VITS_DEC: u32 = 7;
    /// Piper's VITS text encoder — the `enc_p` module, 130 phoneme symbols to a prior.
    ///
    /// See [`crate::nets::vits_enc`]. Its input is symbol ids rather than pixels or latents.
    pub const VITS_ENC: u32 = 8;
    /// Piper's VITS normalising flow \u2014 the `flow` module, run in reverse.
    ///
    /// See [`crate::nets::vits_flow`].
    pub const VITS_FLOW: u32 = 9;
    /// Piper's VITS stochastic duration predictor \u2014 the `dp` module.
    ///
    /// Read on the host rather than compiled into a plan: see [`crate::post::duration`].
    pub const VITS_DP: u32 = 10;
    /// Supertonic 3's ConvNeXt vocoder. See [`crate::nets::supertonic_vocoder`].
    pub const SUPERTONIC_VOC: u32 = 11;
    /// Supertonic 3's duration predictor. See [`crate::nets::supertonic_duration`].
    pub const SUPERTONIC_DP: u32 = 12;
    /// Supertonic 3's text encoder. See [`crate::nets::supertonic_text`].
    pub const SUPERTONIC_TTL: u32 = 13;
    /// Supertonic 3's flow-matching sampler. See [`crate::nets::supertonic_sampler`].
    pub const SUPERTONIC_VE: u32 = 14;
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
    /// Every offset and length in the table is bounds-checked here rather than at
    /// use, so a truncated or hand-edited asset fails at load with a message instead
    /// of dispatching a shader that reads past the end of a device buffer — where
    /// the symptom would be a driver reset, not an error.
    pub fn parse(bytes: &[u8], expect_graph: u32) -> Result<Weights, String> {
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
        let data_end = data_offset
            .checked_add(data_len)
            .ok_or_else(|| "data section overflows".to_string())?;
        if data_end != bytes.len() {
            return Err(format!(
                "data section ends at {data_end} but the file is {} bytes",
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
            let dims = [
                u32(bytes, at + 4),
                u32(bytes, at + 8),
                u32(bytes, at + 12),
                u32(bytes, at + 16),
            ];
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

        Ok(Weights {
            graph_id,
            source_sha256,
            table: Offsets { tensors },
            data: bytes[data_offset..].to_vec(),
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
        blob.extend_from_slice(&graph::VITS_ENC.to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&table);
        blob.extend_from_slice(&data);

        let weights = Weights::parse(&blob, graph::VITS_ENC).expect("parses");
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
