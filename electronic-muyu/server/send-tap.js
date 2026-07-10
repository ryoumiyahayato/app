/**
 * 电子木鱼 — 发送一次测试 tap
 *
 * 环境变量：
 *   WS_URL=ws://localhost:8443
 *   ROOM_ID=test-room
 *   DEVICE_ID=test-client
 */

const WebSocket = require('ws');
const crypto = require('crypto');

const WS_URL = process.env.WS_URL || 'ws://localhost:8443';
const ROOM_ID = (process.env.ROOM_ID || 'test-room').trim();
const DEVICE_ID = (process.env.DEVICE_ID || 'test-client').trim();
const TIMEOUT_MS = 5_000;

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return `${deviceId.substring(0, 4)}***${deviceId.substring(deviceId.length - 4)}`;
}

function roomHash(roomId) {
    return crypto.createHash('sha256').update(roomId).digest('hex').substring(0, 8);
}

function validateConfig() {
    if (!ROOM_ID || ROOM_ID.length > 64 || /[\u0000-\u001F\u007F]/.test(ROOM_ID)) {
        throw new Error('ROOM_ID 必须为 1-64 个无控制字符的文本');
    }
    if (!DEVICE_ID || DEVICE_ID.length > 128 || /[\u0000-\u001F\u007F]/.test(DEVICE_ID)) {
        throw new Error('DEVICE_ID 必须为 1-128 个无控制字符的文本');
    }

    const targetUrl = new URL(WS_URL);
    if (targetUrl.protocol !== 'ws:' && targetUrl.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }
    targetUrl.searchParams.set('room', ROOM_ID);
    return targetUrl;
}

let targetUrl;
try {
    targetUrl = validateConfig();
} catch (err) {
    console.error('[send-tap] 配置错误:', err.message);
    process.exit(1);
}

const safeUrlForLog = `${targetUrl.protocol}//${targetUrl.host}${targetUrl.pathname}`;
const ws = new WebSocket(targetUrl.toString());
let sent = false;
let finished = false;

console.log(
    `[send-tap] 连接 ${safeUrlForLog} roomHash=${roomHash(ROOM_ID)} `
    + `deviceId=${maskDeviceId(DEVICE_ID)} ...`
);

const timeout = setTimeout(() => {
    finish(1, '等待 relay 接受连接超时');
}, TIMEOUT_MS);

ws.on('open', () => {
    console.log('[send-tap] WebSocket 已打开，等待 room_info');
});

ws.on('message', (data, isBinary) => {
    if (isBinary) {
        finish(1, '收到非文本响应');
        return;
    }

    let parsed;
    try {
        parsed = JSON.parse(data.toString());
    } catch (err) {
        finish(1, `收到无法解析的消息: ${err.message}`);
        return;
    }

    if (parsed.type !== 'room_info' || sent) return;
    if (parsed.room !== ROOM_ID) {
        finish(1, 'relay 返回的 room 与请求不一致');
        return;
    }

    const tap = {
        type: 'tap',
        pairId: ROOM_ID,
        deviceId: DEVICE_ID,
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap), (err) => {
        if (err) {
            finish(1, `发送失败: ${err.message}`);
            return;
        }
        sent = true;
        console.log(`[send-tap] 已发送 tap，当前房间连接数=${parsed.connections}`);
        setTimeout(() => ws.close(1000, 'tap sent'), 200);
    });
});

ws.on('close', (code, reasonBuffer) => {
    const reason = reasonBuffer?.toString() || 'none';
    if (sent && code === 1000) {
        finish(0, '已完成并关闭');
    } else {
        finish(1, `连接关闭 code=${code} reason=${reason}`);
    }
});

ws.on('error', (err) => {
    finish(1, `WebSocket 错误: ${err.message}`);
});

function finish(exitCode, message) {
    if (finished) return;
    finished = true;
    clearTimeout(timeout);

    if (exitCode === 0) {
        console.log(`[send-tap] ${message}`);
    } else {
        console.error(`[send-tap] ${message}`);
    }

    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        try {
            ws.close(1000, 'client finish');
        } catch (_) {
            ws.terminate();
        }
    }

    setTimeout(() => process.exit(exitCode), 20);
}
