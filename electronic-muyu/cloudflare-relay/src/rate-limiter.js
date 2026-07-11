import { DurableObject } from "cloudflare:workers";
import { jsonResponse } from "./http.js";
import { isPlainObject } from "./protocol.js";

export class RequestRateLimiter extends DurableObject {
  async fetch(request) {
    if (request.method !== "POST") return jsonResponse({ allowed: false }, 405);
    let input;
    try {
      input = await request.json();
    } catch {
      return jsonResponse({ allowed: false }, 400);
    }
    if (!isPlainObject(input)
      || !Number.isSafeInteger(input.now)
      || !Number.isInteger(input.limit)
      || !Number.isInteger(input.windowMs)
      || input.limit < 1
      || input.limit > 10_000
      || input.windowMs < 100
      || input.windowMs > 3_600_000) {
      return jsonResponse({ allowed: false }, 400);
    }

    const result = await this.ctx.storage.transaction(async (transaction) => {
      const previous = await transaction.get("bucket");
      const reset = !previous
        || input.now < previous.startedAt
        || input.now - previous.startedAt >= input.windowMs;
      const next = reset
        ? { startedAt: input.now, count: 1 }
        : { startedAt: previous.startedAt, count: previous.count + 1 };
      await transaction.put("bucket", next);
      return { allowed: next.count <= input.limit, retryAfterMs: Math.max(0, input.windowMs - (input.now - next.startedAt)) };
    });
    return jsonResponse(result);
  }
}