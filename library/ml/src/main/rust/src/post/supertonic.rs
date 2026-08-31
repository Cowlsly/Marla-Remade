//! The host half of Supertonic 3's sampler: the timestep embedding, the rotary angles, and the
//! guidance combination that turns two forward passes into one Euler step.
//!
//! # Why any of this is on the host
//!
//! [`crate::nets::supertonic_sampler`] is a plan of convolutions, attentions and layer norms.
//! Four things in the export are none of those, and all four are cheap:
//!
//! * The **timestep conditioning** is a sinusoidal embedding of `current_step / total_step`, a
//!   two-layer MLP with a Mish between, and four `Linear`s to 512 numbers. 2,048 values from two
//!   scalars, against a net that spends 64M multiply-adds per latent frame — so `Sin`, `Cos`,
//!   `Softplus` and `Tanh` shaders would exist for nothing.
//! * The **rotary angles** are `(position / length) * theta`. Nothing learned, and they change
//!   with the sequence lengths rather than with the weights.
//! * The **unconditional branch's inputs** are two learned tokens, one broadcast over the text
//!   and one standing in for the voice.
//! * The **guidance combination and the Euler step**, four multiply-adds per latent value.
//!
//! Each of those tensors is declared to [`crate::nets::Builder::host_tensor`] so a genuinely
//! unread weight is still an error.
//!
//! # The step is two passes
//!
//! The export tiles its batch to two and runs the whole network twice, once conditioned on the
//! real text and voice and once on the unconditional tokens, then takes
//! `4 * conditional - 3 * unconditional`. This runtime has no batch axis, so [`step`] takes the
//! two velocities the caller already ran. That is a genuine doubling of the sampler's cost.

use crate::nets::embed_lanes;
use crate::nets::supertonic_duration as duration_net;
use crate::nets::supertonic_sampler as net;
use crate::preprocess::f16_to_f32;
use crate::weights::Reader;

/// The frequency multiplier the export applies before the sinusoids: `t * 1000 * frequency`.
const TIME_SCALE: f32 = 1000.0;

/// `out[o] = sum_i weight[o][i] * x[i] + bias[o]`, over a row-major `[out, in]` weight.
fn linear(weight: &[f32], bias: &[f32], x: &[f32]) -> Vec<f32> {
    let inputs = x.len();
    bias.iter()
        .enumerate()
        .map(|(o, &b)| {
            let row = &weight[o * inputs..(o + 1) * inputs];
            row.iter().zip(x).map(|(&w, &v)| w * v).sum::<f32>() + b
        })
        .collect()
}

/// `x * tanh(softplus(x))`, the export's `mlp.1`.
///
/// `softplus` is written as `ln(1 + e^x)` only for negative `x`: for large positive `x` the
/// exponential overflows while the function is within an fp32 epsilon of `x` itself.
fn mish(x: f32) -> f32 {
    let softplus = if x > 20.0 { x } else { x.exp().ln_1p() };
    x * softplus.tanh()
}

/// The four per-block timestep shifts, `[4 * 512]`, for step `current` of `total`.
///
/// The plan reads them as one `[2048, 1, 1]` input and [`crate::nets::Builder::slice_channels`]
/// hands each main block its own 512.
pub fn time_shifts(weights: Reader, current: u32, total: u32) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    let frequencies = weights.fp16(net::HOST_FREQUENCIES, &[net::FREQUENCIES])?;
    let progress = current as f32 / total as f32;

    // Sines then cosines, which is the order of the export's `Concat`.
    let angles: Vec<f32> = frequencies.iter().map(|&f| progress * TIME_SCALE * f).collect();
    let embedding: Vec<f32> = angles
        .iter()
        .map(|a| a.sin())
        .chain(angles.iter().map(|a| a.cos()))
        .collect();

    let in_weight = weights.fp16(net::HOST_MLP_IN, &[net::TIME_INNER, net::TIME])?;
    let in_bias = weights.fp16(net::HOST_MLP_IN + 1, &[net::TIME_INNER])?;
    let hidden: Vec<f32> = linear(&in_weight, &in_bias, &embedding).into_iter().map(mish).collect();

    let out_weight = weights.fp16(net::HOST_MLP_OUT, &[net::TIME, net::TIME_INNER])?;
    let out_bias = weights.fp16(net::HOST_MLP_OUT + 1, &[net::TIME])?;
    let time = linear(&out_weight, &out_bias, &hidden);

    let mut shifts = Vec::with_capacity(net::MAIN_BLOCKS * net::CHANNELS as usize);
    for block in 0..net::MAIN_BLOCKS {
        let index = net::HOST_TIME_LINEARS + block * 2;
        let weight = weights.fp16(index, &[net::CHANNELS, net::TIME])?;
        let bias = weights.fp16(index + 1, &[net::CHANNELS])?;
        shifts.extend(linear(&weight, &bias, &time));
    }
    Ok(shifts)
}

