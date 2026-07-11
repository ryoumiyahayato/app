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

async function jsonRequest(path, method, body, expectedStatus) {
  const response = await fetch(`${baseHttpUrl}${path}`, {
    method,
    headers: { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const value = await response.json();
  assert.equal(response.status, expectedStatus, `${method} ${path}: ${JSON.stringify(value)}`);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  return value;
}

function connect(pairId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${baseWebSocketUrl}/v1/socket?pair=${encodeURIComponent(pairId)}`);
    const timer = setTimeout(() => reject(new Error("WebSocket connect timeout")), 8_000);
    socket.addEventListener("open", () => {
      clearTimeout(timer);
      resolve(socket);
    }, { once: true });
    socket.addEventListener("error", () => {
      clearTimeout(timer);
      reject(new Error("WebSocket connection failed"));
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

function waitForClose(socket, timeoutMs = 8_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket close timeout")), timeoutMs);
    socket.addEventListener("close", (event) => {
      clearTimeout(timer);
      resolve({ code: event.code, reason: event.reason });
    }, { once: true });
  });
}

async function authenticate(socket, pairId, deviceId, token) {
  const received = nextJson(socket);
  socket.send(JSON.stringify({ type: "auth", version: 1, pairId, deviceId, token }));
  const response = await received;
  assert.equal(response.type, "auth_ok");
  return response;
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

  // Pending unauthenticated sockets do not occupy either registered device slot.
  const pending = await connect(pairId);
  sockets.push(pending);
  const first = await connect(pairId);
  const second = await connect(pairId);
  sockets.push(first, second);
  assert.equal((await authenticate(first, pairId, inviter.deviceId, inviterToken)).slot, 0);
  assert.equal((await authenticate(second, pairId, selectedJoiner.deviceId, joinerToken)).slot, 1);
  pending.close(1000, "pending slot check complete");
  await sleep(100);

  const duplicate = await connect(pairId);
  sockets.push(duplicate);
  const duplicateClose = waitForClose(duplicate);
  duplicate.send(JSON.stringify({
    type: "auth", version: 1, pairId, deviceId: inviter.deviceId, token: inviterToken
  }));
  assert.equal((await duplicateClose).code, 4409);

  const unauthorized = await connect(pairId);
  sockets.push(unauthorized);
  const unauthorizedClose = waitForClose(unauthorized);
  unauthorized.send(JSON.stringify({
    type: "auth", version: 1, pairId, deviceId: opaque(), token: secret()
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

  const reconnected = await connect(pairId);
  sockets.push(reconnected);
  await authenticate(reconnected, pairId, inviter.deviceId, inviterToken);

  const plaintext = await connect(pairId);
  sockets.push(plaintext);
  await second.close(1000, "replace for protocol check");
  await sleep(100);
  await authenticate(plaintext, pairId, selectedJoiner.deviceId, joinerToken);
  const plaintextClose = waitForClose(plaintext);
  plaintext.send(JSON.stringify({ type: "tap", timestamp: Date.now() }));
  assert.equal((await plaintextClose).code, 4400);

  const oversized = await connect(pairId);
  sockets.push(oversized);
  const oversizedClose = waitForClose(oversized);
  oversized.send("x".repeat(4097));
  assert.equal((await oversizedClose).code, 1009);

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
