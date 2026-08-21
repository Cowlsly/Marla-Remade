//! Protobuf wire-format codec — enough of it to read and write Mapbox Vector
//! Tiles.
//!
//! The [`Reader`] half mirrors `scripts/maps/osm_ingest/src/proto.rs` field for
//! field, so the two are recognisably siblings; that one is decode-only because
//! an `.osm.pbf` is only ever read. The [`Writer`] half is new, and follows the
//! encoding already proven in production by `maps/src/main/rust/src/mvt.rs` (the
//! live-traffic tile encoder): varints little-endian base-128, zigzag for
//! `sint32`, and length-delimited submessages written body-first so the length is
//! known before the tag.
//!
//! The schema subset is small and fixed (see `mvt.rs`), so generated code would
//! buy nothing and would cost the offline build.

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
        Ok(zigzag_decode(self.uvarint()?))
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

    /// Length-delimited payload as UTF-8. Lossy, because a basemap tile with one
    /// mis-encoded label should still tile.
    pub fn string(&mut self) -> Result<String> {
        Ok(String::from_utf8_lossy(self.bytes()?).into_owned())
    }

    pub fn fixed32(&mut self) -> Result<u32> {
        self.advance(4)?;
        let s = &self.buf[self.pos - 4..self.pos];
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }

    pub fn fixed64(&mut self) -> Result<u64> {
        self.advance(8)?;
        let s = &self.buf[self.pos - 8..self.pos];
        Ok(u64::from_le_bytes([s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]]))
    }

    /// Skip a field of the given wire type. Unknown fields must be skippable or a
    /// schema addition upstream would break the reader.
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
}

/// Builder for one protobuf message body.
///
/// Submessages are built into their own [`Writer`] and appended with
/// [`Writer::message`], because a length-delimited field needs its body's length
/// before its tag — the same body-first shape `mvt.rs` uses on device.
#[derive(Default, Clone)]
pub struct Writer {
    buf: Vec<u8>,
}

impl Writer {
    pub fn new() -> Writer {
        Writer { buf: Vec::new() }
    }

    pub fn with_capacity(n: usize) -> Writer {
        Writer { buf: Vec::with_capacity(n) }
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.buf
    }

    pub fn into_vec(self) -> Vec<u8> {
        self.buf
    }

    pub fn len(&self) -> usize {
        self.buf.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }

    pub fn clear(&mut self) {
        self.buf.clear();
    }

    pub fn uvarint(&mut self, mut value: u64) -> &mut Self {
        while value >= 0x80 {
            self.buf.push((value as u8) | 0x80);
            value >>= 7;
        }
        self.buf.push(value as u8);
        self
    }

    pub fn tag(&mut self, field: u32, wire: u8) -> &mut Self {
        self.uvarint(((field as u64) << 3) | wire as u64)
    }

    /// `uint32`/`uint64`/`bool`/`enum`.
    pub fn varint_field(&mut self, field: u32, value: u64) -> &mut Self {
        self.tag(field, WIRE_VARINT).uvarint(value)
    }

    /// `sint32`/`sint64`: zigzagged so small negatives stay one byte.
    pub fn svarint_field(&mut self, field: u32, value: i64) -> &mut Self {
        self.tag(field, WIRE_VARINT).uvarint(zigzag_encode(value))
    }

    /// `int32`/`int64`. A negative value costs the full 10 bytes, which is why
    /// coordinate deltas use [`Writer::svarint_field`] instead.
    pub fn ivarint_field(&mut self, field: u32, value: i64) -> &mut Self {
        self.tag(field, WIRE_VARINT).uvarint(value as u64)
    }

    pub fn bytes_field(&mut self, field: u32, value: &[u8]) -> &mut Self {
        self.tag(field, WIRE_BYTES).uvarint(value.len() as u64);
        self.buf.extend_from_slice(value);
        self
    }

    pub fn string_field(&mut self, field: u32, value: &str) -> &mut Self {
        self.bytes_field(field, value.as_bytes())
    }

    /// Append an already-built submessage body as a length-delimited field.
    pub fn message(&mut self, field: u32, body: &Writer) -> &mut Self {
        self.bytes_field(field, body.as_slice())
    }

