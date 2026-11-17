//! nav-rs: a tiny software renderer for a three-button navigation bar.
//!
//! The bar itself is transparent; only icons and ripples are painted, so the
//! system's own nav-bar background (app colour / scrim) shows through.

mod recents;
mod deck;

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

const ACTION_DOWN: jint = 0;
const ACTION_UP: jint = 1;
const ACTION_MOVE: jint = 2;
const ACTION_CANCEL: jint = 3;

const ACT_NONE: jint = 0;
const ACT_BACK: jint = 1;
const ACT_HOME: jint = 2;
const ACT_RECENTS: jint = 3;

const RIPPLE_SECONDS: f64 = 0.42;
const LONG_PRESS_SECONDS: f64 = 0.45;

/// Fraction of the bar's long axis covered by the three buttons. 1.0 would give the
/// stock "one third each" layout; 0.66 pulls the outer buttons away from the corners
/// and cuts the gap between them from 360 px to ~238 px on a 1080-wide bar.
const BTN_BAR_FRACTION: f32 = 0.66;

#[derive(Clone, Copy)]
struct Rect {
    l: f32,
    t: f32,
    r: f32,
    b: f32,
}

impl Rect {
    fn contains(&self, x: f32, y: f32) -> bool {
        x >= self.l && x <= self.r && y >= self.t && y <= self.b
    }
}

struct Button {
    action: jint,
    rect: Rect,
    pressed: bool,
    ripple_start: f64,
    ripple_active: bool,
}

struct Renderer {
    w: i32,
    h: i32,
    density: f32,
    color: (u8, u8, u8),
    /// Bar background; alpha 0 means "transparent, let the system bar show through".
    bar: (u8, u8, u8, u8),
    keyguard: bool,
    buttons: Vec<Button>,
    now: f64,
    down_at: f64,
    long_fired: bool,
}

impl Renderer {
    fn new(w: i32, h: i32, density: f32) -> Self {
        let mut r = Renderer {
            w,
            h,
            density: if density > 0.1 { density } else { 2.75 },
            color: (255, 255, 255),
            bar: (0, 0, 0, 0),
            keyguard: false,
            buttons: Vec::new(),
            now: 0.0,
            down_at: 0.0,
            long_fired: true,
        };
        r.layout();
        r
    }

    fn layout(&mut self) {
        let w = self.w as f32;
        let h = self.h as f32;
        let vertical = h > w;
        let mut buttons = Vec::new();
        for i in 0..3 {
            // Stock centres each button in its own third, which leaves the outer two
            // hugging the screen corners and 1/3 of the bar apart. Tightening the used
            // span moves them inwards and brings them closer together.
            let (l, t, r, b) = if vertical {
                let span = h * BTN_BAR_FRACTION;
                let start = (h - span) * 0.5;
                let slot = span / 3.0;
                (
                    0.0,
                    start + slot * i as f32,
                    w,
                    start + slot * (i as f32 + 1.0),
                )
            } else {
                let span = w * BTN_BAR_FRACTION;
                let start = (w - span) * 0.5;
                let slot = span / 3.0;
                (
                    start + slot * i as f32,
                    0.0,
                    start + slot * (i as f32 + 1.0),
                    h,
                )
            };
            buttons.push(Button {
                action: ACT_BACK + i as jint,
                rect: Rect { l, t, r, b },
                pressed: false,
                ripple_start: 0.0,
                ripple_active: false,
            });
        }
        self.buttons = buttons;
    }

    fn resize(&mut self, w: i32, h: i32) {
        if self.w == w && self.h == h {
            return;
        }
        self.w = w;
        self.h = h;
        self.layout();
    }

    fn set_color(&mut self, argb: i32) {
        let v = argb as u32;
        self.color = (
            ((v >> 16) & 0xFF) as u8,
            ((v >> 8) & 0xFF) as u8,
            (v & 0xFF) as u8,
        );
    }

