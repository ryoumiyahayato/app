import { MAX_HTTP_BODY_BYTES } from "./protocol.js";

export const SECURITY_HEADERS = Object.freeze({
  "cache-control": "no-store",
  "content-type": "application/json; charset=utf-8",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff"
});

export function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: SECURITY_HEADERS });
}

export function errorResponse(status, code) {
  return jsonResponse({ ok: false, error: code }, status);
}

export async function readStrictJson(request) {
  const mediaType = request.headers.get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    ?.toLowerCase();
  if (mediaType !== "application/json") {
    return { response: errorResponse(415, "unsupported_media_type") };
  }

  const declaredLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_HTTP_BODY_BYTES) {
    return { response: errorResponse(413, "body_too_large") };
  }

  let bytes;
  try {
    bytes = new Uint8Array(await request.arrayBuffer());
  } catch {
    return { response: errorResponse(400, "invalid_body") };
  }
  if (bytes.byteLength > MAX_HTTP_BODY_BYTES) {
    return { response: errorResponse(413, "body_too_large") };
  }

  try {
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return { value };
  } catch {
    return { response: errorResponse(400, "invalid_json") };
  }
}

export function rejectedWebSocket(code, reason) {
  const pair = new WebSocketPair();
  const [client, server] = Object.values(pair);
  server.accept();
  server.close(code, reason);
  return new Response(null, { status: 101, webSocket: client });
}

export function safeClose(socket, code, reason) {
  try {
    socket.close(code, reason);
  } catch {
    // Closing is best effort; no sensitive state is logged here.
  }
}

export async function parseJsonResponse(response) {
  const value = await response.json();
  return { value, status: response.status };
}