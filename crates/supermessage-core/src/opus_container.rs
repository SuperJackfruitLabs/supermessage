//! Moving Opus packets between the two containers voice notes live in:
//! **Ogg**, which Matrix voice messages are defined in, and **CAF**, which is
//! the only container Apple's own frameworks read and write Opus in.
//!
//! ## Why this exists
//!
//! MSC3245 defines a voice message as "OGG files, encoded with Opus". Element
//! X on iOS refuses to play a voice message whose `info.mimetype` does not
//! start with `audio/ogg` (it converts the Ogg to AAC before playing), which
//! is why the `.m4a` notes this app sent until 0.0.13 showed as voice
//! messages there but never played.
//!
//! Apple's side is the mirror image: `AVAudioRecorder` encodes Opus, but only
//! into a CAF file, and `AVAudioPlayer` decodes Opus, but only out of one. No
//! Apple framework reads or writes Ogg.
//!
//! Both containers hold the same thing — a run of self-delimited Opus
//! packets and a count of how many samples at each end are padding — so the
//! bridge between them is a **remux, not a transcode**: no audio is decoded,
//! no encoder runs, and a packet leaves byte-for-byte as it came in. That is
//! what keeps this small, pure Rust, and free of a codec library (libopus is
//! BSD, but it is C, and would be a cross-compiled native dependency on five
//! targets for work that needs none of it).
//!
//! - [`caf_to_ogg`] — a recording from the iOS composer, on its way out.
//! - [`ogg_to_caf`] — a voice note from Element (or from us) on its way into
//!   `AVAudioPlayer`, or into a WebKit `<audio>`, which also plays CAF/Opus.
//!
//! Both also report the note's length, read from the container's own sample
//! counts, which is exact where a recorder's clock is not.
//!
//! ## Untrusted input
//!
//! `ogg_to_caf` runs over whatever a stranger uploaded. Everything read is
//! bounds-checked, every count is capped ([`MAX_PACKETS`]), and a file that
//! is not what it claims is an [`OpusContainerError`], never a panic.
//!
//! References: RFC 7845 (Ogg encapsulation for Opus), RFC 6716 §3.1 (the
//! TOC byte), and Apple's *Core Audio Format Specification*.

use std::io::Cursor;

/// Opus always runs at 48 kHz: Ogg granule positions and pre-skip are counted
/// at this rate whatever rate the audio was recorded at (RFC 7845 §4).
pub const OPUS_RATE: u32 = 48_000;

/// An hour of 20 ms packets. A voice note is minutes at most; a file claiming
/// more packets than this is refused rather than allocated for.
pub const MAX_PACKETS: usize = 180_000;

/// Why a file could not be moved between containers.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum OpusContainerError {
    #[error("not a CAF file")]
    NotCaf,
    #[error("not an Ogg file")]
    NotOgg,
    #[error("the audio is not Opus")]
    NotOpus,
    #[error("unsupported Opus layout: {0}")]
    Unsupported(String),
    #[error("the file is damaged: {0}")]
    Malformed(String),
}

type Result<T> = std::result::Result<T, OpusContainerError>;

/// Opus audio in a container, with its playable length.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Remuxed {
    pub bytes: Vec<u8>,
    /// Samples a player will actually output — pre-skip and end padding
    /// excluded — as milliseconds, rounded to the nearest.
    pub duration_ms: u64,
}

/// The container-neutral middle: what both formats agree an Opus stream is.
#[derive(Debug, Clone, PartialEq, Eq)]
struct OpusStream {
    channels: u8,
    /// Samples at 48 kHz decoded and thrown away before the first real one.
    pre_skip: u32,
    /// Samples at 48 kHz a player outputs after pre-skip.
    valid_samples: u64,
    packets: Vec<Vec<u8>>,
}

impl OpusStream {
    fn duration_ms(&self) -> u64 {
        (self.valid_samples * 1000 + u64::from(OPUS_RATE) / 2) / u64::from(OPUS_RATE)
    }
}

// ---- The Opus TOC byte ---------------------------------------------------

/// Samples (at 48 kHz) one Opus packet decodes to, from its TOC byte and, for
/// code 3, its frame-count byte (RFC 6716 §3.1). `None` for an empty packet,
/// a truncated code-3 packet, or one claiming more than the 120 ms the format
/// allows.
fn packet_samples(packet: &[u8]) -> Option<u32> {
    let toc = *packet.first()?;
    let config = toc >> 3;
    let frame = match config {
        // SILK-only: 10, 20, 40, 60 ms.
        0..=11 => [480, 960, 1920, 2880][usize::from(config % 4)],
        // Hybrid: 10, 20 ms.
        12..=15 => [480, 960][usize::from(config % 2)],
        // CELT-only: 2.5, 5, 10, 20 ms.
        _ => [120, 240, 480, 960][usize::from(config % 4)],
    };
    let frames = match toc & 0x3 {
        0 => 1,
        1 | 2 => 2,
        _ => u32::from(*packet.get(1)? & 0x3F),
    };
    let samples = frame * frames;
    (frames > 0 && samples <= 5760).then_some(samples)
}

/// Samples (at 48 kHz) in one frame of a packet with this TOC byte.
fn frame_samples(toc: u8) -> u32 {
    // A code-0 packet is one frame, so its length is the frame's.
    packet_samples(&[toc & !0x3]).expect("every configuration has a frame size")
}

/// The most bytes one Opus frame may hold (RFC 6716 §3.2.1, R2).
const MAX_FRAME_BYTES: usize = 1275;

/// The most frames one Opus packet may hold: 120 ms of 2.5 ms frames.
const MAX_FRAMES: usize = 48;

/// A frame length (RFC 6716 §3.2.1): one byte below 252, else two.
fn read_frame_len(packet: &[u8], at: &mut usize) -> Option<usize> {
    let first = usize::from(*packet.get(*at)?);
    *at += 1;
    if first < 252 {
        return Some(first);
    }
    let second = usize::from(*packet.get(*at)?);
    *at += 1;
    Some(second * 4 + first)
}

fn write_frame_len(out: &mut Vec<u8>, len: usize) {
    if len < 252 {
        out.push(len as u8);
    } else {
        let first = 252 + (len & 0x3);
        out.push(first as u8);
        out.push(((len - first) / 4) as u8);
    }
}

