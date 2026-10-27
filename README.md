<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

# SillyTavern APK（酒馆）

中文 | [English](README.en.md)

无需部署，不需要 Termux。直接下载 APK，安装后点开就是完整的 SillyTavern；Node.js 和 git 都已经装在 app 里。
适用于 arm64 安卓手机（Android 10 及以上），界面全中文，并针对 vivo / OriginOS 的省电策略做过适配。

---

## 直接下载 APK

- 下载：到 [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases/latest) 下载最新的 `jiuguan-<版本>-st<SillyTavern 版本>-arm64.apk`，用手机浏览器打开就能直接下。
- 更新 app：新版本都发在 [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases)。下载新 APK 直接覆盖安装，不用卸载，聊天、角色、设置都还在。
  app 里 ☰ → 酒馆 App 设置 → 关于 → 源代码和新版本，也能跳到这个仓库。
- 更新 SillyTavern 本体：不用等新 APK，app 里就能升级，见 [升级 SillyTavern](#升级-sillytavern)。
- 只有 arm64-v8a 版本（这几年的安卓手机基本都是）。

---

## 从 Termux 导出并导入：三步

原来在 Termux 里用 SillyTavern 的，数据可以整个搬进来：角色、聊天、世界书、预设、设置、API key、扩展和服务端插件都会带上。

1. 在 Termux 里粘贴这一行、回车（最好先按 Ctrl+C 关掉正在运行的 SillyTavern）：

   ```bash
   curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash
   ```

   第一次会弹存储权限，点「允许」。跑完手机的「下载」文件夹里会多一个 `酒馆数据包-日期.zip`。
2. 装好酒馆，打开一次，等它进到 SillyTavern 界面。
3. ☰ → 酒馆 App 设置 → 数据 → 导入数据包（zip）→ 选刚才那个 zip。app 会先自动备份现有数据，再导入，然后重启酒馆。

导出脚本是本仓库的配套工具，源码就是 [`tools/termux-export.sh`](tools/termux-export.sh)。它会读取文件字节来压缩和校验，但不会解析、打印或上传内容。
数据包里有你的 API key，不要发给别人或传到网上。脚本的更多用法、数据包格式、导入时具体做了什么，见 [导入数据（详细说明）](#导入数据详细说明)。

---

## 安装和使用说明

### 安装

1. 手机浏览器打开 [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases/latest)，下载 APK。
2. 点开 APK。第一次会提示「禁止安装未知来源应用」：点「设置」，给你用的浏览器或文件管理打开「允许安装未知应用」，返回继续安装。
   - vivo / OriginOS 可能会弹「安全检测」或要求输入 vivo 账号密码，选「继续安装」/ 输密码即可。
3. 以后有新版本：直接装新 APK 覆盖，不用卸载，数据都在。

> ⚠️ 不要卸载再装——卸载会把 app 里的数据一起删掉。要换手机或重装前，先 [导出数据](#导出数据)。

### 日常使用：跟普通 app 一样

- 点开图标才启动酒馆后台。还没起来时显示开屏（开屏图、花体的「Silly Tavern」和一条细进度条）。第一次打开要解压程序，会久一点（一两分钟）。
- 切到别的 app（比如去点个外卖）：酒馆继续在后台跑，通知栏有一个「酒馆 · 营业中」的常驻通知。回来直接接着用，正在生成的回复也会继续。
- 在最近任务里把酒馆划掉：酒馆的 Node 进程、前台服务、唤醒锁全部结束，app 进程也退出，不留任何后台。通知栏的「退出」按钮、App 设置里的「退出」效果一样。
- 按返回键：先关掉酒馆里打开的面板 / 弹窗；没有可关的就把 app 退到后台（不会停止）。
- App 设置在哪：酒馆左下角 ☰ 菜单 → 酒馆 App 设置；或者长按桌面图标 → App 设置。

酒馆只在手机本机 `127.0.0.1` 上监听，默认不对局域网开放（App 设置 → 高级 里可以手动打开）。

---

## 更多说明

- [导入数据（详细说明）](#导入数据详细说明)
- [导出数据](#导出数据)
- [屏幕：全屏、缩放、刘海、键盘、横屏](#屏幕全屏缩放刘海键盘横屏)
- [开屏和图标](#开屏和图标)
- [省电流畅](#省电流畅)
- [插件和扩展](#插件和扩展)
- [升级 SillyTavern](#升级-sillytavern)
- [vivo / OriginOS 要开的开关](#vivo--originos-要开的开关)
- [出问题去哪里看](#出问题去哪里看)
- [和 TavernDroid 原版相比改了什么](#和-taverndroid-原版相比改了什么)
- [自己构建](#自己构建)
- [手机测试清单](#手机测试清单)
- [源码结构](#源码结构)
- [许可](#许可)

---

## 导入数据（详细说明）

### 数据包格式

一个 zip，里面的结构是（最外面多包一层文件夹也可以）：

```
酒馆数据包.zip
├── data/                                     ← 必须。Termux 里 SillyTavern/data/ 整个文件夹
│   ├── default-user/                         ← 用户文件夹（开了多用户就有多个）
│   │   ├── characters/                       角色卡
│   │   ├── chats/  group chats/  groups/     聊天记录、群聊
│   │   ├── worlds/                           世界书
│   │   ├── OpenAI Settings/  TextGen Settings/  instruct/  context/  sysprompt/ …  预设
│   │   ├── extensions/                       给这个用户装的第三方扩展（git 装的）
│   │   ├── settings.json                     酒馆设置
│   │   ├── secrets.json                      你的各家 API key
│   │   └── …（头像、背景、QuickReplies 等，全部原样）
│   ├── _storage/  cookie-secret.txt …        有就一起带上
├── config.yaml                               ← 可选。SillyTavern/config.yaml
├── plugins/                                  ← 可选。SillyTavern/plugins/（服务端插件）
└── public/scripts/extensions/third-party/    ← 可选。全局第三方扩展
    （也可以写成顶层的 extensions/，效果一样）
```

不用带：SillyTavern 程序本身（`src/`、`public/` 其余部分、`node_modules/`）——带了也会被自动忽略。
也不用带 `data/_webpack/`、`data/_uploads/`、`data/_cache/`（会自动重建）。

另外这几种也认：
- 只有用户文件夹的 zip（最外层就是 `default-user/`）；
- SillyTavern 界面里「下载备份」得到的用户备份（最外层直接是 `settings.json`、`chats/`、`characters/`…，会导入到 `default-user`）；
- 本 app 自己导出的数据包。

### 在 Termux 里打包

就是 [三步](#从-termux-导出并导入三步) 里的那一行：

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash
```

跑完手机的「下载」文件夹里会多一个 `酒馆数据包-日期.zip`。它会自己处理这些事：

- 在 `~` 下面找到 SillyTavern；config.yaml 里改过 dataRoot 的，也能找到数据
- 第一次用时申请存储权限，弹窗点「允许」就行
- 用 SillyTavern 自带的压缩库打包（就是它「下载备份」用的那个），不用装 zip，也不用联网。特别老、没带这个库的版本才会改用 zip/unzip 命令，没装的话按提示 `pkg install zip unzip`
- 打完逐条读回 zip，核对条目数、解压后长度和 CRC-32；有文件读不到或任一条损坏就不保存数据包

装在别的地方，或者 `~` 下面有好几个 SillyTavern 时，把目录写在 `bash -s --` 后面，其他参数也写在那里：

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash -s -- /你的/SillyTavern
```

- `-o 目录`：zip 放到别处（默认是手机的「下载」）
- `-y`：SillyTavern 还开着也不提问，直接打包

脚本只把读取到的文件字节用于压缩和完整性校验，不解析、不打印、不上传内容。

不想跑脚本的话，也可以在 SillyTavern 目录里手动打包（这样不认 dataRoot，数据必须在默认的 `data/` 里）：

```bash
zip -r ~/storage/downloads/酒馆数据包.zip data config.yaml plugins public/scripts/extensions/third-party \
    -x 'data/_webpack/*' -x 'data/_uploads/*' -x 'data/_cache/*'
```

（不要加 `-y`，让符号链接按真实文件打包。）

### 在 app 里导入

☰ → 酒馆 App 设置 → 数据 → 导入数据包（zip）→ 选 zip → 开始导入。app 会依次：

1. 把 app 里现有的数据先备份到 `下载/酒馆/自动备份/导入前-日期.zip`（自动备份只留最近 3 个）。备份失败就不会继续导入。
2. 暂停酒馆。
3. 合并覆盖：zip 里有的文件覆盖进来，zip 里没有的保留不动。扩展和插件按「整个文件夹」替换——zip 里带了哪个扩展，就换成 zip 里那一份（避免新旧两份 git 文件混在一起）；zip 里没带的扩展保留。
4. 重新打开酒馆，并问你要不要顺便开启「[省电流畅](#省电流畅)」（不点就不改）。

关于 `secrets.json`（API key）：它只在你手机本地从 zip 解出来、写进 app 的私有目录。app 不会打印、显示或上传它的内容，构建过程也完全接触不到它。不要把数据包发给别人或传到网上。

> 从旧版 SillyTavern 导过来的数据，第一次启动时 SillyTavern 会自己做数据迁移，耐心等它启动完。

---

## 导出数据

☰ → 酒馆 App 设置 → 数据 → 一键导出全部数据。生成 `下载/酒馆/酒馆数据-日期.zip`，格式跟上面的数据包一样（`data/`、`config.yaml`、`plugins/`、`extensions/`），以后可以直接再导入。

聊天里导出的单个聊天记录、角色卡等文件，都保存到 `下载/酒馆/`，保存后有通知，点一下就能打开。

---

## 屏幕：全屏、缩放、刘海、键盘、横屏

全在 ☰ → 酒馆 App 设置 → 显示：

- 没有地址栏，酒馆铺满整个屏幕。
- 收起状态栏 / 收起导航条：两个开关分开设置。收起后从屏幕边缘滑一下可以临时叫出来。
- 整体缩放：滑块 80%～150%（每格 5%），字、按钮、图标一起放大缩小，页面始终正好铺满屏幕、不会横向溢出。返回聊天界面立刻生效，会一直记住。
- 刘海、挖孔、圆角屏会自动留出安全区；键盘弹出时界面跟着往上收，输入框不会被挡住。
- 横屏：跟随手机的「自动旋转」开关。
- 屏幕常亮：停在聊天界面时不自动息屏（默认关）。

---

## 开屏和图标

点开酒馆时先看到开屏：一张图、花体的「Silly Tavern」、一条细进度条。默认的开屏图和图标，原图都在 `design/`。

☰ → 酒馆 App 设置 → 开屏和图标：

- 换开屏图片：选一张图铺满整个开屏，字和进度条压在下方（竖图最合适），下次打开酒馆时生效；「恢复默认开屏」换回默认图。
- 用一张图做桌面图标：安卓不允许 app 换掉自己的图标，所以会在桌面上新建一个用这张图的「酒馆」快捷方式，点它一样打开酒馆，原来的图标可以收进文件夹。以后再选一张，会直接换掉这个快捷方式的图。vivo 等手机要在手机设置里给「酒馆」打开「桌面快捷方式」权限，不然点了没反应。
- 通知的小图标也跟着换：通知左边和状态栏的小图标安卓只允许单色剪影，会从这张图里抠出图案做成白色剪影。背景干净的图抠得最好；抠不出像样的图案（比如背景很花的照片）就继续用默认的门剪影。vivo 等国产系统的通知左边画的是 app 本体的图标，app 自己改不了，那里一直是默认的门；想换只能换默认图标重新编译（见下）。「恢复默认图标」把通知换回默认的门，桌面上建的快捷方式要自己长按移除。

自己编译、想换默认图的话：替换 `design/` 里的两张原图，运行 `python design/make_assets.py`（需要 Pillow），再重新构建。构图不一样时要改脚本里的裁剪坐标。

---

## 省电流畅

可选，不点就不改。在 App 设置 → 显示 → 一键「省电流畅」，或者导入数据后弹出的对话框里点。会把当前用户的这四项改掉（跟你自己在酒馆「用户设置」里点一样，会正常保存）：

| 设置 | 改成 |
|---|---|
| No Blur Effect | 开 |
| Reduced Motion | 开 |
| Streaming FPS | 12 |
| # Messages to Load | 30 |

说明：酒馆里 Streaming FPS 滑块每格是 5，所以滑块会停在 10 的位置，但旁边的数字框和实际生效的值都是 12。想改回来直接在酒馆用户设置里改就行。

app 另外默认开着硬件加速，并让 WebView 提前栅格化屏幕外的内容，长聊天滚动更顺。

---

## 插件和扩展

- 前端扩展（用 git 装的第三方扩展）：直接在酒馆的「扩展 → 安装扩展」里填 git 地址安装，app 自带 git。需要能连上 GitHub（开 VPN）。
  - 只给当前用户装的在 `data/<用户>/extensions/`，给所有用户装的在共享的 `extensions/`。
- 服务端插件（server plugins）：默认强制开启（App 设置 → 高级 里可以关）。放在共享的 `plugins/` 目录，从数据包导入即可；酒馆启动时会照常尝试用 git 自动更新插件。
- 插件和扩展都放在程序目录之外，升级 SillyTavern 不会把它们冲掉。
- 没有额外打包 Python / uvx 之类的环境。插件如果自带编译过的原生模块（`.node` 文件），可能需要在 app 里重新安装。

---

## 升级 SillyTavern

☰ → 酒馆 App 设置 → SillyTavern 本体 → 检查更新。有新版本时按钮会变成「升级到 x.y.z」，点了之后：

1. 先把数据自动备份到 `下载/酒馆/自动备份/升级前-日期.zip`；
2. 从 GitHub 下载新版本源码；
3. 用 app 自带的 npm 在手机上安装依赖（跟 Termux 里 `npm install` 一样，几分钟）；
4. 停几秒，换成新程序，自动重启。

下载和装依赖的时候可以继续聊天。需要能连上 GitHub 和 npm（开 VPN 即可）；npm 慢的话，在同一个地方点「用国内镜像」。
升级只替换程序目录，数据、config.yaml、插件、扩展都不动。升级失败的话还是原来的版本，数据也不变。

退回：恢复到安装包自带版本（或者「修复程序文件」）会重新解压 APK 里带的 SillyTavern，数据不受影响。
以后装了带更高版本 SillyTavern 的新 APK，也会自动换成新 APK 里的版本。

app 本身的新版本不在这里升级，去 [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases) 下载新 APK 覆盖安装。

---

## vivo / OriginOS 要开的开关

vivo 的省电策略很凶，下面几项决定了「切出去 / 息屏后回复还能不能出来」。菜单名字在不同 OriginOS 版本里可能略有不同：

1. 电池不限制：第一次生成回复时 app 会问你，点「去允许」；或者 App 设置 → 后台运行 → 允许不限制，在系统弹窗里选允许。
2. 允许后台高耗电：App 设置 → 后台运行 → 系统应用设置 → 电池（或「耗电管理」）→ 打开「允许后台高耗电」。
   也可以从 手机设置 → 电池 → 后台耗电管理 → 酒馆 → 允许后台高耗电。
3. 通知：允许酒馆发通知（第一次打开会问）。常驻通知能让系统知道酒馆正在前台服务；角色在你切出去时回复了也会通知你。
4. 不要在最近任务里给酒馆「加锁」——你想要的是划掉就能彻底停，加锁会让一键清理清不掉它。
5. 不需要开「自启动」「关联启动」：酒馆只在你打开它时运行。

App 设置 → 后台运行 第一行会显示当前是否已经「不限制」。

---

## 出问题去哪里看

- 酒馆日志：☰ → 酒馆 App 设置 → 查看日志（也可以长按桌面图标 → 酒馆日志）。这里是 SillyTavern 服务器的实时输出，以及 app 内升级、导入时的过程。上方「分享日志文件」可以把完整日志发给别人（日志里不会有 API key，但可能有聊天片段，分享前看一眼）。
  - 完整日志文件在 app 私有目录 `files/st/logs/server.log`，上一次运行的在 `server.previous.log`。
- 打不开 / 一直转圈：启动失败时开屏会换成出错说明和「重试」「日志」「设置」按钮，下面滚动显示启动日志。
  - 提示端口被占用：app 用的是 8800 端口（Termux 里的 SillyTavern 默认是 8000，一般不冲突）。如果别的程序占了 8800，关掉它或在 App 设置 → 高级 换个端口。
  - 提示「被系统结束，多半是手机内存不够了」：关掉一些后台 app，或在 高级 里调大 Node.js 内存上限。
- 界面显示异常：App 设置 → 高级 → 清除前端编译缓存。
- 程序文件坏了：App 设置 → SillyTavern 本体 → 修复程序文件（不动数据）。
- 自己构建失败：看终端里最先报错的那一步；仓库的 Actions 只检查公开源码，不构建或签名 APK。
- 更深入（电脑连手机、开了 USB 调试）：`adb logcat -s Jiuguan` 看 app 自己的日志；App 设置 → 高级 打开「网页调试」后，电脑 Chrome 打开 `chrome://inspect` 可以调试酒馆页面。

---

## 和 TavernDroid 原版相比改了什么

| | 原版 TavernDroid | 酒馆 |
|---|---|---|
| 名字、语言 | TavernDroid，英文 | 「酒馆」，全中文，新的开屏和图标 |
| 包名 | `io.github.taverndroid` | `io.github.jiuguan`（两个可以同时装，互不影响） |
| PIN 锁 | 首次强制设 6 位 PIN，闲置 5 分钟自动锁 | 去掉，打开就用 |
| 后台 | 可设置成划掉后继续跑、开机自启 | 跟普通 app 一样：打开才启动；切到别的 app 继续跑；在最近任务里划掉就全部结束（Node 进程、前台服务、唤醒锁、app 进程）。没有开机自启 |
| 屏幕 | 普通全屏 | 状态栏 / 导航条可分别收起；80%～150% 整体缩放滑块；刘海、圆角屏留白；键盘弹出界面跟着收；横屏 |
| 流畅 | — | 硬件加速；屏幕外内容预先栅格化；可选的「省电流畅」一键设置 |
| 数据 | 程序目录里混着插件和扩展 | 程序和数据分开放：升级只替换程序目录，数据、配置、插件、扩展都在外面 |
| 导入 | 直接覆盖 | 先自动备份，再合并覆盖；认好几种 zip 格式；配套 Termux 一行命令导出脚本 |
| 导出 | 选位置保存 | 一键导出到 下载/酒馆/ |
| 升级 SillyTavern | 只能等新 APK | app 里直接升级（自动备份 → 下载 → 手机上装依赖 → 切换），可以随时退回安装包自带版本 |
| 拍照 | 不支持 | 选图片时多一个「拍照」 |
| 签名 | 自己配 | 发布版用固定签名，新版本直接覆盖安装、不丢数据 |
| 构建 | 作者在 Mac 上手动构建 | 本机固定流程构建、验收后手动发布 |

跑的是原版 SillyTavern（默认 1.19.0，低于 1.18.0 的版本构建脚本会拒绝），再应用仓库 `patches/` 里列明日期的两个小改动：一个修复滑动后无法发送的问题，一个在 Web 界面显著提供本修改版的源码入口。

---

## 自己构建

本仓库不会在 GitHub 上自动构建或签名 APK。Releases 里的正式 APK 在本机用固定钥匙构建，验完签名、包名、版本和架构后手动上传；仓库 Actions 只跑不接触签名材料的源码检查。

Linux（或 Windows 的 WSL）上装好 JDK 17、Android SDK（platform 35、build-tools 35）、Node.js 22、`python3`、`git`、`patch`、`dpkg-deb`、`zip`，然后依次运行 `tools/fetch_termux.sh`、克隆并安装 SillyTavern 1.19.0 的依赖、`tools/prepare.sh`，最后运行 Gradle。`runtime/termux-packages.tsv` 锁定了运行环境的准确版本和 SHA-256；Termux 是滚动仓库，旧二进制被移走时，需要把匹配哈希的包放进 `vendor/debs/`，或按 `runtime/` 记录的固定配方从源码重建，不能悄悄换成别的版本。

运行 Gradle 前先设置北京时间版本号：`export VERSION_CODE=$(TZ=Asia/Shanghai date +%y%m%d%H)` 和 `export VERSION_NAME=1.0.$VERSION_CODE`，否则默认版本号是 1，装不上去覆盖正式版。只构建 `arm64-v8a`。

仓库里没有签名钥匙。没有 `keystore.properties` 时会使用调试签名，只适合测试，不能覆盖 Releases 里的正式版。要使用自己的固定签名，把钥匙放进仓库根目录的 `keystore/`，再放一个 `keystore.properties`（两者都已写进 `.gitignore`）：

```properties
storeFile=keystore/my.keystore
storePassword=你的密码
keyAlias=jiuguan
keyPassword=跟上面一样的密码
```

> 钥匙文件和密码千万别提交进仓库，也别发给别人：谁拿到它，谁就能做出能覆盖安装你的 app 的包，读到里面的聊天和 API key。钥匙丢了也没法补，只能换新钥匙、卸载重装。

完整流程是：校验并准备锁定的 Termux 运行环境 → 克隆 SillyTavern 1.19.0 并 `npm ci` → `tools/prepare.sh` 校验许可、应用补丁并打包 → Gradle 构建和签名 → `tools/build_source_bundle.py` 生成与 APK 同时发布的对应源码包。

---

## 手机测试清单

装好后照着逐项试，每项后面是「怎么算通过」。

- [ ] 装好以后能打开
  安装 APK → 点图标。看到开屏（星球图案 + 花体「Silly Tavern」+ 细进度条），第一次一两分钟后进入 SillyTavern 1.19.0 界面。
- [ ] 导入数据后，聊天记录和角色都在
  ☰ → 酒馆 App 设置 → 导入数据包 → 选 Termux 打的 zip。完成后：角色列表里是你原来的角色；打开一个角色，聊天记录完整；世界书、预设在；API 连接里的 key 不用重新填（直接能连上）。下载/酒馆/自动备份/ 里多了一个「导入前-…」的 zip。
- [ ] 息屏 10 分钟后，回复还能出来
  先做完上面 [vivo 开关](#vivo--originos-要开的开关) 的 1、2 两项。发一条消息让角色开始回复 → 立刻锁屏 → 等 10 分钟 → 解锁：回复完整写完了（中间应该还收到了「新回复」通知）。再发一条，能正常回复。
- [ ] 切到别的 app 再回来
  生成回复时切到别的 app 待几分钟再回来：回复继续 / 已完成，界面不用重新加载。
- [ ] 划掉就彻底停
  在最近任务里把酒馆划掉：通知栏的「酒馆 · 营业中」通知消失。再点图标，会重新显示等待画面启动（说明之前确实停了）。
- [ ] 缩放正常
  App 设置 → 显示 → 把缩放拖到 130% → 返回：字和按钮一起变大，没有横向滚动条，顶部图标没有挤出屏幕。划掉 app 再打开，还是 130%。
- [ ] 全屏正常
  打开「收起状态栏」「收起导航条」→ 返回：上下都没有系统栏；从边缘滑一下能临时叫出来。刘海 / 圆角处没有内容被遮住。
- [ ] 键盘不挡输入框
  点聊天输入框，键盘弹出后输入框在键盘上方。
- [ ] 横屏能用
  打开手机自动旋转，横过来：界面铺满、能正常聊天。
- [ ] 插件能用
  扩展面板里看到你原来的第三方扩展并能正常使用；服务端插件在 查看日志 里能看到 `server plugin(s) are currently loaded`。再试着用 git 装一个新扩展，能装上。
- [ ] 上传和拍照
  换头像 / 发图片时，选择器里能选相册图片，也有「相机」可以直接拍照；导入一张角色卡 PNG 能成功。
- [ ] 导出能拿到文件
  App 设置 → 一键导出全部数据：文件管理里 下载/酒馆/ 下有「酒馆数据-…zip」。再在聊天里导出一个聊天记录：下载/酒馆/ 里有这个文件，并且收到「已保存」通知。
- [ ] 省电流畅（可选）
  点「一键省电流畅」→ 返回聊天：提示已开启；酒馆用户设置里 No Blur Effect、Reduced Motion 已勾选，Streaming FPS 数字框是 12，# Messages to Load 是 30。
- [ ] 覆盖安装不丢数据
  之后装一个新构建的 APK（不卸载）：打开后聊天、角色、设置都还在，缩放比例也还在。

---

## 源码结构

```
.github/workflows/checks.yml  GitHub 上只跑源码测试和 Shell 语法检查
app/src/main/java/io/github/taverndroid/
  MainActivity.kt      全屏 WebView、开屏、系统栏 / 安全区 / 键盘、文件选择和拍照、下载
  Appearance.kt        自定义开屏图、用图片建桌面快捷图标、通知小图标的剪影
  SplashBar.kt         开屏上的细进度条
  ServerService.kt     前台服务；划掉 app 时（onTaskRemoved）结束一切
  ServerController.kt  启动 / 停止 Node.js 上的 SillyTavern（只监听 127.0.0.1）
  Installer.kt         解压 APK 自带的 SillyTavern，维护程序 / 数据分离的目录和链接
  BackupManager.kt     导出、导入（自动备份 + 合并覆盖）
  StUpdater.kt         app 内升级 SillyTavern
  SettingsActivity.kt  App 设置
  StPaths.kt           手机上的目录布局（见下）
app/src/main/assets/taverndroid.js   注入酒馆页面的脚本：整体缩放、省电流畅、下载、TTS、返回键
tools/termux-export.sh  配套的 Termux 导出脚本：在 Termux 里打数据包
tools/fetch_termux.sh   下载 Termux 的 Node.js、npm 和 git
tools/prepare.sh        打包运行环境和 SillyTavern
tools/collect_libs.py   自动找出 Node / git 需要的所有 .so
design/                 默认开屏图、图标的原图，make_assets.py 生成 app 里用的图
patches/                给 SillyTavern 打的小修复
```

手机上的目录（都在 app 私有目录 `files/st/` 下）：

```
st/app/          SillyTavern 程序本体（升级时整个替换）
st/data/         数据：用户、角色、聊天、世界书、预设、设置、secrets.json
st/config.yaml   服务器配置
st/plugins/      服务端插件        （st/app/plugins 是指向这里的链接）
st/extensions/   全局第三方扩展    （st/app/public/scripts/extensions/third-party 是指向这里的链接）
st/logs/         日志
```

`docs/ARCHITECTURE.md`、`docs/FEATURE_PARITY.md`、`docs/research/` 是上游 TavernDroid 的英文技术文档，留作参考。

---

## 许可

GNU AGPL-3.0（见 `LICENSE`），与上游 TavernDroid 和 SillyTavern 相同。酒馆项目在 2026-10-18 修改了 SillyTavern，改动及日期见 [`patches/README.md`](patches/README.md)；App 和经局域网打开的 Web 首页都提供本仓库源码入口。

所有 Termux 二进制的固定版本、哈希、许可证、主页和不可变构建配方都记录在 `runtime/`。构建会在任何许可正文缺失、包哈希漂移或只剩“见另一个文件”的占位说明时直接失败。完整正文随 APK 放进 App 设置 → 关于 → 开源许可；每个签名 Release 都在 APK 旁发布 `jiuguan-*-corresponding-source.zip`，里面包含本项目、SillyTavern、Git、less、libiconv 的准确源码及固定的 Termux 构建脚本。开屏花体字 Pinyon Script 的 SIL OFL 1.1 也随 APK 打包。

本项目与 SillyTavern 官方团队、TavernDroid 作者无关。