/// The rotary angle table for a sequence of `positions`, as the `[64, 1, positions]` the plan
/// wants: 32 channels of cosine then 32 of sine.
///
/// The angle is `(position / positions) * theta` — normalised by the sequence's **own** length,
/// which is what lets a latent frame and a text character at the same fraction of the way through
/// meet at the same angle. It also means the query table and the key table are different tensors
/// even though they share `theta`.
pub fn rotary_angles(theta: &[f32], positions: u32) -> Result<Vec<f32>, String> {
    if positions == 0 {
        return Err("a rotary table over no positions".into());
    }
    if theta.len() != net::FREQUENCIES as usize {
        return Err(format!("{} rotary frequencies, not {}", theta.len(), net::FREQUENCIES));
    }
    let width = positions as usize;
    let mut table = vec![0.0f32; 2 * net::FREQUENCIES as usize * width];
    for (frequency, &turn) in theta.iter().enumerate() {
        for position in 0..width {
            let angle = position as f32 / positions as f32 * turn;
            table[frequency * width + position] = angle.cos();
            table[(net::FREQUENCIES as usize + frequency) * width + position] = angle.sin();
        }
    }
    Ok(table)
}

/// The unconditional branch's text input: `text_special_token` at every one of `chars` positions.
pub fn unconditional_text(token: &[f32], chars: u32) -> Result<Vec<f32>, String> {
    if chars == 0 {
        return Err("an unconditional text of no characters".into());
    }
    if token.len() != net::TEXT as usize {
        return Err(format!("an unconditional token of {}, not {}", token.len(), net::TEXT));
    }
    let mut out = Vec::with_capacity(token.len() * chars as usize);
    for &value in token {
        out.extend(std::iter::repeat_n(value, chars as usize));
    }
    Ok(out)
}

/// The unconditional branch's style values, `[256, 1, 50]` and already transposed in the file.
pub fn unconditional_style(weights: Reader) -> Result<Vec<f32>, String> {
    weights.fp16(net::HOST_STYLE_TOKEN, &[net::STYLE, net::STYLE_TOKENS])
}

/// The folded style keys for one guidance branch, `[4 * 256, 1, 50]`.
///
/// `tanh(W_key . style_key + b_key)`, all constant, so the converter evaluates it. The four style
/// attentions share one `style_key` but each has its own `W_key`, so this is four 256-channel
/// blocks stacked; the plan slices its own out. The two branches have different `style_key`s,
/// which is the only structural difference between them — everything else is an input, so one
/// plan serves both.
pub fn style_keys(weights: Reader, conditional: bool) -> Result<Vec<f32>, String> {
    let index = if conditional {
        net::HOST_KEYS_CONDITIONAL
    } else {
        net::HOST_KEYS_UNCONDITIONAL
    };
    weights.fp16(index, &[net::STYLE * net::MAIN_BLOCKS as u32, net::STYLE_TOKENS])
}

/// Entries in the codepoint table: every code unit of the Basic Multilingual Plane.
pub const INDEXER_ENTRIES: usize = 65_536;

