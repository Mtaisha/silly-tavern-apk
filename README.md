<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

# SillyTavern APK（酒馆）

中文 / English

无需部署，不需要 Termux。直接下载 APK，安装后点开就是完整的 SillyTavern；Node.js 和 git 都已经装在 app 里。

No setup and no Termux required. Download the APK, install it and open the complete SillyTavern app; Node.js and git are already built in.

[完整中文说明](README.zh.md) | [Full English guide](README.en.md)

适用于 arm64 安卓手机（Android 10 及以上）。App 界面为中文，并针对 vivo / OriginOS 的省电策略做过适配。

For arm64 Android phones running Android 10 or later. The app interface is in Chinese and is tuned for vivo / OriginOS battery management.

---

## 中文

### 直接下载 APK

到 [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases/latest) 下载文件名以 `arm64.apk` 结尾的安装包。以后更新时直接覆盖安装，不要先卸载；聊天、角色和设置都会保留。

### 从 Termux 导出并导入

原来在 Termux 里使用 SillyTavern 的，在 Termux 粘贴这一行并回车：

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash
```

脚本会把角色、聊天、世界书、预设、设置、API key、扩展和服务端插件打成 `酒馆数据包-日期.zip`，保存在手机的「下载」文件夹。

安装并打开酒馆一次，然后进入：☰ → 酒馆 App 设置 → 数据 → 导入数据包（zip），选择刚生成的 zip。App 会先自动备份现有数据，再导入并重启 SillyTavern。

数据包里包含 API key，不要把它发给别人或上传到网上。完整格式、参数和排错说明见 [完整中文说明](README.zh.md)。

### 安装和使用

1. 下载 APK，点开安装；安卓询问未知来源权限时，允许当前浏览器或文件管理器安装应用。
2. 第一次打开需要一两分钟解压程序，之后就像普通 app 一样使用。
3. 切到其他 app 时酒馆继续运行；从最近任务里划掉酒馆时，Node 进程和后台服务会一起结束。
4. App 设置位于 SillyTavern 左下角 ☰ → 酒馆 App 设置，也可以长按桌面图标进入。

屏幕设置、插件、App 内升级、数据导出、vivo 开关和完整测试清单都在 [完整中文说明](README.zh.md) 中。

---

## English

### Download the APK

Open [Releases](https://github.com/Mtaisha/silly-tavern-apk/releases/latest) and download the file whose name ends in `arm64.apk`. Install future versions over the existing app without uninstalling it; your chats, characters and settings remain intact.

### Export from Termux and import

If you previously ran SillyTavern in Termux, paste this line into Termux and press Enter:

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/silly-tavern-apk/main/tools/termux-export.sh | bash
```

The script packages your characters, chats, world info, presets, settings, API keys, extensions and server plugins into `酒馆数据包-<date>.zip` in the phone's Download folder.

Install Jiuguan and open it once. Then go to ☰ → 酒馆 App 设置 (App settings) → 数据 (Data) → 导入数据包（zip）(Import data package) and select that zip. The app backs up its current data before importing and restarting SillyTavern.

The package contains your API keys, so never share or upload it. See the [full English guide](README.en.md) for package formats, options and troubleshooting.

### Installation and usage

1. Download and open the APK. If Android blocks unknown sources, allow your current browser or file manager to install apps.
2. The first launch takes a minute or two while the bundled program is unpacked; later launches work like a normal app.
3. Jiuguan keeps running when you switch apps. Swiping it away from Recents ends the Node process and background service together.
4. Open app settings from the ☰ menu at the lower left of SillyTavern → 酒馆 App 设置, or by long-pressing the home-screen icon.

The [full English guide](README.en.md) covers screen settings, plugins, in-app upgrades, data export, vivo settings and the complete test checklist.

---

GNU AGPL-3.0. Based on [TavernDroid](https://github.com/S0ul-Sh3ll/TavernDroid) and the original [SillyTavern](https://github.com/SillyTavern/SillyTavern). This project is not affiliated with either upstream team.
