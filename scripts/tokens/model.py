"""Load design/tokens.toml, and refuse to return anything invalid.

`validate` raises on the first violation with a message naming the
appearance, the role and the contract it broke. There is no warning level
and no fallback: a role missing from one appearance is exactly how three
themes drifted into disagreement, and a fallback would have hidden it.
"""

import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .contrast import contrast_ratio, region_luminance_drop

ROLES: tuple[str, ...] = (
    "surface", "surface-sunken", "surface-raised",
    "border", "border-strong",
    "content", "content-muted", "content-faint",
    "accent", "accent-content", "accent-soft",
    "signal", "signal-soft",
    "danger", "ok", "scrim",
)


FAMILIES: tuple[str, ...] = ("sans", "serif", "mono")

#: The roles an accent replaces. Everything else stays the appearance's own.
ACCENT_ROLES: tuple[str, ...] = ("accent", "accent-content", "accent-soft")

#: How many person colours there are. The core's `peer_color_index` picks
#: one by `user_id`, modulo this — the two must agree.
PEER_COUNT = 7

TYPE_ROLES: tuple[str, ...] = (
    "label", "meta", "ui", "ui-lg", "avatar", "body", "longread", "body-own",
)


class TokenError(Exception):
    """A contract in design/tokens.toml is not satisfied."""


@dataclass(frozen=True)
class Appearance:
    name: str
    colors: dict[str, str]
    comments: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class TypeRole:
    """One rank, expressed for each platform.

    `web` is a size; `ios` and `android` are text *style names*. That
    asymmetry is the point — see `validate`.
    """

    name: str
    family: str
    web: dict
    ios: str
    android: str


@dataclass(frozen=True)
class Brand:
    """The mark's gradient stops, and the grounds it is drawn on.

    `mark` maps a shape to its `(from, to)` stops. `ground` maps a name to a
    colour already resolved from its appearance and role, so no emitter can
    reach for a ground the palette does not have.
    """

    mark: dict[str, tuple[str, str]]
    ground: dict[str, str]


@dataclass(frozen=True)
class Tokens:
    appearances: dict[str, Appearance]
    #: accent name -> appearance name -> accent role -> value.
    accents: dict[str, dict[str, dict[str, str]]] = field(default_factory=dict)
    #: appearance name -> the person colours, in index order.
    peers: dict[str, list[str]] = field(default_factory=dict)
    type: dict[str, TypeRole] = field(default_factory=dict)
    scale: dict[str, dict] = field(default_factory=dict)
    breakpoints: dict[str, int] = field(default_factory=dict)
    brand: Brand | None = None


def validate(data: dict) -> None:
    appearances = data.get("appearance", {})
    if not appearances:
        raise TokenError("no [appearance.*] tables found")

    for name, table in appearances.items():
        colors = {role: spec["value"] for role, spec in table["color"].items()}

        missing = set(ROLES) - set(colors)
        if missing:
            raise TokenError(
                f"appearance '{name}' is missing role(s): "
                f"{', '.join(sorted(missing))}. A role absent from any "
                f"appearance is an error, never a fallback."
            )
        unknown = set(colors) - set(ROLES)
        if unknown:
            raise TokenError(
                f"appearance '{name}' declares unknown role(s): "
                f"{', '.join(sorted(unknown))}"
            )

        for role, spec in table["color"].items():
            for rule in spec.get("contrast", []):
                against = rule["against"]
                actual = contrast_ratio(colors[role], colors[against])
                low, high = rule.get("min"), rule.get("max")
                if low is not None and actual < low:
                    raise TokenError(
                        f"{name}.{role} ({colors[role]}) is {actual:.2f}:1 "
                        f"against {against} ({colors[against]}); "
                        f"the contract requires at least {low}"
                    )
                if high is not None and actual > high:
                    raise TokenError(
                        f"{name}.{role} ({colors[role]}) is {actual:.3f}:1 "
                        f"against {against}; the contract requires at most "
                        f"{high}"
                    )

            drop_rule = spec.get("drop")
            if drop_rule:
                # Ground plus the text on it. Not a single surface: each
                # theme's scrim IS one of the two, so a single-surface
                # check reports 1.00x for a scrim that works.
                actual = region_luminance_drop(
                    colors["surface-sunken"], colors["content"], colors[role]
                )
                if actual < drop_rule["min"]:
                    raise TokenError(
                        f"{name}.{role} veils its region by only "
                        f"{actual:.2f}x; the contract requires "
                        f"{drop_rule['min']}x. (Region mean, not a WCAG "
                        f"ratio and not a single surface — see "
                        f"contrast.region_luminance_drop.)"
                    )

    _validate_accents(data)
    _validate_peers(data)
    _validate_type(data)
    _validate_brand(data)

    for required in ("radius", "elevation", "motion", "layout"):
        if required not in data:
            raise TokenError(f"design/tokens.toml has no [{required}] table")


