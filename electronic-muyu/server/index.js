/**
 * 电子木鱼 — WebSocket 中继服务
 *
 * 支持本地开发和短期公网测试部署。
 * 连接建立后必须先发送 hello；房间按 deviceId 计数，同设备新连接会替换旧连接。
 */

const http = require('http');
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = parseIntegerInRange(process.env.PORT, 8443, 1, 65535);
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_PAYLOAD_BYTES = 4 * 1024;
const MAX_ROOM_CONNECTIONS = 2;
const MAX_TOTAL_CONNECTIONS = parseIntegerInRange(process.env.MAX_TOTAL_CONNECTIONS, 100, 1, 10_000);
const RATE_LIMIT_WINDOW_MS = 10_000;
const MAX_MESSAGES_PER_WINDOW = parseIntegerInRange(process.env.MAX_MESSAGES_PER_WINDOW, 60, 1, 1_000);
const HEARTBEAT_INTERVAL_MS = parseIntegerInRange(process.env.HEARTBEAT_INTERVAL_MS, 30_000, 100, 300_000);
const HELLO_TIMEOUT_MS = 5_000;

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
    return String(value ?? 'none').replace(/[\u0000-\u001F\u007F]/g, '?').slice(0, maxLength);
}

function tokenMatches(actualToken) {
    if (!RELAY_TOKEN) return true;
    const expected = Buffer.from(RELAY_TOKEN);
    const actual = Buffer.from(actualToken || '');
    return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function isValidIdentifier(value, maxLength) {
    return typeof value === 'string'
        && value.length > 0
        && value.length <= maxLength
        && !/[\u0000-\u001F\u007F]/.test(value);
}

function isValidRoom(room) {
    return isValidIdentifier(room, 64);
}

function isValidHello(message, room) {
    return message !== null
        && typeof message === 'object'
        && !Array.isArray(message)
        && message.type === 'hello'
        && message.pairId === room
        && isValidIdentifier(message.deviceId, 128)
        && message.protocolVersion === 2;
}

function isValidTap(message, room, deviceId) {
    return message !== null
        && typeof message === 'object'
        && !Array.isArray(message)
        && message.type === 'tap'
        && message.pairId === room
        && message.deviceId === deviceId
        && Number.isSafeInteger(message.timestamp)
        && message.timestamp > 0;
}

function roomClients(room) {
    let clients = rooms.get(room);
    if (!clients) {
        clients = new Map();
        rooms.set(room, clients);
    }
    return clients;
}

function sendJson(ws, payload) {
    if (ws.readyState !== WebSocket.OPEN) return false;
    try {
        ws.send(JSON.stringify(payload));
        return true;
    } catch (_) {
        ws.terminate();
        return false;
    }
}

function broadcastRoomState(room) {
    const clients = rooms.get(room);
    if (!clients || clients.size === 0) return;
    const now = Date.now();
    const onlineDeviceIds = [...clients.entries()]
        .filter(([, socket]) => socket.readyState === WebSocket.OPEN)
        .map(([deviceId]) => deviceId);

    for (const [deviceId, socket] of clients.entries()) {
        if (socket.readyState !== WebSocket.OPEN) continue;
        sendJson(socket, {
            type: 'room_state',
            pairId: room,
            peerOnline: onlineDeviceIds.some((candidate) => candidate !== deviceId),
            connections: onlineDeviceIds.length,
            timestamp: now
        });
    }
}

function removeClientFromRoom(room, ws, roomHash) {
    clearTimeout(ws.helloTimer);
    const clients = rooms.get(room);
    if (!clients || !ws.deviceId || clients.get(ws.deviceId) !== ws) return;
    clients.delete(ws.deviceId);
    if (clients.size === 0) {
        rooms.delete(room);
        console.log(`[relay] roomHash=${roomHash} 已无连接，清理`);
    } else {
        console.log(`[relay] roomHash=${roomHash} 剩余设备数: ${clients.size}`);
        broadcastRoomState(room);
    }
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
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
});