    pub fn fixed32_field(&mut self, field: u32, value: u32) -> &mut Self {
        self.tag(field, WIRE_I32);
        self.buf.extend_from_slice(&value.to_le_bytes());
        self
    }

    pub fn fixed64_field(&mut self, field: u32, value: u64) -> &mut Self {
        self.tag(field, WIRE_I64);
        self.buf.extend_from_slice(&value.to_le_bytes());
        self
    }

    /// A packed `repeated uint32`/`uint64` field.
    pub fn packed_uvarint_field(&mut self, field: u32, values: &[u64]) -> &mut Self {
        let mut body = Writer::with_capacity(values.len());
        for &v in values {
            body.uvarint(v);
        }
        self.message(field, &body)
    }

    /// Raw bytes with no tag, for callers assembling a payload by hand (MVT
    /// geometry is a bare packed varint stream).
    pub fn raw(&mut self, bytes: &[u8]) -> &mut Self {
        self.buf.extend_from_slice(bytes);
        self
    }
}

#[inline]
pub fn zigzag_decode(v: u64) -> i64 {
    ((v >> 1) as i64) ^ -((v & 1) as i64)
}

#[inline]
pub fn zigzag_encode(v: i64) -> u64 {
    ((v << 1) ^ (v >> 63)) as u64
}

/// Decode a packed `repeated uint64`/`int64` payload, appending to `out`.
pub fn packed_uvarint(payload: &[u8], out: &mut Vec<u64>) -> Result<()> {
    let mut r = Reader::new(payload);
    while !r.at_end() {
        out.push(r.uvarint()?);
    }
    Ok(())
}

