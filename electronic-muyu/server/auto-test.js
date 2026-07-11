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
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_MESSAGES_PER_WINDOW = Number(process.env.MAX_MESSAGES_PER_WINDOW || 60);
const MAX_TOTAL_CONNECTIONS = Number(process.env.MAX_TOTAL_CONNECTIONS || 100);
const HEARTBEAT_INTERVAL_MS = Number(process.env.HEARTBEAT_INTERVAL_MS || 30_000);
const TIMEOUT_MS = 7_000;
const PROTOCOL_VERSION = 2;
const roomA = `auto-a-${Date.now()}-${process.pid}`;
const roomB = `auto-b-${Date.now()}-${process.pid}`;
const clients = new Set();

function buildUrl(room, token = RELAY_TOKEN) {
    const target = new URL(SERVER);
    if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
        throw new Error('WS_URL 必须使用 ws:// 或 wss://');
    }
    target.searchParams.set('room', room);
    if (token) target.searchParams.set('token', token);
    return target.toString();
}

function buildHttpUrl(pathname) {
    const target = new URL(SERVER);
    target.protocol = target.protocol === 'wss:' ? 'https:' : 'http:';
    target.pathname = pathname;
    target.search = '';
    target.hash = '';
    return target.toString();
}

function shortHash(value) {
    return crypto.createHash('sha256').update(value).digest('hex').substring(0, 8);
}

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function waitForJson(socket, predicate, description, timeoutMs = TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            socket.removeListener('message', handler);
            reject(new Error(`${socket.clientName} 等待 ${description} 超时`));
        }, timeoutMs);

        const handler = (data, isBinary) => {
            if (isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (!predicate(message)) return;
                clearTimeout(timer);
                socket.removeListener('message', handler);
                resolve(message);
            } catch (_) {
                // 等待下一条可解析消息。
            }
        };
        socket.on('message', handler);
    });
}

function waitForRoomState(socket, predicate = () => true, timeoutMs = TIMEOUT_MS) {
    return waitForJson(
        socket,
        (message) => message.type === 'room_state' && predicate(message),
        'room_state',
        timeoutMs
    );
}

function waitForTap(socket, expectedDeviceId, timeoutMs = TIMEOUT_MS) {
    return waitForJson(
        socket,
        (message) => message.type === 'tap' && message.deviceId === expectedDeviceId,
        'tap',
        timeoutMs
    );
}

function expectNoTap(socket, timeoutMs = 500) {
    return new Promise((resolve, reject) => {
        const handler = (data, isBinary) => {
            if (isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (message.type !== 'tap') return;
                clearTimeout(timer);
                socket.removeListener('message', handler);
                reject(new Error(`${socket.clientName} 收到了不应出现的 tap`));
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

function waitForClose(socket, expectedCode, timeoutMs = TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            socket.terminate();
            reject(new Error(`${socket.clientName || 'client'} 未以 ${expectedCode} 关闭`));
        }, timeoutMs);

        socket.once('close', (code, reasonBuffer) => {
            clearTimeout(timer);
            if (code === expectedCode) {
                resolve(reasonBuffer?.toString() || '');
            } else {
                reject(new Error(
                    `${socket.clientName || 'client'} 关闭码=${code}，预期=${expectedCode}`
                ));
            }
        });
    });
}

function createClient(name, room, options = {}, deviceIdOverride = null) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(buildUrl(room), options);
        socket.clientName = name;
        socket.deviceId = deviceIdOverride || `auto-${name}-${process.pid}`;
        clients.add(socket);

        let settled = false;
        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            socket.terminate();
            reject(new Error(`${name} 等待 hello 握手超时`));
        }, TIMEOUT_MS);

        const onMessage = (data, isBinary) => {
            if (settled || isBinary) return;
            try {
                const message = JSON.parse(data.toString());
                if (message.type !== 'room_state' || message.pairId !== room) return;
                settled = true;
                clearTimeout(timer);
                socket.removeListener('message', onMessage);
                socket.initialRoomState = message;
                resolve(socket);
            } catch (_) {
                // 等待下一条可解析消息。
            }
        };

        socket.on('message', onMessage);
        socket.once('open', () => {
            socket.send(JSON.stringify({
                type: 'hello',
                pairId: room,
                deviceId: socket.deviceId,
                protocolVersion: PROTOCOL_VERSION
            }));
        });
        socket.once('close', (code, reasonBuffer) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            reject(new Error(
                `${name} 在握手前关闭 code=${code} reason=${reasonBuffer?.toString() || 'none'}`
            ));
        });
        socket.once('error', (error) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            reject(error);
        });
    });
}

