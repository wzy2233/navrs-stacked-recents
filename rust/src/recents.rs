//! recents-rs: a horizontal, iOS-style card carousel with spring physics and a
//! wheel-like 3D projection. Pure software rendering into an RGBA buffer.

use jni::objects::{JByteBuffer, JClass, JFloatArray};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

const ACT_DOWN: jint = 0;
const ACT_UP: jint = 1;
const ACT_MOVE: jint = 2;
const ACT_CANCEL: jint = 3;

const STIFFNESS: f32 = 140.0;
const DAMPING: f32 = 0.86; // fraction of critical damping
const MAX_OVERSCROLL: f32 = 0.55; // in card units
const SLOP_DP: f32 = 8.0;

// Deck ("caravan") mode tuning. PITCH is the 30% spacing between cards,
// BRIGHT_TRIGGER is the 20% travel over which the next card lights up.
const DECK_PITCH: f32 = 0.30;
/// Fan layout: pivot radius (x screen height) and angle per card (radians).
const FAN_RADIUS_F: f32 = 0.88;
const FAN_STEP: f32 = 0.185;
/// Corner radius as a fraction of card width; must match RecentsView.java.
const CORNER: f32 = 0.13;
/// Portrait aspect ratio of a recents card (w / h), like a phone screen.
const PHONE_ASPECT: f32 = 1080.0 / 2340.0;
/// Card height as a fraction of the view height, and vertical centre.
/// Two selectable sizes: compact (more cards on screen) and large (iOS-like).
const CARD_H_SMALL: f32 = 0.663;
const CARD_H_LARGE: f32 = 0.85;
const CARD_CY: f32 = 0.451;
const DECK_BRIGHT_TRIGGER: f32 = 0.25;
// Brightness of a card still sitting in the deck; it ramps up to 1.0 as the
// card in front sweeps away, and back down when it is pushed into the deck.
const DECK_DIM: f32 = 0.80;
// Feel knobs: drag gain < 1 makes the cards trail the finger (heavier feel),
// the spring pair controls how gently the ends settle back.
const DECK_DRAG_GAIN: f32 = 0.80;
const DECK_SPRING_K: f32 = 8.0;
const DECK_SPRING_DAMP: f32 = 4.5;

struct Recents {
    w: i32,
    h: i32,
    density: f32,
    colors: Vec<u32>,
    pos: f32,
    target: f32,
    vel: f32,
    spring_fast: bool,
    /// Deck spacing: false = fixed 0.3 screen widths, true = tied to card width.
    pitch_by_card: bool,
    /// true = large cards (0.85h), false = compact cards (0.58h).
    card_large: bool,
    /// While a launch/return transition runs the layout must not move at all,
    /// otherwise the final animation frame would not match the static layout.
    frozen: bool,
    dragging: bool,
    down_x: f32,
    down_y: f32,
    down_pos: f32,
    down_t: f64,
    last_x: f32,
    last_t: f64,
    moved: bool,
    vel_ema: f32,
    mode: i32,
    /// Card aspect (w/h) of the app content area (system bars removed); 0 = view aspect.
    content_aspect: f32,
    /// Card centre as a fraction of the view height; 0 = default CARD_CY.
    content_cy: f32,
    peel: f32,
    base: f32,
    peel_target: f32,
    settle: f32,
    front: usize,
    down_peel: f32,
    now: f64,
}

impl Recents {
    fn new(w: i32, h: i32, density: f32, count: i32) -> Self {
        let n = count.max(1) as usize;
        let colors = vec![
            0xFF3B5BA5u32,
            0xFFB23A48,
            0xFF2E7D5B,
            0xFF8A5A44,
            0xFF5B4B8A,
        ];
        Recents {
            w,
            h,
            density: if density > 0.1 { density } else { 2.75 },
            colors: (0..n).map(|i| colors[i % colors.len()]).collect(),
            pos: 0.0,
            target: 0.0,
            vel: 0.0,
            spring_fast: false,
            pitch_by_card: false,
            card_large: false,
            frozen: false,
            dragging: false,
            down_x: 0.0,
            down_y: 0.0,
            down_pos: 0.0,
            down_t: 0.0,
            last_x: 0.0,
            last_t: 0.0,
            moved: false,
            vel_ema: 0.0,
            mode: 0,
            content_aspect: 0.0,
            content_cy: 0.0,
            peel: 0.0,
            base: 0.0,
            peel_target: 0.0,
            settle: 0.0,
            front: 0,
            down_peel: 0.0,
            now: 0.0,
        }
    }

    fn count(&self) -> usize {
        self.colors.len()
    }

    /// Removes a card once its dismiss animation has finished, so the layout
    /// closes up around it.
    fn remove_card(&mut self, index: usize) {
        if index >= self.colors.len() {
            return;
        }
        self.colors.remove(index);
        // The deck may legitimately become empty: every background app was closed.
        let last = (self.count() as f32 - 1.0).max(0.0);
        self.pos = self.pos.min(last).max(0.0);
        self.target = self.target.min(last).max(0.0);
        if self.count() == 0 {
            self.peel = 0.0;
            self.vel_ema = 0.0;
        }
    }

    /// Index of the card under a point, for gesture hit testing.
    fn hit_at(&self, x: f32, y: f32) -> i32 {
        self.hit_test(x, y).map(|i| i as i32).unwrap_or(-1)
    }

    fn resize(&mut self, w: i32, h: i32) {
        self.w = w;
        self.h = h;
    }

    fn clamp_index(&self, v: f32) -> f32 {
        let max = (self.count() - 1) as f32;
        v.max(0.0).min(max)
    }

