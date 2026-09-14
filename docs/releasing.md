# Releasing

Three platforms, three paths, and one rule they share: **a build number is
spent the moment a store accepts it.** Apple and Google both reject a number
they have seen before, so every upload here is opt-in rather than the tail of
a command.

| Platform | Workflow | Trigger |
|---|---|---|
| Desktop — macOS, Linux, Windows | `release.yml` | tag `v*`, or dispatch |
| iOS — TestFlight | `testflight.yml` | dispatch, upload off by default |
| Android — Play internal | `play.yml` | dispatch, upload off by default |

## 1. Build numbers

`CFBundleVersion` and `versionCode` both come from `github.run_number`,
floored at the value committed in `apple/project.yml` and
`android/app/build.gradle.kts`. One counter for both, so "build 7" names one
release rather than two unrelated artifacts.

The floor is a floor, not the value used. It exists because `run_number`
starts at 1 for a new workflow and would otherwise ship a number below one
already spent — the iOS workflow shipped with exactly that bug and had to be
fixed before the first upload. Check the rule locally:

    cd android && ./gradlew -q :app:printVersion
    ANDROID_VERSION_CODE=42 ./gradlew -q :app:printVersion

## 2. iOS

Signing is **manual**, not automatic, and that is deliberate: `automatic`
makes Xcode insist on cloud signing, which needs a cloud-managed distribution
certificate. This team's was created the ordinary way, so Xcode refuses with
`Cloud signing permission error` and — misleadingly — `No profiles were
found`, even when the profile exists and is ACTIVE.

So both halves of the identity come from the runner: the `Apple Distribution`
certificate from `IOS_DIST_P12_BASE64`, and the profile from
`IOS_PROFILE_BASE64`. Nothing is fetched during export, so nothing can be
refused.

**The profile expires 2027-08-31.** Rotating it is manual, and is the price
of an export that works. Regenerate through the App Store Connect API with
the team key, then replace the secret.

The job selects the **newest Xcode on the image** rather than pinning one.
Apple raises the SDK floor roughly yearly — an upload was refused for being
built with iOS 18.5 when 26 had become the minimum — and an image update
should absorb that rather than another failed upload.

## 3. Android

Play App Signing holds the real key; what is in secrets is the **upload**
key. A leaked upload key is rotated through the Play Console, unlike iOS,
where the distribution key is the identity itself.

`isMinifyEnabled = false`, deliberately. This project reaches its UniFFI
boundary reflectively through JNA, and R8 cannot see those call sites.
Enabling it is a real size win and a real risk, and wants its own change.

## 4. What a person still has to do

Neither store lets an API create the app record, and both have product
decisions behind the release:

- **App Store Connect** — the record exists (`SuperMessage - Matrix`).
- **Play Console** — the record must be created, plus the declarations Google
  requires before *any* track publishes, internal included: privacy policy
  URL, data safety form, content rating, target audience.
- **A Play service account** — Google Cloud project, service account, the
  Android Publisher API enabled, and access granted in the Play Console.
  `PLAY_SERVICE_ACCOUNT_JSON` holds its key.

## 5. Beta to production

**Promotion never rebuilds.** The same bytes that were tested are the bytes
that ship — select the TestFlight build and submit it, or promote the AAB
from internal to production. Not every tag is promoted, and gaps in shipped
versions are normal.

Review latency differs by roughly a day between the stores, so use **hold for
manual release after approval** on both and release together, rather than
letting Android ship first.

**Production is blocked on #60** — App Review requires a way to report
content and block users for anything carrying user-generated content, and a
Matrix client is squarely that. TestFlight *internal* needs none of it, which
is why iOS is shipping today.
