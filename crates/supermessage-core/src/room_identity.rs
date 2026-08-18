//! A room name, parsed into the suite's `<glyph> <Name> — <Role>` convention.
//!
//! Ported from `$lib/components/roomIdentity.ts`. On this deployment a room is
//! usually an agent — `🧠 Buddhimaan — Squad Lead`, `🛡️ Threat Hunter Theo —
//! Security` — and telling those three parts apart is what lets a roster row
//! show a sigil, a name and a role rather than one run-on string.
//!
//! A name that does not match is a **normal outcome**, not an error: plenty of
//! rooms are just `research` or `workspace`. Every field degrades on its own.
//!
//! `relativeTime` stayed in TypeScript deliberately. It reads a clock and
//! coarsens into "3h" / "1d", which is presentation and has to re-evaluate as
//! time passes — a host should format it, not receive it stale on a DTO.
//!
//! **Everything here is code-point-aware, and that is the point.** A room's
//! display name is attacker-controlled text from a homeserver. Before this
//! module existed, `RoomList.svelte` took `name[0]` for the avatar initial and
//! rendered a lone surrogate as tofu on every emoji-named row in this
//! deployment. Rust cannot produce a lone surrogate at all — a `char` is a
//! scalar value — but the equivalent hazard is real: slicing by byte index
//! panics mid-character, and the caps below are therefore counted in `char`s.

/// The parsed structure of a room name.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomIdentity {
    /// The leading emoji or symbol, `None` when the name does not start with
    /// one.
    pub glyph: Option<String>,
    /// The room's display name. **Never empty** — a name that would otherwise
    /// be blank becomes the literal `"Unnamed room"`, so a host can render it
    /// straight into a roster row or an avatar without an emptiness check.
    pub name: String,
    /// The role or team half after the em dash, `None` when there is none.
    pub role: Option<String>,
    /// The single character for an avatar's fallback slot: the glyph when
    /// there is one, otherwise the first character of the *parsed* name,
    /// uppercased.
    ///
    /// Carried rather than left to a host because it is derived from `name`
    /// after parsing, never from the raw room name — for a structured room the
    /// raw first character is the glyph itself, and for an unstructured one it
    /// could be leading whitespace or punctuation the parse already stripped.
    pub initial: String,
}

/// Layout safety against a hostile homeserver. Nothing stops a room name from
/// being megabytes long.
const MAX_NAME_CHARS: usize = 120;
const MAX_ROLE_CHARS: usize = 40;

/// What every caller renders for a name that would otherwise be empty.
const UNNAMED_ROOM: &str = "Unnamed room";

/// The separator's own character. Excluded from ever being read as a glyph.
const EM_DASH: char = '\u{2014}';

/// Upper bound, in code points, on a run that can still count as one glyph.
///
/// A ZWJ family sequence — 👨‍👩‍👧‍👦, four people joined by three ZWJs — is 7 code
/// points, and a flag or keycap with a variation selector is well under that.
/// Eight admits every real emoji cluster while still rejecting a run that is
/// obviously a word.
const MAX_GLYPH_CHARS: usize = 8;

/// Find the first `\s+—\s*` separator, returning the byte range to cut out.
///
/// Whitespace is **required before** the dash and **optional after**, and both
/// asymmetries are deliberate. Without the leading requirement, a plain hyphen
/// inside a name like `aether-dispatches` would split into a bogus pair. With
/// the trailing whitespace optional, a dangling `Buddhimaan —` still splits —
/// into name `Buddhimaan`, role `None` — rather than keeping the stray dash.
///
/// Only the *first* qualifying dash matches: `Coder Kai — Code — Build` must
/// keep its second dash inside the role.
fn find_separator(raw: &str) -> Option<(usize, usize)> {
    let chars: Vec<(usize, char)> = raw.char_indices().collect();
    for (i, &(idx, ch)) in chars.iter().enumerate() {
        if ch != EM_DASH {
            continue;
        }
        // Walk back over the whitespace run; there must be at least one.
        let mut start = idx;
        let mut j = i;
        while j > 0 && chars[j - 1].1.is_whitespace() {
            start = chars[j - 1].0;
            j -= 1;
        }
        if start == idx {
            continue;
        }
        // Consume any whitespace after the dash.
        let mut end = idx + ch.len_utf8();
        let mut k = i + 1;
        while k < chars.len() && chars[k].1.is_whitespace() {
            end = chars[k].0 + chars[k].1.len_utf8();
            k += 1;
        }
        return Some((start, end));
    }
    None
}

