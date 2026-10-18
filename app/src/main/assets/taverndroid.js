// 「酒馆」app 在每个 SillyTavern 页面开始加载时注入的脚本。
// 不改 SillyTavern 本身，只把 WebView 缺少的浏览器功能接到安卓原生实现上（window.TavernDroidNative），
// 外加整体缩放和一点窄屏样式修正。
(() => {
    'use strict';
    if (window.__taverndroid || !window.TavernDroidNative) return;
    window.__taverndroid = true;
    const native = window.TavernDroidNative;

    // ---------------------------------------------------------------- downloads
    // SillyTavern exports files by clicking <a download href="blob:..."> and
    // usually revokes the blob URL right away. WebView has no download manager
    // for blob URLs, so keep the blobs alive briefly and stream them to Android.
    const blobs = new Map();
    const createObjectURL = URL.createObjectURL.bind(URL);
    const revokeObjectURL = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = (obj) => {
        const url = createObjectURL(obj);
        if (obj instanceof Blob) blobs.set(url, obj);
        return url;
    };
    URL.revokeObjectURL = (url) => {
        setTimeout(() => {
            blobs.delete(url);
            revokeObjectURL(url);
        }, 120000);
    };

    function guessName(href) {
        try {
            const path = new URL(href, location.href).pathname;
            return decodeURIComponent(path.split('/').filter(Boolean).pop() || 'download');
        } catch {
            return 'download';
        }
    }

    async function toBlob(href) {
        if (blobs.has(href)) return blobs.get(href);
        const response = await fetch(href, { credentials: 'include' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return await response.blob();
    }

    function readChunk(blob) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(String(reader.result).split(',', 2)[1] || '');
            reader.onerror = () => reject(reader.error);
            reader.readAsDataURL(blob);
        });
    }

    const CHUNK = 768 * 1024; // multiple of 3 so base64 chunks concatenate cleanly
    async function saveHref(href, name) {
        const fileName = name || guessName(href);
        let id = null;
        try {
            const blob = await toBlob(href);
            id = native.saveBegin(fileName, blob.type || 'application/octet-stream', blob.size);
            for (let offset = 0; offset < blob.size; offset += CHUNK) {
                native.saveChunk(id, await readChunk(blob.slice(offset, offset + CHUNK)));
            }
            native.saveEnd(id);
        } catch (error) {
            console.error('[TavernDroid] download failed', error);
            if (id) native.saveAbort(id);
            native.toast(`下载失败：${error.message || error}`);
        }
    }

    window.__taverndroidSave = saveHref;

    function isDownloadAnchor(a) {
        return a instanceof HTMLAnchorElement && a.hasAttribute('download') && a.href;
    }

    const anchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (isDownloadAnchor(this)) {
            saveHref(this.href, this.getAttribute('download'));
            return;
        }
        return anchorClick.call(this);
    };
    document.addEventListener('click', (event) => {
        const a = event.target instanceof Element ? event.target.closest('a') : null;
        if (a && isDownloadAnchor(a)) {
            event.preventDefault();
            event.stopImmediatePropagation();
            saveHref(a.href, a.getAttribute('download'));
        }
    }, true);

    // ------------------------------------------------------------ 整体缩放
    // 按 app 设置里的百分比放大/缩小整个界面（字、按钮、图标一起变）。做法是改写 viewport：
    // 布局宽度 = 屏幕宽度 / 缩放比例，再用同样的比例显示，所以页面始终正好铺满屏幕、不会横向溢出。
    (() => {
        if (!native.uiScale || !native.viewWidthDp) return;
        let meta = null;
        let original = null;
        let applying = false;
        const wanted = () => {
            if (!original) return null;
            const scale = Number(native.uiScale()) / 100;
            const width = Number(native.viewWidthDp());
            if (!scale || Math.abs(scale - 1) < 0.001 || !(width > 0)) return original;
            const layoutWidth = Math.max(240, Math.round(width / scale));
            return `width=${layoutWidth}, initial-scale=${scale}, minimum-scale=${scale}, maximum-scale=${scale}, ` +
                'user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';
        };
        const apply = () => {
            if (!meta) return;
            const next = wanted();
            if (next && meta.getAttribute('content') !== next) {
                applying = true;
                meta.setAttribute('content', next);
                applying = false;
            }
        };
        const adopt = (el) => {
            if (meta === el) return;
            meta = el;
            original = el.getAttribute('content') || 'width=device-width, initial-scale=1';
            new MutationObserver(() => {
                if (applying) return;
                original = meta.getAttribute('content') || original;
                apply();
            }).observe(meta, { attributes: true, attributeFilter: ['content'] });
            apply();
        };
        const find = () => {
            const el = document.querySelector('meta[name="viewport"]');
            if (el) adopt(el);
            return !!el;
        };
        if (!find()) {
            const watcher = new MutationObserver(() => {
                if (find()) watcher.disconnect();
            });
            watcher.observe(document, { childList: true, subtree: true });
        }
        // 转屏、分屏时宽度变了要重算；app 设置里改了比例后由安卓端调用 __jiuguanApplyScale()。
        window.addEventListener('resize', apply);
        window.__jiuguanApplyScale = apply;
    })();

    // ------------------------------------------------------------ 窄屏样式修正
    // 放大后布局宽度变窄，顶栏九个图标会挤出屏幕右边。让图标随宽度缩小，并禁止整页横向拖动。
    (() => {
        const css = `
            html { overflow-x: hidden; }
            #top-settings-holder > .drawer { min-width: 0; }
            #top-settings-holder .drawer-icon { font-size: min(var(--topBarIconSize), calc(11vw - 6px)); }
        `;
        const add = () => {
            if (document.getElementById('jiuguan-style')) return true;
            const parent = document.head || document.documentElement;
            if (!parent) return false;
            const style = document.createElement('style');
            style.id = 'jiuguan-style';
            style.textContent = css;
            parent.appendChild(style);
            return true;
        };
        if (!add()) document.addEventListener('DOMContentLoaded', add, { once: true });
    })();

    // ------------------------------------------------------------ page focus
    // In a desktop browser a hidden tab reports document.hasFocus() === false. Android WebView keeps
    // reporting true while the app is in the background or behind the lock screen. SillyTavern's Smooth
    // Streaming types each received chunk out with small timers when the page "has focus", and Chrome
    // throttles timers in hidden pages to 1/s (1/min after 5 minutes), so replies crawled or appeared to
    // stop until the user came back. Report no focus while hidden, like a browser tab does.
    const nativeHasFocus = Document.prototype.hasFocus;
    Document.prototype.hasFocus = function () {
        return !document.hidden && nativeHasFocus.call(this);
    };

    // ------------------------------------------------------- speech synthesis
    // Android WebView has no Web Speech synthesis (no SpeechSynthesisUtterance), which SillyTavern's
    // "System" TTS provider uses. This implements the API on top of Android's TextToSpeech.
    if (typeof window.SpeechSynthesisUtterance === 'undefined' && native.ttsVoices) {
        class Utterance extends EventTarget {
            constructor(text = '') {
                super();
                this.text = String(text);
                this.lang = '';
                this.voice = null;
                this.rate = 1;
                this.pitch = 1;
                this.volume = 1;
                for (const type of ['start', 'end', 'error', 'pause', 'resume', 'boundary', 'mark']) this['on' + type] = null;
            }
        }
        const fire = (target, type, extra = {}) => {
            const event = new Event(type);
            Object.assign(event, { utterance: target, charIndex: 0, elapsedTime: 0, name: '' }, extra);
            try {
                target['on' + type]?.call(target, event);
            } catch (error) {
                console.error(error);
            }
            target.dispatchEvent(event);
        };

        let seq = 0;
        let paused = false;
        let voices = null;
        let parked = [];
        const live = new Map(); // utterance id -> utterance, in speaking order
        const synth = new EventTarget();
        synth.onvoiceschanged = null;
        Object.defineProperties(synth, {
            speaking: { get: () => live.size > 0 },
            pending: { get: () => live.size > 1 || parked.length > 0 },
            paused: { get: () => paused },
        });
        const send = (utterance) => {
            const id = 'u' + (++seq);
            live.set(id, utterance);
            native.ttsSpeak(id, String(utterance.text ?? ''), utterance.voice?.voiceURI || '', utterance.lang || '',
                Number(utterance.rate) || 1, Number(utterance.pitch) || 1,
                utterance.volume == null ? 1 : Number(utterance.volume));
        };
        synth.getVoices = () => {
            if (!voices || voices.length === 0) {
                try {
                    voices = JSON.parse(native.ttsVoices()).map((v) => Object.freeze(v));
                } catch {
                    voices = [];
                }
            }
            return voices.slice();
        };
        synth.speak = (utterance) => {
            if (paused) parked.push(utterance);
            else send(utterance);
        };
        const flush = () => {
            // Utterances that were cleared end without being spoken, like in Chrome.
            const dropped = [...live.values(), ...parked];
            live.clear();
            parked = [];
            native.ttsStop();
            dropped.forEach((utterance) => fire(utterance, 'end'));
        };
        synth.cancel = () => {
            paused = false;
            flush();
        };
        synth.pause = () => {
            if (paused) return;
            paused = true;
            parked = [...live.values(), ...parked];
            live.clear();
            native.ttsStop();
            parked[0] && fire(parked[0], 'pause');
        };
        synth.resume = () => {
            if (!paused) return;
            paused = false;
            const queue = parked;
            parked = [];
            queue[0] && fire(queue[0], 'resume');
            queue.forEach(send);
        };
        window.__tdSpeech = {
            onNative(id, type, detail) {
                const utterance = live.get(id);
                if (!utterance) return;
                if (type === 'start') {
                    fire(utterance, 'start');
                    return;
                }
                live.delete(id);
                if (type === 'error') fire(utterance, 'error', { error: detail || 'synthesis-failed' });
                else fire(utterance, 'end');
            },
            onVoicesChanged() {
                voices = null;
                fire(synth, 'voiceschanged');
            },
        };
        window.SpeechSynthesisUtterance = Utterance;
        Object.defineProperty(window, 'speechSynthesis', { configurable: true, get: () => synth });
        synth.getVoices(); // starts the Android TTS engine
    }

    // ----------------------------------------------------------- back button
    // Returns true when some SillyTavern panel, popup or editor is open; the app
    // then sends a real Escape key, which SillyTavern already handles layer by layer.
    const visible = (el) => !!el && el.offsetParent !== null && getComputedStyle(el).visibility !== 'hidden';
    window.__taverndroidBack = () => {
        if (document.querySelector('dialog[open]')) return true;
        const selectors = [
            '.drawer-content.openDrawer', '#character_popup', '#select_chat_popup', '#dialogue_popup',
            '#curEditTextarea', '#dialogue_del_mes_cancel', '#logprobsViewer', '#floatingPrompt',
            '#cfgConfig', '.zoomed_avatar', '#movingDivs > div', '.select2-container--open',
        ];
        return selectors.some((selector) => Array.from(document.querySelectorAll(selector)).some(visible));
    };

    // ------------------------------------------------------------ 省电流畅
    // 只在用户点了 App 设置里的「省电流畅」后由安卓端调用。改的是当前用户的四项设置，跟手动在
    // 「用户设置」里点一样（走 SillyTavern 自己的处理函数和保存）。返回 'ok' / 'not-ready' / 错误说明。
    let appReady = false; // SillyTavern 发出 APP_READY（设置已经从服务器读进来）之后才能改
    window.__jiuguanPerf = () => {
        const context = window.SillyTavern?.getContext?.();
        if (!appReady) return 'not-ready';
        const $ = window.jQuery;
        const power = context?.powerUserSettings;
        if (!context || !$ || !power || !$('#fast_ui_mode').length) return 'not-ready';
        try {
            // 两个开关：勾上后触发 SillyTavern 自己监听的事件，界面效果立刻生效
            const fast = $('#fast_ui_mode');
            if (!fast.prop('checked')) fast.prop('checked', true).trigger('change');
            const reduced = $('#reduced_motion');
            if (!reduced.prop('checked') && !reduced.prop('disabled')) reduced.prop('checked', true).trigger('input');
            // 两个滑块：直接写设置值（Streaming FPS 的滑块每格是 5，拖不到 12，所以不经过滑块）
            power.streaming_fps = 12;
            $('#streaming_fps').val(12);
            $('#streaming_fps_counter').val(12);
            power.chat_truncation = 30;
            $('#chat_truncation').val(30);
            $('#chat_truncation_counter').val(30);
            context.saveSettingsDebounced();
            return 'ok';
        } catch (error) {
            console.error('[酒馆] 省电流畅失败', error);
            return String(error?.message || error);
        }
    };

    // ------------------------------------------------- SillyTavern integration
    let integrated = false;
    function onReady() {
        if (integrated) return true;
        const context = window.SillyTavern?.getContext?.();
        if (!context?.eventSource || !context.eventTypes) return false;
        const { eventSource, eventTypes } = context;

        const onAppReady = () => {
            if (appReady) return;
            appReady = true;
            if (typeof native.appReady === 'function') native.appReady();
        };
        if (eventTypes.APP_READY) eventSource.on(eventTypes.APP_READY, onAppReady); // 已经发过的话会立刻回调
        else onAppReady();

        eventSource.on(eventTypes.GENERATION_STARTED, (type, _options, dryRun) => {
            if (!dryRun) native.generationStarted(String(type || 'normal'));
        });
        const ended = () => native.generationEnded();
        eventSource.on(eventTypes.GENERATION_ENDED, ended);
        eventSource.on(eventTypes.GENERATION_STOPPED, ended);
        eventSource.on(eventTypes.MESSAGE_RECEIVED, (messageId, type) => {
            if (type === 'quiet' || type === 'impersonate') return;
            const message = window.SillyTavern.getContext().chat?.[messageId];
            if (!message || message.is_user || message.is_system) return;
            const text = String(message.mes || '').replace(/\s+/g, ' ').trim();
            native.replyReceived(String(message.name || 'SillyTavern'), text.slice(0, 400));
        });

        integrated = true;
        addAppButton();
        native.pageReady();
        return true;
    }

    // 左下角「☰」菜单里加一项「酒馆 App 设置」（不往顶栏塞图标，免得挤）。
    function addAppButton() {
        const menu = document.querySelector('#options .options-content');
        if (!menu || document.getElementById('option_jiuguan_app')) return;
        const item = document.createElement('a');
        item.id = 'option_jiuguan_app';
        const icon = document.createElement('i');
        icon.className = 'fa-lg fa-solid fa-mobile-screen-button';
        const label = document.createElement('span');
        label.textContent = '酒馆 App 设置';
        item.append(icon, label);
        item.addEventListener('click', () => {
            document.getElementById('options_button')?.click(); // 让 SillyTavern 自己把菜单收起来
            native.openSettings();
        });
        menu.prepend(item);
    }

    // getContext() can throw while script.js is still initialising, so poll until it works.
    let attempts = 0;
    const waitForApp = () => {
        // The login page has no SillyTavern UI; nothing to integrate there.
        if (!document.getElementById('top-settings-holder')) return;
        let ready = false;
        try {
            ready = onReady();
        } catch (error) {
            if (++attempts % 40 === 0) console.warn('[酒馆] waiting for SillyTavern', error);
        }
        if (!ready) setTimeout(waitForApp, 250);
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(waitForApp, 0));
    } else {
        waitForApp();
    }
})();
