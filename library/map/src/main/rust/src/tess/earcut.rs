//! Ear-clipping polygon triangulation with hole support — a port of Mapbox's
//! `earcut` (2.2.x), the algorithm every vector-tile renderer uses for fills.
//!
//! # Why integer coordinates
//!
//! The reference implementation works in floating point and therefore needs
//! epsilons in its orientation tests. This port takes MVT tile coordinates, which
//! are **integers** — extent 4096, overspilling a little past the tile edge where
//! geometry was clipped — so every predicate here ([`area`], [`point_in_triangle`],
//! [`intersects`]) is an exact `i64` cross product. A degenerate case is decided the
//! same way every time on every device, which is what makes a golden-image
//! comparison in CI meaningful at all.
//!
//! # Shape of the algorithm
//!
//! Rings become a doubly linked list, held in a flat `Vec<Node>` addressed by index
//! rather than by pointer — no `Rc<RefCell<..>>`, no unsafe. Holes are joined to the
//! outer ring by a bridge so the whole polygon becomes one ring, then ears —
//! triangles of three consecutive vertices containing no other vertex — are clipped
//! one at a time. Above a size threshold the vertices are additionally indexed on a
//! **Z-order curve**, so the "does this ear contain another vertex" test visits only
//! spatially nearby vertices: a coastline ring in one tile runs to thousands of
//! vertices and the unhashed test is quadratic.
//!
//! Two fallbacks handle self-intersecting input, which real basemap data contains:
//! [`cure_local_intersections`] and [`split_earcut`]. Without them a bad ring
//! silently loses its fill.

/// A vertex in the ring and in the Z-order list. `usize::MAX` is the null link.
#[derive(Clone, Copy)]
struct Node {
    /// Index of this vertex's x in the caller's flat coordinate array.
    i: usize,
    x: i32,
    y: i32,
    prev: usize,
    next: usize,
    z: i32,
    prev_z: usize,
    next_z: usize,
    steiner: bool,
}

const NIL: usize = usize::MAX;

/// The linked-list arena. Indices into `nodes` are the "pointers".
struct Ring {
    nodes: Vec<Node>,
}

impl Ring {
    fn new(capacity: usize) -> Ring {
        Ring { nodes: Vec::with_capacity(capacity) }
    }

    fn insert(&mut self, i: usize, x: i32, y: i32, last: usize) -> usize {
        let at = self.nodes.len();
        self.nodes.push(Node {
            i,
            x,
            y,
            prev: NIL,
            next: NIL,
            z: 0,
            prev_z: NIL,
            next_z: NIL,
            steiner: false,
        });
        if last == NIL {
            self.nodes[at].prev = at;
            self.nodes[at].next = at;
        } else {
            let next = self.nodes[last].next;
            self.nodes[at].next = next;
            self.nodes[at].prev = last;
            self.nodes[next].prev = at;
            self.nodes[last].next = at;
        }
        at
    }

    fn remove(&mut self, p: usize) {
        let (prev, next) = (self.nodes[p].prev, self.nodes[p].next);
        self.nodes[next].prev = prev;
        self.nodes[prev].next = next;
        let (prev_z, next_z) = (self.nodes[p].prev_z, self.nodes[p].next_z);
        if prev_z != NIL {
            self.nodes[prev_z].next_z = next_z;
        }
        if next_z != NIL {
            self.nodes[next_z].prev_z = prev_z;
        }
    }

    #[inline]
    fn x(&self, p: usize) -> i32 {
        self.nodes[p].x
    }
    #[inline]
    fn y(&self, p: usize) -> i32 {
        self.nodes[p].y
    }
    #[inline]
    fn next(&self, p: usize) -> usize {
        self.nodes[p].next
    }
    #[inline]
    fn prev(&self, p: usize) -> usize {
        self.nodes[p].prev
    }

    /// Twice the signed area of the triangle, exactly.
    #[inline]
    fn area(&self, p: usize, q: usize, r: usize) -> i64 {
        let (px, py) = (self.nodes[p].x as i64, self.nodes[p].y as i64);
        let (qx, qy) = (self.nodes[q].x as i64, self.nodes[q].y as i64);
        let (rx, ry) = (self.nodes[r].x as i64, self.nodes[r].y as i64);
        (qy - py) * (rx - qx) - (qx - px) * (ry - qy)
    }

    #[inline]
    fn equals(&self, p: usize, q: usize) -> bool {
        self.nodes[p].x == self.nodes[q].x && self.nodes[p].y == self.nodes[q].y
    }
}

