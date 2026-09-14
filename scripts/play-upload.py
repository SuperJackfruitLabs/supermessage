#!/usr/bin/env python3
"""Upload an App Bundle to a Play track.

Hand-rolled against the Publishing API rather than reaching for a third-party
action, for the same reason `scripts/asc_jwt`-style signing is hand-rolled on
the Apple side: a release workflow holding the keys to the application's
identity is the last place to add a dependency nobody in this repository has
read. It needs the stdlib and `openssl`, both of which are already here.

Usage:
    play-upload.py <service-account.json> <aab> <track>

The service account JSON is read by this process and never printed. The
private key inside it is passed to `openssl` on stdin, so it does not appear
in the process table either.

## The edit model, which is not obvious

Play changes are transactional. Nothing takes effect until `commit`:

    edits.insert   open a transaction
    bundles.upload put the artifact in it
    tracks.update  say which track and which versionCode
    edits.commit   make all of it real, or none of it

A failure before `commit` leaves the app exactly as it was, which is why the
upload is safe to retry — unlike the versionCode it carries, which is spent
the moment a commit succeeds.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def access_token(sa: dict) -> str:
    """Exchange a service-account JWT for an OAuth token.

    RS256 rather than Apple's ES256, which makes this the easier of the two:
    `openssl dgst -sign` emits the signature in exactly the form JOSE wants,
    with no DER unwrapping.
    """
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": sa["client_email"],
        "scope": SCOPE,
        "aud": sa["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = (
        f"{b64(json.dumps(header, separators=(',', ':')).encode())}."
        f"{b64(json.dumps(claims, separators=(',', ':')).encode())}"
    )
    # `openssl` reads the payload from stdin, so the key cannot also come that
    # way — it goes to a temporary file this process owns, mode 0600, removed
    # when the block exits. It is never echoed and never a command-line
    # argument, so it stays out of both the log and the process table.
    with tempfile.NamedTemporaryFile("w", delete=True) as key_file:
        key_file.write(sa["private_key"])
        key_file.flush()
        os.chmod(key_file.name, 0o600)
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_file.name],
            input=signing_input.encode(),
            capture_output=True,
            check=True,
        )
    jwt = f"{signing_input}.{b64(proc.stdout)}"

    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}
    ).encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


def call(token: str, method: str, url: str, body=None, content_type=None):
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    data = None
    if content_type:
        req.add_header("Content-Type", content_type)
        data = body
    elif body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data=data) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:400]
        sys.exit(f"{method} {url.split('?')[0]} -> {e.code}\n{detail}")


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().split("Usage:")[1].split("\n\n")[0].strip())
    sa_path, aab_path, track = sys.argv[1], sys.argv[2], sys.argv[3]
    sa = json.load(open(sa_path))
    package = "dev.supermessage"

    token = access_token(sa)
    print(f"  authenticated as {sa['client_email']}")

    edit = call(token, "POST", f"{API}/{package}/edits")
    edit_id = edit["id"]
    print(f"  edit {edit_id} opened")

    aab = open(aab_path, "rb").read()
    uploaded = call(
        token,
        "POST",
        f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/"
        f"applications/{package}/edits/{edit_id}/bundles?uploadType=media",
        body=aab,
        content_type="application/octet-stream",
    )
    version_code = uploaded["versionCode"]
    print(f"  uploaded versionCode {version_code} ({len(aab) // 1_000_000}MB)")

    call(
        token,
        "PUT",
        f"{API}/{package}/edits/{edit_id}/tracks/{track}",
        body={
            "track": track,
            "releases": [{"versionCodes": [str(version_code)], "status": "completed"}],
        },
    )
    print(f"  assigned to the {track} track")

    call(token, "POST", f"{API}/{package}/edits/{edit_id}:commit")
    print(f"  committed — versionCode {version_code} is now spent")


if __name__ == "__main__":
    main()
