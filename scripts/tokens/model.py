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

TYPE_ROLES: tuple[str, ...] = (
    "label", "meta", "ui", "ui-lg", "avatar", "body", "body-own",
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
class Tokens:
    appearances: dict[str, Appearance]
    type: dict[str, TypeRole] = field(default_factory=dict)


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

    _validate_type(data)


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
        }
    )
