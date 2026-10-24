# ProductWatcher — Telegram automatic monitoring

This patch is intended to be applied over the existing `tomersaporta/ProductWatcher` repository. It includes the previous UI, Telegram photo OCR, and foreground-service patches in one coherent set.

## User flow
1. Open ProductWatcher and grant Android notification permission.
2. Open Telegram settings, enter Telegram API ID and hash once, and complete login.
3. Choose **קונים באושר** from the real channel list. Monitoring starts automatically.
4. The app catches up on recent channel posts and listens for new messages while TDLib is connected.
5. Photo files are downloaded through TDLib; Google ML Kit Latin OCR runs on-device. Matching offers trigger local notifications.
6. A persistent Android notification indicates when the foreground service is active. Stop monitoring in the Telegram screen.

## Architecture and failure handling
- TDLib `UpdateNewMessage` handles live events.
- `GetChatHistory` paginates backwards up to 10 pages per catch-up, plus a first-page retry of previously failed OCR.
- `DownloadFile` is asynchronous; `UpdateFile` completion is handled before OCR.
- Message-level processed flags are written only after successful OCR; duplicate notifications are suppressed.
- `TelegramMonitorService` runs a foreground service with 5-minute catch-up checks.
- `TelegramCatchupWorker` is scheduled with WorkManager at a minimum 15-minute period and retries when Telegram is unavailable. This is not an exact interval.
- `MonitorBootReceiver` re-registers periodic catch-up after reboot without illegally launching a `dataSync` foreground service at boot.
- The dataSync foreground service has a limited Android 15+ time budget; it stops on timeout. Periodic catch-up is a best-effort fallback.

## Important limits
- **Not guaranteed 24/7**: Android Doze, battery restrictions, force-stop, missing connectivity, WorkManager scheduling and dataSync time limits can delay detection. An always-on external process/server would be needed for stronger uptime guarantees; this patch keeps data local on the phone.
- Latin ML Kit OCR may miss Hebrew branch names or distort the model name. Price/branch extraction is heuristic and must be treated as unverified.
- Channel history is limited to 1000 posts per catch-up. A prolonged outage can exceed this bound.
- Notifications require Android notification permission. No notifications after user force-stop until the app is launched again.
- TDLib API credentials are stored in private app SharedPreferences, not in source control; they are not Android Keystore encrypted.
- No live device integration tests or Android APK build were run in this environment.

## Install
Upload the ZIP to the root of the GitHub Codespaces checkout, then:

```sh
unzip -o ProductWatcher-Complete-Monitor.zip
git add app/ README-AUTOMATION.md
git commit -m "Implement automatic Telegram OCR monitoring and catch-up"
git push origin main
```

Check the GitHub Actions build logs. Install the generated APK, verify Telegram login, channel selection, one matching image, one nonmatching image, and behavior after screen off/reboot.
