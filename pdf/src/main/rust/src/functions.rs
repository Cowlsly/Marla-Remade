//! PDF Function evaluator (PDF 1.7 §7.10).
//!
//! Supports all four function types:
//! - Type 0: sampled functions (multilinear interpolation over an N-D sample grid)
//! - Type 2: exponential interpolation (`C0 + t^N·(C1-C0)`)
//! - Type 3: stitching (piecewise sub-functions selected by `/Bounds`)
//! - Type 4: PostScript calculator (a bounded stack-machine over the operator subset)
//!
//! Plus [`PdfFunction::Array`], an array of single-output functions that together
//! produce one output tuple (used where a shading `/Function` is an array).
//!
//! This is the shared color-transform foundation for `color.rs`
//! (Separation/DeviceN tint transforms) and `shading.rs`/`images.rs`
//! (axial/radial gradient color functions).

use crate::*;

/// Guards for the Type 4 PostScript calculator.
const MAX_PS_TOKENS: usize = 100_000;
const MAX_PS_STACK: usize = 1000;
const MAX_PS_STEPS: usize = 5_000_000;
const MAX_PS_DEPTH: u32 = 64;
/// Cap on total sampled-function bytes we will hold / interpolate.
const MAX_SAMPLED_BYTES: usize = 64 * 1024 * 1024;
/// Cap on a sampled function's input arity. `eval_sampled` interpolates over
/// `2^m` grid corners per evaluation and runs once per pixel for a shading, so an
/// unbounded `m` taken from `/Size` is a hang. Real-world m is 1 or 2 (7.10.2).
const MAX_SAMPLED_INPUTS: usize = 8;
/// Cap on a function's output arity, so a bogus `/Range` cannot make every
/// evaluation allocate an absurd vector.
const MAX_FN_OUTPUTS: usize = 32;
/// Nesting and total-node budgets for function PARSING.
///
/// Type 3 (`/Functions`) and the array-of-functions form both recurse through
/// `PdfFunction::parse`, and nothing in 7.10 stops `5 0 R` naming a Type 3 whose
/// `/Functions` array is `[5 0 R]`. That recursed forever: a Rust stack overflow is
/// not a panic, so `catch_unwind` cannot contain it and the whole process dies.
///
/// A depth cap alone is not sufficient, because the recursion BRANCHES — a Type 3
/// holding 100 sub-functions, each holding 100, is 100^depth nodes long before it
/// ever reaches the depth limit. The shared node budget is what actually bounds the
/// work; the depth cap bounds the stack. Real functions nest one or two levels
/// (7.10.4 NOTE: a Type 3's sub-functions "shall not" themselves be Type 3).
const MAX_FN_DEPTH: u32 = 8;
const MAX_FN_NODES: usize = 4096;

#[derive(Clone)]
pub(crate) enum PdfFunction {
    Sampled {
        domain: Vec<[f64; 2]>,
        range: Vec<[f64; 2]>,
        size: Vec<usize>,
        bps: u32,
        encode: Vec<[f64; 2]>,
        decode: Vec<[f64; 2]>,
        samples: Vec<u8>,
        n_in: usize,
        n_out: usize,
    },
    Exponential {
        domain: [f64; 2],
        /// 7.10.1 Table 38 makes `/Range` optional for types 2 and 3, but when it IS
        /// present outputs shall be clipped to it — a Type 2 whose `/Domain` extends
        /// past 1 evaluates `C0 + t^N*(C1-C0)` outside the `C0..C1` interval, so the
        /// entry is not redundant.
        range: Vec<[f64; 2]>,
        c0: Vec<f64>,
        c1: Vec<f64>,
        n: f64,
    },
    Stitching {
        domain: [f64; 2],
        range: Vec<[f64; 2]>,
        functions: Vec<PdfFunction>,
        bounds: Vec<f64>,
        encode: Vec<[f64; 2]>,
    },
    PostScript {
        domain: Vec<[f64; 2]>,
        range: Vec<[f64; 2]>,
        program: Vec<PsToken>,
    },
    /// An array of functions, each contributing (usually one) output component.
    Array(Vec<PdfFunction>),
}

#[derive(Clone)]
pub(crate) enum PsToken {
    Num(f64),
    Op(PsOp),
    Proc(Vec<PsToken>),
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum PsOp {
    Abs, Add, Atan, Ceiling, Cos, Cvi, Cvr, Div, Exp, Floor, Idiv, Ln, Log,
    Mod, Mul, Neg, Round, Sin, Sqrt, Sub, Truncate,
    And, Bitshift, Eq, False, Ge, Gt, Le, Lt, Ne, Not, Or, True, Xor,
    If, Ifelse,
    Copy, Dup, Exch, Index, Pop, Roll,
}

fn read_pairs(obj: Option<&Object>) -> Vec<[f64; 2]> {
    let arr = match obj {
        Some(Object::Array(a)) => a,
        _ => return Vec::new(),
    };
    arr.chunks(2)
        .filter_map(|c| {
            if c.len() == 2 {
                Some([num(&c[0])?, num(&c[1])?])
            } else {
                None
            }
        })
        .collect()
}

fn read_floats(obj: Option<&Object>) -> Vec<f64> {
    match obj {
        Some(Object::Array(a)) => a.iter().filter_map(num).collect(),
        _ => Vec::new(),
    }
}

/// Clip `v` to the interval described by the pair `[a, b]` taken from a `/Domain`
/// or `/Range` entry (7.10.1: inputs are clipped to Domain, outputs to Range).
///
/// This exists instead of `f64::clamp` for two reasons, both reachable from a file:
///
/// * `f64::clamp` PANICS when its low bound exceeds its high bound, and nothing in
///   Table 38 stops a generator writing `[1 0]`. A reversed pair therefore aborted
///   the render of the whole page rather than clipping one component.
/// * `f64::clamp` PROPAGATES NaN. A NaN component survives every downstream
///   conversion and lands as an arbitrary (usually black) colour, so it has to be
///   removed at the boundary rather than clamped — the same reasoning the
///   `Exponential` arm already applies to `t^N`.
///
/// A non-finite bound is ignored rather than honoured, so `f64::max`/`min`'s
/// NaN-skipping behaviour is what makes the degenerate cases fall out.
fn clip(v: f64, a: f64, b: f64) -> f64 {
    if !v.is_finite() {
        let lo = a.min(b);
        return if lo.is_finite() { lo } else { 0.0 };
    }
    v.max(a.min(b)).min(a.max(b))
}

impl PdfFunction {
    /// Parse a function object (reference, dict, stream, or array-of-functions).
    pub(crate) fn parse(doc: &Document, obj: &Object) -> Option<PdfFunction> {
        let mut budget = MAX_FN_NODES;
        PdfFunction::parse_at(doc, obj, 0, &mut budget)
    }

