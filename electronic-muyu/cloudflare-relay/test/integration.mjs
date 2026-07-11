import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const host = "127.0.0.1";
const port = 18787;
const baseHttpUrl = `http://${host}:${port}`;
const baseWebSocketUrl = `ws://${host}:${port}`;
const wranglerEntry = new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url);

let output = "";
const worker = spawn(
  process.execPath,
  [wranglerEntry.pathname, "dev", "--local", "--ip", host, "--port", String(port)],
  {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, NO_COLOR: "1" },
    stdio: ["ignore", "pipe", "pipe"],
    detached: process.platform !== "win32"
  }
);
worker.stdout.on("data", (chunk) => { output += chunk.toString(); });
worker.stderr.on("data", (chunk) => { output += chunk.toString(); });

function nextJson(socket, predicate = () => true, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.removeEventListener("message", handler);
      reject(new Error("WebSocket message timeout"));
    }, timeoutMs);
    const handler = (event) => {
      let value;
      try {
        value = JSON.parse(event.data);
      } catch {
        return;
      }
      if (!predicate(value)) return;
      clearTimeout(timer);
      socket.removeEventListener("message", handler);
      resolve(value);
    };
    socket.addEventListener("message", handler);
  });
}

function expectNoTap(socket, timeoutMs = 400) {
  return new Promise((resolve, reject) => {
    const handler = (event) => {
      try {
        const value = JSON.parse(event.data);
        if (value.type !== "tap") return;
        clearTimeout(timer);
        socket.removeEventListener("message", handler);
        reject(new Error("Unexpected cross-room tap"));
      } catch {
        // Ignore non-JSON messages.
      }
    };
    const timer = setTimeout(() => {
      socket.removeEventListener("message", handler);
      resolve();
    }, timeoutMs);
    socket.addEventListener("message", handler);
  });
}

function waitForClose(socket, code, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket close timeout")), timeoutMs);
    socket.addEventListener("close", (event) => {
      clearTimeout(timer);
      if (event.code === code) resolve(event.reason);
      else reject(new Error(`close=${event.code}, expected=${code}`));
    }, { once: true });
  });
}

function waitForCloseOrNetworkError(socket, code, timeoutMs = 12_000) {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      socket.removeEventListener("close", onClose);
      socket.removeEventListener("error", onError);
    };
    const onClose = (event) => {
      cleanup();
      if (event.code === code) resolve({ kind: "close", reason: event.reason });
      else reject(new Error(`close=${event.code}, expected=${code}`));
    };
    const onError = () => {
      cleanup();
      resolve({ kind: "network_error", reason: "" });
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("WebSocket close/error timeout"));
    }, timeoutMs);
    socket.addEventListener("close", onClose, { once: true });
    socket.addEventListener("error", onError, { once: true });
  });
}

async function waitForOpen(socket, timeoutMs = 5_000) {
  await new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      socket.removeEventListener("open", onOpen);
      socket.removeEventListener("error", onError);
    };
    const onOpen = () => {
      cleanup();
      resolve();
    };
    const onError = () => {
      cleanup();
      reject(new Error("WebSocket connection failed"));
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("WebSocket open timeout"));
    }, timeoutMs);
    socket.addEventListener("open", onOpen, { once: true });
    socket.addEventListener("error", onError, { once: true });
  });
}

