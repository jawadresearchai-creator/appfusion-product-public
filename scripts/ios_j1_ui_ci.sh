#!/bin/bash
set -euo pipefail

# This phase is inert for ordinary/local Xcode builds. The public Product CI is
# the only authorized hosted execution path for this acceptance slice.
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${APPFUSION_SKIP_NESTED_J1:-}" = "1" ]; then
  exit 0
fi

OUTER_APP="${1:?outer AppFusion.app path is required}"
FRAMEWORK="$SRCROOT/../shared/build/bin/iosSimulatorArm64/debugFramework/AppFusionShared.framework"
test -d "$FRAMEWORK"
grep -n 'AppleDocumentJourneyRuntime' "$FRAMEWORK/Headers/AppFusionShared.h"

RUNTIME="$(python3 - <<'PY'
import json, subprocess
payload = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'runtimes', '-j']))
candidates = [
    r for r in payload['runtimes']
    if r.get('isAvailable') and r['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-')
]
if not candidates:
    raise SystemExit('No available iOS simulator runtime')
def version(runtime):
    return tuple(int(part) for part in runtime.get('version', '0').split('.'))
print(max(candidates, key=version)['identifier'])
PY
)"

DEVICE_TYPES="$(python3 - <<'PY'
import json, subprocess
payload = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devicetypes', '-j']))
ids = [d['identifier'] for d in payload['devicetypes'] if d.get('name', '').startswith('iPhone')]
for identifier in reversed(ids):
    print(identifier)
PY
)"

UDID=''
while IFS= read -r DEVICE_TYPE; do
  if [ -n "$DEVICE_TYPE" ] && CANDIDATE="$(xcrun simctl create AppFusionJourneyJ1 "$DEVICE_TYPE" "$RUNTIME" 2>/dev/null)"; then
    UDID="$CANDIDATE"
    break
  fi
done <<< "$DEVICE_TYPES"
test -n "$UDID"

cleanup_simulator() {
  xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
  xcrun simctl delete "$UDID" >/dev/null 2>&1 || true
}
trap cleanup_simulator EXIT

xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b

DERIVED="$RUNNER_TEMP/appfusion-ios-j1-derived"
RESULT="$RUNNER_TEMP/AppFusion-iOS-J1.xcresult"
rm -rf "$DERIVED" "$RESULT"

APPFUSION_SKIP_NESTED_J1=1 xcodebuild \
  -project "$SRCROOT/AppFusion.xcodeproj" \
  -scheme AppFusion \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -resultBundlePath "$RESULT" \
  CODE_SIGN_IDENTITY=- \
  CODE_SIGNING_ALLOWED=YES \
  CODE_SIGNING_REQUIRED=YES \
  ONLY_ACTIVE_ARCH=YES \
  clean test

TESTED_APP="$DERIVED/Build/Products/Debug-iphonesimulator/AppFusion.app"
test -d "$TESTED_APP"
codesign --verify --deep --strict "$TESTED_APP"

# The outer Product CI already preserves $OUTER_APP. Replace it with the exact
# simulator application exercised by XCUITest so the existing artifact remains
# the tested J1 binary rather than an unexercised shell build.
rm -rf "$OUTER_APP"
ditto "$TESTED_APP" "$OUTER_APP"

xcrun xcresulttool get test-results summary --path "$RESULT" || true
echo "APPFUSION_IOS_J1=PASS"