    fn parse_at(
        doc: &Document,
        obj: &Object,
        depth: u32,
        budget: &mut usize,
    ) -> Option<PdfFunction> {
        if depth > MAX_FN_DEPTH || *budget == 0 {
            return None;
        }
        *budget -= 1;
        let resolved = deref(doc, obj)?;
        if let Object::Array(arr) = resolved {
            // Array of functions -> one output component each.
            let mut fns = Vec::new();
            for o in arr {
                if let Some(f) = PdfFunction::parse_at(doc, o, depth + 1, budget) {
                    fns.push(f);
                }
            }
            if fns.is_empty() {
                return None;
            }
            return Some(PdfFunction::Array(fns));
        }
        let (dict, stream_bytes): (&Dictionary, Option<Vec<u8>>) = match resolved {
            Object::Dictionary(d) => (d, None),
            Object::Stream(s) => (&s.dict, Some(stream_data_with_doc(doc, s))),
            _ => return None,
        };
        let ftype = dict.get(b"FunctionType").ok().and_then(num)? as i64;
        let domain_pairs = read_pairs(dict.get(b"Domain").ok());
        match ftype {
            0 => {
                let bytes = stream_bytes?;
                if bytes.len() > MAX_SAMPLED_BYTES {
                    return None;
                }
                let size: Vec<usize> = read_floats(dict.get(b"Size").ok())
                    .iter()
                    .map(|v| (*v as i64).max(0) as usize)
                    .collect();
                let bps = dict.get(b"BitsPerSample").ok().and_then(num)? as u32;
                let range = read_pairs(dict.get(b"Range").ok());
                if size.is_empty() || range.is_empty() || domain_pairs.is_empty() {
                    return None;
                }
                // 7.10.2 Table 39: BitsPerSample shall be one of these. An
                // out-of-set value (notably 0) makes the sample normaliser divide
                // by zero, and the resulting NaN survives the Range clamp and
                // poisons every output component.
                if !matches!(bps, 1 | 2 | 4 | 8 | 12 | 16 | 24 | 32) {
                    return None;
                }
                // Guard the 2^m corner interpolation and the flattened index maths.
                if size.len() > MAX_SAMPLED_INPUTS || range.len() > MAX_FN_OUTPUTS {
                    return None;
                }
                if size.iter().any(|s| *s == 0) {
                    return None;
                }
                let n_in = size.len();
                let n_out = range.len();
                let encode = {
                    let e = read_pairs(dict.get(b"Encode").ok());
                    if e.len() == n_in {
                        e
                    } else {
                        size.iter().map(|s| [0.0, (*s as f64 - 1.0).max(0.0)]).collect()
                    }
                };
                let decode = {
                    let d = read_pairs(dict.get(b"Decode").ok());
                    if d.len() == n_out { d } else { range.clone() }
                };
                Some(PdfFunction::Sampled {
                    domain: domain_pairs,
                    range,
                    size,
                    bps,
                    encode,
                    decode,
                    samples: bytes,
                    n_in,
                    n_out,
                })
            }
            2 => {
                let mut c0 = read_floats(dict.get(b"C0").ok());
                let mut c1 = read_floats(dict.get(b"C1").ok());
                if c0.is_empty() { c0 = vec![0.0]; }
                if c1.is_empty() { c1 = vec![1.0]; }
                // j is over-determined: 7.10.3 Table 40 makes C0/C1 arrays of j
                // numbers, and Table 38 requires /Range (when present) to hold 2*j
                // entries. Materialise all of them at the widest arity so the scalar
                // C0/C1 defaults broadcast, instead of pinning j to 1 and handing
                // the target colour space too few components. The padding values are
                // the same per-index defaults `eval` already applied, so this is a
                // no-op for every well-formed function.
                let range = read_pairs(dict.get(b"Range").ok());
                let j = c0.len().max(c1.len()).max(range.len()).min(MAX_FN_OUTPUTS);
                c0.resize(j, 0.0);
                c1.resize(j, 1.0);
                let n = dict.get(b"N").ok().and_then(num).unwrap_or(1.0);
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Exponential { domain, range, c0, c1, n })
            }
            3 => {
                let funcs_obj = deref(doc, dict.get(b"Functions").ok()?)?;
                let funcs_arr = funcs_obj.as_array().ok()?;
                let mut functions = Vec::new();
                for o in funcs_arr {
                    functions.push(PdfFunction::parse_at(doc, o, depth + 1, budget)?);
                }
                // An empty /Functions array would make `eval` return an empty
                // vector, which downstream colour code cannot distinguish from a
                // one-component result. Reject it here so a parse failure is
                // reported as None instead.
                if functions.is_empty() {
                    return None;
                }
                let bounds = read_floats(dict.get(b"Bounds").ok());
                let encode = read_pairs(dict.get(b"Encode").ok());
                let range = read_pairs(dict.get(b"Range").ok());
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Stitching { domain, range, functions, bounds, encode })
            }
            4 => {
                let bytes = stream_bytes?;
                let range = read_pairs(dict.get(b"Range").ok());
                let program = parse_ps_program(&bytes)?;
                Some(PdfFunction::PostScript { domain: domain_pairs, range, program })
            }
            _ => None,
        }
    }

    /// Evaluate the function, returning the output tuple.
    pub(crate) fn eval(&self, inputs: &[f64]) -> Vec<f64> {
        match self {
            PdfFunction::Exponential { domain, range, c0, c1, n } => {
                let t = clip(inputs.first().copied().unwrap_or(0.0), domain[0], domain[1]);
                // 7.10.3 constrains Domain so that t^N is defined, but a malformed
                // file can still reach negative t with a non-integer N, which gives
                // NaN and would poison every output component.
                let tn = if *n == 1.0 {
                    t
                } else {
                    let p = t.powf(*n);
                    if p.is_finite() { p } else { 0.0 }
                };
                let len = c0.len().max(c1.len());
                (0..len)
                    .map(|i| {
                        let a = c0.get(i).copied().unwrap_or(0.0);
                        let b = c1.get(i).copied().unwrap_or(1.0);
                        let v = a + (b - a) * tn;
                        match range.get(i) {
                            Some(r) => clip(v, r[0], r[1]),
                            None => v,
                        }
                    })
                    .collect()
            }
            PdfFunction::Stitching { domain, range, functions, bounds, encode } => {
                if functions.is_empty() {
                    return Vec::new();
                }
                let x = clip(inputs.first().copied().unwrap_or(0.0), domain[0], domain[1]);
                // Select sub-function k.
                let mut k = 0usize;
                while k < bounds.len() && x >= bounds[k] {
                    k += 1;
                }
                k = k.min(functions.len() - 1);
                // Sub-domain for k.
                let lo = if k == 0 { domain[0] } else { bounds[k - 1] };
                let hi = if k < bounds.len() { bounds[k] } else { domain[1] };
                let (e0, e1) = encode.get(k).map(|e| (e[0], e[1])).unwrap_or((0.0, 1.0));
                let xe = if (hi - lo).abs() < 1e-12 {
                    e0
                } else {
                    e0 + (x - lo) * (e1 - e0) / (hi - lo)
                };
                let mut out = functions[k].eval(&[xe]);
                for (i, v) in out.iter_mut().enumerate() {
                    if let Some(r) = range.get(i) {
                        *v = clip(*v, r[0], r[1]);
                    }
                }
                out
            }
            PdfFunction::Sampled { .. } => self.eval_sampled(inputs),
            PdfFunction::PostScript { domain, range, program } => {
                let clamped: Vec<f64> = inputs
                    .iter()
                    .enumerate()
                    .map(|(i, v)| {
                        if let Some(d) = domain.get(i) {
                            clip(*v, d[0], d[1])
                        } else {
                            *v
                        }
                    })
                    .collect();
                let mut out = eval_ps(program, &clamped).unwrap_or_default();
                if !range.is_empty() {
                    // Keep only the last n_out values and clamp to range.
                    let n_out = range.len();
                    if out.len() > n_out {
                        out = out.split_off(out.len() - n_out);
                    }
                    for (i, v) in out.iter_mut().enumerate() {
                        if let Some(r) = range.get(i) {
                            *v = clip(*v, r[0], r[1]);
                        }
                    }
                }
                out
            }
            PdfFunction::Array(fns) => {
                let mut out = Vec::new();
                for f in fns {
                    out.extend(f.eval(inputs));
                }
                out
            }
        }
    }

    /// Sample a one-in/one-out function into a 256-entry lookup table over the input
    /// range [0,1], with both index and value in 0..=255.
    ///
    /// This is the form a transfer function has to take to be usable per-pixel: it is
    /// evaluated once here instead of once per mask sample, and it can be carried over
    /// the wire and applied by a GPU shader or a bitmap remap, neither of which can run
    /// a PostScript calculator. Only the FIRST output component is used, which is what
    /// §11.6.5.2's `/TR` and §11.7.4's transfer functions specify.
    pub(crate) fn to_lut256(&self) -> [u8; 256] {
        let mut lut = [0u8; 256];
        for (i, slot) in lut.iter_mut().enumerate() {
            let v = self.eval(&[i as f64 / 255.0]).first().copied().unwrap_or(0.0);
            // A NaN from a malformed function must not become an arbitrary byte;
            // `clamp` propagates NaN, so test for it explicitly.
            *slot = if v.is_finite() {
                (v.clamp(0.0, 1.0) * 255.0).round() as u8
            } else {
                i as u8
            };
        }
        lut
    }

    fn eval_sampled(&self, inputs: &[f64]) -> Vec<f64> {
        let (domain, range, size, bps, encode, decode, samples, n_in, n_out) = match self {
            PdfFunction::Sampled {
                domain, range, size, bps, encode, decode, samples, n_in, n_out,
            } => (domain, range, size, *bps, encode, decode, samples, *n_in, *n_out),
            _ => return Vec::new(),
        };
        if n_in == 0 || n_out == 0 {
            return Vec::new();
        }
        // Encode each input to a continuous grid coordinate e in [0, size-1].
        let mut e = Vec::with_capacity(n_in);
        for (i, sz) in size.iter().enumerate().take(n_in) {
            let d = domain.get(i).copied().unwrap_or([0.0, 1.0]);
            let enc = encode.get(i).copied().unwrap_or([0.0, (*sz as f64 - 1.0).max(0.0)]);
            let x = clip(inputs.get(i).copied().unwrap_or(0.0), d[0], d[1]);
            let ev = if (d[1] - d[0]).abs() < 1e-12 {
                enc[0]
            } else {
                enc[0] + (x - d[0]) * (enc[1] - enc[0]) / (d[1] - d[0])
            };
            e.push(clip(ev, 0.0, (*sz as f64 - 1.0).max(0.0)));
        }
        // Multilinear interpolation over the 2^n_in surrounding grid corners.
        let max_val = if bps >= 32 { u32::MAX as f64 } else { ((1u64 << bps) - 1) as f64 };
        let corners = 1usize << n_in;
        let mut out = vec![0.0f64; n_out];
        for corner in 0..corners {
            let mut weight = 1.0;
            let mut grid = Vec::with_capacity(n_in);
            for i in 0..n_in {
                let floor = e[i].floor();
                let frac = e[i] - floor;
                let hi_bit = (corner >> i) & 1 == 1;
                let (idx, w) = if hi_bit {
                    ((floor as usize + 1).min(size[i].saturating_sub(1)), frac)
                } else {
                    (floor as usize, 1.0 - frac)
                };
                weight *= w;
                grid.push(idx);
            }
            if weight == 0.0 {
                continue;
            }
            // Flatten grid index (first dimension varies fastest per spec).
            let mut flat = 0usize;
            let mut stride = 1usize;
            let mut ok = true;
            for i in 0..n_in {
                match grid[i].checked_mul(stride).and_then(|v| flat.checked_add(v)) {
                    Some(f) => flat = f,
                    None => { ok = false; break; }
                }
                // Only advance the stride when another dimension follows, so a
                // harmless overflow on the final axis cannot discard a valid corner.
                if i + 1 < n_in {
                    match stride.checked_mul(size[i].max(1)) {
                        Some(s) => stride = s,
                        None => { ok = false; break; }
                    }
                }
            }
            if !ok {
                continue;
            }
            for (j, o) in out.iter_mut().enumerate() {
                let sample_idx = flat * n_out + j;
                let raw = read_sample(samples, sample_idx, bps);
                *o += weight * (raw / max_val);
            }
        }
        // Decode from [0,1] to Decode range, then clamp to Range.
        for (j, o) in out.iter_mut().enumerate() {
            let dec = decode.get(j).copied().unwrap_or([0.0, 1.0]);
            *o = dec[0] + *o * (dec[1] - dec[0]);
            if let Some(r) = range.get(j) {
                *o = clip(*o, r[0], r[1]);
            }
        }
        out
    }
}

