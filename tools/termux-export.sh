#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 里把 SillyTavern 的全部数据打包成「酒馆」app 能直接导入的 zip。
# Packs all of your SillyTavern data in Termux into a zip that the Jiuguan (酒馆) app can import.
# Messages are in Chinese; see README.en.md in https://github.com/Mtaisha/silly-tavern-apk for details.
#
# 在 Termux 里粘贴这一行、回车 / Paste this line into Termux and press Enter:
#   curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash
#
# 想指定目录或者跳过提问，参数写在 bash -s -- 后面（下载下来用 bash termux-export.sh 运行也一样）：
#   curl -fsSL …/termux-export.sh | bash -s -- [SillyTavern 目录] [-o 输出目录] [-y]
#     SillyTavern 目录   不写就自动在 ~ 下面找，找到好几个会让你选
#     -o 输出目录        zip 放哪，默认手机的「下载」文件夹（~/storage/downloads）
#     -y                SillyTavern 还开着也不问，直接打包
#
# 会自动做的事：
#   - 认出 config.yaml 里改过的 dataRoot（数据没放在默认的 data/ 也能找到）
#   - 第一次用时申请存储权限（弹窗点「允许」）
#   - 用 SillyTavern 自己带的压缩库打包，不用另外装 zip，也不用联网；
#     特别老、没带压缩库的 SillyTavern 才会用 zip 命令（没有的话按提示 pkg install zip）
#
# zip 里是：data/（所有用户的角色、聊天、世界书、预设、设置、secrets.json）、config.yaml、
# plugins/（服务端插件）、public/scripts/extensions/third-party/（全局扩展）。符号链接按真实文件打包。
# 不打包能自动重建的缓存：data/_webpack、data/_uploads、data/_cache。
#
# 本脚本会读取文件字节来压缩和校验，但不会解析、打印或上传内容（包括 secrets.json 里的 API key）。
set -euo pipefail

say()  { printf '%s\n' "$*"; }
warn() { printf '提示：%s\n' "$*" >&2; }
die()  { printf '出错了：%s\n' "$*" >&2; exit 1; }
# 用 curl … | bash 运行时，标准输入是脚本本身，所以提问要直接从终端读
has_tty() { ( exec < /dev/tty ) 2>/dev/null; }
ask() {
    REPLY=""
    printf '%s' "$1" > /dev/tty
    read -r REPLY < /dev/tty || REPLY=""
}
usage() {
    cat <<'EOF'
用法：bash termux-export.sh [SillyTavern 目录] [-o 输出目录] [-y]
  不写目录就自动在 ~ 下面找；-o 指定 zip 放哪（默认手机的「下载」）；-y 不提问直接打包。
EOF
}

ST="" OUT_DIR="" YES=0
while [ $# -gt 0 ]; do
    case "$1" in
        -o) [ $# -ge 2 ] || die "-o 后面要写一个目录"; OUT_DIR="$2"; shift 2 ;;
        -y) YES=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; die "不认识的参数：$1" ;;
        *) ST="$1"; shift ;;
    esac
done

# ---------------------------------------------------------------- 找 SillyTavern
is_st() {
    [ -f "$1/server.js" ] && [ -f "$1/package.json" ] &&
        grep -q '"name"[[:space:]]*:[[:space:]]*"sillytavern"' "$1/package.json"
}
if [ -n "$ST" ]; then
    is_st "$ST" || die "$ST 不是 SillyTavern 目录（里面要有 server.js，package.json 里的名字是 sillytavern）"
else
    FOUND=()
    while IFS= read -r f; do
        d="$(dirname "$f")"
        if is_st "$d"; then FOUND+=("$d"); fi
    done < <(find "$HOME" -maxdepth 4 -name server.js -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null | sort)
    case ${#FOUND[@]} in
        0) die "在 $HOME 下面没找到 SillyTavern。装在别处的话，把目录写在命令后面：… | bash -s -- /你的/SillyTavern" ;;
        1) ST="${FOUND[0]}" ;;
        *)
            say "找到好几个 SillyTavern："
            for i in "${!FOUND[@]}"; do say "  $((i + 1)). ${FOUND[$i]}"; done
            has_tty || die "请把要打包的那个目录写在命令后面"
            ask "要打包哪一个？输入编号回车："
            [[ "$REPLY" =~ ^[0-9]+$ ]] && [ "$REPLY" -ge 1 ] && [ "$REPLY" -le ${#FOUND[@]} ] || die "没有这个编号"
            ST="${FOUND[$((REPLY - 1))]}"
            ;;
    esac
fi
ST="$(cd "$ST" && pwd -P)"
ST_VER="$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$ST/package.json" | head -1)"

# 数据目录：config.yaml 里的 dataRoot，相对路径从 SillyTavern 目录算起（跟它自己启动时一样）
DATA="$ST/data"
if [ -f "$ST/config.yaml" ]; then
    DR="$(sed -n 's/^dataRoot:[[:space:]]*//p' "$ST/config.yaml" | head -1 |
        sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//' -e "s/^[\"']//" -e "s/[\"']\$//")"
    if [ -n "$DR" ]; then
        case "$DR" in /*) DATA="$DR" ;; *) DATA="$ST/$DR" ;; esac
    fi
fi
[ -d "$DATA" ] || die "数据目录 $DATA 不存在（config.yaml 里的 dataRoot 写对了吗？）"
DATA="$(cd "$DATA" && pwd -P)"

USERS=()
for u in "$DATA"/*/; do
    [ -d "$u" ] || continue
    n="$(basename "$u")"
    case "$n" in _*) continue ;; esac
    if [ -e "$u/settings.json" ] || [ -d "$u/characters" ]; then USERS+=("$n"); fi
