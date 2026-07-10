/**
 * 电子木鱼 relay 自动回归脚本。
 *
 * 本脚本不会启动 relay。请先运行 npm start，再运行 npm test。
 * 可通过 WS_URL 指定目标，例如：
 *   WS_URL=ws://localhost:8443 npm test
 */

const WebSocket = require('ws');
const crypto = require('crypto');

const SERVER = process.env.WS_URL || 'ws://localhost:8443';
const TIMEOUT_MS = 5_000;
const roomA = `auto-a-${Date.now()}-${process.pid}`;
const roomB = `auto-b-${Date.now()}-${process.pid}`;
const clients = new Set();

function buildUrl(room) {
    const target = new URL(SERVER);
    if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }
    target.searchParams.set('room', room);
    return target.toString();
}

function shortHash(value) {
    return crypto.createHash('sha256').update(value).digest('hex').substring(0, 8);
}

function createClient(name, room) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(buildUrl(room));
        socket.clientName = name;
        socket.deviceId = `auto-${name}-${process.pid}`;
        clients.add(socket);

        let settled = false;
        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            socket.terminate();
            reject(new Error(`${name} 等待 room_info 超时`));
        }, TIMEOUT_MS);

        const onMessage = (data, isBinary) => {
            if (settled || isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (message.type === 'room_info' && message.room === room) {
                    settled = true;
                    clearTimeout(timer);
                    socket.removeListener('message', onMessage);
                    resolve(socket);
                }
            } catch (_) {
                // 等待下一条可解析消息。
            }
        };

        socket.on('message', onMessage);
        socket.once('close', (code, reasonBuffer) => {
            if (!settled) {
                settled = true;
                clearTimeout(timer);
                reject(new Error(
                    `${name} 在接受前关闭 code=${code} reason=${reasonBuffer?.toString() || 'none'}`
                ));
            }
        });
        socket.once('error', (err) => {
            if (!settled) {
                settled = true;
                clearTimeout(timer);
                reject(err);
            }
        });
    });
}

function expectRejectedClient(name, room, expectedCode) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(buildUrl(room));
        clients.add(socket);
        const timer = setTimeout(() => {
            socket.terminate();
            reject(new Error(`${name} 未在预期时间内被拒绝`));
        }, TIMEOUT_MS);

        socket.once('close', (code) => {
            clearTimeout(timer);
            if (code === expectedCode) {
                resolve();
            } else {
                reject(new Error(`${name} 关闭码=${code}，预期=${expectedCode}`));
            }
        });
        socket.once('error', () => {
            // close 事件负责判断服务端关闭码。
        });
    });
}

function waitForTap(socket, expectedDeviceId, timeoutMs = TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            socket.removeListener('message', handler);
            reject(new Error(`${socket.clientName} 等待 tap 超时`));
        }, timeoutMs);

        const handler = (data, isBinary) => {
            if (isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (message.type === 'tap' && message.deviceId === expectedDeviceId) {
                    clearTimeout(timer);
                    socket.removeListener('message', handler);
                    resolve(message);
                }
            } catch (_) {
                // 忽略无关消息。
            }
        };
        socket.on('message', handler);
    });
}

function expectNoTap(socket, timeoutMs = 500) {
    return new Promise((resolve, reject) => {
        const handler = (data, isBinary) => {
            if (isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (message.type === 'tap') {
                    clearTimeout(timer);
                    socket.removeListener('message', handler);
                    reject(new Error(`${socket.clientName} 收到了不应出现的 tap`));
                }
            } catch (_) {
                // 忽略无关消息。
            }
        };
        const timer = setTimeout(() => {
            socket.removeListener('message', handler);
            resolve();
        }, timeoutMs);
        socket.on('message', handler);
    });
}

function sendJson(socket, message) {
    return new Promise((resolve, reject) => {
        if (socket.readyState !== WebSocket.OPEN) {
            reject(new Error(`${socket.clientName} 当前不可发送`));
            return;
        }
        socket.send(JSON.stringify(message), (err) => {
            if (err) reject(err); else resolve();
        });
    });
}

function sendTap(socket, room, overrides = {}) {
    return sendJson(socket, {
        type: 'tap',
        pairId: room,
        deviceId: socket.deviceId,
        timestamp: Date.now(),
        ...overrides
    });
}

async function run() {
    let passed = 0;
    console.log('[auto-test] 开始 relay 回归检查');
    console.log(`[auto-test] server=${new URL(SERVER).host}`);
    console.log(`[auto-test] roomAHash=${shortHash(roomA)} roomBHash=${shortHash(roomB)}`);

    try {
        const clientA = await createClient('A', roomA);
        const clientB = await createClient('B', roomA);
        const clientC = await createClient('C', roomB);
        passed++;
        console.log('[1/6] 两个同房间客户端和一个隔离房间客户端连接成功');

        const receiveAtB = waitForTap(clientB, clientA.deviceId);
        const noEchoAtA = expectNoTap(clientA);
        const noCrossRoomAtC = expectNoTap(clientC);
        await sendTap(clientA, roomA);
        const messageAtB = await receiveAtB;
        await Promise.all([noEchoAtA, noCrossRoomAtC]);
        if (messageAtB.pairId !== roomA) throw new Error('转发后的 pairId 不匹配');
        passed++;
        console.log('[2/6] A→B 转发成功，发送方无回显，不同房间无串消息');

        const receiveAtA = waitForTap(clientA, clientB.deviceId);
        await sendTap(clientB, roomA);
        await receiveAtA;
        passed++;
        console.log('[3/6] B→A 反向转发成功');

        await expectRejectedClient('third-client', roomA, 4002);
        passed++;
        console.log('[4/6] 同一房间第三个连接被 4002 拒绝');

        const noInvalidTapAtB = expectNoTap(clientB);
        await sendTap(clientA, roomA, { pairId: `${roomA}-wrong` });
        await noInvalidTapAtB;
        passed++;
        console.log('[5/6] pairId 不匹配的 tap 未被转发');

        const noUnknownMessageAtB = expectNoTap(clientB);
        await sendJson(clientA, { type: 'unknown' });
        await noUnknownMessageAtB;
        passed++;
        console.log('[6/6] 未知消息类型未被转发');

        console.log(`[auto-test] 回归场景通过数=${passed}/6`);
    } finally {
        clients.forEach((socket) => {
            try {
                socket.terminate();
            } catch (_) {
                // 忽略清理错误。
            }
        });
    }
}

run().catch((err) => {
    console.error('[auto-test] 失败:', err.message);
    process.exitCode = 1;
});
