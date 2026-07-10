/**
 * 电子木鱼 — WebSocket 中继服务
 *
 * 支持本地开发和短期公网测试部署。
 * 不做账号认证（仅支持可选 RELAY_TOKEN）、不做持久化、不做离线缓存。
 */

const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = parseIntegerInRange(process.env.PORT, 8443, 1, 65535);
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_PAYLOAD_BYTES = 4 * 1024;
const MAX_ROOM_CONNECTIONS = 2;
const MAX_TOTAL_CONNECTIONS = 100;
const RATE_LIMIT_WINDOW_MS = 10_000;
const MAX_MESSAGES_PER_WINDOW = parseIntegerInRange(
    process.env.MAX_MESSAGES_PER_WINDOW,
    60,
    1,
    1_000
);
const HEARTBEAT_INTERVAL_MS = 30_000;

const rooms = new Map();
let shuttingDown = false;

function parseIntegerInRange(value, fallback, min, max) {
    if (value === undefined || value === '') return fallback;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
        throw new Error(`配置值必须是 ${min}-${max} 范围内的整数`);
    }
    return parsed;
}

function maskDeviceId(deviceId) {
    if (!deviceId || deviceId.length < 8) return '***';
    return deviceId.substring(0, 4) + '***' + deviceId.substring(deviceId.length - 4);
}

function shortHash(value) {
    if (!value) return 'none';
    return crypto.createHash('sha256').update(value).digest('hex').substring(0, 8);
}

function safeLogValue(value, maxLength = 80) {
    return String(value ?? 'none')
        .replace(/[\u0000-\u001F\u007F]/g, '?')
        .slice(0, maxLength);
}

function tokenMatches(actualToken) {
    if (!RELAY_TOKEN) return true;
    const expected = Buffer.from(RELAY_TOKEN);
    const actual = Buffer.from(actualToken || '');
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
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
        && !Array.isArray(message)
        && message.type === 'tap'
        && typeof message.pairId === 'string'
        && message.pairId === room
        && typeof message.deviceId === 'string'
        && message.deviceId.length > 0
        && message.deviceId.length <= 128
        && !/[\u0000-\u001F\u007F]/.test(message.deviceId)
        && Number.isSafeInteger(message.timestamp)
        && message.timestamp > 0;
}