const wss = new WebSocket.Server({ server: httpServer, maxPayload: MAX_PAYLOAD_BYTES });

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
        ws.close(4003, 'relay is full');
        return;
    }
    if (!isValidRoom(room)) {
        ws.close(4000, 'valid room parameter required');
        return;
    }
    const roomHash = shortHash(room);
    if (!tokenMatches(token)) {
        console.log(`[relay] [${connId}] token 无效 networkHash=${networkHash} roomHash=${roomHash}`);
        ws.close(4001, 'invalid token');
        return;
    }

    ws.isAlive = true;
    ws.deviceId = null;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.helloTimer = setTimeout(() => {
        if (!ws.deviceId && ws.readyState === WebSocket.OPEN) ws.close(4004, 'hello timeout');
    }, HELLO_TIMEOUT_MS);
    ws.helloTimer.unref();

    sendJson(ws, { type: 'hello_required', protocolVersion: 2, timestamp: Date.now() });

    ws.on('close', (code, reasonBuffer) => {
        console.log(
            `[relay] [${connId}] 断开 code=${code} reason=${safeLogValue(reasonBuffer?.toString() || 'none')} `
            + `roomHash=${roomHash}`
        );
        removeClientFromRoom(room, ws, roomHash);
    });

    ws.on('error', (err) => {
        console.error(`[relay] [${connId}] WebSocket 错误:`, safeLogValue(err.message));
    });

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
            ws.close(4008, 'rate limit exceeded');
            return;
        }

        let parsed;
        try {
            parsed = JSON.parse(data.toString());
        } catch (_) {
            return;
        }

        if (!ws.deviceId) {
            if (!isValidHello(parsed, room)) {
                ws.close(4004, 'hello required');
                return;
            }

            const clients = roomClients(room);
            const existing = clients.get(parsed.deviceId);
            if (!existing && clients.size >= MAX_ROOM_CONNECTIONS) {
                ws.close(4002, 'room is full');
                return;
            }

            ws.deviceId = parsed.deviceId;
            clearTimeout(ws.helloTimer);
            clients.set(ws.deviceId, ws);
            if (existing && existing !== ws) {
                existing.close(4004, 'replaced by newer connection');
            }
            console.log(
                `[relay] [${connId}] hello deviceId=${maskDeviceId(ws.deviceId)} `
                + `roomHash=${roomHash} devices=${clients.size}`
            );
            broadcastRoomState(room);
            return;
        }

        if (!isValidTap(parsed, room, ws.deviceId)) return;
        const clients = rooms.get(room);
        if (!clients || clients.get(ws.deviceId) !== ws) {
            console.log(
                `[relay] [${connId}] 忽略已被替换连接的 tap deviceId=${maskDeviceId(ws.deviceId)} `
                + `roomHash=${roomHash}`
            );
            return;
        }
        if (clients.size <= 1) return;

        const payload = JSON.stringify({
            type: 'tap',
            pairId: room,
            deviceId: ws.deviceId,
            timestamp: parsed.timestamp
        });
        for (const [peerDeviceId, client] of clients.entries()) {
            if (peerDeviceId === ws.deviceId || client.readyState !== WebSocket.OPEN) continue;
            try {
                client.send(payload);
            } catch (_) {
                client.terminate();
            }
        }
    });
});

wss.on('error', (err) => console.error('[relay] 服务器错误:', safeLogValue(err.message)));

const heartbeatTimer = setInterval(() => {
    wss.clients.forEach((client) => {
        if (client.readyState !== WebSocket.OPEN) return;
        if (client.isAlive === false) {
            client.terminate();
            return;
        }
        client.isAlive = false;
        try { client.ping(); } catch (_) { client.terminate(); }
    });
}, HEARTBEAT_INTERVAL_MS);
heartbeatTimer.unref();

httpServer.listen(PORT, () => {
    console.log(`[relay] 电子木鱼中继已启动 → ws://0.0.0.0:${PORT}`);
    console.log(`[relay] 健康检查 → http://localhost:${PORT}/health`);
});

function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(heartbeatTimer);
    console.log(`\n[relay] 收到 ${signal}，关闭服务...`);
    wss.clients.forEach((client) => {
        try { client.close(1001, 'server shutdown'); } catch (_) { client.terminate(); }
    });

    let finished = false;
    let forceTimer;
    const finish = (forceTerminate = false) => {
        if (finished) return;
        finished = true;
        if (forceTimer) clearTimeout(forceTimer);

        if (forceTerminate) {
            wss.clients.forEach((client) => {
                try { client.terminate(); } catch (_) { /* already closed */ }
            });
        }

        httpServer.close((err) => {
            if (err && err.code !== 'ERR_SERVER_NOT_RUNNING') {
                console.error('[relay] HTTP 关闭错误:', safeLogValue(err.message));
                process.exit(1);
                return;
            }
            console.log('[relay] 服务已关闭');
            process.exit(0);
        });
    };

    forceTimer = setTimeout(() => finish(true), 5_000);
    forceTimer.unref();
    wss.close(() => finish(false));
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