    /// Background painted behind the buttons. Alpha 0 keeps the bar transparent.
    fn set_bar_color(&mut self, argb: i32) {
        let v = argb as u32;
        self.bar = (
            ((v >> 16) & 0xFF) as u8,
            ((v >> 8) & 0xFF) as u8,
            (v & 0xFF) as u8,
            ((v >> 24) & 0xFF) as u8,
        );
    }

    fn render(&mut self, buf: &mut [u8], time_ns: i64) -> bool {
        self.now = time_ns as f64 / 1_000_000_000.0;
        let (w, h) = (self.w as usize, self.h as usize);
        if w == 0 || h == 0 || buf.len() < w * h * 4 {
            return false;
        }

        let (bar_r, bar_g, bar_b, bar_a) = self.bar;
        for px in buf[..w * h * 4].chunks_exact_mut(4) {
            px[0] = bar_r;
            px[1] = bar_g;
            px[2] = bar_b;
            px[3] = bar_a;
        }

        let mut animating = false;
        let d = self.density;
        let (cr, cg, cb) = self.color;

        for b in &self.buttons {
            if self.keyguard && b.action != ACT_BACK {
                continue;
            }
            if b.ripple_active {
                let p = ((self.now - b.ripple_start) / RIPPLE_SECONDS).clamp(0.0, 1.0) as f32;
                if p < 1.0 {
                    animating = true;
                }
                let radius = (10.0 + 34.0 * p) * d;
                let cx = (b.rect.l + b.rect.r) * 0.5;
                let cy = (b.rect.t + b.rect.b) * 0.5;
                let alpha = 0.13 * (1.0 - p);
                if alpha > 0.002 {
                    fill_circle_clipped(
                        buf, w, h, cx, cy, radius, &b.rect, (cr, cg, cb), alpha,
                    );
                }
            }

            let cx = (b.rect.l + b.rect.r) * 0.5;
            let cy = (b.rect.t + b.rect.b) * 0.5;
            let alpha = if b.pressed { 1.0 } else { 0.94 };

            match b.action {
                ACT_BACK => {
                    let stroke = 1.15 * d;
                    let vertical = self.h > self.w;
                    let (x1, y1) = rotate(vertical, cx, cy, 3.6 * d, -7.0 * d);
                    let (x2, y2) = rotate(vertical, cx, cy, -3.6 * d, 0.0);
                    let (x3, y3) = rotate(vertical, cx, cy, 3.6 * d, 7.0 * d);
                    seg(buf, w, h, x1, y1, x2, y2, stroke, (cr, cg, cb), alpha);
                    seg(buf, w, h, x2, y2, x3, y3, stroke, (cr, cg, cb), alpha);
                }
                ACT_HOME => {
                    let radius = 8.6 * d;
                    let stroke = 1.9 * d;
                    ring_circle(buf, w, h, cx, cy, radius, stroke, (cr, cg, cb), alpha);
                }
                ACT_RECENTS => {
                    let half = 7.6 * d;
                    let corner = 2.4 * d;
                    let stroke = 1.85 * d;
                    ring_round_rect(buf, w, h, cx, cy, half, half, corner, stroke, (cr, cg, cb), alpha);
                }
                _ => {}
            }
        }

        for b in &mut self.buttons {
            if b.ripple_active && self.now - b.ripple_start > RIPPLE_SECONDS {
                b.ripple_active = false;
            }
        }

        animating
    }

    fn touch(&mut self, action: jint, x: f32, y: f32, time_ns: i64) -> jint {
        self.now = time_ns as f64 / 1_000_000_000.0;
        match action {
            ACTION_DOWN => {
                self.down_at = self.now;
                self.long_fired = false;
                for b in &mut self.buttons {
                    let inside = b.rect.contains(x, y);
                    b.pressed = inside;
                    if inside {
                        b.ripple_start = self.now;
                        b.ripple_active = true;
                    }
                }
                ACT_NONE
            }
            ACTION_MOVE => {
                for b in &mut self.buttons {
                    if b.pressed && !b.rect.contains(x, y) {
                        b.pressed = false;
                        self.long_fired = true;
                    }
                }
                ACT_NONE
            }
            ACTION_UP => {
                let mut result = ACT_NONE;
                if !self.long_fired {
                    for b in &mut self.buttons {
                        if b.pressed && b.rect.contains(x, y) {
                            result = b.action;
                        }
                        b.pressed = false;
                    }
                }
                for b in &mut self.buttons {
                    b.pressed = false;
                }
                self.long_fired = true;
                result
            }
            ACTION_CANCEL => {
                for b in &mut self.buttons {
                    b.pressed = false;
                }
                self.long_fired = true;
                ACT_NONE
            }
            _ => ACT_NONE,
        }
    }

