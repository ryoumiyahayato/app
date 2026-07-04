/**
 * 电子木鱼 — 本地开发测试 WSS 中转服务
 *
 * ⚠️ 仅限本地开发测试用 ⚠️
 * 正式环境必须使用 Cloudflare Worker + Durable Object + wss://
 * 此服务不做认证、不做持久化、不做离线缓存。
 */

const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 8443;

// 存储 room -> Set<WebSocket>
const rooms = new Map();

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return deviceId.substring(0, 4) + '***' + deviceId.substring(deviceId.length - 4);
}

const server = new WebSocket.Server({ port: PORT }, () => {
    console.log(`[relay] 电子木鱼测试中继已启动 → ws://0.0.0.0:${PORT}`);
    console.log(`[relay] ⚠️ 仅限本地开发测试，请勿用于生产环境`);
    console.log(`[relay] 连接示例: ws://<电脑IP>:${PORT}?room=test-room`);
});

server.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const room = url.searchParams.get('room') || 'default';
    const clientIp = req.socket.remoteAddress;
    const connId = crypto.randomUUID().substring(0, 8);

    console.log(`[relay] [${connId}] 新连接 → room=${room} ip=${clientIp}`);

    // 加入 room
    if (!rooms.has(room)) {
        rooms.set(room, new Set());
    }
    rooms.get(room).add(ws);

    // 通知房间人数
    const roomSize = rooms.get(room).size;
    console.log(`[relay] [${connId}] room=${room} 当前连接数: ${roomSize}`);

    ws.send(JSON.stringify({
        type: 'room_info',
        room: room,
        connections: roomSize,
        timestamp: Date.now()
    }));

    ws.on('message', (data) => {
        try {
            const parsed = JSON.parse(data.toString());

            // 日志脱敏：不打印完整 deviceId
            const masked = {
                ...parsed,
                deviceId: parsed.deviceId ? maskDeviceId(parsed.deviceId) : 'unknown'
            };
            console.log(`[relay] [${connId}] 收到消息 type=${parsed.type} deviceId=${masked.deviceId}`);

            if (parsed.type !== 'tap') {
                console.log(`[relay] [${connId}] 忽略未知消息类型: ${parsed.type}`);
                return;
            }

            // 转发给同 room 的其他所有连接
            const clients = rooms.get(room);
            if (!clients || clients.size <= 1) {
                console.log(`[relay] [${connId}] room=${room} 没有其他在线设备，丢弃 tap`);
                return;
            }

            let forwarded = 0;
            clients.forEach((client) => {
                if (client !== ws && client.readyState === WebSocket.OPEN) {
                    client.send(JSON.stringify(parsed));
                    forwarded++;
                }
            });

            console.log(`[relay] [${connId}] 转发 tap 给 ${forwarded} 个客户端`);
        } catch (err) {
            console.error(`[relay] [${connId}] 消息解析失败:`, err.message);
        }
    });

    ws.on('close', (code, reason) => {
        console.log(`[relay] [${connId}] 断开连接 code=${code} reason=${reason || 'none'}`);
        const clients = rooms.get(room);
        if (clients) {
            clients.delete(ws);
            if (clients.size === 0) {
                rooms.delete(room);
                console.log(`[relay] room=${room} 已无连接，清理`);
            } else {
                console.log(`[relay] room=${room} 剩余连接数: ${clients.size}`);
            }
        }
    });

    ws.on('error', (err) => {
        console.error(`[relay] [${connId}] 错误:`, err.message);
    });
});

server.on('error', (err) => {
    console.error('[relay] 服务器错误:', err.message);
});

process.on('SIGINT', () => {
    console.log('\n[relay] 收到 SIGINT，关闭服务...');
    server.close(() => {
        console.log('[relay] 服务已关闭');
        process.exit(0);
    });
});

process.on('SIGTERM', () => {
    console.log('\n[relay] 收到 SIGTERM，关闭服务...');
    server.close(() => {
        console.log('[relay] 服务已关闭');
        process.exit(0);
    });
});