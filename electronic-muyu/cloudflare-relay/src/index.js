import { errorResponse, jsonResponse, readStrictJson } from "./http.js";
import { routeLegacy } from "./legacy.js";
import {
  isOpaqueId,
  isValidCancelInvite,
  isValidConfirmInvite,
  isValidCreateInvite,
  isValidJoinInvite,
  isValidRevokeDevice,
  shortHash
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

async function enforceRequestLimit(env, operation, hash) {
  const policy = REQUEST_LIMITS[operation];
  const id = env.RATE_LIMITS.idFromName(`${operation}:${hash}`);
  const response = await env.RATE_LIMITS.get(id).fetch("https://internal/consume", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ now: Date.now(), ...policy })
  });
  if (!response.ok) return false;
  return (await response.json()).allowed === true;
}

async function validatedBody(request, validator) {
  const parsed = await readStrictJson(request);
  if (parsed.response) return parsed;
  if (!validator(parsed.value)) return { response: errorResponse(400, "invalid_request") };
  return parsed;
}

async function forwardInvitation(env, inviteId, action, body) {
  const object = env.INVITATIONS.get(env.INVITATIONS.idFromName(inviteId));
  return object.fetch(`https://internal/${action}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...body, now: Date.now() })
  });
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

async function handleSocket(request, env, url, hash) {
  const isUpgrade = request.method === "GET"
    && request.headers.get("upgrade")?.toLowerCase() === "websocket";
  const queryKeys = [...url.searchParams.keys()];
  const pairId = url.searchParams.get("pair");
  if (!isUpgrade
    || queryKeys.length !== 1
    || queryKeys[0] !== "pair"
    || url.searchParams.getAll("pair").length !== 1
    || !isOpaqueId(pairId)) {
    return errorResponse(400, "invalid_socket_request");
  }

  const object = env.PAIRS.get(env.PAIRS.idFromName(pairId));
  const headers = new Headers(request.headers);
  headers.set("x-electronic-muyu-network", hash);
  headers.delete("cf-connecting-ip");
  const internalRequest = new Request(`https://internal/socket?pair=${encodeURIComponent(pairId)}`, {
    method: "GET",
    headers
  });
  return object.fetch(internalRequest);
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const hash = await networkHash(request);

      if (url.pathname === "/health") {
        return request.method === "GET"
          ? jsonResponse(HEALTH_BODY)
          : errorResponse(405, "method_not_allowed");
      }
      if (url.pathname === "/v1/invites") return handleCreate(request, env, hash);

      const inviteMatch = url.pathname.match(
        /^\/v1\/invites\/([A-Za-z0-9_-]+)\/(join|confirm|cancel)$/u
      );
      if (inviteMatch) {
        return handleInviteAction(request, env, hash, inviteMatch[1], inviteMatch[2]);
      }

      const revokeMatch = url.pathname.match(
        /^\/v1\/pairs\/([A-Za-z0-9_-]+)\/devices\/([A-Za-z0-9_-]+)$/u
      );
      if (revokeMatch) {
        return handleRevoke(request, env, hash, revokeMatch[1], revokeMatch[2]);
      }

      if (url.pathname === "/v1/socket") return handleSocket(request, env, url, hash);

      const legacyRequested = url.pathname === "/" || url.pathname === "/ws";
      if (legacyRequested && String(env.ALLOW_LEGACY).toLowerCase() === "true") {
        return routeLegacy(request, env, url);
      }
      return errorResponse(404, "not_found");
    } catch (error) {
      console.error(`[relay] request_failed type=${error?.name || "Error"}`);
      return errorResponse(500, "internal_error");
    }
  }
};