/// Character ids for text that is **already NFKD-decomposed**, wrapped in its language tag and
/// dropping what the model has no token for.
///
/// `indexer` is the voice bundle's `unicode_indexer.bin`: [`INDEXER_ENTRIES`] little-endian
/// `int16`, one per BMP codepoint, `-1` where there is no token. 8,321 of them are mapped.
///
/// # The language tag is not optional, and getting it wrong is silent
///
/// Supertonic 3 is the 31-language model, and it was trained with every utterance wrapped as
/// `<en>text</en>`. `language` is the ISO-639-1 code, or `na` for one the model does not list.
///
/// The tag is not a special token with a row of its own — it is literally the characters `<`,
/// `e`, `n` and `>` through this same table, which is why it needs no re-export. That is also
/// what makes omitting it so expensive to find: the ids stay in range, every net still matches
/// onnxruntime to five decimal places, and the model reads the sentence in confident,
/// correctly-timed gibberish. The symptom is fluent-sounding speech that is not words. Untagged,
/// two noise draws of one sentence agreed spectrally at 0.36; tagged, 0.70 — a conditioned flow
/// says the same thing whatever the noise, and that ratio is the cheapest check that this
/// argument is still being threaded through.
///
/// # NFKD is the caller's job, and it is not optional either
///
/// The model has no precomposed accents: `U+00E9` is unmapped while `e` and `U+0301` are both
/// first-class tokens, and Hangul syllables map only through the Jamo block. So `café`, `über`,
/// `niño`, `안녕` and `привет` index completely under NFKD and partially or not at all otherwise.
/// The Kotlin side calls `java.text.Normalizer.normalize(text, Form.NFKD)`, which is a platform
/// API and free; doing it here would mean carrying Unicode decomposition tables in the APK.
///
/// # Unmapped codepoints are dropped, not substituted
///
/// There is no unknown token to substitute. Dropping loses a character; mapping to something
/// else would mispronounce it, and mapping past the table would read the sentence token — see
/// [`crate::nets::supertonic_duration`]. Text of which *nothing* maps is refused rather than
/// synthesised as an empty tag pair.
pub fn to_ids(indexer: &[u8], text: &str, language: &str) -> Result<Vec<u32>, String> {
    if indexer.len() != INDEXER_ENTRIES * 2 {
        return Err(format!(
            "a codepoint table of {} bytes, not {}",
            indexer.len(),
            INDEXER_ENTRIES * 2
        ));
    }
    // Every code the model knows is two ASCII lowercase letters, `na` included. Checked because
    // a malformed tag indexes cleanly and then mispronounces the whole utterance.
    if language.len() != 2 || !language.bytes().all(|b| b.is_ascii_lowercase()) {
        return Err(format!("a language code of {language:?}, not two lowercase letters"));
    }
    let index = |text: &str| {
        text.chars()
            .filter_map(|codepoint| {
                let entry = codepoint as usize;
                // Astral-plane characters — emoji, most CJK extensions — are outside the table
                // entirely rather than mapped to -1.
                if entry >= INDEXER_ENTRIES {
                    return None;
                }
                let at = entry * 2;
                let token = i16::from_le_bytes([indexer[at], indexer[at + 1]]);
                (token >= 0).then_some(token as u32)
            })
            .collect::<Vec<u32>>()
    };

    // The tag is indexed apart from the text so that text which maps to nothing is still refused.
    // Tagged, the ids would never be empty, and the model would read out an empty utterance.
    let body = index(text);
    if body.is_empty() {
        return Err("nothing in this text is in the model's vocabulary".into());
    }
    let mut ids = index(&format!("<{language}>"));
    ids.extend(body);
    ids.extend(index(&format!("</{language}>")));
    Ok(ids)
}

/// The speed the SDK reads at by default, which the predicted duration is divided by.
///
/// `supertonic`'s Python SDK defaults `synthesize(speed=1.05)` and calls values near it "more
/// natural speech"; the duration predictor is trained against un-sped reference audio, so
/// reading its answer literally is a 5% drawl. It divides the seconds, so a *larger* speed is a
/// *shorter* utterance.
pub const SPEED: f32 = 1.05;

/// Sampler steps per utterance.
///
/// Measured, not chosen: at four or fewer the waveform's peak goes above 1.0, sixteen is the floor
/// for a stable level, and audio at 16 correlates with audio at 32 at only 0.883. There is no
/// cheap-steps escape hatch here, which with two guidance branches per step means 32 passes of
/// [`crate::nets::supertonic_sampler`] for one sentence.
///
/// The 0.883 is not a quality argument either way. It was once read as one, and 32 was tried
/// against speech that turned out to be garbled for an unrelated reason — see [`to_ids`].
pub const STEPS: u32 = 16;

