from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/build_third_party_notices.py"


class NoticeBuilderTest(unittest.TestCase):
    def run_builder(self, base: Path, with_fallback: bool) -> subprocess.CompletedProcess[str]:
        binary_sha = "a" * 64
        (base / "packages.tsv").write_text(f"alpha\tpool/alpha.deb\t{binary_sha}\n", encoding="utf-8")
        (base / "metadata.tsv").write_text(
            f"alpha\t{binary_sha}\tMIT\thttps://example.invalid/alpha\n", encoding="utf-8"
        )
        (base / "recipe.commit").write_text("b" * 40 + "\n", encoding="ascii")
        fallback = base / "LICENSE"
        license_text = (
            "Copyright 2026 Alpha contributors\n\n"
            "Permission is hereby granted, free of charge, to any person obtaining a copy "
            "of this software to use, copy, modify, merge, publish, distribute and sublicense it.\n"
        )
        fallback.write_text(license_text, encoding="utf-8")
        if with_fallback:
            expected = hashlib.sha256(fallback.read_bytes()).hexdigest()
            fallback_row = f"alpha\tLICENSE\t{fallback.as_uri()}\t{expected}\n"
        else:
            fallback_row = ""
        (base / "fallbacks.tsv").write_text(fallback_row, encoding="utf-8")
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--packages",
                str(base / "packages.tsv"),
                "--metadata",
                str(base / "metadata.tsv"),
                "--fallbacks",
                str(base / "fallbacks.tsv"),
                "--recipe-commit",
                str(base / "recipe.commit"),
                "--termux-root",
                str(base / "root"),
                "--cache",
                str(base / "cache"),
                "--output",
                str(base / "notices.txt"),
            ],
            text=True,
            capture_output=True,
        )

    def test_fallback_license_is_verified_and_emitted(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            base = Path(name)
            result = self.run_builder(base, with_fallback=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            notice = (base / "notices.txt").read_text(encoding="utf-8")
            self.assertIn("License: MIT", notice)
            self.assertIn("Copyright 2026 Alpha contributors", notice)

    def test_non_public_domain_package_without_text_fails(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            result = self.run_builder(Path(name), with_fallback=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("no complete license/copyright text", result.stderr)


if __name__ == "__main__":
    unittest.main()
