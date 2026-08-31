//! Adobe Standard 14 ("Core 14") font glyph-width metrics.
//!
//! When a PDF references one of the 14 standard fonts (Helvetica, Times,
//! Courier, Symbol, ZapfDingbats and their bold/italic variants) without
//! embedding it *and* without supplying a `/Widths` array, a conforming reader
//! is expected to fall back to the publicly-standardized AFM metrics for that
//! face. This module provides those metrics as pure data so a non-embedded
//! standard font can still be spaced correctly.
//!
//! Widths are returned in *text-space units* (AFM units / 1000.0), so e.g. the
//! Helvetica space glyph is `0.278`. Values are the canonical Adobe AFM
//! `WX` numbers keyed by glyph name (the AFM `N` field).
//!
//! Pure data + lookup: no I/O, no external dependencies.

use std::collections::HashMap;

/// Returns a map of glyph-name -> advance width (in text-space units, i.e.
/// AFM width / 1000.0) for the given base font name, or `None` if the name does
/// not resolve to one of the Adobe Core-14 faces.
///
/// The name is matched case-insensitively and tolerates subset prefixes
/// (`ABCDEF+Helvetica`) and common style suffixes. Standard substitution
/// aliases are honoured (Arial -> Helvetica, TimesNewRoman -> Times, etc.).
pub(crate) fn standard_14_widths(base_font: &str) -> Option<HashMap<String, f64>> {
    let face = resolve_face(base_font)?;
    Some(match face {
        Face::Helvetica => build(HELVETICA),
        Face::HelveticaBold => build(HELVETICA_BOLD),
        Face::HelveticaOblique => build(HELVETICA),
        Face::HelveticaBoldOblique => build(HELVETICA_BOLD),
        Face::TimesRoman => build(TIMES_ROMAN),
        Face::TimesBold => build(TIMES_BOLD),
        Face::TimesItalic => build(TIMES_ITALIC),
        Face::TimesBoldItalic => build(TIMES_BOLD_ITALIC),
        Face::Courier => build_courier(),
        Face::Symbol => build(SYMBOL),
        Face::ZapfDingbats => build(ZAPF_DINGBATS),
    })
}

/// The canonical Core-14 face a base-font name resolves to. Oblique variants of
/// Helvetica share the upright metrics, so they map onto the same tables; they
/// are kept as distinct variants for clarity at the call site.
enum Face {
    Helvetica,
    HelveticaBold,
    HelveticaOblique,
    HelveticaBoldOblique,
    TimesRoman,
    TimesBold,
    TimesItalic,
    TimesBoldItalic,
    Courier,
    Symbol,
    ZapfDingbats,
}

/// Maps a (possibly subset-tagged / aliased) base-font name onto a Core-14 face.
fn resolve_face(base_font: &str) -> Option<Face> {
    // Strip a subset prefix like "ABCDEF+" then lowercase for matching.
    let name = base_font.split('+').next_back().unwrap_or(base_font);
    let n = name.to_ascii_lowercase();

    let bold = n.contains("bold");
    let italic = n.contains("italic") || n.contains("oblique");

    // Order matters: check the specific decorative faces before the text families.
    if n.contains("zapf") || n.contains("dingbat") {
        return Some(Face::ZapfDingbats);
    }
    if n.contains("symbol") {
        return Some(Face::Symbol);
    }
    // "monotype" is a foundry name, not a monospaced face: Monotype Corsiva is a
    // script font and must not be given Courier's uniform 600-unit advance.
    if n.contains("courier") || (n.contains("mono") && !n.contains("monotype")) {
        return Some(Face::Courier);
    }
    // "sans" wins over "serif" because "sans-serif" contains both, and this test
    // runs first: without the exclusion every sans-serif face took Times metrics.
    if n.contains("times") || (n.contains("serif") && !n.contains("sans")) {
        return Some(match (bold, italic) {
            (true, true) => Face::TimesBoldItalic,
            (true, false) => Face::TimesBold,
            (false, true) => Face::TimesItalic,
            (false, false) => Face::TimesRoman,
        });
    }
    if n.contains("helvetica") || n.contains("arial") || n.contains("sans") {
        return Some(match (bold, italic) {
            (true, true) => Face::HelveticaBoldOblique,
            (true, false) => Face::HelveticaBold,
            (false, true) => Face::HelveticaOblique,
            (false, false) => Face::Helvetica,
        });
    }
    None
}

/// Builds a glyph-name -> text-space-width map from an AFM `(name, WX)` slice.
fn build(entries: &[(&str, u16)]) -> HashMap<String, f64> {
    entries
        .iter()
        .map(|(name, wx)| ((*name).to_string(), f64::from(*wx) / 1000.0))
        .collect()
}

/// Courier and its variants are monospaced: every glyph advances 600 AFM units
/// (0.6). We synthesize the map from the full glyph-name set so any WinAnsi /
/// StandardEncoding name resolves to the correct 0.6.
fn build_courier() -> HashMap<String, f64> {
    HELVETICA
        .iter()
        .map(|(name, _)| ((*name).to_string(), 0.6))
        .collect()
}

// ---------------------------------------------------------------------------
// Canonical Adobe Core-14 AFM widths (name, WX-in-1000-units).
//
// These are the published, standardized AFM `WX` values. Covers the full
// StandardEncoding / WinAnsi Latin set (ASCII 0x20-0x7E plus common Latin-1
// accented glyphs and punctuation).
// ---------------------------------------------------------------------------

