//! Streaming reader for the English Wikipedia abstracts dump.
//!
//! `enwiki-latest-abstract.xml` is the lead of every article, already extracted, in one file. That
//! is exactly what this archive needs, and it is why the generator reads a dump instead of calling
//! the API: the admin-place set is of order 10^5-10^6, and that many HTTP requests would need
//! caching, resumability and rate limiting, and would take hours. The dump is one download and one
//! sequential pass.
//!
//! The shape, repeated for every article:
//!
//! ```xml
//! <doc>
//! <title>Wikipedia: Săcălaz</title>
//! <url>https://en.wikipedia.org/wiki/S%C4%83c%C4%83laz</url>
//! <abstract>Săcălaz (formerly Săcalhaz; German: Sackelhausen) is a commune in Timiș County.</abstract>
//! <links>…</links>
//! </doc>
//! ```
//!
//! Two things about the title matter. It is prefixed `Wikipedia: `, which is not part of the
//! article name and is stripped here. And it is the *display* title, which is what an OSM
//! `wikipedia=en:Săcălaz` tag holds after its language prefix — so the join is a string match and
//! needs no Wikidata round trip.
//!
//! # Why hand-rolled rather than an XML crate
//!
//! The file is ~6 GB and the structure is four fixed tags in a fixed order. A pull parser would be
//! correct and would also pay to validate a document nobody is going to hand-edit. This reads
//! line-oriented, holds one record at a time, and never allocates for the parts it discards
//! (`<url>`, `<links>`, and the per-link `<sublink>` blocks, which are the bulk of the file).

use std::io::BufRead;

/// One article's lead.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Abstract {
    /// The article title, with the dump's `Wikipedia: ` prefix removed.
    pub title: String,
    /// The lead text, XML-unescaped. May be empty: many stubs have no abstract.
    pub text: String,
}

/// Yields one [`Abstract`] per `<doc>`, holding a single record in memory at a time.
pub struct Abstracts<R: BufRead> {
    lines: std::io::Lines<R>,
}

impl<R: BufRead> Abstracts<R> {
    pub fn new(source: R) -> Self {
        Abstracts { lines: source.lines() }
    }
}

impl<R: BufRead> Iterator for Abstracts<R> {
    type Item = Abstract;

    fn next(&mut self) -> Option<Abstract> {
        let mut title: Option<String> = None;
        let mut text: Option<String> = None;
        for line in self.lines.by_ref() {
            // A read error mid-file ends the iteration rather than panicking: a partial archive
            // built from a truncated download is recoverable, a crash three hours in is not.
            let line = line.ok()?;
            let line = line.trim();
            if let Some(raw) = tag_body(line, "title") {
                title = Some(unescape(raw.strip_prefix("Wikipedia: ").unwrap_or(raw)));
            } else if let Some(raw) = tag_body(line, "abstract") {
                text = Some(unescape(raw));
            } else if line.starts_with("</doc>") {
                // `<abstract/>` and a missing abstract both arrive as no text at all.
                return Some(Abstract {
                    title: title.take()?,
                    text: text.take().unwrap_or_default(),
                });
            }
        }
        None
    }
}

/// The contents of `<tag>…</tag>` when `line` is exactly that, else `None`.
///
/// Both tags must be on one line, which is how the dump is written. A `<tag/>` empty element is
/// not matched and so reads as absent, which is the right answer for an article with no abstract.
fn tag_body<'a>(line: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let close = format!("</{tag}>");
    line.strip_prefix(&open)?.strip_suffix(&close)
}

/// The five predefined XML entities.
///
/// Numeric character references are left alone deliberately: they are rare in abstracts, and a
/// half-decoded `&#8212;` is more obviously wrong than a decoded one is right.
fn unescape(raw: &str) -> String {
    if !raw.contains('&') {
        return raw.to_string();
    }
    raw.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Last, or an escaped ampersand would be decoded twice: `&amp;lt;` is the text `&lt;`.
        .replace("&amp;", "&")
}