def _check(label: str, value: str, rule: dict, colors: dict[str, str]) -> None:
    against = rule["against"]
    actual = contrast_ratio(value, colors[against])
    low, high = rule.get("min"), rule.get("max")
    if low is not None and actual < low:
        raise TokenError(
            f"{label} ({value}) is {actual:.2f}:1 against {against} "
            f"({colors[against]}); the contract requires at least {low}"
        )
    if high is not None and actual > high:
        raise TokenError(
            f"{label} ({value}) is {actual:.3f}:1 against {against}; "
            f"the contract requires at most {high}"
        )


def _validate_accents(data: dict) -> None:
    """Every accent covers every appearance, with all three roles, and each
    role passes its contracts on the palette it would be dropped into."""
    appearances = data["appearance"]
    for name, per_appearance in data.get("accent", {}).items():
        missing = set(appearances) - set(per_appearance)
        if missing:
            raise TokenError(
                f"accent '{name}' has no values for appearance(s): "
                f"{', '.join(sorted(missing))}. An accent that exists in one "
                f"appearance and not another would change on a scheme switch."
            )
        for appearance, roles in per_appearance.items():
            if appearance not in appearances:
                raise TokenError(f"accent '{name}' names unknown appearance '{appearance}'")
            if set(roles) != set(ACCENT_ROLES):
                raise TokenError(
                    f"accent '{name}.{appearance}' must define exactly "
                    f"{', '.join(ACCENT_ROLES)}"
                )
            colors = {r: spec["value"] for r, spec in appearances[appearance]["color"].items()}
            colors.update({r: spec["value"] for r, spec in roles.items()})
            for role, spec in roles.items():
                for rule in spec.get("contrast", []):
                    _check(f"accent {name}.{appearance}.{role}", spec["value"], rule, colors)


def _validate_peers(data: dict) -> None:
    """Every appearance has exactly PEER_COUNT person colours, each clearing
    every contract — a name is text."""
    peers = data.get("peer")
    if peers is None:
        return
    appearances = data["appearance"]
    missing = set(appearances) - set(peers)
    if missing:
        raise TokenError(f"[peer] has no colours for appearance(s): {', '.join(sorted(missing))}")
    for appearance, spec in peers.items():
        values = spec["colors"]
        if len(values) != PEER_COUNT:
            raise TokenError(
                f"peer.{appearance} has {len(values)} colours; the core picks "
                f"modulo {PEER_COUNT}, so it must have exactly that many"
            )
        colors = {r: s["value"] for r, s in appearances[appearance]["color"].items()}
        for index, value in enumerate(values):
            for rule in spec.get("contrast", []):
                _check(f"peer.{appearance}[{index}]", value, rule, colors)


def breakpoints_for(data: dict) -> dict[str, int]:
    """Derive the pane breakpoints from the pane widths.

    `+page.svelte` documented this arithmetic — 1238 = 288 (roster) + 320
    (panel) + 630 (sheet) — and then hardcoded its result ten times, with
    1293 alongside it. Computing it is what stops the comment and the code
    drifting: widen the roster and the breakpoint follows, instead of the
    comment quietly becoming a lie.
    """
    layout = data["layout"]
    base = layout["roster"] + layout["panel"] + layout["sheet"]
    return {
        "panel-column": base,
        "panel-column-with-rail": base + layout["rail"],
    }


def _validate_type(data: dict) -> None:
    for name, spec in data.get("type", {}).items():
        if spec.get("family") not in FAMILIES:
            raise TokenError(
                f"type.{name}.family must be one of {FAMILIES}, got "
                f"{spec.get('family')!r}"
            )
        if "size" not in spec.get("web", {}):
            raise TokenError(f"type.{name}.web needs a size")
        for platform in ("ios", "android"):
            if not isinstance(spec.get(platform), str):
                raise TokenError(
                    f"type.{name}.{platform} must be a text-style NAME, not "
                    f"{spec.get(platform)!r}. A number here is emitted as a "
                    f"fixed size and silently costs every native user their "
                    f"Dynamic Type / font-scale setting — and the app still "
                    f"looks right to whoever made the change."
                )


