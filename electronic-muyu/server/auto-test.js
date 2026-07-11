const WebSocket = require('ws');
const crypto = require('crypto');

const SERVER = process.env.WS_URL || 'ws://localhost:8443';
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_MESSAGES_PER_WINDOW = Number(process.env.MAX_MESSAGES_PER_WINDOW || 60);
const TIMEOUT_MS = 5_000;
const clients = new Set();

function buildUrl(room, token = RELAY_TOKEN) {
    const target = new URL(SERVER);
    target.searchParams.set('room', room);
    if (token) target.searchParams.set('token', token);
    return target.toString();
}

function nextJson(socket, predicate = () => true, timeoutMs = TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('等待消息超时')), timeoutMs);
        const handler = (data, isBinary) => {
            if (isBinary) return;
            try {
                const value = JSON.parse(data.toString());
                if (!predicate(value)) return;
                clearTimeout(timer);
                socket.off('message', handler);
                resolve(value);
            } catch (_) { /* ignore */ }
        };
        socket.on('message', handler);
    });
}

function waitForClose(socket, expectedCode) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`等待关闭 ${expectedCode} 超时`)), TIMEOUT_MS);
        socket.once('close', (code) => {
            clearTimeout(timer);
            if (code === expectedCode) resolve();
            else reject(new Error(`关闭码=${code}，预期=${expectedCode}`));
        });
    });
}

async function connect(room, deviceId) {
    const socket = new WebSocket(buildUrl(room));
    clients.add(socket);
    socket.deviceId = deviceId;
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('连接超时')), TIMEOUT_MS);
        socket.once('open', () => { clearTimeout(timer); resolve(); });
        socket.once('error', reject);
    });
    const statePromise = nextJson(socket, (message) => message.type === 'room_state');
    socket.send(JSON.stringify({ type: 'hello', pairId: room, deviceId, protocolVersion: 2 }));
    return { socket, state: await statePromise };
}

async function expectRejected(room, deviceId, code) {
    const socket = new WebSocket(buildUrl(room));
    clients.add(socket);
    const closed = waitForClose(socket, code);
    socket.once('open', () => {
        socket.send(JSON.stringify({ type: 'hello', pairId: room, deviceId, protocolVersion: 2 }));
    });
    await closed;
}

function sendTap(socket, room) {
    socket.send(JSON.stringify({
        type: 'tap', pairId: room, deviceId: socket.deviceId, timestamp: Date.now()
    }));
}

async function run() {
    let passed = 0;
    const room = `audit-${Date.now()}-${process.pid}`;

    const healthUrl = new URL('/health', SERVER.replace(/^ws/, 'http'));
    const health = await fetch(healthUrl);
    if (!health.ok || (await health.json()).ok !== true) throw new Error('健康检查失败');
    console.log(`[${++passed}] health`);

    const a1 = await connect(room, 'device-a');
    if (a1.state.peerOnline !== false) throw new Error('首台设备应显示对方离线');
    const stateAOnline = nextJson(a1.socket, (m) => m.type === 'room_state' && m.peerOnline === true);
    const b = await connect(room, 'device-b');
    await stateAOnline;
    if (b.state.peerOnline !== true) throw new Error('第二台设备应显示对方在线');
    console.log(`[${++passed}] peer online broadcast`);

    const tapAtB = nextJson(b.socket, (m) => m.type === 'tap' && m.deviceId === 'device-a');
    sendTap(a1.socket, room);
    await tapAtB;
    console.log(`[${++passed}] tap forwarding`);

    const oldClosed = waitForClose(a1.socket, 4004);
    const a2 = await connect(room, 'device-a');
    await oldClosed;
    if (a2.state.peerOnline !== true) throw new Error('替换连接后应保持对方在线');
    console.log(`[${++passed}] same device replaces stale connection`);

    await expectRejected(room, 'device-c', 4002);
    console.log(`[${++passed}] true third device rejected`);

    const offlineAtA = nextJson(a2.socket, (m) => m.type === 'room_state' && m.peerOnline === false);
    b.socket.close(1000, 'leave');
    await offlineAtA;
    console.log(`[${++passed}] peer offline broadcast`);

    const invalid = new WebSocket(buildUrl(`${room}-invalid`));
    clients.add(invalid);
    const invalidClosed = waitForClose(invalid, 4004);
    invalid.once('open', () => invalid.send(JSON.stringify({ type: 'tap' })));
    await invalidClosed;
    console.log(`[${++passed}] hello required`);

    const rate = await connect(`${room}-rate`, 'device-rate');
    const rateClosed = waitForClose(rate.socket, 4008);
    for (let index = 0; index <= MAX_MESSAGES_PER_WINDOW; index++) {
        rate.socket.send(JSON.stringify({ type: 'unknown', index }));
    }
    await rateClosed;
    console.log(`[${++passed}] rate limit`);

    console.log(`[auto-test] 通过=${passed}`);
}

run().catch((error) => {
    console.error('[auto-test] 失败:', error.message);
    process.exitCode = 1;
}).finally(() => {
    clients.forEach((socket) => { try { socket.terminate(); } catch (_) { /* no-op */ } });
});