/// The run of non-whitespace at the start, when it is followed by whitespace.
fn leading_token(s: &str) -> Option<&str> {
    let ws = s.find(char::is_whitespace)?;
    (ws > 0).then(|| &s[..ws])
}

/// Whether `token` is shaped like a glyph rather than an ordinary word.
///
/// The em dash is excluded even though it is above ASCII: it is this format's
/// own punctuation, so `— Squad Lead` comes back whole rather than having its
/// dash misread as a one-character glyph.
///
/// The length cap matters because nothing about "outside ASCII, followed by
/// whitespace" stops a 50,000-character run of astral characters — one all-caps
/// mathematical-alphanumeric "word" — from qualifying, since it has no internal
/// whitespace and reads as one token. Rejecting it outright rather than
/// truncating it is deliberate: that is a word, not an over-long glyph, and
/// cutting it to eight characters would silently discard the rest instead of
/// leaving it where a reader can still see it, in `name`.
fn looks_like_glyph(token: &str) -> bool {
    let mut chars = token.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    first as u32 > 0x7f && first != EM_DASH && token.chars().count() <= MAX_GLYPH_CHARS
}

/// Cap at `max` **characters**, never bytes.
///
/// No ellipsis: this is a layout-safety cap on a value, not a display excerpt.
/// CSS owns visual truncation, the way every other roster string here does.
fn bound(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    s.chars().take(max).collect()
}

/// Parse a raw Matrix room name.
pub fn parse_room_identity(raw_name: &str) -> RoomIdentity {
    if raw_name.trim().is_empty() {
        return finish(None, UNNAMED_ROOM.to_string(), None);
    }

    // Deliberately not pre-trimmed: the separator search needs the raw
    // whitespace around the dash to decide whether it is a separator at all,
    // and a half that turns out to be nothing but that whitespace must
    // collapse to empty. Trimming first eats the evidence.
    let (name_half, role_half) = match find_separator(raw_name) {
        Some((start, end)) => (raw_name[..start].trim(), raw_name[end..].trim()),
        None => (raw_name.trim(), ""),
    };

    let glyph = leading_token(name_half).filter(|token| looks_like_glyph(token));
    let without_glyph = match glyph {
        Some(token) => name_half[token.len()..].trim(),
        None => name_half,
    };

    let name = if without_glyph.is_empty() {
        UNNAMED_ROOM.to_string()
    } else {
        bound(without_glyph, MAX_NAME_CHARS)
    };
    let role = (!role_half.is_empty()).then(|| bound(role_half, MAX_ROLE_CHARS));

    finish(glyph.map(str::to_string), name, role)
}