const httpServer = http.createServer((req, res) => {
    let parsedUrl;
    try {
        parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    } catch (_) {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Bad Request\n');
        return;
    }

    if (parsedUrl.pathname === '/health' && req.method === 'GET') {
        res.writeHead(200, {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store'
        });
        res.end(JSON.stringify({ ok: true, service: 'electronic-muyu-relay' }));
        return;
    }

    res.writeHead(426, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('This is a WebSocket server. Use ws:// or wss:// to connect.\n');
});

httpServer.on('clientError', (err, socket) => {
    console.error('[relay] HTTP client error:', safeLogValue(err.message));
    if (socket.writable) {
        socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    }
});

const wss = new WebSocket.Server({
    server: httpServer,
    maxPayload: MAX_PAYLOAD_BYTES
});

wss.on('connection', (ws, req) => {
    let parsedUrl;
    try {
        parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    } catch (_) {
        ws.close(4000, 'valid room parameter required');
        return;
    }

    const room = parsedUrl.searchParams.get('room');
    const token = parsedUrl.searchParams.get('token') || '';
    const networkHash = shortHash(req.socket.remoteAddress || 'unknown');
    const connId = crypto.randomUUID().substring(0, 8);

    if (shuttingDown) {
        ws.close(1012, 'server restarting');
        return;
    }

    if (wss.clients.size > MAX_TOTAL_CONNECTIONS) {
        console.log(`[relay] [${connId}] 拒绝连接: 总连接数已满, networkHash=${networkHash}`);
        ws.close(4003, 'relay is full');
        return;
    }

    if (!isValidRoom(room)) {
        console.log(`[relay] [${connId}] 拒绝连接: room 参数无效, networkHash=${networkHash}`);
        ws.close(4000, 'valid room parameter required');
        return;
    }

    const roomHash = shortHash(room);

    if (!tokenMatches(token)) {
        console.log(
            `[relay] [${connId}] 拒绝连接: token 无效, `
            + `networkHash=${networkHash}, roomHash=${roomHash}`
        );
        ws.close(4001, 'invalid token');
        return;
    }

    const existingClients = rooms.get(room);
    if (existingClients && existingClients.size >= MAX_ROOM_CONNECTIONS) {
        console.log(
            `[relay] [${connId}] 拒绝连接: room 已满, `
            + `networkHash=${networkHash}, roomHash=${roomHash}`
        );
        ws.close(4002, 'room is full');
        return;
    }

    ws.isAlive = true;
    ws.on('pong', () => {
        ws.isAlive = true;
    });

    if (!rooms.has(room)) {
        rooms.set(room, new Set());
    }
    rooms.get(room).add(ws);

    const roomSize = rooms.get(room).size;
    console.log(
        `[relay] [${connId}] 新连接 roomHash=${roomHash} `
        + `networkHash=${networkHash} connections=${roomSize}`
    );

    try {
        ws.send(JSON.stringify({
            type: 'room_info',
            room,
            connections: roomSize,
            timestamp: Date.now()
        }));
    } catch (err) {
        console.error(`[relay] [${connId}] room_info 发送失败:`, safeLogValue(err.message));
        ws.terminate();
        return;
    }

    let messageWindowStartedAt = Date.now();
    let messagesInWindow = 0;

    ws.on('message', (data, isBinary) => {
        if (isBinary) {
            ws.close(1003, 'text messages only');
            return;
        }

        const now = Date.now();
        if (now - messageWindowStartedAt >= RATE_LIMIT_WINDOW_MS) {
            messageWindowStartedAt = now;
            messagesInWindow = 0;
        }

        messagesInWindow++;
        if (messagesInWindow > MAX_MESSAGES_PER_WINDOW) {
            console.log(`[relay] [${connId}] 消息频率超限 roomHash=${roomHash}`);
            ws.close(4008, 'rate limit exceeded');
            return;
        }

        try {
            const parsed = JSON.parse(data.toString());
            console.log(
                `[relay] [${connId}] 收到消息 type=${safeLogValue(parsed?.type, 32)} `
                + `deviceId=${safeLogValue(maskDeviceId(parsed?.deviceId), 32)}`
            );

            if (!isValidTap(parsed, room)) {
                console.log(`[relay] [${connId}] 忽略无效 tap roomHash=${roomHash}`);
                return;
            }

            const clients = rooms.get(room);
            if (!clients || clients.size <= 1) {
                console.log(`[relay] [${connId}] 对方离线，丢弃 tap roomHash=${roomHash}`);
                return;
            }

            const payload = JSON.stringify(parsed);
            let forwarded = 0;
            clients.forEach((client) => {
                if (client !== ws && client.readyState === WebSocket.OPEN) {
                    try {
                        client.send(payload);
                        forwarded++;
                    } catch (err) {
                        console.error(
                            `[relay] [${connId}] 转发失败:`,
                            safeLogValue(err.message)
                        );
                    }
                }
            });

            console.log(
                `[relay] [${connId}] 转发 tap 给 ${forwarded} 个客户端 `
                + `roomHash=${roomHash}`
            );
        } catch (err) {
            console.error(
                `[relay] [${connId}] 消息解析失败:`,
                safeLogValue(err.message)
            );
        }
    });

    ws.on('close', (code, reasonBuffer) => {
        const reason = safeLogValue(reasonBuffer?.toString() || 'none');
        console.log(
            `[relay] [${connId}] 断开 code=${code} reason=${reason} `
            + `roomHash=${roomHash}`
        );
        const clients = rooms.get(room);
        if (clients) {
            clients.delete(ws);
            if (clients.size === 0) {
                rooms.delete(room);
                console.log(`[relay] roomHash=${roomHash} 已无连接，清理`);
            } else {
                console.log(`[relay] roomHash=${roomHash} 剩余连接数: ${clients.size}`);
            }
        }
    });

    ws.on('error', (err) => {
        console.error(
            `[relay] [${connId}] WebSocket 错误:`,
            safeLogValue(err.message)
        );
    });
});

wss.on('error', (err) => {
    console.error('[relay] 服务器错误:', safeLogValue(err.message));
});

const heartbeatTimer = setInterval(() => {
    wss.clients.forEach((client) => {
        if (client.readyState !== WebSocket.OPEN) return;
        if (client.isAlive === false) {
            client.terminate();
            return;
        }
        client.isAlive = false;
        try {
            client.ping();
        } catch (_) {
            client.terminate();
        }
    });
}, HEARTBEAT_INTERVAL_MS);
heartbeatTimer.unref();

httpServer.listen(PORT, () => {
    console.log(`[relay] 电子木鱼中继已启动 → ws://0.0.0.0:${PORT}`);
    console.log(`[relay] 健康检查 → http://localhost:${PORT}/health`);
    console.log(
        `[relay] 限制: maxPayload=${MAX_PAYLOAD_BYTES} bytes, `
        + `maxRoomConnections=${MAX_ROOM_CONNECTIONS}, `
        + `maxTotalConnections=${MAX_TOTAL_CONNECTIONS}, `
        + `maxMessages=${MAX_MESSAGES_PER_WINDOW}/${RATE_LIMIT_WINDOW_MS}ms`
    );
    console.log(
        RELAY_TOKEN
            ? '[relay] RELAY_TOKEN 已设置，客户端需传递 token 参数'
            : '[relay] RELAY_TOKEN 未设置（仅限私用测试）'
    );
});

function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(heartbeatTimer);
    console.log(`\n[relay] 收到 ${signal}，关闭服务...`);

    wss.clients.forEach((client) => {
        try {
            client.close(1001, 'server shutdown');
        } catch (_) {
            client.terminate();
        }
    });

    let finished = false;
    let forceTimer;
    const finish = () => {
        if (finished) return;
        finished = true;
        if (forceTimer) clearTimeout(forceTimer);
        wss.clients.forEach((client) => client.terminate());

        const exitTimer = setTimeout(() => process.exit(0), 1_000);
        exitTimer.unref();
        httpServer.close((err) => {
            if (err && err.code !== 'ERR_SERVER_NOT_RUNNING') {
                console.error('[relay] HTTP 关闭错误:', safeLogValue(err.message));
            }
            console.log('[relay] 服务已关闭');
            process.exit(0);
        });
    };

    forceTimer = setTimeout(finish, 2_000);
    forceTimer.unref();
    wss.close(finish);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
