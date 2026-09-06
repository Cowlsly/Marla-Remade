//! Checks `post::sentencepiece` against real HuggingFace `tokenizers` output for Gemma 4.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_gemma4_tokenizer -- \
//!     gemma4_tokenizer.spm1 tok_golden.json
//! ```
//!
//! # Why a golden and not a unit test
//!
//! The merge loop is already unit-tested on a handful of hand-built pieces, and that catches an
//! algorithm that merges in the wrong order. It cannot catch the things that actually differ
//! between two tokenizers of the *same* algorithm: whether whitespace is collapsed, whether a
//! dummy prefix is added, how an unrepresentable byte is spelled, and whether the merge ranks
//! were inverted correctly on the way into the file. Those only show up against the real thing.
//!
//! The samples deliberately include runs of spaces and a code block, because Gemma's normaliser
//! is a bare `Replace(" ", metaspace)` with no trimming - the opposite of what fairseq's models
//! were trained with, and what `Flavour::tidy_whitespace` exists to distinguish.
use std::path::PathBuf;

use modelrunner::post::sentencepiece::{Table, GEMMA};

/// One `{ "text": ..., "ids": [...] }` from the golden file.
struct Case {
    text: String,
    ids: Vec<u32>,
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(table_path), Some(golden_path)) =
        (args.next().map(PathBuf::from), args.next().map(PathBuf::from))
    else {
        println!("usage: check_gemma4_tokenizer <table.spm1> <golden.json>");
        return;
    };
    let blob = match std::fs::read(&table_path) {
        Ok(bytes) => bytes,
        Err(why) => return println!("cannot read {}: {why}", table_path.display()),
    };
    let table = match Table::parse_with(&blob, GEMMA) {
        Ok(table) => table,
        Err(why) => return println!("the table does not parse: {why}"),
    };
    println!("{} pieces, byte fallback {}", table.len(), table.has_byte_fallback());
    if !table.has_byte_fallback() {
        println!("FAIL: Gemma's vocabulary holds all 256 byte pieces and this one does not");
        return;
    }

    let golden = match std::fs::read_to_string(&golden_path) {
        Ok(text) => text,
        Err(why) => return println!("cannot read {}: {why}", golden_path.display()),
    };
    let cases = parse_golden(&golden);
    println!("{} cases", cases.len());
    println!();

    // The markers a chat template inserts. `<bos>` is the one the golden exercises; the rest are
    // listed because the same call site will need them and an unknown entry is skipped.
    let specials = [
        "<bos>",
        "<eos>",
        "<pad>",
        "<unk>",
        "<|tool>",
        "<tool|>",
        "<|tool_call>",
        "<tool_call|>",
        "<|tool_response>",
        "<tool_response|>",
    ];

    let mut passed = 0;
    for case in &cases {
        let got = table.encode_with_specials(&case.text, &specials);
        if got == case.ids {
            passed += 1;
            continue;
        }
        println!("MISMATCH for {:?}", case.text);
        println!("  want {:?}", case.ids);
        println!("  got  {got:?}");
        let first = got.iter().zip(&case.ids).position(|(a, b)| a != b).unwrap_or(0);
        println!("  first differs at {first}");
    }
    println!();
    println!("{passed} of {} match HuggingFace", cases.len());

    // Round-tripping matters as much as encoding: byte fallback is only useful if `decode` fuses
    // the byte pieces back into characters rather than emitting one replacement each.
    let mut round_tripped = 0;
    for case in &cases {
        let text = table.decode(&case.ids);
        let want = case.text.trim();
        if text == want {
            round_tripped += 1;
        } else {
            println!("ROUND TRIP {:?} -> {:?}", case.text, text);
        }
    }
    println!("{round_tripped} of {} round-trip through decode", cases.len());
}

/// The golden file, parsed without pulling in a JSON crate for one shape.
fn parse_golden(text: &str) -> Vec<Case> {
    let mut cases = Vec::new();
    let mut rest = text;
    while let Some(at) = rest.find("\"text\":") {
        rest = &rest[at + 7..];
        let Some(text_value) = read_string(&mut rest) else { break };
        let Some(at) = rest.find("\"ids\":") else { break };
        rest = &rest[at + 6..];
        let Some(open) = rest.find('[') else { break };
        let Some(close) = rest[open..].find(']') else { break };
        let ids = rest[open + 1..open + close]
            .split(',')
            .filter_map(|piece| piece.trim().parse::<u32>().ok())
            .collect();
        rest = &rest[open + close..];
        cases.push(Case { text: text_value, ids });
    }
    cases
}

/// The next JSON string in `rest`, advancing past it. Handles the escapes the writer emits.
fn read_string(rest: &mut &str) -> Option<String> {
    let open = rest.find('"')?;
    let mut out = String::new();
    let mut chars = rest[open + 1..].char_indices();
    while let Some((at, c)) = chars.next() {
        match c {
            '"' => {
                *rest = &rest[open + 1 + at + 1..];
                return Some(out);
            }
            '\\' => {
                let (_, escaped) = chars.next()?;
                out.push(match escaped {
                    'n' => '\n',
                    't' => '\t',
                    'r' => '\r',
                    'u' => {
                        let hex: String = (0..4).filter_map(|_| chars.next().map(|(_, c)| c)).collect();
                        char::from_u32(u32::from_str_radix(&hex, 16).ok()?)?
                    }
                    other => other,
                });
            }
            other => out.push(other),
        }
    }
    None
}