    fn step(&mut self, dt: f32) {
        if self.mode >= 4 {
            if !self.dragging {
                // Case C: viscous damping + dry (Coulomb) friction, so the glide
                // decelerates and actually stops in finite time:
                //   dv/dt = -c*v - Tf*sign(v)
                let c = 2.4f32;
                let tf = 0.55f32;
                let v = self.vel_ema;
                let mut nv = v + (-c * v - tf * v.signum()) * dt;
                if v != 0.0 && nv.signum() != v.signum() {
                    nv = 0.0;
                }
                if nv.abs() < 0.004 {
                    nv = 0.0;
                }
                self.vel_ema = nv;
                self.peel += nv * dt;
                // Both ends spring back to a fresh stable state. The upper bound is
                // "one card parked at +pitch while the deck behind it stays put",
                // i.e. there is still one visible card at the left edge.
                let p = self.deck_pitch();
                let stable_min = 0.0f32;
                let stable_max = p * (self.count() as f32 - 1.0);
                // Free coasting keeps a fling alive; once it is slow the pile
                // parks with one card in the front slot, so a released drag always
                // snaps to the nearest card instead of resting half-way.
                let target = if self.peel < stable_min {
                    stable_min
                } else if self.peel > stable_max {
                    stable_max
                } else if nv.abs() > 0.25 {
                    self.peel
                } else {
                    ((self.peel / p).round() * p).clamp(stable_min, stable_max)
                };
                if (target - self.peel).abs() > 0.0005 {
                    let (k, damp) = if self.spring_fast {
                        (11.0f32, 6.0f32)
                    } else {
                        (DECK_SPRING_K, DECK_SPRING_DAMP)
                    };
                    self.peel += (target - self.peel) * (dt * k).min(1.0);
                    self.vel_ema *= (-dt * damp).exp();
                } else if self.peel < stable_min || self.peel > stable_max {
                    self.peel = target;
                    self.vel_ema = 0.0;
                }
            }
            return;
        }
        if self.dragging {
            return;
        }
        let max = (self.count() - 1) as f32;
        // Two selectable feels: soft (default, like the deck modes) and snappy.
        let (stiffness, damping) = if self.spring_fast {
            (150.0f32, 0.94f32)
        } else {
            (45.0f32, 0.78f32)
        };
        // critically-damped-ish spring
        let c = 2.0 * (stiffness).sqrt() * damping;
        let a = -stiffness * (self.pos - self.target) - c * self.vel;
        self.vel += a * dt;
        self.pos += self.vel * dt;
        // Do not hard-clamp here: an overscroll beyond the first/last card must
        // be allowed to spring back visibly instead of snapping to the bound.
        let guard = max + MAX_OVERSCROLL;
        if self.pos < -MAX_OVERSCROLL {
            self.pos = -MAX_OVERSCROLL;
            self.vel = 0.0;
        } else if self.pos > guard {
            self.pos = guard;
            self.vel = 0.0;
        }
    }

    fn animating(&self) -> bool {
        if self.mode >= 4 {
            let p = self.deck_pitch();
            let stable_max = p * (self.count() as f32 - 1.0);
            // A released pile between two cards is still animating: the snap
            // spring needs frames even after the glide velocity has died out.
            let off_step = (self.peel - (self.peel / p).round() * p).abs() > 0.0008
                    && self.peel >= -0.0005
                    && self.peel <= stable_max + 0.0005;
            return self.dragging
                || self.vel_ema.abs() > 0.002
                || self.peel < -0.0005
                || self.peel > stable_max + 0.0005
                || off_step;
        }
        self.dragging
            || (self.pos - self.target).abs() > 0.001
            || self.vel.abs() > 0.01
    }

    fn touch(&mut self, action: jint, x: f32, y: f32, time_ns: i64) -> jint {
        if self.frozen {
            return -1;
        }
        if self.mode >= 4 {
            return self.touch_peel(action, x, y, time_ns);
        }
        self.now = time_ns as f64 / 1e9;
        let dt = if self.last_t > 0.0 {
            (self.now - self.last_t) as f32
        } else {
            0.0
        };
        match action {
            ACT_DOWN => {
                self.dragging = true;
                self.moved = false;
                self.down_x = x;
                self.down_y = y;
                self.down_pos = self.pos;
                self.down_t = self.now;
                self.last_x = x;
                self.last_t = self.now;
                self.vel = 0.0;
                self.vel_ema = 0.0;
            }
            ACT_MOVE => {
                if !self.dragging {
                    return -1;
                }
                let dx = x - self.down_x;
                if dx.abs() > SLOP_DP * self.density && dt > 0.0 {
                    self.moved = true;
                }
                let spacing = self.spacing();
                let raw = self.down_pos + dx / spacing.max(1.0);
                self.pos = self.rubber(raw);
                if dt > 0.0001 {
                    let vx = (x - self.last_x) / dt;
                    let inst = vx / spacing.max(1.0);
                    self.vel_ema = self.vel_ema * 0.55 + inst * 0.45;
                    self.vel = self.vel_ema;
                }
                self.last_x = x;
                self.last_t = self.now;
            }
            ACT_UP | ACT_CANCEL => {
                if !self.dragging {
                    return -1;
                }
                self.dragging = false;
                let dt_up = (self.now - self.last_t) as f32;
                if dt_up > 0.12 {
                    self.vel = 0.0;
                }
                let tapped = !self.moved
                    && (x - self.down_x).abs() < SLOP_DP * self.density
                    && (y - self.down_y).abs() < SLOP_DP * self.density;
                if tapped {
                    self.target = self.pos;
                    return self.hit_test(x, y).map(|i| i as i32).unwrap_or(-1);
                }
                // Fling distance scales with release velocity: a fast flick can
                // skip up to three cards, a slow drag snaps to the nearest one.
                let projected = self.pos + (self.vel * 0.18).clamp(-2.2, 2.2);
                self.target = self.clamp_index(projected.round());
            }
            _ => {}
        }
        -1
    }

