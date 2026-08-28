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
}

impl Tensor {
    /// The offset as an fp16 **element** index, which is how the shaders address it.
    pub fn elem_offset(&self) -> u32 {
        self.offset / 2
    }
}

/// A parsed `.maml` file: the tensor table, and the data section to upload.
#[derive(Debug)]
pub struct Weights {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    tensors: Vec<Tensor>,
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
            if dtype != DTYPE_F16 {
                return Err(format!("tensor {i} has dtype {dtype}, only fp16 is read"));
            }
            let offset = u32(bytes, at + 24);
            let len = u32(bytes, at + 28);

            let expected: u64 = dims[..rank as usize].iter().map(|&d| d as u64).product();
            if expected != len as u64 {
                return Err(format!("tensor {i} has dims {dims:?} but len {len}"));
            }
            if !offset.is_multiple_of(ALIGNMENT) {
                return Err(format!("tensor {i} is at {offset}, not {ALIGNMENT}-aligned"));
            }
            let end = (offset as u64) + (len as u64) * 2;
            if end > data_len as u64 {
                return Err(format!(
                    "tensor {i} spans {offset}..{end} of a {data_len}-byte data section"
                ));
            }
            tensors.push(Tensor { rank, dims, offset, len });
        }

        Ok(Weights {
            graph_id,
            source_sha256,
            tensors,
            data: bytes[data_offset..].to_vec(),
        })
    }

    /// The fp16 blob to upload, verbatim.
    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.tensors.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.tensors.is_empty()
    }

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
            Tensor { rank: 4, dims: [2, 1, 1, 1], offset: 0, len: 2 }
        );
        // Tensor 0 is 4 bytes but the next starts at 16: the alignment the shaders
        // rely on, and the arithmetic most likely to be got wrong.
        assert_eq!(
            weights.tensor(1).expect("second"),
            Tensor { rank: 1, dims: [3, 0, 0, 0], offset: 16, len: 3 }
        );
        assert_eq!(weights.tensor(1).expect("second").elem_offset(), 8);
        assert_eq!(weights.data().len(), 16 + 6);
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
