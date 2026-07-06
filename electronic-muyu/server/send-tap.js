/**
 * 电子木鱼 — 发送一次测试 tap
 *
 * 用法：
 *   node send-tap.js
 *
 * 环境变量（可选）：
 *   WS_URL=ws://localhost:8443        # 服务器地址
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

const url = `${WS_URL}?room=${ROOM_ID}`;
const ws = new WebSocket(url);

console.log(`[send-tap] 连接 ${url} ...`);

ws.on('open', () => {
    console.log('[send-tap] 已连接');

    const tap = {
        type: 'tap',
        pairId: ROOM_ID,
        deviceId: DEVICE_ID,
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap));
    console.log(`[send-tap] 已发送 tap (room=${ROOM_ID} deviceId=${DEVICE_ID})`);
});

ws.on('message', (data) => {
    const parsed = JSON.parse(data.toString());
    if (parsed.type === 'room_info') {
        console.log(`[send-tap] room=${parsed.room} connections=${parsed.connections}`);
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