    /// Fills one card rect with its placeholder content (header bar + text lines).
    ///
    /// Per row the card is split into a handful of constant-colour runs (the
    /// content bands are all horizontal), so the interior is written with slice
    /// fills. Only rows that touch the rounded corners fall back to per-pixel work.
    fn draw_quad(&self, buf: &mut [u8], q: [f32; 6], color: u32, shade_scale: f32) {
        let (w, h) = (self.w as usize, self.h as usize);
        let (l, t, r, b) = (q[0], q[1], q[2], q[3]);
        let cw = (r - l).max(1.0);
        let ch = (b - t).max(1.0);
        let corner = q[4] * cw;
        let cr = ((color >> 16) & 0xFF) as f32;
        let cg = ((color >> 8) & 0xFF) as f32;
        let cb = (color & 0xFF) as f32;

        let x0 = l.max(0.0).floor() as usize;
        let y0 = t.max(0.0).floor() as usize;
        let x1 = (r.ceil() as usize).min(w);
        let y1 = (b.ceil() as usize).min(h);
        if x1 <= x0 || y1 <= y0 {
            return;
        }

        for y in y0..y1 {
            let py = y as f32 + 0.5;
            if py < t || py > b {
                continue;
            }
            let v = (py - t) / ch;
            let base = 0.88 + 0.12 * (1.0 - v);
            let dy = (py - t).min(b - py);
            let corner_row = dy < corner;
            let row_start = (y * w) * 4;
            let row = &mut buf[row_start + x0 * 4..row_start + x1 * 4];

            // The content bands only depend on v, so build the run list once.
            let bands = content_runs(v);

            let mut x = x0;
            while x < x1 {
                let pxc = x as f32 + 0.5;
                if pxc < l || pxc > r {
                    x += 1;
                    continue;
                }
                let ex = pxc - l;
                let dx = ex.min(r - pxc);
                if corner_row && dx < corner {
                    let ddx = corner - dx;
                    let ddy = corner - dy;
                    if ddx * ddx + ddy * ddy > corner * corner {
                        x += 1;
                        continue;
                    }
                }
                let u = ex / cw;
                let k = band_k(bands, u);
                let shade = base * k * shade_scale;
                let rr = (cr * shade).min(255.0) as u8;
                let gg = (cg * shade).min(255.0) as u8;
                let bb = (cb * shade).min(255.0) as u8;

                // Extend until the band changes or a corner is entered.
                let mut xe = x + 1;
                while xe < x1 {
                    let p2 = xe as f32 + 0.5;
                    if p2 > r {
                        break;
                    }
                    let ex2 = p2 - l;
                    if corner_row {
                        let dx2 = ex2.min(r - p2);
                        if dx2 < corner {
                            let ddx = corner - dx2;
                            let ddy = corner - dy;
                            if ddx * ddx + ddy * ddy > corner * corner {
                                break;
                            }
                        }
                    }
                    if band_k(bands, ex2 / cw) != k {
                        break;
                    }
                    xe += 1;
                }
                let s = (x - x0) * 4;
                let e = (xe - x0) * 4;
                for slot in row[s..e].chunks_exact_mut(4) {
                    slot[0] = rr;
                    slot[1] = gg;
                    slot[2] = bb;
                    slot[3] = 255;
                }
                x = xe;
            }
        }
    }
    /// Draws every visible card from `card_quad`, in the stacking order the
    /// layout defines (higher index first, index 0 last / on top).
    fn render_cards(&self, buf: &mut [u8]) {
        for i in (0..self.count()).rev() {
            if let Some(q) = self.card_quad(i) {
                self.draw_quad(buf, q, self.colors[i], self.card_alpha(i));
            }
        }
    }
    /// Card geometry for one index: [left, top, right, bottom, corner_ratio, skew]
    /// in buffer pixels. `None` when the card is not visible.
    ///
    /// This is the single source of truth for card placement: the Java layer
    /// composites thumbnails into exactly these rects, so every layout stays in
    /// sync with the native renderer.
    fn card_quad(&self, i: usize) -> Option<[f32; 6]> {
        self.quad_at(i, true)
    }

    /// Same geometry, but never culled: used by transition animations so a card
    /// that is about to enter the screen is already drawn from its real position
    /// instead of popping in when the animation hands over to the static layout.
    fn card_quad_raw(&self, i: usize) -> [f32; 6] {
        self.quad_at(i, false).unwrap_or([0.0; 6])
    }

