#!/usr/bin/env python3
"""Generate every design-token target from design/tokens.toml.

Run from the repo root. Writes nothing if validation fails, so a broken
palette cannot half-land across five surfaces.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.tokens.emit_css import emit_app_css  # noqa: E402
from scripts.tokens.emit_kotlin import emit_kotlin  # noqa: E402
from scripts.tokens.emit_swift import emit_swift  # noqa: E402
from scripts.tokens.model import TokenError, load  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "design" / "tokens.toml"


def main() -> int:
    try:
        tokens = load(SOURCE)
    except TokenError as error:
        print(f"design/tokens.toml is invalid:\n  {error}", file=sys.stderr)
        return 1

    outputs = {
        REPO / "src" / "lib" / "tokens.css": emit_app_css(tokens),
        REPO
        / "apple"
        / "Supermessage"
        / "Generated"
        / "ThemeTokens.swift": emit_swift(tokens),
        REPO
        / "android"
        / "app"
        / "src"
        / "main"
        / "kotlin"
        / "dev"
        / "supermessage"
        / "GeneratedThemeTokens.kt": emit_kotlin(tokens),
    }

    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        print(f"wrote {path.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
