import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const host = "127.0.0.1";
const port = 18787;
const baseHttpUrl = `http://${host}:${port}`;
const baseWebSocketUrl = `ws://${host}:${port}`;
const wranglerEntry = fileURLToPath(
  new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url)
);

const b64 = (value) => Buffer.from(value).toString("base64url");
const opaque = () => b64(randomBytes(16));
const secret = () => b64(randomBytes(32));
const publicKey = () => b64(randomBytes(91));
const digest = (value) => b64(createHash("sha256").update(Buffer.from(value, "base64url")).digest());

let output = "";
const worker = spawn(
  process.execPath,
  [wranglerEntry, "dev", "--local", "--ip", host, "--port", String(port)],
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
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (worker.exitCode !== null) {
      throw new Error(`wrangler exited early with ${worker.exitCode}\n${output}`);
    }
    try {
      const response = await fetch(`${baseHttpUrl}/health`);
      if (response.ok) return response.json();
    } catch {
      // Miniflare is still starting.
    }
    await sleep(250);
  }
  throw new Error(`wrangler did not become ready\n${output}`);
}

function inviteBody(inviteId, inviteSecret, inviter) {
  return {
    version: 1,
    inviteId,
    inviteSecretHash: digest(inviteSecret),
    inviterDeviceId: inviter.deviceId,
    inviterDeviceName: inviter.name,
    inviterPublicKey: inviter.publicKey
  };
}

function joinBody(inviteSecret, joiner) {
  return {
    version: 1,
    inviteSecret,
    joinerDeviceId: joiner.deviceId,
    joinerDeviceName: joiner.name,
    joinerPublicKey: joiner.publicKey
  };
}

