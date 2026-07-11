import { DurableObject } from "cloudflare:workers";
import {
  MAX_MESSAGE_BYTES,
  canonicalTap,
  constantTimeEqual,
  isValidHello,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "./protocol.js";

const HEALTH_BODY = JSON.stringify({
  ok: true,
  service: "electronic-muyu-relay",
  runtime: "cloudflare-workers-durable-objects"
});
const MAX_PENDING_AND_ACTIVE_SOCKETS = 6;

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer"
    }
  });
}

function rejectedWebSocket(code, reason) {
  const pair = new WebSocketPair();
  const [client, server] = Object.values(pair);
  server.accept();
  server.close(code, reason);
  return new Response(null, { status: 101, webSocket: client });
}

function safeClose(socket, code, reason) {
  try { socket.close(code, reason); } catch { /* already closed */ }
}

async function shortHash(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest).slice(0, 4))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return new Response(HEALTH_BODY, {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "no-store",
          "x-content-type-options": "nosniff",
          "referrer-policy": "no-referrer"
        }
      });
    }
    if (url.pathname !== "/" && url.pathname !== "/ws") {
      return jsonResponse({ ok: false, error: "not_found" }, 404);
    }
    const isWebSocket = request.method === "GET"
      && request.headers.get("Upgrade")?.toLowerCase() === "websocket";
    if (!isWebSocket) return jsonResponse({ ok: false, error: "websocket_upgrade_required" }, 426);

    const roomId = url.searchParams.get("room");
    if (!isValidRoomId(roomId)) return rejectedWebSocket(4000, "valid room parameter required");
    const requiredToken = typeof env.RELAY_TOKEN === "string" ? env.RELAY_TOKEN : "";
    if (requiredToken.length > 0) {
      const suppliedToken = url.searchParams.get("token") || "";
      if (!constantTimeEqual(suppliedToken, requiredToken)) return rejectedWebSocket(4001, "invalid token");
    }
    return env.ROOMS.get(env.ROOMS.idFromName(roomId)).fetch(request);
  }
};

export class PairRoom extends DurableObject {
  activeSockets() {
    return this.ctx.getWebSockets().filter((socket) => socket.readyState === 1);
  }

  attachment(socket) {
    return socket.deserializeAttachment() || null;
  }

  broadcastRoomState(roomId) {
    const sockets = this.activeSockets();
    const registered = sockets
      .map((socket) => ({ socket, attachment: this.attachment(socket) }))
      .filter(({ attachment }) => attachment?.deviceId);
    const now = Date.now();
    for (const entry of registered) {
      const peerOnline = registered.some(
        (candidate) => candidate.attachment.deviceId !== entry.attachment.deviceId
      );
      try {
        entry.socket.send(JSON.stringify({
          type: "room_state",
          pairId: roomId,
          peerOnline,
          connections: registered.length,
          timestamp: now
        }));
      } catch {
        safeClose(entry.socket, 1011, "room_state send failed");
      }
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.searchParams.get("room");
    const isWebSocket = request.method === "GET"
      && request.headers.get("Upgrade")?.toLowerCase() === "websocket";
    if (!isWebSocket || !isValidRoomId(roomId)) {
      return jsonResponse({ ok: false, error: "invalid_room_request" }, 400);
    }
    if (this.activeSockets().length >= MAX_PENDING_AND_ACTIVE_SOCKETS) {
      return rejectedWebSocket(4003, "room handshake capacity reached");
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const now = Date.now();
    const roomHash = await shortHash(roomId);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      roomId,
      roomHash,
      deviceId: null,
      joinedAt: now,
      windowStartedAt: now,
      messagesInWindow: 0
    });
    try {
      server.send(JSON.stringify({ type: "hello_required", protocolVersion: 2, timestamp: now }));
    } catch {
      safeClose(server, 1011, "hello_required send failed");
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    const attachment = this.attachment(socket);
    if (!attachment || !isValidRoomId(attachment.roomId)) {
      safeClose(socket, 1011, "missing connection state");
      return;
    }
    if (typeof message !== "string") {
      safeClose(socket, 1003, "text messages only");
      return;
    }
    if (new TextEncoder().encode(message).byteLength > MAX_MESSAGE_BYTES) {
      safeClose(socket, 1009, "message too large");
      return;
    }

    const rateState = updateRateWindow(attachment, Date.now());
    attachment.windowStartedAt = rateState.windowStartedAt;
    attachment.messagesInWindow = rateState.messagesInWindow;
    socket.serializeAttachment(attachment);
    if (!rateState.allowed) {
      safeClose(socket, 4008, "rate limit exceeded");
      return;
    }

    let parsed;
    try { parsed = JSON.parse(message); } catch { return; }

    if (!attachment.deviceId) {
      if (!isValidHello(parsed, attachment.roomId)) {
        safeClose(socket, 4004, "hello required");
        return;
      }

      const registered = this.activeSockets()
        .filter((peer) => peer !== socket)
        .map((peer) => ({ peer, attachment: this.attachment(peer) }))
        .filter((entry) => entry.attachment?.deviceId);
      const existing = registered.find((entry) => entry.attachment.deviceId === parsed.deviceId);
      const distinctDeviceIds = new Set(registered.map((entry) => entry.attachment.deviceId));
      if (!existing && distinctDeviceIds.size >= 2) {
        safeClose(socket, 4002, "room is full");
        return;
      }

      attachment.deviceId = parsed.deviceId;
      socket.serializeAttachment(attachment);
      if (existing) safeClose(existing.peer, 4004, "replaced by newer connection");
      this.broadcastRoomState(attachment.roomId);
      return;
    }

    if (!isValidTap(parsed, attachment.roomId, attachment.deviceId)) return;
    const payload = canonicalTap(parsed);
    for (const peer of this.activeSockets()) {
      if (peer === socket) continue;
      const peerAttachment = this.attachment(peer);
      if (!peerAttachment?.deviceId || peerAttachment.deviceId === attachment.deviceId) continue;
      try { peer.send(payload); } catch { safeClose(peer, 1011, "forward failed"); }
    }
  }

  async webSocketClose(socket, code, _reason, wasClean) {
    const attachment = this.attachment(socket);
    console.log(`[relay] disconnected code=${code} clean=${wasClean}`);
    if (attachment?.roomId) this.broadcastRoomState(attachment.roomId);
  }

  webSocketError(socket) {
    safeClose(socket, 1011, "websocket error");
  }
}
