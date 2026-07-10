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

function connect(roomId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(roomId)}`);
    const timer = setTimeout(() => reject(new Error(`WebSocket connect timeout: ${roomId}`)), 8_000);
    socket.addEventListener("open", () => {
      clearTimeout(timer);
      resolve(socket);
    }, { once: true });
    socket.addEventListener("error", () => {
      clearTimeout(timer);
      reject(new Error(`WebSocket connection failed: ${roomId}`));
    }, { once: true });
  });
}

function nextJson(socket, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket message timeout")), timeoutMs);
    const handler = (event) => {
      clearTimeout(timer);
      socket.removeEventListener("message", handler);
      resolve(JSON.parse(event.data));
    };
    socket.addEventListener("message", handler);
  });
}

function expectNoMessage(socket, timeoutMs = 400) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.removeEventListener("message", handler);
      resolve();
    }, timeoutMs);
    const handler = () => {
      clearTimeout(timer);
      socket.removeEventListener("message", handler);
      reject(new Error("Unexpected cross-room message"));
    };
    socket.addEventListener("message", handler);
  });
}

function waitForClose(socket, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket close timeout")), timeoutMs);
    socket.addEventListener("close", (event) => {
      clearTimeout(timer);
      resolve({ code: event.code, reason: event.reason });
    }, { once: true });
  });
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
  const first = await connect(room);
  sockets.push(first);
  assert.equal((await nextJson(first)).connections, 1);

  const second = await connect(room);
  sockets.push(second);
  assert.equal((await nextJson(second)).connections, 2);

  const timestamp = Date.now();
  const forwardedPromise = nextJson(second);
  first.send(JSON.stringify({
    type: "tap",
    pairId: room,
    deviceId: "device-first",
    timestamp,
    ignored: "not-forwarded"
  }));
  assert.deepEqual(await forwardedPromise, {
    type: "tap",
    pairId: room,
    deviceId: "device-first",
    timestamp
  });

  const isolatedRoom = `${room}-isolated`;
  const isolated = await connect(isolatedRoom);
  sockets.push(isolated);
  await nextJson(isolated);
  const noLeak = expectNoMessage(isolated);
  first.send(JSON.stringify({
    type: "tap",
    pairId: room,
    deviceId: "device-first",
    timestamp: Date.now()
  }));
  await noLeak;

  const third = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(room)}`);
  assert.equal((await waitForClose(third)).code, 4002);

  const invalid = new WebSocket(`${baseWebSocketUrl}/`);
  assert.equal((await waitForClose(invalid)).code, 4000);

  const binaryRoom = `${room}-binary`;
  const binary = await connect(binaryRoom);
  sockets.push(binary);
  await nextJson(binary);
  const binaryClose = waitForClose(binary);
  binary.send(new Uint8Array([1, 2, 3]));
  assert.equal((await binaryClose).code, 1003);

  console.log("Cloudflare relay integration test passed");
} catch (error) {
  console.error(output);
  throw error;
} finally {
  for (const socket of sockets) {
    try { socket.close(1000, "test complete"); } catch { /* no-op */ }
  }
  if (worker.exitCode === null) {
    if (process.platform === "win32") {
      const killer = spawn("taskkill", ["/pid", String(worker.pid), "/t", "/f"], {
        stdio: "ignore"
      });
      await new Promise((resolve) => killer.once("exit", resolve));
    } else {
      try { process.kill(-worker.pid, "SIGTERM"); } catch { /* already stopped */ }
    }
  }
  await Promise.race([
    worker.exitCode === null
      ? new Promise((resolve) => worker.once("exit", resolve))
      : Promise.resolve(),
    sleep(3_000)
  ]);
}
