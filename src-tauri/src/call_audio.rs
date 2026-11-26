//! The microphone and speaker for calls, in Rust beside the core (not in the webview), so the
//! audio path is the call library's: 48 kHz mono i16 in and out of superdial.
//!
//! cpal with its PulseAudio host, which cpal prefers when a server answers: PipeWire serves the
//! PulseAudio protocol (`pipewire-pulse`), and so does PulseAudio itself, so this is the one
//! path that reaches both. Without a server cpal falls back to ALSA.
//!
//! The devices run at whatever rate and channel count they offer; everything here converts to
//! and from 48 kHz mono. Streams live on one thread that ends when the [`AudioIo`] is dropped.
//!
//! `SUPERMESSAGE_CALL_INPUT` / `SUPERMESSAGE_CALL_OUTPUT` name a device (its cpal id, with or
//! without the `host:` prefix) instead of the defaults: the integration test points them at
//! PipeWire null sinks.

use std::{
    collections::VecDeque,
    sync::{mpsc as std_mpsc, Arc, Mutex},
};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};
use supermessage_core::calls::controller::{AudioIo, CallAudio};
use tokio::sync::mpsc;

/// What superdial takes and gives.
pub const CALL_RATE: u32 = 48_000;

/// Most audio the speaker may lag behind the call: 300 ms. Beyond it the oldest is dropped,
/// so a stall does not leave the agent's voice running seconds late.
const MAX_PLAYBACK_MS: usize = 300;

pub const INPUT_ENV: &str = "SUPERMESSAGE_CALL_INPUT";
pub const OUTPUT_ENV: &str = "SUPERMESSAGE_CALL_OUTPUT";

/// Averages interleaved frames down to one channel.
pub fn downmix_to_mono(interleaved: &[f32], channels: usize) -> Vec<f32> {
    if channels <= 1 {
        return interleaved.to_vec();
    }
    interleaved
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect()
}

/// `[-1, 1]` floats to i16, clamped.
pub fn f32_to_i16(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|s| (s.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16)
        .collect()
}

pub fn i16_to_f32(samples: &[i16]) -> Vec<f32> {
    samples
        .iter()
        .map(|s| *s as f32 / i16::MAX as f32)
        .collect()
}

/// A linear-interpolation resampler for one mono stream, continuous across chunks. Plenty for
/// speech on its way to speech-to-text, and for a voice coming back.
#[derive(Debug, Clone)]
pub struct Resampler {
    /// Input samples advanced per output sample.
    step: f64,
    /// Where the next output sample falls, in the coming chunk's indices; -1 is `last`.
    next: f64,
    /// The previous chunk's last sample.
    last: f32,
    identity: bool,
}

impl Resampler {
    pub fn new(from_hz: u32, to_hz: u32) -> Self {
        Self {
            step: from_hz as f64 / to_hz as f64,
            next: 0.0,
            last: 0.0,
            identity: from_hz == to_hz,
        }
    }

    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if self.identity || input.is_empty() {
            return input.to_vec();
        }
        let n = input.len() as f64;
        let at = |i: isize| if i < 0 { self.last } else { input[i as usize] };
        let mut out = Vec::with_capacity((n / self.step) as usize + 2);
        // Interpolating at `next` needs the sample after it, so stop before the chunk's last;
        // the next chunk picks up from `next - n`, which is never below -1 (`last`).
        while self.next < n - 1.0 {
            let i = self.next.floor();
            let frac = (self.next - i) as f32;
            let (a, b) = (at(i as isize), at(i as isize + 1));
            out.push(a + (b - a) * frac);
            self.next += self.step;
        }
        self.next -= n;
        self.last = input[input.len() - 1];
        out
    }
}

/// What the speaker plays from: device-rate mono, bounded.
#[derive(Debug)]
pub struct PlaybackBuffer {
    samples: VecDeque<f32>,
    max: usize,
}