    fn long_press(&mut self, time_ns: i64) -> jint {
        self.now = time_ns as f64 / 1_000_000_000.0;
        if self.long_fired || self.now - self.down_at < LONG_PRESS_SECONDS {
            return ACT_NONE;
        }
        let mut result = ACT_NONE;
        for b in &self.buttons {
            if b.pressed {
                result = b.action;
            }
        }
        if result != ACT_NONE {
            self.long_fired = true;
        }
        result
    }
}

#[inline]
fn rotate(vertical: bool, cx: f32, cy: f32, dx: f32, dy: f32) -> (f32, f32) {
    if vertical {
        (cx - dy, cy + dx)
    } else {
        (cx + dx, cy + dy)
    }
}

#[inline]
fn blend(px: &mut [u8], r: u8, g: u8, b: u8, a: f32) {
    let a = a.clamp(0.0, 1.0);
    if a <= 0.0 {
        return;
    }
    let ia = 1.0 - a;
    px[0] = (r as f32 * a + px[0] as f32 * ia) as u8;
    px[1] = (g as f32 * a + px[1] as f32 * ia) as u8;
    px[2] = (b as f32 * a + px[2] as f32 * ia) as u8;
    px[3] = (255.0 * a + px[3] as f32 * ia) as u8;
}

#[inline]
fn coverage(sd: f32) -> f32 {
    (0.5 - sd).clamp(0.0, 1.0)
}

fn fill_circle_clipped(
    buf: &mut [u8],
    w: usize,
    h: usize,
    cx: f32,
    cy: f32,
    radius: f32,
    clip: &Rect,
    color: (u8, u8, u8),
    alpha: f32,
) {
    let x0 = (cx - radius).max(clip.l).max(0.0) as usize;
    let x1 = ((cx + radius).min(clip.r).min(w as f32) as usize).min(w);
    let y0 = (cy - radius).max(clip.t).max(0.0) as usize;
    let y1 = ((cy + radius).min(clip.b).min(h as f32) as usize).min(h);
    for y in y0..y1 {
        for x in x0..x1 {
            let px = x as f32 + 0.5;
            let py = y as f32 + 0.5;
            let dx = px - cx;
            let dy = py - cy;
            let sd = (dx * dx + dy * dy).sqrt() - radius;
            let c = coverage(sd);
            if c > 0.0 {
                let i = (y * w + x) * 4;
                blend(&mut buf[i..i + 4], color.0, color.1, color.2, alpha * c);
            }
        }
    }
}

fn seg(
    buf: &mut [u8],
    w: usize,
    h: usize,
    ax: f32,
    ay: f32,
    bx: f32,
    by: f32,
    radius: f32,
    color: (u8, u8, u8),
    alpha: f32,
) {
    let x0 = (ax.min(bx) - radius - 1.0).max(0.0) as usize;
    let x1 = ((ax.max(bx) + radius + 1.0).min(w as f32) as usize).min(w);
    let y0 = (ay.min(by) - radius - 1.0).max(0.0) as usize;
    let y1 = ((ay.max(by) + radius + 1.0).min(h as f32) as usize).min(h);
    let pax = ax;
    let pay = ay;
    let bax = bx - ax;
    let bay = by - ay;
    let len2 = (bax * bax + bay * bay).max(0.0001);
    for y in y0..y1 {
        for x in x0..x1 {
            let px = x as f32 + 0.5;
            let py = y as f32 + 0.5;
            let pax2 = px - pax;
            let pay2 = py - pay;
            let t = ((pax2 * bax + pay2 * bay) / len2).clamp(0.0, 1.0);
            let dx = pax2 - bax * t;
            let dy = pay2 - bay * t;
            let sd = (dx * dx + dy * dy).sqrt() - radius;
            let c = coverage(sd);
            if c > 0.0 {
                let i = (y * w + x) * 4;
                blend(&mut buf[i..i + 4], color.0, color.1, color.2, alpha * c);
            }
        }
    }
}