/// The article title an OSM `wikipedia` tag refers to, if it names an English article.
///
/// The tag is `wikipedia=en:Săcălaz`. A bare value with no language prefix is treated as English,
/// which is what the older tagging convention meant. Any other language is `None`: this archive
/// carries English leads, and a German title would silently never match.
pub fn english_title(tag: &str) -> Option<&str> {
    let tag = tag.trim();
    if tag.is_empty() {
        return None;
    }
    match tag.split_once(':') {
        Some(("en", title)) => Some(title.trim()),
        // A colon inside a title with no language prefix, e.g. "Toronto: A City".
        Some((prefix, _)) if prefix.len() == 2 || prefix.len() == 3 => None,
        _ => Some(tag),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(xml: &str) -> Vec<Abstract> {
        Abstracts::new(std::io::BufReader::new(xml.as_bytes())).collect()
    }

    #[test]
    fn a_doc_yields_its_title_and_lead() {
        let got = parse(
            "<feed>\n<doc>\n<title>Wikipedia: Săcălaz</title>\n\
             <url>https://en.wikipedia.org/wiki/S%C4%83c%C4%83laz</url>\n\
             <abstract>Săcălaz is a commune in Timiș County, Romania.</abstract>\n\
             <links><sublink><anchor>Geography</anchor></sublink></links>\n</doc>\n</feed>",
        );
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].title, "Săcălaz", "the `Wikipedia: ` prefix is not part of the title");
        assert_eq!(got[0].text, "Săcălaz is a commune in Timiș County, Romania.");
    }

    #[test]
    fn documents_are_streamed_one_after_another() {
        let got = parse(
            "<feed>\n\
             <doc>\n<title>Wikipedia: Paris</title>\n<abstract>Paris is in France.</abstract>\n</doc>\n\
             <doc>\n<title>Wikipedia: Berlin</title>\n<abstract>Berlin is in Germany.</abstract>\n</doc>\n\
             </feed>",
        );
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].title, "Paris");
        assert_eq!(got[1].title, "Berlin");
        assert_eq!(got[1].text, "Berlin is in Germany.");
    }

    /// Stubs are common and must not stop the pass or drop the title.
    #[test]
    fn an_article_with_no_abstract_yields_empty_text() {
        let got = parse(
            "<doc>\n<title>Wikipedia: Stub</title>\n<abstract/>\n</doc>\n\
             <doc>\n<title>Wikipedia: Other</title>\n</doc>",
        );
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].text, "");
        assert_eq!(got[1].text, "");
        assert_eq!(got[1].title, "Other");
    }

    #[test]
    fn xml_entities_are_decoded() {
        let got = parse(
            "<doc>\n<title>Wikipedia: A &amp; B</title>\n\
             <abstract>&quot;Quoted&quot; &lt;tagged&gt; &apos;and&apos; &amp; more.</abstract>\n</doc>",
        );
        assert_eq!(got[0].title, "A & B");
        assert_eq!(got[0].text, "\"Quoted\" <tagged> 'and' & more.");
    }

    /// `&amp;lt;` is the literal text `&lt;`, not a nested escape.
    #[test]
    fn an_escaped_ampersand_is_decoded_once() {
        let got = parse("<doc>\n<title>Wikipedia: T</title>\n<abstract>&amp;lt;</abstract>\n</doc>");
        assert_eq!(got[0].text, "&lt;");
    }

    /// A dump cut short leaves a `<doc>` with no `</doc>`, which must end the pass rather than
    /// emit a half record.
    #[test]
    fn a_truncated_final_document_is_dropped() {
        let got = parse(
            "<doc>\n<title>Wikipedia: Complete</title>\n<abstract>All here.</abstract>\n</doc>\n\
             <doc>\n<title>Wikipedia: Cut</title>\n<abstract>Half a",
        );
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].title, "Complete");
    }

    #[test]
    fn the_join_key_comes_from_the_osm_wikipedia_tag() {
        assert_eq!(english_title("en:Săcălaz"), Some("Săcălaz"));
        assert_eq!(english_title("  en:Paris  "), Some("Paris"));
        // The older convention: no prefix meant English.
        assert_eq!(english_title("Paris"), Some("Paris"));
        // Another language cannot match an English dump, so it must not be guessed at.
        assert_eq!(english_title("de:Paris"), None);
        assert_eq!(english_title("ro:Săcălaz"), None);
        assert_eq!(english_title(""), None);
        // A colon that is punctuation rather than a language prefix.
        assert_eq!(english_title("Toronto: A City"), Some("Toronto: A City"));
    }
}
