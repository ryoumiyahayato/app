/**
 * 电子木鱼 — WebSocket 中继服务
 *
 * 支持本地开发和公网测试部署。
 * 不做认证（可选 RELAY_TOKEN）、不做持久化、不做离线缓存。
 */

const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 8443;
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';

const rooms = new Map();

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return deviceId.substring(0, 4) + '***' + deviceId.substring(deviceId.length - 4);
}

// ------ HTTP server (for health check and non-WS fallback) ------

const httpServer = http.createServer((req, res) => {
    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (parsedUrl.pathname === '/health' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, service: 'electronic-muyu-relay' }));
        return;
    }

    res.writeHead(426, { 'Content-Type': 'text/plain' });
    res.end('This is a WebSocket server. Use ws:// to connect.\n');
});

// ------ WebSocket server (attached to HTTP server) ------

const wss = new WebSocket.Server({ server: httpServer });

wss.on('connection', (ws, req) => {
    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const room = parsedUrl.searchParams.get('room');
    const token = parsedUrl.searchParams.get('token') || '';
    const clientIp = req.socket.remoteAddress;
    const connId = crypto.randomUUID().substring(0, 8);

    // Require room parameter
    if (!room) {
        console.log(`[relay] [${connId}] 拒绝连接: 缺少 room 参数, ip=${clientIp}`);
        ws.close(4000, 'room parameter required');
        return;
    }

    // Optional token check
    if (RELAY_TOKEN && token !== RELAY_TOKEN) {
        console.log(`[relay] [${connId}] 拒绝连接: token 无效, ip=${clientIp}, room=${room}`);
        ws.close(4001, 'invalid token');
        return;
    }

    console.log(`[relay] [${connId}] 新连接 → room=${room} ip=${clientIp}`);

    if (!rooms.has(room)) {
        rooms.set(room, new Set());
    }
    rooms.get(room).add(ws);

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

            const masked = {
                ...parsed,
                deviceId: parsed.deviceId ? maskDeviceId(parsed.deviceId) : 'unknown'
            };
            console.log(`[relay] [${connId}] 收到消息 type=${parsed.type} deviceId=${masked.deviceId}`);

            if (parsed.type !== 'tap') {
                console.log(`[relay] [${connId}] 忽略未知消息类型: ${parsed.type}`);
                return;
            }

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

wss.on('error', (err) => {
    console.error('[relay] 服务器错误:', err.message);
});

httpServer.listen(PORT, () => {
    console.log(`[relay] 电子木鱼中继已启动 → ws://0.0.0.0:${PORT}`);
    console.log(`[relay] 健康检查 → http://localhost:${PORT}/health`);
    if (RELAY_TOKEN) {
        console.log(`[relay] RELAY_TOKEN 已设置，客户端需传递 &token=xxx`);
    } else {
        console.log(`[relay] RELAY_TOKEN 未设置（仅限私用测试）`);
    }
    console.log(`[relay] 连接示例: ws://<host>:${PORT}?room=<roomId>`);
});

process.on('SIGINT', () => {
    console.log('\n[relay] 收到 SIGINT，关闭服务...');
    wss.close();
    httpServer.close(() => {
        console.log('[relay] 服务已关闭');
        process.exit(0);
    });
});

process.on('SIGTERM', () => {
    console.log('\n[relay] 收到 SIGTERM，关闭服务...');
    wss.close();
    httpServer.close(() => {
        console.log('[relay] 服务已关闭');
        process.exit(0);
    });
});
