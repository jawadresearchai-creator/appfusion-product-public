from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / "policies/product-boundary.json"


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def walk(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def verify(
    blueprint: dict[str, Any],
    attestation: dict[str, Any],
    *,
    blueprint_artifact_sha256: str | None = None,
) -> list[str]:
    errors: list[str] = []
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    forbidden = {item.casefold() for item in policy["forbidden_keys"]}
    observed_keys = {key.casefold() for key, _ in walk(blueprint)}
    contaminated = sorted(observed_keys & forbidden)
    if contaminated:
        errors.append("Forbidden clean-room keys: " + ", ".join(contaminated))
    if blueprint.get("schema_version") != "1.0.0":
        errors.append("Unsupported ProductBlueprint schema version")
    if attestation.get("approval_status") != "APPROVED":
        errors.append("Product approval attestation is not APPROVED")

    # Approval is bound to the exact transferred ProductBlueprint artifact bytes.
    # canonical_hash remains available for object-level unit tests, but production
    # transfer verification passes the raw file SHA-256 explicitly.
    expected_blueprint_hash = blueprint_artifact_sha256 or canonical_hash(blueprint)
    if attestation.get("product_blueprint_sha256", "").casefold() != expected_blueprint_hash.casefold():
        errors.append("Product Blueprint hash does not match approval attestation")

    expected_policy_hash = file_hash(POLICY_PATH)
    if attestation.get("policy_bundle_hash", "").casefold() != expected_policy_hash.casefold():
        errors.append("Product boundary policy hash does not match approval attestation")
    if attestation.get("clean_room_schema_version") != "1.0.0":
        errors.append("Unsupported clean-room schema version")

    targets = blueprint.get("target_platforms", {})
    if targets.get("android") and targets.get("ios") and blueprint.get("release_scope") == "EXPERIMENTAL_ANDROID_ONLY_PILOT":
        errors.append("Cross-platform target cannot use Android-only pilot release scope")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("blueprint", type=Path)
    parser.add_argument("attestation", type=Path)
    args = parser.parse_args()

    blueprint_bytes = args.blueprint.read_bytes()
    blueprint = json.loads(blueprint_bytes.decode("utf-8-sig"))
    attestation = json.loads(args.attestation.read_text(encoding="utf-8-sig"))
    errors = verify(
        blueprint,
        attestation,
        blueprint_artifact_sha256=hashlib.sha256(blueprint_bytes).hexdigest(),
    )
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print("Clean-room transfer: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