MARK_SHAPES: tuple[str, ...] = ("blue", "coral", "overlap")
HEX = re.compile(r"^#[0-9a-f]{6}$")


def _validate_brand(data: dict) -> None:
    brand = data.get("brand")
    if brand is None:
        raise TokenError("design/tokens.toml has no [brand] table")

    mark = brand.get("mark", {})
    if sorted(mark) != sorted(MARK_SHAPES):
        raise TokenError(
            f"brand.mark must define exactly {MARK_SHAPES}, got "
            f"{tuple(sorted(mark))}"
        )
    for shape, stops in mark.items():
        for end in ("from", "to"):
            # Lowercase six-digit hex only: these are written into SVG and
            # Android resource XML verbatim, and neither accepts rgb().
            if not HEX.match(str(stops.get(end, ""))):
                raise TokenError(
                    f"brand.mark.{shape}.{end} must be a lowercase #rrggbb, "
                    f"got {stops.get(end)!r}"
                )

    appearances = data.get("appearance", {})
    for name, ref in brand.get("ground", {}).items():
        appearance, role = ref.get("appearance"), ref.get("role")
        if appearance not in appearances or role not in ROLES:
            raise TokenError(
                f"brand.ground.{name} names {appearance}.{role}, which is not "
                f"a colour in this palette. A ground is a role, never a new "
                f"value."
            )
        value = appearances[appearance]["color"][role]["value"]
        if not HEX.match(value):
            raise TokenError(
                f"brand.ground.{name} resolves to {value}, which is not opaque"
            )
    for required in ("ink", "paper"):
        if required not in brand.get("ground", {}):
            raise TokenError(f"brand.ground has no `{required}`")


def _brand(data: dict) -> Brand:
    brand = data["brand"]
    return Brand(
        mark={
            shape: (stops["from"], stops["to"])
            for shape, stops in brand["mark"].items()
        },
        ground={
            name: data["appearance"][ref["appearance"]]["color"][ref["role"]][
                "value"
            ]
            for name, ref in brand["ground"].items()
        },
    )


def _comments(path: Path) -> dict[str, dict[str, str]]:
    """Collect the comment block immediately above each role assignment.

    Read from the text rather than from tomllib, which discards comments.
    The rationale is the most valuable content in the file and has to reach
    the generated targets.
    """
    out: dict[str, dict[str, str]] = {}
    appearance: str | None = None
    buffer: list[str] = []
    header = re.compile(r"^\[appearance\.([a-z]+)\.color\]")
    assignment = re.compile(r"^([a-z-]+)\s*=")

    for line in path.read_text().splitlines():
        stripped = line.strip()
        if match := header.match(stripped):
            appearance = match[1]
            out[appearance] = {}
            buffer = []
        elif stripped.startswith("#"):
            buffer.append(stripped.lstrip("# ").rstrip())
        elif match := assignment.match(stripped):
            if appearance is not None:
                out[appearance][match[1]] = " ".join(buffer)
            buffer = []
        elif not stripped:
            buffer = []
    return out


def load(path: Path) -> Tokens:
    with path.open("rb") as fh:
        data = tomllib.load(fh)
    validate(data)
    comments = _comments(path)
    return Tokens(
        scale={
            name: data[name]
            for name in ("radius", "elevation", "motion", "layout")
        },
        breakpoints=breakpoints_for(data),
        brand=_brand(data),
        type={
            name: TypeRole(
                name=name,
                family=spec["family"],
                web=spec["web"],
                ios=spec["ios"],
                android=spec["android"],
            )
            for name, spec in data.get("type", {}).items()
        },
        appearances={
            name: Appearance(
                name=name,
                colors={role: table["color"][role]["value"] for role in ROLES},
                comments=comments.get(name, {}),
            )
            for name, table in data["appearance"].items()
        },
        accents={
            name: {
                appearance: {role: spec["value"] for role, spec in roles.items()}
                for appearance, roles in per_appearance.items()
            }
            for name, per_appearance in data.get("accent", {}).items()
        },
        peers={name: list(spec["colors"]) for name, spec in data.get("peer", {}).items()},
    )