impl PlaybackBuffer {
    pub fn new(max_samples: usize) -> Self {
        Self {
            samples: VecDeque::with_capacity(max_samples),
            max: max_samples,
        }
    }

    pub fn push(&mut self, mono: &[f32]) {
        self.samples.extend(mono.iter().copied());
        let excess = self.samples.len().saturating_sub(self.max);
        self.samples.drain(..excess);
    }

    /// Fills interleaved `out` (every channel the same), with silence once empty.
    pub fn pull_into(&mut self, out: &mut [f32], channels: usize) {
        let channels = channels.max(1);
        for frame in out.chunks_mut(channels) {
            let s = self.samples.pop_front().unwrap_or(0.0);
            frame.iter_mut().for_each(|x| *x = s);
        }
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.samples.len()
    }
}

/// Whether `id` (cpal's `host:device`) is the device someone named.
fn names_device(id: &str, wanted: &str) -> bool {
    id == wanted
        || id
            .split_once(':')
            .is_some_and(|(_, device)| device == wanted)
}

/// The microphone and speaker through cpal.
#[derive(Default)]
pub struct CpalAudio;

struct OpenAudio {
    playback: Arc<Mutex<(PlaybackBuffer, Resampler)>>,
    /// Dropped with this: the device thread then drops its streams and ends.
    _stop: std_mpsc::Sender<()>,
}

impl AudioIo for OpenAudio {
    fn play(&self, samples: &[i16]) {
        let mut guard = self.playback.lock().expect("playback lock");
        let (buffer, resampler) = &mut *guard;
        let at_device_rate = resampler.process(&i16_to_f32(samples));
        buffer.push(&at_device_rate);
    }
}

