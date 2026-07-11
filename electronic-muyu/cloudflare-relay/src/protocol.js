export const PROTOCOL_VERSION = 1;
export const INVITE_TTL_MS = 120_000;
export const MAX_HTTP_BODY_BYTES = 16 * 1024;
export const MAX_WEBSOCKET_MESSAGE_BYTES = 4096;
export const AUTH_TIMEOUT_MS = 5_000;
export const RATE_LIMIT_WINDOW_MS = 10_000;
export const MAX_MESSAGES_PER_WINDOW = 60;
export const PROTOCOL_VERSION = 2;

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/u;
const DEVICE_NAME_CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/u;
const textEncoder = new TextEncoder();

export function isValidRoomId(value) {
  return typeof value === "string" && value.length >= 1
    && value.length <= MAX_ROOM_ID_LENGTH && !CONTROL_CHARACTERS.test(value);
}

export function isValidDeviceId(value) {
  return typeof value === "string" && value.length >= 1
    && value.length <= MAX_DEVICE_ID_LENGTH && !CONTROL_CHARACTERS.test(value);
}

export function isValidHello(value, roomId) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    && value.type === "hello" && value.pairId === roomId
    && isValidDeviceId(value.deviceId) && value.protocolVersion === PROTOCOL_VERSION;
}

export function isValidTap(value, roomId, deviceId = value?.deviceId) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    && value.type === "tap" && value.pairId === roomId
    && value.deviceId === deviceId && isValidDeviceId(value.deviceId)
    && Number.isSafeInteger(value.timestamp) && value.timestamp > 0;
}

export function isValidRevokeDevice(value) {
  return hasExactKeys(value, ["version", "token"])
    && value.version === PROTOCOL_VERSION
    && isSecret(value.token);
}

export function isValidAuth(value, pairId) {
  return hasExactKeys(value, ["type", "version", "pairId", "deviceId", "token"])
    && value.type === "auth"
    && value.version === PROTOCOL_VERSION
    && value.pairId === pairId
    && isOpaqueId(value.pairId)
    && isOpaqueId(value.deviceId)
    && isSecret(value.token);
}

export function isValidEncryptedTap(value, pairId, deviceId) {
  return hasExactKeys(value, [
    "type",
    "version",
    "pairId",
    "sender",
    "counter",
    "iv",
    "ciphertext"
  ])
    && value.type === "encrypted_tap"
    && value.version === PROTOCOL_VERSION
    && value.pairId === pairId
    && value.sender === deviceId
    && Number.isSafeInteger(value.counter)
    && value.counter > 0
    && decodeBase64Url(value.iv, 12) !== null
    && (() => {
      const ciphertext = decodeBase64Url(value.ciphertext);
      return ciphertext !== null && ciphertext.byteLength >= 16 && ciphertext.byteLength <= 1024;
    })();
}

export function canonicalEncryptedTap(value) {
  return JSON.stringify({
    type: "tap", pairId: value.pairId, deviceId: value.deviceId, timestamp: value.timestamp
  });
}

export function updateRateWindow(state, now) {
  const windowStartedAt = Number.isSafeInteger(state?.windowStartedAt) ? state.windowStartedAt : now;
  const messagesInWindow = Number.isSafeInteger(state?.messagesInWindow) ? state.messagesInWindow : 0;
  if (now - windowStartedAt >= RATE_LIMIT_WINDOW_MS || now < windowStartedAt) {
    return { windowStartedAt: now, messagesInWindow: 1, allowed: true };
  }
  const nextCount = messagesInWindow + 1;
  return { windowStartedAt, messagesInWindow: nextCount, allowed: nextCount <= MAX_MESSAGES_PER_WINDOW };
}

export function constantTimeEqual(left, right) {
  if (typeof left !== "string" || typeof right !== "string") return false;
  const maxLength = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

export async function sha256Base64Url(value) {
  const bytes = typeof value === "string" ? textEncoder.encode(value) : value;
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return encodeBase64Url(new Uint8Array(digest));
}

export async function shortHash(value) {
  const digest = decodeBase64Url(await sha256Base64Url(value));
  return Array.from(digest.slice(0, 4))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function randomBase64Url(byteLength) {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

function appendLengthPrefixed(parts, bytes) {
  if (!(bytes instanceof Uint8Array)) throw new Error("invalid transcript field");
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, bytes.byteLength, false);
  parts.push(length, bytes);
}

export function canonicalTranscriptBytes(transcript) {
  const parts = [];
  appendLengthPrefixed(parts, textEncoder.encode(String(transcript.version)));
  appendLengthPrefixed(parts, decodeBase64Url(transcript.inviteId, 16));
  appendLengthPrefixed(parts, decodeBase64Url(transcript.inviterPublicKey, 91));
  appendLengthPrefixed(parts, decodeBase64Url(transcript.joinerPublicKey, 91));
  appendLengthPrefixed(parts, decodeBase64Url(transcript.pairId, 16));

  const totalLength = parts.reduce((sum, part) => sum + part.byteLength, 0);
  const output = new Uint8Array(totalLength);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.byteLength;
  }
  return output;
}

export async function computeSas(transcript) {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", canonicalTranscriptBytes(transcript))
  );
  const firstTwentyBits = (digest[0] << 12) | (digest[1] << 4) | (digest[2] >> 4);
  return String(firstTwentyBits % 1_000_000).padStart(6, "0");
}

export function isInviteExpired(expiresAt, now) {
  return !Number.isSafeInteger(expiresAt)
    || !Number.isSafeInteger(now)
    || now >= expiresAt;
}
