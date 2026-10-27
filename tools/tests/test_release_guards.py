from __future__ import annotations

import re
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def rows(path: Path, columns: int) -> list[list[str]]:
    parsed = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw and not raw.startswith("#"):
            fields = raw.split("\t")
            if len(fields) != columns:
                raise AssertionError(f"{path}: expected {columns} columns: {raw}")
            parsed.append(fields)
    return parsed


class ReleaseGuardsTest(unittest.TestCase):
    def test_notification_builders_never_set_a_large_icon(self) -> None:
        paths = [
            ROOT / "app/src/main/java/io/github/taverndroid/Appearance.kt",
            ROOT / "app/src/main/java/io/github/taverndroid/MainActivity.kt",
            ROOT / "app/src/main/java/io/github/taverndroid/ServerService.kt",
        ]
        source = "\n".join(path.read_text(encoding="utf-8") for path in paths)
        self.assertNotIn("setLargeIcon(", source)
        self.assertNotIn("notificationIcon(", source)
        self.assertEqual(source.count(".setSmallIcon(Appearance.notificationSmallIcon("), 3)

    def test_source_check_action_is_read_only_and_pinned(self) -> None:
        workflow = (ROOT / ".github/workflows/checks.yml").read_text(encoding="utf-8")
        actual = dict(re.findall(r"uses:\s*([^@\s]+)@([0-9a-f]{40})\s*$", workflow, re.MULTILINE))
        expected = {
            "actions/checkout": "11d5960a326750d5838078e36cf38b85af677262",
        }
        self.assertEqual(actual, expected)
        self.assertIn("permissions:\n  contents: read", workflow.split("jobs:", 1)[0])
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("assembleRelease", workflow)
        self.assertNotIn("KEYSTORE_", workflow)
        self.assertNotIn("action-gh-release", workflow)

    def test_termux_metadata_matches_the_binary_lock(self) -> None:
        locked = {name: sha for name, _archive, sha in rows(ROOT / "runtime/termux-packages.tsv", 3)}
        metadata = {name: sha for name, sha, _license, _homepage in rows(ROOT / "runtime/termux-package-metadata.tsv", 4)}
        self.assertEqual(metadata, locked)
        fallback_packages = {row[0] for row in rows(ROOT / "runtime/termux-license-fallbacks.tsv", 4)}
        self.assertTrue(
            {"ca-certificates", "git", "libc++", "libiconv", "libicu", "libsqlite", "npm", "openssl", "pcre2"}
            <= fallback_packages
        )

    def test_source_manifest_is_complete(self) -> None:
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools/build_source_bundle.py"),
                "--manifest",
                str(ROOT / "runtime/copyleft-sources.tsv"),
                "--check",
            ],
            check=True,
        )

    def test_exporter_verifies_payload_bytes_and_docs_match(self) -> None:
        script = (ROOT / "tools/termux-export.sh").read_text(encoding="utf-8")
        self.assertIn("zip.openReadStream(entry", script)
        self.assertIn("entry.crc32", script)
        self.assertIn('node_modules/yauzl/package.json', script)
        self.assertIn('unzip -tqq "$PART"', script)
        self.assertIn("有项目未能加入压缩包", script)
        for readme in (ROOT / "README.md", ROOT / "README.en.md"):
            text = readme.read_text(encoding="utf-8")
            self.assertNotIn("只打包，不读取", text)
            self.assertNotIn("never reads, prints or uploads", text)

    def test_modified_sillytavern_files_carry_date_and_source_offer(self) -> None:
        patches = "\n".join(path.read_text(encoding="utf-8") for path in sorted((ROOT / "patches").glob("*.patch")))
        self.assertGreaterEqual(patches.count("Modified by Jiuguan project on 2026-10-18"), 2)
        self.assertIn("https://github.com/Mtaisha/Silly-Tavern-apk", patches)


if __name__ == "__main__":
    unittest.main()
