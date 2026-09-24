#!/usr/bin/env bash
#
# Archive, export and (optionally) upload the iOS app to TestFlight.
#
# Three steps, deliberately separable:
#
#     ./scripts/testflight.sh            # archive + export, stop
#     ./scripts/testflight.sh --upload   # and send it
#
# **Upload is opt-in because it cannot be undone.** App Store Connect rejects
# a build number it has seen before, so a mistaken upload does not waste a
# minute, it burns a number — and the next attempt needs the counter bumped
# and committed. Exporting first means the .ipa can be inspected while the
# number is still spendable.
#
# Credentials come from the environment and are never read by this script:
#
#     ASC_KEY_ID      the App Store Connect API key id (the XXX in AuthKey_XXX.p8)
#     ASC_ISSUER_ID   the team's issuer id — one per App Store Connect
#                     organisation, from Users and Access → Integrations
#
# The key itself stays at ~/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8,
# which is where `xcodebuild` and `altool` both look by convention. Nothing
# here cats it, echoes it, or copies it anywhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

UPLOAD=no
if [ "${1:-}" = "--upload" ]; then UPLOAD=yes; shift; fi

: "${ASC_KEY_ID:?set ASC_KEY_ID — the key id, e.g. P7RXDWPTJK}"
: "${ASC_ISSUER_ID:?set ASC_ISSUER_ID — the team issuer id from App Store Connect}"

KEY="$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"
[ -f "$KEY" ] || { echo "error: no key at $KEY" >&2; exit 1; }

ARCHIVE="$ROOT/build/Supermessage.xcarchive"
EXPORT="$ROOT/build/export"

# The framework the app links, built release rather than debug.
#
# Easy to get wrong and invisible afterwards: a debug core ships an
# unoptimised Rust build and, worse, one whose `#[cfg(debug_assertions)]`
# paths are live. `build-xcframework.sh` defaults to release; the `--debug`
# flag is for local iteration and has no business in an upload.
echo "==> building the release XCFramework"
./scripts/build-xcframework.sh

echo "==> archiving"
rm -rf "$ARCHIVE" "$EXPORT"
# Signed manually, with the distribution identity and App Store profile this
# job already installed — the same pair the export uses.
#
# It was automatic, with `-allowProvisioningUpdates`: every runner starts with
# an empty keychain, so every archive asked Apple for a fresh *development*
# certificate. Four runs on 2026-09-23 reached the account's certificate cap
# and the fifth failed with "Choose a certificate to revoke". Manual signing
# never asks Apple for anything.
xcodebuild archive \
    -project apple/Supermessage.xcodeproj \
    -scheme Supermessage \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$ARCHIVE" \
    CODE_SIGN_STYLE=Manual \
    CODE_SIGN_IDENTITY="Apple Distribution" \
    DEVELOPMENT_TEAM=N2QQPW2BRJ \
    SM_APP_PROFILE="supermessage iOS App Store" \
    -quiet

# What actually shipped, read back from the archive rather than from the
# source that was supposed to produce it. The version keys are the first
# blocker in #35 and the failure mode is silent: `GENERATE_INFOPLIST_FILE`
# writes only the keys it is given, so a missing one is an absent key rather
# than an error.
PLIST="$ARCHIVE/Products/Applications/Supermessage.app/Info.plist"
echo "==> what is in the archive"
for key in CFBundleIdentifier CFBundleShortVersionString CFBundleVersion \
           ITSAppUsesNonExemptEncryption; do
    value="$(/usr/libexec/PlistBuddy -c "Print :$key" "$PLIST" 2>/dev/null || echo "MISSING")"
    printf '    %-32s %s\n' "$key" "$value"
    [ "$value" = MISSING ] && { echo "error: $key is absent; TestFlight will reject this" >&2; exit 1; }
done

echo "==> exporting"
xcodebuild -exportArchive \
    -archivePath "$ARCHIVE" \
    -exportOptionsPlist apple/ExportOptions.plist \
    -exportPath "$EXPORT" \
    -allowProvisioningUpdates \
    -authenticationKeyPath "$KEY" \
    -authenticationKeyID "$ASC_KEY_ID" \
    -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
    -quiet

IPA="$(find "$EXPORT" -name '*.ipa' | head -1)"
[ -n "$IPA" ] || { echo "error: export produced no .ipa" >&2; exit 1; }
echo "    $IPA ($(du -h "$IPA" | cut -f1))"

if [ "$UPLOAD" != yes ]; then
    echo
    echo "Exported, not uploaded. Look at it, then:"
    echo "    ./scripts/testflight.sh --upload"
    exit 0
fi

# `--validate-app` first, always. It catches the whole class of rejections
# that otherwise arrive by email twenty minutes later — missing icons, a
# reused build number, an entitlement the profile does not grant — and unlike
# the upload it costs nothing to repeat.
echo "==> validating"
xcrun altool --validate-app -f "$IPA" -t ios \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

echo "==> uploading"
xcrun altool --upload-app -f "$IPA" -t ios \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

echo
echo "Uploaded. Processing takes a few minutes; the build appears under"
echo "TestFlight → iOS Builds when it finishes."
echo
echo "**Bump CURRENT_PROJECT_VERSION in apple/project.yml before the next"
echo "upload.** App Store Connect has now seen build $(/usr/libexec/PlistBuddy \
    -c 'Print :CFBundleVersion' "$PLIST" 2>/dev/null) and will refuse it again."
