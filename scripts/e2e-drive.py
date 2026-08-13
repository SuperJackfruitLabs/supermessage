#!/usr/bin/env python3
"""Drive the supermessage Tauri app over W3C WebDriver via tauri-driver.

Raw HTTP against tauri-driver on :4444 — no webdriverio, no project dependency.
Validates the M0 acceptance criteria against real data.
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

DRIVER = "http://127.0.0.1:4444"
APP = sys.argv[1] if len(sys.argv) > 1 else None


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        DRIVER + path, data=data, method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"{}").get("value")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def safe(s):
    """WebKitWebDriver hands back lone surrogates for astral-plane emoji;
    repair them so printing doesn't explode."""
    return (s or "").encode("utf-16", "surrogatepass").decode("utf-16", "replace")


def find_all(sid, css):
    return call("POST", f"/session/{sid}/elements",
                {"using": "css selector", "value": css}) or []


def text_of(sid, el):
    return call("GET", f"/session/{sid}/element/{list(el.values())[0]}/text") or ""


def texts(sid, css):
    return [safe(text_of(sid, e)) for e in find_all(sid, css)]


def click(sid, el):
    call("POST", f"/session/{sid}/element/{list(el.values())[0]}/click", {})


def wait_for(sid, css, want=1, timeout=45, label=""):
    """Poll until at least `want` elements match, else report what we saw."""
    deadline = time.time() + timeout
    seen = 0
    while time.time() < deadline:
        seen = len(find_all(sid, css))
        if seen >= want:
            return seen
        time.sleep(1)
    print(f"  TIMEOUT waiting for {want}x '{css}' {label} — saw {seen}")
    return seen


ROOMS = 'nav[aria-label="Rooms"] button'
BODIES = "p.selectable"
PLACEHOLDERS = "span.italic"
DIVIDERS = 'div[role="separator"]'
BANNER = '[role="status"]'
COMPOSER = "textarea"

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")


def main():
    print(f"Creating session against {APP}")
    sid = call("POST", "/session", {
        "capabilities": {"alwaysMatch": {"tauri:options": {"application": APP}}}
    })["sessionId"]
    print(f"session {sid}\n")

    try:
        # --- Room list -------------------------------------------------
        print("Room list:")
        n = wait_for(sid, ROOMS, 1, 60, "(room list)")
        check("room list populates", n >= 2, f"{n} rooms rendered")
        names = texts(sid, ROOMS)
        print('  rooms: ' + ', '.join(repr(x.replace(chr(10),' | ')[:34]) for x in names[:20]))

        # --- Open a room ------------------------------------------------
        print("\nTimeline:")
        rooms = find_all(sid, ROOMS)
        best = None
        for idx, el in enumerate(rooms[:12]):
            click(sid, el)
            time.sleep(3)
            bodies = len(find_all(sid, BODIES))
            places = len(find_all(sid, PLACEHOLDERS))
            print(f"  room[{idx}] {(names[idx].splitlines() or [''])[0][:28]!r}: "
                  f"{bodies} message(s), {places} placeholder(s)")
            if best is None or bodies + places > best[1]:
                best = (idx, bodies + places, bodies, places)
        check("a room renders timeline content", best and best[1] > 0,
              f"best room[{best[0]}] had {best[2]} messages + {best[3]} placeholders")
        check("more than one timeline item somewhere", best and best[1] > 1,
              f"max items in any room = {best[1]}")

        # Re-open the richest room for the remaining checks
        click(sid, find_all(sid, ROOMS)[best[0]])
        time.sleep(3)
        before = len(find_all(sid, BODIES)) + len(find_all(sid, PLACEHOLDERS))
        print(f"  dividers: {len(find_all(sid, DIVIDERS))}, "
              f"placeholders sample: {texts(sid, PLACEHOLDERS)[:3]}")

        # --- Draft isolation --------------------------------------------
        print("\nComposer draft scoping:")
        ta = find_all(sid, COMPOSER)
        if ta and len(rooms) > 1:
            eid = list(ta[0].values())[0]
            call("POST", f"/session/{sid}/element/{eid}/value",
                 {"text": "draft-for-room-A"})
            time.sleep(1)
            other = 1 if best[0] == 0 else 0
            click(sid, find_all(sid, ROOMS)[other])
            time.sleep(3)
            ta2 = find_all(sid, COMPOSER)
            val = call("GET", f"/session/{sid}/element/{list(ta2[0].values())[0]}/property/value") or ""
            check("draft does not follow to another room", val.strip() == "",
                  f"composer in other room contained {val!r}")
            click(sid, find_all(sid, ROOMS)[best[0]])
            time.sleep(3)
            ta3 = find_all(sid, COMPOSER)
            back = call("GET", f"/session/{sid}/element/{list(ta3[0].values())[0]}/property/value") or ""
            check("draft is restored on return", back.strip() == "draft-for-room-A",
                  f"got {back!r}")
            # clear it
            call("POST", f"/session/{sid}/element/{list(ta3[0].values())[0]}/clear", {})
        else:
            check("draft scoping", False, "no composer or too few rooms")

        # --- Connection banner -------------------------------------------
        print("\nConnection:")
        b = texts(sid, BANNER)
        check("no connection banner while live", len(b) == 0,
              f"banner text: {b}" if b else "banner absent (state is live)")

        print("\nFinal DOM snapshot of the open room:")
        print(f"  messages={len(find_all(sid, BODIES))} "
              f"placeholders={len(find_all(sid, PLACEHOLDERS))} "
              f"dividers={len(find_all(sid, DIVIDERS))} (was {before} before draft test)")

    finally:
        try:
            call("DELETE", f"/session/{sid}")
        except Exception:
            pass

    print("\n=== SUMMARY ===")
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} passed")


if __name__ == "__main__":
    main()