static HELVETICA: &[(&str, u16)] = &[
    ("Euro", 556),
    ("space", 278), ("exclam", 278), ("quotedbl", 355), ("numbersign", 556),
    ("dollar", 556), ("percent", 889), ("ampersand", 667), ("quotesingle", 191),
    ("quoteright", 222), ("parenleft", 333), ("parenright", 333), ("asterisk", 389),
    ("plus", 584), ("comma", 278), ("hyphen", 333), ("period", 278), ("slash", 278),
    ("zero", 556), ("one", 556), ("two", 556), ("three", 556), ("four", 556),
    ("five", 556), ("six", 556), ("seven", 556), ("eight", 556), ("nine", 556),
    ("colon", 278), ("semicolon", 278), ("less", 584), ("equal", 584),
    ("greater", 584), ("question", 556), ("at", 1015),
    ("A", 667), ("B", 667), ("C", 722), ("D", 722), ("E", 667), ("F", 611),
    ("G", 778), ("H", 722), ("I", 278), ("J", 500), ("K", 667), ("L", 556),
    ("M", 833), ("N", 722), ("O", 778), ("P", 667), ("Q", 778), ("R", 722),
    ("S", 667), ("T", 611), ("U", 722), ("V", 667), ("W", 944), ("X", 667),
    ("Y", 667), ("Z", 611),
    ("bracketleft", 278), ("backslash", 278), ("bracketright", 278),
    ("asciicircum", 469), ("underscore", 556), ("grave", 333), ("quoteleft", 222),
    ("a", 556), ("b", 556), ("c", 500), ("d", 556), ("e", 556), ("f", 278),
    ("g", 556), ("h", 556), ("i", 222), ("j", 222), ("k", 500), ("l", 222),
    ("m", 833), ("n", 556), ("o", 556), ("p", 556), ("q", 556), ("r", 333),
    ("s", 500), ("t", 278), ("u", 556), ("v", 500), ("w", 722), ("x", 500),
    ("y", 500), ("z", 500),
    ("braceleft", 334), ("bar", 260), ("braceright", 334), ("asciitilde", 584),
    // Latin-1 / punctuation extras.
    ("exclamdown", 333), ("cent", 556), ("sterling", 556), ("fraction", 167),
    ("yen", 556), ("florin", 556), ("section", 556), ("currency", 556),
    ("quotedblleft", 333), ("guillemotleft", 556), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 500), ("fl", 500), ("endash", 556),
    ("dagger", 556), ("daggerdbl", 556), ("periodcentered", 278), ("paragraph", 537),
    ("bullet", 350), ("quotesinglbase", 222), ("quotedblbase", 333),
    ("quotedblright", 333), ("guillemotright", 556), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 611), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 1000),
    ("ordfeminine", 370), ("Lslash", 556), ("Oslash", 778), ("OE", 1000),
    ("ordmasculine", 365), ("ae", 889), ("dotlessi", 278), ("lslash", 222),
    ("oslash", 611), ("oe", 944), ("germandbls", 611),
    ("Aacute", 667), ("Acircumflex", 667), ("Adieresis", 667), ("Agrave", 667),
    ("Aring", 667), ("Atilde", 667), ("Ccedilla", 722), ("Eacute", 667),
    ("Ecircumflex", 667), ("Edieresis", 667), ("Egrave", 667), ("Iacute", 278),
    ("Icircumflex", 278), ("Idieresis", 278), ("Igrave", 278), ("Ntilde", 722),
    ("Oacute", 778), ("Ocircumflex", 778), ("Odieresis", 778), ("Ograve", 778),
    ("Otilde", 778), ("Scaron", 667), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 667), ("Ydieresis", 667),
    ("Zcaron", 611), ("Thorn", 667), ("Eth", 722),
    ("aacute", 556), ("acircumflex", 556), ("adieresis", 556), ("agrave", 556),
    ("aring", 556), ("atilde", 556), ("ccedilla", 500), ("eacute", 556),
    ("ecircumflex", 556), ("edieresis", 556), ("egrave", 556), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 556),
    ("oacute", 556), ("ocircumflex", 556), ("odieresis", 556), ("ograve", 556),
    ("otilde", 556), ("scaron", 500), ("uacute", 556), ("ucircumflex", 556),
    ("udieresis", 556), ("ugrave", 556), ("yacute", 500), ("ydieresis", 500),
    ("zcaron", 500), ("thorn", 556), ("eth", 556), ("mu", 556), ("degree", 400),
    ("plusminus", 584), ("twosuperior", 333), ("threesuperior", 333),
    ("onesuperior", 333), ("onehalf", 834), ("onequarter", 834),
    ("threequarters", 834), ("multiply", 584), ("divide", 584), ("brokenbar", 260),
    ("logicalnot", 584), ("registered", 737), ("copyright", 737),
    ("trademark", 1000), ("minus", 584),
];