/// Decode a packed `repeated uint32` payload, appending to `out`.
pub fn packed_u32(payload: &[u8], out: &mut Vec<u32>) -> Result<()> {
    let mut r = Reader::new(payload);
    while !r.at_end() {
        out.push(r.uvarint()? as u32);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zigzag_round_trips_across_the_sign_boundary() {
        for v in [0i64, -1, 1, -2, 2, 63, -64, i32::MIN as i64, i32::MAX as i64] {
            assert_eq!(zigzag_decode(zigzag_encode(v)), v, "zigzag({v})");
        }
        // The spec's own worked examples.
        assert_eq!(zigzag_encode(0), 0);
        assert_eq!(zigzag_encode(-1), 1);
        assert_eq!(zigzag_encode(1), 2);
        assert_eq!(zigzag_encode(-2), 3);
    }

    #[test]
    fn uvarint_round_trips_and_matches_known_encodings() {
        // Canonical protobuf examples, so the writer is pinned to the wire format
        // rather than only to our own reader.
        let mut w = Writer::new();
        w.uvarint(0);
        assert_eq!(w.as_slice(), &[0x00]);
        let mut w = Writer::new();
        w.uvarint(1);
        assert_eq!(w.as_slice(), &[0x01]);
        let mut w = Writer::new();
        w.uvarint(300);
        assert_eq!(w.as_slice(), &[0xAC, 0x02]);

        for v in [0u64, 1, 127, 128, 300, 16_383, 16_384, u32::MAX as u64, u64::MAX] {
            let mut w = Writer::new();
            w.uvarint(v);
            let mut r = Reader::new(w.as_slice());
            assert_eq!(r.uvarint().unwrap(), v, "uvarint({v})");
            assert!(r.at_end(), "uvarint({v}) left trailing bytes");
        }
    }

    #[test]
    fn a_tag_packs_field_and_wire_type() {
        let mut w = Writer::new();
        w.tag(3, WIRE_BYTES);
        let mut r = Reader::new(w.as_slice());
        assert_eq!(r.next_field().unwrap(), Some((3, WIRE_BYTES)));
        // Field 3 wire type 2 is the MVT `layers` tag, and encodes as 0x1a.
        let mut w = Writer::new();
        w.tag(3, WIRE_BYTES);
        assert_eq!(w.as_slice(), &[0x1a]);
    }

    #[test]
    fn every_field_kind_round_trips() {
        let mut w = Writer::new();
        w.varint_field(1, 42);
        w.svarint_field(2, -7);
        w.ivarint_field(3, 9);
        w.string_field(4, "transit_stops");
        w.fixed32_field(5, 0xDEAD_BEEF);
        w.fixed64_field(6, 0x0123_4567_89AB_CDEF);
        w.packed_uvarint_field(7, &[1, 300, 5]);

        let mut r = Reader::new(w.as_slice());
        assert_eq!(r.next_field().unwrap(), Some((1, WIRE_VARINT)));
        assert_eq!(r.uvarint().unwrap(), 42);
        assert_eq!(r.next_field().unwrap(), Some((2, WIRE_VARINT)));
        assert_eq!(r.svarint().unwrap(), -7);
        assert_eq!(r.next_field().unwrap(), Some((3, WIRE_VARINT)));
        assert_eq!(r.ivarint().unwrap(), 9);
        assert_eq!(r.next_field().unwrap(), Some((4, WIRE_BYTES)));
        assert_eq!(r.string().unwrap(), "transit_stops");
        assert_eq!(r.next_field().unwrap(), Some((5, WIRE_I32)));
        assert_eq!(r.fixed32().unwrap(), 0xDEAD_BEEF);
        assert_eq!(r.next_field().unwrap(), Some((6, WIRE_I64)));
        assert_eq!(r.fixed64().unwrap(), 0x0123_4567_89AB_CDEF);
        assert_eq!(r.next_field().unwrap(), Some((7, WIRE_BYTES)));
        let mut packed = Vec::new();
        packed_uvarint(r.bytes().unwrap(), &mut packed).unwrap();
        assert_eq!(packed, vec![1, 300, 5]);
        assert_eq!(r.next_field().unwrap(), None);
    }

    #[test]
    fn a_submessage_is_length_prefixed() {
        let mut inner = Writer::new();
        inner.string_field(1, "water");
        inner.varint_field(5, 4096);
        let mut outer = Writer::new();
        outer.message(3, &inner);

        let mut r = Reader::new(outer.as_slice());
        assert_eq!(r.next_field().unwrap(), Some((3, WIRE_BYTES)));
        let body = r.bytes().unwrap();
        assert_eq!(body.len(), inner.len());
        let mut ri = Reader::new(body);
        assert_eq!(ri.next_field().unwrap(), Some((1, WIRE_BYTES)));
        assert_eq!(ri.string().unwrap(), "water");
        assert_eq!(ri.next_field().unwrap(), Some((5, WIRE_VARINT)));
        assert_eq!(ri.uvarint().unwrap(), 4096);
        assert!(ri.at_end());
    }

    #[test]
    fn skip_steps_over_every_wire_type() {
        let mut w = Writer::new();
        w.varint_field(1, 300);
        w.fixed64_field(2, 7);
        w.string_field(3, "skipme");
        w.fixed32_field(4, 9);
        w.varint_field(5, 1);

        let mut r = Reader::new(w.as_slice());
        for _ in 0..4 {
            let (_, wire) = r.next_field().unwrap().unwrap();
            r.skip(wire).unwrap();
        }
        // Landing exactly on field 5 proves each skip consumed the right width.
        assert_eq!(r.next_field().unwrap(), Some((5, WIRE_VARINT)));
        assert_eq!(r.uvarint().unwrap(), 1);
        assert!(r.at_end());
    }

    #[test]
    fn a_truncated_varint_is_an_error_not_a_panic() {
        // A high continuation bit with nothing after it: what a clipped tile looks
        // like. It must not index past the buffer.
        let mut r = Reader::new(&[0x80]);
        assert!(r.uvarint().is_err());
    }

    #[test]
    fn a_length_running_past_the_end_is_an_error() {
        // Field 1, wire 2, length 200, but only 2 bytes of body.
        let mut r = Reader::new(&[0x0a, 200, 1, 2]);
        assert_eq!(r.next_field().unwrap(), Some((1, WIRE_BYTES)));
        assert!(r.bytes().is_err());
    }

    #[test]
    fn field_number_zero_is_rejected() {
        let mut r = Reader::new(&[0x00]);
        assert!(r.next_field().is_err());
    }
}
