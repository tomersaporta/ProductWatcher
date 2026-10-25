import test from "node:test";
import assert from "node:assert/strict";
import { analysisSchema, validateClientPayload } from "../src/worker.js";

const valid = () => ({ chatId: -1001, messageId: 22, watchedProducts: ["Dreame X60 Ultra Complete"],
  imageBase64: Buffer.from("image").toString("base64"), mimeType: "image/jpeg", caption: "", ocrEvidence: "" });

test("strict schema includes exact model, nullable price and regions", () => {
  const item = analysisSchema().properties.matches.items;
  assert.equal(item.additionalProperties, false);
  assert.ok(item.required.includes("exactModel"));
  assert.ok(item.required.includes("priceRegion"));
  assert.deepEqual(item.properties.priceIls.anyOf.map(x => x.type), ["integer", "null"]);
});

test("client validation accepts a bounded candidate image", () => assert.equal(validateClientPayload(valid()).messageId, 22));
test("client validation rejects too many watched products", () => {
  const body = valid(); body.watchedProducts = Array(21).fill("model");
  assert.throws(() => validateClientPayload(body), /1-20/);
});
test("client validation rejects unsupported image types", () => {
  const body = valid(); body.mimeType = "image/svg+xml";
  assert.throws(() => validateClientPayload(body), /Unsupported/);
});