done
[ ${#USERS[@]} -gt 0 ] || die "$DATA 里没有任何用户的数据（比如 default-user/），是不是还没用过这个 SillyTavern？"

say "SillyTavern ${ST_VER:-?}：$ST"
[ "$DATA" = "$ST/data" ] || say "数据目录（config.yaml 里的 dataRoot）：$DATA"

if command -v pgrep >/dev/null 2>&1 && pgrep -f 'node.*server\.js' >/dev/null 2>&1; then
    warn "SillyTavern 好像还开着。最好先回到它的窗口按 Ctrl+C 关掉再打包，数据更完整。"
    if [ "$YES" != 1 ] && has_tty; then
        ask "仍然继续？[y/N] "
        case "$REPLY" in y | Y | yes) ;; *) say "已取消。"; exit 1 ;; esac
    fi
fi

# ---------------------------------------------------------------- 放到哪
if [ -z "$OUT_DIR" ]; then
    OUT_DIR="$HOME/storage/downloads"
    if [ ! -d "$OUT_DIR" ]; then
        # ~/storage 完全不存在时 termux-setup-storage 不会提问，只弹系统授权窗；
        # 已经存在但坏了的话它会问 y/n，那种情况让用户自己去跑。
        if [ ! -e "$HOME/storage" ] && command -v termux-setup-storage >/dev/null 2>&1; then
            say "第一次用需要授权访问手机存储：马上会弹出系统窗口，请点「允许」。"
            if has_tty; then termux-setup-storage < /dev/tty || true; else termux-setup-storage < /dev/null || true; fi
            for _ in $(seq 1 60); do
                [ -d "$OUT_DIR" ] && break
                sleep 1
            done
        fi
        [ -d "$OUT_DIR" ] || die "没法写入手机的「下载」文件夹。请先运行 termux-setup-storage，在弹窗里点允许，然后再运行一次。"
    fi
fi
mkdir -p "$OUT_DIR" 2>/dev/null || true
[ -d "$OUT_DIR" ] && [ -w "$OUT_DIR" ] || die "没法写入 $OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
case "$OUT_DIR" in
    "$HOME/storage/downloads" | /storage/emulated/0/Download | /sdcard/Download) OUT_LABEL="下载" ;;
    *) OUT_LABEL="$OUT_DIR" ;;
esac

# 粗算一下空间：压缩后一般更小，剩余空间连数据的一半都不到才拦下
NEED_KB="$(du -sk --exclude=_webpack --exclude=_uploads --exclude=_cache "$DATA" 2>/dev/null | cut -f1)"
for p in "$ST/plugins" "$ST/public/scripts/extensions/third-party"; do
    if [ -d "$p" ]; then NEED_KB=$((NEED_KB + $(du -sk "$p" 2>/dev/null | cut -f1))); fi
done
AVAIL_KB="$(df -Pk "$OUT_DIR" 2>/dev/null | awk 'NR == 2 { print $4 }')"
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt $((NEED_KB / 2)) ]; then
    die "手机空间不够：数据大约 $((NEED_KB / 1024)) MB，$OUT_LABEL 所在的存储只剩 $((AVAIL_KB / 1024)) MB"
fi

NAME="酒馆数据包-$(date +%Y%m%d-%H%M).zip"
[ -e "$OUT_DIR/$NAME" ] && NAME="酒馆数据包-$(date +%Y%m%d-%H%M%S).zip"
OUT="$OUT_DIR/$NAME"
PART="$OUT_DIR/.$NAME.part"
trap 'rm -f "$PART"' EXIT