static HELVETICA_BOLD: &[(&str, u16)] = &[
    ("Euro", 556),
    ("space", 278), ("exclam", 333), ("quotedbl", 474), ("numbersign", 556),
    ("dollar", 556), ("percent", 889), ("ampersand", 722), ("quotesingle", 238),
    ("quoteright", 278), ("parenleft", 333), ("parenright", 333), ("asterisk", 389),
    ("plus", 584), ("comma", 278), ("hyphen", 333), ("period", 278), ("slash", 278),
    ("zero", 556), ("one", 556), ("two", 556), ("three", 556), ("four", 556),
    ("five", 556), ("six", 556), ("seven", 556), ("eight", 556), ("nine", 556),
    ("colon", 333), ("semicolon", 333), ("less", 584), ("equal", 584),
    ("greater", 584), ("question", 611), ("at", 975),
    ("A", 722), ("B", 722), ("C", 722), ("D", 722), ("E", 667), ("F", 611),
    ("G", 778), ("H", 722), ("I", 278), ("J", 556), ("K", 722), ("L", 611),
    ("M", 833), ("N", 722), ("O", 778), ("P", 667), ("Q", 778), ("R", 722),
    ("S", 667), ("T", 611), ("U", 722), ("V", 667), ("W", 944), ("X", 667),
    ("Y", 667), ("Z", 611),
    ("bracketleft", 333), ("backslash", 278), ("bracketright", 333),
    ("asciicircum", 584), ("underscore", 556), ("grave", 333), ("quoteleft", 278),
    ("a", 556), ("b", 611), ("c", 556), ("d", 611), ("e", 556), ("f", 333),
    ("g", 611), ("h", 611), ("i", 278), ("j", 278), ("k", 556), ("l", 278),
    ("m", 889), ("n", 611), ("o", 611), ("p", 611), ("q", 611), ("r", 389),
    ("s", 556), ("t", 333), ("u", 611), ("v", 556), ("w", 778), ("x", 556),
    ("y", 556), ("z", 500),
    ("braceleft", 389), ("bar", 280), ("braceright", 389), ("asciitilde", 584),
    ("exclamdown", 333), ("cent", 556), ("sterling", 556), ("fraction", 167),
    ("yen", 556), ("florin", 556), ("section", 556), ("currency", 556),
    ("quotedblleft", 500), ("guillemotleft", 556), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 611), ("fl", 611), ("endash", 556),
    ("dagger", 556), ("daggerdbl", 556), ("periodcentered", 278), ("paragraph", 556),
    ("bullet", 350), ("quotesinglbase", 278), ("quotedblbase", 500),
    ("quotedblright", 500), ("guillemotright", 556), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 611), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 1000),
    ("ordfeminine", 370), ("Lslash", 611), ("Oslash", 778), ("OE", 1000),
    ("ordmasculine", 365), ("ae", 889), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 611), ("oe", 944), ("germandbls", 611),
    ("Aacute", 722), ("Acircumflex", 722), ("Adieresis", 722), ("Agrave", 722),
    ("Aring", 722), ("Atilde", 722), ("Ccedilla", 722), ("Eacute", 667),
    ("Ecircumflex", 667), ("Edieresis", 667), ("Egrave", 667), ("Iacute", 278),
    ("Icircumflex", 278), ("Idieresis", 278), ("Igrave", 278), ("Ntilde", 722),
    ("Oacute", 778), ("Ocircumflex", 778), ("Odieresis", 778), ("Ograve", 778),
    ("Otilde", 778), ("Scaron", 667), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 667), ("Ydieresis", 667),
    ("Zcaron", 611), ("Thorn", 667), ("Eth", 722),
    ("aacute", 556), ("acircumflex", 556), ("adieresis", 556), ("agrave", 556),
    ("aring", 556), ("atilde", 556), ("ccedilla", 556), ("eacute", 556),
    ("ecircumflex", 556), ("edieresis", 556), ("egrave", 556), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 611),
    ("oacute", 611), ("ocircumflex", 611), ("odieresis", 611), ("ograve", 611),
    ("otilde", 611), ("scaron", 556), ("uacute", 611), ("ucircumflex", 611),
    ("udieresis", 611), ("ugrave", 611), ("yacute", 556), ("ydieresis", 556),
    ("zcaron", 500), ("thorn", 611), ("eth", 611), ("mu", 611), ("degree", 400),
    ("plusminus", 584), ("twosuperior", 333), ("threesuperior", 333),
    ("onesuperior", 333), ("onehalf", 834), ("onequarter", 834),
    ("threequarters", 834), ("multiply", 584), ("divide", 584), ("brokenbar", 280),
    ("logicalnot", 584), ("registered", 737), ("copyright", 737),
    ("trademark", 1000), ("minus", 584),
];

