/**
 * 电子木鱼 — 交互式联调客户端
 *
 * 用法：
 *   node test-client.js
 *   node test-client.js my-room
 *
 * 环境变量：
 *   WS_URL=ws://localhost:8443
 */

const WebSocket = require('ws');
const readline = require('readline');
const crypto = require('crypto');

const SERVER = process.env.WS_URL || 'ws://localhost:8443';
const room = (process.argv[2] || 'test-room').trim();
const deviceId = `test-client-${process.pid}`;

function buildTargetUrl() {
    if (!room || room.length > 64 || /[\u0000-\u001F\u007F]/.test(room)) {
        throw new Error('room 必须为 1-64 个无控制字符的文本');
    }
    const target = new URL(SERVER);
    if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }
    target.searchParams.set('room', room);
    return target;
}

function maskDeviceId(value) {
    if (!value || value.length < 8) return '***';
    return `${value.substring(0, 4)}***${value.substring(value.length - 4)}`;
}

function shortHash(value) {
    return crypto.createHash('sha256').update(value).digest('hex').substring(0, 8);
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

console.log(
    `[test-client] 连接 ${targetUrl.protocol}//${targetUrl.host}${targetUrl.pathname} `
    + `roomHash=${shortHash(room)} deviceId=${maskDeviceId(deviceId)}`
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
                console.error('[test-client] relay 返回了不匹配的 room');
                ws.close(1008, 'room mismatch');
                return;
            }
            accepted = true;
            console.log(`[test-client] relay 已接受，在线连接数=${message.connections}`);
            return;
        }

        if (message.type === 'tap') {
            const sender = maskDeviceId(message.deviceId);
            const time = new Date(message.timestamp).toLocaleTimeString();
            console.log(`[test-client] 收到 tap，deviceId=${sender} time=${time}`);
        }
    } catch (err) {
        console.error('[test-client] 无法解析响应:', err.message);
    }
});

ws.on('close', (code, reasonBuffer) => {
    const reason = reasonBuffer?.toString() || 'none';
    console.log(`[test-client] 连接关闭 code=${code} reason=${reason}`);
    rl.close();
    process.exit(userClosing && code === 1000 ? 0 : 1);
});

ws.on('error', (err) => {
    console.error('[test-client] WebSocket 错误:', err.message);
});

rl.on('line', (input) => {
    if (input.trim().toLowerCase() === 'q') {
        userClosing = true;
        ws.close(1000, 'user exit');
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
            console.error('[test-client] 发送失败:', err.message);
        } else {
            console.log(`[test-client] 已发送 tap (${new Date().toLocaleTimeString()})`);
        }
    });
});

rl.on('close', () => {
    if (ws.readyState === WebSocket.OPEN) {
        userClosing = true;
        ws.close(1000, 'input closed');
    }
});
