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
        c0: Vec<f64>,
        c1: Vec<f64>,
        n: f64,
    },
    Stitching {
        domain: [f64; 2],
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

impl PdfFunction {
    /// Parse a function object (reference, dict, stream, or array-of-functions).
    pub(crate) fn parse(doc: &Document, obj: &Object) -> Option<PdfFunction> {
        let resolved = deref(doc, obj)?;
        if let Object::Array(arr) = resolved {
            // Array of functions -> one output component each.
            let mut fns = Vec::new();
            for o in arr {
                if let Some(f) = PdfFunction::parse(doc, o) {
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
                let range_j = read_pairs(dict.get(b"Range").ok()).len();
                let j = c0.len().max(c1.len()).max(range_j).min(MAX_FN_OUTPUTS);
                c0.resize(j, 0.0);
                c1.resize(j, 1.0);
                let n = dict.get(b"N").ok().and_then(num).unwrap_or(1.0);
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Exponential { domain, c0, c1, n })
            }
            3 => {
                let funcs_obj = deref(doc, dict.get(b"Functions").ok()?)?;
                let funcs_arr = funcs_obj.as_array().ok()?;
                let mut functions = Vec::new();
                for o in funcs_arr {
                    functions.push(PdfFunction::parse(doc, o)?);
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
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Stitching { domain, functions, bounds, encode })
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
            PdfFunction::Exponential { domain, c0, c1, n } => {
                let t = inputs.first().copied().unwrap_or(0.0).clamp(domain[0], domain[1]);
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
                        a + (b - a) * tn
                    })
                    .collect()
            }
            PdfFunction::Stitching { domain, functions, bounds, encode } => {
                if functions.is_empty() {
                    return Vec::new();
                }
                let x = inputs.first().copied().unwrap_or(0.0).clamp(domain[0], domain[1]);
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
                functions[k].eval(&[xe])
            }
            PdfFunction::Sampled { .. } => self.eval_sampled(inputs),
            PdfFunction::PostScript { domain, range, program } => {
                let clamped: Vec<f64> = inputs
                    .iter()
                    .enumerate()
                    .map(|(i, v)| {
                        if let Some(d) = domain.get(i) {
                            v.clamp(d[0], d[1])
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
                            *v = v.clamp(r[0], r[1]);
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
            let x = inputs.get(i).copied().unwrap_or(0.0).clamp(d[0], d[1]);
            let ev = if (d[1] - d[0]).abs() < 1e-12 {
                enc[0]
            } else {
                enc[0] + (x - d[0]) * (enc[1] - enc[0]) / (d[1] - d[0])
            };
            e.push(ev.clamp(0.0, (*sz as f64 - 1.0).max(0.0)));
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
                *o = o.clamp(r[0], r[1]);
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
        Round => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.round())); }
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
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { (a / b) as f64 } else { 0.0 }));
        }
        Mod => {
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { (a % b) as f64 } else { 0.0 }));
        }
        Exp => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a.powf(b))); }
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
            let r = if shift >= 0 { a << (shift.min(63)) } else { a >> ((-shift).min(63)) };
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

    #[test]
    fn stitching_selects_subfunction() {
        let f = PdfFunction::Stitching {
            domain: [0.0, 1.0],
            functions: vec![
                PdfFunction::Exponential { domain: [0.0, 1.0], c0: vec![0.0], c1: vec![1.0], n: 1.0 },
                PdfFunction::Exponential { domain: [0.0, 1.0], c0: vec![1.0], c1: vec![0.0], n: 1.0 },
            ],
            bounds: vec![0.5],
            encode: vec![[0.0, 1.0], [0.0, 1.0]],
        };
        // Below the bound -> first function, above -> second.
        let a = f.eval(&[0.25]);
        let b = f.eval(&[0.75]);
        assert!(a[0] > 0.0 && a[0] < 1.0);
        assert!(b[0] > 0.0 && b[0] < 1.0);
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

    // A non-monotonic /TR must be carried faithfully; only the FIRST output component is
    // used, per 11.6.5.2's one-in/one-out requirement.
    #[test]
    fn transfer_lut_uses_only_the_first_output() {
        let f = PdfFunction::Exponential {
            domain: [0.0, 1.0],
            c0: vec![1.0, 0.0, 0.0],
            c1: vec![0.0, 1.0, 1.0],
            n: 1.0,
        };
        let lut = f.to_lut256();
        assert_eq!(lut[0], 255, "first component starts at 1.0");
        assert_eq!(lut[255], 0, "and ends at 0.0");
    }
}