function expectRejectedClient(
    name,
    room,
    expectedCode,
    token = RELAY_TOKEN,
    deviceId = `auto-${name}-${process.pid}`,
    sendHello = true
) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(buildUrl(room, token));
        socket.clientName = name;
        clients.add(socket);
        const timer = setTimeout(() => {
            socket.terminate();
            reject(new Error(`${name} 未在预期时间内被拒绝`));
        }, TIMEOUT_MS);

        if (sendHello) {
            socket.once('open', () => {
                socket.send(JSON.stringify({
                    type: 'hello',
                    pairId: room,
                    deviceId,
                    protocolVersion: PROTOCOL_VERSION
                }));
            });
        }

        socket.once('close', (code) => {
            clearTimeout(timer);
            if (code === expectedCode) resolve();
            else reject(new Error(`${name} 关闭码=${code}，预期=${expectedCode}`));
        });
        socket.once('error', () => {
            // close 事件负责判断服务端关闭码。
        });
    });
}

function sendJson(socket, message) {
    return new Promise((resolve, reject) => {
        if (socket.readyState !== WebSocket.OPEN) {
            reject(new Error(`${socket.clientName} 当前不可发送`));
            return;
        }
        socket.send(JSON.stringify(message), (error) => {
            if (error) reject(error); else resolve();
        });
    });
}

