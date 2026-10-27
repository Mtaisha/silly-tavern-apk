#!/usr/bin/env python3
"""Create the source bundle shipped beside every Jiuguan APK artifact."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-f]{64}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]*$")
ZIP_TIME = (2026, 10, 18, 0, 0, 0)


def read_manifest(path: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    seen_components: set[str] = set()
    seen_names: set[str] = set()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw or raw.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) != 6 or any(not field for field in fields):
            raise ValueError(f"{path}:{number}: expected 6 non-empty tab-separated fields")
        component, version, license_id, url, expected, filename = fields
        if component in seen_components:
            raise ValueError(f"duplicate component in source manifest: {component}")
        if filename in seen_names or not SAFE_NAME.fullmatch(filename):
            raise ValueError(f"duplicate or unsafe source filename: {filename}")
        if not url.startswith("https://") and not url.startswith("file://"):
            raise ValueError(f"source URL must be HTTPS (or file:// in tests): {url}")
        if not SHA256.fullmatch(expected):
            raise ValueError(f"invalid source SHA-256 for {component}")
        seen_components.add(component)
        seen_names.add(filename)
        rows.append(
            {
                "component": component,
                "version": version,
                "license": license_id,
                "url": url,
                "sha256": expected,
                "filename": filename,
            }
        )
    required = {"git", "less", "libiconv", "termux-packages"}
    if set(seen_components) != required:
        raise ValueError(f"source manifest components must be exactly {sorted(required)}")
    return rows


def sha256_file(path: Path) -> str:
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            checksum.update(chunk)
    return checksum.hexdigest()


def download(row: dict[str, str], cache: Path) -> Path:
    destination = cache / row["filename"]
    if destination.is_file() and sha256_file(destination) == row["sha256"]:
        return destination
    destination.unlink(missing_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    temporary.unlink(missing_ok=True)
    request = urllib.request.Request(row["url"], headers={"User-Agent": "jiuguan-source-builder/1"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        actual = sha256_file(temporary)
        if actual != row["sha256"]:
            raise ValueError(
                f"SHA-256 mismatch for {row['component']}: got {actual}, expected {row['sha256']}"
            )
        os.replace(temporary, destination)
        return destination
    finally:
        temporary.unlink(missing_ok=True)


def git_text(repo: Path, *arguments: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repo), *arguments], stderr=subprocess.STDOUT, text=True, encoding="utf-8"
    ).strip()


def git_archive(repo: Path, destination: Path) -> str:
    status = git_text(repo, "status", "--porcelain", "--untracked-files=no")
    if status:
        raise ValueError(f"tracked files are dirty; refusing a stale source archive: {repo}")
    revision = git_text(repo, "rev-parse", "HEAD")
    with destination.open("wb") as output:
        subprocess.run(
            ["git", "-C", str(repo), "archive", "--format=tar", "HEAD"],
            check=True,
            stdout=output,
        )
    return revision


def zip_info(name: str, compressed: bool) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.external_attr = 0o100644 << 16
    info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
    return info


def add_file(bundle: zipfile.ZipFile, name: str, path: Path, compressed: bool) -> None:
    with path.open("rb") as source, bundle.open(zip_info(name, compressed), "w") as destination:
        while chunk := source.read(1024 * 1024):
            destination.write(chunk)


def add_text(bundle: zipfile.ZipFile, name: str, text: str) -> None:
    bundle.writestr(zip_info(name, True), text.replace("\r\n", "\n").encode("utf-8"))


def build(args: argparse.Namespace, rows: list[dict[str, str]]) -> None:
    if args.repo is None or args.sillytavern is None or args.cache is None or args.output is None:
        raise ValueError("--repo, --sillytavern, --cache and --output are required unless --check is used")

    args.repo = args.repo.resolve()
    args.sillytavern = args.sillytavern.resolve()
    downloads = [download(row, args.cache) for row in rows]

    with tempfile.TemporaryDirectory(prefix="jiuguan-source-") as temporary_name:
        temporary = Path(temporary_name)
        jiuguan_tar = temporary / "jiuguan-source.tar"
        sillytavern_tar = temporary / "sillytavern-source.tar"
        jiuguan_revision = git_archive(args.repo, jiuguan_tar)
        sillytavern_revision = git_archive(args.sillytavern, sillytavern_tar)

        manifest_lines = [
            "Jiuguan corresponding source bundle",
            "",
            f"Jiuguan commit: {jiuguan_revision}",
            f"SillyTavern commit: {sillytavern_revision}",
            "",
            "SHA-256  File  License  Upstream",
            f"{sha256_file(jiuguan_tar)}  jiuguan-source.tar  AGPL-3.0  repository snapshot",
            f"{sha256_file(sillytavern_tar)}  sillytavern-source.tar  AGPL-3.0  upstream snapshot",
        ]
        for row, path in zip(rows, downloads, strict=True):
            manifest_lines.append(
                f"{sha256_file(path)}  {path.name}  {row['license']}  {row['url']}"
            )
        manifest = "\n".join(manifest_lines) + "\n"
        readme = f"""Jiuguan corresponding source
============================

This archive accompanies the Jiuguan APK built from commit {jiuguan_revision}.
It contains:

* the exact public Jiuguan source tree, including build scripts and all patches;
* the exact upstream SillyTavern source tree used by the build;
* upstream source archives for redistributed GPL/LGPL Termux components; and
* the pinned Termux build-recipe repository used to build those components.

Apply the patches under patches/ from jiuguan-source.tar in filename order, as
documented in patches/README.md and tools/prepare.sh. See SOURCE_MANIFEST.txt
for immutable upstream URLs and SHA-256 values. JavaScript dependencies shipped
inside st-bundle.zip are already distributed in source form with their license
files.

The Jiuguan project modified SillyTavern on 2026-10-18. Source is also published
at https://github.com/Mtaisha/silly-tavern-apk and beside each signed Release APK.
"""

        args.output.parent.mkdir(parents=True, exist_ok=True)
        partial = args.output.with_name(args.output.name + ".part")
        partial.unlink(missing_ok=True)
        try:
            with zipfile.ZipFile(partial, "w", allowZip64=True) as bundle:
                add_text(bundle, "README-SOURCE.txt", readme)
                add_text(bundle, "SOURCE_MANIFEST.txt", manifest)
                add_file(bundle, "source/jiuguan-source.tar", jiuguan_tar, True)
                add_file(bundle, "source/sillytavern-source.tar", sillytavern_tar, True)
                for row, path in zip(rows, downloads, strict=True):
                    add_file(bundle, f"source/{row['filename']}", path, False)
            with zipfile.ZipFile(partial) as bundle:
                damaged = bundle.testzip()
                if damaged is not None:
                    raise ValueError(f"corresponding-source ZIP failed CRC verification: {damaged}")
            os.replace(partial, args.output)
        finally:
            partial.unlink(missing_ok=True)

        print(f"created {args.output} ({args.output.stat().st_size} bytes)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--repo", type=Path)
    parser.add_argument("--sillytavern", type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        rows = read_manifest(args.manifest)
        if not args.check:
            build(args, rows)
        return 0
    except Exception as error:
        print(f"source bundle build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
