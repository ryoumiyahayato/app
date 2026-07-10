import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_MESSAGES_PER_WINDOW,
  RATE_LIMIT_WINDOW_MS,
  canonicalTap,
  constantTimeEqual,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "../src/protocol.js";

test("room validation accepts normal IDs and rejects invalid values", () => {
  assert.equal(isValidRoomId("test-room"), true);
  assert.equal(isValidRoomId("房间-A_1"), true);
  assert.equal(isValidRoomId(""), false);
  assert.equal(isValidRoomId("a".repeat(65)), false);
  assert.equal(isValidRoomId("bad\nroom"), false);
  assert.equal(isValidRoomId(null), false);
});

test("tap validation binds pairId and validates fields", () => {
  const tap = {
    type: "tap",
    pairId: "room-a",
    deviceId: "device-a",
    timestamp: 123
  };
  assert.equal(isValidTap(tap, "room-a"), true);
  assert.equal(isValidTap({ ...tap, pairId: "room-b" }, "room-a"), false);
  assert.equal(isValidTap({ ...tap, timestamp: 0 }, "room-a"), false);
  assert.equal(isValidTap({ ...tap, deviceId: "" }, "room-a"), false);
});

test("canonical tap strips unknown fields", () => {
  const encoded = canonicalTap({
    type: "tap",
    pairId: "room-a",
    deviceId: "device-a",
    timestamp: 123,
    injected: "ignored"
  });
  assert.deepEqual(JSON.parse(encoded), {
    type: "tap",
    pairId: "room-a",
    deviceId: "device-a",
    timestamp: 123
  });
});

test("rate window enforces the configured limit and resets", () => {
  const startedAt = 1_000;
  let state = { windowStartedAt: startedAt, messagesInWindow: 0 };
  for (let index = 0; index < MAX_MESSAGES_PER_WINDOW; index += 1) {
    state = updateRateWindow(state, startedAt + 1);
    assert.equal(state.allowed, true);
  }
  state = updateRateWindow(state, startedAt + 2);
  assert.equal(state.allowed, false);

  const reset = updateRateWindow(state, startedAt + RATE_LIMIT_WINDOW_MS);
  assert.equal(reset.allowed, true);
  assert.equal(reset.messagesInWindow, 1);
});

test("constant-time equality handles equal, unequal and different-length strings", () => {
  assert.equal(constantTimeEqual("secret", "secret"), true);
  assert.equal(constantTimeEqual("secret", "secrex"), false);
  assert.equal(constantTimeEqual("secret", "secret-long"), false);
  assert.equal(constantTimeEqual(null, "secret"), false);
});