/// Read the `idx`-th packed sample of `bps` bits (big-endian bit order). Returns
/// 0 when the sample lies wholly or partly past the end of `data`: a truncated
/// sample stream must not yield a partially-shifted value, which looks plausible
/// but is wrong.
fn read_sample(data: &[u8], idx: usize, bps: u32) -> f64 {
    let bit_pos = idx as u64 * bps as u64;
    let end_bit = bit_pos + bps as u64;
    if end_bit > (data.len() as u64).saturating_mul(8) {
        return 0.0;
    }
    let mut value: u64 = 0;
    for b in 0..bps as u64 {
        let bit = bit_pos + b;
        let byte = (bit / 8) as usize;
        if byte >= data.len() {
            break;
        }
        let bit_in_byte = 7 - (bit % 8) as u32;
        let bit_val = (data[byte] >> bit_in_byte) & 1;
        value = (value << 1) | bit_val as u64;
    }
    value as f64
}

// ---------------------------------------------------------------------------
// Type 4 PostScript calculator
// ---------------------------------------------------------------------------

fn parse_ps_program(bytes: &[u8]) -> Option<Vec<PsToken>> {
    let mut toks = Vec::new();
    let mut pos = 0usize;
    let mut count = 0usize;
    // Skip to first '{'.
    while pos < bytes.len() && bytes[pos] != b'{' {
        pos += 1;
    }
    if pos >= bytes.len() {
        return None;
    }
    pos += 1; // consume the outer '{'
    parse_ps_block(bytes, &mut pos, &mut toks, &mut count)?;
    Some(toks)
}

