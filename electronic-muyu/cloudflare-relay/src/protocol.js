export const MAX_ROOM_ID_LENGTH = 64;
export const MAX_DEVICE_ID_LENGTH = 128;
export const MAX_MESSAGE_BYTES = 4096;
export const RATE_LIMIT_WINDOW_MS = 10_000;
export const MAX_MESSAGES_PER_WINDOW = 60;
export const PROTOCOL_VERSION = 2;

const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/u;

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

export function canonicalTap(value) {
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
