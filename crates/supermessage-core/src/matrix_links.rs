//! matrix.to URLs and `matrix:` URIs, parsed into what they address.
//!
//! Ported from `$lib/components/matrixLinks.ts`. This is protocol parsing, so
//! it belongs where every client can share it rather than being written once
//! per platform against the same two appendices.
//!
//! Grammar taken from the spec's appendices, not guessed:
//!
//! - **matrix.to**: `https://matrix.to/#/<identifier>[/<event-id>][?via=…]`,
//!   where `<identifier>` carries its sigil (`!room:x`, `#alias:x`, `@user:x`)
//!   and everything after the `#` — *including* a second, nested `?via=` query
//!   — is the URL's fragment, not its query. matrix.to is a static redirector,
//!   so the whole address has to survive being pasted as one opaque fragment.
//! - **`matrix:`**: `matrix:<type>/<id-without-sigil>[/e/<event-id>][?via=…]`,
//!   with `type` one of `u` (user), `r` (room alias), `roomid` (room id). An
//!   event reference is a *path* segment (`/e/<id>`), never a query parameter
//!   — there is no `?event=` in the real grammar, which is easy to get wrong.
//!
//! `resolveInAppRoomId` stayed in TypeScript: it checks a target against
//! whichever rooms the host currently knows about, which is a host question,
//! and it is two lines.

use percent_encoding::percent_decode_str;
use url::Url;

/// What a parsed matrix.to or `matrix:` link addresses.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum MatrixLinkTarget {
    Room {
        room_id: String,
        event_id: Option<String>,
    },
    RoomAlias {
        alias: String,
        event_id: Option<String>,
    },
    User {
        user_id: String,
    },
    /// Recognisably a matrix link — so it must never fall through as though
    /// this parser did not understand matrix links at all — but too malformed,
    /// or an address form the grammar does not define, to extract anything
    /// from. Distinct from `None`, which means "not a matrix link".
    Unknown,
}

/// Parse `href`. `None` when it is not a matrix link in the first place —
/// an ordinary `https://`, a `mailto:`, or a string that is not a URL.
///
/// Never fails loudly: an unparseable `href` is simply not a matrix link.
pub fn parse_matrix_link(href: &str) -> Option<MatrixLinkTarget> {
    let url = Url::parse(href).ok()?;
    match url.scheme() {
        "matrix" => Some(parse_matrix_uri(&url)),
        "https" | "http" if url.host_str() == Some("matrix.to") => Some(parse_matrix_to(&url)),
        _ => None,
    }
}

