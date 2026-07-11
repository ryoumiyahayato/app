import { DurableObject } from "cloudflare:workers";
import { errorResponse, jsonResponse, rejectedWebSocket, safeClose } from "./http.js";
import {
  AUTH_TIMEOUT_MS,
  MAX_PENDING_SOCKETS,
  MAX_WEBSOCKET_MESSAGE_BYTES,
  PROTOCOL_VERSION,
  canonicalEncryptedTap,
  constantTimeEqual,
  decodeBase64Url,
  hasExactKeys,
  isDeviceName,
  isOpaqueId,
  isP256Spki,
  isSecret,
  isValidAuth,
  isValidEncryptedTap,
  sha256Base64Url,
  shortHash,
  updateRateWindow
} from "./protocol.js";

const AUTH_RATE_WINDOW_MS = 60_000;
const AUTH_ATTEMPTS_PER_WINDOW = 20;

async function hashSecret(secret) {
  const bytes = decodeBase64Url(secret, 32);
  return bytes ? sha256Base64Url(bytes) : null;
}

function isValidInitialization(input) {
  if (!hasExactKeys(input, ["version", "pairId", "createdAt", "devices"])) return false;
  if (input.version !== PROTOCOL_VERSION
    || !isOpaqueId(input.pairId)
    || !Number.isSafeInteger(input.createdAt)
    || !Array.isArray(input.devices)
    || input.devices.length !== 2) return false;
  const [first, second] = input.devices;
  for (const [expectedSlot, device] of input.devices.entries()) {
    if (!hasExactKeys(device, [
      "slot",
      "deviceId",
      "deviceName",
      "publicKey",
      "accessTokenHash"
    ])) return false;
    if (device.slot !== expectedSlot
      || !isOpaqueId(device.deviceId)
      || !isDeviceName(device.deviceName)
      || !isP256Spki(device.publicKey)
      || !isSecret(device.accessTokenHash)) return false;
  }
  return first.deviceId !== second.deviceId
    && first.publicKey !== second.publicKey
    && first.accessTokenHash !== second.accessTokenHash;
}