fn ring_circle(
    buf: &mut [u8],
    w: usize,
    h: usize,
    cx: f32,
    cy: f32,
    radius: f32,
    stroke: f32,
    color: (u8, u8, u8),
    alpha: f32,
) {
    let outer = radius + stroke;
    let x0 = (cx - outer).max(0.0) as usize;
    let x1 = ((cx + outer).min(w as f32) as usize).min(w);
    let y0 = (cy - outer).max(0.0) as usize;
    let y1 = ((cy + outer).min(h as f32) as usize).min(h);
    for y in y0..y1 {
        for x in x0..x1 {
            let dx = x as f32 + 0.5 - cx;
            let dy = y as f32 + 0.5 - cy;
            let sd = ((dx * dx + dy * dy).sqrt() - radius).abs() - stroke * 0.5;
            let c = coverage(sd);
            if c > 0.0 {
                let i = (y * w + x) * 4;
                blend(&mut buf[i..i + 4], color.0, color.1, color.2, alpha * c);
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn ring_round_rect(
    buf: &mut [u8],
    w: usize,
    h: usize,
    cx: f32,
    cy: f32,
    hw: f32,
    hh: f32,
    corner: f32,
    stroke: f32,
    color: (u8, u8, u8),
    alpha: f32,
) {
    let x0 = (cx - hw - stroke).max(0.0) as usize;
    let x1 = ((cx + hw + stroke).min(w as f32) as usize).min(w);
    let y0 = (cy - hh - stroke).max(0.0) as usize;
    let y1 = ((cy + hh + stroke).min(h as f32) as usize).min(h);
    for y in y0..y1 {
        for x in x0..x1 {
            let px = x as f32 + 0.5;
            let py = y as f32 + 0.5;
            let qx = (px - cx).abs() - (hw - corner);
            let qy = (py - cy).abs() - (hh - corner);
            let ax = qx.max(0.0);
            let ay = qy.max(0.0);
            let sd = (ax * ax + ay * ay).sqrt() + qx.max(qy).min(0.0) - corner;
            let sd = sd.abs() - stroke * 0.5;
            let c = coverage(sd);
            if c > 0.0 {
                let i = (y * w + x) * 4;
                blend(&mut buf[i..i + 4], color.0, color.1, color.2, alpha * c);
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    w: jint,
    h: jint,
    density: jfloat,
) -> jlong {
    Box::into_raw(Box::new(Renderer::new(w, h, density))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    w: jint,
    h: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Renderer)).resize(w, h) };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeSetColor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    argb: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Renderer)).set_color(argb) };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeSetBarColor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    argb: jint,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Renderer)).set_bar_color(argb) };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeSetKeyguard(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    keyguard: jboolean,
) {
    if handle == 0 {
        return;
    }
    unsafe { (*(handle as *mut Renderer)).keyguard = keyguard != JNI_FALSE };
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeLongPress(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ns: jlong,
) -> jint {
    if handle == 0 {
        return ACT_NONE;
    }
    unsafe { (*(handle as *mut Renderer)).long_press(time_ns) }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeRender(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    time_ns: jlong,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let r = unsafe { &mut *(handle as *mut Renderer) };
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
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeTouch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    action: jint,
    x: jfloat,
    y: jfloat,
    time_ns: jlong,
) -> jint {
    if handle == 0 {
        return ACT_NONE;
    }
    unsafe { (*(handle as *mut Renderer)).touch(action, x, y, time_ns) }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Renderer));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_NavBarView_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    env.new_string("nav-rs 0.2")
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
