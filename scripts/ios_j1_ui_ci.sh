#!/bin/bash
set -euo pipefail

# Called explicitly by the guarded public workflow, or an optional existing Mac.
# Never invoke a nested build/test from an Xcode application build phase.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
EVIDENCE="${APPFUSION_IOS_J1_EVIDENCE_DIR:-$TEMP_ROOT/appfusion-ios-j1-evidence}"
mkdir -p "$EVIDENCE"
FRAMEWORK="$ROOT/shared/build/bin/iosSimulatorArm64/debugFramework/AppFusionShared.framework"
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

DERIVED="$(mktemp -d "$TEMP_ROOT/appfusion-ios-j1.XXXXXX")"
RESULT="$EVIDENCE/AppFusion-iOS-J1.xcresult"
test ! -e "$RESULT"

xcodebuild \
  -project "$ROOT/iosApp/AppFusion.xcodeproj" \
  -scheme AppFusion \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -resultBundlePath "$RESULT" \
  -parallel-testing-enabled NO \
  CODE_SIGN_IDENTITY=- \
  CODE_SIGNING_ALLOWED=YES \
  CODE_SIGNING_REQUIRED=YES \
  ONLY_ACTIVE_ARCH=YES \
  clean test 2>&1 | tee "$EVIDENCE/xcodebuild.log"

# A green build or an empty test selection cannot satisfy installed J1.
grep -F "Test Case '-[AppFusionUITests.JourneyJ1UITests testEncryptedDocumentSurvivesTerminationSearchAndReopen]' passed" "$EVIDENCE/xcodebuild.log"

TESTED_APP="$DERIVED/Build/Products/Debug-iphonesimulator/AppFusion.app"
test -d "$TESTED_APP"
codesign --verify --deep --strict "$TESTED_APP"

# Package the exact application from the successful UI test build.
ARTIFACT="$TEMP_ROOT/AppFusion-ios-simulator.zip"
ditto -c -k --sequesterRsrc --keepParent "$TESTED_APP" "$ARTIFACT"

xcrun xcresulttool get test-results summary --path "$RESULT" > "$EVIDENCE/summary.json" || true
xcrun xcresulttool export attachments --path "$RESULT" --output-path "$EVIDENCE/attachments" || true
python3 - "$ROOT" "$TESTED_APP/AppFusion" "$ARTIFACT" "$EVIDENCE/result.json" <<'PY'
import datetime, hashlib, json, pathlib, subprocess, sys
root, executable, artifact, result = sys.argv[1:]
payload = {
    'journey': 'J1_IOS_UI', 'status': 'PASS',
    'source_commit': subprocess.check_output(['git', '-C', root, 'rev-parse', 'HEAD'], text=True).strip(),
    'tested_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'tested_executable_sha256': hashlib.sha256(pathlib.Path(executable).read_bytes()).hexdigest(),
    'simulator_zip_sha256': hashlib.sha256(pathlib.Path(artifact).read_bytes()).hexdigest(),
    'operations': ['create', 'terminate', 'relaunch', 'search', 'decrypt', 'reopen'],
}
pathlib.Path(result).write_text(json.dumps(payload, indent=2) + '\n')
PY

echo "APPFUSION_IOS_J1=PASS"
