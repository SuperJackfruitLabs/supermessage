//! Resolves a Matrix `mxc://` URI to a `data:` URI the webview can render
//! directly.
//!
//! This homeserver is spec v1.11, where media moved to **authenticated**
//! endpoints requiring the access token as a header — there is deliberately
//! no `http(s)://` thumbnail URL that can be handed to a webview `<img
//! src>`. [`avatar_thumbnail`] instead uses `Client::media().get_media_content`,
//! which performs that authenticated fetch itself and returns decoded bytes,
//! and encodes the result as a `data:` URI.
//!
//! The SDK caches media in the same encrypted SQLite store the client is
//! already configured with (see `Session::build_client`), so repeat calls
//! for the same `mxc://` URI do not re-hit the network — no separate disk
//! cache is built here.
//!
//! This module only ever fetches an mxc URI it's handed — it has no opinion
//! on *which* mxc URI represents a room's avatar (that's
//! `core::rooms::resolve_room_avatar_mxc`'s job, since it needs the room's
//! member list, not just its own state). `Session::room_avatar` is what
//! wires the two together: resolve, then fetch via
//! [`avatar_thumbnail`]. Keeping the fetch keyed purely on the mxc URI
//! itself (never a room id) is also what lets this same function serve a
//! user avatar identically once the timeline needs one at M2.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use matrix_sdk::media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings};
use matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method;
use matrix_sdk::ruma::events::room::MediaSource;
use matrix_sdk::ruma::{OwnedMxcUri, UInt};
use matrix_sdk::Client;

use super::error::{CoreError, CoreResult};

/// Thumbnail dimensions requested for avatars. Sized generously above the
/// room list's 40px (`h-10 w-10`) circle to stay crisp at 2x DPI, while
/// staying a thumbnail request (not [`MediaFormat::File`]) — full-size
/// avatars can be megabytes, and nothing here needs more than a small
/// circle's worth of detail.
const AVATAR_THUMBNAIL_SIZE: u16 = 96;

/// Thumbnail dimensions requested for an inline message image
/// (`Timeline.svelte`'s `render: "image"` row). Sized above the webview-side
/// display cap (`IMAGE_MAX_WIDTH`/`IMAGE_MAX_HEIGHT` in `Timeline.svelte`,
/// 320px) for 2x-DPI crispness, while staying a thumbnail request — the
/// same reasoning as [`AVATAR_THUMBNAIL_SIZE`], just for a bigger box: a
/// full-size photo can run to many megabytes, and nothing this pass renders
/// needs more detail than a bubble-sized thumbnail carries.
const MESSAGE_THUMBNAIL_SIZE: u16 = 640;

/// Fetches `mxc_uri` as a thumbnail and encodes it as a `data:` URI.
///
/// Returns `Ok(None)` when the fetched bytes don't sniff to a known image
/// format (see [`sniff_mime`]), so the webview is never handed a `data:` URI
/// it can't render rather than one guessing a MIME type that turns out
/// wrong. A genuinely missing/unreachable media item surfaces as `Err`
/// instead — `mxc_uri` here is always one `resolve_room_avatar_mxc` already
/// resolved to *some* real avatar source before calling this (the room's
/// own avatar, a hero's, or a member's), so "there is no avatar" is decided
/// before this function is ever called; a fetch failure past that point is a
/// real error, not an expected "no avatar" case.
///
/// A thin wrapper over [`fetch_thumbnail`], fixing the source to
/// `MediaSource::Plain` — correct here because a room/member avatar is
/// always unencrypted in this deployment's model (`resolve_room_avatar_mxc`
/// only ever resolves a plain mxc URI, never an `EncryptedFile`). Message
/// media (see [`message_media_thumbnail`]) can be either, which is exactly
/// why that one takes a full `MediaSource` instead of also assuming `Plain`.
pub async fn avatar_thumbnail(client: &Client, mxc_uri: &str) -> CoreResult<Option<String>> {
    fetch_thumbnail(
        client,
        MediaSource::Plain(OwnedMxcUri::from(mxc_uri)),
        AVATAR_THUMBNAIL_SIZE,
    )
    .await
}

/// Fetches a message's media (an `m.image`/`m.file`/`m.audio`/`m.video`'s
/// `source`) as a thumbnail and encodes it as a `data:` URI, exactly like
/// [`avatar_thumbnail`] but sized for a bubble-width image instead of an
/// avatar circle.
///
/// Takes the real `MediaSource` — `Plain(OwnedMxcUri)` or
/// `Encrypted(Box<EncryptedFile>)` — rather than a bare mxc string, because
/// only the former can address encrypted media at all: `Client::media().
/// get_media_content` (called from [`fetch_thumbnail`]) transparently
/// decrypts an `Encrypted` source using the keys carried on the source
/// itself, but there is nothing to decrypt *with* if this function were
/// handed just the mxc URI. This deployment's rooms are unencrypted today
/// (every source that reaches this function is `Plain`), but the signature
/// makes an encrypted room work through the exact same path with no
/// redesign here — see `core::timeline::FocusedTimeline::media_source` for
/// where the `MediaSource` this function receives actually comes from (the
/// live timeline item, looked up by event id, never a cached mxc string).
pub async fn message_media_thumbnail(
    client: &Client,
    source: MediaSource,
) -> CoreResult<Option<String>> {
    fetch_thumbnail(client, source, MESSAGE_THUMBNAIL_SIZE).await
}

