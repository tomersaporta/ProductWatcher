# Product Watcher — Android prototype (v0.1)

Private, on-device image scanning with local Android notifications. **No cloud upload, no Google Drive, no backend, no account required.**

## Build an APK without Android Studio

1. Create a **private** repository on GitHub, e.g. `product-watcher`.
2. Upload the **contents of this directory** to the repository root (including `.github/workflows/build-apk.yml`). The easiest way to preserve hidden `.github` files is `git init`, `git add .`, `git commit`, then push. Alternatively use GitHub's **Add file → Upload files**, ensuring `.github/workflows/build-apk.yml` is included.
3. Open **Actions → Build Android APK → Run workflow** (or push to `main`). Enable Actions for the repository if prompted.
4. When the workflow completes, download the `ProductWatcher-debug-apk` artifact and extract the ZIP.
5. Copy `app-debug.apk` to your Android phone and open it. Allow installation from this source when Android prompts you. Only install APKs built from your own trusted repository.

> This is a debug-signed APK for personal testing, **not** a production release. To publish or distribute, use your own protected signing key and release build.

## First launch

1. Allow notifications.
2. Tap **Select Telegram Images folder** and select the folder in Android's system folder picker (Storage Access Framework). On some Samsung/Android versions Telegram images may be under `Pictures/Telegram` or `Android/media/org.telegram.messenger/Telegram/Telegram Images`, rather than `Internal Storage/Telegram/Telegram Images`. Android's folder picker may not expose every location; select a user-accessible image folder.
3. Tap **Scan now** and later **Refresh status**. A periodic background scan is scheduled approximately every 15 minutes (not guaranteed exact; battery optimization and Android scheduling apply).
4. Add or remove watched products. Default: `Dreame X60 Pro Ultra`.
5. Keep Telegram configured to **download/save the relevant images locally**. The app cannot read images that Telegram has not saved to a selectable folder.

## Capabilities and limitations

- Bundled Google ML Kit **Latin** OCR. English model names and numbers work best; **Hebrew OCR is not supported in this prototype**. Hebrew branch names and price labels may be missed.
- The matching algorithm checks for **all words** of the requested model anywhere in the recognized image text. It does not understand product photos without readable text, synonyms, exact ordering, or ambiguous ad layouts.
- Price extraction uses simple patterns and is **unverified**; if several products/prices appear, it may attach the wrong price. The app does not reliably extract a branch or publication date.
- Reads the latest **150** images in the selected folder per pass. Already-scanned files are skipped; failed OCR files retry. It does not recurse into subfolders.
- No guaranteed instant notifications. Android WorkManager periodic scheduling has a 15-minute minimum and can be deferred.
- Notifications show the product and possible price and an OCR excerpt. They **do not attach the source image** in v0.1.
- On-device processing means images stay on the device. ML Kit's bundled recognizer is a Google library, but the app itself makes no image-upload requests.
- This project has **not been built or tested on a physical Android device** in this environment. GitHub Actions is the first compilation check.

## Technical design

- Kotlin, Android SDK 35, min SDK 26
- Android Storage Access Framework (persisted read-only tree URI)
- AndroidX WorkManager periodic + manual one-time scan
- Bundled Google ML Kit text-recognition (Latin)
- Local SharedPreferences for watched products, processed-image fingerprints and status
- Android notification channel for product matches

## Privacy

No internet permission is requested. Folder access is scoped to the selected folder; no broad storage permissions. The app does not sync with ChatGPT and cannot automatically push alerts into this chat. Alerts are standard Android notifications.

## Future improvements

- Offline Hebrew OCR, with a compatible maintained Android engine and bundled Hebrew trained data
- Image thumbnails and history screen
- Structured offer extraction with per-product price associations and branch identification
- Faster file-change detection where Android permits
- More robust deduplication and handling of very large folders
- Instrumented tests and signed release APK
