"""Fail-closed public-source checks. Never print a matched sensitive value."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_ROOTS = {".github", "androidApp", "iosApp", "iosProbe", "shared", "gradle",
                 "blueprints", "policies", "schemas", "scripts", "tests"}
ALLOWED_FILES = {".gitattributes", ".gitignore", "README.md", "SECURITY.md",
                 "environment-manifest.json", "build.gradle.kts", "settings.gradle.kts",
                 "gradle.properties"}
FORBIDDEN_SUFFIXES = {".apk", ".apkm", ".xapk", ".dex", ".smali", ".jks", ".keystore", ".p12", ".mobileprovision", ".env", ".zip"}
PATTERNS = {
    "private_storage_link": r"https?://(?:drive|docs)\.google\.com/",
    "private_storage_identifier": r'"(?:drive_[a-z_]*id|[a-z_]*drive_id)"\s*:',
    "private_repository": r"(?:github\.com/|\brepository\s*:\s*)[^\s/]+/appfusion-(?:coscientist|product)(?![\w-])",
    "private_key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "github_token": r"\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b",
    "aws_key": r"\bAKIA[0-9A-Z]{16}\b",
    "google_service_key": r'"private_key"\s*:\s*"',
    "local_user_path": r"[A-Z]:\\(?:Users|Andriod Development)\\",
}


def check_text(text):
    return [label for label, pattern in PATTERNS.items() if re.search(pattern, text)]


def main():
    files = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    failures = []
    for name in filter(None, files):
        relative = Path(name)
        path = ROOT / relative
        if name not in ALLOWED_FILES and relative.parts[0] not in ALLOWED_ROOTS:
            failures.append((name, "unreviewed_root"))
        if path.is_symlink() or relative.suffix.lower() in FORBIDDEN_SUFFIXES or any(p in {".env", "build", "schemas-generated"} for p in relative.parts):
            failures.append((name, "forbidden_file"))
            continue
        if path.stat().st_size > 1_000_000:
            failures.append((name, "unreviewed_large_file"))
            continue
        try:
            text = path.read_text(encoding="utf-8-sig")
        except UnicodeError:
            failures.append((name, "unreviewed_binary"))
            continue
        failures.extend((name, label) for label in check_text(text))
    for name, label in failures:
        print(f"REJECT {name}: {label}")
    if failures:
        return 1
    print(f"Public-source checks: PASS ({len(list(filter(None, files)))} tracked files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
