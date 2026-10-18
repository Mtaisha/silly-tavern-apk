#!/bin/bash
# 准备 Android 构建需要、但不放进 git 的东西（GitHub Actions 里自动运行，也可以在 Linux 上手动跑）：
#   1. app/src/main/jniLibs/arm64-v8a/   Node.js + git（Termux 编译版），改名成 lib*.so 并修补 ELF
#   2. app/src/main/assets/runtime/       CA 证书、openssl.cnf、Node 预加载脚本
#   3. app/src/main/assets/npm.zip        npm（只在 app 内升级 SillyTavern 时用）
#   4. app/src/main/assets/st-bundle.zip  SillyTavern 源码 + 生产依赖 node_modules
#
# 输入：
#   ST_SRC        要打包的 SillyTavern git 仓库（已经 npm ci 过），默认 ./vendor/SillyTavern
#   vendor/debs   Termux 的 .deb 包（tools/fetch_termux.sh 下载）
# 需要：python3、dpkg-deb、zip、npm、git、patch
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ST_SRC="${ST_SRC:-$ROOT/vendor/SillyTavern}"
VENDOR="$ROOT/vendor"
STAGE="$ROOT/build-stage"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/app/src/main/assets"
PREFIX_REL="data/data/com.termux/files/usr"

echo "== 解开 Termux 软件包"
rm -rf "$VENDOR/root"; mkdir -p "$VENDOR/root"
for deb in "$VENDOR"/debs/*.deb; do
    dpkg-deb -x "$deb" "$VENDOR/root"
done
U="$VENDOR/root/$PREFIX_REL"

echo "== 原生库（按依赖关系自动找）"
rm -rf "$JNI"; mkdir -p "$JNI"
LIBS="$(python3 -I "$ROOT/tools/collect_libs.py" "$U")"
read -r -a RENAMES <<< "$(echo "$LIBS" | sed -n 's/^RENAMES //p')"
while IFS=$'\t' read -r src dst; do
    [ -n "$dst" ] || continue
    cp "$U/$src" "$JNI/$dst"
    chmod 755 "$JNI/$dst"
    python3 -I "$ROOT/tools/elfpatch.py" "$JNI/$dst" "${RENAMES[@]}"
done <<< "$(echo "$LIBS" | grep -v '^RENAMES ')"
ls -la "$JNI"

echo "== 运行时文件"
rm -rf "$ASSETS/runtime"; mkdir -p "$ASSETS/runtime"
cp "$U/etc/tls/cert.pem" "$ASSETS/runtime/cert.pem"
cp "$U/etc/tls/openssl.cnf" "$ASSETS/runtime/openssl.cnf"
cp "$ROOT/runtime/android-preload.cjs" "$ASSETS/runtime/android-preload.cjs"

echo "== npm（Termux 的 Node.js 包里自带）"
NPM_DIR="$U/lib/node_modules/npm"
rm -f "$ASSETS/npm.zip" "$ASSETS/npm.version"
if [ -f "$NPM_DIR/bin/npm-cli.js" ]; then
    (cd "$NPM_DIR" && zip -q -r -9 "$ASSETS/npm.zip" . -x 'man/*' -x 'docs/*')
    python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' "$NPM_DIR/package.json" > "$ASSETS/npm.version"
    echo "   npm $(cat "$ASSETS/npm.version")"
else
    echo "   警告：Termux 包里没找到 npm，app 内升级 SillyTavern 将不可用" >&2
fi

echo "== 分词器（SillyTavern 第一次用到时才会去 GitHub 下载，提前放进去）"
mkdir -p "$VENDOR/tokenizers" "$ASSETS/seed-cache"
rm -f "$ASSETS/seed-cache/"*
for name in command-r command-a qwen2 nemo deepseek; do
    f="$VENDOR/tokenizers/$name.json.gz"
    [ -s "$f" ] || curl -sfL --retry 3 "https://github.com/SillyTavern/SillyTavern-Tokenizers/raw/main/$name.json.gz" -o "$f" || true
    if [ -s "$f" ] && gzip -t "$f" 2>/dev/null; then
        cp "$f" "$ASSETS/seed-cache/"
    else
        echo "   跳过 $name（下载失败，不影响使用）"
        rm -f "$f"
    fi
done

echo "== 第三方许可"
NOTICES="$ASSETS/THIRD_PARTY_NOTICES.txt"
cp "$VENDOR/debs/list.tsv" "$ROOT/runtime/termux-packages.tsv"
{
    echo "「酒馆」打包了下面这些第三方软件。"
    echo "Termux 编译的程序来自 https://packages-cf.termux.dev/apt/termux-main（编译脚本："
    echo "https://github.com/termux/termux-packages）。具体版本和 SHA-256 见源码里的 runtime/termux-packages.tsv。"
    echo
    while IFS=$'\t' read -r name file _sha; do
        echo "================================================================================"
        echo "$name  ($(basename "$file"))"
        echo "================================================================================"
        for f in "$U/share/doc/$name"/{copyright,LICENSE,LICENCE.md,COPYING}; do
            [ -f "$f" ] && { cat "$f"; echo; break; }
        done
    done < "$VENDOR/debs/list.tsv"
    echo "================================================================================"
    echo "SillyTavern (AGPL-3.0) - https://github.com/SillyTavern/SillyTavern"
    echo "它自己依赖的许可证在 st-bundle.zip 里（node_modules/*/LICENSE*）。"
    echo "================================================================================"
    echo "TavernDroid (AGPL-3.0) - https://github.com/S0ul-Sh3ll/TavernDroid"
} > "$NOTICES"

