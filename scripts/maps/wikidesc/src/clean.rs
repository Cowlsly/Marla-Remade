//! Turning a Wikipedia lead paragraph into one line of plain text.
//!
//! The lead of a geography article is written for a page with hyperlinks and footnotes, and most
//! of what makes it long is not information — it is the same place name repeated in other
//! languages, link markup around words that are already there, and reference markers. On a phone
//! sheet none of that survives contact with a reader, and in an archive shipped to every device it
//! is the difference between a download people accept and one they do not.
//!
//! The worked example this module is specified by:
//!
//! ```text
//! Săcălaz (formerly Săcalhaz; [German](…): Sackelhausen; [Banat Swabian](…): Sacklass;[\[3\]](…)
//! [Hungarian](…): Szakálháza) is a [commune](…) in [Timiș County](…), [Romania](…). It is
//! composed of three villages: Beregsău Mare, Beregsău Mic and Săcălaz (commune seat).
//! ```
//!
//! becomes
//!
//! ```text
//! Săcălaz is a commune in Timiș County, Romania. It is composed of three villages: Beregsău Mare,
//! Beregsău Mic and Săcălaz.
//! ```
//!
//! Note what happens to each construct: a link keeps its *text* and loses its target, because the
//! text is the sentence; a reference marker goes entirely; and a parenthesis goes entirely,
//! brackets and all. That last rule is the aggressive one and it is deliberate — see
//! [`strip_parentheticals`].

/// Reduce a lead paragraph to one line of plain prose.
pub fn clean(raw: &str) -> String {
    let text = unwrap_links(raw);
    let text = strip_parentheticals(&text);
    tidy(&text)
}