/// Triangulate a polygon.
///
/// `coords` is a flat `[x0, y0, x1, y1, ...]` of the outer ring followed by each
/// hole, with no repeated closing vertex. `hole_starts` gives the **vertex** index
/// (not coordinate index) each hole begins at. Returns triangle vertex indices,
/// three per triangle.
pub fn triangulate(coords: &[i32], hole_starts: &[usize]) -> Vec<u32> {
    let mut out = Vec::new();
    let outer_end = if hole_starts.is_empty() { coords.len() } else { hole_starts[0] * 2 };
    let mut ring = Ring::new(coords.len() / 2 + hole_starts.len() * 2);

    let mut outer = match linked_list(&mut ring, coords, 0, outer_end, true) {
        Some(p) => p,
        None => return out,
    };
    if ring.next(outer) == ring.prev(outer) {
        return out;
    }

    if !hole_starts.is_empty() {
        outer = eliminate_holes(&mut ring, coords, hole_starts, outer);
    }

    let (mut min_x, mut min_y, mut inv_size) = (0i32, 0i32, 0f64);
    // The Z-order index costs a sort, so it only pays for itself on rings big enough
    // for the quadratic ear test to hurt. 80 vertices is the reference's threshold.
    if coords.len() > 80 * 2 {
        min_x = coords[0];
        min_y = coords[1];
        let mut max_x = min_x;
        let mut max_y = min_y;
        let mut i = 2;
        while i < outer_end {
            let (x, y) = (coords[i], coords[i + 1]);
            if x < min_x {
                min_x = x;
            }
            if y < min_y {
                min_y = y;
            }
            if x > max_x {
                max_x = x;
            }
            if y > max_y {
                max_y = y;
            }
            i += 2;
        }
        let span = (max_x - min_x).max(max_y - min_y);
        inv_size = if span != 0 { 32767.0 / span as f64 } else { 0.0 };
    }

    earcut_linked(&mut ring, outer, &mut out, min_x, min_y, inv_size, 0);
    out
}

/// Build a circular doubly linked list from a ring, in the requested winding.
fn linked_list(
    ring: &mut Ring,
    coords: &[i32],
    start: usize,
    end: usize,
    clockwise: bool,
) -> Option<usize> {
    if end <= start {
        return None;
    }
    let mut last = NIL;
    if clockwise == (signed_area(coords, start, end) > 0) {
        let mut i = start;
        while i < end {
            last = ring.insert(i, coords[i], coords[i + 1], last);
            i += 2;
        }
    } else {
        let mut i = end;
        while i > start {
            i -= 2;
            last = ring.insert(i, coords[i], coords[i + 1], last);
        }
    }
    if last != NIL {
        let next = ring.next(last);
        if ring.equals(last, next) {
            ring.remove(last);
            last = next;
        }
    }
    if last == NIL {
        None
    } else {
        Some(last)
    }
}

/// Drop colinear and duplicate vertices; they can never form a valid ear.
fn filter_points(ring: &mut Ring, start: usize, end_in: usize) -> usize {
    let mut end = if end_in == NIL { start } else { end_in };
    let mut p = start;
    loop {
        let mut again = false;
        let (prev, next) = (ring.prev(p), ring.next(p));
        if !ring.nodes[p].steiner && (ring.equals(p, next) || ring.area(prev, p, next) == 0) {
            ring.remove(p);
            p = prev;
            end = p;
            if p == ring.next(p) {
                break;
            }
            again = true;
        } else {
            p = ring.next(p);
        }
        if !again && p == end {
            break;
        }
    }
    end
}

/// The main loop: clip ears off, falling back on two repair passes.
fn earcut_linked(
    ring: &mut Ring,
    ear_in: usize,
    out: &mut Vec<u32>,
    min_x: i32,
    min_y: i32,
    inv_size: f64,
    pass: u8,
) {
    if ear_in == NIL {
        return;
    }
    let mut ear = ear_in;
    if pass == 0 && inv_size > 0.0 {
        index_curve(ring, ear, min_x, min_y, inv_size);
    }

    let mut stop = ear;
    while ring.prev(ear) != ring.next(ear) {
        let prev = ring.prev(ear);
        let next = ring.next(ear);

        let is_ear = if inv_size > 0.0 {
            is_ear_hashed(ring, ear, min_x, min_y, inv_size)
        } else {
            is_ear(ring, ear)
        };
        if is_ear {
            out.push((ring.nodes[prev].i / 2) as u32);
            out.push((ring.nodes[ear].i / 2) as u32);
            out.push((ring.nodes[next].i / 2) as u32);
            ring.remove(ear);
            // Skipping the next vertex leads to fewer sliver triangles.
            ear = ring.next(next);
            stop = ring.next(next);
            continue;
        }

        ear = next;
        if ear != stop {
            continue;
        }

        // No ear was found in a full loop: the ring is not simple.
        match pass {
            0 => {
                let filtered = filter_points(ring, ear, NIL);
                earcut_linked(ring, filtered, out, min_x, min_y, inv_size, 1);
            }
            1 => {
                let filtered = filter_points(ring, ear, NIL);
                let cured = cure_local_intersections(ring, filtered, out);
                earcut_linked(ring, cured, out, min_x, min_y, inv_size, 2);
            }
            _ => split_earcut(ring, ear, out, min_x, min_y, inv_size),
        }
        break;
    }
}

