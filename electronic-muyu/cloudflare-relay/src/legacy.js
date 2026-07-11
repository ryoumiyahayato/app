import { DurableObject } from "cloudflare:workers";
import { rejectedWebSocket, safeClose } from "./http.js";
import { constantTimeEqual, shortHash, updateRateWindow } from "./protocol.js";

const MAX_MESSAGE_BYTES = 4096;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/u;

export function isLegacyRoomId(value) {
  return typeof value === "string"
    && value.length >= 1
    && value.length <= 64
    && !CONTROL_CHARACTERS.test(value);
}

function isLegacyTap(value, roomId) {
  return value !== null
    && typeof value === "object"
    && !Array.isArray(value)
    && Object.keys(value).every((key) => ["type", "pairId", "deviceId", "timestamp"].includes(key))
    && value.type === "tap"
    && value.pairId === roomId
    && typeof value.deviceId === "string"
    && value.deviceId.length >= 1
    && value.deviceId.length <= 128
    && !CONTROL_CHARACTERS.test(value.deviceId)
    && Number.isSafeInteger(value.timestamp)
    && value.timestamp > 0;
}

export async function routeLegacy(request, env, url) {
  const roomId = url.searchParams.get("room");
  if (!isLegacyRoomId(roomId)) return rejectedWebSocket(4000, "valid room required");
  const requiredToken = typeof env.RELAY_TOKEN === "string" ? env.RELAY_TOKEN : "";
  const suppliedToken = url.searchParams.get("token") || "";
  if (requiredToken && !constantTimeEqual(suppliedToken, requiredToken)) {
    return rejectedWebSocket(4001, "invalid token");
  }
  return env.ROOMS.get(env.ROOMS.idFromName(roomId)).fetch(request);
}

export class PairRoom extends DurableObject {
  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.searchParams.get("room");
    const isUpgrade = request.method === "GET"
      && request.headers.get("upgrade")?.toLowerCase() === "websocket";
    if (!isUpgrade || !isLegacyRoomId(roomId)) {
      return rejectedWebSocket(4000, "invalid legacy request");
    }
    const active = this.ctx.getWebSockets().filter((socket) => socket.readyState === 1);
    if (active.length >= 2) return rejectedWebSocket(4002, "room is full");

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const now = Date.now();
    const roomHash = await shortHash(roomId);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      roomId,
      roomHash,
      deviceId: null,
      windowStartedAt: now,
      messagesInWindow: 0
    });
    server.send(JSON.stringify({
      type: "room_info",
      room: roomId,
      connections: active.length + 1,
      timestamp: now
    }));
    console.log(`[legacy] connected roomHash=${roomHash}`);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || !isLegacyRoomId(attachment.roomId)) {
      safeClose(socket, 1011, "missing state");
      return;
    }
    if (typeof message !== "string") {
      safeClose(socket, 1003, "text only");
      return;
    }
    if (new TextEncoder().encode(message).byteLength > MAX_MESSAGE_BYTES) {
      safeClose(socket, 1009, "message too large");
      return;
    }
    const rate = updateRateWindow(attachment, Date.now());
    attachment.windowStartedAt = rate.windowStartedAt;
    attachment.messagesInWindow = rate.messagesInWindow;
    socket.serializeAttachment(attachment);
    if (!rate.allowed) {
      safeClose(socket, 4008, "rate limit exceeded");
      return;
    }

    let value;
    try { value = JSON.parse(message); } catch { return; }
    if (!isLegacyTap(value, attachment.roomId)) return;
    if (attachment.deviceId === null) {
      attachment.deviceId = value.deviceId;
      socket.serializeAttachment(attachment);
    } else if (attachment.deviceId !== value.deviceId) {
      safeClose(socket, 4000, "device changed");
      return;
    }

    const payload = JSON.stringify({
      type: "tap",
      pairId: value.pairId,
      deviceId: value.deviceId,
      timestamp: value.timestamp
    });
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === socket || peer.readyState !== 1) continue;
      try { peer.send(payload); } catch { safeClose(peer, 1011, "forward failed"); }
    }
  }

  webSocketError(socket) {
    safeClose(socket, 1011, "websocket error");
  }
}