fn parse_ps_block(
    bytes: &[u8],
    pos: &mut usize,
    out: &mut Vec<PsToken>,
    count: &mut usize,
) -> Option<()> {
    while *pos < bytes.len() {
        if *count > MAX_PS_TOKENS {
            return None;
        }
        let c = bytes[*pos];
        if c.is_ascii_whitespace() {
            *pos += 1;
            continue;
        }
        if c == b'}' {
            *pos += 1;
            return Some(());
        }
        if c == b'{' {
            *pos += 1;
            let mut inner = Vec::new();
            parse_ps_block(bytes, pos, &mut inner, count)?;
            out.push(PsToken::Proc(inner));
            *count += 1;
            continue;
        }
        if c == b'%' {
            // comment to end of line
            while *pos < bytes.len() && bytes[*pos] != b'\n' && bytes[*pos] != b'\r' {
                *pos += 1;
            }
            continue;
        }
        // Read a token (number or operator name).
        let start = *pos;
        while *pos < bytes.len() {
            let ch = bytes[*pos];
            if ch.is_ascii_whitespace() || ch == b'{' || ch == b'}' || ch == b'%' {
                break;
            }
            *pos += 1;
        }
        let word = &bytes[start..*pos];
        if word.is_empty() {
            *pos += 1;
            continue;
        }
        let s = std::str::from_utf8(word).ok()?;
        if let Ok(n) = s.parse::<f64>() {
            out.push(PsToken::Num(n));
        } else if let Some(op) = parse_ps_op(s) {
            out.push(PsToken::Op(op));
        } else {
            // Unknown operator: ignore (best-effort).
        }
        *count += 1;
    }
    Some(())
}

fn parse_ps_op(s: &str) -> Option<PsOp> {
    use PsOp::*;
    Some(match s {
        "abs" => Abs, "add" => Add, "atan" => Atan, "ceiling" => Ceiling,
        "cos" => Cos, "cvi" => Cvi, "cvr" => Cvr, "div" => Div, "exp" => Exp,
        "floor" => Floor, "idiv" => Idiv, "ln" => Ln, "log" => Log, "mod" => Mod,
        "mul" => Mul, "neg" => Neg, "round" => Round, "sin" => Sin, "sqrt" => Sqrt,
        "sub" => Sub, "truncate" => Truncate,
        "and" => And, "bitshift" => Bitshift, "eq" => Eq, "false" => False,
        "ge" => Ge, "gt" => Gt, "le" => Le, "lt" => Lt, "ne" => Ne, "not" => Not,
        "or" => Or, "true" => True, "xor" => Xor,
        "if" => If, "ifelse" => Ifelse,
        "copy" => Copy, "dup" => Dup, "exch" => Exch, "index" => Index,
        "pop" => Pop, "roll" => Roll,
        _ => return None,
    })
}

#[derive(Clone)]
enum PsVal {
    Num(f64),
    Proc(Vec<PsToken>),
}

fn eval_ps(program: &[PsToken], inputs: &[f64]) -> Option<Vec<f64>> {
    let mut stack: Vec<PsVal> = inputs.iter().map(|v| PsVal::Num(*v)).collect();
    let mut steps = 0usize;
    exec_ps(program, &mut stack, &mut steps, 0)?;
    Some(stack.into_iter().filter_map(|v| match v {
        PsVal::Num(n) => Some(n),
        PsVal::Proc(_) => None,
    }).collect())
}

fn exec_ps(tokens: &[PsToken], stack: &mut Vec<PsVal>, steps: &mut usize, depth: u32) -> Option<()> {
    if depth > MAX_PS_DEPTH {
        return None;
    }
    for tok in tokens {
        *steps += 1;
        if *steps > MAX_PS_STEPS || stack.len() > MAX_PS_STACK {
            return None;
        }
        match tok {
            PsToken::Num(n) => stack.push(PsVal::Num(*n)),
            PsToken::Proc(p) => stack.push(PsVal::Proc(p.clone())),
            PsToken::Op(op) => exec_ps_op(*op, stack, steps, depth)?,
        }
    }
    Some(())
}

fn pop_num(stack: &mut Vec<PsVal>) -> Option<f64> {
    match stack.pop()? {
        PsVal::Num(n) => Some(n),
        PsVal::Proc(_) => None,
    }
}

fn pop_proc(stack: &mut Vec<PsVal>) -> Option<Vec<PsToken>> {
    match stack.pop()? {
        PsVal::Proc(p) => Some(p),
        PsVal::Num(_) => None,
    }
}

