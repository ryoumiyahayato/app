import { DurableObject } from "cloudflare:workers";
import {
  MAX_MESSAGE_BYTES,
  canonicalTap,
  constantTimeEqual,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "./protocol.js";

const HEALTH_BODY = JSON.stringify({
  ok: true,
  service: "electronic-muyu-relay",
  runtime: "cloudflare-workers-durable-objects"
});

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
  try {
    socket.close(code, reason);
  } catch {
    // The peer may already have closed. No retry or logging is needed here.
  }
}

async function shortHash(value) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value)
  );
  return Array.from(new Uint8Array(digest).slice(0, 4))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
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
    if (!isWebSocket) {
      return jsonResponse({ ok: false, error: "websocket_upgrade_required" }, 426);
    }

    const roomId = url.searchParams.get("room");
    if (!isValidRoomId(roomId)) {
      return rejectedWebSocket(4000, "valid room parameter required");
    }

    const requiredToken = typeof env.RELAY_TOKEN === "string" ? env.RELAY_TOKEN : "";
    if (requiredToken.length > 0) {
      const suppliedToken = url.searchParams.get("token") || "";
      if (!constantTimeEqual(suppliedToken, requiredToken)) {
        return rejectedWebSocket(4001, "invalid token");
      }
    }

    const roomObjectId = env.ROOMS.idFromName(roomId);
    const roomObject = env.ROOMS.get(roomObjectId);
    return roomObject.fetch(request);
  }
};

export class PairRoom extends DurableObject {
  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.searchParams.get("room");
    const isWebSocket = request.method === "GET"
      && request.headers.get("Upgrade")?.toLowerCase() === "websocket";

    if (!isWebSocket || !isValidRoomId(roomId)) {
      return jsonResponse({ ok: false, error: "invalid_room_request" }, 400);
    }

    const activeSockets = this.ctx.getWebSockets().filter((socket) => socket.readyState === 1);
    if (activeSockets.length >= 2) {
      return rejectedWebSocket(4002, "room is full");
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
      server.send(JSON.stringify({
        type: "room_info",
        room: roomId,
        connections: activeSockets.length + 1,
        timestamp: now
      }));
      console.log(`[relay] connected roomHash=${roomHash} connections=${activeSockets.length + 1}`);
    } catch {
      safeClose(server, 1011, "room_info send failed");
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
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
      console.log(`[relay] rate limited roomHash=${attachment.roomHash}`);
      safeClose(socket, 4008, "rate limit exceeded");
      return;
    }

    let parsed;
    try {
      parsed = JSON.parse(message);
    } catch {
      return;
    }

    if (!isValidTap(parsed, attachment.roomId)) {
      return;
    }

    if (attachment.deviceId === null) {
      attachment.deviceId = parsed.deviceId;
      socket.serializeAttachment(attachment);
    } else if (attachment.deviceId !== parsed.deviceId) {
      safeClose(socket, 4000, "deviceId changed");
      return;
    }

    const payload = canonicalTap(parsed);
    let forwarded = 0;
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === socket || peer.readyState !== 1) continue;
      try {
        peer.send(payload);
        forwarded += 1;
      } catch {
        safeClose(peer, 1011, "forward failed");
      }
    }

    console.log(`[relay] forwarded=${forwarded} roomHash=${attachment.roomHash}`);
  }

  webSocketClose(_socket, code, _reason, wasClean) {
    console.log(`[relay] disconnected code=${code} clean=${wasClean}`);
  }

  webSocketError(socket) {
    safeClose(socket, 1011, "websocket error");
  }
}