/// Is the triangle `prev, ear, next` an ear?
fn is_ear(ring: &Ring, ear: usize) -> bool {
    let a = ring.prev(ear);
    let b = ear;
    let c = ring.next(ear);
    // Reflex vertices cannot be ears.
    if ring.area(a, b, c) >= 0 {
        return false;
    }

    let mut p = ring.next(c);
    while p != a {
        if point_in_triangle(ring, a, b, c, p) && ring.area(ring.prev(p), p, ring.next(p)) >= 0 {
            return false;
        }
        p = ring.next(p);
    }
    true
}

/// [`is_ear`], but visiting only vertices whose Z-order code falls inside the
/// candidate triangle's Z range — what turns the quadratic scan into something a
/// coastline tile can afford.
fn is_ear_hashed(ring: &Ring, ear: usize, min_x: i32, min_y: i32, inv_size: f64) -> bool {
    let a = ring.prev(ear);
    let b = ear;
    let c = ring.next(ear);
    if ring.area(a, b, c) >= 0 {
        return false;
    }

    let min_tx = ring.x(a).min(ring.x(b)).min(ring.x(c));
    let min_ty = ring.y(a).min(ring.y(b)).min(ring.y(c));
    let max_tx = ring.x(a).max(ring.x(b)).max(ring.x(c));
    let max_ty = ring.y(a).max(ring.y(b)).max(ring.y(c));
    let min_z = z_order(min_tx, min_ty, min_x, min_y, inv_size);
    let max_z = z_order(max_tx, max_ty, min_x, min_y, inv_size);

    let inside = |p: usize| -> bool {
        ring.x(p) >= min_tx
            && ring.x(p) <= max_tx
            && ring.y(p) >= min_ty
            && ring.y(p) <= max_ty
            && p != a
            && p != c
            && point_in_triangle(ring, a, b, c, p)
            && ring.area(ring.prev(p), p, ring.next(p)) >= 0
    };

    let mut p = ring.nodes[ear].prev_z;
    let mut n = ring.nodes[ear].next_z;
    // Walk outward in both directions while still inside the Z range.
    while p != NIL && ring.nodes[p].z >= min_z && n != NIL && ring.nodes[n].z <= max_z {
        if inside(p) {
            return false;
        }
        p = ring.nodes[p].prev_z;
        if inside(n) {
            return false;
        }
        n = ring.nodes[n].next_z;
    }
    while p != NIL && ring.nodes[p].z >= min_z {
        if inside(p) {
            return false;
        }
        p = ring.nodes[p].prev_z;
    }
    while n != NIL && ring.nodes[n].z <= max_z {
        if inside(n) {
            return false;
        }
        n = ring.nodes[n].next_z;
    }
    true
}

/// Cut off a self-intersecting sliver so the rest of the ring can be clipped.
fn cure_local_intersections(ring: &mut Ring, start_in: usize, out: &mut Vec<u32>) -> usize {
    let mut start = start_in;
    let mut p = start;
    loop {
        let a = ring.prev(p);
        let next = ring.next(p);
        let b = ring.next(next);
        if !ring.equals(a, b)
            && intersects(ring, a, p, next, b)
            && locally_inside(ring, a, b)
            && locally_inside(ring, b, a)
        {
            out.push((ring.nodes[a].i / 2) as u32);
            out.push((ring.nodes[p].i / 2) as u32);
            out.push((ring.nodes[b].i / 2) as u32);
            ring.remove(p);
            ring.remove(next);
            p = b;
            start = b;
        }
        p = ring.next(p);
        if p == start {
            break;
        }
    }
    filter_points(ring, p, NIL)
}

/// Split the polygon along a valid diagonal and triangulate the halves.
fn split_earcut(
    ring: &mut Ring,
    start: usize,
    out: &mut Vec<u32>,
    min_x: i32,
    min_y: i32,
    inv_size: f64,
) {
    let mut a = start;
    loop {
        let mut b = ring.next(ring.next(a));
        while b != ring.prev(a) {
            if ring.nodes[a].i != ring.nodes[b].i && is_valid_diagonal(ring, a, b) {
                let c = split_polygon(ring, a, b);
                let next_a = ring.next(a);
                let filtered_a = filter_points(ring, a, next_a);
                let next_c = ring.next(c);
                let filtered_c = filter_points(ring, c, next_c);
                earcut_linked(ring, filtered_a, out, min_x, min_y, inv_size, 0);
                earcut_linked(ring, filtered_c, out, min_x, min_y, inv_size, 0);
                return;
            }
            b = ring.next(b);
        }
        a = ring.next(a);
        if a == start {
            break;
        }
    }
}

/// Bridge every hole into the outer ring, so the polygon becomes one ring.
fn eliminate_holes(
    ring: &mut Ring,
    coords: &[i32],
    hole_starts: &[usize],
    outer_in: usize,
) -> usize {
    let mut queue: Vec<usize> = Vec::with_capacity(hole_starts.len());
    for (h, &start) in hole_starts.iter().enumerate() {
        let start = start * 2;
        let end = if h < hole_starts.len() - 1 { hole_starts[h + 1] * 2 } else { coords.len() };
        if let Some(list) = linked_list(ring, coords, start, end, false) {
            if list == ring.next(list) {
                ring.nodes[list].steiner = true;
            }
            queue.push(leftmost(ring, list));
        }
    }
    queue.sort_by_key(|&p| ring.x(p));

    let mut outer = outer_in;
    for hole in queue {
        outer = eliminate_hole(ring, hole, outer);
    }
    outer
}