static TIMES_ROMAN: &[(&str, u16)] = &[
    ("Euro", 500),
    ("space", 250), ("exclam", 333), ("quotedbl", 408), ("numbersign", 500),
    ("dollar", 500), ("percent", 833), ("ampersand", 778), ("quotesingle", 180),
    ("quoteright", 333), ("parenleft", 333), ("parenright", 333), ("asterisk", 500),
    ("plus", 564), ("comma", 250), ("hyphen", 333), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 278), ("semicolon", 278), ("less", 564), ("equal", 564),
    ("greater", 564), ("question", 444), ("at", 921),
    ("A", 722), ("B", 667), ("C", 667), ("D", 722), ("E", 611), ("F", 556),
    ("G", 722), ("H", 722), ("I", 333), ("J", 389), ("K", 722), ("L", 611),
    ("M", 889), ("N", 722), ("O", 722), ("P", 556), ("Q", 722), ("R", 667),
    ("S", 556), ("T", 611), ("U", 722), ("V", 722), ("W", 944), ("X", 722),
    ("Y", 722), ("Z", 611),
    ("bracketleft", 333), ("backslash", 278), ("bracketright", 333),
    ("asciicircum", 469), ("underscore", 500), ("grave", 333), ("quoteleft", 333),
    ("a", 444), ("b", 500), ("c", 444), ("d", 500), ("e", 444), ("f", 333),
    ("g", 500), ("h", 500), ("i", 278), ("j", 278), ("k", 500), ("l", 278),
    ("m", 778), ("n", 500), ("o", 500), ("p", 500), ("q", 500), ("r", 333),
    ("s", 389), ("t", 278), ("u", 500), ("v", 500), ("w", 722), ("x", 500),
    ("y", 500), ("z", 444),
    ("braceleft", 480), ("bar", 200), ("braceright", 480), ("asciitilde", 541),
    ("exclamdown", 333), ("cent", 500), ("sterling", 500), ("fraction", 167),
    ("yen", 500), ("florin", 500), ("section", 500), ("currency", 500),
    ("quotedblleft", 444), ("guillemotleft", 500), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 556), ("fl", 556), ("endash", 500),
    ("dagger", 500), ("daggerdbl", 500), ("periodcentered", 250), ("paragraph", 453),
    ("bullet", 350), ("quotesinglbase", 333), ("quotedblbase", 444),
    ("quotedblright", 444), ("guillemotright", 500), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 444), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 889),
    ("ordfeminine", 276), ("Lslash", 611), ("Oslash", 722), ("OE", 889),
    ("ordmasculine", 310), ("ae", 667), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 500), ("oe", 722), ("germandbls", 500),
    ("Aacute", 722), ("Acircumflex", 722), ("Adieresis", 722), ("Agrave", 722),
    ("Aring", 722), ("Atilde", 722), ("Ccedilla", 667), ("Eacute", 611),
    ("Ecircumflex", 611), ("Edieresis", 611), ("Egrave", 611), ("Iacute", 333),
    ("Icircumflex", 333), ("Idieresis", 333), ("Igrave", 333), ("Ntilde", 722),
    ("Oacute", 722), ("Ocircumflex", 722), ("Odieresis", 722), ("Ograve", 722),
    ("Otilde", 722), ("Scaron", 556), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 722), ("Ydieresis", 722),
    ("Zcaron", 611), ("Thorn", 556), ("Eth", 722),
    ("aacute", 444), ("acircumflex", 444), ("adieresis", 444), ("agrave", 444),
    ("aring", 444), ("atilde", 444), ("ccedilla", 444), ("eacute", 444),
    ("ecircumflex", 444), ("edieresis", 444), ("egrave", 444), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 500),
    ("oacute", 500), ("ocircumflex", 500), ("odieresis", 500), ("ograve", 500),
    ("otilde", 500), ("scaron", 389), ("uacute", 500), ("ucircumflex", 500),
    ("udieresis", 500), ("ugrave", 500), ("yacute", 500), ("ydieresis", 500),
    ("zcaron", 444), ("thorn", 500), ("eth", 500), ("mu", 500), ("degree", 400),
    ("plusminus", 564), ("twosuperior", 300), ("threesuperior", 300),
    ("onesuperior", 300), ("onehalf", 750), ("onequarter", 750),
    ("threequarters", 750), ("multiply", 564), ("divide", 564), ("brokenbar", 200),
    ("logicalnot", 564), ("registered", 760), ("copyright", 760),
    ("trademark", 980), ("minus", 564),
];

static TIMES_BOLD: &[(&str, u16)] = &[
    ("Euro", 500),
    ("space", 250), ("exclam", 333), ("quotedbl", 555), ("numbersign", 500),
    ("dollar", 500), ("percent", 1000), ("ampersand", 833), ("quotesingle", 278),
    ("quoteright", 333), ("parenleft", 333), ("parenright", 333), ("asterisk", 500),
    ("plus", 570), ("comma", 250), ("hyphen", 333), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 333), ("semicolon", 333), ("less", 570), ("equal", 570),
    ("greater", 570), ("question", 500), ("at", 930),
    ("A", 722), ("B", 667), ("C", 722), ("D", 722), ("E", 667), ("F", 611),
    ("G", 778), ("H", 778), ("I", 389), ("J", 500), ("K", 778), ("L", 667),
    ("M", 944), ("N", 722), ("O", 778), ("P", 611), ("Q", 778), ("R", 722),
    ("S", 556), ("T", 667), ("U", 722), ("V", 722), ("W", 1000), ("X", 722),
    ("Y", 722), ("Z", 667),
    ("bracketleft", 333), ("backslash", 278), ("bracketright", 333),
    ("asciicircum", 581), ("underscore", 500), ("grave", 333), ("quoteleft", 333),
    ("a", 500), ("b", 556), ("c", 444), ("d", 556), ("e", 444), ("f", 333),
    ("g", 500), ("h", 556), ("i", 278), ("j", 333), ("k", 556), ("l", 278),
    ("m", 833), ("n", 556), ("o", 500), ("p", 556), ("q", 556), ("r", 444),
    ("s", 389), ("t", 333), ("u", 556), ("v", 500), ("w", 722), ("x", 500),
    ("y", 500), ("z", 444),
    ("braceleft", 394), ("bar", 220), ("braceright", 394), ("asciitilde", 520),
    ("exclamdown", 333), ("cent", 500), ("sterling", 500), ("fraction", 167),
    ("yen", 500), ("florin", 500), ("section", 500), ("currency", 500),
    ("quotedblleft", 500), ("guillemotleft", 500), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 556), ("fl", 556), ("endash", 500),
    ("dagger", 500), ("daggerdbl", 500), ("periodcentered", 250), ("paragraph", 540),
    ("bullet", 350), ("quotesinglbase", 333), ("quotedblbase", 500),
    ("quotedblright", 500), ("guillemotright", 500), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 500), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 1000),
    ("ordfeminine", 300), ("Lslash", 667), ("Oslash", 778), ("OE", 1000),
    ("ordmasculine", 330), ("ae", 722), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 500), ("oe", 722), ("germandbls", 556),
    ("Aacute", 722), ("Acircumflex", 722), ("Adieresis", 722), ("Agrave", 722),
    ("Aring", 722), ("Atilde", 722), ("Ccedilla", 722), ("Eacute", 667),
    ("Ecircumflex", 667), ("Edieresis", 667), ("Egrave", 667), ("Iacute", 389),
    ("Icircumflex", 389), ("Idieresis", 389), ("Igrave", 389), ("Ntilde", 722),
    ("Oacute", 778), ("Ocircumflex", 778), ("Odieresis", 778), ("Ograve", 778),
    ("Otilde", 778), ("Scaron", 556), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 722), ("Ydieresis", 722),
    ("Zcaron", 667), ("Thorn", 611), ("Eth", 722),
    ("aacute", 500), ("acircumflex", 500), ("adieresis", 500), ("agrave", 500),
    ("aring", 500), ("atilde", 500), ("ccedilla", 444), ("eacute", 444),
    ("ecircumflex", 444), ("edieresis", 444), ("egrave", 444), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 556),
    ("oacute", 500), ("ocircumflex", 500), ("odieresis", 500), ("ograve", 500),
    ("otilde", 500), ("scaron", 389), ("uacute", 556), ("ucircumflex", 556),
    ("udieresis", 556), ("ugrave", 556), ("yacute", 500), ("ydieresis", 500),
    ("zcaron", 444), ("thorn", 556), ("eth", 500), ("mu", 556), ("degree", 400),
    ("plusminus", 570), ("twosuperior", 300), ("threesuperior", 300),
    ("onesuperior", 300), ("onehalf", 750), ("onequarter", 750),
    ("threequarters", 750), ("multiply", 570), ("divide", 570), ("brokenbar", 220),
    ("logicalnot", 570), ("registered", 747), ("copyright", 747),
    ("trademark", 1000), ("minus", 570),
];

