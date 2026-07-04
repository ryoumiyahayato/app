/**
 * 电子木鱼 — 发送一次测试 tap
 *
 * 用法：
 *   node send-tap.js
 *
 * 作用：
 *   连接本地中继，向 test-room 发送一次 tap，然后退出。
 *   用于验证 电脑脚本 → Node 中继 → Android 手机 通路。
 */

const WebSocket = require('ws');

const WS_URL = 'ws://localhost:8443?room=test-room';
const ws = new WebSocket(WS_URL);

ws.on('open', () => {
    console.log('[send-tap] 已连接');

    const tap = {
        type: 'tap',
        pairId: 'test-room',
        deviceId: 'test-client',
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap));
    console.log('[send-tap] 已发送 tap');
});

ws.on('message', (data) => {
    // 忽略 room_info 等回应消息
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

// 发送后等 1 秒再关闭退出
setTimeout(() => {
    ws.close();
}, 1000);