/// A packet's TOC byte and its frames (RFC 6716 §3.2), padding removed.
/// `None` for anything the RFC calls malformed (R1–R7).
fn opus_frames(packet: &[u8]) -> Option<(u8, Vec<&[u8]>)> {
    let toc = *packet.first()?;
    let body = &packet[1..];
    let frames = match toc & 0x3 {
        0 => vec![body],
        1 => {
            if !body.len().is_multiple_of(2) {
                return None;
            }
            let (a, b) = body.split_at(body.len() / 2);
            vec![a, b]
        }
        2 => {
            let mut at = 0;
            let first = read_frame_len(body, &mut at)?;
            let rest = body.get(at..)?;
            if first > rest.len() {
                return None;
            }
            let (a, b) = rest.split_at(first);
            vec![a, b]
        }
        _ => {
            let header = *body.first()?;
            let vbr = header & 0x80 != 0;
            let padded = header & 0x40 != 0;
            let count = usize::from(header & 0x3F);
            if count == 0 || count > MAX_FRAMES {
                return None;
            }
            let mut at = 1;
            let mut padding = 0usize;
            if padded {
                loop {
                    let byte = *body.get(at)?;
                    at += 1;
                    padding += if byte == 255 { 254 } else { usize::from(byte) };
                    if byte != 255 {
                        break;
                    }
                }
            }
            let mut lengths = Vec::with_capacity(count);
            if vbr {
                for _ in 1..count {
                    lengths.push(read_frame_len(body, &mut at)?);
                }
            }
            let end = body.len().checked_sub(padding)?;
            let data = body.get(at..end)?;
            if vbr {
                let given: usize = lengths.iter().sum();
                lengths.push(data.len().checked_sub(given)?);
            } else {
                if data.len() % count != 0 {
                    return None;
                }
                lengths = vec![data.len() / count; count];
            }
            let mut frames = Vec::with_capacity(count);
            let mut from = 0;
            for len in lengths {
                frames.push(&data[from..from + len]);
                from += len;
            }
            frames
        }
    };
    if frames.iter().any(|f| f.len() > MAX_FRAME_BYTES)
        || frames.len() as u32 * frame_samples(toc) > 5760
    {
        return None;
    }
    Some((toc, frames))
}

/// A code-3, variable-size packet (RFC 6716 §3.2.5) holding `frames`, all
/// in the configuration `toc` names. `None` past the format's limits.
fn opus_code3_packet(toc: u8, frames: &[&[u8]]) -> Option<Vec<u8>> {
    if frames.is_empty()
        || frames.len() > MAX_FRAMES
        || frames.len() as u32 * frame_samples(toc) > 5760
        || frames.iter().any(|f| f.len() > MAX_FRAME_BYTES)
    {
        return None;
    }
    let data: usize = frames.iter().map(|f| f.len()).sum();
    let mut out = Vec::with_capacity(2 + 2 * frames.len() + data);
    out.push((toc & !0x3) | 0x3);
    out.push(0x80 | frames.len() as u8);
    for frame in &frames[..frames.len() - 1] {
        write_frame_len(&mut out, frame.len());
    }
    for frame in frames {
        out.extend_from_slice(frame);
    }
    Some(out)
}

// ---- CAF -----------------------------------------------------------------

/// Big-endian reader over a byte slice, failing rather than panicking.
struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, at: 0 }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .at
            .checked_add(n)
            .filter(|end| *end <= self.bytes.len())
            .ok_or_else(|| OpusContainerError::Malformed("ends early".into()))?;
        let slice = &self.bytes[self.at..end];
        self.at = end;
        Ok(slice)
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(
            self.take(4)?.try_into().expect("4 bytes"),
        ))
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(
            self.take(4)?.try_into().expect("4 bytes"),
        ))
    }

    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(
            self.take(8)?.try_into().expect("8 bytes"),
        ))
    }

    fn f64(&mut self) -> Result<f64> {
        Ok(f64::from_be_bytes(
            self.take(8)?.try_into().expect("8 bytes"),
        ))
    }

    /// CAF's variable-length integer: 7 bits a byte, high bit set on every
    /// byte but the last. Capped at five bytes, which is past `u32::MAX`.
    fn varint(&mut self) -> Result<u64> {
        let mut value: u64 = 0;
        for _ in 0..5 {
            let byte = self.take(1)?[0];
            value = (value << 7) | u64::from(byte & 0x7F);
            if byte & 0x80 == 0 {
                return Ok(value);
            }
        }
        Err(OpusContainerError::Malformed("packet size too long".into()))
    }

    fn remaining(&self) -> usize {
        self.bytes.len() - self.at
    }
}

fn write_varint(out: &mut Vec<u8>, value: u64) {
    let mut groups = vec![(value & 0x7F) as u8];
    let mut rest = value >> 7;
    while rest > 0 {
        groups.push(((rest & 0x7F) as u8) | 0x80);
        rest >>= 7;
    }
    out.extend(groups.iter().rev());
}

/// The CAF chunks an Opus file needs, read out of whatever else is there.
struct CafParts<'a> {
    sample_rate: f64,
    frames_per_packet: u32,
    bytes_per_packet: u32,
    channels: u32,
    /// `(packets, valid frames, priming, remainder, descriptions)`.
    pakt: Option<(i64, i64, i32, i32, &'a [u8])>,
    /// The audio bytes, after the edit count.
    data: &'a [u8],
}

fn parse_caf(bytes: &[u8]) -> Result<CafParts<'_>> {
    let mut r = Reader::new(bytes);
    if r.take(4).map_err(|_| OpusContainerError::NotCaf)? != b"caff" {
        return Err(OpusContainerError::NotCaf);
    }
    r.take(4)?; // version, flags

    let mut desc = None;
    let mut pakt = None;
    let mut data = None;
    while r.remaining() >= 12 {
        let kind: [u8; 4] = r.take(4)?.try_into().expect("4 bytes");
        let size = r.i64()?;
        // Only `data` may say -1 ("to the end of the file"), and only because
        // a recorder writes the header before it knows how long it will run.
        let body = if size == -1 && &kind == b"data" {
            let rest = r.remaining();
            r.take(rest)?
        } else {
            let size = usize::try_from(size)
                .map_err(|_| OpusContainerError::Malformed("negative chunk size".into()))?;
            r.take(size)?
        };
        match &kind {
            b"desc" => {
                let mut d = Reader::new(body);
                let sample_rate = d.f64()?;
                let format = d.take(4)?;
                if format != b"opus" {
                    return Err(OpusContainerError::NotOpus);
                }
                let _flags = d.u32()?;
                let bytes_per_packet = d.u32()?;
                let frames_per_packet = d.u32()?;
                let channels = d.u32()?;
                desc = Some((sample_rate, bytes_per_packet, frames_per_packet, channels));
            }
            b"pakt" => {
                let mut p = Reader::new(body);
                let packets = p.i64()?;
                let valid = p.i64()?;
                let priming = p.i32()?;
                let remainder = p.i32()?;
                pakt = Some((packets, valid, priming, remainder, &body[24..]));
            }
            b"data" => {
                if body.len() < 4 {
                    return Err(OpusContainerError::Malformed("empty data chunk".into()));
                }
                data = Some(&body[4..]);
            }
            _ => {}
        }
    }

    let (sample_rate, bytes_per_packet, frames_per_packet, channels) =
        desc.ok_or_else(|| OpusContainerError::Malformed("no desc chunk".into()))?;
    Ok(CafParts {
        sample_rate,
        frames_per_packet,
        bytes_per_packet,
        channels,
        pakt,
        data: data.ok_or_else(|| OpusContainerError::Malformed("no data chunk".into()))?,
    })
}

