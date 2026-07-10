/**
 * 电子木鱼 — 交互式联调客户端
 *
 * 用法：
 *   node test-client.js
 *   node test-client.js my-room
 *
 * 环境变量：
 *   WS_URL=ws://localhost:8443
 *   RELAY_TOKEN=optional-local-test-token
 */

const WebSocket = require('ws');
const readline = require('readline');
const crypto = require('crypto');

const SERVER = process.env.WS_URL || 'ws://localhost:8443';
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const room = (process.argv[2] || 'test-room').trim();
const deviceId = `test-client-${process.pid}`;
const ACCEPT_TIMEOUT_MS = 5_000;

function buildTargetUrl() {
    if (!room || room.length > 64 || /[\u0000-\u001F\u007F]/.test(room)) {
        throw new Error('room 必须为 1-64 个无控制字符的文本');
    }
    const target = new URL(SERVER);
    if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }
    target.searchParams.set('room', room);
    if (RELAY_TOKEN) {
        target.searchParams.set('token', RELAY_TOKEN);
    }
    return target;
}

function maskDeviceId(value) {
    if (!value || value.length < 8) return '***';
    return `${value.substring(0, 4)}***${value.substring(value.length - 4)}`;
}

function shortHash(value) {
    return crypto.createHash('sha256').update(value).digest('hex').substring(0, 8);
}

function safeReason(value) {
    return String(value || 'none')
        .replace(/[\u0000-\u001F\u007F]/g, '?')
        .slice(0, 80);
}

let targetUrl;
try {
    targetUrl = buildTargetUrl();
} catch (err) {
    console.error('[test-client] 配置错误:', err.message);
    process.exit(1);
}

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});
const ws = new WebSocket(targetUrl.toString());
let accepted = false;
let userClosing = false;
let finished = false;

const acceptTimer = setTimeout(() => {
    finish(1, '等待 relay 接受连接超时');
}, ACCEPT_TIMEOUT_MS);

console.log(
    `[test-client] 连接 ${targetUrl.protocol}//${targetUrl.host}${targetUrl.pathname} `
    + `roomHash=${shortHash(room)} deviceId=${maskDeviceId(deviceId)} `
    + `auth=${RELAY_TOKEN ? 'enabled' : 'disabled'}`
);
console.log('[test-client] relay 接受连接后，按 Enter 发送 tap；输入 q 后回车退出');

ws.on('open', () => {
    console.log('[test-client] WebSocket 已打开，等待 room_info');
});

ws.on('message', (data, isBinary) => {
    if (isBinary) {
        console.error('[test-client] 忽略非文本响应');
        return;
    }

    try {
        const message = JSON.parse(data.toString());
        if (message.type === 'room_info') {
            if (message.room !== room) {
                finish(1, 'relay 返回了不匹配的 room');
                return;
            }
            accepted = true;
            clearTimeout(acceptTimer);
            console.log(`[test-client] relay 已接受，在线连接数=${message.connections}`);
            return;
        }

        if (message.type === 'tap') {
            const sender = maskDeviceId(message.deviceId);
            const time = new Date(message.timestamp).toLocaleTimeString();
            console.log(`[test-client] 收到 tap，deviceId=${sender} time=${time}`);
        }
    } catch (err) {
        console.error('[test-client] 无法解析响应:', safeReason(err.message));
    }
});

ws.on('close', (code, reasonBuffer) => {
    if (finished) return;
    const reason = safeReason(reasonBuffer?.toString());
    finish(userClosing ? 0 : 1, `连接关闭 code=${code} reason=${reason}`, false);
});

ws.on('error', (err) => {
    if (!finished) {
        console.error('[test-client] WebSocket 错误:', safeReason(err.message));
    }
});

rl.on('line', (input) => {
    if (input.trim().toLowerCase() === 'q') {
        userClosing = true;
        finish(0, '用户退出');
        return;
    }

    if (!accepted || ws.readyState !== WebSocket.OPEN) {
        console.log('[test-client] relay 尚未接受连接，未发送');
        return;
    }

    const tap = {
        type: 'tap',
        pairId: room,
        deviceId,
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap), (err) => {
        if (err) {
            console.error('[test-client] 发送失败:', safeReason(err.message));
        } else {
            console.log(`[test-client] 已发送 tap (${new Date().toLocaleTimeString()})`);
        }
    });
});

rl.on('close', () => {
    if (!finished) {
        userClosing = true;
        finish(0, '输入已关闭');
    }
});

function finish(exitCode, message, closeSocket = true) {
    if (finished) return;
    finished = true;
    clearTimeout(acceptTimer);
    console.log(`[test-client] ${message}`);

    if (closeSocket) {
        if (ws.readyState === WebSocket.OPEN) {
            ws.close(1000, 'client finish');
        } else if (ws.readyState === WebSocket.CONNECTING) {
            ws.terminate();
        }
    }

    rl.close();
    setTimeout(() => process.exit(exitCode), 20);
}