const sockets = [];
const sensitiveValues = [];
try {
  const health = await waitUntilReady();
  assert.deepEqual(health, {
    ok: true,
    service: "electronic-muyu-relay",
    protocolVersion: 1,
    runtime: "cloudflare-workers-durable-objects"
  });
  assert.equal((await fetch(`${baseHttpUrl}/?room=legacy`)).status, 404);

  const inviter = { deviceId: opaque(), name: "设备甲", publicKey: publicKey() };
  const winner = { deviceId: opaque(), name: "设备乙", publicKey: publicKey() };
  const loser = { deviceId: opaque(), name: "设备丙", publicKey: publicKey() };
  const inviteId = opaque();
  const inviteSecret = secret();
  sensitiveValues.push(inviteSecret);

  const created = await jsonRequest(
    "/v1/invites",
    "POST",
    inviteBody(inviteId, inviteSecret, inviter),
    201
  );
  sensitiveValues.push(created.ownerSessionToken);
  assert.equal(created.inviteId, inviteId);
  assert.ok(created.expiresAt - Date.now() > 115_000);
  assert.ok(created.expiresAt - Date.now() <= 120_000);

  await jsonRequest(
    `/v1/invites/${inviteId}/join`,
    "POST",
    joinBody(secret(), winner),
    401
  );

  const concurrent = await Promise.all([
    fetch(`${baseHttpUrl}/v1/invites/${inviteId}/join`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(joinBody(inviteSecret, winner))
    }),
    fetch(`${baseHttpUrl}/v1/invites/${inviteId}/join`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(joinBody(inviteSecret, loser))
    })
  ]);
  assert.deepEqual(concurrent.map((response) => response.status).sort(), [200, 409]);
  const winningIndex = concurrent.findIndex((response) => response.status === 200);
  const selectedJoiner = winningIndex === 0 ? winner : loser;
  const joined = await concurrent[winningIndex].json();
  sensitiveValues.push(joined.joinSessionToken);
  assert.equal(joined.status, "joined");
  assert.equal(joined.transcript.joiner.deviceId, selectedJoiner.deviceId);

  const ownerStatus = await jsonRequest(`/v1/invites/${inviteId}/confirm`, "POST", {
    version: 1,
    deviceId: inviter.deviceId,
    sessionToken: created.ownerSessionToken,
    decision: "status"
  }, 200);
  assert.equal(ownerStatus.transcript.pairId, joined.transcript.pairId);
  const pairId = joined.transcript.pairId;

  const inviterToken = secret();
  const joinerToken = secret();
  sensitiveValues.push(inviterToken, joinerToken);
  const firstConfirm = await jsonRequest(`/v1/invites/${inviteId}/confirm`, "POST", {
    version: 1,
    deviceId: inviter.deviceId,
    sessionToken: created.ownerSessionToken,
    decision: "confirm",
    accessTokenHash: digest(inviterToken)
  }, 200);
  assert.equal(firstConfirm.status, "joined");
  const secondConfirm = await jsonRequest(`/v1/invites/${inviteId}/confirm`, "POST", {
    version: 1,
    deviceId: selectedJoiner.deviceId,
    sessionToken: joined.joinSessionToken,
    decision: "confirm",
    accessTokenHash: digest(joinerToken)
  }, 200);
  assert.equal(secondConfirm.status, "paired");

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
  assert.equal((await unauthorizedClose).code, 4401);

  const encryptedTap = {
    type: "encrypted_tap",
    version: 1,
    pairId,
    sender: inviter.deviceId,
    counter: 1,
    iv: b64(randomBytes(12)),
    ciphertext: b64(randomBytes(64))
  };
  const forwarded = nextJson(second);
  first.send(JSON.stringify(encryptedTap));
  assert.deepEqual(await forwarded, encryptedTap);

  const replayClose = waitForClose(first);
  first.send(JSON.stringify(encryptedTap));
  assert.equal((await replayClose).code, 4414);

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

  const saturatedRoom = `${room}-stale-pending`;
  const stalePendingSockets = [];
  for (let index = 0; index < 6; index += 1) {
    const pending = new WebSocket(
      `${baseWebSocketUrl}/?room=${encodeURIComponent(saturatedRoom)}`
    );
    pending.addEventListener("error", () => { /* Wrangler may report alarm close as error. */ });
    sockets.push(pending);
    stalePendingSockets.push(pending);
    await waitForOpen(pending);
  }
  await sleep(6_000);
  const afterPendingExpiry = await connect(saturatedRoom, "device-after-timeout");
  sockets.push(afterPendingExpiry.socket);
  assert.equal(afterPendingExpiry.state.peerOnline, false);
  assert.equal(afterPendingExpiry.state.connections, 1);

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

  const timeoutSocket = await connect(pairId);
  sockets.push(timeoutSocket);
  await sleep(5_500);
  assert.match(output, /disconnected code=4410/u);
  timeoutSocket.close(1000, "timeout observed on Worker");

  const revokeClose = waitForClose(reconnected);
  const revoked = await jsonRequest(
    `/v1/pairs/${pairId}/devices/${inviter.deviceId}`,
    "DELETE",
    { version: 1, token: inviterToken },
    200
  );
  assert.equal(revoked.status, "revoked");
  assert.equal((await revokeClose).code, 4403);
  const afterRevoke = new WebSocket(
    `${baseWebSocketUrl}/v1/socket?pair=${encodeURIComponent(pairId)}`
  );
  assert.equal((await waitForClose(afterRevoke)).code, 4403);

  const cancelledId = opaque();
  const cancelledSecret = secret();
  const cancelled = await jsonRequest(
    "/v1/invites",
    "POST",
    inviteBody(cancelledId, cancelledSecret, inviter),
    201
  );
  await jsonRequest(`/v1/invites/${cancelledId}/cancel`, "POST", {
    version: 1,
    deviceId: inviter.deviceId,
    sessionToken: cancelled.ownerSessionToken
  }, 200);
  await jsonRequest(
    `/v1/invites/${cancelledId}/join`,
    "POST",
    joinBody(cancelledSecret, winner),
    410
  );

  for (const value of sensitiveValues) {
    assert.equal(output.includes(value), false, "sensitive value appeared in Worker logs");
  }
  console.log("Cloudflare secure pairing integration test passed");
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