fn eliminate_hole(ring: &mut Ring, hole: usize, outer: usize) -> usize {
    let bridge = match find_hole_bridge(ring, hole, outer) {
        Some(b) => b,
        None => return outer,
    };
    let bridge_reverse = split_polygon(ring, bridge, hole);
    // Filter colinear points around both cuts.
    let next_reverse = ring.next(bridge_reverse);
    filter_points(ring, bridge_reverse, next_reverse);
    let next_bridge = ring.next(bridge);
    filter_points(ring, bridge, next_bridge)
}

/// A visible vertex of the outer ring to bridge `hole` to: cast a ray left from the
/// hole's leftmost vertex and take the nearest edge it hits.
///
/// The ray intersection is computed in `f64`, as the reference does. It is a distance
/// comparison rather than an orientation predicate, and integer truncation here would
/// occasionally accept an edge whose true intersection lies past the hole —
/// producing a bridge that crosses the polygon.
fn find_hole_bridge(ring: &Ring, hole: usize, outer: usize) -> Option<usize> {
    let hx = ring.x(hole);
    let hy = ring.y(hole);
    let mut qx = f64::NEG_INFINITY;
    let mut m = NIL;

    let mut p = outer;
    loop {
        let next = ring.next(p);
        if hy <= ring.y(p) && hy >= ring.y(next) && ring.y(next) != ring.y(p) {
            let x = ring.x(p) as f64
                + (hy - ring.y(p)) as f64 * (ring.x(next) - ring.x(p)) as f64
                    / (ring.y(next) - ring.y(p)) as f64;
            if x <= hx as f64 && x > qx {
                qx = x;
                m = if ring.x(p) < ring.x(next) { p } else { next };
                if x == hx as f64 {
                    // The hole touches the outline.
                    return Some(m);
                }
            }
        }
        p = next;
        if p == outer {
            break;
        }
    }
    if m == NIL {
        return None;
    }

    // Look for a better bridge: among the reflex vertices inside the triangle
    // (hole, ray hit, m), take the one at the smallest angle to the ray.
    let stop = m;
    let mx = ring.x(m);
    let my = ring.y(m);
    let mut tan_min = f64::INFINITY;
    let mut best = m;
    let mut p = m;
    loop {
        let in_triangle = hx >= ring.x(p)
            && ring.x(p) >= mx
            && hx != ring.x(p)
            && point_in_triangle_f(
                if hy < my { hx as f64 } else { qx },
                hy as f64,
                mx as f64,
                my as f64,
                if hy < my { qx } else { hx as f64 },
                hy as f64,
                ring.x(p) as f64,
                ring.y(p) as f64,
            );
        if in_triangle {
            let tan = ((hy - ring.y(p)) as f64).abs() / (hx - ring.x(p)) as f64;
            let better = locally_inside(ring, p, hole)
                && (tan < tan_min
                    || (tan == tan_min
                        && (ring.x(p) > ring.x(best)
                            || (ring.x(p) == ring.x(best) && sector_contains_sector(ring, best, p)))));
            if better {
                best = p;
                tan_min = tan;
            }
        }
        p = ring.next(p);
        if p == stop {
            break;
        }
    }
    Some(best)
}

/// Does the reflex sector at `m` contain the sector at `p`?
fn sector_contains_sector(ring: &Ring, m: usize, p: usize) -> bool {
    ring.area(ring.prev(m), m, ring.prev(p)) < 0 && ring.area(ring.next(p), m, ring.next(m)) < 0
}

/// Build the Z-order linked list for the ring.
fn index_curve(ring: &mut Ring, start: usize, min_x: i32, min_y: i32, inv_size: f64) {
    let mut p = start;
    loop {
        if ring.nodes[p].z == 0 {
            ring.nodes[p].z = z_order(ring.x(p), ring.y(p), min_x, min_y, inv_size);
        }
        let (prev, next) = (ring.prev(p), ring.next(p));
        ring.nodes[p].prev_z = prev;
        ring.nodes[p].next_z = next;
        p = next;
        if p == start {
            break;
        }
    }
    let prev_z = ring.nodes[p].prev_z;
    ring.nodes[prev_z].next_z = NIL;
    ring.nodes[p].prev_z = NIL;
    sort_linked(ring, p);
}