impl CallAudio for CpalAudio {
    fn open(&self, mic: mpsc::UnboundedSender<Vec<i16>>) -> Result<Box<dyn AudioIo>, String> {
        let (ready_tx, ready_rx) =
            std_mpsc::channel::<Result<Arc<Mutex<(PlaybackBuffer, Resampler)>>, String>>();
        let (stop_tx, stop_rx) = std_mpsc::channel::<()>();
        std::thread::Builder::new()
            .name("call-audio".into())
            .spawn(move || {
                match open_streams(mic) {
                    Ok((input, output, playback)) => {
                        let _ = ready_tx.send(Ok(playback));
                        // Until the AudioIo is dropped (the sender goes away).
                        let _ = stop_rx.recv();
                        drop(input);
                        drop(output);
                        tracing::info!("call audio: devices closed");
                    }
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        let playback = ready_rx
            .recv()
            .map_err(|_| "the audio thread ended".to_string())??;
        Ok(Box::new(OpenAudio {
            playback,
            _stop: stop_tx,
        }))
    }
}

type Opened = (
    cpal::Stream,
    cpal::Stream,
    Arc<Mutex<(PlaybackBuffer, Resampler)>>,
);

fn pick(host: &cpal::Host, env: &str, input: bool) -> Result<cpal::Device, String> {
    if let Ok(wanted) = std::env::var(env) {
        let devices = if input {
            host.input_devices().map(|d| d.collect::<Vec<_>>())
        } else {
            host.output_devices().map(|d| d.collect::<Vec<_>>())
        }
        .map_err(|e| e.to_string())?;
        return devices
            .into_iter()
            .find(|d| {
                d.id()
                    .is_ok_and(|id| names_device(&id.to_string(), &wanted))
            })
            .ok_or_else(|| format!("no audio device named {wanted}"));
    }
    if input {
        host.default_input_device()
    } else {
        host.default_output_device()
    }
    .ok_or_else(|| {
        format!(
            "no default {} device",
            if input { "input" } else { "output" }
        )
    })
}

fn open_streams(mic: mpsc::UnboundedSender<Vec<i16>>) -> Result<Opened, String> {
    let host = cpal::default_host();
    let input = pick(&host, INPUT_ENV, true)?;
    let output = pick(&host, OUTPUT_ENV, false)?;
    let in_config = input.default_input_config().map_err(|e| e.to_string())?;
    let out_config = output.default_output_config().map_err(|e| e.to_string())?;
    tracing::info!(
        host = ?host.id(),
        input = %input.id().map(|i| i.to_string()).unwrap_or_default(),
        input_config = ?in_config,
        output = %output.id().map(|i| i.to_string()).unwrap_or_default(),
        output_config = ?out_config,
        "call audio: opening"
    );

    let in_stream = match in_config.sample_format() {
        cpal::SampleFormat::F32 => build_input::<f32>(&input, &in_config.config(), mic),
        cpal::SampleFormat::I16 => build_input::<i16>(&input, &in_config.config(), mic),
        cpal::SampleFormat::I32 => build_input::<i32>(&input, &in_config.config(), mic),
        other => Err(format!(
            "microphone sample format {other:?} is not supported"
        )),
    }?;

    let out_rate = out_config.sample_rate();
    let playback = Arc::new(Mutex::new((
        PlaybackBuffer::new(out_rate as usize * MAX_PLAYBACK_MS / 1000),
        Resampler::new(CALL_RATE, out_rate),
    )));
    let out_stream = match out_config.sample_format() {
        cpal::SampleFormat::F32 => {
            build_output::<f32>(&output, &out_config.config(), playback.clone())
        }
        cpal::SampleFormat::I16 => {
            build_output::<i16>(&output, &out_config.config(), playback.clone())
        }
        cpal::SampleFormat::I32 => {
            build_output::<i32>(&output, &out_config.config(), playback.clone())
        }
        other => Err(format!("speaker sample format {other:?} is not supported")),
    }?;
    in_stream.play().map_err(|e| e.to_string())?;
    out_stream.play().map_err(|e| e.to_string())?;
    Ok((in_stream, out_stream, playback))
}

fn build_input<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    mic: mpsc::UnboundedSender<Vec<i16>>,
) -> Result<cpal::Stream, String>
where
    T: SizedSample,
    f32: FromSample<T>,
{
    let channels = config.channels as usize;
    let mut resampler = Resampler::new(config.sample_rate, CALL_RATE);
    device
        .build_input_stream::<T, _, _>(
            *config,
            move |data: &[T], _| {
                let floats: Vec<f32> = data.iter().map(|s| s.to_sample::<f32>()).collect();
                let mono = downmix_to_mono(&floats, channels);
                let _ = mic.send(f32_to_i16(&resampler.process(&mono)));
            },
            |e| tracing::warn!(error = %e, "call audio: microphone"),
            None,
        )
        .map_err(|e| e.to_string())
}

fn build_output<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    playback: Arc<Mutex<(PlaybackBuffer, Resampler)>>,
) -> Result<cpal::Stream, String>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = config.channels as usize;
    let mut scratch: Vec<f32> = Vec::new();
    device
        .build_output_stream::<T, _, _>(
            *config,
            move |data: &mut [T], _| {
                scratch.resize(data.len(), 0.0);
                if let Ok(mut guard) = playback.lock() {
                    guard.0.pull_into(&mut scratch, channels);
                } else {
                    scratch.iter_mut().for_each(|s| *s = 0.0);
                }
                for (out, s) in data.iter_mut().zip(&scratch) {
                    *out = T::from_sample(*s);
                }
            },
            |e| tracing::warn!(error = %e, "call audio: speaker"),
            None,
        )
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(hz: f64, rate: u32, seconds: f64) -> Vec<f32> {
        let n = (rate as f64 * seconds) as usize;
        (0..n)
            .map(|i| (2.0 * std::f64::consts::PI * hz * i as f64 / rate as f64).sin() as f32 * 0.5)
            .collect()
    }

