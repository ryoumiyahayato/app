import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_MESSAGES_PER_WINDOW,
  PROTOCOL_VERSION,
  RATE_LIMIT_WINDOW_MS,
  canonicalTap,
  constantTimeEqual,
  isValidHello,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "../src/protocol.js";

test("room validation", () => {
  assert.equal(isValidRoomId("test-room"), true);
  assert.equal(isValidRoomId(""), false);
  assert.equal(isValidRoomId("a".repeat(65)), false);
  assert.equal(isValidRoomId("bad\nroom"), false);
});

test("hello validation binds room and protocol", () => {
  const hello = { type: "hello", pairId: "room-a", deviceId: "device-a", protocolVersion: PROTOCOL_VERSION };
  assert.equal(isValidHello(hello, "room-a"), true);
  assert.equal(isValidHello({ ...hello, pairId: "room-b" }, "room-a"), false);
  assert.equal(isValidHello({ ...hello, protocolVersion: 1 }, "room-a"), false);
});

test("tap validation binds registered device", () => {
  const tap = { type: "tap", pairId: "room-a", deviceId: "device-a", timestamp: 123 };
  assert.equal(isValidTap(tap, "room-a", "device-a"), true);
  assert.equal(isValidTap(tap, "room-a", "device-b"), false);
  assert.equal(isValidTap({ ...tap, timestamp: 0 }, "room-a", "device-a"), false);
});

test("canonical tap strips unknown fields", () => {
  const encoded = canonicalTap({
    type: "tap", pairId: "room-a", deviceId: "device-a", timestamp: 123, injected: "ignored"
  });
  assert.deepEqual(JSON.parse(encoded), {
    type: "tap", pairId: "room-a", deviceId: "device-a", timestamp: 123
  });
});

test("rate window", () => {
  const startedAt = 1_000;
  let state = { windowStartedAt: startedAt, messagesInWindow: 0 };
  for (let index = 0; index < MAX_MESSAGES_PER_WINDOW; index += 1) {
    state = updateRateWindow(state, startedAt + 1);
    assert.equal(state.allowed, true);
  }
  assert.equal(updateRateWindow(state, startedAt + 2).allowed, false);
  assert.equal(updateRateWindow(state, startedAt + RATE_LIMIT_WINDOW_MS).allowed, true);
});

test("constant time equality", () => {
  assert.equal(constantTimeEqual("secret", "secret"), true);
  assert.equal(constantTimeEqual("secret", "secrex"), false);
  assert.equal(constantTimeEqual("secret", "secret-long"), false);
});
