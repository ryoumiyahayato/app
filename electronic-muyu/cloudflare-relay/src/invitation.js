import { DurableObject } from "cloudflare:workers";
import { errorResponse, jsonResponse } from "./http.js";
import {
  INVITE_TTL_MS,
  PROTOCOL_VERSION,
  constantTimeEqual,
  decodeBase64Url,
  isInviteExpired,
  randomBase64Url,
  sha256Base64Url,
  shortHash
} from "./protocol.js";

const TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1000;

async function hashSecret(secret) {
  const bytes = decodeBase64Url(secret, 32);
  return bytes ? sha256Base64Url(bytes) : null;
}

function transcriptFor(state) {
  if (!state.joiner || !state.pairId) return null;
  return {
    version: PROTOCOL_VERSION,
    inviteId: state.inviteId,
    pairId: state.pairId,
    expiresAt: state.expiresAt,
    inviter: {
      deviceId: state.inviter.deviceId,
      deviceName: state.inviter.deviceName,
      publicKey: state.inviter.publicKey
    },
    joiner: {
      deviceId: state.joiner.deviceId,
      deviceName: state.joiner.deviceName,
      publicKey: state.joiner.publicKey
    }
  };
}

function statusBody(state) {
  const transcript = transcriptFor(state);
  return {
    ok: true,
    version: PROTOCOL_VERSION,
    status: state.status,
    expiresAt: state.expiresAt,
    ...(transcript ? { transcript } : {}),
    confirmations: {
      inviter: state.inviter?.confirmed === true,
      joiner: state.joiner?.confirmed === true
    }
  };
}

function terminalResponse(state) {
  const code = state?.status === "cancelled"
    ? "invite_cancelled"
    : state?.status === "rejected"
      ? "pairing_rejected"
      : state?.status === "paired"
        ? "invite_already_used"
        : "invite_expired";
  return errorResponse(410, code);
}

export class InvitationSession extends DurableObject {
  async fetch(request) {
    const path = new URL(request.url).pathname;
    let input;
    try {
      input = await request.json();
    } catch {
      return errorResponse(400, "invalid_internal_request");
    }

    if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
    if (path === "/create") return this.create(input);
    if (path === "/join") return this.join(input);
    if (path === "/confirm") return this.confirm(input);
    if (path === "/cancel") return this.cancel(input);
    return errorResponse(404, "not_found");
  }

  async create(input) {
    const existing = await this.ctx.storage.get("invite");
    if (existing) return errorResponse(409, "invite_id_conflict");

    const ownerSessionToken = randomBase64Url(32);
    const now = input.now;
    const expiresAt = now + INVITE_TTL_MS;
    const state = {
      version: PROTOCOL_VERSION,
      inviteId: input.inviteId,
      inviteSecretHash: input.inviteSecretHash,
      ownerSessionHash: await hashSecret(ownerSessionToken),
      status: "open",
      createdAt: now,
      expiresAt,
      pairId: null,
      inviter: {
        deviceId: input.inviterDeviceId,
        deviceName: input.inviterDeviceName,
        publicKey: input.inviterPublicKey,
        confirmed: false,
        accessTokenHash: null
      },
      joiner: null
    };
    await this.ctx.storage.put("invite", state);
    await this.ctx.storage.setAlarm(expiresAt);
    console.log(`[pairing] invite_created inviteHash=${await shortHash(state.inviteId)}`);
    return jsonResponse({
      ok: true,
      version: PROTOCOL_VERSION,
      inviteId: state.inviteId,
      ownerSessionToken,
      expiresAt
    }, 201);
  }

  async join(input) {
    const suppliedSecretHash = await hashSecret(input.inviteSecret);
    const joinSessionToken = randomBase64Url(32);
    const joinSessionHash = await hashSecret(joinSessionToken);
    const now = input.now;

    const result = await this.ctx.storage.transaction(async (transaction) => {
      const state = await transaction.get("invite");
      if (!state) return { error: errorResponse(404, "invite_not_found") };
      if (["expired", "cancelled", "rejected", "paired"].includes(state.status)) {
        return { error: terminalResponse(state) };
      }
      if (isInviteExpired(state.expiresAt, now)) {
        await transaction.put("invite", this.tombstone(state, "expired", now));
        return { error: errorResponse(410, "invite_expired") };
      }
      if (state.status !== "open" || state.joiner) {
        return { error: errorResponse(409, "invite_already_redeemed") };
      }
      if (!constantTimeEqual(suppliedSecretHash, state.inviteSecretHash)) {
        return { error: errorResponse(401, "invalid_invite_secret") };
      }
      if (input.joinerDeviceId === state.inviter.deviceId
        || input.joinerPublicKey === state.inviter.publicKey) {
        return { error: errorResponse(409, "same_device_not_allowed") };
      }

      state.status = "joined";
      state.pairId = randomBase64Url(16);
      state.joiner = {
        deviceId: input.joinerDeviceId,
        deviceName: input.joinerDeviceName,
        publicKey: input.joinerPublicKey,
        sessionHash: joinSessionHash,
        confirmed: false,
        accessTokenHash: null
      };
      await transaction.put("invite", state);
      return { state };
    });

    if (result.error) return result.error;
    console.log(`[pairing] invite_joined inviteHash=${await shortHash(result.state.inviteId)}`);
    return jsonResponse({
      ...statusBody(result.state),
      joinSessionToken
    });
  }

