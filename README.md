# ProductWatcher — Telegram deal monitor (Android)

This Android application monitors Telegram channels selected by the user through the local TDLib client and reads text from posted photos on-device using bundled Google ML Kit OCR. An optional, explicitly enabled HTTPS proxy can add cloud image analysis without placing an OpenAI API key or Telegram credentials in the APK.

## Features

- Connects directly to the account's Telegram channels using TDLib; **no forwarding and no external server**.
- Watches several product names and several selected channels.
- Distinguishes `Dreame X60 Ultra` / `X60 Ultra Complete` from `Dreame X60 Pro Ultra`, `X60 Max Ultra`, `X50 Ultra` and `X40 Ultra`.
- Associates prices to product names using the locations of recognized text lines; with multiple prices, reports *price unknown* when assignment is ambiguous rather than taking the first price.
- Recall-oriented candidate screening recognizes partial/split model hints. Optional cloud analysis upgrades candidates only after exact-model, plausible-price and same-tile evidence passes deterministic checks on-device.
- Cloud failures remain pending/failed instead of falling back to speculative prices. Only image-supported, high-confidence matches alert; OCR-only prices remain unverified.
- Durable app-private source images and thumbnails remain available after optional TDLib cache cleanup. Match history includes OCR/image evidence, source links, regions, model version and a bounded audit trail.
- Background monitoring starts automatically when a channel is selected; explicit Stop is remembered.
- On app open, restarts monitoring if it was enabled. On reboot, WorkManager re-registers periodic catch-up; Android 15+ may defer scans and impose a foreground-service quota.
- New watched product automatically triggers bounded full Telegram-history rescan; progress/cursor survives process recreation. Recent posts are checked first, historical messages in groups of up to four pages (400 posts) per scan cycle. Missing-post gaps after long background suspension are backfilled separately.
- At most three photos are downloaded/OCR'd concurrently. Failed downloads or OCR processing retry on later cycles (last 150 failed IDs per channel tracked).
- After successful OCR, can delete the **TDLib local cached file only**. Never deletes a Telegram post or a user's gallery photo.
- Status for each watched product: last activity, checked posts, matches, alerts, last matching product/price. Channel status: counts, OCR preview, backfill progress.

## Install/update through GitHub Codespaces

From your existing `tomersaporta/ProductWatcher` Codespace, upload the patch ZIP in the chat into the repository root, then:

```bash
unzip -o ProductWatcher-Reliability-Fix-Patch.zip
bash apply_patch.sh
git add -A
git commit -m "Repair Telegram OCR, product matching, rescan and monitoring"
git push origin main
```

Your existing Telegram session and watchlist are saved in Android's local app data and are not reset by installing the debug APK over the old installation (same applicationId and debug signing key). Do not uninstall the app or clear its data if you want to keep the session.

GitHub Actions runs Kotlin regression tests and creates the `ProductWatcher-debug-apk` artifact. Inspect Actions for `BUILD SUCCESSFUL` before installing.

## Optional cloud image analysis

Deploy the authenticated serverless proxy documented in [`backend/README.md`](backend/README.md), then use **Configure cloud analysis** in the app. Cloud analysis is disabled by default and monitoring continues locally when it is unavailable. The backend model is selected through its `OPENAI_MODEL` environment variable so deployments can use a currently supported image-input model with Structured Outputs without shipping a model identifier in the APK.

## Verification

JVM-only model matching regression tests also run without an Android SDK:

```bash
kotlinc app/src/main/java/com/productwatcher/app/{Matcher.kt,OfferExtractor.kt} MatcherRegression.kt -include-runtime -d matcher-test.jar
java -jar matcher-test.jar
```

On Android: open the app, verify the monitor is enabled and your Telegram source is listed. Click **Rescan full Telegram history** if the image was posted before this version was installed, then watch the per-channel history cursor, last OCR text and per-product counters. On the supplied multi-product sample, a successful OCR should identify `X60 Ultra Complete` and, if it can read the tag, associate **₪3,799**. The separate `X50 Ultra` and `X40 Ultra Complete` tags show ₪2,450 and ₪1,790. The sample must **not** trigger an `X60 Pro Ultra` alert.

## Known limits

- Local OCR may miss Hebrew text; caption-derived branches are preferred, image/OCR evidence is used only when present, and unknown values remain unknown.
- Image OCR accuracy varies with photograph resolution, glare and font size; price/model matching is only as good as the recognized text. OCR snippets are for diagnosis and stay on-device unless the user explicitly shares a status report.
- Android background execution is best effort: exact 24/7 live monitoring and instant notification are not guaranteed by WorkManager or foreground-service quotas.
- Automated tests use synthetic/fixture OCR geometry and simulated cloud output. They do not establish perfect accuracy on every real retail image; maintain a labeled evaluation set before changing alert thresholds.
