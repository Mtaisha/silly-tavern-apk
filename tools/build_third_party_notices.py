#!/usr/bin/env python3
"""Build deterministic Termux notices and fail on missing license text."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path


LICENSE_NAMES = re.compile(r"^(?:copying|copyright|licen[cs]e|notice)(?:\..*)?$", re.IGNORECASE)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")


def read_tsv(path: Path, columns: int) -> list[list[str]]:
    rows: list[list[str]] = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw or raw.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) != columns or any(not field for field in fields):
            raise ValueError(f"{path}:{number}: expected {columns} non-empty tab-separated fields")
        rows.append(fields)
    return rows


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download(url: str, expected: str, destination: Path) -> bytes:
    if not SHA256.fullmatch(expected):
        raise ValueError(f"invalid SHA-256 for {url}: {expected}")
    if destination.is_file():
        data = destination.read_bytes()
        if digest(data) == expected:
            return data
        destination.unlink()

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    temporary.unlink(missing_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "jiuguan-license-builder/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        data = temporary.read_bytes()
        actual = digest(data)
        if actual != expected:
            raise ValueError(f"SHA-256 mismatch for {url}: got {actual}, expected {expected}")
        os.replace(temporary, destination)
        return data
    finally:
        temporary.unlink(missing_ok=True)


def decode(data: bytes, label: str) -> str:
    for encoding in ("utf-8", "utf-8-sig", "latin-1"):
        try:
            return data.decode(encoding).replace("\r\n", "\n").replace("\r", "\n").strip()
        except UnicodeDecodeError:
            pass
    raise ValueError(f"cannot decode license text: {label}")


def meaningful(text: str) -> bool:
    compact = " ".join(text.split())
    if len(compact) < 80:
        return False
    if len(text.splitlines()) <= 4 and re.search(r"\b(?:please\s+)?see\s+(?:the\s+)?(?:file\s+)?(?:licen[cs]e|copying)\b", compact, re.I):
        return False
    return True


def package_documents(doc_root: Path) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    if not doc_root.is_dir():
        return found
    for path in sorted(doc_root.rglob("*"), key=lambda item: item.as_posix().lower()):
        name = path.name[:-3] if path.name.lower().endswith(".gz") else path.name
        if not path.is_file() or not LICENSE_NAMES.fullmatch(name):
            continue
        raw = path.read_bytes()
        if path.name.lower().endswith(".gz"):
            raw = gzip.decompress(raw)
        text = decode(raw, str(path))
        if meaningful(text):
            found.append((path.relative_to(doc_root).as_posix(), text))
    return found


def build(args: argparse.Namespace) -> None:
    packages = read_tsv(args.packages, 3)
    metadata_rows = read_tsv(args.metadata, 4)
    fallback_rows = read_tsv(args.fallbacks, 4)

    metadata: dict[str, tuple[str, str, str]] = {}
    for name, binary_sha, license_id, homepage in metadata_rows:
        if name in metadata:
            raise ValueError(f"duplicate metadata package: {name}")
        if not SHA256.fullmatch(binary_sha):
            raise ValueError(f"invalid binary SHA-256 for {name}")
        metadata[name] = (binary_sha, license_id, homepage)

    package_names = [row[0] for row in packages]
    if len(package_names) != len(set(package_names)):
        raise ValueError("duplicate package in lock file")
    if set(package_names) != set(metadata):
        missing = sorted(set(package_names) - set(metadata))
        extra = sorted(set(metadata) - set(package_names))
        raise ValueError(f"package metadata mismatch; missing={missing}, extra={extra}")

    fallbacks: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for name, filename, url, expected in fallback_rows:
        if name not in metadata:
            raise ValueError(f"fallback refers to unknown package: {name}")
        if Path(filename).name != filename or filename in {".", ".."}:
            raise ValueError(f"unsafe fallback filename: {filename}")
        if not url.startswith("https://") and not url.startswith("file://"):
            raise ValueError(f"fallback URL must be HTTPS (or file:// in tests): {url}")
        fallbacks[name].append((filename, url, expected))

    recipe_commit = args.recipe_commit.read_text(encoding="ascii").strip()
    if not COMMIT.fullmatch(recipe_commit):
        raise ValueError(f"invalid Termux recipe commit: {recipe_commit}")

    sections = [
        "Jiuguan redistributes the pinned Termux packages listed below.",
        "Every section includes the binary hash, upstream license, and immutable Termux recipe.",
        f"Termux recipe commit: {recipe_commit}",
        "Package lock: runtime/termux-packages.tsv",
        "",
    ]

    for name, archive_path, binary_sha in packages:
        expected_sha, license_id, homepage = metadata[name]
        if binary_sha != expected_sha:
            raise ValueError(
                f"{name}: binary hash changed; update metadata and re-audit licenses "
                f"({binary_sha} != {expected_sha})"
            )

        recipe = f"https://github.com/termux/termux-packages/tree/{recipe_commit}/packages/{name}"
        sections.extend(
            [
                "=" * 80,
                f"{name}  ({Path(archive_path).name})",
                "=" * 80,
                f"License: {license_id}",
                f"Homepage: {homepage}",
                f"Binary SHA-256: {binary_sha}",
                f"Termux recipe: {recipe}",
                "",
            ]
        )

        documents = package_documents(args.termux_root / "share" / "doc" / name)
        for filename, url, expected in fallbacks.get(name, []):
            data = download(url, expected, args.cache / name / filename)
            text = decode(data, url)
            if not meaningful(text):
                raise ValueError(f"fallback license text is only a pointer or is too short: {url}")
            documents.append((f"curated/{filename}", text))

        unique: list[tuple[str, str]] = []
        seen: set[str] = set()
        for label, text in documents:
            fingerprint = digest(text.encode("utf-8"))
            if fingerprint not in seen:
                seen.add(fingerprint)
                unique.append((label, text))

        if not unique and license_id.lower() != "public domain":
            raise ValueError(f"{name}: no complete license/copyright text found")
        if not unique:
            unique.append(("public-domain-notice", f"{name} is identified by its Termux recipe as Public Domain."))

        for label, text in unique:
            sections.extend([f"--- {label} ---", text, ""])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(sections).rstrip() + "\n", encoding="utf-8", newline="\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--packages", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--fallbacks", type=Path, required=True)
    parser.add_argument("--recipe-commit", type=Path, required=True)
    parser.add_argument("--termux-root", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    try:
        build(parse_args())
        return 0
    except Exception as error:
        print(f"third-party notice build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