fn exec_ps_op(op: PsOp, stack: &mut Vec<PsVal>, steps: &mut usize, depth: u32) -> Option<()> {
    use PsOp::*;
    let bool_of = |b: bool| PsVal::Num(if b { 1.0 } else { 0.0 });
    match op {
        Abs => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.abs())); }
        Neg => { let a = pop_num(stack)?; stack.push(PsVal::Num(-a)); }
        Sqrt => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.max(0.0).sqrt())); }
        Sin => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.to_radians().sin())); }
        Cos => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.to_radians().cos())); }
        Ln => { let a = pop_num(stack)?; stack.push(PsVal::Num(if a > 0.0 { a.ln() } else { 0.0 })); }
        Log => { let a = pop_num(stack)?; stack.push(PsVal::Num(if a > 0.0 { a.log10() } else { 0.0 })); }
        Floor => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.floor())); }
        Ceiling => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.ceil())); }
        Round => {
            // PLRM `round` returns the nearest integer and, for a value exactly
            // halfway, the GREATER of the two. `f64::round` breaks that tie away
            // from zero instead, so -1.5 came out -2 where PostScript gives -1.
            let a = pop_num(stack)?;
            stack.push(PsVal::Num((a + 0.5).floor()));
        }
        Truncate => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.trunc())); }
        Cvi => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.trunc())); }
        Cvr => { /* no-op: already real */ }
        Not => {
            let a = pop_num(stack)?;
            // If used as boolean it's 0/1; as bitwise on ints, invert.
            if a == 0.0 || a == 1.0 {
                stack.push(bool_of(a == 0.0));
            } else {
                stack.push(PsVal::Num(!(a as i64) as f64));
            }
        }
        Add => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a + b)); }
        Sub => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a - b)); }
        Mul => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a * b)); }
        Div => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(if b != 0.0 { a / b } else { 0.0 }));
        }
        Idiv => {
            // `f64 as i64` SATURATES, so any large real reaches i64::MIN, and
            // `i64::MIN / -1` overflows — which Rust panics on in every profile,
            // not just debug. The panic unwinds out of the whole page render.
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { a.wrapping_div(b) as f64 } else { 0.0 }));
        }
        Mod => {
            // `i64::MIN % -1` overflows for the same reason as `idiv` above.
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { a.wrapping_rem(b) as f64 } else { 0.0 }));
        }
        Exp => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            // A negative base with a fractional exponent is NaN, which then survives
            // the /Range clip and every colour conversion below it. Same guard the
            // Type 2 `t^N` path already applies, for the same reason.
            let p = a.powf(b);
            stack.push(PsVal::Num(if p.is_finite() { p } else { 0.0 }));
        }
        Atan => {
            let den = pop_num(stack)?; let num_ = pop_num(stack)?;
            let mut deg = num_.atan2(den).to_degrees();
            if deg < 0.0 { deg += 360.0; }
            stack.push(PsVal::Num(deg));
        }
        And => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) & (b as i64)) as f64));
        }
        Or => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) | (b as i64)) as f64));
        }
        Xor => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) ^ (b as i64)) as f64));
        }
        Bitshift => {
            let shift = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            // `-i64::MIN` overflows; a saturating negation cannot, and everything
            // past 63 clamps to a full-width shift anyway.
            let r = if shift >= 0 { a << (shift.min(63)) } else { a >> (shift.saturating_neg().min(63)) };
            stack.push(PsVal::Num(r as f64));
        }
        Eq => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a == b)); }
        Ne => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a != b)); }
        Gt => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a > b)); }
        Ge => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a >= b)); }
        Lt => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a < b)); }
        Le => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a <= b)); }
        True => stack.push(PsVal::Num(1.0)),
        False => stack.push(PsVal::Num(0.0)),
        Pop => { stack.pop()?; }
        Dup => { let v = stack.last()?.clone(); stack.push(v); }
        Exch => {
            let b = stack.pop()?; let a = stack.pop()?;
            stack.push(b); stack.push(a);
        }
        Copy => {
            let n = pop_num(stack)? as i64;
            if n > 0 {
                let n = n as usize;
                if n > stack.len() { return None; }
                let start = stack.len() - n;
                let slice: Vec<PsVal> = stack[start..].to_vec();
                for v in slice { stack.push(v); }
            }
        }
        Index => {
            let n = pop_num(stack)? as i64;
            if n < 0 { return None; }
            let n = n as usize;
            if n >= stack.len() { return None; }
            let v = stack[stack.len() - 1 - n].clone();
            stack.push(v);
        }
        Roll => {
            let j = pop_num(stack)? as i64;
            let n = pop_num(stack)? as i64;
            if n <= 0 { return Some(()); }
            let n = n as usize;
            if n > stack.len() { return None; }
            let start = stack.len() - n;
            let slice = &mut stack[start..];
            let jm = ((j % n as i64) + n as i64) % n as i64;
            slice.rotate_right(jm as usize);
        }
        If => {
            let proc = pop_proc(stack)?;
            let cond = pop_num(stack)?;
            if cond != 0.0 {
                exec_ps(&proc, stack, steps, depth + 1)?;
            }
        }
        Ifelse => {
            let proc2 = pop_proc(stack)?;
            let proc1 = pop_proc(stack)?;
            let cond = pop_num(stack)?;
            if cond != 0.0 {
                exec_ps(&proc1, stack, steps, depth + 1)?;
            } else {
                exec_ps(&proc2, stack, steps, depth + 1)?;
            }
        }
    }
    Some(())
}