echo "== 打包 SillyTavern（$ST_SRC）"
rm -rf "$STAGE"; mkdir -p "$STAGE/st"
git -C "$ST_SRC" archive HEAD | tar -x -C "$STAGE/st"
ST_REV="$(git -C "$ST_SRC" rev-parse --short HEAD)"
# patches/ 里的小修复：能打上才打（只针对特定版本写的，换了版本打不上就跳过）
PATCH_ID=""
for patch in "$ROOT"/patches/*.patch; do
    [ -f "$patch" ] || continue
    if (cd "$STAGE/st" && patch -p1 --dry-run --quiet --forward < "$patch" >/dev/null 2>&1); then
        echo "   打补丁 $(basename "$patch")"
        (cd "$STAGE/st" && patch -p1 --quiet --forward < "$patch")
        PATCH_ID="$PATCH_ID$(sha256sum "$patch" | cut -c1-4)"
    else
        echo "   跳过 $(basename "$patch")（和这个版本对不上）"
    fi
done
if [ -n "$PATCH_ID" ]; then ST_REV="$ST_REV+p$PATCH_ID"; fi
ST_VER="$(python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' "$STAGE/st/package.json")"
# 生产依赖：直接用仓库里装好的 node_modules，再离线删掉开发用的包
cp -a "$ST_SRC/node_modules" "$STAGE/st/"
(cd "$STAGE/st" && npm prune --omit=dev --no-audit --no-fund --offline >/dev/null)
# 运行时用不到的文件（source map、类型声明、说明文档、命令行入口）
find "$STAGE/st/node_modules" -type d -name .bin -prune -exec rm -rf {} +
find "$STAGE/st/node_modules" -type f \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' \
    -o -iname 'readme*' -o -iname 'changelog*' -o -iname 'history.md' \) -delete
rm -rf "$STAGE/st/tests" "$STAGE/st/colab" "$STAGE/st/docker" "$STAGE/st/.github"
# 插件和全局扩展目录在手机上会换成指向共享目录的链接，包里只留顶层文件
find "$STAGE/st/plugins" -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} + 2>/dev/null || true
printf '{"version":"%s","revision":"%s"}\n' "$ST_VER" "$ST_REV" > "$STAGE/st/.taverndroid-bundle.json"

mkdir -p "$ASSETS"
rm -f "$ASSETS/st-bundle.zip"
(cd "$STAGE/st" && zip -q -r -9 -y "$ASSETS/st-bundle.zip" .)
printf '%s-%s\n' "$ST_VER" "$ST_REV" > "$ASSETS/st-bundle.version"

du -sh "$JNI" "$ASSETS/st-bundle.zip"
echo "已打包 SillyTavern $ST_VER ($ST_REV)"
