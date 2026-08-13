//! Resolves a room's `mxc://` avatar to a `data:` URI the webview can render
//! directly.
//!
//! This homeserver is spec v1.11, where media moved to **authenticated**
//! endpoints requiring the access token as a header — there is deliberately
//! no `http(s)://` thumbnail URL that can be handed to a webview `<img
//! src>`. [`room_avatar`] instead uses `matrix_sdk::Room::avatar`, which
//! performs that authenticated fetch itself and returns decoded bytes (or
//! `None` when the room has no avatar), and encodes the result as a `data:`
//! URI.
//!
//! The SDK caches media in the same encrypted SQLite store the client is
//! already configured with (see `Session::build_client`), so repeat calls
//! for the same avatar do not re-hit the network — no separate disk cache is
//! built here.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use matrix_sdk::media::{MediaFormat, MediaThumbnailSettings};
use matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method;
use matrix_sdk::ruma::{RoomId, UInt};
use matrix_sdk::Client;

use super::error::{CoreError, CoreResult};

/// Thumbnail dimensions requested for room avatars. Sized generously above
/// the room list's 40px (`h-10 w-10`) circle to stay crisp at 2x DPI, while
/// staying a thumbnail request (not [`MediaFormat::File`]) — full-size
/// avatars can be megabytes, and nothing here needs more than a small
/// circle's worth of detail.
const AVATAR_THUMBNAIL_SIZE: u16 = 96;

/// Fetches `room_id`'s avatar as a thumbnail and encodes it as a `data:`
/// URI.
///
/// Returns `Ok(None)` both when the room has no avatar set (`Room::avatar`
/// itself returns `None`) and when the fetched bytes don't sniff to a known
/// image format (see [`sniff_mime`]) — the latter so the webview is never
/// handed a `data:` URI it can't render, rather than one guessing a MIME
/// type that turns out wrong.
pub async fn room_avatar(client: &Client, room_id: &str) -> CoreResult<Option<String>> {
    let parsed_room_id = RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
    let room = client
        .get_room(&parsed_room_id)
        .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

    let format = MediaFormat::Thumbnail(MediaThumbnailSettings::with_method(
        Method::Scale,
        UInt::from(AVATAR_THUMBNAIL_SIZE),
        UInt::from(AVATAR_THUMBNAIL_SIZE),
    ));

    let bytes = room
        .avatar(format)
        .await
        .map_err(|e| CoreError::Network(e.to_string()))?;

    Ok(bytes.as_deref().and_then(to_data_uri))
}

/// Sniffs `bytes`' leading magic number to a MIME type.
///
/// Pure and SDK-free on purpose, so it's unit-testable without a live
/// homeserver or a real fetched image. `Room::avatar` returns raw bytes with
/// no content type attached, so this is what stands between "some bytes" and
/// a `data:` URI that actually needs a real MIME type to render. Returns
/// `None` for anything that doesn't match a known signature rather than
/// guessing — see [`room_avatar`]'s doc comment for why that matters.
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