# ---------------------------------------------------------------- 打包
pack_with_node() {
    node - "$ST" "$DATA" "$PART" "SillyTavern ${ST_VER:-?} · $(date '+%Y-%m-%d %H:%M') · termux-export.sh" <<'JS'
const fs = require('fs');
const path = require('path');
const [stDir, dataDir, outFile, comment] = process.argv.slice(2);
const archiver = require(path.join(stDir, 'node_modules', 'archiver'));

// 先把要打包的文件列出来（顺着符号链接走，只跳过绕回自己上层的循环链接），这样能显示进度
const entries = [];
const skipped = [];
function walk(abs, name, skipNames, ancestors) {
    let real;
    try { real = fs.realpathSync(abs); } catch { skipped.push(name + '/'); return; }
    if (ancestors.has(real)) { skipped.push(name + '/（循环链接）'); return; }
    const chain = new Set(ancestors).add(real);
    entries.push({ name: name + '/' });
    let names;
    try { names = fs.readdirSync(abs).sort(); } catch { skipped.push(name + '/'); return; }
    for (const child of names) {
        if (skipNames.has(child)) continue;
        const childAbs = path.join(abs, child);
        const childName = `${name}/${child}`;
        let stat;
        try { stat = fs.statSync(childAbs); } catch { skipped.push(childName); continue; } // 坏链接
        if (stat.isDirectory()) walk(childAbs, childName, new Set(), chain);
        else if (stat.isFile()) entries.push({ abs: fs.realpathSync(childAbs), name: childName });
    }
}
walk(dataDir, 'data', new Set(['_webpack', '_uploads', '_cache']), new Set());
const config = path.join(stDir, 'config.yaml');
if (fs.existsSync(config)) entries.push({ abs: fs.realpathSync(config), name: 'config.yaml' });
for (const [dir, name] of [
    [path.join(stDir, 'plugins'), 'plugins'],
    [path.join(stDir, 'public', 'scripts', 'extensions', 'third-party'), 'public/scripts/extensions/third-party'],
]) {
    if (fs.existsSync(dir)) walk(dir, name, new Set(), new Set());
}

const archive = archiver('zip', { zlib: { level: 6 }, comment });
const output = fs.createWriteStream(outFile);
let added = 0;
let lastShown = 0;
const total = entries.length;
const fail = (error) => {
    process.stderr.write(`\n打包失败：${error && error.message ? error.message : error}\n`);
    process.exit(1);
};
archive.on('warning', fail);
archive.on('error', fail);
archive.on('entry', () => {
    added++;
    const now = Date.now();
    if (now - lastShown > 300 || added === total) {
        lastShown = now;
        process.stderr.write(`\r正在打包… ${added}/${total}`);
    }
});
output.on('error', fail);
output.on('close', () => {
    process.stderr.write('\n');
    if (skipped.length) {
        process.stderr.write(`有 ${skipped.length} 项读不到（多半是失效的快捷链接）：\n`);
        for (const s of skipped.slice(0, 20)) process.stderr.write(`  ${s}\n`);
        if (skipped.length > 20) process.stderr.write('  …\n');
        return fail(new Error('有项目未能加入压缩包；为避免生成缺数据的备份，已经停止'));
    }
    // 打完后逐条读回数据，核对未压缩长度和 CRC-32，确认 zip 没坏、条目一个不少
    let yauzl;
    try { yauzl = require(path.join(stDir, 'node_modules', 'yauzl')); }
    catch (error) { return fail(new Error(`没法加载 zip 校验库 yauzl：${error.message}`)); }
    const crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let value = n;
        for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
        crcTable[n] = value >>> 0;
    }
    const updateCrc = (crc, chunk) => {
        for (const byte of chunk) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
        return crc >>> 0;
    };
    yauzl.open(outFile, { lazyEntries: true }, (error, zip) => {
        if (error) return fail(error);
        let verified = 0;
        zip.on('entry', (entry) => {
            if (entry.fileName.endsWith('/')) {
                verified++;
                zip.readEntry();
                return;
            }
            zip.openReadStream(entry, (streamError, stream) => {
                if (streamError) return fail(streamError);
                let bytes = 0;
                let crc = 0xffffffff;
                stream.on('data', (chunk) => {
                    bytes += chunk.length;
                    crc = updateCrc(crc, chunk);
                });
                stream.on('error', fail);
                stream.on('end', () => {
                    crc = (crc ^ 0xffffffff) >>> 0;
                    if (bytes !== entry.uncompressedSize) {
                        return fail(new Error(`${entry.fileName} 解压后是 ${bytes} 字节，应该是 ${entry.uncompressedSize} 字节`));
                    }
                    if (crc !== (entry.crc32 >>> 0)) {
                        return fail(new Error(`${entry.fileName} 的 CRC-32 不对，压缩包内容已损坏`));
                    }
                    verified++;
                    zip.readEntry();
                });
            });
        });
        zip.on('end', () => {
            if (verified !== added) return fail(new Error(`zip 里验证了 ${verified} 项，应该有 ${added} 项`));
            process.stderr.write(`校验完成：${verified} 项都能完整读回，CRC-32 正确。\n`);
        });
        zip.on('error', fail);
        zip.readEntry();
    });
});
archive.pipe(output);
for (const entry of entries) {
    if (entry.abs) archive.file(entry.abs, { name: entry.name });
    else archive.append(Buffer.alloc(0), { name: entry.name });
}
archive.finalize();
JS
}