/// Percent-decode one segment, rejecting malformed encoding.
///
/// Deliberately stricter than the permissive decoders: `%` must be followed by
/// two hex digits or the segment is malformed, matching what
/// `decodeURIComponent` throws on. A lone trailing `%` is the realistic case,
/// and treating it as a literal would quietly address a different room than
/// the link says.
fn decode_segment(segment: &str) -> Option<String> {
    let bytes = segment.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = bytes.get(i + 1..i + 3)?;
            if !hex.iter().all(u8::is_ascii_hexdigit) {
                return None;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    let decoded = percent_decode_str(segment).decode_utf8().ok()?;
    (!decoded.is_empty()).then(|| decoded.into_owned())
}

fn parse_matrix_to(url: &Url) -> MatrixLinkTarget {
    let fragment = url.fragment().unwrap_or("");
    let path = fragment.strip_prefix('/').unwrap_or(fragment);
    // The nested query lives inside the fragment, so it is split off by hand.
    let path = path.split('?').next().unwrap_or("");
    let segments: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();

    let Some(first) = segments.first() else {
        return MatrixLinkTarget::Unknown;
    };
    let Some(identifier) = decode_segment(first) else {
        return MatrixLinkTarget::Unknown;
    };
    let event_id = segments.get(1).and_then(|s| decode_segment(s));
    target_from_sigil(&identifier, event_id)
}

fn target_from_sigil(identifier: &str, event_id: Option<String>) -> MatrixLinkTarget {
    let mut chars = identifier.chars();
    let Some(sigil) = chars.next() else {
        return MatrixLinkTarget::Unknown;
    };
    if chars.as_str().is_empty() {
        return MatrixLinkTarget::Unknown;
    }
    match sigil {
        '!' => MatrixLinkTarget::Room {
            room_id: identifier.to_string(),
            event_id,
        },
        // The spec deprecates referencing an event within a room identified by
        // alias. Still parsed — never silently dropped — just never actionable.
        '#' => MatrixLinkTarget::RoomAlias {
            alias: identifier.to_string(),
            event_id,
        },
        '@' => MatrixLinkTarget::User {
            user_id: identifier.to_string(),
        },
        _ => MatrixLinkTarget::Unknown,
    }
}

fn parse_matrix_uri(url: &Url) -> MatrixLinkTarget {
    let segments: Vec<&str> = url.path().split('/').filter(|s| !s.is_empty()).collect();
    if segments.len() < 2 {
        return MatrixLinkTarget::Unknown;
    }
    let Some(id) = decode_segment(segments[1]) else {
        return MatrixLinkTarget::Unknown;
    };
    // An event reference is `/e/<id>` and nothing else. A bare `e` with
    // nothing after it is not one.
    let event_id = match (segments.get(2), segments.get(3)) {
        (Some(&"e"), Some(raw)) => decode_segment(raw).map(|id| format!("${id}")),
        _ => None,
    };

    match segments[0] {
        "u" => MatrixLinkTarget::User {
            user_id: format!("@{id}"),
        },
        "r" => MatrixLinkTarget::RoomAlias {
            alias: format!("#{id}"),
            event_id,
        },
        "roomid" => MatrixLinkTarget::Room {
            room_id: format!("!{id}"),
            event_id,
        },
        _ => MatrixLinkTarget::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn room(room_id: &str, event_id: Option<&str>) -> MatrixLinkTarget {
        MatrixLinkTarget::Room {
            room_id: room_id.into(),
            event_id: event_id.map(str::to_string),
        }
    }

    fn alias(alias: &str, event_id: Option<&str>) -> MatrixLinkTarget {
        MatrixLinkTarget::RoomAlias {
            alias: alias.into(),
            event_id: event_id.map(str::to_string),
        }
    }

    fn user(user_id: &str) -> MatrixLinkTarget {
        MatrixLinkTarget::User {
            user_id: user_id.into(),
        }
    }

    // ---- not a matrix link at all ---------------------------------------

    #[test]
    fn a_plain_https_url_is_not_a_matrix_link() {
        assert_eq!(parse_matrix_link("https://example.org/page"), None);
    }

    #[test]
    fn a_mailto_link_is_not_a_matrix_link() {
        assert_eq!(parse_matrix_link("mailto:someone@example.org"), None);
    }

    #[test]
    fn a_string_that_is_not_a_url_is_not_a_matrix_link() {
        assert_eq!(parse_matrix_link("just some words"), None);
        assert_eq!(parse_matrix_link(""), None);
    }

    #[test]
    fn a_host_that_merely_resembles_matrix_to_is_not_a_matrix_link() {
        // The lookalike is the point: a link to matrix.to.example.org must
        // not be treated as a matrix link and routed in-app.
        assert_eq!(
            parse_matrix_link("https://matrix.to.example.org/#/!r:x.org"),
            None
        );
        assert_eq!(parse_matrix_link("https://notmatrix.to/#/!r:x.org"), None);
    }

    // ---- matrix.to -------------------------------------------------------

    #[test]
    fn parses_a_room_id() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn parses_a_percent_encoded_room_id_sigil() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/%21abc:example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn parses_a_percent_encoded_room_alias() {
        // The spec's recommended form, since `#` would otherwise start a
        // second fragment.
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/%23room:example.org"),
            Some(alias("#room:example.org", None))
        );
    }

    #[test]
    fn parses_a_literal_room_alias_too() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/#room:example.org"),
            Some(alias("#room:example.org", None))
        );
    }

    #[test]
    fn parses_a_user_id() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/@alice:example.org"),
            Some(user("@alice:example.org"))
        );
    }

    #[test]
    fn parses_an_event_id_following_a_room_id() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org/$event:example.org"),
            Some(room("!abc:example.org", Some("$event:example.org")))
        );
    }

    #[test]
    fn parses_a_percent_encoded_event_id() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org/%24event:example.org"),
            Some(room("!abc:example.org", Some("$event:example.org")))
        );
    }

    #[test]
    fn ignores_a_via_parameter_appended_to_a_room_id() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org?via=example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn ignores_repeated_via_parameters() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org?via=a.org&via=b.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn ignores_via_appended_after_an_event_id_too() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org/$e:example.org?via=a.org"),
            Some(room("!abc:example.org", Some("$e:example.org")))
        );
    }

    #[test]
    fn an_empty_fragment_is_unknown_rather_than_a_failure() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/"),
            Some(MatrixLinkTarget::Unknown)
        );
        assert_eq!(
            parse_matrix_link("https://matrix.to/"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn a_sigil_less_identifier_is_unknown() {
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/abc:example.org"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn a_bare_sigil_with_no_id_after_it_is_unknown() {
        for href in [
            "https://matrix.to/#/!",
            "https://matrix.to/#/@",
            "https://matrix.to/#/%23",
        ] {
            assert_eq!(
                parse_matrix_link(href),
                Some(MatrixLinkTarget::Unknown),
                "for {href}"
            );
        }
    }

    #[test]
    fn malformed_percent_encoding_is_unknown_rather_than_decoded_loosely() {
        // A lone trailing `%` is the realistic case. Treating it as a literal
        // would quietly address a different room than the link says.
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/%"),
            Some(MatrixLinkTarget::Unknown)
        );
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/%zz"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn malformed_percent_encoding_after_a_valid_sigil_is_still_unknown() {
        // The bare-`%` cases above reach Unknown for a different reason — `%`
        // is not a sigil — so they never exercise the decoder's strictness at
        // all. These do: decoding leniently would hand back a room id with a
        // stray `%` in it and address a room that does not exist.
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc%:example.org"),
            Some(MatrixLinkTarget::Unknown)
        );
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc%zz:example.org"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn malformed_encoding_in_an_event_id_drops_the_event_not_the_room() {
        // The room half is well formed and still usable; only the event
        // reference is discarded.
        assert_eq!(
            parse_matrix_link("https://matrix.to/#/!abc:example.org/%zz"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn the_matrix_to_host_is_matched_case_insensitively() {
        assert_eq!(
            parse_matrix_link("https://MATRIX.TO/#/!abc:example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    // ---- matrix: URIs ----------------------------------------------------

    #[test]
    fn parses_a_matrix_uri_user() {
        assert_eq!(
            parse_matrix_link("matrix:u/alice:example.org"),
            Some(user("@alice:example.org"))
        );
    }

    #[test]
    fn parses_a_matrix_uri_room_alias() {
        assert_eq!(
            parse_matrix_link("matrix:r/room:example.org"),
            Some(alias("#room:example.org", None))
        );
    }

    #[test]
    fn parses_a_matrix_uri_room_id() {
        assert_eq!(
            parse_matrix_link("matrix:roomid/abc:example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn parses_an_event_as_a_path_segment_after_a_room_id() {
        // The spec's own example shape. There is no `?event=` in the grammar,
        // which is the detail this test exists to pin.
        assert_eq!(
            parse_matrix_link("matrix:roomid/abc:example.org/e/event:example.org"),
            Some(room("!abc:example.org", Some("$event:example.org")))
        );
    }

    #[test]
    fn ignores_a_via_query_string_on_a_matrix_uri() {
        assert_eq!(
            parse_matrix_link("matrix:roomid/abc:example.org?via=example.org"),
            Some(room("!abc:example.org", None))
        );
    }

    #[test]
    fn decodes_a_percent_encoded_matrix_uri_id() {
        assert_eq!(
            parse_matrix_link("matrix:u/alice%3Aexample.org"),
            Some(user("@alice:example.org"))
        );
    }

    #[test]
    fn an_unknown_type_qualifier_is_unknown() {
        assert_eq!(
            parse_matrix_link("matrix:x/abc:example.org"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn a_matrix_uri_with_no_id_segment_is_unknown() {
        assert_eq!(
            parse_matrix_link("matrix:u"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn a_matrix_uri_with_an_empty_id_segment_is_unknown() {
        assert_eq!(
            parse_matrix_link("matrix:u/"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn malformed_percent_encoding_in_a_matrix_uri_is_unknown() {
        assert_eq!(
            parse_matrix_link("matrix:u/%"),
            Some(MatrixLinkTarget::Unknown)
        );
    }

    #[test]
    fn a_bare_event_marker_with_no_id_is_not_an_event_reference() {
        assert_eq!(
            parse_matrix_link("matrix:roomid/abc:example.org/e"),
            Some(room("!abc:example.org", None))
        );
    }
}
