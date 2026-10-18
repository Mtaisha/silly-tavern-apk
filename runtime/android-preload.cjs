'use strict';
// Loaded with `node --require` before SillyTavern starts.
//
// The bundled Node.js is the Termux build, whose child_process uses
// /data/data/com.termux/files/usr/bin/sh whenever `shell: true` is requested
// (exec, execSync, spawn with shell, ...). That path does not exist inside this
// app, so every shell spawn is redirected to the system shell instead.
const childProcess = require('node:child_process');
const { syncBuiltinESMExports } = require('node:module');

const SHELL = process.env.TAVERNDROID_SHELL || '/system/bin/sh';

// 记下进程号，app 下次启动时能清理掉意外残留的服务器进程。
if (process.env.JIUGUAN_PID_FILE) {
    try {
        require('node:fs').writeFileSync(process.env.JIUGUAN_PID_FILE, String(process.pid));
    } catch {
        // 写不了也不影响运行
    }
    // 只记服务器自己：它再启动的 node 子进程（比如插件）不要覆盖这个文件
    delete process.env.JIUGUAN_PID_FILE;
}

function withShell(options, defaultShell) {
    if (options === null || typeof options !== 'object') {
        return defaultShell ? { shell: SHELL } : options;
    }
    if (options.shell === true || (defaultShell && options.shell === undefined)) {
        return { ...options, shell: SHELL };
    }
    return options;
}

// exec/execSync always run through a shell; spawn/execFile only when asked.
const original = {};
for (const name of ['exec', 'execSync', 'execFile', 'execFileSync', 'spawn', 'spawnSync']) {
    original[name] = childProcess[name];
}

childProcess.exec = function exec(command, options, callback) {
    if (typeof options === 'function') {
        return original.exec.call(this, command, withShell(undefined, true), options);
    }
    return original.exec.call(this, command, withShell(options, true), callback);
};

childProcess.execSync = function execSync(command, options) {
    return original.execSync.call(this, command, withShell(options, true));
};

// (file, args?, options?, ...rest): the options object is the first plain object after the file.
function patchOptionsArg(fnName) {
    childProcess[fnName] = function (file, ...rest) {
        for (let i = 0; i < rest.length; i++) {
            const arg = rest[i];
            if (arg !== null && typeof arg === 'object' && !Array.isArray(arg)) {
                rest[i] = withShell(arg, false);
                break;
            }
        }
        return original[fnName].call(this, file, ...rest);
    };
}
['execFile', 'execFileSync', 'spawn', 'spawnSync'].forEach(patchOptionsArg);

syncBuiltinESMExports();
