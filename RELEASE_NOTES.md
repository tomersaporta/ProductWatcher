# Reliability update — 24 October 2026

## User-visible changes

- OCR price association is per-model using layout, not the first price in a post.
- Model matching tolerates spaces, punctuation, OCR confusion of X60 and "UltraComplete".
- X60 Ultra matches Complete, but not Pro or Max; X60 Pro Ultra is matched separately.
- Auto-start preserves explicit Stop and reopens the foreground monitor when possible.
- Background scans include ALL monitored Telegram channels.
- Historical rescan persists a cursor and never launches 10,000 pages simultaneously.
- Recent 100 posts are checked every cycle; missed gaps are paged separately.
- Last OCR excerpt, per-product report, channel counts and history progress are visible and refresh without pressing Refresh.
- New product requests a full rescan automatically, and users can request one manually.
- Three-image OCR concurrency cap; TDLib download timeout and bounded failed-ID retries.
- Successful OCR can delete Telegram's downloaded cache copy (setting can turn off).
- Separate Android notification IDs for different models in the same post.
- Removed obsolete local folder scanner and obsolete share receiver declaration.
- Regression tests added to GitHub Actions.

## Caveats

Build still needs to run in GitHub Actions. This update is a project/patch ZIP, not an installable APK. No claims of end-to-end Telegram OCR success can be made until an APK is installed and the channel image is tested on-device.
