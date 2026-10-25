# ProductWatcher image-analysis proxy

This Cloudflare Worker keeps the OpenAI key out of the APK. It accepts only authenticated, bounded candidate-image requests; Telegram credentials and the TDLib database never leave the phone.

## Deploy

1. Copy `wrangler.toml.example` to `wrangler.toml` (ignored by Git), create the two KV namespaces, and insert their IDs.
2. Choose an image-input model that supports Responses API Structured Outputs and is available to your OpenAI project. Set its exact ID as `OPENAI_MODEL`; the Worker has no silent model fallback.
3. Store secrets with `npx wrangler secret put OPENAI_API_KEY` and `npx wrangler secret put PROXY_AUTH_TOKEN`. Use a long random proxy token distinct from the OpenAI key.
4. Set daily request and OpenAI project spend limits, update the optional per-token estimate variables from current pricing, run `npm test`, then deploy with `npx wrangler deploy`.
5. In ProductWatcher, configure the HTTPS Worker URL and the proxy token, then explicitly enable cloud analysis.

The Worker sends images with `store: false`, enforces a 6 MB decoded-image limit, accepts at most 20 watched variants, applies an IP/day cap, and caches by image hash + model + prompt version. Cached records expire after `CACHE_TTL_SECONDS`. Rate-limit records expire after two days. OpenAI retention and project controls remain governed by the OpenAI account configuration.

The Android app stores source images in app-private storage. Its default cleanup window is 30 days; images referenced by retained match records are not automatically deleted. “Clean expired private images” removes only unreferenced expired files. Uninstalling or clearing app data deletes local sessions and records—do not do that when upgrading.

API construction follows the official [Responses API image-input guide](https://developers.openai.com/api/docs/guides/images-vision) and [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses). Re-check model availability and pricing before deployment.
