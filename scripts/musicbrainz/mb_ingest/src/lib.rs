//! MusicBrainz offline pack: build one from an mbdump full export, and read one
//! back.
//!
//! The format spec lives on `pack` (the producer is the source of truth); `reader`
//! is the consumer the server uses and must stay in sync with it.
//!
//! ```no_run
//! use mb_ingest::{build, copy::Input, reader::MbPack};
//! let input = Input::detect(std::path::Path::new("mbdump.tar.bz2"));
//! let mut out = std::fs::File::create("musicbrainz.pack")?;
//! let stats = build::build(&input, &mut out, &build::BuildOptions::default(),
//!                          &mut std::io::stderr())?;
//! print!("{}", stats.report());
//! # Ok::<(), std::io::Error>(())
//! ```

pub mod build;
pub mod copy;
pub mod fixture;
pub mod pack;
pub mod reader;

pub use pack::{format_mbid, parse_mbid, Mbid};
pub use reader::{MbPack, PackError};
