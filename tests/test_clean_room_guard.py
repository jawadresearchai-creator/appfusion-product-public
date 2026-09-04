import hashlib
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.verify_transfer import canonical_hash, verify  # noqa: E402


class CleanRoomGuardTests(unittest.TestCase):
    def valid_blueprint(self):
        return {
            "schema_version": "1.0.0",
            "blueprint_id": "4c9ddfb1-1450-4af9-b87a-3d53322ee53b",
            "product_thesis": "A modular productivity product",
            "target_platforms": {"android": True, "ios": True},
            "release_scope": "CROSS_PLATFORM_PRODUCT",
            "modules": [],
            "acceptance": {},
        }

    def policy_hash(self):
        return hashlib.sha256((ROOT / "policies/product-boundary.json").read_bytes()).hexdigest()

    def attestation(self, blueprint):
        return {
            "schema_version": "1.0.0",
            "approval_id": "ba200e7c-bfc0-425d-8c8e-0522391352bc",
            "product_blueprint_sha256": canonical_hash(blueprint),
            "approval_status": "APPROVED",
            "approved_at": "2026-09-02T00:00:00Z",
            "policy_bundle_hash": self.policy_hash(),
            "clean_room_schema_version": "1.0.0",
        }

    def test_valid_transfer_passes(self):
        blueprint = self.valid_blueprint()
        self.assertEqual(verify(blueprint, self.attestation(blueprint)), [])

    def test_exact_artifact_hash_override_passes(self):
        blueprint = self.valid_blueprint()
        attestation = self.attestation(blueprint)
        exact_hash = "1" * 64
        attestation["product_blueprint_sha256"] = exact_hash
        self.assertEqual(
            verify(blueprint, attestation, blueprint_artifact_sha256=exact_hash),
            [],
        )

    def test_source_aware_key_is_rejected(self):
        blueprint = self.valid_blueprint()
        blueprint["source_app"] = "forbidden"
        errors = verify(blueprint, self.attestation(blueprint))
        self.assertTrue(any("source_app" in error for error in errors))

    def test_hash_mismatch_is_rejected(self):
        blueprint = self.valid_blueprint()
        attestation = self.attestation(blueprint)
        attestation["product_blueprint_sha256"] = "0" * 64
        self.assertTrue(any("hash" in error.lower() for error in verify(blueprint, attestation)))

    def test_policy_hash_mismatch_is_rejected(self):
        blueprint = self.valid_blueprint()
        attestation = self.attestation(blueprint)
        attestation["policy_bundle_hash"] = "0" * 64
        self.assertTrue(any("policy" in error.lower() for error in verify(blueprint, attestation)))

    def test_product_repository_has_no_known_source_identity_leak(self):
        forbidden_fragments = ("doc" + "vault", "last" + "time")
        text_suffixes = {".json", ".kt", ".kts", ".md", ".plist", ".py", ".swift", ".toml", ".xml", ".yml"}
        leaks = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or ".git" in path.parts or "build" in path.parts:
                continue
            if path.suffix.casefold() not in text_suffixes:
                continue
            content = path.read_text(encoding="utf-8-sig", errors="ignore").casefold()
            if any(fragment in content for fragment in forbidden_fragments):
                leaks.append(path.relative_to(ROOT).as_posix())
        self.assertEqual(leaks, [], "Known source identity leaked into Product repository")


if __name__ == "__main__":
    unittest.main()