pack_with_zip() {
    # zip 只认相对路径，所以先搭一个「影子目录」，用符号链接把各部分摆成 zip 里该有的样子
    local stage
    stage="$(mktemp -d)"
    ln -s "$DATA" "$stage/data"
    local items=(data)
    if [ -f "$ST/config.yaml" ]; then ln -s "$ST/config.yaml" "$stage/config.yaml"; items+=(config.yaml); fi
    if [ -d "$ST/plugins" ]; then ln -s "$ST/plugins" "$stage/plugins"; items+=(plugins); fi
    if [ -d "$ST/public/scripts/extensions/third-party" ]; then
        mkdir -p "$stage/public/scripts/extensions"
        ln -s "$ST/public/scripts/extensions/third-party" "$stage/public/scripts/extensions/third-party"
        items+=(public)
    fi
    say "正在打包（用 zip 命令）…"
    # 不带 -y：符号链接按真实文件打包。LC_ALL=C.UTF-8：让 zip 给中文文件名打上 UTF-8 标记
    if ! (cd "$stage" && LC_ALL=C.UTF-8 zip -r -q "$PART" "${items[@]}" -x 'data/_webpack/*' -x 'data/_uploads/*' -x 'data/_cache/*'); then
        rm -rf "$stage"
        die "zip 打包失败"
    fi
    rm -rf "$stage"
    say "正在逐条读回并校验压缩包…"
    unzip -tqq "$PART" >/dev/null || die "zip 校验失败，压缩包没有保存"
}

PACKER="${JIUGUAN_PACKER:-auto}"
if [ "$PACKER" = auto ]; then
    if command -v node >/dev/null 2>&1 &&
        [ -f "$ST/node_modules/archiver/package.json" ] &&
        [ -f "$ST/node_modules/yauzl/package.json" ]; then
        PACKER=node
    elif command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
        PACKER=zip
    else
        die "这个 SillyTavern 太老，没有自带压缩库，Termux 里也没装 zip/unzip。先运行 pkg install zip unzip 再试一次。"
    fi
fi
case "$PACKER" in
    node)
        command -v node >/dev/null 2>&1 &&
            [ -f "$ST/node_modules/archiver/package.json" ] &&
            [ -f "$ST/node_modules/yauzl/package.json" ] ||
            die "缺少完整的 Node 压缩/校验库（archiver、yauzl）"
        pack_with_node
        ;;
    zip)
        command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 || die "缺少 zip/unzip：pkg install zip unzip"
        pack_with_zip
        ;;
    *) die "JIUGUAN_PACKER 只能是 node 或 zip" ;;
esac
mv "$PART" "$OUT"

# ---------------------------------------------------------------- 报告
count_in() {
    local n=0 u
    for u in "${USERS[@]}"; do
        if [ -d "$DATA/$u/$1" ]; then n=$((n + $(find "$DATA/$u/$1" -mindepth 1 -maxdepth 1 | wc -l))); fi
    done
    echo "$n"
}
PLUGINS=0
[ -d "$ST/plugins" ] && PLUGINS="$(find "$ST/plugins" -mindepth 1 -maxdepth 1 -type d | wc -l)"
EXTS="$(count_in extensions)"
[ -d "$ST/public/scripts/extensions/third-party" ] &&
    EXTS=$((EXTS + $(find "$ST/public/scripts/extensions/third-party" -mindepth 1 -maxdepth 1 -type d | wc -l)))

say ""
say "完成！$OUT_LABEL/$NAME（$(du -h "$OUT" | cut -f1)）"
say "  用户 ${#USERS[@]} 个：${USERS[*]}"
say "  角色卡 $(count_in characters)，聊天 $(count_in chats) 个角色的记录，世界书 $(count_in worlds)，扩展 $EXTS 个，服务端插件 $PLUGINS 个"
for u in "${USERS[@]}"; do
    if [ -f "$DATA/$u/secrets.json" ]; then
        say "里面有 secrets.json（你的 API key）：只在自己手机上用，别发给别人，也别传到网上。"
        break
    fi
done
say "接下来：打开「酒馆」app → 输入框左边的三条横线 → 酒馆 App 设置 → 数据 → 导入数据包（zip），在「$OUT_LABEL」里选这个文件。"
