//! What a voice note is recorded as, and how its waveform is drawn.
//!
//! The numbers and the maths are the iOS recorder's, moved here so Android and
//! the desktop record the same note: `VoiceRecordingFormat.swift:17-27` and
//! `Supermessage/Composer/VoiceRecorder.swift:44-56, 124-170`.
//!
//! A Matrix voice message is Ogg/Opus (MSC3245). 48 kHz mono at 24 kbit/s is
//! MSC3245's suggestion: about 3 KB a second, at a quality made for speech
//! (`VoiceRecordingFormat.swift:4-14`; iOS records Opus in CAF and the core
//! moves the packets into Ogg pages).
//!
//! The level scale: -50 dB and below is silence, 0 dB is full scale. A linear
//! scale over that range reads like speech in a bubble; the full -160 dB range
//! would flatten everything but a shout (`VoiceRecorder.swift:151-153`). A
//! long note is squeezed into at most [`VoiceRecordingSpec::waveform_bins`]
//! points by averaging, so a long note and a short one draw the same width
//! (`:160-161`).
//!
//! This is the recording side. `core::audio::normalise_waveform` is the
//! receiving side's bound and is untouched.

use serde::{Deserialize, Serialize};

/// The recorder's numbers, for a host to configure its encoder and meter.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct VoiceRecordingSpec {
    /// `VoiceRecordingFormat.swift:17` minus the container's extension.
    pub file_stem: String,
    /// `VoiceRecordingFormat.swift:24`.
    pub sample_rate: u32,
    /// `VoiceRecordingFormat.swift:25`.
    pub channels: u32,
    /// `VoiceRecordingFormat.swift:26`, bits per second.
    pub bit_rate: u32,
    /// How often the input level is sampled: `VoiceRecorder.swift:46` (0.1 s).
    pub meter_interval_ms: u32,
    /// How many points a waveform keeps: `VoiceRecorder.swift:47`.
    pub waveform_bins: u32,
    /// Shorter than this is a slip of the thumb, not a message:
    /// `VoiceRecorder.swift:56` (0.6 s).
    pub shortest_ms: u64,
}

/// Decibels at and below this read as silence.
pub const FLOOR_DB: f32 = -50.0;

pub fn spec() -> VoiceRecordingSpec {
    VoiceRecordingSpec {
        file_stem: "Voice message".into(),
        sample_rate: 48_000,
        channels: 1,
        bit_rate: 24_000,
        meter_interval_ms: 100,
        waveform_bins: 60,
        shortest_ms: 600,
    }
}

/// dBFS to 0...1 (`VoiceRecorder.swift:154-158`). A reading that is not a
/// finite number (NaN or either infinity, as Swift's `isFinite`) is silence.
pub fn level(decibels: f32) -> f32 {
    if !decibels.is_finite() {
        return 0.0;
    }
    ((decibels - FLOOR_DB) / -FLOOR_DB).clamp(0.0, 1.0)
}

/// The meter's readings as a waveform: every reading through [`level`], then
/// squeezed into at most `bins` points by averaging (`:162-170`). Fewer
/// levels than bins, or no bins, return the levels unchanged.
pub fn waveform(decibels: &[f32], bins: usize) -> Vec<f32> {
    let levels: Vec<f32> = decibels.iter().map(|&db| level(db)).collect();
    let n = levels.len();
    if n <= bins || bins == 0 {
        return levels;
    }
    (0..bins)
        .map(|bin| {
            let start = bin * n / bins;
            let end = (start + 1).max((bin + 1) * n / bins);
            let slice = &levels[start..end];
            slice.iter().sum::<f32>() / slice.len() as f32
        })
        .collect()
}

/// Shorter than [`VoiceRecordingSpec::shortest_ms`] is not a message (`:130`).
pub fn long_enough(duration_ms: u64) -> bool {
    duration_ms >= spec().shortest_ms
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Mutation seen failing: changing any one number fails the field-by-field
    /// comparison.
    #[test]
    fn the_recorders_numbers_are_ioss() {
        let s = spec();
        assert_eq!(s.file_stem, "Voice message");
        assert_eq!(s.sample_rate, 48_000);
        assert_eq!(s.channels, 1);
        assert_eq!(s.bit_rate, 24_000);
        assert_eq!(s.meter_interval_ms, 100);
        assert_eq!(s.waveform_bins, 60);
        assert_eq!(s.shortest_ms, 600);
    }

    /// Mutation seen failing: a floor of -60 instead of -50 moves `-25` off
    /// `0.5`.
    #[test]
    fn decibels_map_linearly_from_minus_fifty_to_zero() {
        assert_eq!(
            waveform(&[-60.0, -50.0, -25.0, 0.0, 6.0], 60),
            vec![0.0, 0.0, 0.5, 1.0, 1.0]
        );
    }

    /// Mutation seen failing: `is_nan()` in place of `!is_finite()` lets
    /// infinity through as `1.0` / `0.0` clamped, failing the `INFINITY` case.
    #[test]
    fn a_level_that_is_not_a_number_is_silence() {
        assert_eq!(level(f32::NAN), 0.0);
        assert_eq!(level(f32::INFINITY), 0.0);
        assert_eq!(level(f32::NEG_INFINITY), 0.0);
    }

    /// Mutation seen failing: taking the first of each bin (`slice[0]`) instead
    /// of the mean fails the alternating case (all `0.0` where `0.5` is wanted).
    #[test]
    fn a_long_recording_is_averaged_into_its_bins() {
        let alternating: Vec<f32> = (0..120)
            .map(|i| if i % 2 == 0 { -50.0 } else { 0.0 })
            .collect();
        assert_eq!(waveform(&alternating, 60), vec![0.5; 60]);

        let uneven = waveform(&[-50.0, -40.0, -30.0, -20.0, -10.0, 0.0, 0.0], 3);
        let want = [0.1, 0.5, 0.933_333_3];
        assert_eq!(uneven.len(), 3);
        for (got, want) in uneven.iter().zip(want) {
            assert!((got - want).abs() < 1e-6, "{got} vs {want}");
        }
    }

    /// Mutation seen failing: dropping the `bins == 0` guard returns an empty
    /// vector, since `0..0` is empty, so the zero-bins equality fails
    /// (`left: []`).
    #[test]
    fn fewer_levels_than_bins_pass_through_and_zero_bins_changes_nothing() {
        assert_eq!(waveform(&[-50.0; 5], 60).len(), 5);
        assert_eq!(waveform(&[-25.0; 10], 0), vec![0.5; 10]);
        assert!(waveform(&[], 60).is_empty());
    }

    /// Mutation seen failing: `> 600` for `>=` fails the 600 case.
    #[test]
    fn six_hundred_milliseconds_is_the_shortest_message() {
        assert!(!long_enough(0));
        assert!(!long_enough(599));
        assert!(long_enough(600));
    }
}
