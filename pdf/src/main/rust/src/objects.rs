use crate::*;

/// Numeric value of an integer or real object, else `None`.
pub(crate) fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}

/// Follow a chain of references to the underlying object.
pub(crate) fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

/// Decoded stream bytes, falling back to the raw content when the stream has no
/// `/Filter` (lopdf's `decompressed_content` errors instead of returning raw).
/// This version is legacy without Document context (used for ToUnicode cmap and font files).
pub(crate) fn stream_data(s: &lopdf::Stream) -> Vec<u8> {
    match s.decompressed_content() {
        Ok(d) => d,
        // With no /Filter the content is already plaintext and lopdf errors rather than
        // returning it, so the raw fallback is correct. With a /Filter present a failure
        // means we could not decode, and handing back the still-ENCODED bytes fed binary
        // garbage to the cmap/font parser.
        Err(_) if s.dict.get(b"Filter").is_err() => s.content.clone(),
        Err(_) => {
            // lopdf implements only Flate, LZW and ASCII85 (object.rs:690-693) and
            // returns Unimplemented for anything else, so an ASCIIHex- or
            // RunLength-encoded ToUnicode CMap or embedded font file decoded to
            // nothing at all — silently no text, or no glyphs. `filters` implements
            // both, so retry through our own chain.
            //
            // With no Document only DIRECT filter entries resolve. An indirect
            // /Filter leaves `specs` empty, and `decode_stream_content` treats an
            // empty chain as "already plaintext" and would hand back the still-
            // ENCODED bytes, so that case must yield nothing instead.
            let doc = Document::new();
            let specs = filters::filter_specs_from_dict(&doc, &s.dict);
            if specs.is_empty() {
                return Vec::new();
            }
            decode_stream_content(&doc, &s.dict, &s.content)
        }
    }
}

/// Extended decoder that uses Document context to handle filter chains case-insensitively,
/// including ASCIIHex, ASCII85, RunLength, LZW (EarlyChange), Flate with PNG predictors,
/// CCITT and JBIG2 (passthrough).
pub(crate) fn decode_stream_content(doc: &Document, dict: &Dictionary, raw: &[u8]) -> Vec<u8> {
    let specs = filters::filter_specs_from_dict(doc, dict);
    if specs.is_empty() {
        // No /Filter: the stream is already plaintext.
        return raw.to_vec();
    }
    // Image codecs (DCT/JPX/JBIG2/CCITT) are decoded by the image layer, which is the only
    // place with the real /Width and /Height, so it needs the still-encoded bytes.
    let image_codec_only = specs.iter().all(|(k, _)| {
        matches!(
            k,
            filters::FilterKind::Dct
                | filters::FilterKind::Jpx
                | filters::FilterKind::Jbig2
                | filters::FilterKind::Ccitt
                | filters::FilterKind::Crypt
        )
    });
    if image_codec_only {
        return raw.to_vec();
    }
    if let Some(decoded) = filters::decode_stream_chain(raw.to_vec(), &specs, doc) {
        return decoded;
    }
    // Fall back to lopdf's native chain — but NOT when a predictor is involved.
    //
    // lopdf reads the parameters as a single DIRECT dictionary (object.rs:681,
    // `get(b"DecodeParms").and_then(Object::as_dict)`), so an indirect reference, or
    // the array form used alongside a filter chain, silently resolves to `None`. It
    // then inflates happily and returns Ok with the /Predictor NEVER UNDONE — success
    // carrying garbage, which no error check can catch. Our own chain is the only
    // predictor implementation here that resolves both forms, so if it failed there is
    // nothing left to trust.
    if !wants_predictor(doc, dict) {
        let temp = Stream::new(dict.clone(), raw.to_vec());
        if let Ok(d) = temp.decompressed_content() {
            return d;
        }
    }
    // Every decoder failed. Returning `raw` here handed the still-ENCODED bytes to the
    // content-stream interpreter to be parsed as operators, or to the image layer as raw
    // samples — visible as garbage on the page. Render nothing instead.
    Vec::new()
}

/// Whether any `/DecodeParms` entry asks for a predictor (§7.4.4.4).
///
/// Used to decide whether lopdf's decoder can be trusted as a fallback: it cannot see
/// an indirect or array-form `/DecodeParms`, and silently returns undecoded-predictor
/// data as success. `/DP` is the inline-image abbreviation for the same key.
fn wants_predictor(doc: &Document, dict: &Dictionary) -> bool {
    let Some(parms) = dict
        .get(b"DecodeParms")
        .or_else(|_| dict.get(b"DP"))
        .ok()
        .and_then(|o| deref(doc, o))
    else {
        return false;
    };
    let has_predictor = |d: &Dictionary| {
        d.get(b"Predictor")
            .ok()
            .and_then(|o| deref(doc, o).and_then(num).or_else(|| num(o)))
            .is_some_and(|p| p > 1.0)
    };
    match parms {
        Object::Dictionary(d) => has_predictor(d),
        Object::Array(a) => a.iter().any(|el| {
            deref(doc, el)
                .and_then(|o| o.as_dict().ok())
                .is_some_and(has_predictor)
        }),
        _ => false,
    }
}

