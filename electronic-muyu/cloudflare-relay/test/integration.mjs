import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const host = "127.0.0.1";
const port = 18787;
const baseHttpUrl = `http://${host}:${port}`;
const baseWebSocketUrl = `ws://${host}:${port}`;
const wranglerEntry = new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url);
let output = "";
const worker = spawn(process.execPath, [wranglerEntry.pathname, "dev", "--local", "--ip", host, "--port", String(port)], {
  cwd: new URL("..", import.meta.url), env: { ...process.env, NO_COLOR: "1" },
  stdio: ["ignore", "pipe", "pipe"], detached: process.platform !== "win32"
});
worker.stdout.on("data", (chunk) => { output += chunk.toString(); });
worker.stderr.on("data", (chunk) => { output += chunk.toString(); });

function nextJson(socket, predicate = () => true, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket message timeout")), timeoutMs);
    const handler = (event) => {
      const value = JSON.parse(event.data);
      if (!predicate(value)) return;
      clearTimeout(timer);
      socket.removeEventListener("message", handler);
      resolve(value);
    };
    socket.addEventListener("message", handler);
  });
}

function waitForClose(socket, code, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket close timeout")), timeoutMs);
    socket.addEventListener("close", (event) => {
      clearTimeout(timer);
      if (event.code === code) resolve();
      else reject(new Error(`close=${event.code}, expected=${code}`));
    }, { once: true });
  });
}

async function connect(roomId, deviceId) {
  const socket = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(roomId)}`);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  const statePromise = nextJson(socket, (message) => message.type === "room_state");
  socket.send(JSON.stringify({ type: "hello", pairId: roomId, deviceId, protocolVersion: 2 }));
  return { socket, state: await statePromise, deviceId };
}

async function waitUntilReady() {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (worker.exitCode !== null) throw new Error(`wrangler exited early\n${output}`);
    try {
      const response = await fetch(`${baseHttpUrl}/health`);
      if (response.ok) return response.json();
    } catch { /* starting */ }
    await sleep(250);
  }
  throw new Error(`wrangler did not become ready\n${output}`);
}

const sockets = [];
try {
  assert.equal((await waitUntilReady()).ok, true);
  const room = `integration-${Date.now()}`;
  const first = await connect(room, "device-a"); sockets.push(first.socket);
  assert.equal(first.state.peerOnline, false);
  const firstOnline = nextJson(first.socket, (m) => m.type === "room_state" && m.peerOnline === true);
  const second = await connect(room, "device-b"); sockets.push(second.socket);
  await firstOnline;
  assert.equal(second.state.peerOnline, true);

  const forwarded = nextJson(second.socket, (m) => m.type === "tap");
  first.socket.send(JSON.stringify({
    type: "tap", pairId: room, deviceId: "device-a", timestamp: Date.now(), ignored: true
  }));
  assert.equal((await forwarded).deviceId, "device-a");

  const replaced = waitForClose(first.socket, 4004);
  const replacement = await connect(room, "device-a"); sockets.push(replacement.socket);
  await replaced;
  assert.equal(replacement.state.peerOnline, true);

  const third = new WebSocket(`${baseWebSocketUrl}/?room=${encodeURIComponent(room)}`);
  const thirdClosed = waitForClose(third, 4002);
  third.addEventListener("open", () => third.send(JSON.stringify({
    type: "hello", pairId: room, deviceId: "device-c", protocolVersion: 2
  })), { once: true });
  await thirdClosed;

  const offline = nextJson(replacement.socket, (m) => m.type === "room_state" && m.peerOnline === false);
  second.socket.close(1000, "leave");
  await offline;
  console.log("Cloudflare relay integration test passed");
} catch (error) {
  console.error(output);
  throw error;
} finally {
  for (const socket of sockets) { try { socket.close(1000, "test complete"); } catch { /* no-op */ } }
  if (worker.exitCode === null) {
    if (process.platform === "win32") {
      const killer = spawn("taskkill", ["/pid", String(worker.pid), "/t", "/f"], { stdio: "ignore" });
      await new Promise((resolve) => killer.once("exit", resolve));
    } else {
      try { process.kill(-worker.pid, "SIGTERM"); } catch { /* stopped */ }
    }
  }
  await Promise.race([
    worker.exitCode === null ? new Promise((resolve) => worker.once("exit", resolve)) : Promise.resolve(),
    sleep(3_000)
  ]);
}
