"""Provider-neutral Product boundary gate; does not invoke hosted CI."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    subprocess.run([sys.executable, "scripts/verify_publication.py"], cwd=ROOT, check=True)
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=ROOT, check=True)
    blueprints = sorted((ROOT / "blueprints/approved").glob("PRODUCT_BLUEPRINT__*.json"))
    attestations = list((ROOT / "blueprints/approved").glob("PRODUCT_APPROVAL_ATTESTATION__*.json"))
    if not blueprints or len(blueprints) != len(attestations):
        raise SystemExit("Missing or unmatched approved blueprint/attestation")
    for blueprint in blueprints:
        attestation = blueprint.with_name(blueprint.name.replace("PRODUCT_BLUEPRINT__", "PRODUCT_APPROVAL_ATTESTATION__", 1))
        subprocess.run([sys.executable, "scripts/verify_transfer.py", str(blueprint), str(attestation)], cwd=ROOT, check=True)
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    forbidden = {".apk", ".apkm", ".xapk", ".dex", ".smali"}
    if any(Path(path).suffix.lower() in forbidden for path in tracked if path):
        raise SystemExit("Forbidden tracked input artifact")
    print("Provider-neutral Product boundary: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
