#!/bin/bash
# 下载 runtime/termux-packages.tsv 锁定的 Termux aarch64 软件包到 vendor/debs。
# 文件名和 SHA-256 都是发布输入的一部分；软件源更新不会悄悄改变下一次 APK。
# 要升级运行时，先人工更新 lock、许可元数据和对应源码清单，再重新审查。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/debs"
LOCK="$ROOT/runtime/termux-packages.tsv"
[ -s "$LOCK" ] || { echo "缺少 $LOCK" >&2; exit 1; }
mkdir -p "$DEST"
cp "$LOCK" "$DEST/list.tsv"
MIRRORS=(
    "${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
    "https://packages.termux.dev/apt/termux-main"
    "https://grimler.se/termux/termux-main"
    "https://mirror.mwt.me/termux/main"
)
# 删掉旧版本留下的包，免得新旧两份同时被解开
for f in "$DEST"/*.deb; do
    [ -e "$f" ] || continue
    grep -q "/$(basename "$f")	" "$DEST/list.tsv" || rm -f "$f"
done
while IFS=$'\t' read -r name file sha; do
    out="$DEST/$(basename "$file")"
    if [ -f "$out" ] && printf '%s  %s\n' "$sha" "$out" | sha256sum -c - >/dev/null 2>&1; then
        echo "ok $name（缓存）"
        continue
    fi
    rm -f "$out"
    ok=0
    for repo in "${MIRRORS[@]}"; do
        echo "下载 $name：$repo/$file"
        if curl -sfL --retry 3 --connect-timeout 20 "$repo/$file" -o "$out" &&
            printf '%s  %s\n' "$sha" "$out" | sha256sum -c - >/dev/null 2>&1; then
            ok=1
            break
        fi
        rm -f "$out"
    done
    [ "$ok" = 1 ] || { echo "所有镜像都没能提供锁定且校验正确的 $name" >&2; exit 1; }
    echo "ok $name"
done < "$DEST/list.tsv"