  async confirm(input) {
    let state = await this.ctx.storage.get("invite");
    if (!state) return errorResponse(404, "invite_not_found");
    if (["expired", "cancelled", "rejected"].includes(state.status)) {
      return terminalResponse(state);
    }
    if (isInviteExpired(state.expiresAt, input.now) && state.status !== "paired") {
      await this.expire(state, input.now);
      return errorResponse(410, "invite_expired");
    }

    const role = await this.authenticatedRole(state, input.deviceId, input.sessionToken);
    if (!role) return errorResponse(401, "invalid_pairing_session");
    if (state.status === "paired") return jsonResponse(statusBody(state));
    if (!state.joiner) {
      if (input.decision !== "status") return errorResponse(409, "waiting_for_joiner");
      return jsonResponse(statusBody(state));
    }

    if (input.decision === "reject") {
      await this.ctx.storage.put("invite", this.tombstone(state, "rejected", input.now));
      console.log(`[pairing] sas_rejected inviteHash=${await shortHash(state.inviteId)}`);
      return terminalResponse({ status: "rejected" });
    }

    if (input.decision === "confirm") {
      const device = role === "inviter" ? state.inviter : state.joiner;
      const peer = role === "inviter" ? state.joiner : state.inviter;
      if (peer.accessTokenHash && constantTimeEqual(peer.accessTokenHash, input.accessTokenHash)) {
        return errorResponse(409, "access_tokens_must_differ");
      }
      device.confirmed = true;
      device.accessTokenHash = input.accessTokenHash;
      state.status = "joined";
      await this.ctx.storage.put("invite", state);
    }

    if (state.inviter.confirmed && state.joiner.confirmed) {
      const initialized = await this.initializePair(state);
      if (!initialized) return errorResponse(503, "pair_initialization_failed");
      state.status = "paired";
      await this.ctx.storage.put("invite", state);
      console.log(`[pairing] pair_completed pairHash=${await shortHash(state.pairId)}`);
    }
    return jsonResponse(statusBody(state));
  }

  async cancel(input) {
    const state = await this.ctx.storage.get("invite");
    if (!state) return errorResponse(404, "invite_not_found");
    if (["expired", "cancelled", "rejected"].includes(state.status)) {
      return terminalResponse(state);
    }
    if (state.status === "paired") return errorResponse(409, "pair_already_created");

    const role = await this.authenticatedRole(state, input.deviceId, input.sessionToken);
    if (role !== "inviter") return errorResponse(401, "invalid_pairing_session");
    await this.ctx.storage.put("invite", this.tombstone(state, "cancelled", input.now));
    await this.ctx.storage.setAlarm(input.now + TOMBSTONE_TTL_MS);
    console.log(`[pairing] invite_cancelled inviteHash=${await shortHash(state.inviteId)}`);
    return jsonResponse({ ok: true, status: "cancelled" });
  }

  async authenticatedRole(state, deviceId, sessionToken) {
    const suppliedHash = await hashSecret(sessionToken);
    if (deviceId === state.inviter.deviceId
      && constantTimeEqual(suppliedHash, state.ownerSessionHash)) return "inviter";
    if (state.joiner
      && deviceId === state.joiner.deviceId
      && constantTimeEqual(suppliedHash, state.joiner.sessionHash)) return "joiner";
    return null;
  }

  async initializePair(state) {
    const object = this.env.PAIRS.get(this.env.PAIRS.idFromName(state.pairId));
    const response = await object.fetch("https://internal/initialize", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        version: PROTOCOL_VERSION,
        pairId: state.pairId,
        createdAt: Date.now(),
        devices: [
          {
            slot: 0,
            deviceId: state.inviter.deviceId,
            deviceName: state.inviter.deviceName,
            publicKey: state.inviter.publicKey,
            accessTokenHash: state.inviter.accessTokenHash
          },
          {
            slot: 1,
            deviceId: state.joiner.deviceId,
            deviceName: state.joiner.deviceName,
            publicKey: state.joiner.publicKey,
            accessTokenHash: state.joiner.accessTokenHash
          }
        ]
      })
    });
    return response.ok;
  }

  tombstone(state, status, now) {
    return {
      version: PROTOCOL_VERSION,
      inviteId: state.inviteId,
      status,
      expiresAt: state.expiresAt,
      purgeAt: now + TOMBSTONE_TTL_MS
    };
  }

  async expire(state, now) {
    await this.ctx.storage.put("invite", this.tombstone(state, "expired", now));
    await this.ctx.storage.setAlarm(now + TOMBSTONE_TTL_MS);
  }

  async alarm() {
    const state = await this.ctx.storage.get("invite");
    if (!state) return;
    const now = Date.now();
    if (state.purgeAt && now >= state.purgeAt) {
      await this.ctx.storage.deleteAll();
      return;
    }
    if (now >= state.expiresAt) await this.expire(state, now);
  }
}