fn stream_from_caf(bytes: &[u8]) -> Result<OpusStream> {
    let caf = parse_caf(bytes)?;
    let channels = u8::try_from(caf.channels)
        .ok()
        .filter(|c| (1..=2).contains(c))
        .ok_or_else(|| OpusContainerError::Unsupported(format!("{} channels", caf.channels)))?;
    // Frames in CAF are counted at the file's own rate; Ogg counts at 48 kHz.
    let scale = match caf.sample_rate as u32 {
        rate @ (8_000 | 12_000 | 16_000 | 24_000 | 48_000)
            if f64::from(rate) == caf.sample_rate =>
        {
            OPUS_RATE / rate
        }
        _ => {
            return Err(OpusContainerError::Unsupported(format!(
                "a {} Hz Opus stream",
                caf.sample_rate
            )))
        }
    };
    if caf.bytes_per_packet != 0 {
        return Err(OpusContainerError::Unsupported(
            "constant-size Opus packets".into(),
        ));
    }
    let (count, valid, priming, remainder, table) = caf
        .pakt
        .ok_or_else(|| OpusContainerError::Malformed("no packet table".into()))?;
    let count = usize::try_from(count)
        .ok()
        .filter(|c| *c <= MAX_PACKETS)
        .ok_or_else(|| OpusContainerError::Malformed(format!("{count} packets")))?;

    let mut table = Reader::new(table);
    let mut data = Reader::new(caf.data);
    let mut packets = Vec::with_capacity(count);
    let mut total_frames: u64 = 0;
    for _ in 0..count {
        let size = usize::try_from(table.varint()?)
            .map_err(|_| OpusContainerError::Malformed("packet size".into()))?;
        let frames = if caf.frames_per_packet == 0 {
            table.varint()?
        } else {
            u64::from(caf.frames_per_packet)
        };
        total_frames += frames;
        packets.push(data.take(size)?.to_vec());
    }

    let priming = u64::try_from(priming).unwrap_or(0);
    let remainder = u64::try_from(remainder).unwrap_or(0);
    // The table's own count is the authority; the header's `valid` is
    // checked against it rather than trusted over it.
    let valid = u64::try_from(valid)
        .ok()
        .filter(|v| *v <= total_frames)
        .unwrap_or_else(|| total_frames.saturating_sub(priming + remainder));
    let pre_skip = u32::try_from(priming * u64::from(scale))
        .map_err(|_| OpusContainerError::Malformed("priming".into()))?;
    Ok(OpusStream {
        channels,
        pre_skip,
        valid_samples: valid * u64::from(scale),
        packets,
    })
}

/// Apple's Opus magic cookie, as `afconvert` and `AVAudioRecorder` write it:
/// seven big-endian words — application (2048, voice), sample rate, frame
/// size, bitrate (-1000, auto), channels, and two zeroes. Not documented;
/// read off Apple's own output and written back the same way so its decoder
/// meets nothing it has not seen before.
fn apple_opus_cookie(frames_per_packet: u32, channels: u8) -> Vec<u8> {
    let words: [i32; 7] = [
        2048,
        OPUS_RATE as i32,
        frames_per_packet as i32,
        -1000,
        i32::from(channels),
        0,
        0,
    ];
    words.iter().flat_map(|w| w.to_be_bytes()).collect()
}

fn caf_chunk(out: &mut Vec<u8>, kind: &[u8; 4], body: &[u8]) {
    out.extend_from_slice(kind);
    out.extend_from_slice(&(body.len() as i64).to_be_bytes());
    out.extend_from_slice(body);
}

/// `packet` grown to `target` samples by empty frames — before its own when
/// `front`, after them otherwise — as one code-3 packet in the same
/// configuration. Its audio bytes are untouched. `None` when its frames do
/// not divide `target`, or the result would pass the format's limits.
fn pad_packet(packet: &[u8], target: u32, front: bool) -> Option<Vec<u8>> {
    let (toc, frames) = opus_frames(packet)?;
    let per_frame = frame_samples(toc);
    let have = frames.len() as u32 * per_frame;
    if target <= have || !(target - have).is_multiple_of(per_frame) {
        return None;
    }
    let empty = std::iter::repeat_n(&[][..], ((target - have) / per_frame) as usize);
    let all: Vec<&[u8]> = if front {
        empty.chain(frames).collect()
    } else {
        frames.into_iter().chain(empty).collect()
    };
    opus_code3_packet(toc, &all)
}

/// The stream with every packet the same length, where that costs no audio.
///
/// Apple's Opus decoder reads a CAF by the `desc` chunk's frames per packet.
/// Given 0 — "each packet says its own", which the format allows — it
/// refuses a short file outright (`AVAudioPlayer.prepareToPlay()` is false)
/// and silently drops a long one's odd packet. libsndfile (AgentPod's speech
/// service) ends every stream on a short packet: the tail that does not
/// fill a 20 ms frame goes out as a 2.5, 5 or 10 ms one.
///
/// A packet at either **end** can be brought up to length without touching
/// its audio: the missing time is added as empty Opus frames (RFC 6716
/// §3.2.1 — length zero, which a decoder conceals), after the last packet's
/// frames, where the end trim already discards everything past the valid
/// samples, or before the first packet's, where the pre-skip is lengthened
/// by the same amount. A packet in the **middle** cannot — its padding would
/// be heard — so a stream that changes frame length mid-way is returned as
/// it is and gets a per-packet table.
fn with_uniform_packets(stream: &OpusStream) -> std::borrow::Cow<'_, OpusStream> {
    use std::borrow::Cow;

    let Some(lengths) = stream
        .packets
        .iter()
        .map(|p| packet_samples(p))
        .collect::<Option<Vec<u32>>>()
    else {
        // The caller refuses a packet without a TOC.
        return Cow::Borrowed(stream);
    };
    let n = lengths.len();
    if n < 2 || lengths.iter().all(|l| *l == lengths[0]) {
        return Cow::Borrowed(stream);
    }
    // What every packet must last: the middle's, which cannot change, or
    // with no middle, the longer end's.
    let middle = &lengths[1..n - 1];
    let target = match middle.first() {
        Some(first) if middle.iter().all(|l| l == first) => *first,
        Some(_) => return Cow::Borrowed(stream),
        None => lengths[0].max(lengths[1]),
    };

    let mut uniform = stream.clone();
    for (index, front) in [(0, true), (n - 1, false)] {
        if lengths[index] == target {
            continue;
        }
        let Some(padded) = pad_packet(&stream.packets[index], target, front) else {
            return Cow::Borrowed(stream);
        };
        uniform.packets[index] = padded;
        if front {
            uniform.pre_skip += target - lengths[index];
        }
    }
    Cow::Owned(uniform)
}