fn finish(glyph: Option<String>, name: String, role: Option<String>) -> RoomIdentity {
    let initial = match &glyph {
        Some(glyph) => glyph.clone(),
        None => name
            .chars()
            .next()
            .map(|c| c.to_uppercase().collect::<String>())
            .unwrap_or_else(|| "?".to_string()),
    };
    RoomIdentity {
        glyph,
        name,
        role,
        initial,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parsed(raw: &str) -> (Option<String>, String, Option<String>) {
        let id = parse_room_identity(raw);
        (id.glyph, id.name, id.role)
    }

    #[test]
    fn splits_glyph_name_and_role_on_an_em_dash() {
        assert_eq!(
            parsed("🧠 Buddhimaan — Squad Lead"),
            (
                Some("🧠".to_string()),
                "Buddhimaan".to_string(),
                Some("Squad Lead".to_string())
            )
        );
    }

    #[test]
    fn leaves_a_hyphenated_name_alone() {
        // Without requiring whitespace before the dash, this would split into
        // a bogus name/role pair.
        assert_eq!(
            parsed("aether-dispatches"),
            (None, "aether-dispatches".to_string(), None)
        );
    }

    #[test]
    fn an_em_dash_without_whitespace_before_it_is_not_a_separator() {
        // The hyphen case above does not cover this: this module only ever
        // looks for an em dash, so `aether-dispatches` would survive even if
        // the whitespace requirement were deleted. This is the case that
        // actually exercises it.
        assert_eq!(parsed("Coder—Kai"), (None, "Coder—Kai".to_string(), None));
    }

    #[test]
    fn a_leading_em_dash_is_punctuation_not_a_glyph() {
        // It is above ASCII, so without the explicit exclusion it would pass
        // the glyph test. There is no whitespace before it either, so it is
        // not a separator — the whole string must come back verbatim rather
        // than losing its dash to an avatar circle.
        assert_eq!(
            parsed("— Squad Lead"),
            (None, "— Squad Lead".to_string(), None)
        );
    }

    #[test]
    fn splits_on_the_first_em_dash_only() {
        assert_eq!(
            parsed("Coder Kai — Code — Build"),
            (
                None,
                "Coder Kai".to_string(),
                Some("Code — Build".to_string())
            )
        );
    }

    #[test]
    fn takes_a_whole_astral_glyph_never_half_of_one() {
        // 🛡️ is U+1F6E1 followed by U+FE0F: two code points that must travel
        // together, or the glyph renders as a bare unstyled shield.
        let (glyph, name, role) = parsed("🛡️ Threat Hunter Theo — Security");
        assert_eq!(glyph.as_deref(), Some("🛡️"));
        assert_eq!(glyph.expect("a glyph").chars().count(), 2);
        assert_eq!(name, "Threat Hunter Theo");
        assert_eq!(role.as_deref(), Some("Security"));
    }

    #[test]
    fn does_not_treat_a_leading_ascii_word_as_a_glyph() {
        assert_eq!(parsed("Ops room"), (None, "Ops room".to_string(), None));
    }

    #[test]
    fn does_not_treat_a_leading_grapheme_as_a_glyph_without_a_following_space() {
        assert_eq!(
            parsed("🧠Buddhimaan"),
            (None, "🧠Buddhimaan".to_string(), None)
        );
    }

    #[test]
    fn yields_none_for_an_empty_half_rather_than_an_empty_string() {
        assert_eq!(
            parsed("Buddhimaan —"),
            (None, "Buddhimaan".to_string(), None)
        );
        // Nothing but the separator: the name half collapses, and the literal
        // stands in so a caller never renders a blank row.
        assert_eq!(parsed(" — "), (None, UNNAMED_ROOM.to_string(), None));
    }

    #[test]
    fn bounds_a_hostile_name_and_role() {
        let long_name = "n".repeat(500);
        let long_role = "r".repeat(500);
        let (_, name, role) = parsed(&format!("{long_name} — {long_role}"));
        assert_eq!(name.chars().count(), MAX_NAME_CHARS);
        assert_eq!(role.expect("a role").chars().count(), MAX_ROLE_CHARS);
    }

    #[test]
    fn never_returns_an_empty_name() {
        for raw in ["", "   ", "\n\t "] {
            assert_eq!(parse_room_identity(raw).name, UNNAMED_ROOM, "for {raw:?}");
        }
    }

    #[test]
    fn bounds_at_an_odd_offset_without_splitting_a_character() {
        // The Rust form of the surrogate hazard: `&s[..120]` on a run of
        // multi-byte characters panics outright, and a cap counted in bytes
        // would land mid-character on almost any emoji-heavy name.
        let name = "🎉".repeat(300);
        let role = "🎉".repeat(300);
        let (_, parsed_name, parsed_role) = parsed(&format!("{name} — {role}"));
        assert_eq!(parsed_name.chars().count(), MAX_NAME_CHARS);
        assert!(parsed_name.chars().all(|c| c == '🎉'));
        let parsed_role = parsed_role.expect("a role");
        assert_eq!(parsed_role.chars().count(), MAX_ROLE_CHARS);
        assert!(parsed_role.chars().all(|c| c == '🎉'));
    }

    #[test]
    fn keeps_an_eight_code_point_zwj_sequence_as_a_glyph_at_the_boundary() {
        // 👨‍👩‍👧‍👦 is 7 code points; one variation selector more is 8, the cap.
        let family = "👨\u{200d}👩\u{200d}👧\u{200d}👦";
        assert_eq!(family.chars().count(), 7);
        let (glyph, name, _) = parsed(&format!("{family} Household"));
        assert_eq!(glyph.as_deref(), Some(family));
        assert_eq!(name, "Household");
    }

    #[test]
    fn rejects_a_long_astral_run_as_a_glyph_because_it_is_a_word() {
        // No internal whitespace, so it reads as one token — but it is a word,
        // and cutting it to eight characters would discard the rest instead of
        // leaving it readable in `name`.
        let run = "𝐀".repeat(50);
        let (glyph, name, _) = parsed(&format!("{run} tail"));
        assert_eq!(glyph, None);
        assert_eq!(name, format!("{run} tail"));
    }

    #[test]
    fn an_initial_prefers_the_glyph() {
        assert_eq!(parse_room_identity("🧠 Buddhimaan — Lead").initial, "🧠");
    }

    #[test]
    fn an_initial_falls_back_to_the_names_first_character_uppercased() {
        assert_eq!(parse_room_identity("research").initial, "R");
        // Derived from the *parsed* name, never the raw string: the leading
        // punctuation here is stripped before the initial is taken.
        assert_eq!(parse_room_identity("   research").initial, "R");
    }
}
