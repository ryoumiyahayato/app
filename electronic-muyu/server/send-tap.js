/**
 * 电子木鱼 — 发送一次测试 tap
 *
 * 用法：
 *   node send-tap.js
 *
 * 环境变量（可选）：
 *   WS_URL=ws://localhost:8443        # 服务器地址，可包含已有 query
 *   ROOM_ID=test-room                  # 房间 ID
 *   DEVICE_ID=test-client              # 设备标识
 *
 * 示例：
 *   node send-tap.js
 *   $env:ROOM_ID="test-room-2"; node send-tap.js
 *   $env:WS_URL="wss://example.com"; $env:ROOM_ID="test-room"; node send-tap.js
 */

const WebSocket = require('ws');

const WS_URL = process.env.WS_URL || 'ws://localhost:8443';
const ROOM_ID = process.env.ROOM_ID || 'test-room';
const DEVICE_ID = process.env.DEVICE_ID || 'test-client';

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return `${deviceId.substring(0, 4)}***${deviceId.substring(deviceId.length - 4)}`;
}

function buildWebSocketUrl(baseUrl, roomId) {
    if (!roomId || !roomId.trim()) {
        throw new Error('ROOM_ID 不能为空');
    }

    const targetUrl = new URL(baseUrl);
    if (targetUrl.protocol !== 'ws:' && targetUrl.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }

    targetUrl.searchParams.set('room', roomId.trim());
    return targetUrl;
}

let targetUrl;
try {
    targetUrl = buildWebSocketUrl(WS_URL, ROOM_ID);
} catch (err) {
    console.error('[send-tap] 配置错误:', err.message);
    process.exit(1);
}

const safeUrlForLog = `${targetUrl.protocol}//${targetUrl.host}${targetUrl.pathname}`;
const ws = new WebSocket(targetUrl.toString());

console.log(`[send-tap] 连接 ${safeUrlForLog} room=<hidden> ...`);

ws.on('open', () => {
    console.log('[send-tap] 已连接');

    const tap = {
        type: 'tap',
        pairId: ROOM_ID.trim(),
        deviceId: DEVICE_ID,
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap));
    console.log(`[send-tap] 已发送 tap (room=<hidden> deviceId=${maskDeviceId(DEVICE_ID)})`);
});

ws.on('message', (data) => {
    try {
        const parsed = JSON.parse(data.toString());
        if (parsed.type === 'room_info') {
            console.log(`[send-tap] room=<hidden> connections=${parsed.connections}`);
        }
    } catch (err) {
        console.error('[send-tap] 收到无法解析的消息:', err.message);
    }
});

ws.on('close', () => {
    console.log('[send-tap] 已关闭');
    process.exit(0);
});

ws.on('error', (err) => {
    console.error('[send-tap] 错误:', err.message);
    process.exit(1);
});

setTimeout(() => {
    ws.close();
}, 1000);
