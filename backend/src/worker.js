const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
const MAX_BODY_BYTES = 9 * 1024 * 1024;

export function analysisSchema() {
  const nullableString = { anyOf: [{ type: "string" }, { type: "null" }] };
  const nullableInteger = { anyOf: [{ type: "integer" }, { type: "null" }] };
  const region = {
    anyOf: [{
      type: "object",
      properties: { left: { type: "number" }, top: { type: "number" }, right: { type: "number" }, bottom: { type: "number" } },
      required: ["left", "top", "right", "bottom"], additionalProperties: false
    }, { type: "null" }]
  };
  return {
    type: "object",
    properties: {
      matches: {
        type: "array", maxItems: 20, items: {
          type: "object",
          properties: {
            exactModel: { type: "string" }, priceIls: nullableInteger, branch: nullableString,
            modelEvidence: { type: "string" }, priceEvidence: { type: "string" },
            association: { type: "string", enum: ["same_tile", "labeled_pair", "nearby_only", "unrelated", "unknown"] },
            productRegion: region, priceRegion: region
          },
          required: ["exactModel", "priceIls", "branch", "modelEvidence", "priceEvidence", "association", "productRegion", "priceRegion"],
          additionalProperties: false
        }
      }
    }, required: ["matches"], additionalProperties: false
  };
}

export function validateClientPayload(value) {
  if (!value || typeof value !== "object") throw new Error("Invalid JSON body");
  if (!Number.isSafeInteger(value.chatId) || !Number.isSafeInteger(value.messageId)) throw new Error("Invalid Telegram identifiers");
  if (!Array.isArray(value.watchedProducts) || value.watchedProducts.length < 1 || value.watchedProducts.length > 20) throw new Error("watchedProducts must contain 1-20 entries");
  if (value.watchedProducts.some(x => typeof x !== "string" || x.length > 120)) throw new Error("Invalid watched product");
  if (typeof value.imageBase64 !== "string" || value.imageBase64.length > Math.ceil(MAX_IMAGE_BYTES * 4 / 3) + 8) throw new Error("Image exceeds upload limit");
  const estimatedBytes = Math.floor(value.imageBase64.length * 3 / 4);
  if (estimatedBytes < 1 || estimatedBytes > MAX_IMAGE_BYTES) throw new Error("Image exceeds upload limit");
  if (value.mimeType !== "image/jpeg" && value.mimeType !== "image/png" && value.mimeType !== "image/webp") throw new Error("Unsupported image type");
  if (String(value.caption || "").length > 4000 || String(value.ocrEvidence || "").length > 8000) throw new Error("Text evidence too large");
  return value;
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

async function tokenMatches(supplied, expected) {
  if (!supplied || !expected) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(supplied)), crypto.subtle.digest("SHA-256", enc.encode(expected))]);
  const av = new Uint8Array(a), bv = new Uint8Array(b); let diff = 0;
  for (let i = 0; i < av.length; i++) diff |= av[i] ^ bv[i];
  return diff === 0;
}

function outputText(response) {
  for (const item of response.output || []) for (const content of item.content || []) if (content.type === "output_text") return content.text;
  throw new Error("OpenAI response did not contain structured output text");
}

function prompt(input) {
  return `Analyze this Telegram retail image conservatively. Watched exact variants: ${JSON.stringify(input.watchedProducts)}.
Identify only products visibly present. Treat Pro, Max, Complete and base variants as different exact models. For each price, require a visible same-tile or explicit labeled-pair relationship to the product; otherwise priceIls must be null. Never use an accessory, installment, crossed-out, or adjacent-column price. Branch may come from the caption first, otherwise the image; return null when uncertain. Coordinates are normalized 0..1. Do not infer missing text.
Caption: ${String(input.caption || "").slice(0, 4000)}
Local OCR lines and boxes: ${JSON.stringify(input.ocrLines || []).slice(0, 12000)}
Local OCR text: ${String(input.ocrEvidence || "").slice(0, 8000)}`;
}

async function rateLimit(request, env) {
  if (!env.RATE_LIMITS) throw new Error("RATE_LIMITS binding is required");
  const day = new Date().toISOString().slice(0, 10);
  const client = request.headers.get("cf-connecting-ip") || "unknown";
  const key = `daily:${day}:${client}`;
  const count = Number(await env.RATE_LIMITS.get(key) || 0);
  const limit = Math.max(1, Number(env.DAILY_REQUEST_LIMIT || 100));
  if (count >= limit) return false;
  await env.RATE_LIMITS.put(key, String(count + 1), { expirationTtl: 172800 });
  return true;
}

export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname === "/health") return json({ ok: true, modelConfigured: Boolean(env.OPENAI_MODEL) });
    if (request.method !== "POST" || new URL(request.url).pathname !== "/analyze") return json({ error: "Not found" }, 404);
    if (!env.OPENAI_API_KEY || !env.OPENAI_MODEL || !env.PROXY_AUTH_TOKEN || !env.VISION_CACHE) return json({ error: "Backend is not fully configured" }, 503);
    const supplied = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
    if (!(await tokenMatches(supplied, env.PROXY_AUTH_TOKEN))) return json({ error: "Unauthorized" }, 401);
    const contentLength = Number(request.headers.get("content-length") || 0);
    if (contentLength > MAX_BODY_BYTES) return json({ error: "Request too large" }, 413);
    if (!(await rateLimit(request, env))) return json({ error: "Daily request limit reached" }, 429);
    let input;
    try {
      const body = await request.text();
      if (body.length > MAX_BODY_BYTES) return json({ error: "Request too large" }, 413);
      input = validateClientPayload(JSON.parse(body));
    } catch (e) { return json({ error: e.message }, 400); }
    const cacheKey = `v1:${input.imageIdentifier}:${env.OPENAI_MODEL}:${input.promptVersion}`;
    const cached = await env.VISION_CACHE.get(cacheKey, "json");
    if (cached) return json({ ...cached, estimatedCostUsd: 0, cached: true });
    const apiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "authorization": `Bearer ${env.OPENAI_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({
        model: env.OPENAI_MODEL, store: false, max_output_tokens: 1800,
        input: [{ role: "user", content: [
          { type: "input_text", text: prompt(input) },
          { type: "input_image", image_url: `data:${input.mimeType};base64,${input.imageBase64}`, detail: "high" }
        ] }],
        text: { format: { type: "json_schema", name: "telegram_offer_analysis", strict: true, schema: analysisSchema() } }
      })
    });
    if (!apiResponse.ok) return json({ error: "Image model request failed", upstreamStatus: apiResponse.status }, apiResponse.status === 429 ? 429 : 502);
    const raw = await apiResponse.json();
    let structured;
    try { structured = JSON.parse(outputText(raw)); } catch (e) { return json({ error: e.message }, 502); }
    const inputRate = Number(env.INPUT_COST_PER_MILLION || 0), outputRate = Number(env.OUTPUT_COST_PER_MILLION || 0);
    const estimatedCostUsd = ((raw.usage?.input_tokens || 0) * inputRate + (raw.usage?.output_tokens || 0) * outputRate) / 1_000_000;
    const result = { ...structured, modelVersion: env.OPENAI_MODEL, promptVersion: input.promptVersion,
      analysisTimestamp: Date.now(), estimatedCostUsd, cached: false };
    await env.VISION_CACHE.put(cacheKey, JSON.stringify(result), { expirationTtl: Number(env.CACHE_TTL_SECONDS || 2592000) });
    return json(result);
  }
};
