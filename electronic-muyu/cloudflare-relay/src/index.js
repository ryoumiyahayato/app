import { errorResponse, jsonResponse, readStrictJson } from "./http.js";
import { routeLegacy } from "./legacy.js";
import {
  MAX_MESSAGE_BYTES,
  canonicalTap,
  constantTimeEqual,
  isValidHello,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "./protocol.js";

export { InvitationSession } from "./invitation.js";
export { PairRoom } from "./legacy.js";
export { RequestRateLimiter } from "./rate-limiter.js";
export { SecurePair } from "./secure-pair.js";

const HEALTH_BODY = Object.freeze({
  ok: true,
  service: "electronic-muyu-relay",
  protocolVersion: 1,
  runtime: "cloudflare-workers-durable-objects"
});
const MAX_PENDING_AND_ACTIVE_SOCKETS = 6;
const HELLO_TIMEOUT_MS = 5_000;

const REQUEST_LIMITS = Object.freeze({
  create: { limit: 10, windowMs: 60_000 },
  join: { limit: 20, windowMs: 60_000 },
  confirm: { limit: 120, windowMs: 60_000 },
  cancel: { limit: 30, windowMs: 60_000 },
  revoke: { limit: 30, windowMs: 60_000 }
});

async function networkHash(request) {
  const address = request.headers.get("cf-connecting-ip") || "local-development";
  return shortHash(address);
}

function safeClose(socket, code, reason) {
  try {
    socket.close(code, reason);
  } catch {
    // The socket may already be closing or closed.
  }
}

async function validatedBody(request, validator) {
  const parsed = await readStrictJson(request);
  if (parsed.response) return parsed;
  if (!validator(parsed.value)) return { response: errorResponse(400, "invalid_request") };
  return parsed;
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

async function handleCreate(request, env, hash) {
  if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
  if (!await enforceRequestLimit(env, "create", hash)) return errorResponse(429, "rate_limited");
  const parsed = await validatedBody(request, isValidCreateInvite);
  if (parsed.response) return parsed.response;
  return forwardInvitation(env, parsed.value.inviteId, "create", parsed.value);
}

async function handleInviteAction(request, env, hash, inviteId, action) {
  if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
  if (!isOpaqueId(inviteId)) return errorResponse(404, "invite_not_found");
  const operation = action === "join" ? "join" : action === "cancel" ? "cancel" : "confirm";
  if (!await enforceRequestLimit(env, operation, hash)) return errorResponse(429, "rate_limited");
  const validator = action === "join"
    ? isValidJoinInvite
    : action === "cancel"
      ? isValidCancelInvite
      : isValidConfirmInvite;
  const parsed = await validatedBody(request, validator);
  if (parsed.response) return parsed.response;
  return forwardInvitation(env, inviteId, action, parsed.value);
}

async function handleRevoke(request, env, hash, pairId, deviceId) {
  if (request.method !== "DELETE") return errorResponse(405, "method_not_allowed");
  if (!isOpaqueId(pairId) || !isOpaqueId(deviceId)) return errorResponse(404, "pair_not_found");
  if (!await enforceRequestLimit(env, "revoke", hash)) return errorResponse(429, "rate_limited");
  const parsed = await validatedBody(request, isValidRevokeDevice);
  if (parsed.response) return parsed.response;
  const object = env.PAIRS.get(env.PAIRS.idFromName(pairId));
  return object.fetch("https://internal/revoke", {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      pairId,
      deviceId,
      token: parsed.value.token,
      now: Date.now()
    })
  });
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

  socketEntries(excludedSocket = null) {
    return this.activeSockets()
      .filter((socket) => socket !== excludedSocket)
      .map((socket) => ({ socket, attachment: this.attachment(socket) }))
      .filter(({ attachment }) => attachment && isValidRoomId(attachment.roomId));
  }

  latestRegisteredEntries(excludedSocket = null) {
    const byDeviceId = new Map();
    for (const entry of this.socketEntries(excludedSocket)) {
      const deviceId = entry.attachment.deviceId;
      if (!deviceId) continue;
      const existing = byDeviceId.get(deviceId);
      const joinedAt = Number(entry.attachment.joinedAt) || 0;
      const existingJoinedAt = Number(existing?.attachment.joinedAt) || 0;
      if (!existing || joinedAt >= existingJoinedAt) {
        byDeviceId.set(deviceId, entry);
      }
    }
    return [...byDeviceId.values()];
  }

  expirePendingSockets(now = Date.now()) {
    let nextDeadline = null;
    for (const entry of this.socketEntries()) {
      if (entry.attachment.deviceId) continue;
      const joinedAt = Number(entry.attachment.joinedAt) || now;
      const deadline = joinedAt + HELLO_TIMEOUT_MS;
      if (deadline <= now) {
        safeClose(entry.socket, 4004, "hello timeout");
      } else if (nextDeadline === null || deadline < nextDeadline) {
        nextDeadline = deadline;
      }
    }
    return nextDeadline;
  }

  async schedulePendingCleanup(nextDeadline = null) {
    const deadline = nextDeadline ?? this.expirePendingSockets();
    const currentAlarm = await this.ctx.storage.getAlarm();
    if (deadline === null) {
      if (currentAlarm !== null) await this.ctx.storage.deleteAlarm();
      return;
    }
    if (currentAlarm === null || deadline < currentAlarm) {
      await this.ctx.storage.setAlarm(deadline);
    }
  }

  broadcastRoomState(roomId) {
    const registered = this.latestRegisteredEntries();
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

    const now = Date.now();
    const nextDeadline = this.expirePendingSockets(now);
    const countableSockets = this.socketEntries().filter(({ attachment }) => {
      return Boolean(attachment.deviceId)
        || (Number(attachment.joinedAt) || now) + HELLO_TIMEOUT_MS > now;
    });
    if (countableSockets.length >= MAX_PENDING_AND_ACTIVE_SOCKETS) {
      await this.schedulePendingCleanup(nextDeadline);
      return rejectedWebSocket(4003, "room handshake capacity reached");
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
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
    await this.schedulePendingCleanup(now + HELLO_TIMEOUT_MS);

export default {
  async fetch(request, env) {
    try {
      server.send(JSON.stringify({
        type: "hello_required",
        protocolVersion: 2,
        timestamp: now
      }));
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

      const inviteMatch = url.pathname.match(
        /^\/v1\/invites\/([A-Za-z0-9_-]+)\/(join|confirm|cancel)$/u
      );
      if (inviteMatch) {
        return handleInviteAction(request, env, hash, inviteMatch[1], inviteMatch[2]);
      }

    if (!attachment.deviceId) {
      if (!isValidHello(parsed, attachment.roomId)) {
        safeClose(socket, 4004, "hello required");
        return;
      }

      const registered = this.socketEntries(socket)
        .filter((entry) => entry.attachment.deviceId);
      const sameDeviceEntries = registered.filter(
        (entry) => entry.attachment.deviceId === parsed.deviceId
      );
      const distinctDeviceIds = new Set(
        registered.map((entry) => entry.attachment.deviceId)
      );
      if (sameDeviceEntries.length === 0 && distinctDeviceIds.size >= 2) {
        safeClose(socket, 4002, "room is full");
        return;
      }

      attachment.deviceId = parsed.deviceId;
      socket.serializeAttachment(attachment);
      for (const existing of sameDeviceEntries) {
        safeClose(existing.socket, 4004, "replaced by newer connection");
      }
      await this.schedulePendingCleanup();
      this.broadcastRoomState(attachment.roomId);
      return;
    }

    if (!isValidTap(parsed, attachment.roomId, attachment.deviceId)) return;

    const registered = this.latestRegisteredEntries();
    const currentSender = registered.find(
      (entry) => entry.attachment.deviceId === attachment.deviceId
    );
    if (!currentSender || currentSender.socket !== socket) {
      // A stale socket may briefly remain open while its replacement closes it.
      return;
    }

    const payload = canonicalTap(parsed);
    for (const peer of registered) {
      if (peer.attachment.deviceId === attachment.deviceId) continue;
      try {
        peer.socket.send(payload);
      } catch {
        safeClose(peer.socket, 1011, "forward failed");
      }
      return errorResponse(404, "not_found");
    } catch (error) {
      console.error(`[relay] request_failed type=${error?.name || "Error"}`);
      return errorResponse(500, "internal_error");
    }
  }

  async webSocketClose(socket, code, _reason, wasClean) {
    const attachment = this.attachment(socket);
    console.log(`[relay] disconnected code=${code} clean=${wasClean}`);
    await this.schedulePendingCleanup();
    if (attachment?.roomId) this.broadcastRoomState(attachment.roomId);
  }

  webSocketError(socket) {
    safeClose(socket, 1011, "websocket error");
  }

  async alarm() {
    const nextDeadline = this.expirePendingSockets(Date.now());
    if (nextDeadline !== null) {
      await this.ctx.storage.setAlarm(nextDeadline);
    }
  }
}