static TIMES_ITALIC: &[(&str, u16)] = &[
    ("Euro", 500),
    ("space", 250), ("exclam", 333), ("quotedbl", 420), ("numbersign", 500),
    ("dollar", 500), ("percent", 833), ("ampersand", 778), ("quotesingle", 214),
    ("quoteright", 333), ("parenleft", 333), ("parenright", 333), ("asterisk", 500),
    ("plus", 675), ("comma", 250), ("hyphen", 333), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 333), ("semicolon", 333), ("less", 675), ("equal", 675),
    ("greater", 675), ("question", 500), ("at", 920),
    ("A", 611), ("B", 611), ("C", 667), ("D", 722), ("E", 611), ("F", 611),
    ("G", 722), ("H", 722), ("I", 333), ("J", 444), ("K", 667), ("L", 556),
    ("M", 833), ("N", 667), ("O", 722), ("P", 611), ("Q", 722), ("R", 611),
    ("S", 500), ("T", 556), ("U", 722), ("V", 611), ("W", 833), ("X", 611),
    ("Y", 556), ("Z", 556),
    ("bracketleft", 389), ("backslash", 278), ("bracketright", 389),
    ("asciicircum", 422), ("underscore", 500), ("grave", 333), ("quoteleft", 333),
    ("a", 500), ("b", 500), ("c", 444), ("d", 500), ("e", 444), ("f", 278),
    ("g", 500), ("h", 500), ("i", 278), ("j", 278), ("k", 444), ("l", 278),
    ("m", 722), ("n", 500), ("o", 500), ("p", 500), ("q", 500), ("r", 389),
    ("s", 389), ("t", 278), ("u", 500), ("v", 444), ("w", 667), ("x", 444),
    ("y", 444), ("z", 389),
    ("braceleft", 400), ("bar", 275), ("braceright", 400), ("asciitilde", 541),
    ("exclamdown", 389), ("cent", 500), ("sterling", 500), ("fraction", 167),
    ("yen", 500), ("florin", 500), ("section", 500), ("currency", 500),
    ("quotedblleft", 556), ("guillemotleft", 500), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 500), ("fl", 500), ("endash", 500),
    ("dagger", 500), ("daggerdbl", 500), ("periodcentered", 250), ("paragraph", 523),
    ("bullet", 350), ("quotesinglbase", 333), ("quotedblbase", 556),
    ("quotedblright", 556), ("guillemotright", 500), ("ellipsis", 889),
    ("perthousand", 1000), ("questiondown", 500), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 889), ("AE", 889),
    ("ordfeminine", 276), ("Lslash", 556), ("Oslash", 722), ("OE", 944),
    ("ordmasculine", 310), ("ae", 667), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 500), ("oe", 667), ("germandbls", 500),
    ("Aacute", 611), ("Acircumflex", 611), ("Adieresis", 611), ("Agrave", 611),
    ("Aring", 611), ("Atilde", 611), ("Ccedilla", 667), ("Eacute", 611),
    ("Ecircumflex", 611), ("Edieresis", 611), ("Egrave", 611), ("Iacute", 333),
    ("Icircumflex", 333), ("Idieresis", 333), ("Igrave", 333), ("Ntilde", 667),
    ("Oacute", 722), ("Ocircumflex", 722), ("Odieresis", 722), ("Ograve", 722),
    ("Otilde", 722), ("Scaron", 500), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 556), ("Ydieresis", 556),
    ("Zcaron", 556), ("Thorn", 611), ("Eth", 722),
    ("aacute", 500), ("acircumflex", 500), ("adieresis", 500), ("agrave", 500),
    ("aring", 500), ("atilde", 500), ("ccedilla", 444), ("eacute", 444),
    ("ecircumflex", 444), ("edieresis", 444), ("egrave", 444), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 500),
    ("oacute", 500), ("ocircumflex", 500), ("odieresis", 500), ("ograve", 500),
    ("otilde", 500), ("scaron", 389), ("uacute", 500), ("ucircumflex", 500),
    ("udieresis", 500), ("ugrave", 500), ("yacute", 444), ("ydieresis", 444),
    ("zcaron", 389), ("thorn", 500), ("eth", 500), ("mu", 500), ("degree", 400),
    ("plusminus", 675), ("twosuperior", 300), ("threesuperior", 300),
    ("onesuperior", 300), ("onehalf", 750), ("onequarter", 750),
    ("threequarters", 750), ("multiply", 675), ("divide", 675), ("brokenbar", 275),
    ("logicalnot", 675), ("registered", 760), ("copyright", 760),
    ("trademark", 980), ("minus", 675),
];

