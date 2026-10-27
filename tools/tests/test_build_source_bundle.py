import hashlib
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "build_source_bundle.py"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*arguments: str, cwd: Path) -> str:
    return subprocess.check_output(
        ["git", *arguments], cwd=cwd, text=True, encoding="utf-8", stderr=subprocess.STDOUT
    ).strip()


class SourceBundleTest(unittest.TestCase):
    def make_repo(self, path: Path, filename: str) -> str:
        path.mkdir()
        (path / filename).write_text(f"source for {filename}\n", encoding="utf-8")
        git("init", "-q", cwd=path)
        git("config", "user.name", "Jiuguan Test", cwd=path)
        git("config", "user.email", "test@invalid.example", cwd=path)
        git("add", filename, cwd=path)
        git("commit", "-q", "-m", "fixture", cwd=path)
        return git("rev-parse", "HEAD", cwd=path)

    def test_builds_complete_verified_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            temporary = Path(temporary_name)
            jiuguan = temporary / "jiuguan"
            sillytavern = temporary / "sillytavern"
            jiuguan_revision = self.make_repo(jiuguan, "JIUGUAN.txt")
            sillytavern_revision = self.make_repo(sillytavern, "SILLYTAVERN.txt")

            sources = temporary / "sources"
            sources.mkdir()
            rows = []
            for component, version, license_id, filename in (
                ("git", "1", "GPL-2.0-only", "git.tar.xz"),
                ("less", "1", "GPL-3.0-or-later", "less.tar.gz"),
                ("libiconv", "1", "LGPL-2.1-or-later", "libiconv.tar.gz"),
                ("termux-packages", "abc", "mixed", "termux-packages.tar.gz"),
            ):
                source = sources / filename
                source.write_bytes((component + " source\n").encode())
                rows.append(
                    "\t".join(
                        (component, version, license_id, source.as_uri(), sha256(source), filename)
                    )
                )

            manifest = temporary / "sources.tsv"
            manifest.write_text("\n".join(rows) + "\n", encoding="utf-8")
            output = temporary / "corresponding-source.zip"
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--manifest",
                    str(manifest),
                    "--repo",
                    str(jiuguan),
                    "--sillytavern",
                    str(sillytavern),
                    "--cache",
                    str(temporary / "cache"),
                    "--output",
                    str(output),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            with zipfile.ZipFile(output) as bundle:
                names = set(bundle.namelist())
                self.assertEqual(
                    names,
                    {
                        "README-SOURCE.txt",
                        "SOURCE_MANIFEST.txt",
                        "source/jiuguan-source.tar",
                        "source/sillytavern-source.tar",
                        *(f"source/{row.split(chr(9))[-1]}" for row in rows),
                    },
                )
                readme = bundle.read("README-SOURCE.txt").decode()
                source_manifest = bundle.read("SOURCE_MANIFEST.txt").decode()
                self.assertIn(jiuguan_revision, readme)
                self.assertIn(jiuguan_revision, source_manifest)
                self.assertIn(sillytavern_revision, source_manifest)
                for row in rows:
                    self.assertIn(row.split("\t")[4], source_manifest)

                for line in source_manifest.splitlines():
                    if not line or not line[0].isalnum() or "  " not in line:
                        continue
                    expected, filename, *_ = line.split("  ")
                    if len(expected) != 64:
                        continue
                    self.assertEqual(
                        hashlib.sha256(bundle.read(f"source/{filename}")).hexdigest(), expected
                    )

                with bundle.open("source/jiuguan-source.tar") as archive_stream:
                    with tarfile.open(fileobj=archive_stream, mode="r|") as archive:
                        self.assertIn("JIUGUAN.txt", (member.name for member in archive))
                with bundle.open("source/sillytavern-source.tar") as archive_stream:
                    with tarfile.open(fileobj=archive_stream, mode="r|") as archive:
                        self.assertIn("SILLYTAVERN.txt", (member.name for member in archive))


if __name__ == "__main__":
    unittest.main()