/// Shared implementation behind [`avatar_thumbnail`]/[`message_media_thumbnail`]:
/// fetches `source` as a `size`x`size` thumbnail and encodes the result as a
/// `data:` URI (`None` when the bytes don't sniff to a known image format —
/// see [`sniff_mime`]).
async fn fetch_thumbnail(
    client: &Client,
    source: MediaSource,
    size: u16,
) -> CoreResult<Option<String>> {
    let request = MediaRequestParameters {
        source,
        format: MediaFormat::Thumbnail(MediaThumbnailSettings::with_method(
            Method::Scale,
            UInt::from(size),
            UInt::from(size),
        )),
    };

    let bytes = client
        .media()
        .get_media_content(&request, true)
        .await
        .map_err(|e| CoreError::Network(e.to_string()))?;

    Ok(to_data_uri(&bytes))
}

/// Sniffs `bytes`' leading magic number to a MIME type.
///
/// Pure and SDK-free on purpose, so it's unit-testable without a live
/// homeserver or a real fetched image. `get_media_content` returns raw bytes
/// with no content type attached, so this is what stands between "some
/// bytes" and a `data:` URI that actually needs a real MIME type to render.
/// Returns `None` for anything that doesn't match a known signature rather
/// than guessing — see [`avatar_thumbnail`]'s doc comment for why that
/// matters.
pub fn sniff_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG") {
        Some("image/png")
    } else if bytes.starts_with(b"\xFF\xD8\xFF") {
        Some("image/jpeg")
    } else if bytes.starts_with(b"GIF8") {
        Some("image/gif")
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

/// Base64-encodes `bytes` into a `data:` URI, or `None` when [`sniff_mime`]
/// can't identify the format.
fn to_data_uri(bytes: &[u8]) -> Option<String> {
    let mime = sniff_mime(bytes)?;
    let encoded = BASE64.encode(bytes);
    Some(format!("data:{mime};base64,{encoded}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sniffs_png() {
        let bytes = b"\x89PNG\r\n\x1a\nrest-of-file";
        assert_eq!(sniff_mime(bytes), Some("image/png"));
    }

    #[test]
    fn sniffs_jpeg() {
        let bytes = b"\xFF\xD8\xFFrest-of-file";
        assert_eq!(sniff_mime(bytes), Some("image/jpeg"));
    }

    #[test]
    fn sniffs_gif87a() {
        let bytes = b"GIF87arest-of-file";
        assert_eq!(sniff_mime(bytes), Some("image/gif"));
    }

    #[test]
    fn sniffs_gif89a() {
        let bytes = b"GIF89arest-of-file";
        assert_eq!(sniff_mime(bytes), Some("image/gif"));
    }

    #[test]
    fn sniffs_webp() {
        let mut bytes = b"RIFF".to_vec();
        bytes.extend_from_slice(&[0, 0, 0, 0]); // chunk size, irrelevant here
        bytes.extend_from_slice(b"WEBPrest-of-file");
        assert_eq!(sniff_mime(&bytes), Some("image/webp"));
    }

    #[test]
    fn rejects_riff_that_is_not_webp() {
        // A RIFF container that isn't WebP (e.g. a WAV file) must not be
        // mistaken for one just because it starts with "RIFF".
        let mut bytes = b"RIFF".to_vec();
        bytes.extend_from_slice(&[0, 0, 0, 0]);
        bytes.extend_from_slice(b"WAVEfmt ");
        assert_eq!(sniff_mime(&bytes), None);
    }

    #[test]
    fn rejects_unknown_bytes_instead_of_guessing() {
        assert_eq!(sniff_mime(b"not an image"), None);
        assert_eq!(sniff_mime(b""), None);
    }

    #[test]
    fn rejects_a_riff_header_too_short_to_carry_a_format_tag() {
        // Fewer than 12 bytes: indexing bytes[8..12] would panic without the
        // length guard.
        assert_eq!(sniff_mime(b"RIFF\x00\x00"), None);
    }

    #[test]
    fn encodes_a_known_format_as_a_data_uri() {
        let bytes = b"\x89PNGrest-of-file";
        let uri = to_data_uri(bytes).expect("PNG bytes must sniff successfully");
        assert!(uri.starts_with("data:image/png;base64,"));

        // Round-trip: decoding the base64 payload back out must reproduce
        // the original bytes exactly.
        let payload = uri.strip_prefix("data:image/png;base64,").unwrap();
        let decoded = BASE64.decode(payload).unwrap();
        assert_eq!(decoded, bytes);
    }

    #[test]
    fn refuses_to_encode_bytes_that_do_not_sniff_to_a_known_format() {
        assert_eq!(to_data_uri(b"definitely not an image"), None);
    }
}
