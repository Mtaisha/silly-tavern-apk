<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

# Jiuguan (酒馆)

[中文](README.md) | English

The original SillyTavern, packed whole into one Android app: Node.js and git are built in, no Termux needed — tap the icon and it runs.
For arm64 Android phones (Android 10 or later). The app's own screens are in Chinese only; it is tuned for the aggressive battery management on vivo / OriginOS.

"Jiuguan" (酒馆) means "tavern". In this README, app buttons are given with their Chinese label followed by an English translation in parentheses, so you can find them on screen.

Based on [TavernDroid](https://github.com/S0ul-Sh3ll/TavernDroid) (AGPL-3.0). Why TavernDroid rather than ST-android or SillyTavern-Android:
it had already solved the hardest parts (running the original Node + git without Termux, third-party extensions and server plugins working, even vivo freezing background web pages),
while of the other two, one opens SillyTavern in an external browser and the other is only a web shell that still depends on Termux.

---

## Contents

- [Download and updates](#download-and-updates)
- [Moving from Termux in three steps](#moving-from-termux-in-three-steps)
- [Installing](#installing)
- [Everyday use: like any other app](#everyday-use-like-any-other-app)
- [Importing data (details)](#importing-data-details)
- [Exporting data](#exporting-data)
- [Screen: fullscreen, scaling, notches, keyboard, landscape](#screen-fullscreen-scaling-notches-keyboard-landscape)
- [Splash screen and icon](#splash-screen-and-icon)
- [Battery saver and smoothness](#battery-saver-and-smoothness)
- [Plugins and extensions](#plugins-and-extensions)
- [Upgrading SillyTavern](#upgrading-sillytavern)
- [Switches to turn on for vivo / OriginOS](#switches-to-turn-on-for-vivo--originos)
- [Troubleshooting](#troubleshooting)
- [Changes from the original TavernDroid](#changes-from-the-original-taverndroid)
- [Building it yourself](#building-it-yourself)
- [Phone test checklist](#phone-test-checklist)
- [Source layout](#source-layout)
- [License](#license)

---

## Download and updates

- Download: get the latest `jiuguan-<version>-st<SillyTavern version>-arm64.apk` from [Releases](https://github.com/Mtaisha/Silly-Tavern-apk/releases/latest). Open the page in your phone's browser and download it directly.
- App updates: every new version is published on [Releases](https://github.com/Mtaisha/Silly-Tavern-apk/releases). Download the new APK and install it over the old one — no need to uninstall; chats, characters and settings stay.
  In the app, ☰ → 酒馆 App 设置 (App settings) → 关于 (About) → 源代码和新版本 (Source code and new versions) also takes you to this repository.
- SillyTavern updates: no need to wait for a new APK — SillyTavern can be upgraded inside the app, see [Upgrading SillyTavern](#upgrading-sillytavern).
- arm64-v8a only (practically every Android phone from recent years).

---

## Moving from Termux in three steps

If you have been running SillyTavern in Termux, you can move everything over: characters, chats, world info, presets, settings, API keys, extensions and server plugins.

1. Paste this line into Termux and press Enter (preferably stop the running SillyTavern with Ctrl+C first):

   ```bash
   curl -fsSL https://raw.githubusercontent.com/Mtaisha/Silly-Tavern-apk/main/tools/termux-export.sh | bash
   ```

   The first time, Android asks for storage permission — tap Allow. When it finishes, your phone's Download folder contains `酒馆数据包-<date>.zip` ("tavern data package").
2. Install Jiuguan, open it once and wait until the SillyTavern interface appears.
3. ☰ → 酒馆 App 设置 (App settings) → 数据 (Data) → 导入数据包（zip）(Import data package) → pick that zip. The app first backs up its current data automatically, then imports and restarts SillyTavern.

The export script is this repository's companion tool; its source is [`tools/termux-export.sh`](tools/termux-export.sh). It only packs files — it never reads, prints or uploads their contents. Its messages are in Chinese.
The package contains your API keys, so don't share it or upload it anywhere. For more options, the package format and exactly what the import does, see [Importing data (details)](#importing-data-details).

---

## Installing

1. Open [Releases](https://github.com/Mtaisha/Silly-Tavern-apk/releases/latest) in your phone's browser and download the APK.
2. Open the APK. The first time, Android says installing apps from unknown sources is blocked: tap Settings, allow your browser or file manager to install unknown apps, then go back and continue.
   - vivo / OriginOS may show a "security check" or ask for your vivo account password; choose Continue installing / enter the password.
3. For future versions: install the new APK over the old one. No uninstall needed; your data stays.

> ⚠️ Don't uninstall and reinstall — uninstalling deletes the app's data too. Before switching phones or reinstalling, [export your data](#exporting-data) first.

---

## Everyday use: like any other app

- SillyTavern starts only when you open the app. Until it's up you see the splash screen (a picture, "Silly Tavern" in script lettering and a thin progress bar). The first launch unpacks the program and takes longer (a minute or two).
- Switch to another app (to order takeout, say): SillyTavern keeps running in the background, with a persistent "酒馆 · 营业中" ("open for business") notification. Come back and carry on; a reply that's being generated keeps going.
- Swipe the app away in Recents: the Node process, the foreground service and the wake lock all end, and the app process exits — nothing is left running. The 退出 (Exit) button in the notification and in App settings does the same.
- Back button: first closes open panels / popups in SillyTavern; if there's nothing to close, it sends the app to the background (it keeps running).
- Where App settings are: ☰ menu at the bottom left of SillyTavern → 酒馆 App 设置; or long-press the home screen icon → App 设置.

SillyTavern listens only on `127.0.0.1` on the phone itself and is not exposed to your local network by default (you can turn that on under App settings → 高级 (Advanced)).

---

## Importing data (details)

### Package format

A zip with this layout (an extra top-level folder around everything is fine too):

```
酒馆数据包.zip
├── data/                                     ← required. The whole SillyTavern/data/ folder from Termux
│   ├── default-user/                         ← a user folder (several if you use multiple users)
│   │   ├── characters/                       character cards
│   │   ├── chats/  group chats/  groups/     chats, group chats
│   │   ├── worlds/                           world info
│   │   ├── OpenAI Settings/  TextGen Settings/  instruct/  context/  sysprompt/ …  presets
│   │   ├── extensions/                       third-party extensions installed for this user (via git)
│   │   ├── settings.json                     SillyTavern settings
│   │   ├── secrets.json                      your API keys
│   │   └── … (avatars, backgrounds, QuickReplies and so on, all as they are)
│   ├── _storage/  cookie-secret.txt …        include them if present
├── config.yaml                               ← optional. SillyTavern/config.yaml
├── plugins/                                  ← optional. SillyTavern/plugins/ (server plugins)
└── public/scripts/extensions/third-party/    ← optional. Global third-party extensions
    (a top-level extensions/ works the same)
```

Not needed: the SillyTavern program itself (`src/`, the rest of `public/`, `node_modules/`) — it's ignored if present.
Also not needed: `data/_webpack/`, `data/_uploads/`, `data/_cache/` (rebuilt automatically).

These are accepted too:
- a zip of just a user folder (the top level is `default-user/`);
- a user backup from SillyTavern's own "Download backup" (the top level is directly `settings.json`, `chats/`, `characters/`…; it's imported into `default-user`);
- packages exported by this app.

### Packing in Termux

It's the one line from the [three steps](#moving-from-termux-in-three-steps):

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/Silly-Tavern-apk/main/tools/termux-export.sh | bash
```

It leaves `酒馆数据包-<date>.zip` in your phone's Download folder and takes care of these things by itself:

- finds SillyTavern under `~`, including data moved elsewhere with `dataRoot` in config.yaml
- requests storage permission on first use (tap Allow in the popup)
- packs with the compression library that ships with SillyTavern (the one its "Download backup" uses), so you don't need to install zip or be online. Only very old versions without that library fall back to the zip command; if it's missing, run `pkg install zip` as prompted
- reads the whole package back afterwards to make sure it's intact and no file is missing

If SillyTavern lives somewhere else, or there are several of them under `~`, put the directory after `bash -s --`, along with any other options:

```bash
curl -fsSL https://raw.githubusercontent.com/Mtaisha/Silly-Tavern-apk/main/tools/termux-export.sh | bash -s -- /path/to/SillyTavern
```

- `-o <dir>`: put the zip somewhere else (default: the phone's Download folder)
- `-y`: don't ask even if SillyTavern is still running; just pack

The script only packs; it never reads, prints or uploads file contents.

If you'd rather not run the script, you can pack by hand inside the SillyTavern directory (this ignores `dataRoot`, so your data must be in the default `data/`):

```bash
zip -r ~/storage/downloads/酒馆数据包.zip data config.yaml plugins public/scripts/extensions/third-party \
    -x 'data/_webpack/*' -x 'data/_uploads/*' -x 'data/_cache/*'
```

(Don't add `-y`, so symbolic links are stored as the real files.)

### Importing in the app

☰ → 酒馆 App 设置 (App settings) → 数据 (Data) → 导入数据包（zip）(Import data package) → pick the zip → start. The app then:

1. Backs up the app's current data to `Download/酒馆/自动备份/导入前-<date>.zip` (only the latest 3 automatic backups are kept). If the backup fails, the import stops there.
2. Pauses SillyTavern.
3. Merges: files in the zip overwrite existing ones; files that aren't in the zip are left alone. Extensions and plugins are replaced as whole folders — if the zip contains an extension, it replaces the existing copy (so old and new git files don't get mixed); extensions not in the zip are kept.
4. Reopens SillyTavern and asks whether to turn on [battery saver and smoothness](#battery-saver-and-smoothness) as well (nothing changes unless you tap it).

About `secrets.json` (API keys): it's extracted from the zip only on your phone and written into the app's private directory. The app never prints, shows or uploads its contents, and the build process never touches it. Don't share the package or upload it anywhere.

> If your data comes from an older SillyTavern version, SillyTavern migrates it by itself on first start — give it time to finish starting.

---

## Exporting data

☰ → 酒馆 App 设置 → 数据 → 一键导出全部数据 (Export all data). This creates `Download/酒馆/酒馆数据-<date>.zip` in the same format as the package above (`data/`, `config.yaml`, `plugins/`, `extensions/`), which you can import again later.

Single chats, character cards and other files exported from inside SillyTavern are saved to `Download/酒馆/`; you get a notification and can tap it to open the file.

---

## Screen: fullscreen, scaling, notches, keyboard, landscape

All under ☰ → 酒馆 App 设置 → 显示 (Display):

- There's no address bar; SillyTavern fills the whole screen.
- Hide status bar / hide navigation bar: two separate switches. When hidden, swipe from the screen edge to bring them back for a moment.
- Overall scale: a slider from 80% to 150% (in 5% steps) that scales text, buttons and icons together; the page always fits the screen exactly, with no horizontal overflow. It takes effect as soon as you go back to the chat, and is remembered.
- Notches, punch holes and rounded corners get safe-area padding automatically; when the keyboard opens, the layout shrinks so the input box isn't covered.
- Landscape: follows the phone's auto-rotate switch.
- Keep screen on: the screen doesn't time out while you're on the chat screen (off by default).

---

## Splash screen and icon

When you open the app you first see the splash screen: a picture, "Silly Tavern" in script lettering and a thin progress bar. The source images of the default splash picture and icon are in `design/`.

☰ → 酒馆 App 设置 → 开屏和图标 (Splash and icon):

- 换开屏图片 (Change splash picture): pick a picture to fill the whole splash screen, with the lettering and progress bar on top of it near the bottom (portrait pictures work best). It takes effect the next time you open the app. 恢复默认开屏 (Restore default splash) brings back the default picture.
- 用一张图做桌面图标 (Use a picture as the home screen icon): Android doesn't let an app replace its own icon, so this creates a new "酒馆" shortcut on the home screen with your picture. It opens the app just the same, and you can tuck the original icon away in a folder. Picking another picture later replaces this shortcut's picture. On vivo and similar phones, give 酒馆 the "home screen shortcuts" permission in the phone's settings, otherwise nothing happens when you tap it.
- The notification's small icon follows along: Android only allows a single-color silhouette for the small icon on the left of notifications and in the status bar, so the app cuts the shape out of your picture as a white silhouette. Pictures with a clean background work best; if nothing usable can be cut out (a busy photo, for example), the default door silhouette stays. vivo and other Chinese-brand systems draw the app's own icon on the left of notifications, which the app can't change, so that stays the default door; changing it means changing the default icon and rebuilding (see below). 恢复默认图标 (Restore default icon) puts the notification back to the default door; the home screen shortcut has to be removed by hand with a long press.

If you build it yourself and want different default pictures: replace the two source images in `design/`, run `python design/make_assets.py` (needs Pillow), then rebuild. If the composition is different, adjust the crop coordinates in the script.

---

## Battery saver and smoothness

Optional — nothing changes unless you tap it. Use App settings → 显示 → 一键「省电流畅」 (one-tap battery saver and smoothness), or the dialog that appears after importing data. It changes these four settings for the current user (exactly as if you changed them yourself in SillyTavern's User Settings; they're saved normally):

| Setting | Changed to |
|---|---|
| No Blur Effect | on |
| Reduced Motion | on |
| Streaming FPS | 12 |
| # Messages to Load | 30 |

Note: SillyTavern's Streaming FPS slider moves in steps of 5, so the slider sits at 10, but the number box next to it and the value actually used are both 12. To change anything back, just change it in SillyTavern's User Settings.

The app also turns on hardware acceleration by default and lets the WebView rasterize off-screen content ahead of time, so long chats scroll more smoothly.

---

## Plugins and extensions

- Frontend extensions (third-party extensions installed via git): install them right in SillyTavern under Extensions → Install extension with the git URL; the app ships with git. You need to be able to reach GitHub.
  - Extensions installed for the current user only are in `data/<user>/extensions/`; those installed for all users are in the shared `extensions/`.
- Server plugins: force-enabled by default (you can turn them off under App settings → 高级 (Advanced)). They live in the shared `plugins/` directory — just bring them in with a data package; SillyTavern still tries to auto-update plugins with git at startup, as usual.
- Plugins and extensions are kept outside the program directory, so upgrading SillyTavern doesn't wipe them.
- No Python / uvx or similar environments are bundled. Plugins that ship compiled native modules (`.node` files) may need to be reinstalled inside the app.

---

## Upgrading SillyTavern

☰ → 酒馆 App 设置 → SillyTavern 本体 (SillyTavern itself) → 检查更新 (Check for updates). When there's a new version, the button changes to 升级到 x.y.z (Upgrade to x.y.z). After you tap it:

1. your data is backed up automatically to `Download/酒馆/自动备份/升级前-<date>.zip`;
2. the new version's source code is downloaded from GitHub;
3. its dependencies are installed on the phone with the npm bundled in the app (just like `npm install` in Termux; a few minutes);
4. after a pause of a few seconds it switches to the new program and restarts automatically.

You can keep chatting while it downloads and installs dependencies. You need to be able to reach GitHub and npm; if npm is slow, tap 用国内镜像 (use the China mirror) in the same place.
Upgrading only replaces the program directory — data, config.yaml, plugins and extensions are untouched. If the upgrade fails, you stay on the previous version and your data is unchanged.

Going back: 恢复到安装包自带版本 (Restore the version bundled with the APK), or 修复程序文件 (Repair program files), re-extracts the SillyTavern bundled in the APK; your data is unaffected.
When you later install a new APK that bundles a newer SillyTavern, it switches to that version automatically as well.

New versions of the app itself aren't upgraded here — download the new APK from [Releases](https://github.com/Mtaisha/Silly-Tavern-apk/releases) and install it over the old one.

---

## Switches to turn on for vivo / OriginOS

vivo's battery management is aggressive. The settings below decide whether replies still come through after you switch away or the screen goes off. Menu names may differ slightly between OriginOS versions:

1. Unrestricted battery: the first time a reply is generated, the app asks you — tap 去允许 (Allow); or go to App settings → 后台运行 (Background) → 允许不限制 (Allow unrestricted) and allow it in the system dialog.
2. Allow high background power consumption: App settings → 后台运行 → 系统应用设置 (System app settings) → Battery (or "Power consumption management") → turn on "Allow high background power consumption".
   You can also get there from the phone's Settings → Battery → Background power consumption management → 酒馆 → allow high background power consumption.
3. Notifications: allow 酒馆 to post notifications (you're asked on first launch). The persistent notification tells the system the app is running a foreground service; you also get notified when a character replies while you're away.
4. Don't "lock" 酒馆 in Recents — you want swiping it away to stop it completely, and a lock keeps one-tap cleanup from clearing it.
5. There's no need for "auto-start" or "associated start": the app only runs while you have it open.

The first line of App settings → 后台运行 shows whether it's currently unrestricted.

---

## Troubleshooting

- Logs: ☰ → 酒馆 App 设置 → 查看日志 (View logs) (or long-press the home screen icon → 酒馆日志 (Logs)). This is the live output of the SillyTavern server, plus the steps of in-app upgrades and imports. 分享日志文件 (Share log file) at the top sends the full log to someone (it never contains API keys, but may contain bits of your chats — take a look before sharing).
  - The full log file is in the app's private directory at `files/st/logs/server.log`; the previous run's log is `server.previous.log`.
- Won't open / keeps spinning: if startup fails, the splash screen switches to an explanation of the error with 重试 (Retry), 日志 (Logs) and 设置 (Settings) buttons, and the startup log scrolling below.
  - Port in use: the app uses port 8800 (SillyTavern in Termux defaults to 8000, so they normally don't clash). If another program is using 8800, close it or pick another port under App settings → 高级.
  - "Killed by the system, most likely low on memory": close some background apps, or raise the Node.js memory limit under 高级.
- The interface looks wrong: App settings → 高级 → 清除前端编译缓存 (Clear the frontend build cache).
- Program files are damaged: App settings → SillyTavern 本体 → 修复程序文件 (Repair program files) (your data isn't touched).
- A build failed: GitHub repository → Actions → open the failed run and read the output of the step with the red cross.
- Going deeper (phone connected to a computer with USB debugging on): `adb logcat -s Jiuguan` shows the app's own log; after turning on 网页调试 (Web debugging) under App settings → 高级, open `chrome://inspect` in Chrome on the computer to debug the SillyTavern page.

---

## Changes from the original TavernDroid

| | Original TavernDroid | Jiuguan |
|---|---|---|
| Name, language | TavernDroid, English | 「酒馆」, all Chinese, new splash screen and icon |
| Package name | `io.github.taverndroid` | `io.github.jiuguan` (both can be installed side by side without affecting each other) |
| PIN lock | 6-digit PIN forced on first launch, auto-lock after 5 minutes idle | Removed; open and use |
| Background | Can be set to keep running after being swiped away and to start at boot | Like any other app: starts only when opened; keeps running when you switch to another app; swiping it away in Recents ends everything (Node process, foreground service, wake lock, app process). No start at boot |
| Screen | Plain fullscreen | Status bar and navigation bar can be hidden separately; 80%–150% overall scale slider; padding for notches and rounded corners; layout shrinks for the keyboard; landscape |
| Smoothness | — | Hardware acceleration; off-screen content rasterized ahead of time; optional one-tap "battery saver and smoothness" |
| Data | Plugins and extensions mixed into the program directory | Program and data kept apart: upgrades only replace the program directory; data, config, plugins and extensions live outside it |
| Import | Overwrites directly | Automatic backup first, then merge; accepts several zip layouts; companion one-line Termux export script |
| Export | Save to a chosen location | One tap exports to Download/酒馆/ |
| Upgrading SillyTavern | Only by waiting for a new APK | Upgrade inside the app (automatic backup → download → install dependencies on the phone → switch over); you can go back to the version bundled with the APK at any time |
| Camera | Not supported | A "take photo" option when picking images |
| Signing | Configure it yourself | Releases are signed with a fixed key, so new versions install over old ones without losing data |
| Building | Built by hand on the author's Mac | One-click build with GitHub Actions |

It always runs the original SillyTavern (1.19.0 by default; the build scripts refuse versions below 1.18.0), with just one small bug-fix patch added (see `patches/README.md`).

---

## Building it yourself

### With GitHub Actions (no Android development environment needed)

1. Fork this repository (or push the code to the `main` / `master` branch of your own repository). On a fork, first enable workflows on the Actions page.
2. A push starts a build automatically. You can also run one by hand: Actions → 构建 APK (Build APK) → Run workflow:
   - `SillyTavern 版本` (SillyTavern version): `1.19.0` by default; `latest` uses the newest release on GitHub; anything below `1.18.0` fails without building.
   - `构建完发一个 Release` (Publish a Release after building): checked by default (it only actually publishes once signing is set up, see below).
3. After roughly 10–20 minutes the build is done; download the APK from Artifacts at the bottom of the run's page (it downloads as a zip; unzip it to get the APK).

Signing: the repository contains no signing key. Without one, builds are signed with a temporary debug key that GitHub generates — the APK installs, but its signature is different on every build, so a new APK can't install over an old one, nor over the official version from this repository's Releases (Android reports a signature conflict; the only way out is uninstalling, which loses your data).
To be able to install each new build over the last one, generate a key of your own (keytool ships with the JDK). Run this outside the repository folder, so the key can't be committed by accident:

```bash
keytool -genkeypair -v -keystore my.keystore -alias jiuguan -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 my.keystore
```

keytool has you set just one password (typed twice); remember it. The second line prints the key as one long string of text; copy it for the next step.
(On macOS, replace the second line with `base64 -i my.keystore`; in Windows PowerShell, with `[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path my.keystore))) | Set-Clipboard`, which copies it straight to the clipboard.)

Then add four secrets under the repository's Settings → Secrets and variables → Actions: `KEYSTORE_BASE64` (that long string), `KEYSTORE_PASSWORD` (the password keytool asked for), `KEY_ALIAS` (`jiuguan` above) and `KEY_PASSWORD` (the same password as `KEYSTORE_PASSWORD`). All four are needed; with only some of them the build fails.
From then on, builds are signed with that key; run the workflow by hand with "Publish a Release" checked and a "酒馆 Jiuguan 1.0.N" release appears under Releases, ready to download on your phone.

> Never commit the key file or its passwords, and never give them to anyone: whoever has them can build a package that installs over your app and reads its chats and API keys. A lost key can't be recovered either — the only way out is a new key and an uninstall.

Each build's version number is the build time (China Standard Time) written as yyMMddHH. It only ever goes up, so a new APK can always install over an older one; two builds within the same hour share a version number and can still install over each other.

> Build steps: `tools/fetch_termux.sh` downloads Node.js LTS, npm and git from the Termux package repository (arm64, each checked against its SHA256) → the chosen SillyTavern version is cloned and `npm ci` is run → `tools/prepare.sh` packages everything → Gradle builds and signs the APK. Only `arm64-v8a` is built.

### On your own computer

On Linux (or WSL on Windows), install JDK 17, the Android SDK (platform 35, build-tools 35), Node.js 22, `python3`, `git`, `patch`, `dpkg-deb` and `zip`, then run the steps in `.github/workflows/build.yml` in order.
The step that sets the version number doesn't work outside GitHub Actions, so run `export VERSION_CODE=$(TZ=Asia/Shanghai date +%y%m%d%H)` before Gradle; otherwise the version number is 1 and the APK can't install over any other version.

For fixed signing, put your key in a `keystore/` folder in the repository root and add a `keystore.properties` file there (both are already listed in `.gitignore`, so they won't be committed):

```properties
storeFile=keystore/my.keystore
storePassword=your password
keyAlias=jiuguan
keyPassword=the same password as above
```

Without that file, builds use the debug key.

---

## Phone test checklist

After installing, go through these one by one; each item says what counts as a pass.

- [ ] It opens after installing
  Install the APK → tap the icon. You see the splash screen (planet picture + "Silly Tavern" in script lettering + thin progress bar); on first launch, after a minute or two, the SillyTavern 1.19.0 interface appears.
- [ ] After importing data, chats and characters are all there
  ☰ → 酒馆 App 设置 → 导入数据包 → pick the zip made in Termux. Afterwards: the character list shows your characters; opening a character shows its full chat history; world info and presets are there; the API keys in API Connections don't need to be entered again (it connects right away). Download/酒馆/自动备份/ has a new "导入前-…" zip.
- [ ] Replies still arrive after 10 minutes with the screen off
  First do items 1 and 2 of the [vivo switches](#switches-to-turn-on-for-vivo--originos). Send a message so a character starts replying → lock the screen right away → wait 10 minutes → unlock: the reply is complete (and you should have received a "new reply" notification meanwhile). Send another message; the reply works normally.
- [ ] Switching to another app and back
  While a reply is being generated, switch to another app for a few minutes and come back: the reply continues / has finished, and the page doesn't need to reload.
- [ ] Swiping it away stops it completely
  Swipe the app away in Recents: the "酒馆 · 营业中" notification disappears. Tap the icon again and the splash screen shows up while it starts (proving it had really stopped).
- [ ] Scaling works
  App settings → 显示 → drag the scale to 130% → go back: text and buttons both get bigger, there's no horizontal scrollbar, and the top icons aren't pushed off screen. Swipe the app away and reopen it: still 130%.
- [ ] Fullscreen works
  Turn on 收起状态栏 (Hide status bar) and 收起导航条 (Hide navigation bar) → go back: no system bars at the top or bottom; a swipe from the edge brings them back for a moment. Nothing is hidden behind the notch / rounded corners.
- [ ] The keyboard doesn't cover the input box
  Tap the chat input box: after the keyboard opens, the input box sits above it.
- [ ] Landscape works
  Turn on auto-rotate and turn the phone sideways: the interface fills the screen and chatting works.
- [ ] Plugins work
  The extensions panel shows your existing third-party extensions and they work; for server plugins, 查看日志 (View logs) shows `server plugin(s) are currently loaded`. Try installing a new extension with git; it installs.
- [ ] Uploading and taking photos
  When changing an avatar / sending an image, the picker lets you choose photos from the gallery and also offers the camera; importing a character card PNG works.
- [ ] Exports produce files
  App settings → 一键导出全部数据 (Export all data): the file manager shows "酒馆数据-…zip" in Download/酒馆/. Then export a chat from inside SillyTavern: the file is in Download/酒馆/ and you get a "已保存" (Saved) notification.
- [ ] Battery saver and smoothness (optional)
  Tap 一键省电流畅 → go back to the chat: a message says it's on; in SillyTavern's User Settings, No Blur Effect and Reduced Motion are checked, the Streaming FPS number box shows 12 and # Messages to Load is 30.
- [ ] Installing over the old version keeps data
  Later, install a newly built APK (without uninstalling): after opening it, chats, characters and settings are all there, and so is the scale.

---

## Source layout

```
.github/workflows/build.yml   GitHub Actions build
app/src/main/java/io/github/taverndroid/
  MainActivity.kt      fullscreen WebView, splash screen, system bars / safe areas / keyboard, file picking and camera, downloads
  Appearance.kt        custom splash picture, home screen shortcut icon from a picture, silhouette for the notification small icon
  SplashBar.kt         the thin progress bar on the splash screen
  ServerService.kt     foreground service; ends everything when the app is swiped away (onTaskRemoved)
  ServerController.kt  starts / stops SillyTavern on Node.js (listening only on 127.0.0.1)
  Installer.kt         unpacks the SillyTavern bundled in the APK; maintains the separate program / data directories and links
  BackupManager.kt     export and import (automatic backup + merge)
  StUpdater.kt         in-app SillyTavern upgrades
  SettingsActivity.kt  App settings
  StPaths.kt           directory layout on the phone (see below)
app/src/main/assets/taverndroid.js   script injected into the SillyTavern page: overall scale, battery saver, downloads, TTS, back button
tools/termux-export.sh  companion Termux export script: makes the data package in Termux
tools/fetch_termux.sh   downloads Node.js, npm and git from Termux
tools/prepare.sh        packages the runtime and SillyTavern
tools/collect_libs.py   finds every .so that Node / git need
design/                 source images of the default splash picture and icon; make_assets.py generates the images used in the app
patches/                small fixes applied to SillyTavern
```

Directories on the phone (all under the app's private directory `files/st/`):

```
st/app/          the SillyTavern program (replaced as a whole on upgrade)
st/data/         data: users, characters, chats, world info, presets, settings, secrets.json
st/config.yaml   server configuration
st/plugins/      server plugins          (st/app/plugins is a link to here)
st/extensions/   global third-party extensions  (st/app/public/scripts/extensions/third-party is a link to here)
st/logs/         logs
```

`docs/ARCHITECTURE.md`, `docs/FEATURE_PARITY.md` and `docs/research/` are upstream TavernDroid's technical documents in English, kept for reference.

---

## License

GNU AGPL-3.0 (see `LICENSE`), the same as upstream TavernDroid and SillyTavern. Node.js (MIT) and git (GPL-2.0) are builds from the Termux project; their licenses are packaged in the APK and can be viewed under App settings → 关于 (About) → 开源许可 (Open-source licenses). The script lettering on the splash screen is Pinyon Script (SIL OFL 1.1).
This project is not affiliated with the SillyTavern team or the TavernDroid author.