/// The GPU stages, as a trait, so the sequencing below is host-testable against stubs.
///
/// The same shape `post::ocr` uses, and for the same reason: the order of the
/// four nets, the length arithmetic between them and the sampler loop are where the mistakes are,
/// and none of them needs a device to check.
pub trait Stages {
    /// The duration predictor over `chars + 1` id lanes and a `[128]` style, returning the one
    /// value [`crate::nets::supertonic_duration::seconds`] exponentiates.
    fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String>;

    /// The text encoder, returning `[256, chars]`.
    fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String>;

    /// One guidance branch of the sampler, returning `[144, frames]`.
    #[allow(clippy::too_many_arguments)]
    fn sampler(
        &mut self,
        latent: &[f32],
        text: &[f32],
        keys: &[f32],
        style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<Vec<f32>, String>;

    /// The vocoder over a `[144, frames]` latent, returning `frames * SAMPLES_PER_FRAME` samples.
    fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String>;
}

/// Everything the sampler needs from the weights file that a shader never sees, read once.
///
/// [`synthesise`] takes this rather than a [`Reader`] so the sequencing can be tested against
/// stubs, and so the file is walked once per voice instead of once per step.
pub struct Conditioning {
    /// The rotary `theta`, `[32]`.
    pub theta: Vec<f32>,
    /// The per-block timestep shifts, one `[4 * 512]` per step.
    pub shifts: Vec<Vec<f32>>,
    /// The folded conditional style keys, `[4 * 256, 50]`.
    pub conditional_keys: Vec<f32>,
    /// The folded unconditional style keys, `[4 * 256, 50]`.
    pub unconditional_keys: Vec<f32>,
    /// `text_special_token`, `[256]`, to broadcast over the text positions.
    pub text_token: Vec<f32>,
    /// `style_value_special_token`, `[256, 50]`.
    pub unconditional_style: Vec<f32>,
}

impl Conditioning {
    /// Read all of it, for a sampler run of [`STEPS`] steps.
    pub fn read(weights: Reader) -> Result<Conditioning, String> {
        Ok(Conditioning {
            theta: weights.fp16(net::HOST_THETA, &[net::FREQUENCIES])?,
            shifts: (0..STEPS)
                .map(|step| time_shifts(weights, step, STEPS))
                .collect::<Result<_, _>>()?,
            conditional_keys: style_keys(weights, true)?,
            unconditional_keys: style_keys(weights, false)?,
            text_token: weights.fp16(net::HOST_TEXT_TOKEN, &[net::TEXT])?,
            unconditional_style: unconditional_style(weights)?,
        })
    }
}

/// A voice: the two style tensors the nets take as inputs, already in this runtime's layout.
pub struct Voice {
    /// `style_dp` flattened to 128, for the duration predictor.
    pub duration: Vec<f32>,
    /// `style_ttl` transposed to `[256, 50]`, for the text encoder and the sampler.
    pub text: Vec<f32>,
}

impl Voice {
    /// Read one `style_<name>.bin` from the voice bundle.
    ///
    /// `style_ttl` transposed to `[256, 50]` first, then `style_dp` flattened to 128, both as
    /// little-endian fp16 and nothing else — no header, because the two shapes are fixed by the
    /// architecture and a length check is therefore as strong as a table would be.
    ///
    /// Not a `.maml`: these are **inputs**, one pair per voice, handed to
    /// [`crate::vulkan::run::Net::infer_raw_many`] per utterance rather than living in a plan's
    /// weights buffer. `scripts/ml/supertonic_bundle.py` writes them, and does the transpose
    /// there because the export stores `style_ttl` position-major and this runtime is
    /// channel-major.
    pub fn read(bytes: &[u8]) -> Result<Voice, String> {
        let text_values = (net::STYLE * net::STYLE_TOKENS) as usize;
        let duration_values = duration_net::STYLE as usize;
        let wanted = (text_values + duration_values) * 2;
        if bytes.len() != wanted {
            return Err(format!("a voice file of {} bytes, not {wanted}", bytes.len()));
        }
        let values: Vec<f32> = bytes
            .chunks_exact(2)
            .map(|pair| f16_to_f32(u16::from_le_bytes([pair[0], pair[1]])))
            .collect();
        let (text, duration) = values.split_at(text_values);
        Ok(Voice { duration: duration.to_vec(), text: text.to_vec() })
    }
}

/// Synthesise one utterance.
///
/// `text` must already be NFKD; `language` is its ISO-639-1 code. See [`to_ids`] for why both
/// matter. `noise` supplies the flow's starting latent, one standard normal per value — flow
/// matching is meant to vary between calls, so the caller seeds it from the clock.
///
/// ```text
/// text + language -> ids         to_ids, over the bundle's codepoint table
/// ids + style_dp -> seconds      the duration predictor, then exp, then / SPEED
/// seconds -> frames              ceil(seconds * 44100 / 3072)
/// ids + style_ttl -> text_emb    the text encoder
/// noise + text_emb -> latent     the sampler, [`STEPS`] steps of two guidance branches
/// latent -> waveform             the vocoder
/// ```
pub fn synthesise(
    stages: &mut dyn Stages,
    conditioning: &Conditioning,
    indexer: &[u8],
    voice: &Voice,
    text: &str,
    language: &str,
    noise: &dyn Fn(usize) -> Vec<f32>,
) -> Result<Vec<f32>, String> {
    let ids = to_ids(indexer, text, language)?;
    let chars = ids.len() as u32;

    // The duration predictor's sequence leads with the sentence token; the text encoder's does
    // not. Two id tensors, not one.
    let mut with_token = Vec::with_capacity(ids.len() + 1);
    with_token.push(duration_net::SENTENCE_TOKEN);
    with_token.extend_from_slice(&ids);
    let log_seconds = stages.duration(&embed_lanes(&with_token), &voice.duration)?;
    let frames = duration_net::latent_frames(duration_net::seconds(log_seconds) / SPEED);

    let conditioning_text = stages.text(&embed_lanes(&ids), &voice.text)?;

    let query_angles = rotary_angles(&conditioning.theta, frames)?;
    let key_angles = rotary_angles(&conditioning.theta, chars)?;
    let unconditional_text = unconditional_text(&conditioning.text_token, chars)?;

    let mut latent = noise(net::LATENT as usize * frames as usize);
    for step in 0..STEPS as usize {
        let shifts = conditioning
            .shifts
            .get(step)
            .ok_or("the conditioning holds fewer shifts than there are steps")?;
        let conditional = stages.sampler(
            &latent,
            &conditioning_text,
            &conditioning.conditional_keys,
            &voice.text,
            shifts,
            &query_angles,
            &key_angles,
        )?;
        let unconditional = stages.sampler(
            &latent,
            &unconditional_text,
            &conditioning.unconditional_keys,
            &conditioning.unconditional_style,
            shifts,
            &query_angles,
            &key_angles,
        )?;
        latent = self::step(&latent, &conditional, &unconditional, STEPS)?;
    }

    stages.vocoder(&latent, frames)
}

/// One Euler step: combine the two guidance branches and advance the latent.
///
/// `denoised = latent + (GUIDANCE * conditional - (GUIDANCE - 1) * unconditional) / total`, which
/// is what the export's last five nodes do once its batch is split back in two.
pub fn step(
    latent: &[f32],
    conditional: &[f32],
    unconditional: &[f32],
    total: u32,
) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    if conditional.len() != latent.len() || unconditional.len() != latent.len() {
        return Err(format!(
            "a step over {} latent values against {} conditional and {} unconditional",
            latent.len(),
            conditional.len(),
            unconditional.len()
        ));
    }
    let scale = 1.0 / total as f32;
    Ok(latent
        .iter()
        .zip(conditional)
        .zip(unconditional)
        .map(|((&x, &c), &u)| {
            let velocity = net::GUIDANCE * c - (net::GUIDANCE - 1.0) * u;
            x + velocity * scale
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A table where only the characters named are mapped; everything else is `-1`.
    fn table_of(mapped: &[(char, i16)]) -> Vec<u8> {
        let mut table = vec![0u8; INDEXER_ENTRIES * 2];
        for entry in 0..INDEXER_ENTRIES {
            table[entry * 2..entry * 2 + 2].copy_from_slice(&(-1i16).to_le_bytes());
        }
        for &(codepoint, token) in mapped {
            let at = codepoint as usize * 2;
            table[at..at + 2].copy_from_slice(&token.to_le_bytes());
        }
        table
    }

    #[test]
    fn the_indexer_drops_what_it_cannot_map() {
        // Only 'a' and the combining acute are mapped. 'z' and an emoji outside the BMP both
        // disappear rather than becoming some other character. The tag's characters are unmapped
        // here too, so this says nothing about the tag - `the_indexer_wraps_the_text_in_its_
        // language_tag` does that.
        let table = table_of(&[('a', 60), ('\u{301}', 146)]);
        let got = to_ids(&table, "a\u{301}z\u{1F600}a", "en").expect("indexes");
        assert_eq!(got, vec![60, 146, 60]);
    }

    #[test]
    fn the_indexer_wraps_the_text_in_its_language_tag() {
        // The tag is ordinary characters through the ordinary table, so it has to come out as
        // ids on both sides of the text. Omitting it costs nothing detectable downstream, which
        // is why it is asserted here.
        let table = table_of(&[('<', 1), ('>', 2), ('/', 3), ('e', 4), ('n', 5), ('a', 6)]);
        assert_eq!(
            to_ids(&table, "a", "en").expect("indexes"),
            vec![1, 4, 5, 2, 6, 1, 3, 4, 5, 2],
            "<en>a</en>"
        );
    }

    #[test]
    fn the_indexer_refuses_a_language_that_is_not_a_two_letter_code() {
        let table = table_of(&[('a', 6)]);
        for bad in ["", "e", "eng", "EN", "e1"] {
            let error = to_ids(&table, "a", bad).expect_err("a bad code");
            assert!(error.contains("language code"), "{bad:?}: {error}");
        }
    }

    #[test]
    fn the_indexer_refuses_a_table_of_the_wrong_size() {
        let error = to_ids(&[0u8; 16], "a", "en").expect_err("a short table");
        assert!(error.contains("codepoint table"), "{error}");
    }

    #[test]
    fn a_voice_file_splits_into_the_two_style_tensors() {
        // The order is style_ttl then style_dp, and the two are 12,800 and 128 values, so a file
        // read in the wrong order is exactly the right length. Only the values catch it — hence a
        // fixture where each tensor holds a constant of its own.
        let text_values = (net::STYLE * net::STYLE_TOKENS) as usize;
        let duration_values = duration_net::STYLE as usize;
        let mut bytes = Vec::new();
        for _ in 0..text_values {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(0.25).to_le_bytes());
        }
        for _ in 0..duration_values {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(-0.5).to_le_bytes());
        }

        let voice = Voice::read(&bytes).expect("the declared length");
        assert_eq!(voice.text.len(), text_values);
        assert_eq!(voice.duration.len(), duration_values);
        assert!(voice.text.iter().all(|&v| v == 0.25), "style_ttl comes first");
        assert!(voice.duration.iter().all(|&v| v == -0.5), "style_dp comes second");

        // A truncated or padded file is refused rather than read short: the shapes are fixed by
        // the architecture, so a length that disagrees means the wrong file, not a shorter voice.
        assert!(Voice::read(&bytes[..bytes.len() - 2]).is_err());
        assert!(Voice::read(&[]).is_err());
    }

    /// Records what the pipeline asked of each stage, and answers with fixed shapes.
    struct Recording {
        seconds: f32,
        chars: u32,
        frames: u32,
        sampler_calls: usize,
        latents: Vec<Vec<f32>>,
        vocoded_frames: Vec<u32>,
    }

    impl Stages for Recording {
        fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
            // Two lanes over `chars + 1` positions, the sentence token first.
            assert_eq!(lanes.len() % 2, 0);
            let positions = lanes.len() / 2;
            assert_eq!(positions as u32, self.chars + 1);
            assert_eq!(
                lanes[0],
                (duration_net::SENTENCE_TOKEN % super::super::super::nets::EMBED_LANE) as f32
            );
            assert_eq!(style.len(), 128);
            Ok(self.seconds.ln())
        }

        fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
            // No sentence token on this side.
            assert_eq!(lanes.len() / 2, self.chars as usize);
            assert_eq!(style.len(), 256 * 50);
            Ok(vec![0.25; 256 * self.chars as usize])
        }

