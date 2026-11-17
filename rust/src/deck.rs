//! Minimal, self-contained deck gesture state machine.
//!
//! Owns the progress normalization, the three-tier release policy with two
//! tunable gates, and the shell's running-card rect. No external animation
//! framework: a few atomics and pure math.

use std::sync::atomic::{AtomicU32, Ordering};

fn get(a: &AtomicU32) -> f32 {
    f32::from_bits(a.load(Ordering::Relaxed))
}

fn set(a: &AtomicU32, v: f32) {
    a.store(f32::to_bits(v), Ordering::Relaxed);
}

static GATE_SHORT: AtomicU32 = AtomicU32::new(f32::to_bits(1.2f32));
static GATE_LONG: AtomicU32 = AtomicU32::new(f32::to_bits(3.0f32));

static RUN_L: AtomicU32 = AtomicU32::new(f32::to_bits(0.0f32));
static RUN_T: AtomicU32 = AtomicU32::new(f32::to_bits(0.0f32));
static RUN_R: AtomicU32 = AtomicU32::new(f32::to_bits(0.0f32));
static RUN_B: AtomicU32 = AtomicU32::new(f32::to_bits(0.0f32));

/// Directly assigns the two gesture boundaries (normalized drag shift).
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetGates(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    short_max: jni::sys::jfloat,
    long_min: jni::sys::jfloat,
) {
    set(&GATE_SHORT, short_max);
    set(&GATE_LONG, long_min);
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeGetGateShort(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jfloat {
    get(&GATE_SHORT)
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeGetGateLong(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jfloat {
    get(&GATE_LONG)
}

/// 0 = back to the app, 1 = settle into overview, 2 = fly home.
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeDecideTarget(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    progress: jni::sys::jfloat,
) -> jni::sys::jint {
    if progress < get(&GATE_SHORT) {
        0
    } else if progress < get(&GATE_LONG) {
        1
    } else {
        2
    }
}

/// Normalizes the raw finger displacement into the drag shift.
#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeUpdateProgress(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    displacement: jni::sys::jfloat,
    transition_len: jni::sys::jint,
    drag_factor: jni::sys::jfloat,
) -> jni::sys::jfloat {
    let tl = transition_len as f32;
    if tl <= 0.0 {
        return 0.0;
    }
    let d = displacement;
    if d > tl * drag_factor {
        drag_factor.max(0.0)
    } else {
        d.max(0.0) / tl
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeSetRunningRect(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    l: jni::sys::jfloat,
    t: jni::sys::jfloat,
    r: jni::sys::jfloat,
    b: jni::sys::jfloat,
) {
    set(&RUN_L, l);
    set(&RUN_T, t);
    set(&RUN_R, r);
    set(&RUN_B, b);
}

#[no_mangle]
pub extern "system" fn Java_com_wzy_navrs_RecentsView_nativeGetRunningRect(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    out: jni::objects::JFloatArray,
) {
    let vals = [get(&RUN_L), get(&RUN_T), get(&RUN_R), get(&RUN_B)];
    let _ = env.set_float_array_region(&out, 0, &vals);
}