/// Read a transfer function entry (`/TR` in an ExtGState soft-mask dictionary,
/// §11.6.5.2) as a 256-entry lookup table, or `None` when it has no effect.
///
/// §11.6.5.2 requires the mask value to pass THROUGH `/TR` before it is used as the
/// alpha. Ignoring it is not merely imprecise: an inverting `/TR` (`{ 1 exch sub }`, or
/// a Type 2 with `/C0 [1] /C1 [0]`) is the standard idiom for "mask out where the group
/// is bright", so with one present we hide exactly the wrong half of the content.
///
/// `None` is returned for `/Identity`, for an unparseable function, and for any function
/// whose sampled table is within one 8-bit step of the identity everywhere — the
/// overwhelmingly common case, which callers should not pay to carry or apply.
pub(crate) fn read_transfer_lut(doc: &Document, obj: &Object) -> Option<[u8; 256]> {
    if let Some(Object::Name(n)) = deref(doc, obj) {
        if n.as_slice() == b"Identity" || n.as_slice() == b"Default" {
            return None;
        }
    }
    let lut = PdfFunction::parse(doc, obj)?.to_lut256();
    if lut.iter().enumerate().all(|(i, v)| v.abs_diff(i as u8) <= 1) {
        return None;
    }
    Some(lut)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exponential_eval() {
        let f = PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![0.0, 0.0, 0.0],
            c1: vec![1.0, 0.5, 0.0],
            n: 1.0,
        };
        let out = f.eval(&[0.5]);
        assert_eq!(out.len(), 3);
        assert!((out[0] - 0.5).abs() < 1e-9);
        assert!((out[1] - 0.25).abs() < 1e-9);
        assert!((out[2] - 0.0).abs() < 1e-9);
    }

    // 7.10.4: subdomain i is [Bounds_i-1, Bounds_i) (with Domain_0 / Domain_1 at the
    // ends), and x is then mapped LINEARLY from that subdomain onto Encode_i. Asserting
    // only "somewhere strictly between 0 and 1" let an off-by-one in either the
    // selection or the sub-interval through; those produce a hard colour discontinuity
    // at a stop, so pin the exact values.
    #[test]
    fn stitching_selects_subfunction() {
        let ramp = |lo: f64, hi: f64| PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![lo],
            c1: vec![hi],
            n: 1.0,
        };
        let f = PdfFunction::Stitching {
            domain: [0.0, 1.0],
            range: Vec::new(),
            functions: vec![ramp(0.0, 1.0), ramp(1.0, 0.0)],
            bounds: vec![0.5],
            encode: vec![[0.0, 1.0], [0.0, 1.0]],
        };
        // x=0.25 sits at the midpoint of subdomain 0 = [0, 0.5) -> encoded 0.5 -> 0.5.
        assert!((f.eval(&[0.25])[0] - 0.5).abs() < 1e-9);
        // x=0.75 sits at the midpoint of subdomain 1 = [0.5, 1] -> encoded 0.5 -> 0.5.
        assert!((f.eval(&[0.75])[0] - 0.5).abs() < 1e-9);
        // The bound itself belongs to the UPPER subdomain (half-open below), so x=0.5
        // is the START of function 1, which ramps 1 -> 0.
        assert!((f.eval(&[0.5])[0] - 1.0).abs() < 1e-9);
        // Just below it is the END of function 0, which ramps 0 -> 1. The two agree,
        // i.e. the stitch is continuous rather than stepping.
        assert!((f.eval(&[0.5 - 1e-9])[0] - 1.0).abs() < 1e-6);
        assert!((f.eval(&[0.0])[0] - 0.0).abs() < 1e-9);
        assert!((f.eval(&[1.0])[0] - 0.0).abs() < 1e-9);

        // Three subdomains: the MIDDLE one must use [Bounds_0, Bounds_1), not Domain.
        let g = PdfFunction::Stitching {
            domain: [0.0, 1.0],
            range: Vec::new(),
            functions: vec![ramp(0.0, 0.0), ramp(0.0, 1.0), ramp(0.0, 0.0)],
            bounds: vec![0.25, 0.75],
            encode: vec![[0.0, 1.0], [0.0, 1.0], [0.0, 1.0]],
        };
        assert!((g.eval(&[0.5])[0] - 0.5).abs() < 1e-9, "midpoint of [0.25, 0.75)");
        assert!((g.eval(&[0.25])[0] - 0.0).abs() < 1e-9, "lower edge of the middle");
        assert!((g.eval(&[0.75 - 1e-9])[0] - 1.0).abs() < 1e-6, "upper edge");
    }

    // 7.10.1 Table 38: when /Range is present its outputs SHALL be clipped to it. A
    // Type 2 whose /Domain runs past 1 evaluates outside the C0..C1 interval, so this
    // is not vacuous.
    #[test]
    fn exponential_clips_to_range() {
        let mut doc = Document::with_version("1.7");
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 2.into()],
            "Range" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(id)).expect("parses");
        assert!((f.eval(&[0.5])[0] - 0.5).abs() < 1e-9, "in range, untouched");
        assert!((f.eval(&[2.0])[0] - 1.0).abs() < 1e-9, "t=2 gives 2.0, clipped to 1");
    }

    #[test]
    fn sampled_linear_1d() {
        // 2 samples, 1 in, 1 out, 8 bps: [0, 255] -> decodes to [0,1].
        let f = PdfFunction::Sampled {
            domain: vec![[0.0, 1.0]],
            range: vec![[0.0, 1.0]],
            size: vec![2],
            bps: 8,
            encode: vec![[0.0, 1.0]],
            decode: vec![[0.0, 1.0]],
            samples: vec![0, 255],
            n_in: 1,
            n_out: 1,
        };
        assert!((f.eval(&[0.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0])[0] - 1.0).abs() < 1e-6);
        assert!((f.eval(&[0.5])[0] - 0.5).abs() < 1e-2);
    }

    #[test]
    fn postscript_arith_and_ifelse() {
        // { 2 mul 1 sub dup 0 lt { pop 0 } if }
        let prog = parse_ps_program(b"{ 2 mul 1 sub dup 0 lt { pop 0 } if }").unwrap();
        // input 1.0 -> 2*1-1 = 1.0 (not < 0)
        let out = eval_ps(&prog, &[1.0]).unwrap();
        assert!((out[0] - 1.0).abs() < 1e-9);
        // input 0.0 -> 2*0-1 = -1 < 0 -> 0
        let out2 = eval_ps(&prog, &[0.0]).unwrap();
        assert!((out2[0] - 0.0).abs() < 1e-9);
    }

    // 7.10.5 Table 42 operand ORDER and rounding/truncation semantics. A bug in any of
    // these shows up simultaneously as wrong gradient colours and wrong Separation
    // colours, because both go through the same evaluator.
    #[test]
    fn postscript_operator_semantics_match_table_42() {
        let run = |src: &[u8]| -> Vec<f64> {
            eval_ps(&parse_ps_program(src).expect("parses"), &[]).expect("runs")
        };
        let one = |src: &[u8]| run(src)[0];

        // `num1 num2 sub/div/idiv/mod` take num1 from BELOW num2 on the stack.
        assert_eq!(one(b"{ 7 2 sub }"), 5.0);
        assert_eq!(one(b"{ 7 2 div }"), 3.5);
        // idiv and mod truncate toward zero and keep the dividend's sign.
        assert_eq!(one(b"{ 7 2 idiv }"), 3.0);
        assert_eq!(one(b"{ -7 2 idiv }"), -3.0);
        assert_eq!(one(b"{ -7 2 mod }"), -1.0);
        // `base exponent exp`, not the other way round.
        assert_eq!(one(b"{ 2 10 exp }"), 1024.0);
        // A negative base with a fractional exponent is NaN; it must not escape.
        assert!(one(b"{ -8 0.5 exp }").is_finite());

        // Angles are DEGREES, and `num den atan` returns 0..360.
        assert!((one(b"{ 90 sin }") - 1.0).abs() < 1e-12);
        assert!((one(b"{ 180 cos }") + 1.0).abs() < 1e-12);
        assert!((one(b"{ 0 1 atan }") - 0.0).abs() < 1e-9);
        assert!((one(b"{ 1 0 atan }") - 90.0).abs() < 1e-9);
        assert!((one(b"{ -1 0 atan }") - 270.0).abs() < 1e-9, "never negative");

        // truncate/cvi cut toward zero; round breaks a tie toward +infinity (PLRM),
        // which f64::round does NOT do.
        assert_eq!(one(b"{ -1.7 truncate }"), -1.0);
        assert_eq!(one(b"{ -1.7 cvi }"), -1.0);
        assert_eq!(one(b"{ -1.5 round }"), -1.0);
        assert_eq!(one(b"{ 1.5 round }"), 2.0);
        assert_eq!(one(b"{ -1.7 floor }"), -2.0);
        assert_eq!(one(b"{ -1.7 ceiling }"), -1.0);

        // `n j roll` moves the bottom of the n-group UPWARD for positive j.
        assert_eq!(run(b"{ 1 2 3 3 1 roll }"), vec![3.0, 1.0, 2.0]);
        assert_eq!(run(b"{ 1 2 3 3 -1 roll }"), vec![2.0, 3.0, 1.0]);
        // `n index` counts down from the top, 0 being the top itself.
        assert_eq!(run(b"{ 10 20 30 2 index }"), vec![10.0, 20.0, 30.0, 10.0]);
        assert_eq!(run(b"{ 10 20 2 copy }"), vec![10.0, 20.0, 10.0, 20.0]);
        // `int shift bitshift`, negative shift = right.
        assert_eq!(one(b"{ 1 4 bitshift }"), 16.0);
        assert_eq!(one(b"{ 16 -4 bitshift }"), 1.0);
    }

    // 7.10.5 Table 42's integer operators take their operands from a `f64 as i64`
    // cast, which SATURATES — so any real large enough reaches i64::MIN and
    // `i64::MIN / -1`, `i64::MIN % -1` and `-i64::MIN` all overflow. Rust panics on
    // signed division overflow in RELEASE as well as debug, and the panic unwinds out
    // of the whole page render. This is the sharpest example of the shared-evaluator
    // blast radius: the same six lines serve gradient colours (§8.7.4) and
    // Separation/DeviceN tint transforms (§8.6.6.4), so one crafted Type 4 kills both.
    #[test]
    fn postscript_integer_operators_saturate_instead_of_panicking() {
        let one = |src: &[u8]| eval_ps(&parse_ps_program(src).unwrap(), &[]).unwrap()[0];
        assert!(one(b"{ -1e300 -1 idiv }").is_finite());
        assert!(one(b"{ -1e300 -1 mod }").is_finite());
        assert!(one(b"{ 1 -1e300 bitshift }").is_finite());
        assert!(one(b"{ 1 1e300 bitshift }").is_finite());
        // Division by zero still yields 0 rather than a trap.
        assert_eq!(one(b"{ 7 0 idiv }"), 0.0);
        assert_eq!(one(b"{ 7 0 mod }"), 0.0);
        assert_eq!(one(b"{ 7 0 div }"), 0.0);
        // And the ordinary cases are unchanged.
        assert_eq!(one(b"{ -7 2 idiv }"), -3.0);
        assert_eq!(one(b"{ -7 2 mod }"), -1.0);
    }

    // 7.10.4's `/Functions` array and the array-of-functions form both recurse through
    // `parse`, and nothing stops `5 0 R` naming a Type 3 whose `/Functions` is `[5 0 R]`.
    // That recursed until the stack overflowed — which is NOT a panic, so `catch_unwind`
    // cannot contain it and the process dies rather than the page failing to render.
    #[test]
    fn a_self_referential_function_is_rejected_rather_than_overflowing_the_stack() {
        let mut doc = Document::with_version("1.7");
        let id = doc.new_object_id();
        doc.set_object(
            id,
            dictionary! {
                "FunctionType" => 3,
                "Domain" => vec![0.into(), 1.into()],
                "Functions" => vec![Object::Reference(id)],
                "Bounds" => Vec::<Object>::new(),
                "Encode" => vec![0.into(), 1.into()],
            },
        );
        assert!(PdfFunction::parse(&doc, &Object::Reference(id)).is_none());

        // An array of functions that contains itself takes the other recursive path.
        let arr = doc.new_object_id();
        doc.set_object(arr, Object::Array(vec![Object::Reference(arr)]));
        assert!(PdfFunction::parse(&doc, &Object::Reference(arr)).is_none());

        // A mutual cycle between a Type 3 and an array goes through both arms.
        let a = doc.new_object_id();
        let b = doc.new_object_id();
        doc.set_object(a, Object::Array(vec![Object::Reference(b)]));
        doc.set_object(
            b,
            dictionary! {
                "FunctionType" => 3,
                "Domain" => vec![0.into(), 1.into()],
                "Functions" => vec![Object::Reference(a)],
                "Bounds" => Vec::<Object>::new(),
                "Encode" => vec![0.into(), 1.into()],
            },
        );
        assert!(PdfFunction::parse(&doc, &Object::Reference(a)).is_none());

        // A legitimate one-level Type 3 over two Type 2s still parses.
        let leaf = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let ok = doc.add_object(dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![0.into(), 1.into()],
            "Functions" => vec![Object::Reference(leaf), Object::Reference(leaf)],
            "Bounds" => vec![Object::Real(0.5)],
            "Encode" => vec![0.into(), 1.into(), 0.into(), 1.into()],
        });
        assert!(PdfFunction::parse(&doc, &Object::Reference(ok)).is_some());
    }

    // 7.10.2: sample data is ordered with the FIRST input dimension varying fastest,
    // and the reconstruction is multilinear. Nearest-neighbour (or a transposed index)
    // passes a 1-D two-sample test but fails here, and shows as banded gradients.
    #[test]
    fn sampled_is_bilinear_with_first_dimension_fastest() {
        // 2x2 grid, one output. Sample order is (x0,y0) (x1,y0) (x0,y1) (x1,y1).
        let f = PdfFunction::Sampled {
            domain: vec![[0.0, 1.0], [0.0, 1.0]],
            range: vec![[0.0, 1.0]],
            size: vec![2, 2],
            bps: 8,
            encode: vec![[0.0, 1.0], [0.0, 1.0]],
            decode: vec![[0.0, 1.0]],
            samples: vec![0, 255, 0, 0],
            n_in: 2,
            n_out: 1,
        };
        // Corners come straight back.
        assert!((f.eval(&[0.0, 0.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0, 0.0])[0] - 1.0).abs() < 1e-6, "x varies fastest");
        assert!((f.eval(&[0.0, 1.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0, 1.0])[0] - 0.0).abs() < 1e-6);
        // Interior is the bilinear blend, not a nearest corner.
        assert!((f.eval(&[0.5, 0.0])[0] - 0.5).abs() < 1e-2);
        assert!((f.eval(&[0.5, 0.5])[0] - 0.25).abs() < 1e-2);
        // A quarter step must land a quarter of the way, which nearest-neighbour
        // would snap to 0 or 1.
        assert!((f.eval(&[0.25, 0.0])[0] - 0.25).abs() < 1e-2);
    }

    // A truncated sample stream must read as 0, not as a partially shifted value
    // that looks like a plausible sample.
    #[test]
    fn read_sample_past_end_is_zero() {
        let data = [0xFFu8]; // one byte = one 8-bit sample
        assert!((read_sample(&data, 0, 8) - 255.0).abs() < 1e-9);
        assert_eq!(read_sample(&data, 1, 8), 0.0, "sample past the end reads 0");
        // A 16-bit sample straddling the end must also read 0, not 0xFF00.
        assert_eq!(read_sample(&data, 0, 16), 0.0, "partial sample reads 0");
    }

    // A Type 2 whose C0/C1 are absent must broadcast its scalar defaults to the
    // arity implied by /Range (7.10.3 Table 40 + Table 38), otherwise a spot colour
    // over a 4-component alternate space receives one component and degrades.
    #[test]
    fn exponential_broadcasts_defaults_to_range_arity() {
        let mut doc = Document::with_version("1.7");
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "N" => 1,
            // 4 pairs => j = 4, with no C0/C1 given.
            "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(),
                            0.into(), 1.into(), 0.into(), 1.into()],
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(id)).expect("type 2 parses");
        let out = f.eval(&[0.5]);
        assert_eq!(out.len(), 4, "arity comes from /Range when C0/C1 are absent");
        for v in &out {
            assert!((v - 0.5).abs() < 1e-9, "each component ramps 0 -> 1");
        }
    }

    // /BitsPerSample outside Table 39's set is rejected at parse. bps == 0 used to
    // divide by zero and produce NaN components that survived the Range clamp.
    #[test]
    fn sampled_rejects_illegal_bits_per_sample() {
        let mk = |bps: i64| {
            let mut doc = Document::with_version("1.7");
            let id = doc.add_object(Stream::new(
                dictionary! {
                    "FunctionType" => 0,
                    "Domain" => vec![0.into(), 1.into()],
                    "Range" => vec![0.into(), 1.into()],
                    "Size" => vec![2.into()],
                    "BitsPerSample" => bps,
                },
                vec![0u8, 255u8],
            ));
            PdfFunction::parse(&doc, &Object::Reference(id)).is_some()
        };
        assert!(mk(8), "8 bps is legal");
        assert!(!mk(0), "0 bps must be rejected");
        assert!(!mk(5), "5 bps is not in Table 39");
    }

    // A sampled function's input arity is taken from /Size and drives a 2^m corner
    // loop per evaluation, so an absurd /Size must be rejected rather than hang.
    #[test]
    fn sampled_rejects_absurd_input_arity() {
        let mut doc = Document::with_version("1.7");
        let size: Vec<Object> = (0..32).map(|_| Object::Integer(2)).collect();
        let id = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 0,
                "Domain" => vec![0.into(), 1.into()],
                "Range" => vec![0.into(), 1.into()],
                "Size" => size,
                "BitsPerSample" => 8,
            },
            vec![0u8; 64],
        ));
        assert!(
            PdfFunction::parse(&doc, &Object::Reference(id)).is_none(),
            "32 input dimensions would mean 2^32 corner evaluations per call"
        );
    }

    // §11.6.5.2: an INVERTING /TR is the standard idiom for "mask out where the group is
    // bright", so it must survive sampling into the LUT exactly. Ignoring it hides the
    // wrong half of the content, which is the visible symptom this LUT exists to fix.
    #[test]
    fn inverting_transfer_function_becomes_an_inverting_lut() {
        let mut doc = Document::with_version("1.7");
        // { 1 exch sub } — the canonical inverter.
        let id = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 1.into()],
                "Range" => vec![0.into(), 1.into()],
            },
            b"{ 1 exch sub }".to_vec(),
        ));
        let lut = read_transfer_lut(&doc, &Object::Reference(id)).expect("inverting /TR is not identity");
        assert_eq!(lut[0], 255, "0 maps to 255");
        assert_eq!(lut[255], 0, "255 maps to 0");
        assert!(lut[128].abs_diff(127) <= 1, "midpoint stays mid, got {}", lut[128]);
    }

    // /Identity, and anything indistinguishable from it at 8-bit precision, must report
    // None so callers do not pay to carry or apply a no-op table.
    #[test]
    fn identity_transfer_function_is_none() {
        let mut doc = Document::with_version("1.7");
        assert!(
            read_transfer_lut(&doc, &Object::Name(b"Identity".to_vec())).is_none(),
            "/Identity has no effect"
        );
        // A Type 2 ramp 0 -> 1 with N=1 IS the identity.
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        assert!(
            read_transfer_lut(&doc, &Object::Reference(id)).is_none(),
            "a 0->1 linear ramp is the identity to within one 8-bit step"
        );
        // Something that is not a function at all also yields None rather than garbage.
        assert!(read_transfer_lut(&doc, &Object::Integer(3)).is_none());
    }

    // 7.10.1 Table 38 gives no ordering guarantee on /Domain or /Range beyond their
    // meaning, and `f64::clamp` PANICS when its low bound exceeds its high bound. A
    // reversed pair therefore aborted the page rather than clipping.
    #[test]
    fn reversed_domain_and_range_clip_instead_of_panicking() {
        let mut doc = Document::with_version("1.7");
        let t2 = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![1.into(), 0.into()],
            "Range" => vec![1.into(), 0.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(t2)).expect("parses");
        assert!(f.eval(&[0.5])[0].is_finite());

        let t4 = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![1.into(), 0.into()],
                "Range" => vec![1.into(), 0.into()],
            },
            b"{ }".to_vec(),
        ));
        let f4 = PdfFunction::parse(&doc, &Object::Reference(t4)).expect("parses");
        assert!(f4.eval(&[0.5])[0].is_finite());

        let t0 = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 0,
                "Domain" => vec![1.into(), 0.into()],
                "Range" => vec![1.into(), 0.into()],
                "Size" => vec![2.into()],
                "BitsPerSample" => 8,
            },
            vec![0u8, 255u8],
        ));
        let f0 = PdfFunction::parse(&doc, &Object::Reference(t0)).expect("parses");
        assert!(f0.eval(&[0.5])[0].is_finite());

        let t3 = doc.add_object(dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![1.into(), 0.into()],
            "Functions" => vec![Object::Reference(t2)],
            "Bounds" => Vec::<Object>::new(),
            "Encode" => vec![0.into(), 1.into()],
        });
        let f3 = PdfFunction::parse(&doc, &Object::Reference(t3)).expect("parses");
        assert!(f3.eval(&[0.5])[0].is_finite());
    }

    // A non-monotonic /TR must be carried faithfully; only the FIRST output component is
    // used, per 11.6.5.2's one-in/one-out requirement.
    #[test]
    fn transfer_lut_uses_only_the_first_output() {
        let f = PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![1.0, 0.0, 0.0],
            c1: vec![0.0, 1.0, 1.0],
            n: 1.0,
        };
        let lut = f.to_lut256();
        assert_eq!(lut[0], 255, "first component starts at 1.0");
        assert_eq!(lut[255], 0, "and ends at 0.0");
    }

    // SEAM TEST: dictionary -> `PdfFunction::parse` -> output arity -> `eval_cs_to_rgb`
    // for a Separation. Neither side of this join was witnessed: my own arity tests stop
    // at `out.len()`, and color.rs's Separation tests hand-BUILD a `PdfFunction` rather
    // than parsing one, so nothing exercised parse feeding a real colour conversion.
    //
    // It matters because the failure is silent and total. §8.6.6.4's tint transform must
    // yield `cs_kind_ncomp(alt)` components; when it yields fewer, `eval_cs_to_rgb` falls
    // back to a subtractive grey ramp. So an arity regression in Type 2 parsing does not
    // produce a slightly wrong colour — every spot colour on the page turns grey, which
    // is 7.10's "function bugs show up as wrong Separation colours" in its worst form.
    #[test]
    fn a_parsed_tint_transform_drives_a_separation_to_a_real_colour() {
        let mut doc = Document::with_version("1.7");
        // Type 2 over DeviceCMYK: t=1 -> (0, 1, 1, 0), i.e. red.
        let good = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 1.into(), 1.into(), 0.into()],
            "N" => 1,
        });
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: PdfFunction::parse(&doc, &Object::Reference(good)),
        };
        let res = HashMap::new();
        let argb = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).expect("separation resolves");
        let (r, g, b) = ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        assert!(r > 200 && g < 60 && b < 60, "full tint is red, got #{r:02x}{g:02x}{b:02x}");

        // The same Separation whose tint transform parses to the WRONG arity takes the
        // grey-ramp fallback. Asserting the two differ pins the seam without this test
        // needing to know the fallback's formula.
        let short = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let cs_short = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: PdfFunction::parse(&doc, &Object::Reference(short)),
        };
        let fallback = eval_cs_to_rgb(&doc, &cs_short, &[1.0], &res).expect("fallback resolves");
        assert_ne!(
            argb, fallback,
            "a correctly-parsed 4-component tint transform must not land on the grey ramp"
        );
    }
}