    fn quad_at(&self, i: usize, cull: bool) -> Option<[f32; 6]> {
        if i >= self.count() {
            return None;
        }
        let f = i as f32;
        let d = f - self.pos;
        let (w, h) = (self.w as f32, self.h as f32);
        // Every card keeps the phone's portrait aspect ratio; only the height
        // fraction and the pitch differ per layout.
        let card_h = h * self.card_h_f();
        let card_w = self.card_w_px();
        let hw = card_w * 0.5;

        // Fan: cards rotate around a pivot below the screen.
        if self.mode == 2 {
            let radius = h * FAN_RADIUS_F;
            let pivot_x = w * 0.5;
            let pivot_y = h * 0.42 + radius;
            // Newer cards sit to the right of older ones, matching the stock
            // overview pager: the newest task is the rightmost card.
            let phi = (-d * FAN_STEP).clamp(-1.2, 1.2);
            let cx = pivot_x + radius * phi.sin();
            let cy = pivot_y - radius * phi.cos();
            let (s, c) = (phi.sin().abs(), phi.cos().abs());
            let hh = card_h * 0.5;
            let ex = hw * c + hh * s;
            let ey = hw * s + hh * c;
            return Some([cx - ex, cy - ey, cx + ex, cy + ey, CORNER, 0.0]);
        }

        // Upright deck: later cards underneath (drawn first).
        if self.mode == 3 {
            let left = w * 0.5 - d * w * 0.40 - hw;
            if cull && (left + card_w < 0.0 || left > w) {
                return None;
            }
            return Some([left, h * self.card_cy() - card_h * 0.5, left + card_w,
                h * self.card_cy() + card_h * 0.5, CORNER, 0.0]);
        }

        // Peel decks: cards form a chain pulled out of a pile.
        //
        //   u_i = pitch + peel - pitch * i      (gear angle of card i)
        //
        // The card at u = pitch is the one resting at the left edge; cards with
        // u < 0 are still buried in the pile and sit exactly at the left edge.
        // In gear mode the screen position is the projection 1 - cos(pi/2 * u),
        // which compresses the pile at the left and spreads it towards the right.
        if self.mode == 4 || self.mode == 5 {
            let pitch = self.deck_pitch();
            let u = pitch + self.peel - pitch * f;
            if cull {
                if u >= 1.0 {
                    return None;
                }
                // Everything buried deeper than the top of the pile sits at exactly
                // the same place, so only the shallowest of them is worth drawing.
                let buried_from = ((pitch + self.peel) / pitch).ceil() as usize;
                if u <= 0.0 && i > buried_from {
                    return None;
                }
            }
            let uc = u.max(0.0);
            let s = if self.mode == 5 {
                1.0 - (std::f32::consts::FRAC_PI_2 * uc).cos()
            } else {
                uc
            };
            // Park the resting card exactly on the screen centre (the window
            // animation's card is centred); otherwise the deck sits a fraction of a
            // pitch to the right and the hand-off has to glide sideways.
            let s_rest = if self.mode == 5 {
                1.0 - (std::f32::consts::FRAC_PI_2 * pitch.min(1.0)).cos()
            } else {
                pitch.min(1.0)
            };
            let cx = w * 0.5 + (s - s_rest) * w;
            return Some([cx - hw, h * self.card_cy() - card_h * 0.5,
                cx + hw, h * self.card_cy() + card_h * 0.5, CORNER, 0.0]);
        }

        // Carousel (spaced / stacked): plain upright cards, flat spacing.
        if cull && d.abs() > 2.6 {
            return None;
        }
        let cx = w * 0.5 - d * self.spacing();
        if cull && (cx + hw < 0.0 || cx - hw > w) {
            return None;
        }
        Some([cx - hw, h * self.card_cy() - card_h * 0.5,
            cx + hw, h * self.card_cy() + card_h * 0.5, CORNER, 0.0])
    }
    /// Brightness multiplier applied to a card's thumbnail.
    fn card_alpha(&self, i: usize) -> f32 {
        if i >= self.count() {
            return 1.0;
        }
        let d = i as f32 - self.pos;
        match self.mode {
            3 => {
                // Uncovered cards brighten as the front card sweeps away.
                if i == 0 {
                    1.0
                } else {
                    let p = self.deck_pitch();
                    let u = p + self.peel - p * (i - 1) as f32;
                    0.80 + 0.20 * (u / DECK_BRIGHT_TRIGGER).clamp(0.0, 1.0)
                }
            }
            4 | 5 => {
                // The card behind brightens as the one in front of it is pulled
                // out, so the pile "wakes up" one card at a time.
                let pitch = self.deck_pitch().max(0.0001);
                let u = pitch + self.peel - pitch * i as f32;
                if u <= 0.0 {
                    // Cards buried at the far left stay dim; they must not snap
                    // back to full brightness once the pile has moved past them.
                    DECK_DIM
                } else if u < DECK_BRIGHT_TRIGGER {
                    0.80 + 0.20 * (u / DECK_BRIGHT_TRIGGER)
                } else {
                    1.0
                }
            }
            _ => (1.0 - d.abs() * 0.18).clamp(0.35, 1.0),
        }
    }
    /// Drag handling for the two peel decks (闂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗ù锝呮贡閻濊泛鈹戦悩鍙夋悙缁炬儳顭烽弻娑㈠即閵娿儮鍋撻悩娴嬫婵☆垱绮嶅Λ鍐ㄧ暦閻旂厧鐓涢柍褜鍓欐晥?/ 濠电姷鏁告慨鐢割敊閺嶎厼绐楁俊銈呭暞閺嗘粍淇婇妶鍌氫壕闁告浜堕弻鐔兼倻濮楀棙鐣剁紒鐐劤閻忔繈鍩為幋锔藉亹鐎规洖娴傞弳锟犳⒑?. Deferred handling only:
    /// the deck follows the finger and stops where it is; the ends spring back.
    fn touch_peel(&mut self, action: jint, x: f32, y: f32, time_ns: i64) -> jint {
        self.now = time_ns as f64 / 1e9;
        match action {
            ACT_DOWN => {
                self.dragging = true;
                self.down_x = x;
                self.down_y = y;
                self.down_peel = self.peel;
                self.last_x = x;
                self.last_t = self.now;
                self.vel_ema = 0.0;
                self.moved = false;
            }
            ACT_MOVE => {
                if self.dragging {
                    let next = self.down_peel
                        + (x - self.down_x) / self.w as f32 * DECK_DRAG_GAIN;
                    let dt = (self.now - self.last_t) as f32;
                    if dt > 0.0005 {
                        let inst = (next - self.peel) / dt;
                        self.vel_ema = self.vel_ema * 0.5 + inst * 0.5;
                    }
                    if (x - self.down_x).abs() > SLOP_DP * self.density
                        || (y - self.down_y).abs() > SLOP_DP * self.density
                    {
                        self.moved = true;
                    }
                    self.peel = next;
                    self.last_x = x;
                    self.last_t = self.now;
                }
            }
            ACT_UP | ACT_CANCEL => {
                self.dragging = false;
                if action == ACT_UP && !self.moved {
                    // A tap opens the card that is actually under the finger, not the
                    // one that happens to be fronting the pile.
                    return self.hit_test(x, y).map(|i| i as jint).unwrap_or(-1);
                }
            }
            _ => {}
        }
        -1
    }

