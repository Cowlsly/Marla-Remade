//! Deterministic parallel map over contiguous chunks of a slice.
//!
//! Chunks are claimed dynamically (so a heavy chunk doesn't stall a core) but
//! each chunk's result is stored at its own index, so merging the results in
//! index order gives output that does not depend on thread scheduling. Every
//! parallel stage in this crate is built on that property — it is what makes the
//! tool reproducible, which the C++ generator it replaces was not.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

pub fn threads() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

/// Apply `f(chunk_start, chunk)` to every chunk of `chunk_len` items, returning
/// the results in chunk order.
pub fn map_chunks<T, R, F>(items: &[T], chunk_len: usize, f: F) -> Vec<R>
where
    T: Sync,
    R: Send,
    F: Fn(usize, &[T]) -> R + Sync,
{
    let chunk_len = chunk_len.max(1);
    let n_chunks = items.len().div_ceil(chunk_len);
    if n_chunks == 0 {
        return Vec::new();
    }
    let slots: Mutex<Vec<Option<R>>> = Mutex::new((0..n_chunks).map(|_| None).collect());
    let next = AtomicUsize::new(0);
    let n_threads = threads().min(n_chunks);

    std::thread::scope(|scope| {
        for _ in 0..n_threads {
            scope.spawn(|| loop {
                let chunk = next.fetch_add(1, Ordering::Relaxed);
                if chunk >= n_chunks {
                    break;
                }
                let start = chunk * chunk_len;
                let end = ((chunk + 1) * chunk_len).min(items.len());
                let result = f(start, &items[start..end]);
                slots.lock().expect("chunk slot mutex")[chunk] = Some(result);
            });
        }
    });

    slots
        .into_inner()
        .expect("chunk slot mutex")
        .into_iter()
        .flatten()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn results_come_back_in_chunk_order() {
        let items: Vec<usize> = (0..1000).collect();
        let out = map_chunks(&items, 7, |start, chunk| (start, chunk.iter().sum::<usize>()));
        assert_eq!(out.len(), 1000usize.div_ceil(7));
        assert!(out.windows(2).all(|w| w[0].0 < w[1].0));
        assert_eq!(out.iter().map(|c| c.1).sum::<usize>(), items.iter().sum());
    }

    #[test]
    fn empty_input_produces_no_chunks() {
        let out = map_chunks::<usize, usize, _>(&[], 8, |_, _| unreachable!());
        assert!(out.is_empty());
    }
}