export class SecurePair extends DurableObject {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/initialize") return this.initialize(request);
    if (url.pathname === "/revoke") return this.revoke(request);
    if (url.pathname === "/socket") return this.openSocket(request, url);
    return errorResponse(404, "not_found");
  }

  async initialize(request) {
    if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
    let input;
    try {
      input = await request.json();
    } catch {
      return errorResponse(400, "invalid_pair_initialization");
    }
    if (!isValidInitialization(input)) return errorResponse(400, "invalid_pair_initialization");

    const existing = await this.ctx.storage.get("pair");
    if (existing) {
      const sameDevices = JSON.stringify(existing.devices) === JSON.stringify(input.devices);
      return existing.pairId === input.pairId
        && existing.status === "paired"
        && sameDevices
        ? jsonResponse({ ok: true, status: existing.status })
        : errorResponse(409, "pair_conflict");
    }
    const state = {
      ...input,
      status: "paired",
      lastRelayCounters: { [input.devices[0].deviceId]: 0, [input.devices[1].deviceId]: 0 }
    };
    await this.ctx.storage.put("pair", state);
    console.log(`[pairing] pair_initialized pairHash=${await shortHash(input.pairId)}`);
    return jsonResponse({ ok: true, status: "paired" }, 201);
  }

  async openSocket(request, url) {
    const pairId = url.searchParams.get("pair");
    const isUpgrade = request.method === "GET"
      && request.headers.get("upgrade")?.toLowerCase() === "websocket";
    if (!isUpgrade || !isOpaqueId(pairId)) {
      return rejectedWebSocket(4400, "invalid socket request");
    }

    const state = await this.ctx.storage.get("pair");
    if (!state || state.pairId !== pairId || state.status !== "paired") {
      return rejectedWebSocket(4403, "pair unavailable");
    }

    const pending = this.ctx.getWebSockets().filter((socket) => {
      const attachment = socket.deserializeAttachment();
      return socket.readyState === 1 && attachment?.authenticated !== true;
    });
    if (pending.length >= MAX_PENDING_SOCKETS) {
      return rejectedWebSocket(4408, "too many pending connections");
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const now = Date.now();
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      authenticated: false,
      pairId,
      pairHash: await shortHash(pairId),
      networkHash: request.headers.get("x-electronic-muyu-network") || "unknown",
      authDeadline: now + AUTH_TIMEOUT_MS,
      windowStartedAt: now,
      messagesInWindow: 0
    });
    await this.scheduleAuthAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || !isOpaqueId(attachment.pairId)) {
      safeClose(socket, 1011, "missing connection state");
      return;
    }
    if (typeof message !== "string") {
      safeClose(socket, 1003, "text messages only");
      return;
    }
    if (new TextEncoder().encode(message).byteLength > MAX_WEBSOCKET_MESSAGE_BYTES) {
      safeClose(socket, 1009, "message too large");
      return;
    }

    const rate = updateRateWindow(attachment, Date.now());
    attachment.windowStartedAt = rate.windowStartedAt;
    attachment.messagesInWindow = rate.messagesInWindow;
    socket.serializeAttachment(attachment);
    if (!rate.allowed) {
      safeClose(socket, 4408, "rate limit exceeded");
      return;
    }

    let parsed;
    try {
      parsed = JSON.parse(message);
    } catch {
      safeClose(socket, 4400, "invalid json");
      return;
    }

    if (!attachment.authenticated) {
      await this.authenticate(socket, attachment, parsed);
      return;
    }
    await this.forwardEncryptedTap(socket, attachment, parsed);
  }

  async authenticate(socket, attachment, message) {
    if (Date.now() > attachment.authDeadline) {
      safeClose(socket, 4410, "authentication timeout");
      return;
    }
    if (!await this.consumeAuthAttempt(attachment.networkHash)) {
      safeClose(socket, 4408, "authentication rate limit exceeded");
      return;
    }
    if (!isValidAuth(message, attachment.pairId)) {
      safeClose(socket, 4401, "authentication failed");
      return;
    }

    const state = await this.ctx.storage.get("pair");
    if (!state || state.status !== "paired") {
      safeClose(socket, 4403, "pair unavailable");
      return;
    }
    const device = state.devices.find((candidate) => candidate.deviceId === message.deviceId);
    const suppliedHash = await hashSecret(message.token);
    if (!device || !constantTimeEqual(suppliedHash, device.accessTokenHash)) {
      console.log(`[pairing] auth_failed pairHash=${attachment.pairHash}`);
      safeClose(socket, 4401, "authentication failed");
      return;
    }

    const duplicate = this.ctx.getWebSockets().some((peer) => {
      if (peer === socket || peer.readyState !== 1) return false;
      const peerAttachment = peer.deserializeAttachment();
      return peerAttachment?.authenticated === true
        && peerAttachment.deviceId === device.deviceId;
    });
    if (duplicate) {
      safeClose(socket, 4409, "device already connected");
      return;
    }

    attachment.authenticated = true;
    attachment.deviceId = device.deviceId;
    attachment.deviceHash = await shortHash(device.deviceId);
    attachment.slot = device.slot;
    delete attachment.authDeadline;
    socket.serializeAttachment(attachment);
    socket.send(JSON.stringify({
      type: "auth_ok",
      version: PROTOCOL_VERSION,
      slot: device.slot,
      timestamp: Date.now()
    }));
    console.log(`[pairing] auth_ok pairHash=${attachment.pairHash} deviceHash=${attachment.deviceHash}`);
    this.broadcastPeerState();
    await this.scheduleAuthAlarm();
  }

  async forwardEncryptedTap(socket, attachment, message) {
    if (!isValidEncryptedTap(message, attachment.pairId, attachment.deviceId)) {
      safeClose(socket, 4400, "encrypted message required");
      return;
    }

    const accepted = await this.ctx.storage.transaction(async (transaction) => {
      const state = await transaction.get("pair");
      if (!state || state.status !== "paired") return false;
      const lastCounter = state.lastRelayCounters?.[attachment.deviceId] || 0;
      if (message.counter <= lastCounter) return false;
      state.lastRelayCounters[attachment.deviceId] = message.counter;
      await transaction.put("pair", state);
      return true;
    });
    if (!accepted) {
      safeClose(socket, 4414, "counter replay rejected");
      return;
    }

    const payload = canonicalEncryptedTap(message);
    let forwarded = 0;
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === socket || peer.readyState !== 1) continue;
      const peerAttachment = peer.deserializeAttachment();
      if (peerAttachment?.authenticated !== true
        || peerAttachment.deviceId === attachment.deviceId) continue;
      try {
        peer.send(payload);
        forwarded += 1;
      } catch {
        safeClose(peer, 1011, "forward failed");
      }
    }
    console.log(`[pairing] encrypted_forward forwarded=${forwarded} pairHash=${attachment.pairHash}`);
  }

  broadcastPeerState() {
    const authenticated = this.ctx.getWebSockets()
      .filter((socket) => socket.readyState === 1)
      .map((socket) => ({ socket, attachment: socket.deserializeAttachment() }))
      .filter(({ attachment }) => attachment?.authenticated === true);
    const timestamp = Date.now();

    for (const { socket, attachment } of authenticated) {
      const peerOnline = authenticated.some(({ attachment: candidate }) =>
        candidate.deviceId !== attachment.deviceId
      );
      try {
        socket.send(JSON.stringify({
          type: "peer_state",
          version: PROTOCOL_VERSION,
          peerOnline,
          timestamp
        }));
      } catch {
        safeClose(socket, 1011, "peer state failed");
      }
    }
  }

  async revoke(request) {
    if (request.method !== "DELETE") return errorResponse(405, "method_not_allowed");
    let input;
    try {
      input = await request.json();
    } catch {
      return errorResponse(400, "invalid_revoke_request");
    }
    const state = await this.ctx.storage.get("pair");
    if (!state || state.pairId !== input.pairId) return errorResponse(404, "pair_not_found");
    if (state.status !== "paired") return errorResponse(410, "pair_revoked");
    const device = state.devices.find((candidate) => candidate.deviceId === input.deviceId);
    const suppliedHash = await hashSecret(input.token);
    if (!device || !constantTimeEqual(suppliedHash, device.accessTokenHash)) {
      return errorResponse(401, "authentication_failed");
    }

    state.status = "disabled";
    state.revokedAt = input.now;
    state.revokedBySlot = device.slot;
    state.devices = state.devices.map((entry) => ({ ...entry, accessTokenHash: null }));
    await this.ctx.storage.put("pair", state);
    for (const socket of this.ctx.getWebSockets()) safeClose(socket, 4403, "pair revoked");
    console.log(`[pairing] pair_revoked pairHash=${await shortHash(state.pairId)}`);
    return jsonResponse({ ok: true, status: "revoked" });
  }

  async consumeAuthAttempt(networkHash) {
    const key = `auth-rate:${networkHash}`;
    const now = Date.now();
    return this.ctx.storage.transaction(async (transaction) => {
      const previous = await transaction.get(key);
      const reset = !previous
        || now < previous.startedAt
        || now - previous.startedAt >= AUTH_RATE_WINDOW_MS;
      const next = reset
        ? { startedAt: now, count: 1 }
        : { startedAt: previous.startedAt, count: previous.count + 1 };
      await transaction.put(key, next);
      return next.count <= AUTH_ATTEMPTS_PER_WINDOW;
    });
  }

  async scheduleAuthAlarm() {
    const deadlines = this.ctx.getWebSockets()
      .filter((socket) => socket.readyState === 1)
      .map((socket) => socket.deserializeAttachment())
      .filter((attachment) => attachment?.authenticated !== true
        && Number.isSafeInteger(attachment?.authDeadline))
      .map((attachment) => attachment.authDeadline);
    if (deadlines.length > 0) await this.ctx.storage.setAlarm(Math.min(...deadlines));
  }

  async alarm() {
    const now = Date.now();
    for (const socket of this.ctx.getWebSockets()) {
      const attachment = socket.deserializeAttachment();
      if (attachment?.authenticated !== true
        && Number.isSafeInteger(attachment?.authDeadline)
        && now >= attachment.authDeadline) {
        safeClose(socket, 4410, "authentication timeout");
      }
    }
    await this.scheduleAuthAlarm();
  }

  webSocketClose(socket, code, _reason, wasClean) {
    const attachment = socket.deserializeAttachment();
    console.log(
      `[pairing] disconnected code=${code} clean=${wasClean} `
      + `pairHash=${attachment?.pairHash || "unknown"} deviceHash=${attachment?.deviceHash || "pending"}`
    );
    this.broadcastPeerState();
  }

  webSocketError(socket) {
    safeClose(socket, 1011, "websocket error");
  }
}
