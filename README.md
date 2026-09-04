# AppFusion — Android and iOS

Public, clean-room source for a modular, offline-first productivity application.
The current development milestone implements encrypted document creation, search,
restart recovery and reopening on Android and iOS, with installed journey gates
in CI. Shared activity history, calendar cadence and reminder-reconciliation rules
are implemented with JVM/iOS contract tests; see [the J2 contract](shared/J2_CONTRACT.md).
Native notification delivery, installed activity/reminder UI and cross-module
journeys remain unfinished.

This is a development prototype, not a production security or final release.
Use disposable test data. Debug signing keys can differ between builds.

## Build and verification

- JDK 17, Gradle 9.5.0, Android SDK 36 for Android.
- Xcode/macOS for iOS Simulator and Kotlin/Native tests.
- `python scripts/verify_product.py` checks clean-room approval and public-source safety.
- Android: `gradle :shared:jvmTest :androidApp:assembleDebug`.
- iOS: `gradle :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64`.
- The single GitHub workflow runs installed Android and iOS J1 journeys plus
  Android Keystore and iOS Keychain runtime gates. iOS test results and the exact
  tested simulator application are preserved separately.

All build source is in this repository. Builds do not fetch private repositories,
user documents, analysis inputs, or external private storage. Standard public
GitHub runners are used with bounded timeouts, read-only tokens and no paid services.
Workflows fail closed if this repository becomes private. Local tools are optional.

## Downloads

Development builds produce an Android debug APK, unsigned Android release APK/AAB,
an iOS Simulator app ZIP and test evidence. A simulator app is **not** an iPhone
installation package or an App Store/TestFlight release. No paid signing or store
subscription is part of this workflow. Source ZIPs are available from GitHub.

## Publication boundary

This repository starts with fresh public history. Input applications, private
analysis, private storage links/identifiers, credentials, signing keys, user data,
and prior private Git history are intentionally excluded. See `SECURITY.md`.

Public visibility permits inspection and GitHub forking; no additional open-source
license grant is asserted by this publication. Dependency licenses remain applicable.