/// Merge sort the Z-order list in place — the reference's Simon Tatham sort.
fn sort_linked(ring: &mut Ring, list_in: usize) {
    let mut list = list_in;
    let mut in_size = 1usize;
    loop {
        let mut p = list;
        list = NIL;
        let mut tail = NIL;
        let mut num_merges = 0;

        while p != NIL {
            num_merges += 1;
            let mut q = p;
            let mut p_size = 0usize;
            for _ in 0..in_size {
                p_size += 1;
                q = ring.nodes[q].next_z;
                if q == NIL {
                    break;
                }
            }
            let mut q_size = in_size;

            while p_size > 0 || (q_size > 0 && q != NIL) {
                let e;
                if p_size != 0 && (q_size == 0 || q == NIL || ring.nodes[p].z <= ring.nodes[q].z) {
                    e = p;
                    p = ring.nodes[p].next_z;
                    p_size -= 1;
                } else {
                    e = q;
                    q = ring.nodes[q].next_z;
                    q_size -= 1;
                }
                if tail != NIL {
                    ring.nodes[tail].next_z = e;
                } else {
                    list = e;
                }
                ring.nodes[e].prev_z = tail;
                tail = e;
            }
            p = q;
        }
        ring.nodes[tail].next_z = NIL;
        in_size *= 2;
        if num_merges <= 1 {
            break;
        }
    }
}

/// Interleave the low 16 bits of the normalised coordinates: a Z-order code.
///
/// `inv_size` already carries the `32767 / span` scale (see [`triangulate`]), so the
/// coordinate is only multiplied by it — never scaled by 32767 a second time. Doing that
/// pushed the inputs to ~32767², far past the 15 bits the interleaving masks below assume,
/// so the codes stopped reflecting spatial locality. The sorted Z-list then bore no relation
/// to position, [`is_ear_hashed`] walked out of range before reaching the points that
/// actually sat inside a candidate triangle, and it accepted ears that were not ears. The
/// result was overlapping triangles: the ocean polygon covered 0.98 of its tile instead of
/// 0.71, painting over every continent it should have cut out.
///
/// Only polygons past [`triangulate`]'s 80-vertex threshold take this path, which is why
/// small test cases passed and every real tile was wrong.
///
/// **No test currently catches this.** Reintroducing the second scale leaves every test in
/// this module green, including
/// `a_ring_large_enough_to_use_the_z_order_index_still_triangulates_exactly`, which looks
/// like it would cover it: that ring is a circle, and on a convex ring every candidate ear
/// is a real ear, so a corrupted Z-order cannot produce the false acceptance the bug needs.
/// Catching it requires a concave ring past the threshold. Until one exists, this line rests
/// on the reasoning above and not on CI, so do not treat a green suite as licence to
/// simplify it.
fn z_order(x_in: i32, y_in: i32, min_x: i32, min_y: i32, inv_size: f64) -> i32 {
    let mut x = ((x_in - min_x) as f64 * inv_size) as i32;
    let mut y = ((y_in - min_y) as f64 * inv_size) as i32;

    x = (x | (x << 8)) & 0x00FF00FF;
    x = (x | (x << 4)) & 0x0F0F0F0F;
    x = (x | (x << 2)) & 0x33333333;
    x = (x | (x << 1)) & 0x55555555;

    y = (y | (y << 8)) & 0x00FF00FF;
    y = (y | (y << 4)) & 0x0F0F0F0F;
    y = (y | (y << 2)) & 0x33333333;
    y = (y | (y << 1)) & 0x55555555;

    x | (y << 1)
}

/// The leftmost node of a ring, where a hole bridge starts.
fn leftmost(ring: &Ring, start: usize) -> usize {
    let mut p = start;
    let mut best = start;
    loop {
        if ring.x(p) < ring.x(best) || (ring.x(p) == ring.x(best) && ring.y(p) < ring.y(best)) {
            best = p;
        }
        p = ring.next(p);
        if p == start {
            break;
        }
    }
    best
}

/// Exact: is `p` inside the triangle `a, b, c`?
fn point_in_triangle(ring: &Ring, a: usize, b: usize, c: usize, p: usize) -> bool {
    let (px, py) = (ring.x(p) as i64, ring.y(p) as i64);
    let (ax, ay) = (ring.x(a) as i64 - px, ring.y(a) as i64 - py);
    let (bx, by) = (ring.x(b) as i64 - px, ring.y(b) as i64 - py);
    let (cx, cy) = (ring.x(c) as i64 - px, ring.y(c) as i64 - py);
    cx * ay - ax * cy >= 0 && ax * by - bx * ay >= 0 && bx * cy - cx * by >= 0
}

/// [`point_in_triangle`] for the hole-bridge search, which works in `f64`.
#[allow(clippy::too_many_arguments)]
fn point_in_triangle_f(
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    cx: f64,
    cy: f64,
    px: f64,
    py: f64,
) -> bool {
    (cx - px) * (ay - py) - (ax - px) * (cy - py) >= 0.0
        && (ax - px) * (by - py) - (bx - px) * (ay - py) >= 0.0
        && (bx - px) * (cy - py) - (cx - px) * (by - py) >= 0.0
}

