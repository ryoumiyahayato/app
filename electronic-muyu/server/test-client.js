/**
 * 电子木鱼 — 阶段 3 联调测试客户端
 *
 * ⚠️ 仅用于本地开发测试，验证双人 tap 收发。
 * 使用方法：
 *   node test-client.js <room>            # 不带参数：默认 test-room
 *   node test-client.js my-room           # 指定 room
 *
 * 该客户端会：
 * 1. 连接中继服务器
 * 2. 显示收到的 tap 事件
 * 3. 输入回车发送一次 tap
 * 4. 输入 q + 回车退出
 */

const WebSocket = require('ws');
const readline = require('readline');

const SERVER = process.env.WS_URL || 'ws://localhost:8443';
const room = process.argv[2] || 'test-client';
const deviceId = `test-client-${process.pid}`;

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

console.log(`[test-client] 连接服务器: ${SERVER}?room=${room}`);
console.log(`[test-client] deviceId: ${deviceId}`);
console.log(`[test-client] 按 Enter 发送 tap，输入 q + Enter 退出\n`);

const ws = new WebSocket(`${SERVER}?room=${room}`);

ws.on('open', () => {
    console.log('[test-client] ✅ 已连接');
});

ws.on('message', (data) => {
    try {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'tap') {
            const time = new Date(msg.timestamp).toLocaleTimeString();
            const maskedId = msg.deviceId.substring(0, 4) + '***' + msg.deviceId.substring(msg.deviceId.length - 4);
            console.log(`[test-client] 🔔 收到 tap 来自 ${maskedId} 时间=${time}`);
        } else if (msg.type === 'room_info') {
            console.log(`[test-client] 📋 房间信息: room=${msg.room} 在线=${msg.connections}`);
        } else {
            console.log(`[test-client] 📨 收到消息:`, JSON.stringify(msg));
        }
    } catch (e) {
        console.log('[test-client] 📨 收到原始消息:', data.toString());
    }
});

ws.on('close', (code, reason) => {
    console.log(`[test-client] ❌ 断开连接 code=${code} reason=${reason}`);
    process.exit(0);
});

ws.on('error', (err) => {
    console.error('[test-client] ⚠️ 错误:', err.message);
});

rl.on('line', (input) => {
    if (input.toLowerCase() === 'q') {
        console.log('[test-client] 退出');
        ws.close();
        rl.close();
        return;
    }

    const tap = {
        type: 'tap',
        pairId: room,
        deviceId: deviceId,
        timestamp: Date.now()
    };

    ws.send(JSON.stringify(tap));
    console.log(`[test-client] 👆 发送 tap (${new Date().toLocaleTimeString()})`);
});

rl.on('close', () => {
    ws.close();
    process.exit(0);
});