static TIMES_BOLD_ITALIC: &[(&str, u16)] = &[
    ("Euro", 500),
    ("space", 250), ("exclam", 389), ("quotedbl", 555), ("numbersign", 500),
    ("dollar", 500), ("percent", 833), ("ampersand", 778), ("quotesingle", 278),
    ("quoteright", 333), ("parenleft", 333), ("parenright", 333), ("asterisk", 500),
    ("plus", 570), ("comma", 250), ("hyphen", 333), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 333), ("semicolon", 333), ("less", 570), ("equal", 570),
    ("greater", 570), ("question", 500), ("at", 832),
    ("A", 667), ("B", 667), ("C", 667), ("D", 722), ("E", 667), ("F", 667),
    ("G", 722), ("H", 778), ("I", 389), ("J", 500), ("K", 667), ("L", 611),
    ("M", 889), ("N", 722), ("O", 722), ("P", 611), ("Q", 722), ("R", 667),
    ("S", 556), ("T", 611), ("U", 722), ("V", 667), ("W", 889), ("X", 667),
    ("Y", 611), ("Z", 611),
    ("bracketleft", 333), ("backslash", 278), ("bracketright", 333),
    ("asciicircum", 570), ("underscore", 500), ("grave", 333), ("quoteleft", 333),
    ("a", 500), ("b", 500), ("c", 444), ("d", 500), ("e", 444), ("f", 333),
    ("g", 500), ("h", 556), ("i", 278), ("j", 278), ("k", 500), ("l", 278),
    ("m", 778), ("n", 556), ("o", 500), ("p", 500), ("q", 500), ("r", 389),
    ("s", 389), ("t", 278), ("u", 556), ("v", 444), ("w", 667), ("x", 500),
    ("y", 444), ("z", 389),
    ("braceleft", 348), ("bar", 220), ("braceright", 348), ("asciitilde", 570),
    ("exclamdown", 389), ("cent", 500), ("sterling", 500), ("fraction", 167),
    ("yen", 500), ("florin", 500), ("section", 500), ("currency", 500),
    ("quotedblleft", 500), ("guillemotleft", 500), ("guilsinglleft", 333),
    ("guilsinglright", 333), ("fi", 556), ("fl", 556), ("endash", 500),
    ("dagger", 500), ("daggerdbl", 500), ("periodcentered", 250), ("paragraph", 500),
    ("bullet", 350), ("quotesinglbase", 333), ("quotedblbase", 500),
    ("quotedblright", 500), ("guillemotright", 500), ("ellipsis", 1000),
    ("perthousand", 1000), ("questiondown", 500), ("acute", 333), ("circumflex", 333),
    ("tilde", 333), ("macron", 333), ("breve", 333), ("dotaccent", 333),
    ("dieresis", 333), ("ring", 333), ("cedilla", 333), ("hungarumlaut", 333),
    ("ogonek", 333), ("caron", 333), ("emdash", 1000), ("AE", 944),
    ("ordfeminine", 266), ("Lslash", 611), ("Oslash", 722), ("OE", 944),
    ("ordmasculine", 300), ("ae", 722), ("dotlessi", 278), ("lslash", 278),
    ("oslash", 500), ("oe", 722), ("germandbls", 500),
    ("Aacute", 667), ("Acircumflex", 667), ("Adieresis", 667), ("Agrave", 667),
    ("Aring", 667), ("Atilde", 667), ("Ccedilla", 667), ("Eacute", 667),
    ("Ecircumflex", 667), ("Edieresis", 667), ("Egrave", 667), ("Iacute", 389),
    ("Icircumflex", 389), ("Idieresis", 389), ("Igrave", 389), ("Ntilde", 722),
    ("Oacute", 722), ("Ocircumflex", 722), ("Odieresis", 722), ("Ograve", 722),
    ("Otilde", 722), ("Scaron", 556), ("Uacute", 722), ("Ucircumflex", 722),
    ("Udieresis", 722), ("Ugrave", 722), ("Yacute", 611), ("Ydieresis", 611),
    ("Zcaron", 611), ("Thorn", 611), ("Eth", 722),
    ("aacute", 500), ("acircumflex", 500), ("adieresis", 500), ("agrave", 500),
    ("aring", 500), ("atilde", 500), ("ccedilla", 444), ("eacute", 444),
    ("ecircumflex", 444), ("edieresis", 444), ("egrave", 444), ("iacute", 278),
    ("icircumflex", 278), ("idieresis", 278), ("igrave", 278), ("ntilde", 556),
    ("oacute", 500), ("ocircumflex", 500), ("odieresis", 500), ("ograve", 500),
    ("otilde", 500), ("scaron", 389), ("uacute", 556), ("ucircumflex", 556),
    ("udieresis", 556), ("ugrave", 556), ("yacute", 444), ("ydieresis", 444),
    ("zcaron", 389), ("thorn", 500), ("eth", 500), ("mu", 576), ("degree", 400),
    ("plusminus", 570), ("twosuperior", 300), ("threesuperior", 300),
    ("onesuperior", 300), ("onehalf", 750), ("onequarter", 750),
    ("threequarters", 750), ("multiply", 570), ("divide", 570), ("brokenbar", 220),
    ("logicalnot", 606), ("registered", 747), ("copyright", 747),
    ("trademark", 1000), ("minus", 570),
];