function sendRaw(socket, payload, options) {
    return new Promise((resolve, reject) => {
        if (socket.readyState !== WebSocket.OPEN) {
            reject(new Error(`${socket.clientName} 当前不可发送`));
            return;
        }
        socket.send(payload, options, (error) => {
            if (error) reject(error); else resolve();
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
    if (
        !Number.isInteger(MAX_MESSAGES_PER_WINDOW) ||
        MAX_MESSAGES_PER_WINDOW < 1 ||
        !Number.isInteger(MAX_TOTAL_CONNECTIONS) ||
        MAX_TOTAL_CONNECTIONS < 3 ||
        MAX_TOTAL_CONNECTIONS > 1_000 ||
        !Number.isInteger(HEARTBEAT_INTERVAL_MS) ||
        HEARTBEAT_INTERVAL_MS < 100
    ) {
        throw new Error('自动回归要求合法限流值，且 MAX_TOTAL_CONNECTIONS 必须为 3-1000');
    }

    let passed = 0;
    console.log('[auto-test] 开始 relay 回归检查');
    console.log(`[auto-test] server=${new URL(SERVER).host}`);
    console.log(`[auto-test] roomAHash=${shortHash(roomA)} roomBHash=${shortHash(roomB)}`);

    try {
        const healthResponse = await fetch(buildHttpUrl('/health'));
        const healthBody = await healthResponse.json();
        if (
            healthResponse.status !== 200 ||
            healthBody.ok !== true ||
            healthBody.service !== 'electronic-muyu-relay'
        ) {
            throw new Error('健康检查响应不符合预期');
        }
        const ordinaryHttpResponse = await fetch(buildHttpUrl('/'));
        if (ordinaryHttpResponse.status !== 426) {
            throw new Error(`普通 HTTP 状态=${ordinaryHttpResponse.status}，预期=426`);
        }
        console.log(`[${++passed}] /health 返回 200，普通 HTTP 路径返回 426`);

        const capacityClients = [];
        for (let index = 0; index < MAX_TOTAL_CONNECTIONS; index++) {
            capacityClients.push(
                await createClient(`capacity-${index}`, `capacity-${roomA}-${index}`)
            );
        }
        await expectRejectedClient(
            'over-capacity',
            `${roomB}-capacity`,
            4003,
            RELAY_TOKEN,
            'over-capacity-device',
            false
        );
        capacityClients.forEach((socket) => socket.terminate());
        await delay(200);
        console.log(`[${++passed}] 总连接达到 ${MAX_TOTAL_CONNECTIONS} 后新连接被 4003 拒绝`);

        if (HEARTBEAT_INTERVAL_MS <= 2_000) {
            const unresponsiveClient = await createClient(
                'no-pong',
                `${roomB}-no-pong`,
                { autoPong: false }
            );
            await waitForClose(unresponsiveClient, 1006);
            console.log(`[${++passed}] 不回应 ping 的连接被心跳终止并释放`);
        }

        let clientA = await createClient('A', roomA);
        if (clientA.initialRoomState.peerOnline !== false) {
            throw new Error('房间内第一台设备应显示对方离线');
        }
        const aSeesOnline = waitForRoomState(clientA, (state) => state.peerOnline === true);
        const clientB = await createClient('B', roomA);
        await aSeesOnline;
        if (clientB.initialRoomState.peerOnline !== true) {
            throw new Error('第二台设备加入后应显示对方在线');
        }
        const clientC = await createClient('C', roomB);
        console.log(`[${++passed}] 同房间双方收到在线状态，不同房间独立连接`);

        const receiveAtB = waitForTap(clientB, clientA.deviceId);
        const noEchoAtA = expectNoTap(clientA);
        const noCrossRoomAtC = expectNoTap(clientC);
        await sendTap(clientA, roomA);
        const messageAtB = await receiveAtB;
        await Promise.all([noEchoAtA, noCrossRoomAtC]);
        if (messageAtB.pairId !== roomA) throw new Error('转发后的 pairId 不匹配');
        console.log(`[${++passed}] A→B 转发成功，发送方无回显，不同房间无串消息`);

        const receiveAtA = waitForTap(clientA, clientB.deviceId);
        await sendTap(clientB, roomA);
        await receiveAtA;
        console.log(`[${++passed}] B→A 反向转发成功`);

        const oldClientA = clientA;
        const oldClientClosed = waitForClose(oldClientA, 4004);
        clientA = await createClient('A-replacement', roomA, {}, oldClientA.deviceId);
        await oldClientClosed;
        if (clientA.initialRoomState.peerOnline !== true) {
            throw new Error('同设备替换旧连接后应保持对方在线');
        }
        const receiveAfterReplacement = waitForTap(clientA, clientB.deviceId);
        await sendTap(clientB, roomA);
        await receiveAfterReplacement;
        console.log(`[${++passed}] 同 deviceId 新连接替换旧连接并继续转发`);

        await expectRejectedClient('third-client', roomA, 4002);
        console.log(`[${++passed}] 真正第三台设备被 4002 拒绝`);

        const noInvalidTapAtB = expectNoTap(clientB);
        await sendTap(clientA, roomA, { pairId: `${roomA}-wrong` });
        await noInvalidTapAtB;
        const noChangedDeviceAtB = expectNoTap(clientB);
        await sendTap(clientA, roomA, { deviceId: 'changed-device' });
        await noChangedDeviceAtB;
        console.log(`[${++passed}] pairId 或 deviceId 不匹配的 tap 未被转发`);

        const noUnknownMessageAtB = expectNoTap(clientB);
        await sendJson(clientA, { type: 'unknown' });
        await noUnknownMessageAtB;
        const noMalformedMessageAtB = expectNoTap(clientB);
        await sendRaw(clientA, '{not-json');
        await noMalformedMessageAtB;
        console.log(`[${++passed}] 未知类型和异常 JSON 未被转发，relay 继续运行`);

        await expectRejectedClient('invalid-room', '', 4000, RELAY_TOKEN, 'invalid-room', false);
        console.log(`[${++passed}] 空 room 被 4000 拒绝`);

        if (RELAY_TOKEN) {
            await expectRejectedClient(
                'invalid-token',
                `${roomB}-auth`,
                4001,
                'wrong-token',
                'invalid-token-device',
                false
            );
            console.log(`[${++passed}] 错误 token 被 4001 拒绝`);
        }

        const helloRequired = new WebSocket(buildUrl(`${roomB}-hello-required`));
        helloRequired.clientName = 'hello-required';
        clients.add(helloRequired);
        const helloRequiredClosed = waitForClose(helloRequired, 4004);
        helloRequired.once('open', () => {
            helloRequired.send(JSON.stringify({ type: 'tap' }));
        });
        await helloRequiredClosed;
        console.log(`[${++passed}] 未完成 hello 握手的消息被 4004 拒绝`);

        const binaryClient = await createClient('binary', `${roomB}-binary`);
        const binaryClosed = waitForClose(binaryClient, 1003);
        await sendRaw(binaryClient, Buffer.from([0x01, 0x02]), { binary: true });
        await binaryClosed;
        console.log(`[${++passed}] 二进制消息被 1003 关闭`);

        const oversizedClient = await createClient('oversized', `${roomB}-oversized`);
        const oversizedClosed = waitForClose(oversizedClient, 1009);
        await sendRaw(oversizedClient, 'x'.repeat(4 * 1024 + 1));
        await oversizedClosed;
        console.log(`[${++passed}] 超过 4 KB 的消息被 1009 关闭`);

        const aSeesOffline = waitForRoomState(clientA, (state) => state.peerOnline === false);
        clientB.terminate();
        await aSeesOffline;
        const aSeesReplacementOnline = waitForRoomState(
            clientA,
            (state) => state.peerOnline === true
        );
        const replacementB = await createClient('replacement-B', roomA);
        await aSeesReplacementOnline;
        const receiveAtReplacementB = waitForTap(replacementB, clientA.deviceId);
        await sendTap(clientA, roomA);
        await receiveAtReplacementB;
        console.log(`[${++passed}] 离线状态广播、名额释放和重新加入均正常`);

        const rateClient = await createClient('rate-limit', `${roomB}-rate`);
        const rateClosed = waitForClose(rateClient, 4008);
        for (let index = 0; index <= MAX_MESSAGES_PER_WINDOW; index++) {
            if (rateClient.readyState !== WebSocket.OPEN) break;
            await sendJson(rateClient, { type: 'unknown', index });
        }
        await rateClosed;
        console.log(`[${++passed}] 超过 ${MAX_MESSAGES_PER_WINDOW}/10 秒后以 4008 关闭`);

        console.log(`[auto-test] 回归场景通过数=${passed}`);
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

run().catch((error) => {
    console.error('[auto-test] 失败:', error.message);
    process.exitCode = 1;
});