fn stream_to_caf(stream: &OpusStream) -> Result<Vec<u8>> {
    let stream = &*with_uniform_packets(stream);
    let frames: Vec<u32> = stream
        .packets
        .iter()
        .map(|p| packet_samples(p))
        .collect::<Option<_>>()
        .ok_or_else(|| OpusContainerError::Malformed("an Opus packet has no valid TOC".into()))?;
    // One size for every packet is what Apple's own files have, and what
    // lets the table carry sizes only. A stream that changes frame size
    // mid-way (legal, rare) gets a per-packet frame count instead.
    let constant = frames
        .first()
        .copied()
        .filter(|first| frames.iter().all(|f| f == first));
    let frames_per_packet = constant.unwrap_or(0);
    let total: u64 = frames.iter().map(|f| u64::from(*f)).sum();
    let pre_skip = u64::from(stream.pre_skip).min(total);
    let valid = stream.valid_samples.min(total - pre_skip);
    let remainder = total - pre_skip - valid;

    let mut out = Vec::new();
    out.extend_from_slice(b"caff");
    out.extend_from_slice(&1u16.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes());

    let mut desc = Vec::with_capacity(32);
    desc.extend_from_slice(&f64::from(OPUS_RATE).to_be_bytes());
    desc.extend_from_slice(b"opus");
    desc.extend_from_slice(&0u32.to_be_bytes()); // format flags
    desc.extend_from_slice(&0u32.to_be_bytes()); // bytes per packet: variable
    desc.extend_from_slice(&frames_per_packet.to_be_bytes());
    desc.extend_from_slice(&u32::from(stream.channels).to_be_bytes());
    desc.extend_from_slice(&0u32.to_be_bytes()); // bits per channel
    caf_chunk(&mut out, b"desc", &desc);

    caf_chunk(
        &mut out,
        b"kuki",
        &apple_opus_cookie(constant.unwrap_or(960), stream.channels),
    );

    // kAudioChannelLayoutTag_Mono / _Stereo, no bitmap, no descriptions.
    let layout_tag: u32 = if stream.channels == 1 {
        (100 << 16) | 1
    } else {
        (101 << 16) | 2
    };
    let mut chan = Vec::with_capacity(12);
    chan.extend_from_slice(&layout_tag.to_be_bytes());
    chan.extend_from_slice(&0u32.to_be_bytes());
    chan.extend_from_slice(&0u32.to_be_bytes());
    caf_chunk(&mut out, b"chan", &chan);

    let mut pakt = Vec::new();
    pakt.extend_from_slice(&(stream.packets.len() as i64).to_be_bytes());
    pakt.extend_from_slice(&(valid as i64).to_be_bytes());
    pakt.extend_from_slice(&(pre_skip as i32).to_be_bytes());
    pakt.extend_from_slice(&(remainder as i32).to_be_bytes());
    for (packet, samples) in stream.packets.iter().zip(&frames) {
        write_varint(&mut pakt, packet.len() as u64);
        if constant.is_none() {
            write_varint(&mut pakt, u64::from(*samples));
        }
    }
    caf_chunk(&mut out, b"pakt", &pakt);

    let audio: usize = stream.packets.iter().map(Vec::len).sum();
    let mut data = Vec::with_capacity(4 + audio);
    data.extend_from_slice(&0u32.to_be_bytes()); // edit count
    for packet in &stream.packets {
        data.extend_from_slice(packet);
    }
    caf_chunk(&mut out, b"data", &data);
    Ok(out)
}

// ---- Ogg -----------------------------------------------------------------

/// RFC 7845 §5.1. Version 1, channel mapping family 0 (mono or stereo).
fn opus_head(channels: u8, pre_skip: u32) -> Vec<u8> {
    let mut head = Vec::with_capacity(19);
    head.extend_from_slice(b"OpusHead");
    head.push(1);
    head.push(channels);
    head.extend_from_slice(&(pre_skip as u16).to_le_bytes());
    head.extend_from_slice(&OPUS_RATE.to_le_bytes()); // input rate, informational
    head.extend_from_slice(&0i16.to_le_bytes()); // output gain
    head.push(0); // mapping family
    head
}

/// RFC 7845 §5.2: a vendor string and no comments.
fn opus_tags() -> Vec<u8> {
    let vendor = b"supermessage";
    let mut tags = Vec::with_capacity(8 + 4 + vendor.len() + 4);
    tags.extend_from_slice(b"OpusTags");
    tags.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    tags.extend_from_slice(vendor);
    tags.extend_from_slice(&0u32.to_le_bytes());
    tags
}

/// Roughly a second of 20 ms packets to a page — what libopus's own tools
/// aim for, and small enough that a player can start before the end.
const PACKETS_PER_PAGE: usize = 50;

fn stream_to_ogg(stream: &OpusStream, serial: u32) -> Result<Vec<u8>> {
    use ogg::writing::{PacketWriteEndInfo, PacketWriter};

    if stream.packets.is_empty() {
        return Err(OpusContainerError::Malformed("no audio".into()));
    }
    let pre_skip = u16::try_from(stream.pre_skip)
        .map_err(|_| OpusContainerError::Unsupported("pre-skip over 65535".into()))?;
    let mut out = Vec::new();
    {
        let mut writer = PacketWriter::new(Cursor::new(&mut out));
        let io = |e: std::io::Error| OpusContainerError::Malformed(e.to_string());
        writer
            .write_packet(
                opus_head(stream.channels, u32::from(pre_skip)),
                serial,
                PacketWriteEndInfo::EndPage,
                0,
            )
            .map_err(io)?;
        writer
            .write_packet(opus_tags(), serial, PacketWriteEndInfo::EndPage, 0)
            .map_err(io)?;

        // A page's granule position is the sample count (at 48 kHz, pre-skip
        // included) at the end of its last packet; the final page's is cut
        // back to the playable end, which is how Ogg says "trim the padding".
        let end = u64::from(pre_skip) + stream.valid_samples;
        let mut granule: u64 = 0;
        let last = stream.packets.len() - 1;
        for (i, packet) in stream.packets.iter().enumerate() {
            let samples = packet_samples(packet).ok_or_else(|| {
                OpusContainerError::Malformed("an Opus packet has no valid TOC".into())
            })?;
            granule = (granule + u64::from(samples)).min(end);
            let info = if i == last {
                PacketWriteEndInfo::EndStream
            } else if (i + 1) % PACKETS_PER_PAGE == 0 {
                PacketWriteEndInfo::EndPage
            } else {
                PacketWriteEndInfo::NormalPacket
            };
            writer
                .write_packet(
                    packet.clone(),
                    serial,
                    info,
                    if i == last { end } else { granule },
                )
                .map_err(io)?;
        }
    }
    Ok(out)
}

