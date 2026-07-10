/**
 * 电子木鱼 — WebSocket 中继服务
 *
 * 支持本地开发和短期公网测试部署。
 * 不做账号认证（仅支持可选 RELAY_TOKEN）、不做持久化、不做离线缓存。
 */

const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 8443;
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_PAYLOAD_BYTES = 4 * 1024;
const MAX_ROOM_CONNECTIONS = 2;
const RATE_LIMIT_WINDOW_MS = 10_000;
const MAX_MESSAGES_PER_WINDOW = 20;

const rooms = new Map();

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return deviceId.substring(0, 4) + '***' + deviceId.substring(deviceId.length - 4);
}

function roomLogId(room) {
    return crypto.createHash('sha256').update(room).digest('hex').substring(0, 8);
}

function isValidRoom(room) {
    return typeof room === 'string'
        && room.length > 0
        && room.length <= 64
        && !/[\u0000-\u001F\u007F]/.test(room);
}

function isValidTap(message, room) {
    return message !== null
        && typeof message === 'object'
        && message.type === 'tap'
        && typeof message.pairId === 'string'
        && message.pairId === room
        && typeof message.deviceId === 'string'
        && message.deviceId.length > 0
        && message.deviceId.length <= 128
        && Number.isSafeInteger(message.timestamp)
        && message.timestamp > 0;
}

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

const wss = new WebSocket.Server({
    server: httpServer,
    maxPayload: MAX_PAYLOAD_BYTES
});

wss.on('connection', (ws, req) => {
    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const room = parsedUrl.searchParams.get('room');
    const token = parsedUrl.searchParams.get('token') || '';
    const clientIp = req.socket.remoteAddress;
    const connId = crypto.randomUUID().substring(0, 8);

    if (!isValidRoom(room)) {
        console.log(`[relay] [${connId}] 拒绝连接: room 参数无效, ip=${clientIp}`);
        ws.close(4000, 'valid room parameter required');
        return;
    }

    const roomIdForLog = roomLogId(room);

    if (RELAY_TOKEN && token !== RELAY_TOKEN) {
        console.log(`[relay] [${connId}] 拒绝连接: token 无效, ip=${clientIp}, roomHash=${roomIdForLog}`);
        ws.close(4001, 'invalid token');
        return;
    }

    const existingClients = rooms.get(room);
    if (existingClients && existingClients.size >= MAX_ROOM_CONNECTIONS) {
        console.log(`[relay] [${connId}] 拒绝连接: room 已满, ip=${clientIp}, roomHash=${roomIdForLog}`);
        ws.close(4002, 'room is full');
        return;
    }

    console.log(`[relay] [${connId}] 新连接 → roomHash=${roomIdForLog} ip=${clientIp}`);

    if (!rooms.has(room)) {
        rooms.set(room, new Set());
    }
    rooms.get(room).add(ws);

    const roomSize = rooms.get(room).size;
    console.log(`[relay] [${connId}] roomHash=${roomIdForLog} 当前连接数: ${roomSize}`);

    ws.send(JSON.stringify({
        type: 'room_info',
        room,
        connections: roomSize,
        timestamp: Date.now()
    }));

    let messageWindowStartedAt = Date.now();
    let messagesInWindow = 0;

    ws.on('message', (data) => {
        const now = Date.now();
        if (now - messageWindowStartedAt >= RATE_LIMIT_WINDOW_MS) {
            messageWindowStartedAt = now;
            messagesInWindow = 0;
        }

        messagesInWindow++;
        if (messagesInWindow > MAX_MESSAGES_PER_WINDOW) {
            console.log(`[relay] [${connId}] 断开连接: 消息频率超限, roomHash=${roomIdForLog}`);
            ws.close(4008, 'rate limit exceeded');
            return;
        }

        try {
            const parsed = JSON.parse(data.toString());

            console.log(
                `[relay] [${connId}] 收到消息 type=${parsed?.type || 'unknown'} `
                + `deviceId=${maskDeviceId(parsed?.deviceId)}`
            );

            if (!isValidTap(parsed, room)) {
                console.log(`[relay] [${connId}] 忽略无效 tap 消息, roomHash=${roomIdForLog}`);
                return;
            }

            const clients = rooms.get(room);
            if (!clients || clients.size <= 1) {
                console.log(`[relay] [${connId}] roomHash=${roomIdForLog} 没有其他在线设备，丢弃 tap`);
                return;
            }

            let forwarded = 0;
            clients.forEach((client) => {
                if (client !== ws && client.readyState === WebSocket.OPEN) {
                    client.send(JSON.stringify(parsed));
                    forwarded++;
                }
            });

            console.log(`[relay] [${connId}] 转发 tap 给 ${forwarded} 个客户端, roomHash=${roomIdForLog}`);
        } catch (err) {
            console.error(`[relay] [${connId}] 消息解析失败:`, err.message);
        }
    });

    ws.on('close', (code, reason) => {
        console.log(
            `[relay] [${connId}] 断开连接 code=${code} reason=${reason || 'none'} `
            + `roomHash=${roomIdForLog}`
        );
        const clients = rooms.get(room);
        if (clients) {
            clients.delete(ws);
            if (clients.size === 0) {
                rooms.delete(room);
                console.log(`[relay] roomHash=${roomIdForLog} 已无连接，清理`);
            } else {
                console.log(`[relay] roomHash=${roomIdForLog} 剩余连接数: ${clients.size}`);
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
    console.log(
        `[relay] 限制: maxPayload=${MAX_PAYLOAD_BYTES} bytes, `
        + `maxRoomConnections=${MAX_ROOM_CONNECTIONS}, `
        + `maxMessages=${MAX_MESSAGES_PER_WINDOW}/${RATE_LIMIT_WINDOW_MS}ms`
    );
    if (RELAY_TOKEN) {
        console.log('[relay] RELAY_TOKEN 已设置，客户端需传递 token 参数');
    } else {
        console.log('[relay] RELAY_TOKEN 未设置（仅限私用测试）');
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