// Symbol uses its own encoding; widths are keyed by the AFM glyph names.
//
// KNOWN GAP: this stops at code 0x7E ("similar"). Every Symbol code >= 0xA0 —
// the math operators, arrows, card suits and the bracket/integral build-up
// pieces — has no width here and falls to `default_width` (0.5 em) in
// `fonts.rs`. The real values are far from uniform (build-up pieces ~274-384,
// several operators 700+), so a line of Symbol math drifts both ways. Left
// unfilled rather than guessed: these numbers cannot be verified against
// Adobe's Symbol.afm from this tree, and a plausible-but-wrong advance for
// every math glyph is worse than one uniform one. Needs `/Widths` to be absent
// entirely to matter at all, which is legacy TeX/dvips-era files. The Greek
// alphabet is unaffected — Symbol puts Alpha..Omega and alpha..omega in
// 0x41-0x7A, inside the covered range.
//
// BEFORE ADDING ROWS, read this. `fonts.rs`'s code -> name chain ends in a
// last-resort guess against `type1::STANDARD_ENCODING`, and that step runs for
// every face, Symbol included, even though the two encodings are unrelated.
// Today it is harmless precisely BECAUSE this table stops at 0x7E: above it the
// Standard-guessed name ("section" for 0xA7, where Symbol has `club`) finds no
// row, so the lookup falls through to the code -> Unicode -> width path that
// does know about Symbol. Add a row whose name StandardEncoding also uses at a
// DIFFERENT code and the guess starts winning, silently charging that glyph the
// other encoding's width. The two encodings share exactly four names above
// 0xA0 — `fraction` (164), `florin` (166), `bullet` (183) and `ellipsis` (188)
// — and all four sit at the SAME code in both, so those are safe. Any name
// outside that set must be checked against `STANDARD_ENCODING` first.
//
// That check is ENFORCED, not just documented: `fonts.rs`'s
// `blind_reaudit_r5_width_tests::the_standard_encoding_guess_never_contradicts_symbols_own_encoding`
// walks every `STANDARD_ENCODING` entry and asserts that wherever the Standard
// guess and Symbol's own encoding both produce a width for a code, they agree.
// A colliding row added here fails that test with the code and both widths.
static SYMBOL: &[(&str, u16)] = &[
    ("space", 250), ("exclam", 333), ("universal", 713), ("numbersign", 500),
    ("existential", 549), ("percent", 833), ("ampersand", 778), ("suchthat", 439),
    ("parenleft", 333), ("parenright", 333), ("asteriskmath", 500), ("plus", 549),
    ("comma", 250), ("minus", 549), ("period", 250), ("slash", 278),
    ("zero", 500), ("one", 500), ("two", 500), ("three", 500), ("four", 500),
    ("five", 500), ("six", 500), ("seven", 500), ("eight", 500), ("nine", 500),
    ("colon", 278), ("semicolon", 278), ("less", 549), ("equal", 549),
    ("greater", 549), ("question", 444), ("congruent", 549),
    ("Alpha", 722), ("Beta", 667), ("Chi", 722), ("Delta", 612), ("Epsilon", 611),
    ("Phi", 763), ("Gamma", 603), ("Eta", 722), ("Iota", 333), ("theta1", 631),
    ("Kappa", 722), ("Lambda", 686), ("Mu", 889), ("Nu", 722), ("Omicron", 722),
    ("Pi", 768), ("Theta", 741), ("Rho", 556), ("Sigma", 592), ("Tau", 611),
    ("Upsilon", 690), ("sigma1", 439), ("Omega", 768), ("Xi", 645), ("Psi", 795),
    ("Zeta", 611), ("bracketleft", 333), ("therefore", 863), ("bracketright", 333),
    ("perpendicular", 658), ("underscore", 500), ("radicalex", 500),
    ("alpha", 631), ("beta", 549), ("chi", 549), ("delta", 494), ("epsilon", 439),
    ("phi", 521), ("gamma", 411), ("eta", 603), ("iota", 329), ("phi1", 603),
    ("kappa", 549), ("lambda", 549), ("mu", 576), ("nu", 521), ("omicron", 549),
    ("pi", 549), ("theta", 521), ("rho", 549), ("sigma", 603), ("tau", 439),
    ("upsilon", 576), ("omega1", 713), ("omega", 686), ("xi", 493), ("psi", 686),
    ("zeta", 494), ("braceleft", 480), ("bar", 200), ("braceright", 480),
    ("similar", 549),
];