/// Is `a-b` a valid diagonal: inside the polygon, crossing nothing?
fn is_valid_diagonal(ring: &Ring, a: usize, b: usize) -> bool {
    let (an, ap) = (ring.next(a), ring.prev(a));
    ring.nodes[an].i != ring.nodes[b].i
        && ring.nodes[ap].i != ring.nodes[b].i
        && !intersects_polygon(ring, a, b)
        && ((locally_inside(ring, a, b)
            && locally_inside(ring, b, a)
            && middle_inside(ring, a, b)
            && (ring.area(ring.prev(a), a, ring.prev(b)) != 0
                || ring.area(a, ring.prev(b), b) != 0))
            // The zero-length special case.
            || (ring.equals(a, b)
                && ring.area(ring.prev(a), a, ring.next(a)) > 0
                && ring.area(ring.prev(b), b, ring.next(b)) > 0))
}

/// Do the segments `p1-q1` and `p2-q2` intersect?
fn intersects(ring: &Ring, p1: usize, q1: usize, p2: usize, q2: usize) -> bool {
    let o1 = ring.area(p1, q1, p2).signum();
    let o2 = ring.area(p1, q1, q2).signum();
    let o3 = ring.area(p2, q2, p1).signum();
    let o4 = ring.area(p2, q2, q1).signum();

    if o1 != o2 && o3 != o4 {
        return true;
    }
    // Collinear and overlapping.
    (o1 == 0 && on_segment(ring, p1, p2, q1))
        || (o2 == 0 && on_segment(ring, p1, q2, q1))
        || (o3 == 0 && on_segment(ring, p2, p1, q2))
        || (o4 == 0 && on_segment(ring, p2, q1, q2))
}

/// For collinear `p, q, r`: does `q` lie on segment `p-r`?
fn on_segment(ring: &Ring, p: usize, q: usize, r: usize) -> bool {
    ring.x(q) <= ring.x(p).max(ring.x(r))
        && ring.x(q) >= ring.x(p).min(ring.x(r))
        && ring.y(q) <= ring.y(p).max(ring.y(r))
        && ring.y(q) >= ring.y(p).min(ring.y(r))
}

/// Does the diagonal `a-b` cross any polygon edge?
fn intersects_polygon(ring: &Ring, a: usize, b: usize) -> bool {
    let mut p = a;
    loop {
        let next = ring.next(p);
        if ring.nodes[p].i != ring.nodes[a].i
            && ring.nodes[next].i != ring.nodes[a].i
            && ring.nodes[p].i != ring.nodes[b].i
            && ring.nodes[next].i != ring.nodes[b].i
            && intersects(ring, p, next, a, b)
        {
            return true;
        }
        p = next;
        if p == a {
            break;
        }
    }
    false
}

/// Does `a-b` leave `a` on the inside of the polygon?
fn locally_inside(ring: &Ring, a: usize, b: usize) -> bool {
    let (prev, next) = (ring.prev(a), ring.next(a));
    if ring.area(prev, a, next) < 0 {
        ring.area(a, b, next) >= 0 && ring.area(a, prev, b) >= 0
    } else {
        ring.area(a, b, prev) < 0 || ring.area(a, next, b) < 0
    }
}

/// Is the midpoint of `a-b` inside the polygon? A crossing count along a horizontal
/// ray.
///
/// Everything is doubled so the midpoint stays integral, and the "is the midpoint
/// left of this edge's crossing" test is a cross-product sign rather than a division —
/// integer division truncates toward zero, which on a negative slope would decide
/// a boundary case differently from the floating-point reference.
///
/// `dy` is deliberately **not** doubled, though `px2`, `py2`, `y2` and the `x(p) * 2`
/// term all are. Doubling it as well scales `px2 * dy` and `x(p) * 2 * dy` but leaves
/// `dx * (py2 - y2)` behind, so the two halves of the intersection no longer share a
/// scale and the crossing lands at the wrong point along the edge. Restoring the
/// apparent symmetry therefore looks like a tidy-up and is a bug. It is caught by
/// exactly one test, `the_midpoint_test_meets_a_slanted_edge_at_its_true_crossing`,
/// which needs a deliberately shallow edge: on an axis-aligned one the offset term is
/// zero and either scaling passes, so ordinary rectangular test shapes cannot see it.
fn middle_inside(ring: &Ring, a: usize, b: usize) -> bool {
    let px2 = ring.x(a) as i64 + ring.x(b) as i64;
    let py2 = ring.y(a) as i64 + ring.y(b) as i64;
    let mut inside = false;
    let mut p = a;
    loop {
        let next = ring.next(p);
        let y2 = ring.y(p) as i64 * 2;
        let next_y2 = ring.y(next) as i64 * 2;
        if (y2 > py2) != (next_y2 > py2) && ring.y(next) != ring.y(p) {
            let dx = ring.x(next) as i64 - ring.x(p) as i64;
            let dy = ring.y(next) as i64 - ring.y(p) as i64;
            // 2 * xIntersect * dy, so the comparison never divides.
            let scaled = dx * (py2 - y2) + ring.x(p) as i64 * 2 * dy;
            let left_of = if dy > 0 { px2 * dy < scaled } else { px2 * dy > scaled };
            if left_of {
                inside = !inside;
            }
        }
        p = next;
        if p == a {
            break;
        }
    }
    inside
}