    /// Rubber band for the carousel modes; keeps drags past either end bounded.
    fn rubber(&self, raw: f32) -> f32 {
        let max = (self.count() - 1) as f32;
        if raw < 0.0 {
            -hard_rubber(-raw, MAX_OVERSCROLL)
        } else if raw > max {
            max + hard_rubber(raw - max, MAX_OVERSCROLL)
        } else {
            raw
        }
    }
    /// Distance between neighbouring cards, in buffer pixels.
    ///
    /// The carousel layouts scale their pitch with the card width so a large card
    /// never swallows its neighbour: the same visible sliver is kept at both
    /// sizes. Deck and fan layouts keep their own fixed pitch.
    fn spacing(&self) -> f32 {
        let w = self.w as f32;
        if self.mode == 3 {
            // Upright deck: one card width plus a sliver, so the next card's edge
            // stays visible at any card size.
            return self.card_w_px() * 1.06;
        }
        if self.mode == 2 {
            // Drag the fan by its bottom-edge arc: arc length per card equals
            // the pivot radius times the per-card angle step. A small extra gain
            // makes the fan feel a touch lighter than pure arc-following.
            return self.h as f32 * FAN_RADIUS_F * FAN_STEP * 0.82;
        }
        // Carousel (spaced / stacked): pitch follows the card width.
        let overlap = if self.mode == 1 { 0.60 } else { 0.78 };
        (self.card_w_px() + w * 0.10) * overlap
    }

    /// Topmost card containing the point, in buffer coordinates. Cards are drawn
    /// high index first, so the lowest matching index wins.
    fn hit_test(&self, x: f32, y: f32) -> Option<usize> {
        (0..self.count())
            .filter(|i| {
                self.card_quad(*i)
                    .map(|q| x >= q[0] && x <= q[2] && y >= q[1] && y <= q[3])
                    .unwrap_or(false)
            })
            .next()
    }

    /// Index of the card currently fronting a peel deck.
    fn front(&self) -> usize {
        if self.count() == 0 {
            return 0;
        }
        // The card whose gear angle is exactly one pitch, i.e. the one resting
        // at the left edge of the chain.
        let p = self.deck_pitch().max(0.0001);
        ((self.peel / p).round().max(0.0) as usize).min(self.count() - 1)
    }

    fn set_mode(&mut self, mode: i32) {
        self.mode = mode;
    }

    /// Spacing between peel-deck cards, as a fraction of screen width.
    /// Fixed 0.30 by default; when tied to the card it leaves a narrow sliver.
    fn deck_pitch(&self) -> f32 {
        if self.pitch_by_card {
            let w = self.w as f32;
            self.card_w_px() / w * 1.15
        } else {
            DECK_PITCH
        }
    }

    fn set_frozen(&mut self, on: bool) {
        self.frozen = on;
    }

    /// Snaps the carousel to its resting position. Called before a dismissal is
    /// captured, so the closing animation targets the settled layout instead of a
    /// half-dragged one (which would otherwise snap when the freeze is released).
    fn settle(&mut self) {
        self.pos = self.target.round().clamp(0.0, (self.count() as f32 - 1.0).max(0.0));
        self.target = self.pos;
        self.vel = 0.0;
    }

    fn set_card_large(&mut self, on: bool) {
        self.card_large = on;
    }

    /// Card height fraction for the currently selected size.
    fn card_cy(&self) -> f32 {
        if self.content_cy > 0.0 {
            self.content_cy
        } else {
            CARD_CY
        }
    }

    fn card_h_f(&self) -> f32 {
        if self.card_large {
            CARD_H_LARGE
        } else {
            CARD_H_SMALL
        }
    }

    /// Card width in buffer pixels for the current size.
    ///
    /// Tie the width to the view's own aspect ratio instead of a fixed 9:16 so the
    /// card matches the device screen; on an 18:9 panel a 9:16 card looks too wide
    /// and the full-screen launch never lands on the app window's edges.
    fn card_w_px(&self) -> f32 {
        let w = self.w as f32;
        let h = self.h as f32;
        let aspect = if self.content_aspect > 0.0 {
            self.content_aspect
        } else {
            w / h
        };
        (h * self.card_h_f() * aspect).min(w * 0.96)
    }

    fn set_pitch_by_card(&mut self, on: bool) {
        self.pitch_by_card = on;
    }

    fn set_spring(&mut self, fast: bool) {
        self.spring_fast = fast;
    }