// ZapfDingbats decorative face; widths keyed by the AFM `aNNN` glyph names.
static ZAPF_DINGBATS: &[(&str, u16)] = &[
    ("space", 278),
    ("a1", 974), ("a2", 961), ("a202", 974), ("a3", 980), ("a4", 719),
    ("a5", 789), ("a119", 790), ("a118", 791), ("a117", 690), ("a11", 960),
    ("a12", 939), ("a13", 549), ("a14", 855), ("a15", 911), ("a16", 933),
    ("a105", 911), ("a17", 945), ("a18", 974), ("a19", 755), ("a20", 846),
    ("a21", 762), ("a22", 761), ("a23", 571), ("a24", 677), ("a25", 763),
    ("a26", 760), ("a27", 759), ("a28", 754), ("a6", 494), ("a7", 552),
    ("a8", 537), ("a9", 577), ("a10", 692), ("a29", 786), ("a30", 788),
    ("a31", 788), ("a32", 790), ("a33", 793), ("a34", 794), ("a35", 816),
    ("a36", 823), ("a37", 789), ("a38", 841), ("a39", 823), ("a40", 833),
    ("a41", 816), ("a42", 831), ("a43", 923), ("a44", 744), ("a45", 723),
    ("a46", 749), ("a47", 790), ("a48", 792), ("a49", 695), ("a50", 776),
    ("a51", 768), ("a52", 792), ("a53", 759), ("a54", 707), ("a55", 708),
    ("a56", 682), ("a57", 701), ("a58", 826), ("a59", 815), ("a60", 789),
    ("a61", 789), ("a62", 707), ("a63", 687), ("a64", 696), ("a65", 689),
    ("a66", 786), ("a67", 787), ("a68", 713), ("a69", 791), ("a70", 785),
    ("a71", 791), ("a72", 873), ("a73", 761), ("a74", 762), ("a203", 762),
    ("a75", 759), ("a204", 759), ("a76", 892), ("a77", 892), ("a78", 788),
    ("a79", 784), ("a81", 438), ("a82", 138), ("a83", 277), ("a84", 415),
    ("a97", 392), ("a98", 392), ("a99", 668), ("a100", 668), ("a89", 390),
    ("a90", 390), ("a93", 317), ("a94", 317), ("a91", 276), ("a92", 276),
    ("a205", 509), ("a85", 509), ("a206", 410), ("a86", 410), ("a87", 234),
    ("a88", 234), ("a95", 334), ("a96", 334), ("a101", 732), ("a102", 544),
    ("a103", 544), ("a104", 910), ("a106", 667), ("a107", 760), ("a108", 760),
    ("a112", 776), ("a111", 595), ("a110", 694), ("a109", 626), ("a120", 788),
    ("a121", 788), ("a122", 788), ("a123", 788), ("a124", 788), ("a125", 788),
    ("a126", 788), ("a127", 788), ("a128", 788), ("a129", 788), ("a130", 788),
    ("a131", 788), ("a132", 788), ("a133", 788), ("a134", 788), ("a135", 788),
    ("a136", 788), ("a137", 788), ("a138", 788), ("a139", 788), ("a140", 788),
    ("a141", 788), ("a142", 788), ("a143", 788), ("a144", 788), ("a145", 788),
    ("a146", 788), ("a147", 788), ("a148", 788), ("a149", 788), ("a150", 788),
    ("a151", 788), ("a152", 788), ("a153", 788), ("a154", 788), ("a155", 788),
    ("a156", 788), ("a157", 788), ("a158", 788), ("a159", 788), ("a160", 894),
    ("a161", 838), ("a163", 1016), ("a164", 458), ("a196", 748), ("a165", 924),
    ("a192", 748), ("a166", 918), ("a167", 927), ("a168", 928), ("a169", 928),
    ("a170", 834), ("a171", 873), ("a172", 828), ("a173", 924), ("a162", 924),
    ("a174", 917), ("a175", 930), ("a176", 931), ("a177", 463), ("a178", 883),
    ("a179", 836), ("a193", 836), ("a180", 867), ("a199", 867), ("a181", 696),
    ("a200", 696), ("a182", 874), ("a201", 760), ("a183", 946), ("a184", 771),
    ("a197", 865), ("a185", 771), ("a194", 888), ("a198", 967), ("a186", 888),
    ("a195", 831), ("a187", 873), ("a188", 927), ("a189", 970), ("a190", 918),
    ("a191", 748),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn helvetica_metrics() {
        let m = standard_14_widths("Helvetica").unwrap();
        assert_eq!(m["space"], 0.278);
        assert_eq!(m["W"], 0.944);
    }

    #[test]
    fn times_metrics() {
        let m = standard_14_widths("Times-Roman").unwrap();
        assert_eq!(m["space"], 0.25);
    }

    #[test]
    fn courier_is_monospaced() {
        let m = standard_14_widths("Courier").unwrap();
        assert_eq!(m["m"], 0.6);
        assert_eq!(m["i"], 0.6);
    }

    #[test]
    fn tolerates_subset_prefix_and_aliases() {
        assert!(standard_14_widths("ABCDEF+Helvetica-BoldOblique").is_some());
        assert!(standard_14_widths("Arial").is_some());
        assert!(standard_14_widths("Arial-BoldMT").is_some());
        assert!(standard_14_widths("TimesNewRoman").is_some());
        assert!(standard_14_widths("Symbol").is_some());
        assert!(standard_14_widths("ZapfDingbats").is_some());
        assert!(standard_14_widths("SomeRandomFont").is_none());
    }

    #[test]
    fn foundry_and_family_names_do_not_hijack_a_face() {
        // "Monotype Corsiva" is a script face; it contains "mono" but is not
        // monospaced, and Courier's flat 600 would mis-space every glyph.
        let corsiva = standard_14_widths("MonotypeCorsiva");
        assert!(corsiva.is_none() || corsiva.as_ref().unwrap()["i"] != 0.6);
        // "sans-serif" contains "serif"; the serif test runs first, so without the
        // exclusion a sans face was given Times metrics.
        let sans = standard_14_widths("Some-Sans-Serif").expect("resolves to Helvetica");
        assert_eq!(sans["space"], 0.278, "sans-serif must not take Times' 250");
        // A real serif name still resolves to Times.
        assert_eq!(standard_14_widths("NimbusSerif").unwrap()["space"], 0.25);
    }
}