/// Cut the polygon along `a-b`, producing two rings, and return the node belonging
/// to the second one.
fn split_polygon(ring: &mut Ring, a: usize, b: usize) -> usize {
    let (ai, ax, ay) = (ring.nodes[a].i, ring.nodes[a].x, ring.nodes[a].y);
    let (bi, bx, by) = (ring.nodes[b].i, ring.nodes[b].x, ring.nodes[b].y);
    let a2 = ring.nodes.len();
    ring.nodes.push(Node {
        i: ai,
        x: ax,
        y: ay,
        prev: NIL,
        next: NIL,
        z: 0,
        prev_z: NIL,
        next_z: NIL,
        steiner: false,
    });
    let b2 = ring.nodes.len();
    ring.nodes.push(Node {
        i: bi,
        x: bx,
        y: by,
        prev: NIL,
        next: NIL,
        z: 0,
        prev_z: NIL,
        next_z: NIL,
        steiner: false,
    });

    let an = ring.next(a);
    let bp = ring.prev(b);

    ring.nodes[a].next = b;
    ring.nodes[b].prev = a;
    ring.nodes[a2].next = an;
    ring.nodes[an].prev = a2;
    ring.nodes[b2].next = a2;
    ring.nodes[a2].prev = b2;
    ring.nodes[bp].next = b2;
    ring.nodes[b2].prev = bp;
    b2
}

