#!/bin/bash
# 下载 Termux 的 aarch64 软件包（Node.js LTS + git + npm 以及它们的依赖）到 vendor/debs，
# 每个文件都按软件源索引里的 SHA256 校验。主源连不上时自动换镜像。
# Termux 后来把 npm 从 nodejs-lts 里拆成了单独的包（nodejs-lts 只 Recommends 它），所以要单独点名。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/debs"
mkdir -p "$DEST"
MIRRORS=(
    "${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
    "https://packages.termux.dev/apt/termux-main"
    "https://grimler.se/termux/termux-main"
    "https://mirror.mwt.me/termux/main"
)
INDEX="$(mktemp)"
REPO=""
for m in "${MIRRORS[@]}"; do
    if curl -sfL --retry 3 --connect-timeout 20 "$m/dists/stable/main/binary-aarch64/Packages" -o "$INDEX"; then
        REPO="$m"
        break
    fi
    echo "连不上 $m，换下一个"
done
[ -n "$REPO" ] || { echo "所有 Termux 软件源都连不上" >&2; exit 1; }
echo "使用软件源 $REPO"

python3 -I - "$INDEX" nodejs-lts git npm <<'PY' > "$DEST/list.tsv"
import re, sys
pk = {}
for blk in open(sys.argv[1]).read().split('\n\n'):
    d = dict(l.split(': ', 1) for l in blk.split('\n') if ': ' in l and not l.startswith(' '))
    if 'Package' in d:
        pk[d['Package']] = d
seen = set()
def walk(n):
    if n in seen:
        return
    seen.add(n)
    for dep in re.split(r',\s*', pk[n].get('Depends', '')):
        # "a | b" 二选一时优先用已经选上的：npm 依赖 "nodejs | nodejs-lts"，
        # 不能因为 nodejs 写在前面就把非 LTS 的 Node 也拉进来（解包时会盖掉 LTS）
        alts = [a.strip().split(' ')[0] for a in dep.split('|') if a.strip()]
        if alts:
            walk(next((a for a in alts if a in seen), alts[0]))
for root in sys.argv[2:]:
    walk(root)
for n in sorted(seen):
    print(n, pk[n]['Filename'], pk[n]['SHA256'], sep='\t')
PY
rm "$INDEX"
# 删掉旧版本留下的包，免得新旧两份同时被解开
for f in "$DEST"/*.deb; do
    [ -e "$f" ] || continue
    grep -q "/$(basename "$f")	" "$DEST/list.tsv" || rm -f "$f"
done
while IFS=$'\t' read -r name file sha; do
    out="$DEST/$(basename "$file")"
    [ -f "$out" ] || curl -sfL --retry 3 "$REPO/$file" -o "$out"
    echo "$sha  $out" | sha256sum -c - >/dev/null || { echo "校验失败：$out" >&2; rm -f "$out"; exit 1; }
    echo "ok $name"
done < "$DEST/list.tsv"