async function connect(roomId, deviceId) {
  const socket = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(roomId)}`);
  await waitForOpen(socket);
  const statePromise = nextJson(socket, (message) => message.type === "room_state");
  socket.send(JSON.stringify({
    type: "hello",
    pairId: roomId,
    deviceId,
    protocolVersion: 2
  }));
  return { socket, state: await statePromise, deviceId };
}

async function waitUntilReady() {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (worker.exitCode !== null) {
      throw new Error(`wrangler exited early with ${worker.exitCode}\n${output}`);
    }
    try {
      const response = await fetch(`${baseHttpUrl}/health`);
      if (response.ok) return response.json();
    } catch {
      // Keep waiting while Miniflare starts.
    }
    await sleep(250);
  }
  throw new Error(`wrangler did not become ready\n${output}`);
}

const sockets = [];
try {
  const health = await waitUntilReady();
  assert.deepEqual(health, {
    ok: true,
    service: "electronic-muyu-relay",
    runtime: "cloudflare-workers-durable-objects"
  });

  const room = `integration-${Date.now()}`;
  const first = await connect(room, "device-a");
  sockets.push(first.socket);
  assert.equal(first.state.peerOnline, false);
  assert.equal(first.state.connections, 1);

  const firstOnline = nextJson(
    first.socket,
    (message) => message.type === "room_state" && message.peerOnline === true
  );
  const second = await connect(room, "device-b");
  sockets.push(second.socket);
  await firstOnline;
  assert.equal(second.state.peerOnline, true);
  assert.equal(second.state.connections, 2);

  const timestamp = Date.now();
  const forwarded = nextJson(second.socket, (message) => message.type === "tap");
  first.socket.send(JSON.stringify({
    type: "tap",
    pairId: room,
    deviceId: "device-a",
    timestamp,
    ignored: "not-forwarded"
  }));
  assert.deepEqual(await forwarded, {
    type: "tap",
    pairId: room,
    deviceId: "device-a",
    timestamp
  });

  const isolatedRoom = `${room}-isolated`;
  const isolated = await connect(isolatedRoom, "device-isolated");
  sockets.push(isolated.socket);
  const noLeak = expectNoTap(isolated.socket);
  first.socket.send(JSON.stringify({
    type: "tap",
    pairId: room,
    deviceId: "device-a",
    timestamp: Date.now()
  }));
  await noLeak;

  const replaced = waitForClose(first.socket, 4004);
  const replacement = await connect(room, "device-a");
  sockets.push(replacement.socket);
  await replaced;
  assert.equal(replacement.state.peerOnline, true);
  assert.equal(replacement.state.connections, 2);

  const third = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(room)}`);
  sockets.push(third);
  const thirdClosed = waitForClose(third, 4002);
  await waitForOpen(third);
  third.send(JSON.stringify({
    type: "hello",
    pairId: room,
    deviceId: "device-c",
    protocolVersion: 2
  }));
  await thirdClosed;

  const invalidRoom = new WebSocket(`${baseWebSocketUrl}/`);
  sockets.push(invalidRoom);
  await waitForClose(invalidRoom, 4000);

  const helloRequiredRoom = `${room}-hello-required`;
  const helloRequired = new WebSocket(
    `${baseWebSocketUrl}/?room=${encodeURIComponent(helloRequiredRoom)}`
  );
  sockets.push(helloRequired);
  const helloRequiredClosed = waitForClose(helloRequired, 4004);
  await waitForOpen(helloRequired);
  helloRequired.send(JSON.stringify({ type: "tap" }));
  await helloRequiredClosed;

  const helloTimeoutRoom = `${room}-hello-timeout`;
  const helloTimeout = new WebSocket(
    `${baseWebSocketUrl}/?room=${encodeURIComponent(helloTimeoutRoom)}`
  );
  sockets.push(helloTimeout);
  const timeoutStartedAt = Date.now();
  const helloTimeoutFinished = waitForCloseOrNetworkError(helloTimeout, 4004);
  await waitForOpen(helloTimeout);
  const helloTimeoutResult = await helloTimeoutFinished;
  assert.ok(Date.now() - timeoutStartedAt >= 4_500, "hello timeout fired too early");
  if (helloTimeoutResult.kind === "close") {
    assert.equal(helloTimeoutResult.reason, "hello timeout");
  }

  const binary = await connect(`${room}-binary`, "device-binary");
  sockets.push(binary.socket);
  const binaryClosed = waitForClose(binary.socket, 1003);
  binary.socket.send(new Uint8Array([1, 2, 3]));
  await binaryClosed;

  const oversized = await connect(`${room}-oversized`, "device-oversized");
  sockets.push(oversized.socket);
  const oversizedClosed = waitForClose(oversized.socket, 1009);
  oversized.socket.send("x".repeat(4 * 1024 + 1));
  await oversizedClosed;

  const offline = nextJson(
    replacement.socket,
    (message) => message.type === "room_state" && message.peerOnline === false
  );
  second.socket.close(1000, "leave");
  await offline;

  console.log("Cloudflare relay integration test passed");
} catch (error) {
  console.error(output);
  throw error;
} finally {
  for (const socket of sockets) {
    try {
      socket.close(1000, "test complete");
    } catch {
      // Ignore cleanup errors.
    }
  }
  if (worker.exitCode === null) {
    if (process.platform === "win32") {
      const killer = spawn("taskkill", ["/pid", String(worker.pid), "/t", "/f"], {
        stdio: "ignore"
      });
      await new Promise((resolve) => killer.once("exit", resolve));
    } else {
      try {
        process.kill(-worker.pid, "SIGTERM");
      } catch {
        // Already stopped.
      }
    }
  }
  await Promise.race([
    worker.exitCode === null
      ? new Promise((resolve) => worker.once("exit", resolve))
      : Promise.resolve(),
    sleep(3_000)
  ]);
}