pub(crate) fn stream_data_with_doc(doc: &Document, s: &Stream) -> Vec<u8> {
    decode_stream_content(doc, &s.dict, &s.content)
}

// ---------------------------------------------------------------------------
// Fonts + ToUnicode
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// lopdf's `decompressed_content` handles only Flate/LZW/ASCII85, so these two
    /// filters used to yield an empty ToUnicode CMap or font file — silently no text.
    #[test]
    fn stream_data_decodes_filters_lopdf_does_not_implement() {
        let s = Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"48656C6C6F>".to_vec(),
        );
        assert!(
            s.decompressed_content().is_err(),
            "precondition: lopdf is expected not to implement ASCIIHexDecode"
        );
        assert_eq!(stream_data(&s), b"Hello".to_vec());

        // RunLength: literal run of 1 byte (0x00 => copy 1), then EOD (128).
        let s = Stream::new(
            dictionary! { "Filter" => "RunLengthDecode" },
            vec![0x00, 0xAB, 128],
        );
        assert!(s.decompressed_content().is_err(), "precondition");
        assert_eq!(stream_data(&s), vec![0xAB]);
    }

    #[test]
    fn stream_data_never_returns_still_encoded_bytes() {
        // An indirect /Filter cannot be resolved without the owning document, so the
        // chain is unknown. Yielding the encoded bytes would feed binary garbage to
        // the cmap/font parser, which is the bug this guards.
        let raw = vec![0x9C, 0x78, 0x01, 0x02];
        let s = Stream::new(
            dictionary! { "Filter" => Object::Reference((99, 0)) },
            raw.clone(),
        );
        let out = stream_data(&s);
        assert!(out.is_empty(), "expected nothing, got {out:?}");
        assert_ne!(out, raw);

        // A genuinely unknown filter name likewise decodes to nothing, not to itself.
        let s = Stream::new(dictionary! { "Filter" => "MadeUpDecode" }, raw.clone());
        assert_ne!(stream_data(&s), raw);
    }

    #[test]
    fn stream_data_returns_raw_when_there_is_no_filter() {
        let raw = b"plain cmap bytes".to_vec();
        let s = Stream::new(Dictionary::new(), raw.clone());
        assert_eq!(stream_data(&s), raw);
    }

    /// lopdf reads /DecodeParms as a single DIRECT dictionary, so an indirect
    /// reference or the array form silently loses the /Predictor and it returns
    /// SUCCESS carrying predictor-encoded bytes. That cannot be caught by an error
    /// check, so the fallback must not be consulted at all in those cases.
    #[test]
    fn predictor_params_are_detected_in_every_form() {
        let mut doc = Document::new();

        // Direct dictionary.
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 12, "Columns" => 4 },
        };
        assert!(wants_predictor(&doc, &d));

        // Predictor 1 means "no predictor" (§7.4.4.4), so the fallback stays usable.
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 1 },
        };
        assert!(!wants_predictor(&doc, &d));

        // No /DecodeParms at all.
        let d = dictionary! { "Filter" => "FlateDecode" };
        assert!(!wants_predictor(&doc, &d));

        // INDIRECT /DecodeParms — the case lopdf cannot see.
        let parms_id = doc.add_object(dictionary! { "Predictor" => 15, "Columns" => 8 });
        let d = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => Object::Reference(parms_id),
        };
        assert!(
            wants_predictor(&doc, &d),
            "an indirect /DecodeParms still asks for a predictor"
        );

        // ARRAY form, as used with a filter chain — also invisible to lopdf.
        let d = dictionary! {
            "Filter" => vec![Object::Name(b"ASCII85Decode".to_vec()), Object::Name(b"FlateDecode".to_vec())],
            "DecodeParms" => vec![
                Object::Null,
                Object::Dictionary(dictionary! { "Predictor" => 12, "Columns" => 4 }),
            ],
        };
        assert!(
            wants_predictor(&doc, &d),
            "the array form pairs params with the filter chain by index"
        );

        // /DP is the inline-image abbreviation for the same key.
        let d = dictionary! {
            "F" => "Fl",
            "DP" => dictionary! { "Predictor" => 12 },
        };
        assert!(wants_predictor(&doc, &d));
    }

    #[test]
    fn a_predictor_stream_our_chain_cannot_decode_yields_nothing_not_garbage() {
        // Corrupt Flate payload plus a predictor. Our chain fails, and lopdf must not
        // be consulted, so the result must be empty rather than predictor-encoded.
        let doc = Document::new();
        let dict = dictionary! {
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! { "Predictor" => 12, "Columns" => 4 },
        };
        let out = decode_stream_content(&doc, &dict, &[0xFF, 0xFE, 0xFD, 0xFC]);
        assert!(out.is_empty(), "expected nothing, got {out:?}");
    }
}


// Fonts + ToUnicode
// ---------------------------------------------------------------------------