fn stream_from_ogg(bytes: &[u8]) -> Result<OpusStream> {
    use ogg::reading::PacketReader;

    if !bytes.starts_with(b"OggS") {
        return Err(OpusContainerError::NotOgg);
    }
    let malformed = |e: ogg::OggReadError| OpusContainerError::Malformed(e.to_string());
    let mut reader = PacketReader::new(Cursor::new(bytes));

    let head = reader
        .read_packet()
        .map_err(malformed)?
        .ok_or_else(|| OpusContainerError::Malformed("empty".into()))?;
    let serial = head.stream_serial();
    let h = &head.data;
    if h.len() < 19 || &h[..8] != b"OpusHead" {
        return Err(OpusContainerError::NotOpus);
    }
    if h[8] >> 4 != 0 {
        return Err(OpusContainerError::Unsupported(format!(
            "OpusHead version {}",
            h[8]
        )));
    }
    let channels = h[9];
    let pre_skip = u32::from(u16::from_le_bytes([h[10], h[11]]));
    let family = h[18];
    // Family 0 is mono or stereo with nothing more to say. Family 1 with one
    // or two channels is the same audio with a mapping table attached, which
    // a CAF file has nowhere to put and no need for when coupling is trivial.
    if !(1..=2).contains(&channels) || family > 1 {
        return Err(OpusContainerError::Unsupported(format!(
            "{channels} channels, mapping family {family}"
        )));
    }
    if family == 1 {
        let streams = h.get(19).copied();
        let coupled = h.get(20).copied();
        if streams != Some(1) || coupled != Some(channels - 1) {
            return Err(OpusContainerError::Unsupported("multistream Opus".into()));
        }
    }

    let mut packets = Vec::new();
    let mut last_granule: Option<u64> = None;
    let mut seen_tags = false;
    while let Some(packet) = reader.read_packet().map_err(malformed)? {
        // Another logical stream multiplexed in (a video, a second track) is
        // not ours to play.
        if packet.stream_serial() != serial {
            continue;
        }
        if !seen_tags {
            seen_tags = true;
            if packet.data.starts_with(b"OpusTags") {
                continue;
            }
            return Err(OpusContainerError::Malformed("no OpusTags".into()));
        }
        if packets.len() >= MAX_PACKETS {
            return Err(OpusContainerError::Malformed("too many packets".into()));
        }
        if packet.last_in_page() {
            let g = packet.absgp_page();
            // -1 (all ones) means "no packet ends on this page".
            if g != u64::MAX {
                last_granule = Some(g);
            }
        }
        let end_of_stream = packet.last_in_stream();
        packets.push(packet.data);
        if end_of_stream {
            break;
        }
    }
    if packets.is_empty() {
        return Err(OpusContainerError::Malformed("no audio".into()));
    }

    let total: u64 = packets
        .iter()
        .map(|p| packet_samples(p).map(u64::from))
        .sum::<Option<u64>>()
        .ok_or_else(|| OpusContainerError::Malformed("an Opus packet has no valid TOC".into()))?;
    let decoded = total.saturating_sub(u64::from(pre_skip));
    let valid = last_granule
        .map(|g| g.saturating_sub(u64::from(pre_skip)).min(decoded))
        .unwrap_or(decoded);
    Ok(OpusStream {
        channels,
        pre_skip,
        valid_samples: valid,
        packets,
    })
}

// ---- The two directions --------------------------------------------------

/// A CAF/Opus recording (what `AVAudioRecorder` writes) as Ogg/Opus (what a
/// Matrix voice message is), with its length.
pub fn caf_to_ogg(caf: &[u8]) -> Result<Remuxed> {
    let stream = stream_from_caf(caf)?;
    // Any serial will do for a file with one stream; one derived from the
    // content keeps the output a pure function of the input.
    let serial = caf.iter().fold(0x811C_9DC5u32, |h, b| {
        (h ^ u32::from(*b)).wrapping_mul(0x0100_0193)
    });
    Ok(Remuxed {
        bytes: stream_to_ogg(&stream, serial)?,
        duration_ms: stream.duration_ms(),
    })
}

/// An Ogg/Opus voice note (MSC3245) as CAF/Opus, which `AVAudioPlayer` and
/// WebKit can play and Ogg they cannot, with its length.
pub fn ogg_to_caf(ogg: &[u8]) -> Result<Remuxed> {
    let stream = stream_from_ogg(ogg)?;
    Ok(Remuxed {
        bytes: stream_to_caf(&stream)?,
        duration_ms: stream.duration_ms(),
    })
}

/// The playable length of an Ogg/Opus file, without converting it.
pub fn ogg_duration_ms(ogg: &[u8]) -> Result<u64> {
    Ok(stream_from_ogg(ogg)?.duration_ms())
}

/// Whether `bytes` begin an Ogg stream whose first packet is an `OpusHead`.
/// A header check only — [`ogg_to_caf`] is what finds out if the rest holds.
pub fn is_ogg_opus(bytes: &[u8]) -> bool {
    // The first page's single packet starts after the 27-byte header and its
    // one-entry segment table.
    bytes.starts_with(b"OggS") && bytes.get(28..36) == Some(b"OpusHead")
}