        fn sampler(
            &mut self,
            latent: &[f32],
            text: &[f32],
            keys: &[f32],
            style: &[f32],
            shifts: &[f32],
            query_angles: &[f32],
            key_angles: &[f32],
        ) -> Result<Vec<f32>, String> {
            assert_eq!(latent.len(), net::LATENT as usize * self.frames as usize);
            assert_eq!(text.len(), net::TEXT as usize * self.chars as usize);
            assert_eq!(keys.len(), net::STYLE as usize * net::MAIN_BLOCKS * 50);
            assert_eq!(style.len(), net::STYLE as usize * 50);
            assert_eq!(shifts.len(), net::CHANNELS as usize * net::MAIN_BLOCKS);
            assert_eq!(query_angles.len(), 64 * self.frames as usize);
            assert_eq!(key_angles.len(), 64 * self.chars as usize);
            self.sampler_calls += 1;
            self.latents.push(latent.to_vec());
            // A velocity of zero, so the latent must come back unchanged and any accidental
            // scaling of it in `step` shows up.
            Ok(vec![0.0; latent.len()])
        }

        fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
            assert_eq!(latent.len(), net::LATENT as usize * frames as usize);
            self.vocoded_frames.push(frames);
            Ok(vec![0.5; frames as usize * 3072])
        }
    }

    /// A codepoint table mapping the ASCII letters to themselves, plus the three punctuation
    /// marks the language tag is spelled with, and nothing else.
    fn letters() -> Vec<u8> {
        let mut table = vec![0u8; INDEXER_ENTRIES * 2];
        for entry in 0..INDEXER_ENTRIES {
            let token = if (b'a' as usize..=b'z' as usize).contains(&entry) {
                (entry - b'a' as usize + 1) as i16
            } else if entry < 128 && [b'<', b'>', b'/'].contains(&(entry as u8)) {
                27
            } else {
                -1
            };
            table[entry * 2..entry * 2 + 2].copy_from_slice(&token.to_le_bytes());
        }
        table
    }

    fn conditioning() -> Conditioning {
        Conditioning {
            theta: vec![1.0; net::FREQUENCIES as usize],
            shifts: vec![vec![0.0; net::CHANNELS as usize * net::MAIN_BLOCKS]; STEPS as usize],
            conditional_keys: vec![0.0; net::STYLE as usize * net::MAIN_BLOCKS * 50],
            unconditional_keys: vec![0.0; net::STYLE as usize * net::MAIN_BLOCKS * 50],
            text_token: vec![0.0; net::TEXT as usize],
            unconditional_style: vec![0.0; net::STYLE as usize * 50],
        }
    }

    #[test]
    fn the_pipeline_runs_the_four_nets_in_order_at_the_predicted_length() {
        // "hello" is five mapped letters, and `<en>` and `</en>` are nine more. At one second the
        // duration predictor's answer becomes ceil(44100 / 1.05 / 3072) = 14 frames, and the
        // vocoder emits 3072 samples a frame.
        let mut stages =
            Recording { seconds: 1.0, chars: 14, frames: 14, sampler_calls: 0, latents: Vec::new(), vocoded_frames: Vec::new() };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        let samples = synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "hello",
            "en",
            &|count| vec![0.0; count],
        )
        .expect("synthesises");
        assert_eq!(samples.len(), 14 * 3072);
        assert_eq!(stages.vocoded_frames, vec![14]);
        // Two guidance branches every step, which is the sampler's real cost.
        assert_eq!(stages.sampler_calls, STEPS as usize * 2);
    }

    #[test]
    fn a_zero_velocity_leaves_the_latent_where_the_noise_put_it() {
        // The stub returns a velocity of zero, so every step is the identity and all 16 must see
        // the same latent. A `step` that scaled or reordered would show here rather than as
        // quiet noise on a device.
        let mut stages =
            Recording { seconds: 1.0, chars: 14, frames: 14, sampler_calls: 0, latents: Vec::new(), vocoded_frames: Vec::new() };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "hello",
            "en",
            &|count| (0..count).map(|i| i as f32 * 0.001).collect(),
        )
        .expect("synthesises");
        let first = stages.latents.first().expect("a latent").clone();
        for (i, latent) in stages.latents.iter().enumerate() {
            assert_eq!(latent, &first, "step {i} moved the latent");
        }
    }

    #[test]
    fn text_with_nothing_in_the_vocabulary_is_refused() {
        // Rather than synthesising silence, or a plan over zero characters that the nets refuse
        // with a message about frames. The language tag maps in `letters()`, so this also covers
        // the tag alone not being mistaken for content.
        let mut stages =
            Recording { seconds: 1.0, chars: 0, frames: 1, sampler_calls: 0, latents: Vec::new(), vocoded_frames: Vec::new() };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        let error = synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "12345",
            "en",
            &|count| vec![0.0; count],
        )
        .expect_err("no vocabulary");
        assert!(error.contains("vocabulary"), "{error}");
    }

    #[test]
    fn the_rotary_table_is_normalised_by_its_own_length() {
        // Two positions and one frequency of 1: position 0 is angle 0 and position 1 is angle
        // 0.5, because the divisor is the length rather than a constant. Cosines first.
        let table = rotary_angles(&vec![1.0; net::FREQUENCIES as usize], 2).expect("angles");
        assert_eq!(table.len(), 64 * 2);
        assert!((table[0] - 1.0).abs() < 1e-6);
        assert!((table[1] - 0.5f32.cos()).abs() < 1e-6);
        // Sines start at channel 32.
        assert!((table[32 * 2]).abs() < 1e-6);
        assert!((table[32 * 2 + 1] - 0.5f32.sin()).abs() < 1e-6);
    }

    #[test]
    fn mish_matches_its_definition_and_survives_a_large_input() {
        // `x * tanh(softplus(x))`. At 0 it is 0, at 1 it is 0.86509836, and at 100 it is 100 —
        // the last only because `softplus` is not computed as `ln(1 + e^100)`, which is inf.
        assert!((mish(0.0)).abs() < 1e-6);
        assert!((mish(1.0) - 0.865_098_4).abs() < 1e-5);
        assert!((mish(100.0) - 100.0).abs() < 1e-3);
        assert!(mish(100.0).is_finite());
        // And it is not ReLU: a small negative input is negative, not zero.
        assert!(mish(-1.0) < -0.3 && mish(-1.0) > -0.31);
    }

    #[test]
    fn a_linear_reads_its_weight_row_major() {
        // `[2, 3]` over three inputs. A column-major read would give (14, 32) here, which is
        // the same magnitude and the wrong answer.
        let weight = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        let got = linear(&weight, &[10.0, 20.0], &[1.0, 1.0, 1.0]);
        assert_eq!(got, vec![16.0, 35.0]);
    }

    #[test]
    fn the_euler_step_is_guidance_four_over_the_step_count() {
        // `x + (4c - 3u) / total`. With c = u the guidance cancels to plain c, which is the
        // check that the two coefficients differ by exactly one.
        let got = step(&[0.0, 1.0], &[2.0, 2.0], &[2.0, 2.0], 4).expect("steps");
        assert_eq!(got, vec![0.5, 1.5]);
        // And with them apart, the conditional is extrapolated away from the unconditional.
        let got = step(&[0.0], &[1.0], &[0.0], 1).expect("steps");
        assert_eq!(got, vec![4.0]);
    }

    #[test]
    fn the_euler_step_refuses_mismatched_branches() {
        let error = step(&[0.0, 0.0], &[0.0], &[0.0, 0.0], 1).expect_err("a short branch");
        assert!(error.contains("conditional"), "{error}");
        let error = step(&[0.0], &[0.0], &[0.0], 0).expect_err("no steps");
        assert!(error.contains("no steps"), "{error}");
    }
}
