import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_MESSAGES_PER_WINDOW,
  PROTOCOL_VERSION,
  RATE_LIMIT_WINDOW_MS,
  canonicalEncryptedTap,
  canonicalTranscriptBytes,
  computeSas,
  constantTimeEqual,
  isValidHello,
  isValidRoomId,
  isValidTap,
  updateRateWindow
} from "../src/protocol.js";

const bytes = (length, seed = 1) => Uint8Array.from(
  { length },
  (_, index) => (seed + index * 17) & 0xff
);
const opaque = (seed = 1) => encodeBase64Url(bytes(16, seed));
const secret = (seed = 1) => encodeBase64Url(bytes(32, seed));
const publicKey = (seed = 1) => encodeBase64Url(bytes(91, seed));

test("base64url decoder accepts only canonical unpadded values", () => {
  const encoded = opaque();
  assert.deepEqual(decodeBase64Url(encoded, 16), bytes(16));
  assert.equal(decodeBase64Url(`${encoded}=`, 16), null);
  assert.equal(decodeBase64Url("+/not-url-safe", undefined), null);
  assert.equal(decodeBase64Url(opaque(), 32), null);
});

test("invite create and join schemas are exact and size checked", () => {
  const create = {
    version: 1,
    inviteId: opaque(2),
    inviteSecretHash: secret(3),
    inviterDeviceId: opaque(4),
    inviterDeviceName: "甲的手机",
    inviterPublicKey: publicKey(5)
  };
  assert.equal(isValidCreateInvite(create), true);
  assert.equal(isValidCreateInvite({ ...create, injected: true }), false);
  assert.equal(isValidCreateInvite({ ...create, inviterDeviceName: "bad\nname" }), false);

  const join = {
    version: 1,
    inviteSecret: secret(6),
    joinerDeviceId: opaque(7),
    joinerDeviceName: "乙的手机",
    joinerPublicKey: publicKey(8)
  };
  assert.equal(isValidJoinInvite(join), true);
  assert.equal(isValidJoinInvite({ ...join, inviteSecret: opaque(6) }), false);
});

test("confirmation requires a token hash only for confirm", () => {
  const base = { version: 1, deviceId: opaque(9), sessionToken: secret(10) };
  assert.equal(isValidConfirmInvite({ ...base, decision: "status" }), true);
  assert.equal(isValidConfirmInvite({ ...base, decision: "reject" }), true);
  assert.equal(isValidConfirmInvite({ ...base, decision: "confirm" }), false);
  assert.equal(isValidConfirmInvite({
    ...base,
    decision: "confirm",
    accessTokenHash: secret(11)
  }), true);
});

test("hello validation binds room, device and protocol version", () => {
  const hello = {
    type: "hello",
    pairId: "room-a",
    deviceId: "device-a",
    protocolVersion: PROTOCOL_VERSION
  };
  assert.equal(isValidHello(hello, "room-a"), true);
  assert.equal(isValidHello({ ...hello, pairId: "room-b" }, "room-a"), false);
  assert.equal(isValidHello({ ...hello, deviceId: "" }, "room-a"), false);
  assert.equal(isValidHello({ ...hello, protocolVersion: 1 }, "room-a"), false);
  assert.equal(isValidHello(null, "room-a"), false);
});

test("tap validation binds pairId and the registered device", () => {
  const tap = {
    type: "encrypted_tap",
    version: 1,
    pairId,
    sender: deviceId,
    counter: 1,
    iv: encodeBase64Url(bytes(12, 18)),
    ciphertext: encodeBase64Url(bytes(32, 19))
  };
  assert.equal(isValidTap(tap, "room-a", "device-a"), true);
  assert.equal(isValidTap({ ...tap, pairId: "room-b" }, "room-a", "device-a"), false);
  assert.equal(isValidTap(tap, "room-a", "device-b"), false);
  assert.equal(isValidTap({ ...tap, timestamp: 0 }, "room-a", "device-a"), false);
  assert.equal(isValidTap({ ...tap, deviceId: "" }, "room-a", ""), false);
});

test("transcript encoding is length-prefixed and SAS is deterministic", async () => {
  const transcript = {
    version: 1,
    inviteId: opaque(21),
    inviterPublicKey: publicKey(22),
    joinerPublicKey: publicKey(23),
    pairId: opaque(24)
  };
  const encoded = canonicalTranscriptBytes(transcript);
  assert.equal(encoded.byteLength, 4 + 1 + 4 + 16 + 4 + 91 + 4 + 91 + 4 + 16);
  assert.match(await computeSas(transcript), /^\d{6}$/u);
  assert.equal(await computeSas(transcript), await computeSas(transcript));
  await assert.rejects(async () => computeSas({ ...transcript, pairId: "invalid" }));
});

test("rate window permits the configured burst and then resets", () => {
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

  const clockRollback = updateRateWindow(state, startedAt - 1);
  assert.equal(clockRollback.allowed, true);
  assert.equal(clockRollback.messagesInWindow, 1);
});

test("constant-time equality handles equal, unequal and invalid values", () => {
  assert.equal(constantTimeEqual("secret", "secret"), true);
  assert.equal(constantTimeEqual("secret", "secrex"), false);
  assert.equal(constantTimeEqual("secret", "secret-long"), false);
  assert.equal(constantTimeEqual(null, "secret"), false);
});