/// The reference's own winding measure, kept in its own convention so
/// [`linked_list`]'s `clockwise` flag means what it does upstream.
fn signed_area(coords: &[i32], start: usize, end: usize) -> i64 {
    let mut sum = 0i64;
    let mut j = end - 2;
    let mut i = start;
    while i < end {
        sum += (coords[j] - coords[i]) as i64 * (coords[i + 1] + coords[j + 1]) as i64;
        j = i;
        i += 2;
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Total unsigned area of the emitted triangles.
    fn covered_area(coords: &[i32], triangles: &[u32]) -> f64 {
        let mut total = 0.0;
        for t in triangles.chunks_exact(3) {
            let (a, b, c) = (t[0] as usize, t[1] as usize, t[2] as usize);
            let cross = (coords[b * 2] - coords[a * 2]) as i64
                * (coords[c * 2 + 1] - coords[a * 2 + 1]) as i64
                - (coords[c * 2] - coords[a * 2]) as i64
                    * (coords[b * 2 + 1] - coords[a * 2 + 1]) as i64;
            total += (cross as f64).abs() / 2.0;
        }
        total
    }

    fn shoelace(coords: &[i32]) -> f64 {
        let n = coords.len() / 2;
        let mut sum = 0i64;
        for i in 0..n {
            let j = (i + 1) % n;
            sum += coords[i * 2] as i64 * coords[j * 2 + 1] as i64
                - coords[j * 2] as i64 * coords[i * 2 + 1] as i64;
        }
        sum as f64
    }

    fn assert_no_degenerate(coords: &[i32], triangles: &[u32]) {
        for t in triangles.chunks_exact(3) {
            let (a, b, c) = (t[0] as usize, t[1] as usize, t[2] as usize);
            let cross = (coords[b * 2] - coords[a * 2]) as i64
                * (coords[c * 2 + 1] - coords[a * 2 + 1]) as i64
                - (coords[c * 2] - coords[a * 2]) as i64
                    * (coords[b * 2 + 1] - coords[a * 2 + 1]) as i64;
            assert!(cross != 0, "triangle {a}/{b}/{c} is degenerate");
        }
    }

    /// A monotone staircase: provably simple, with a reflex vertex per step.
    fn staircase(steps: i32, step: i32) -> Vec<i32> {
        let mut out = vec![0, 0, steps * step, 0];
        for i in (1..=steps).rev() {
            let y = (steps - i + 1) * step;
            out.extend_from_slice(&[i * step, y, (i - 1) * step, y]);
        }
        out
    }

    #[test]
    fn a_square_becomes_two_triangles_covering_its_area() {
        let square = [0, 0, 100, 0, 100, 100, 0, 100];
        let triangles = triangulate(&square, &[]);
        assert_eq!(triangles.len() / 3, 2);
        assert!((covered_area(&square, &triangles) - 10_000.0).abs() < 1e-9);
    }

    #[test]
    fn winding_order_does_not_matter() {
        // Rings arrive from a clipper and a simplifier, neither of which preserves
        // orientation, so both windings have to triangulate.
        let cw = [0, 0, 100, 0, 100, 100, 0, 100];
        let ccw = [0, 100, 100, 100, 100, 0, 0, 0];
        assert!((covered_area(&cw, &triangulate(&cw, &[])) - 10_000.0).abs() < 1e-9);
        assert!((covered_area(&ccw, &triangulate(&ccw, &[])) - 10_000.0).abs() < 1e-9);
    }

    #[test]
    fn a_concave_polygon_is_covered_exactly_once() {
        // An L: a naive fan from vertex 0 would cover area outside it, which is what
        // ear clipping exists to avoid.
        let shape = [0, 0, 100, 0, 100, 40, 40, 40, 40, 100, 0, 100];
        let triangles = triangulate(&shape, &[]);
        assert_eq!(triangles.len() / 3, 4);
        assert!((covered_area(&shape, &triangles) - (100.0 * 40.0 + 40.0 * 60.0)).abs() < 1e-9);
        assert_no_degenerate(&shape, &triangles);
    }

    #[test]
    fn a_hole_is_excluded_from_the_covered_area() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        assert!((covered_area(&coords, &triangles) - (10_000.0 - 400.0)).abs() < 1e-9);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn several_holes_are_all_excluded() {
        let mut coords = vec![0, 0, 300, 0, 300, 300, 0, 300];
        coords.extend_from_slice(&[20, 20, 60, 20, 60, 60, 20, 60]);
        coords.extend_from_slice(&[120, 120, 160, 120, 160, 160, 120, 160]);
        coords.extend_from_slice(&[220, 220, 260, 220, 260, 260, 220, 260]);
        let triangles = triangulate(&coords, &[4, 8, 12]);
        assert!((covered_area(&coords, &triangles) - (90_000.0 - 3.0 * 1600.0)).abs() < 1e-9);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn a_hole_wound_the_same_way_as_its_exterior_is_still_a_hole() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        assert!((covered_area(&coords, &triangles) - (10_000.0 - 400.0)).abs() < 1e-9);
    }

    #[test]
    fn degenerate_input_yields_no_triangles_rather_than_panicking() {
        assert!(triangulate(&[], &[]).is_empty());
        assert!(triangulate(&[0, 0], &[]).is_empty());
        assert!(triangulate(&[0, 0, 10, 10], &[]).is_empty());
        // Collinear, so it encloses nothing.
        assert!(triangulate(&[0, 0, 5, 0, 10, 0], &[]).is_empty());
        // A repeated vertex.
        assert!(triangulate(&[0, 0, 0, 0, 0, 0], &[]).is_empty());
    }

    #[test]
    fn a_ring_large_enough_to_use_the_z_order_index_still_triangulates_exactly() {
        // Above 80 vertices this switches to the hashed ear test — a different code
        // path, and the one every coastline tile takes.
        let n = 400;
        let mut coords = vec![0i32; n * 2];
        for i in 0..n {
            let angle = 2.0 * std::f64::consts::PI * i as f64 / n as f64;
            coords[i * 2] = (2048.0 + 1500.0 * angle.cos()) as i32;
            coords[i * 2 + 1] = (2048.0 + 1500.0 * angle.sin()) as i32;
        }
        let triangles = triangulate(&coords, &[]);
        let expected = shoelace(&coords).abs() / 2.0;
        assert!(
            (covered_area(&coords, &triangles) - expected).abs() < 1.0,
            "covered {} vs {expected}",
            covered_area(&coords, &triangles),
        );
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn a_staircase_relentlessly_concave_is_covered_exactly() {
        let coords = staircase(40, 25);
        let triangles = triangulate(&coords, &[]);
        let expected = shoelace(&coords).abs() / 2.0;
        assert!((covered_area(&coords, &triangles) - expected).abs() < 1e-6);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn triangulation_is_deterministic() {
        // Integer predicates mean no epsilon and no device-dependent rounding, so the
        // same ring must give identical output every time — which is what makes a
        // golden-image comparison in CI meaningful.
        let coords = staircase(20, 30);
        let first = triangulate(&coords, &[]);
        for _ in 0..3 {
            assert_eq!(first, triangulate(&coords, &[]));
        }
    }

    #[test]
    fn every_index_addresses_a_real_vertex() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        let vertex_count = coords.len() / 2;
        assert!(!triangles.is_empty());
        assert_eq!(triangles.len() % 3, 0, "indices come in threes");
        for &i in &triangles {
            assert!((i as usize) < vertex_count, "index {i} outside the vertex list");
        }
    }

    #[test]
    fn the_midpoint_test_meets_a_slanted_edge_at_its_true_crossing() {
        // The ray from the diagonal's midpoint meets `(0,0)-(1000,100)` at x=500, far
        // from either endpoint's own x. An edge that shallow is what separates a
        // correctly scaled crossing test from one whose offset term is off by a factor
        // — on axis-aligned test shapes the offset is zero and any scaling passes.
        let pts = [(0, 0), (1000, 100), (800, 100), (-200, 500)];
        let mut ring = Ring::new(pts.len());
        let mut last = NIL;
        for (k, &(x, y)) in pts.iter().enumerate() {
            last = ring.insert(k * 2, x, y, last);
        }
        let v3 = last;
        let (v0, v2) = (ring.next(v3), ring.prev(v3));
        // The midpoint of v0-v2 is (400, 50), inside the quad: the ray to +x crosses
        // v0-v1 once.
        assert!(middle_inside(&ring, v0, v2));
    }
}
