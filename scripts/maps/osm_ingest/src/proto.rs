//! Protobuf wire-format decoder — just enough of it to read an `.osm.pbf`.
//!
//! The mirror image of `maps/src/main/rust/src/mvt.rs`, which hand-rolls a
//! protobuf *encoder* for vector tiles. The `.osm.pbf` schema subset we need is
//! small and fixed (see `pbf.rs`), so a generated-code dependency would buy
//! nothing and would cost the offline build.

use std::fmt;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error(pub String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub fn err<T>(msg: impl Into<String>) -> Result<T> {
    Err(Error(msg.into()))
}

// Wire types.
pub const WIRE_VARINT: u8 = 0;
pub const WIRE_I64: u8 = 1;
pub const WIRE_BYTES: u8 = 2;
pub const WIRE_I32: u8 = 5;

/// Cursor over one protobuf message body.
#[derive(Clone)]
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    #[inline]
    pub fn at_end(&self) -> bool {
        self.pos >= self.buf.len()
    }

    /// Next `(field_number, wire_type)`, or `None` at end of message.
    pub fn next_field(&mut self) -> Result<Option<(u32, u8)>> {
        if self.at_end() {
            return Ok(None);
        }
        let key = self.uvarint()?;
        let field = (key >> 3) as u32;
        if field == 0 {
            return err("protobuf field number 0");
        }
        Ok(Some((field, (key & 7) as u8)))
    }

    pub fn uvarint(&mut self) -> Result<u64> {
        let mut val: u64 = 0;
        let mut shift = 0u32;
        loop {
            if self.pos >= self.buf.len() {
                return err("truncated varint");
            }
            let b = self.buf[self.pos];
            self.pos += 1;
            if shift >= 64 {
                return err("varint longer than 64 bits");
            }
            val |= ((b & 0x7F) as u64) << shift;
            if b & 0x80 == 0 {
                return Ok(val);
            }
            shift += 7;
        }
    }

    /// `sint32`/`sint64`: zigzag-decoded varint.
    pub fn svarint(&mut self) -> Result<i64> {
        Ok(zigzag(self.uvarint()?))
    }

    /// `int32`/`int64`: two's-complement varint (negatives are 10 bytes).
    pub fn ivarint(&mut self) -> Result<i64> {
        Ok(self.uvarint()? as i64)
    }

    /// Length-delimited payload, borrowed from the underlying buffer.
    pub fn bytes(&mut self) -> Result<&'a [u8]> {
        let len = self.uvarint()? as usize;
        let end = self
            .pos
            .checked_add(len)
            .filter(|e| *e <= self.buf.len())
            .ok_or_else(|| Error("length-delimited field runs past end".into()))?;
        let out = &self.buf[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    /// Skip a field of the given wire type. Unknown fields must be skippable or
    /// a schema addition upstream would break the reader.
    pub fn skip(&mut self, wire: u8) -> Result<()> {
        match wire {
            WIRE_VARINT => {
                self.uvarint()?;
            }
            WIRE_I64 => self.advance(8)?,
            WIRE_BYTES => {
                self.bytes()?;
            }
            WIRE_I32 => self.advance(4)?,
            other => return err(format!("unsupported protobuf wire type {other}")),
        }
        Ok(())
    }

    fn advance(&mut self, n: usize) -> Result<()> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|e| *e <= self.buf.len())
            .ok_or_else(|| Error("fixed-width field runs past end".into()))?;
        self.pos = end;
        Ok(())
    }

    pub fn fixed32(&mut self) -> Result<u32> {
        self.advance(4)?;
        let s = &self.buf[self.pos - 4..self.pos];
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
}

#[inline]
pub fn zigzag(v: u64) -> i64 {
    ((v >> 1) as i64) ^ -((v & 1) as i64)
}

/// Decode a packed `repeated uint64`/`int64` payload, appending to `out`.
pub fn packed_uvarint(payload: &[u8], out: &mut Vec<u64>) -> Result<()> {
    let mut r = Reader::new(payload);
    while !r.at_end() {
        out.push(r.uvarint()?);
    }
    Ok(())
}

/// Decode a packed `repeated uint32`/`int32` payload, appending to `out`.
pub fn packed_u32(payload: &[u8], out: &mut Vec<u32>) -> Result<()> {
    let mut r = Reader::new(payload);
    while !r.at_end() {
        out.push(r.uvarint()? as u32);
    }
    Ok(())
}

/// Decode a packed `repeated sint64` payload (zigzag), appending to `out`.
pub fn packed_svarint(payload: &[u8], out: &mut Vec<i64>) -> Result<()> {
    let mut r = Reader::new(payload);
    while !r.at_end() {
        out.push(r.svarint()?);
    }
    Ok(())
}