/// `[text](url)` becomes `text`; a reference marker becomes nothing.
///
/// A reference is a link whose text is itself bracketed — `[\[3\]](…)` — which is what a footnote
/// looks like once the page is rendered to markdown. Recognising it by shape rather than by
/// number also catches `[\[note 1\]]` and `[\[a\]]`.
fn unwrap_links(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let bytes: Vec<char> = raw.chars().collect();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == '[' {
            if let Some((text, next)) = read_link(&bytes, i) {
                if !is_reference(&text) {
                    out.push_str(&text);
                }
                i = next;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// Read `[text](url)` starting at `open`, returning the text and the index just past the link.
///
/// The label may contain balanced brackets — a reference marker is exactly that — so the scan
/// counts depth rather than stopping at the first `]`.
fn read_link(chars: &[char], open: usize) -> Option<(String, usize)> {
    let mut depth = 0usize;
    let mut close = None;
    for (offset, &c) in chars.iter().enumerate().skip(open) {
        match c {
            '[' => depth += 1,
            ']' => {
                depth -= 1;
                if depth == 0 {
                    close = Some(offset);
                    break;
                }
            }
            _ => {}
        }
    }
    let close = close?;
    // A bracketed span is only a link if a parenthesised target follows immediately.
    if chars.get(close + 1) != Some(&'(') {
        return None;
    }
    let mut depth = 0usize;
    for (offset, &c) in chars.iter().enumerate().skip(close + 1) {
        match c {
            '(' => depth += 1,
            ')' => {
                depth -= 1;
                if depth == 0 {
                    let text: String = chars[open + 1..close].iter().collect();
                    return Some((text, offset + 1));
                }
            }
            _ => {}
        }
    }
    None
}

/// A link label that is itself bracketed, i.e. a footnote marker rather than prose.
///
/// The backslashes survive the markdown conversion, so `\[3\]` is what actually arrives.
fn is_reference(text: &str) -> bool {
    let t = text.trim();
    let t = t.strip_prefix('\\').unwrap_or(t);
    t.starts_with('[')
}

/// Remove every parenthesised span, brackets and contents.
///
/// **Aggressive on purpose.** In the lead of a settlement article a parenthesis is almost always
/// the name again — `(formerly …; German: …; Hungarian: …)` — and a reader on a phone who already
/// has the name in front of them gains nothing from four more spellings of it. Keeping only the
/// ones that *look* like name lists was the alternative and it is not worth it: the test would be
/// a list of language names, which is unbounded, and the failure mode is silently keeping a
/// paragraph of Cyrillic in a sheet three lines tall.
///
/// The cost is real and accepted: `(commune seat)` and `(pop. 12,000)` go too.
///
/// Nesting is counted, so `(a (b) c)` disappears as a unit rather than leaving `c)` behind.
fn strip_parentheticals(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut depth = 0usize;
    for c in text.chars() {
        match c {
            '(' => depth += 1,
            ')' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out
}

/// Collapse the whitespace the removals leave behind, and close the gaps before punctuation.
///
/// Cutting ` (commune seat)` out of `… Săcălaz (commune seat).` leaves `… Săcălaz .`, which is the
/// giveaway that a sheet was assembled by a script. Runs of spaces collapse, a space before `.`
/// `,` `;` `:` closes up, and the line is trimmed.
fn tidy(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut pending_space = false;
    for c in text.chars() {
        if c.is_whitespace() {
            pending_space = !out.is_empty();
            continue;
        }
        if pending_space && !matches!(c, '.' | ',' | ';' | ':' | '!' | '?') {
            out.push(' ');
        }
        pending_space = false;
        out.push(c);
    }
    out.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The worked example from the spec, end to end.
    #[test]
    fn a_settlement_lead_loses_its_names_links_and_footnotes() {
        let raw = "Săcălaz (formerly Săcalhaz; [German](https://en.wikipedia.org/wiki/German_language): \
                   Sackelhausen; [Banat Swabian](https://en.wikipedia.org/wiki/Banat_Swabians): \
                   Sacklass;[\\[3\\]](https://en.wikipedia.org/wiki/S%C4%83c%C4%83laz#cite_note-3) \
                   [Hungarian](https://en.wikipedia.org/wiki/Hungarian_language): Szakálháza) is a \
                   [commune](https://en.wikipedia.org/wiki/Communes_of_Romania) in \
                   [Timiș County](https://en.wikipedia.org/wiki/Timi%C8%99_County), \
                   [Romania](https://en.wikipedia.org/wiki/Romania). It is composed of three \
                   villages: Beregsău Mare, Beregsău Mic and Săcălaz (commune seat).";
        assert_eq!(
            clean(raw),
            "Săcălaz is a commune in Timiș County, Romania. It is composed of three villages: \
             Beregsău Mare, Beregsău Mic and Săcălaz.",
        );
    }

    #[test]
    fn a_link_keeps_its_text_and_loses_its_target() {
        assert_eq!(clean("a [commune](https://x/y) in [Timiș](https://x/z)"), "a commune in Timiș");
    }

    /// A footnote is a link whose label is itself bracketed. Recognised by shape, so `[\[note 1\]]`
    /// and `[\[a\]]` go the same way as `[\[3\]]`.
    #[test]
    fn a_reference_marker_leaves_nothing_behind() {
        assert_eq!(clean("Sacklass;[\\[3\\]](https://x/#cite_note-3) and more"), "Sacklass; and more");
        assert_eq!(clean("text[\\[note 1\\]](https://x/#n) end"), "text end");
        assert_eq!(clean("text[\\[a\\]](https://x/#a)"), "text");
    }

    /// The aggressive rule, stated as a test so it is a decision rather than an accident.
    #[test]
    fn every_parenthesis_goes_including_the_useful_ones() {
        assert_eq!(clean("Săcălaz (commune seat)."), "Săcălaz.");
        assert_eq!(clean("Springfield (pop. 12,000) is a city."), "Springfield is a city.");
        // Nested, so the inner close must not end the outer span.
        assert_eq!(clean("A (b (c) d) e"), "A e");
    }

    #[test]
    fn the_space_left_by_a_removal_is_closed_up() {
        assert_eq!(clean("Săcălaz  (x)  ,  Romania ."), "Săcălaz, Romania.");
        assert_eq!(clean("  leading and trailing  "), "leading and trailing");
        assert_eq!(clean("line\nbreaks\tcollapse"), "line breaks collapse");
    }

    /// Bracketed text with no `(target)` after it is prose, not a link.
    #[test]
    fn a_bracket_that_is_not_a_link_survives() {
        assert_eq!(clean("the [sic] spelling"), "the [sic] spelling");
    }

    #[test]
    fn plain_prose_is_returned_unchanged() {
        let plain = "Paris is the capital and most populous city of France.";
        assert_eq!(clean(plain), plain);
    }

    /// Malformed input is common in a dump of five million articles and must not panic or hang.
    #[test]
    fn unbalanced_markup_does_not_panic() {
        for raw in ["unclosed [link](http://x", "stray ) paren", "open ( paren", "[", "]", "()", ""] {
            let _ = clean(raw);
        }
    }
}