    /// Goertzel power at `hz` over the total power.
    fn tone_ratio(samples: &[f32], rate: u32, hz: f64) -> f64 {
        let w = 2.0 * std::f64::consts::PI * hz / rate as f64;
        let (mut s1, mut s2) = (0.0f64, 0.0f64);
        for x in samples {
            let s = *x as f64 + 2.0 * w.cos() * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        let power = s1 * s1 + s2 * s2 - 2.0 * w.cos() * s1 * s2;
        let total: f64 = samples.iter().map(|x| (*x as f64).powi(2)).sum();
        2.0 * power / (samples.len() as f64 * total)
    }

    #[test]
    fn resample_44100_to_48000_length_and_tone() {
        let input = tone(440.0, 44_100, 1.0);
        let out = Resampler::new(44_100, 48_000).process(&input);
        assert!((out.len() as i64 - 48_000).abs() <= 1, "got {}", out.len());
        assert!(tone_ratio(&out, 48_000, 440.0) > 0.9);
        assert!(tone_ratio(&out, 48_000, 660.0) < 0.05);
    }

    #[test]
    fn resample_48000_to_44100_length() {
        let out = Resampler::new(48_000, 44_100).process(&tone(440.0, 48_000, 1.0));
        assert!((out.len() as i64 - 44_100).abs() <= 1, "got {}", out.len());
        assert!(tone_ratio(&out, 44_100, 440.0) > 0.9);
    }

    #[test]
    fn resampler_is_continuous_across_chunks() {
        let input = tone(440.0, 44_100, 0.5);
        let whole = Resampler::new(44_100, 48_000).process(&input);
        let mut r = Resampler::new(44_100, 48_000);
        let mut chunked = Vec::new();
        for c in input.chunks(441) {
            chunked.extend(r.process(c));
        }
        assert_eq!(chunked.len(), whole.len());
        for (a, b) in chunked.iter().zip(&whole) {
            assert!((a - b).abs() < 1e-3, "{a} vs {b}");
        }
    }

    #[test]
    fn same_rate_is_identity() {
        let input = vec![0.1, -0.2, 0.3];
        assert_eq!(Resampler::new(48_000, 48_000).process(&input), input);
    }

    #[test]
    fn downmix_averages_channels() {
        assert_eq!(downmix_to_mono(&[1.0, 0.0, 0.5, 0.5], 2), vec![0.5, 0.5]);
        assert_eq!(downmix_to_mono(&[0.25, 0.75], 1), vec![0.25, 0.75]);
    }

    #[test]
    fn f32_to_i16_clamps() {
        assert_eq!(
            f32_to_i16(&[0.0, 1.0, -1.0, 2.0, -3.0]),
            vec![0, 32767, -32767, 32767, -32767]
        );
    }

    #[test]
    fn playback_buffer_plays_silence_when_empty_and_caps_latency() {
        let mut b = PlaybackBuffer::new(4);
        let mut out = [9.0f32; 2];
        b.pull_into(&mut out, 1);
        assert_eq!(out, [0.0, 0.0]);
        b.push(&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
        assert_eq!(b.len(), 4, "the oldest went");
        let mut out = [0.0f32; 4];
        b.pull_into(&mut out, 1);
        assert_eq!(out, [3.0, 4.0, 5.0, 6.0]);
    }

    #[test]
    fn playback_fills_every_channel() {
        let mut b = PlaybackBuffer::new(10);
        b.push(&[0.5, -0.5]);
        let mut out = [0.0f32; 6];
        b.pull_into(&mut out, 2);
        assert_eq!(out, [0.5, 0.5, -0.5, -0.5, 0.0, 0.0]);
    }

    #[test]
    fn devices_are_named_with_or_without_the_host() {
        assert!(names_device("pulseaudio:sd_mic", "sd_mic"));
        assert!(names_device("pulseaudio:sd_mic", "pulseaudio:sd_mic"));
        assert!(names_device("pulseaudio:sd_spk.monitor", "sd_spk.monitor"));
        assert!(!names_device("pulseaudio:sd_mic2", "sd_mic"));
    }
}
