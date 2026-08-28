//! A [`Net`] that re-records itself when the utterance changes length.
//!
//! Every Supertonic net is utterance-shaped: the duration predictor and the text encoder run at
//! the character count, the sampler at the latent length and the character count, the vocoder at
//! the latent length. A [`Plan`] is one command buffer recorded at fixed shapes, so a plan is only
//! ever right for one utterance, and [`Net::rebuild`] is how the next one gets its own.
//!
//! Doing that by hand needs two things kept in step per net — the table to rebuild from, and the
//! shape it is currently recorded at — and getting the second wrong is silent: a net left at the
//! previous sentence's length runs this sentence's data through it and returns the wrong number of
//! frames, or the right number computed over positions the arena happened to hold. So they live
//! together here, and a caller asks for the net *at a shape* rather than asking for the net.

use std::sync::Arc;

use crate::nets::Plan;
use crate::preprocess::RESCALE_ONLY;
use crate::weights::{Offsets, Weights};

use super::context::Context;
use super::run::Net;

/// A net, the table to rebuild its plan from, and the shape it is recorded at.
///
/// `S` is whatever identifies a shape for this net — `u32` for the three that depend only on a
/// length, `(u32, u32)` for the sampler, which depends on the frame count and the character count
/// independently. It is compared, not interpreted, so a net cannot be reshaped to a shape it is
/// already at and cannot be left at one it is not.
pub struct Reshaped<S> {
    net: Net,
    offsets: Offsets,
    at: S,
    plan: fn(&Offsets, S) -> Result<Plan, String>,
}

impl<S: Copy + PartialEq> Reshaped<S> {
    /// Upload `weights`, record at `shape`, and keep only the table.
    ///
    /// The `Weights` is borrowed rather than held: [`Net::new`] copies the blob to device memory
    /// and [`Weights::offsets`] keeps the few kilobytes a rebuild actually reads, so the caller can
    /// drop 127 MB of host allocation the moment this returns. That is the whole reason rebuilding
    /// beats constructing a second net per utterance.
    ///
    /// [`RESCALE_ONLY`] because no bitmap ever reaches these nets — every input is a tensor a
    /// previous stage computed, handed over by [`Net::infer_raw_many`], so the preprocessing the
    /// vision nets need has nothing to do here.
    pub fn new(
        context: Arc<Context>,
        weights: &Weights,
        shape: S,
        plan: fn(&Offsets, S) -> Result<Plan, String>,
    ) -> Result<Reshaped<S>, String> {
        let offsets = weights.offsets();
        let net = Net::new(context, plan(&offsets, shape)?, weights, RESCALE_ONLY)?;
        Ok(Reshaped { net, offsets, at: shape, plan })
    }

    /// The net recorded at `shape`, re-recording it first if it is not already.
    ///
    /// Cheap when the shape is unchanged, which is the common case and the one that matters: the
    /// sampler is called `2 * STEPS` times at one shape for a single sentence, and the vocoder once
    /// per chunk at one shape, so re-recording per call would build thirty-odd command buffers
    /// where one is needed.
    pub fn at(&mut self, shape: S) -> Result<&mut Net, String> {
        if shape != self.at {
            self.net.rebuild((self.plan)(&self.offsets, shape)?)?;
            self.at = shape;
        }
        Ok(&mut self.net)
    }
}