/// Whether `bytes` begin a CAF file.
pub fn is_caf(bytes: &[u8]) -> bool {
    bytes.starts_with(b"caff")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 1.47 s of speech, encoded by Apple's own Opus encoder (`afconvert -f
    /// caff -d opus@48000 -c 1 -b 24000`) — the same encoder
    /// `AVAudioRecorder` drives, so this is the shape the composer produces.
    const APPLE_CAF: &[u8] = include_bytes!("../tests/fixtures/voice-apple.caf");
    /// The same speech encoded by libopus through ffmpeg: an Ogg/Opus file
    /// written by someone else's muxer, as Element's are.
    const LIBOPUS_OGG: &[u8] = include_bytes!("../tests/fixtures/voice-libopus.ogg");

    #[test]
    fn toc_bytes_decode_to_the_right_sample_counts() {
        // The TOC byte: five bits of config, one of stereo, two of code.
        let toc = |config: u8, code: u8| (config << 3) | code;
        // CELT 20 ms, one frame.
        assert_eq!(packet_samples(&[toc(31, 0)]), Some(960));
        // SILK 60 ms, two frames = 120 ms, the maximum.
        assert_eq!(packet_samples(&[toc(3, 1)]), Some(5760));
        // Hybrid 10 ms, code 3 with three frames.
        assert_eq!(packet_samples(&[toc(12, 3), 3]), Some(1440));
        // Code 3 claiming 49 × 20 ms: over the 120 ms limit.
        assert_eq!(packet_samples(&[toc(31, 3), 49]), None);
        // Code 3 without its count byte, and an empty packet.
        assert_eq!(packet_samples(&[toc(31, 3)]), None);
        assert_eq!(packet_samples(&[]), None);

        // Every config, one frame each: RFC 6716 table 2, in samples at 48 kHz.
        let expected: [u32; 32] = [
            480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880, // SILK
            480, 960, 480, 960, // Hybrid
            120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480,
            960, // CELT
        ];
        for (config, samples) in expected.iter().enumerate() {
            assert_eq!(
                packet_samples(&[toc(config as u8, 0)]),
                Some(*samples),
                "config {config}"
            );
        }
    }

    #[test]
    fn varints_round_trip() {
        for value in [0u64, 1, 127, 128, 300, 16_383, 16_384, u32::MAX as u64] {
            let mut out = Vec::new();
            write_varint(&mut out, value);
            assert_eq!(Reader::new(&out).varint().unwrap(), value, "{value}");
        }
    }

    #[test]
    fn an_apple_recording_becomes_ogg_opus_with_its_exact_length() {
        let caf = stream_from_caf(APPLE_CAF).unwrap();
        // `afinfo`: 70458 valid frames + 312 priming + 270 remainder.
        assert_eq!(caf.channels, 1);
        assert_eq!(caf.pre_skip, 312);
        assert_eq!(caf.valid_samples, 70_458);

        let ogg = caf_to_ogg(APPLE_CAF).unwrap();
        assert_eq!(ogg.duration_ms, 1_468);
        assert!(is_ogg_opus(&ogg.bytes));

        // Read back with the independent reader: same packets, same counts.
        let back = stream_from_ogg(&ogg.bytes).unwrap();
        assert_eq!(back.packets, caf.packets);
        assert_eq!(back.pre_skip, 312);
        assert_eq!(back.valid_samples, 70_458);
    }

    #[test]
    fn the_last_ogg_page_is_trimmed_to_the_playable_end() {
        let ogg = caf_to_ogg(APPLE_CAF).unwrap().bytes;
        let mut reader = ogg::reading::PacketReader::new(Cursor::new(&ogg));
        let mut last = None;
        while let Some(p) = reader.read_packet().unwrap() {
            if p.last_in_stream() {
                last = Some(p.absgp_page());
            }
        }
        // pre-skip + valid, not pre-skip + every decoded sample.
        assert_eq!(last, Some(312 + 70_458));
    }

    #[test]
    fn an_element_style_ogg_becomes_caf_and_keeps_every_packet() {
        let ogg = stream_from_ogg(LIBOPUS_OGG).unwrap();
        let caf = ogg_to_caf(LIBOPUS_OGG).unwrap();
        assert!(is_caf(&caf.bytes));
        assert_eq!(caf.duration_ms, ogg.duration_ms());
        let back = stream_from_caf(&caf.bytes).unwrap();
        assert_eq!(back.packets, ogg.packets);
        assert_eq!(back.pre_skip, ogg.pre_skip);
        assert_eq!(back.valid_samples, ogg.valid_samples);
    }

    #[test]
    fn a_libopus_file_reports_the_length_ffprobe_does() {
        // ffprobe: 1.47 s; libopus pre-skip is 312 at 48 kHz.
        let ms = ogg_duration_ms(LIBOPUS_OGG).unwrap();
        assert!((1_460..=1_475).contains(&ms), "{ms}");
    }

    #[test]
    fn the_wrong_file_is_refused_by_name_not_by_panic() {
        assert_eq!(caf_to_ogg(b"not audio"), Err(OpusContainerError::NotCaf));
        assert_eq!(ogg_to_caf(b"not audio"), Err(OpusContainerError::NotOgg));
        assert_eq!(caf_to_ogg(LIBOPUS_OGG), Err(OpusContainerError::NotCaf));
        assert_eq!(ogg_to_caf(APPLE_CAF), Err(OpusContainerError::NotOgg));
    }

    #[test]
    fn truncated_files_fail_cleanly_at_every_length() {
        for n in 0..APPLE_CAF.len() {
            let _ = caf_to_ogg(&APPLE_CAF[..n]);
        }
        for n in 0..LIBOPUS_OGG.len() {
            let _ = ogg_to_caf(&LIBOPUS_OGG[..n]);
        }
    }

    #[test]
    fn a_caf_holding_aac_is_not_opus() {
        let mut caf = APPLE_CAF.to_vec();
        let at = caf.windows(4).position(|w| w == b"opus").unwrap();
        caf[at..at + 4].copy_from_slice(b"aac ");
        assert_eq!(caf_to_ogg(&caf), Err(OpusContainerError::NotOpus));
    }

    #[test]
    fn a_packet_table_claiming_more_than_the_cap_is_refused_before_allocating() {
        let mut caf = APPLE_CAF.to_vec();
        let at = caf.windows(4).position(|w| w == b"pakt").unwrap() + 12;
        caf[at..at + 8].copy_from_slice(&(i64::MAX).to_be_bytes());
        assert!(matches!(
            caf_to_ogg(&caf),
            Err(OpusContainerError::Malformed(_))
        ));
    }

    #[test]
    fn surround_ogg_is_refused() {
        let mut stream = stream_from_ogg(LIBOPUS_OGG).unwrap();
        stream.channels = 6;
        let ogg = stream_to_ogg(&stream, 1).unwrap();
        assert!(matches!(
            ogg_to_caf(&ogg),
            Err(OpusContainerError::Unsupported(_))
        ));
    }

    // ---- Ogg written by libsndfile, as AgentPod's speech service writes it --

    /// Voice notes from AgentPod's speech service (Kokoro, then
    /// python-soundfile 0.14 / libsndfile 1.2.2, `format="OGG",
    /// subtype="OPUS"`): what the hub posts as an agent's spoken answer and
    /// what Hermes agents' voice notes are. The four long ones were produced
    /// by the service itself; `4s` is the same library over the service's own
    /// audio cut to 4.014 s, the length of the reply that would not play.
    ///
    /// libsndfile ends a stream on a *shorter* packet — the tail that does
    /// not fill a 20 ms frame is flushed as a 2.5, 5 or 10 ms one — and its
    /// OpusHead says 24 kHz input and carries a 791-byte padded OpusTags.
    ///
    /// `(fixture, packets, frame of the last packet, valid samples)`; the
    /// valid counts are the last page's granule minus the 312 pre-skip, as
    /// `ffprobe -show_packets` reads them.
    const LIBSNDFILE: [(&str, &[u8], usize, u32, u64); 5] = [
        (
            "agent-reply",
            include_bytes!("../tests/fixtures/voice-libsndfile-agent-reply.ogg"),
            1581,
            480,
            1_516_896,
        ),
        (
            "tricky",
            include_bytes!("../tests/fixtures/voice-libsndfile-tricky.ogg"),
            813,
            120,
            779_264,
        ),
        (
            "para",
            include_bytes!("../tests/fixtures/voice-libsndfile-para.ogg"),
            1345,
            960,
            1_290_848,
        ),
        (
            "blend",
            include_bytes!("../tests/fixtures/voice-libsndfile-blend.ogg"),
            1579,
            480,
            1_514_848,
        ),
        (
            "4s",
            include_bytes!("../tests/fixtures/voice-libsndfile-4s.ogg"),
            202,
            120,
            192_672,
        ),
    ];

    #[test]
    fn libsndfile_notes_are_read_packet_for_packet() {
        for (name, ogg, packets, last, valid) in LIBSNDFILE {
            let stream = stream_from_ogg(ogg).unwrap();
            assert_eq!(stream.channels, 1, "{name}");
            assert_eq!(stream.pre_skip, 312, "{name}");
            assert_eq!(stream.packets.len(), packets, "{name}");
            assert_eq!(stream.valid_samples, valid, "{name}");
            assert_eq!(
                packet_samples(stream.packets.last().unwrap()),
                Some(last),
                "{name}"
            );
        }
    }

    /// The bug of 2026-09-30: an agent's 4 s spoken reply showed "This audio
    /// can't be played here." on iOS. The remux wrote the short last packet
    /// into a CAF whose `desc` says 0 frames per packet (a per-packet table),
    /// and Apple's Opus decoder does not take that: `AVAudioPlayer`'s
    /// `prepareToPlay()` is false for a short note (iOS 18.6 and 26.5
    /// simulators, macOS 15.5), and a long one plays with its last packet
    /// silently dropped. Every CAF this writes must say one packet length,
    /// as Apple's own files do, and keep every sample.
    #[test]
    fn libsndfile_notes_become_caf_with_one_packet_length_and_every_sample() {
        for (name, ogg, packets, _, valid) in LIBSNDFILE {
            let caf = ogg_to_caf(ogg).unwrap();
            let parts = parse_caf(&caf.bytes).unwrap();
            assert_eq!(
                parts.frames_per_packet, 960,
                "{name}: desc frames per packet"
            );
            let (count, caf_valid, priming, remainder, _) = parts.pakt.unwrap();
            assert_eq!(count as usize, packets, "{name}: packet count");
            assert_eq!(caf_valid as u64, valid, "{name}: valid frames");
            assert_eq!(priming, 312, "{name}: priming");
            // The table's arithmetic holds: every packet is 960 frames.
            assert_eq!(
                caf_valid + i64::from(priming) + i64::from(remainder),
                count * 960,
                "{name}: valid + priming + remainder"
            );
            let back = stream_from_caf(&caf.bytes).unwrap();
            assert!(
                back.packets.iter().all(|p| packet_samples(p) == Some(960)),
                "{name}: a packet that is not 960 samples"
            );
            assert_eq!(back.valid_samples, valid, "{name}");
            assert_eq!(
                caf.duration_ms,
                (valid * 1000 + 24_000) / 48_000,
                "{name}: duration"
            );
        }
        // The reply's own length, as the hub's event said: 4014 ms.
        assert_eq!(ogg_to_caf(LIBSNDFILE[4].1).unwrap().duration_ms, 4_014);
    }

    #[test]
    fn a_padded_last_packet_keeps_its_audio_byte_for_byte() {
        let (_, ogg, ..) = LIBSNDFILE[4];
        let ogg_stream = stream_from_ogg(ogg).unwrap();
        let caf = stream_from_caf(&ogg_to_caf(ogg).unwrap().bytes).unwrap();
        let n = ogg_stream.packets.len();
        // Everything before the last packet leaves exactly as it came.
        assert_eq!(caf.packets[..n - 1], ogg_stream.packets[..n - 1]);
        // The last: the same TOC configuration, its one frame first and
        // unchanged, then empty frames — which a decoder conceals and the
        // end trim then discards.
        let (toc, frames) = opus_frames(ogg_stream.packets.last().unwrap()).unwrap();
        let (padded_toc, padded) = opus_frames(caf.packets.last().unwrap()).unwrap();
        assert_eq!(padded_toc >> 2, toc >> 2, "config and stereo bit");
        assert_eq!(padded[0], frames[0]);
        assert_eq!(padded.len(), 8, "eight 2.5 ms frames make 20 ms");
        assert!(padded[1..].iter().all(|f| f.is_empty()));
    }

    // ---- Opus framing (RFC 6716 §3.2) ---------------------------------------

    #[test]
    fn opus_packets_split_into_their_frames_for_every_code() {
        let toc = |config: u8, code: u8| (config << 3) | code;
        // The frames, owned, so a test can build a packet inline.
        let frames = |packet: &[u8]| -> Option<Vec<Vec<u8>>> {
            opus_frames(packet).map(|(_, f)| f.into_iter().map(<[u8]>::to_vec).collect())
        };
        // Code 0: one frame, and the TOC comes back as it was.
        assert_eq!(opus_frames(&[toc(31, 0), 1, 2, 3]).unwrap().0, toc(31, 0));
        assert_eq!(frames(&[toc(31, 0), 1, 2, 3]), Some(vec![vec![1, 2, 3]]));
        // Code 1: two frames of equal size; an odd remainder is invalid.
        assert_eq!(
            frames(&[toc(31, 1), 1, 2, 3, 4]),
            Some(vec![vec![1, 2], vec![3, 4]])
        );
        assert_eq!(frames(&[toc(31, 1), 1, 2, 3]), None);
        // Code 2: the first frame's length, then the rest — which may be
        // empty.
        assert_eq!(
            frames(&[toc(31, 2), 1, 9, 7, 7]),
            Some(vec![vec![9], vec![7, 7]])
        );
        assert_eq!(
            frames(&[toc(31, 2), 2, 9, 9]),
            Some(vec![vec![9, 9], vec![]])
        );
        // Code 3 CBR: three frames of two bytes.
        assert_eq!(
            frames(&[toc(31, 3), 3, 1, 1, 2, 2, 3, 3]),
            Some(vec![vec![1, 1], vec![2, 2], vec![3, 3]])
        );
        // Code 3 VBR with padding: 0xC2 = VBR, padded, two frames; one
        // byte of padding length (2), the first frame's length (1), then
        // frame 1, frame 2, and the two padding bytes.
        assert_eq!(
            frames(&[toc(31, 3), 0xC2, 2, 1, 5, 6, 6, 0, 0]),
            Some(vec![vec![5], vec![6, 6]])
        );
        // Padding length 255 means 254 and another byte follows.
        let mut long = vec![toc(31, 3), 0x41, 255, 1, 8];
        long.extend(std::iter::repeat_n(0, 255));
        assert_eq!(frames(&long), Some(vec![vec![8]]));
        // Two-byte lengths: 252 + 4 × 1 = 256.
        let mut two = vec![toc(31, 2), 252, 1];
        two.extend(std::iter::repeat_n(4, 256 + 3));
        let split = frames(&two).unwrap();
        assert_eq!((split[0].len(), split[1].len()), (256, 3));
        // Damaged: no bytes, a length past the end, a zero frame count, a
        // CBR remainder that does not divide, over 120 ms of frames.
        assert_eq!(frames(&[]), None);
        assert_eq!(frames(&[toc(31, 2), 9, 1]), None);
        assert_eq!(frames(&[toc(31, 3), 0x80]), None);
        assert_eq!(frames(&[toc(31, 3), 0x02, 1, 2, 3]), None);
        assert_eq!(frames(&[toc(31, 3), 0x07]), None);
    }

    #[test]
    fn code_three_packets_round_trip_through_the_writer() {
        let toc = (13 << 3) | 0x04; // hybrid 20 ms, stereo
        let big = vec![7u8; 1275];
        let cases: [&[&[u8]]; 4] = [
            &[&[1, 2, 3]],
            &[&[1, 2, 3], &[], &[]],
            &[&big, &[9; 251], &[9; 252], &[]],
            &[&[], &[], &[5]],
        ];
        for frames in cases {
            let packet = opus_code3_packet(toc, frames).unwrap();
            let (t, back) = opus_frames(&packet).unwrap();
            assert_eq!(t >> 2, toc >> 2);
            assert_eq!(back, frames.to_vec());
            assert_eq!(
                packet_samples(&packet),
                Some(960 * frames.len() as u32),
                "{} frames",
                frames.len()
            );
        }
        // Over 48 frames, or over 1275 bytes a frame, is not a packet.
        assert_eq!(opus_code3_packet(toc, &[&[][..]; 49]), None);
        assert_eq!(opus_code3_packet(toc, &[&[0u8; 1276][..]]), None);
    }

    #[test]
    fn a_short_first_packet_is_padded_in_front_and_the_pre_skip_grows() {
        let mut stream = stream_from_ogg(LIBOPUS_OGG).unwrap();
        // A 10 ms CELT packet first, as a muxer that starts on a short
        // frame would write: its frame is the audio, so the padding goes
        // before it, where the pre-skip hides it.
        let short = vec![26 << 3, 0xAB, 0xCD];
        stream.packets.insert(0, short.clone());
        stream.valid_samples += 480;
        let caf = stream_from_caf(&stream_to_caf(&stream).unwrap()).unwrap();
        assert_eq!(caf.pre_skip, 312 + 480);
        assert_eq!(caf.valid_samples, stream.valid_samples);
        let (_, frames) = opus_frames(&caf.packets[0]).unwrap();
        assert_eq!(frames, vec![&[][..], &[0xAB, 0xCD][..]]);
        assert_eq!(caf.packets[1..], stream.packets[1..]);
    }

    #[test]
    fn a_stream_that_changes_frame_length_midway_keeps_a_per_packet_table() {
        // Legal and rare: no packet can be padded without adding sound in
        // the middle, so the table carries each packet's own length.
        let mut stream = stream_from_ogg(LIBOPUS_OGG).unwrap();
        stream.packets[10] = vec![26 << 3, 0xAB];
        stream.valid_samples -= 480;
        let bytes = stream_to_caf(&stream).unwrap();
        let parts = parse_caf(&bytes).unwrap();
        assert_eq!(parts.frames_per_packet, 0);
        let back = stream_from_caf(&bytes).unwrap();
        assert_eq!(back.packets, stream.packets);
        assert_eq!(back.valid_samples, stream.valid_samples);
    }

    // ---- Ogg paging (RFC 3533) ------------------------------------------------

    /// Ogg's CRC: polynomial 0x04C11DB7, no reflection, zero start.
    fn ogg_crc(bytes: &[u8]) -> u32 {
        let mut crc = 0u32;
        for byte in bytes {
            crc ^= u32::from(*byte) << 24;
            for _ in 0..8 {
                crc = if crc & 0x8000_0000 != 0 {
                    (crc << 1) ^ 0x04C1_1DB7
                } else {
                    crc << 1
                };
            }
        }
        crc
    }

    /// The same packets, paged the awkward ways RFC 3533 allows: pages cut
    /// every `body` bytes wherever that falls, so packets continue across
    /// pages and many share one; a page no packet ends on says granule -1.
    fn repage(stream: &OpusStream, pre_skip: u16, body: usize) -> Vec<u8> {
        let mut packets: Vec<(Vec<u8>, u64)> = vec![
            (opus_head(stream.channels, u32::from(pre_skip)), 0),
            (opus_tags(), 0),
        ];
        let end = u64::from(pre_skip) + stream.valid_samples;
        let mut granule = 0u64;
        for p in &stream.packets {
            granule += u64::from(packet_samples(p).unwrap());
            packets.push((p.clone(), granule.min(end)));
        }
        packets.last_mut().unwrap().1 = end;

        // Lace every packet: 255s then a terminator below 255.
        let mut segments: Vec<(u8, usize, bool, u64)> = Vec::new(); // (len, packet, ends, granule)
        for (i, (p, g)) in packets.iter().enumerate() {
            let mut left = p.len();
            loop {
                let take = left.min(255);
                left -= take;
                let ends = take < 255;
                segments.push((take as u8, i, ends, *g));
                if ends {
                    break;
                }
            }
        }
        let data: Vec<u8> = packets.iter().flat_map(|(p, _)| p.clone()).collect();

        let mut out = Vec::new();
        let (mut at, mut seg, mut seq, mut continued) = (0usize, 0usize, 0u32, false);
        while seg < segments.len() {
            let mut table = Vec::new();
            let mut size = 0usize;
            let mut granule = u64::MAX;
            while seg < segments.len() && table.len() < 255 {
                let (len, packet, ends, g) = segments[seg];
                table.push(len);
                size += usize::from(len);
                seg += 1;
                if ends {
                    granule = g;
                }
                // Each header ends its page (RFC 7845 §3); audio pages run
                // to about `body` bytes, wherever in a packet that falls.
                if (ends && packet < 2) || (packet >= 2 && size >= body) {
                    break;
                }
            }
            let mut header_type = 0u8;
            if continued {
                header_type |= 1;
            }
            if seq == 0 {
                header_type |= 2;
            }
            if seg == segments.len() {
                header_type |= 4;
            }
            continued = !segments[seg - 1].2;
            let mut page = Vec::new();
            page.extend_from_slice(b"OggS");
            page.push(0);
            page.push(header_type);
            page.extend_from_slice(&granule.to_le_bytes());
            page.extend_from_slice(&0x5EED_u32.to_le_bytes());
            page.extend_from_slice(&seq.to_le_bytes());
            page.extend_from_slice(&0u32.to_le_bytes());
            page.push(table.len() as u8);
            page.extend_from_slice(&table);
            page.extend_from_slice(&data[at..at + size]);
            let crc = ogg_crc(&page);
            page[22..26].copy_from_slice(&crc.to_le_bytes());
            out.extend_from_slice(&page);
            at += size;
            seq += 1;
        }
        out
    }

    #[test]
    fn packets_continued_across_pages_and_packed_many_to_a_page_read_the_same() {
        for (name, ogg, ..) in LIBSNDFILE {
            let stream = stream_from_ogg(ogg).unwrap();
            // 37 bytes: nearly every packet is split over two pages. 4000:
            // dozens of packets to a page, the first and last cut.
            for body in [37, 4000] {
                let paged = repage(&stream, 312, body);
                assert!(is_ogg_opus(&paged), "{name} at {body}");
                let back = stream_from_ogg(&paged).unwrap();
                assert_eq!(back, stream, "{name} paged at {body} bytes");
                assert_eq!(
                    ogg_to_caf(&paged).unwrap(),
                    ogg_to_caf(ogg).unwrap(),
                    "{name} paged at {body} bytes"
                );
            }
        }
    }

    /// The CAF the iOS Kit's player test opens (`VoiceFixturePlaybackTests`)
    /// is exactly what this remuxer writes today — so that test, which runs
    /// Apple's decoder where this crate cannot, is testing this code.
    /// Regenerate with the `remux_a_file_for_external_tools` line below.
    #[test]
    fn the_ios_player_fixture_is_what_the_remuxer_writes() {
        let fixture: &[u8] =
            include_bytes!("../../../apple/SupermessageKitTests/Fixtures/voice-libsndfile-4s.caf");
        assert!(
            ogg_to_caf(LIBSNDFILE[4].1).unwrap().bytes == fixture,
            "apple/SupermessageKitTests/Fixtures/voice-libsndfile-4s.caf is stale. From \
             crates/supermessage-core (where cargo runs a test): \
             SM_REMUX_IN=tests/fixtures/voice-libsndfile-4s.ogg \
             SM_REMUX_OUT=../../apple/SupermessageKitTests/Fixtures/voice-libsndfile-4s.caf \
             cargo test -p supermessage-core --lib remux_a_file_for_external_tools -- --ignored"
        );
    }

    /// Not a check on its own: converts a file for an outside tool to judge.
    /// `ffprobe`/`ffmpeg` read the Ogg this writes, and `afinfo`/`afconvert`
    /// (Apple's decoder, the one `AVAudioPlayer` uses) read the CAF.
    ///
    /// `SM_REMUX_IN=note.caf SM_REMUX_OUT=note.ogg cargo test -p
    /// supermessage-core --lib remux_a_file_for_external_tools -- --ignored`
    #[test]
    #[ignore = "writes a file for external tools; run by hand"]
    fn remux_a_file_for_external_tools() {
        let input = std::env::var("SM_REMUX_IN").expect("SM_REMUX_IN");
        let output = std::env::var("SM_REMUX_OUT").expect("SM_REMUX_OUT");
        let bytes = std::fs::read(&input).unwrap();
        let out = if is_caf(&bytes) {
            caf_to_ogg(&bytes)
        } else {
            ogg_to_caf(&bytes)
        }
        .unwrap();
        std::fs::write(&output, &out.bytes).unwrap();
        println!("{input} -> {output}: {} ms", out.duration_ms);
    }
}