/// Decode a packed `repeated sint64` payload as a running delta sum. The PBF
/// format delta-encodes ids and coordinates this way.
pub fn packed_delta(payload: &[u8], out: &mut Vec<i64>) -> Result<()> {
    let mut r = Reader::new(payload);
    let mut acc: i64 = 0;
    while !r.at_end() {
        acc = acc.wrapping_add(r.svarint()?);
        out.push(acc);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn uvarint_bytes(mut v: u64) -> Vec<u8> {
        let mut out = Vec::new();
        loop {
            let b = (v & 0x7F) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                return out;
            }
            out.push(b | 0x80);
        }
    }

    #[test]
    fn uvarint_round_trips_at_byte_boundaries() {
        // 1/2/3/.../10-byte encodings, including each 7-bit boundary.
        let mut cases = vec![0u64, 1, 127, 128, 129, 16383, 16384, u64::MAX];
        for shift in 0..64 {
            cases.push(1u64 << shift);
            cases.push((1u64 << shift).wrapping_sub(1));
        }
        for v in cases {
            let enc = uvarint_bytes(v);
            let mut r = Reader::new(&enc);
            assert_eq!(r.uvarint().unwrap(), v, "value {v}");
            assert!(r.at_end(), "value {v} left {} bytes", enc.len());
        }
        assert_eq!(uvarint_bytes(127).len(), 1);
        assert_eq!(uvarint_bytes(128).len(), 2);
        assert_eq!(uvarint_bytes(u64::MAX).len(), 10);
    }

    #[test]
    fn truncated_varint_is_an_error() {
        let mut r = Reader::new(&[0x80, 0x80]);
        assert!(r.uvarint().is_err());
    }

    #[test]
    fn zigzag_matches_the_protobuf_spec() {
        assert_eq!(zigzag(0), 0);
        assert_eq!(zigzag(1), -1);
        assert_eq!(zigzag(2), 1);
        assert_eq!(zigzag(3), -2);
        assert_eq!(zigzag(4294967294), 2147483647);
        assert_eq!(zigzag(4294967295), -2147483648);
        // Full round trip through the encoder's rule: (n << 1) ^ (n >> 63).
        for n in [i64::MIN, -1, 0, 1, i64::MAX, -123456789, 123456789] {
            let enc = ((n << 1) ^ (n >> 63)) as u64;
            assert_eq!(zigzag(enc), n, "value {n}");
        }
    }

    #[test]
    fn negative_int64_uses_ten_bytes() {
        // int64 (not sint64) encodes -1 as ten 0xFF-ish bytes.
        let enc = uvarint_bytes(-1i64 as u64);
        assert_eq!(enc.len(), 10);
        let mut r = Reader::new(&enc);
        assert_eq!(r.ivarint().unwrap(), -1);
    }

    #[test]
    fn packed_delta_accumulates() {
        // sint64 deltas 10, 5, -3 -> 10, 15, 12
        let mut payload = Vec::new();
        for d in [10i64, 5, -3] {
            payload.extend(uvarint_bytes(((d << 1) ^ (d >> 63)) as u64));
        }
        let mut out = Vec::new();
        packed_delta(&payload, &mut out).unwrap();
        assert_eq!(out, vec![10, 15, 12]);
    }

    #[test]
    fn skip_walks_past_unknown_fields() {
        // field 1 varint = 300, field 2 bytes = "hi", field 3 i32, field 4 i64,
        // then field 5 varint = 7 which we actually read.
        let mut msg = Vec::new();
        msg.push(1 << 3 | WIRE_VARINT);
        msg.extend(uvarint_bytes(300));
        msg.push(2 << 3 | WIRE_BYTES);
        msg.extend(uvarint_bytes(2));
        msg.extend_from_slice(b"hi");
        msg.push(3 << 3 | WIRE_I32);
        msg.extend_from_slice(&[1, 2, 3, 4]);
        msg.push(4 << 3 | WIRE_I64);
        msg.extend_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        msg.push(5 << 3 | WIRE_VARINT);
        msg.extend(uvarint_bytes(7));

        let mut r = Reader::new(&msg);
        let mut seen = None;
        while let Some((field, wire)) = r.next_field().unwrap() {
            if field == 5 {
                seen = Some(r.uvarint().unwrap());
            } else {
                r.skip(wire).unwrap();
            }
        }
        assert_eq!(seen, Some(7));
    }

    #[test]
    fn length_delimited_past_end_is_an_error() {
        let mut msg = vec![1 << 3 | WIRE_BYTES];
        msg.extend(uvarint_bytes(99));
        msg.extend_from_slice(b"short");
        let mut r = Reader::new(&msg);
        r.next_field().unwrap();
        assert!(r.bytes().is_err());
    }
}