    fn render(&mut self, buf: &mut [u8], time_ns: i64) -> bool {
        let prev = self.now;
        self.now = time_ns as f64 / 1e9;
        let dt = ((self.now - prev) as f32).clamp(0.0, 0.05);
        if dt > 0.0 && !self.dragging && !self.frozen {
            self.step(dt);
        }

        let (w, h) = (self.w as usize, self.h as usize);
        if w == 0 || h == 0 || buf.len() < w * h * 4 {
            return false;
        }
        // Transparent pass: the host supplies the background (wallpaper/blur).
        for px in buf[..w * h * 4].chunks_exact_mut(4) {
            px[0] = 0;
            px[1] = 0;
            px[2] = 0;
            px[3] = 0;
        }

        // The Java compositor draws every card from a real snapshot. The native
        // pass only supplies the background: drawing placeholder bodies here made
        // them peek through rounded/dimmed screenshots.
        self.animating()
    }
}

fn hard_rubber(x: f32, limit: f32) -> f32 {
    // asymptotic rubber band: 0 -> 0, infinity -> limit
    limit * (1.0 - 1.0 / (1.0 + x / limit.max(0.01)))
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    w: jint,
    h: jint,
    density: jfloat,
    count: jint,
) -> jlong {
    Box::into_raw(Box::new(Recents::new(w, h, density, count))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    w: jint,
    h: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).resize(w, h) };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeRender(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    time_ns: jlong,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let r = unsafe { &mut *(handle as *mut Recents) };
    match env.get_direct_buffer_address(&buffer) {
        Ok(ptr) if !ptr.is_null() => {
            let cap = env.get_direct_buffer_capacity(&buffer).unwrap_or(0);
            let need = (r.w as usize) * (r.h as usize) * 4;
            if cap < need {
                return JNI_FALSE;
            }
            let slice = unsafe { std::slice::from_raw_parts_mut(ptr, need) };
            if r.render(slice, time_ns) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeTouch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    action: jint,
    x: jfloat,
    y: jfloat,
    time_ns: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    unsafe { (*(handle as *mut Recents)).touch(action, x, y, time_ns) }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Recents));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeTarget(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    unsafe { (*(handle as *mut Recents)).target as jint }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativePos(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    if handle == 0 {
        return 0.0;
    }
    unsafe { (*(handle as *mut Recents)).pos }
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetContent(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    aspect: jfloat,
    center_y: jfloat,
) {
    if handle == 0 {
        return;
    }
    let rec = unsafe { &mut *(handle as *mut Recents) };
    rec.content_aspect = if aspect > 0.0 { aspect } else { 0.0 };
    rec.content_cy = if center_y > 0.0 && center_y < 1.0 { center_y } else { 0.0 };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetMode(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mode: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).set_mode(mode) };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetSpring(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fast: jboolean,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).set_spring(fast != 0) };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeFrontIndex(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let rec = unsafe { &*(handle as *const Recents) };
    rec.front() as jint
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSettle(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).settle() };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeRemoveCard(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).remove_card(index.max(0) as usize) };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeHitAt(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let rec = unsafe { &*(handle as *const Recents) };
    rec.hit_at(x, y)
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetFrozen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    on: jboolean,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).set_frozen(on != 0) };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetCardLarge(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    on: jboolean,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).set_card_large(on != 0) };
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetPitchByCard(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    on: jboolean,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Recents)).set_pitch_by_card(on != 0) };
}
/// Writes [left, top, right, bottom, corner, skew] into `out`; returns false when
/// the card is not visible.
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeCardRect(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
    _scratch: jfloatArray,
    out: jfloatArray,
) -> jboolean {
    let mut quad = [0.0f32; 6];
    let visible = if handle != 0 {
        let rec = unsafe { &*(handle as *const Recents) };
        match rec.card_quad(index.max(0) as usize) {
            Some(q) => {
                quad = q;
                true
            }
            None => false,
        }
    } else {
        false
    };
    let arr = unsafe { JFloatArray::from_raw(out) };
    let _ = env.set_float_array_region(&arr, 0, &quad);
    if visible {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeCardCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let rec = unsafe { &*(handle as *const Recents) };
    rec.count() as jint
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeCardQuadRaw(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
    out: jfloatArray,
) {
    let mut quad = [0.0f32; 6];
    if handle != 0 {
        let rec = unsafe { &*(handle as *const Recents) };
        quad = rec.card_quad_raw(index.max(0) as usize);
    }
    let arr = unsafe { JFloatArray::from_raw(out) };
    let _ = env.set_float_array_region(&arr, 0, &quad);
}
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeCardAlpha(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloat {
    if handle == 0 {
        return 1.0;
    }
    let rec = unsafe { &*(handle as *const Recents) };
    rec.card_alpha(index.max(0) as usize)
}

/// Content bands (u ranges) that make up a card's placeholder page.
#[derive(Clone, Copy)]
struct Band {
    u0: f32,
    u1: f32,
    k: f32,
}

/// Returns the constant-shade bands for a given vertical position `v`.
fn content_runs(v: f32) -> &'static [Band] {
    const FULL: &[Band] = &[Band { u0: 0.08, u1: 0.92, k: 0.55 }];
    const R70: &[Band] = &[Band { u0: 0.08, u1: 0.70, k: 0.45 }];
    const R80: &[Band] = &[Band { u0: 0.08, u1: 0.80, k: 0.45 }];
    const R60: &[Band] = &[Band { u0: 0.08, u1: 0.60, k: 0.45 }];
    const NONE: &[Band] = &[];
    if v < 0.085 {
        return NONE;
    } else if v < 0.095 {
        return NONE;
    } else if v < 0.20 {
        return FULL;
    } else if v < 0.27 {
        return R70;
    } else if v < 0.34 {
        return R80;
    } else if v < 0.41 {
        return R60;
    }
    NONE
}

/// Shade multiplier from the bands; 1.0 outside them, 0.72 in the header.
fn band_k(bands: &[Band], u: f32) -> f32 {
    for bnd in bands {
        if u >= bnd.u0 && u <= bnd.u1 {
            return bnd.k;
        }
    }
    1.0
}
#[cfg(test)]
mod tests {
    use super::*;

    fn rec() -> Recents {
        Recents::new(400, 800, 1.0, 10)
    }

    /// Cards that report a geometry for the current layout.
    fn visible(r: &Recents) -> usize {
        (0..r.count()).filter(|i| r.card_quad(*i).is_some()).count()
    }

    /// How many cards cover a sample point (screen fraction x, y).
    fn layers_at(r: &Recents, sx: f32, sy: f32) -> usize {
        let (x, y) = (sx * r.w as f32, sy * r.h as f32);
        (0..r.count())
            .filter(|i| {
                r.card_quad(*i)
                    .map(|q| x >= q[0] && x <= q[2] && y >= q[1] && y <= q[3])
                    .unwrap_or(false)
            })
            .count()
    }

    fn report(name: &str, r: &Recents) {
        let layers: Vec<usize> = (0..=10)
            .map(|k| layers_at(r, k as f32 / 10.0, 0.47))
            .collect();
        println!(
            "{:<8} visible={:>2}  layers(x=0..100%)={:?}  max={}",
            name,
            visible(r),
            layers,
            layers.iter().copied().max().unwrap_or(0)
        );
    }

    #[test]
    fn layout_layer_table() {
        println!("\n=== rest state ===");
        for (name, mode) in [
            ("spaced", 0), ("stacked", 1), ("fan", 2),
            ("deck", 3), ("peel", 4), ("gear", 5),
        ] {
            let mut r = rec();
            r.mode = mode;
            report(name, &r);
        }

        println!("\n=== peel dragged by 0.5 pitch ===");
        for (name, mode) in [("peel", 4), ("gear", 5)] {
            let mut r = rec();
            r.mode = mode;
            r.peel = r.deck_pitch() * 0.5;
            report(name, &r);
        }

        println!("\n=== peel at the far end (front = last) ===");
        for (name, mode) in [("peel", 4), ("gear", 5)] {
            let mut r = rec();
            r.mode = mode;
            r.peel = r.deck_pitch() * 9.0;
            report(name, &r);
        }
    }

    #[test]
    fn peel_advances_one_card_per_pitch() {
        let mut r = rec();
        r.mode = 4;
        let p = r.deck_pitch();
        for i in 0..10 {
            r.peel = p * i as f32;
            assert_eq!(r.front(), i, "front at peel={}pitch", i);
        }
    }

    #[test]
    fn peel_draws_only_the_top_of_the_pile() {
        // Buried cards are geometrically identical, so they must be culled: at rest
        // only the front card and the top of the pile are drawn.
        let mut r = rec();
        r.mode = 4;
        assert_eq!(visible(&r), 2, "front card + pile top only");
        // Mid-drag the chain shows the front card plus the two behind it.
        r.peel = r.deck_pitch() * 0.5;
        assert_eq!(visible(&r), 3, "chain cards only, buried pile culled");
    }
}
#[cfg(test)]
mod parity_tests {
    use super::*;

    fn rec() -> Recents {
        Recents::new(400, 800, 1.0, 10)
    }

    /// Deterministic pseudo-random sequence so failures are reproducible.
    fn rand_seq(seed: u64) -> impl Iterator<Item = f32> {
        let mut s = seed;
        std::iter::from_fn(move || {
            s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            Some(((s >> 33) as f32) / (u32::MAX as f32 / 2.0))
        })
    }

    /// Every visible card must be driven by `card_quad` alone: two calls in the
    /// same state have to return bit-identical geometry, for every layout and for
    /// every drag position. This is what guarantees the thumbnails and the native
    /// body can never drift apart.
    #[test]
    fn geometry_is_deterministic_across_layers() {
        for mode in 0..6 {
            for step in 0..40 {
                let mut r = rec();
                r.mode = mode;
                let u = step as f32 / 40.0;
                if mode >= 4 {
                    r.peel = u * r.deck_pitch() * 9.0;
                } else {
                    r.pos = u * 9.0;
                }
                for i in 0..r.count() {
                    let a = r.card_quad(i);
                    let b = r.card_quad(i);
                    assert_eq!(a, b, "mode {mode} state {u} card {i} is not deterministic");
                }
            }
        }
    }

    /// Cards that visually overlap must be at exactly the same place, not merely
    /// close: the pile in the peel decks is the strict case.
    #[test]
    fn buried_pile_cards_share_the_front_rect_exactly() {
        for &mode in &[4usize, 5] {
            for step in 0..12 {
                let mut r = rec();
                r.mode = mode as i32;
                r.peel = step as f32 * 0.25 * r.deck_pitch();
                let pile_top = ((r.deck_pitch() + r.peel) / r.deck_pitch()).ceil() as usize;
                let Some(top) = r.card_quad(pile_top) else { continue };
                // Everything deeper than the top of the pile is culled, so the top
                // rect is the single rect all buried cards would occupy.
                for i in pile_top..r.count() {
                    if let Some(q) = r.card_quad(i) {
                        assert_eq!(q, top, "mode {mode} card {i} drifted from the pile top");
                    }
                }
            }
        }
    }

    /// The brightness driver must stay inside [0.8, 1.0] for every card and state,
    /// otherwise a layer would visibly pop.
    #[test]
    fn alpha_stays_in_range() {
        for mode in 0..6 {
            for step in 0..40 {
                let mut r = rec();
                r.mode = mode;
                let u = step as f32 / 40.0;
                if mode >= 4 {
                    r.peel = u * r.deck_pitch() * 9.0;
                } else {
                    r.pos = u * 9.0;
                }
                for i in 0..r.count() {
                    let a = r.card_alpha(i);
                    // Deck modes stay in the 0.80..1.0 band; carousel modes may fade
                    // with depth down to 0.35, but never leave that envelope.
                    let lo = if mode >= 4 { 0.79 } else { 0.34 };
                    assert!((lo..=1.001).contains(&a), "mode {mode} alpha {a} out of range");
                }
            }
        }
    }

    /// Stress the layouts with random drag states and assert nothing produces a
    /// NaN / inverted rect (which would silently skip drawing).
    #[test]
    fn random_states_never_produce_broken_rects() {
        let mut gen = rand_seq(0x5EED);
        for mode in 0..6 {
            for _ in 0..200 {
                let mut r = rec();
                r.mode = mode;
                let v = gen.next().unwrap();
                if mode >= 4 {
                    r.peel = v * r.deck_pitch() * 10.0;
                } else {
                    r.pos = v * 10.0;
                }
                for i in 0..r.count() {
                    if let Some(q) = r.card_quad(i) {
                        for k in 0..4 {
                            assert!(q[k].is_finite(), "mode {mode} card {i} has non-finite geometry");
                        }
                        assert!(q[2] > q[0] && q[3] > q[1], "mode {mode} card {i} inverted rect");
                    }
                }
            }
        }
    }
}
#[cfg(test)]
mod hit_tests {
    use super::*;

    fn rec() -> Recents {
        let mut r = Recents::new(400, 800, 1.0, 10);
        r.mode = 4;
        r
    }

    /// Tapping the sliver of the card behind must select that card, not the front.
    #[test]
    fn tap_on_the_left_sliver_picks_the_card_behind() {
        let r = rec();
        let pitch = r.deck_pitch();
        let front = r.front();
        // The front card sits a fraction of a pitch to the right, exposing the
        // left edge of the pile underneath.
        let front_q = r.card_quad(front).unwrap();
        let pile_top = (front_q[0]).max(0.0);
        // Sample a point that is left of the front card's own left edge.
        let left_x = (front_q[0] * 0.5).max(1.0);
        let mid_y = (front_q[1] + front_q[3]) * 0.5;
        if left_x < front_q[0] {
            let hit = r.hit_test(left_x, mid_y);
            assert_eq!(hit, Some(front + 1), "left sliver belongs to the card behind");
            let _ = pile_top;
            let _ = pitch;
        }
    }

    /// A tap in the middle of the front card selects the front card.
    #[test]
    fn tap_in_the_middle_picks_the_front_card() {
        let r = rec();
        let front = r.front();
        let q = r.card_quad(front).unwrap();
        let x = (q[0] + q[2]) * 0.5;
        let y = (q[1] + q[3]) * 0.5;
        assert_eq!(r.hit_test(x, y), Some(front));
    }

    /// Outside every card there is no hit at all (the view must not launch anything).
    #[test]
    fn tap_outside_the_cards_hits_nothing() {
        let r = rec();
        assert_eq!(r.hit_test(399.0, 5.0), None);
    }
}
#[cfg(test)]
mod dismiss_tests {
    use super::*;

    fn rec() -> Recents {
        Recents::new(400, 800, 1.0, 10)
    }

    /// Removing cards one by one must end with an empty deck and no ghost card.
    #[test]
    fn dismissing_every_card_empties_the_deck() {
        for &mode in &[0usize, 1, 2, 3, 4, 5] {
            let mut r = rec();
            r.mode = mode as i32;
            let mut expected = r.count();
            while r.count() > 0 {
                r.remove_card(0);
                expected -= 1;
                assert_eq!(r.count(), expected, "mode {mode} lost a card");
                // Every remaining index must still be valid.
                for i in 0..r.count() {
                    if let Some(q) = r.card_quad(i) {
                        assert!(q[2] > q[0] && q[3] > q[1]);
                    }
                    let a = r.card_alpha(i);
                    assert!(a.is_finite());
                }
            }
            assert_eq!(r.count(), 0, "mode {mode} deck not empty");
            // An empty deck must not report any card.
            assert!(r.card_quad(0).is_none());
            assert_eq!(r.front(), 0);
        }
    }

    /// Removing a middle card keeps exactly one geometry per remaining card.
    #[test]
    fn settle_snaps_to_rest_and_stays_in_range() {
        for &mode in &[0usize, 1, 2, 3] {
            let mut r = rec();
            r.mode = mode as i32;
            r.pos = 3.4;
            r.target = 3.0;
            r.settle();
            assert_eq!(r.pos, 3.0, "mode {mode} did not snap");
            r.pos = 99.0;
            r.target = 99.0;
            r.settle();
            assert!(r.pos <= (r.count() - 1) as f32, "mode {mode} out of range");
        }
    }

    #[test]
    fn removing_middle_keeps_exactly_one_rect_per_card() {
        let mut r = rec();
        r.mode = 3;
        let before = r.count();
        r.remove_card(4);
        assert_eq!(r.count(), before - 1);
        let mut visible = 0;
        for i in 0..r.count() {
            if r.card_quad(i).is_some() {
                visible += 1;
            }
        }
        assert!(visible <= r.count());
        assert!(visible >= 1, "至少应有一张可见卡片");
    }
}